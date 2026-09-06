# Plan: PPU compile truth on Home + Launch cards

## Task Summary

Stop reporting Install PPU / Runtime PPU as **done** unless isolated Kotlin
batches actually compiled cache objects. Direct ISO registration currently
writes `PreRuntime=READY` + `Runtime=IDLE_AFTER_COMPILE` so Home and Launch
Center show Ready and enable START. The first real boot then compiles inside
`RPCSXActivity`, which the cards never show.

Compile before START. Show live percent on the Home game card and the Launch
Center card. START stays disabled until both phases are terminal.

Do not change default PPU codegen, LLVM version, or Turnip/RDR GPU work.

## Research Sources

- `DirectIsoManager.kt:126-158` — fakes READY + IDLE_AFTER_COMPILE; `reconcileLaunchReadiness()` re-applies that lie on every MainActivity load.
- `PpuUserActionDecision.kt:49-51` — `READY` + `IDLE_AFTER_COMPILE` maps to START.
- `LaunchPpuPresentation.kt:115-120,194-199` — those states render as “Ready”.
- `GamesScreen.kt:1698` — `showCompileOverlay` omits `usingInstallPpu`, so PREPARE PPU on an already-listed card has no overlay.
- `GamesScreen.kt:761-787` — focused Home info shows title + path only; no PPU rows.
- `GameLaunchCenter.kt:179-180` — launcher already has `PpuPhaseRow`; it is truthful only if readiness is truthful.
- `_rpcsx_compileRuntimePpuBatch` — rejects non-directories (`invalid_game_path`). Direct ISO path is `direct_iso/<TITLE>`.
- `_rpcsx_compileInstallPpuBatch` — `locateEbootPath()` treats a regular file as EBOOT. An ISO is not ELF.
- `System.cpp:1483-1494` — real boot already mounts ISO via `iso_dev` + `vfs::mount("/dev_bdvd")`.
- `System.cpp:521-529` — `PreparePpuOnly` requires `fs::is_dir`, so PRELAUNCH cannot compile a disc image.
- Last shipping commit `17ed82b` — “Direct ISO cards no longer get stuck on PPU preparation” by manufacturing Ready.

## Current Architecture (broken)

```
Direct ISO register
  → PpuReadinessStore READY + IDLE_AFTER_COMPILE     // lie
  → Home / Launch: Install PPU Ready, Runtime PPU Ready, START enabled
  → user START
  → RPCSXActivity boots /proc/self/fd/N
  → native ppu_initialize compiles for real (invisible to Home/Launch cards)
```

Installed-directory titles can still compile on the isolated workers, but Home
does not draw Install-PPU overlay unless an import progress row exists.

## Target Architecture

```
Any listed title (installed dir OR Direct ISO)
  → readiness is NOT_DONE / NOT_STARTED until batches finish
  → Home card + Launch Center show PREPARE PPU
  → PREPARE / hint X starts Kotlin INSTALL batches, then PRELAUNCH batches
  → each batch: :ppu_compile process, ISO mounted like BootGame when needed
  → CompileProgressBridge drives Home overlay + Launch PpuPhaseRow percent
  → all_complete → READY then IDLE_AFTER_COMPILE
  → START enabled
  → RPCSXActivity boot should hit cache (no first-run compile wall)
```

## Affected Components

| File | Change |
|---|---|
| `docs/plans/ppu-card-compile-truth-worker.md` | this plan |
| `iso/DirectIsoManager.kt` | stop manufacturing Ready; reset fake Ready when cache is empty |
| `ppu/PpuCompilePathResolver.kt` | resolve Direct ISO `sourceUri` for workers; materialize `/proc/self/fd/N` in `:ppu_compile` |
| `ppu/ImportPpuPreparationCoordinator.kt` | pass resolved compile path, not `direct_iso/<TITLE>` |
| `ppu/PpuBatchWorkerService.kt` | open ISO in the worker process before native compile |
| `ui/games/GamesScreen.kt` | Home overlay includes Install PPU; focused card + GameCard show phase rows |
| `ui/games/launch/LaunchPpuPresentation.kt` | shared `phaseStatusText()` |
| `ui/games/launch/GameLaunchCenter.kt` | progress bar on compiling rows |
| `rpcs3/Emu/System.h` + `System.cpp` | `ResolvePpuCompileRoot()`; `PreparePpuOnly` accepts ISO |
| `rpcsx-android.cpp` | INSTALL batch mounts ISO and walks `fs::dir` (not `std::filesystem`) |
| tests | readiness policy, path resolver, overlay predicate, presentation |

## Implementation Steps

1. **Truthful Direct ISO readiness.** Register with `NOT_DONE` / `NOT_STARTED`. `reconcileLaunchReadiness()` resets READY/IDLE when `cache/cache/<TITLE>` has no objects.
2. **Resolve a real compile path.** Main process maps `direct_iso/<TITLE>` → persistable `sourceUri` or readable file. Worker opens that URI and passes `/proc/self/fd/<n>` into native.
3. **Native ISO compile root.** Same mount as `BootGame`: `iso_dev` + virtual `/dev_bdvd`. `PreparePpuOnly` uses that directory. INSTALL batch finds EBOOT with `GetElfPathFromDir` and enumerates modules with `fs::dir`.
4. **Home card compile UI.** `showCompileOverlay` is true for import, Install PPU, PRELAUNCH, or in-title runtime compile. Focused Home info and the game card itself render Install PPU / Runtime PPU lines from `LaunchPpuPresentation`.
5. **Launch Center.** Keep existing rows; add a determinate bar while `PpuPhaseState.Compiling`. START remains gated on `GameLaunchAvailability.Ready`.
6. **Tests + patch.** Unit tests for the lie-removal and overlay predicate. Regenerate `patches/rpcsx-submodule-changes.patch` from the dirty RPCSX tree.

## Acceptance Criteria

- [ ] Direct ISO register does **not** write READY or IDLE_AFTER_COMPILE.
- [ ] After process restart, a Direct ISO title with empty `cache/cache/<TITLE>` shows Needs preparation / PREPARE PPU, not Ready.
- [ ] PREPARE PPU on Home or Launch starts isolated batches; both cards show Compiling N% (not Ready).
- [ ] START stays disabled until Install PPU Ready **and** Runtime PPU IDLE_AFTER_COMPILE from a real `all_complete`.
- [ ] First START after that terminal should not open a long in-activity PPU compile wall for modules already in cache.
- [ ] Installed-directory titles keep working; overlay now also appears for Install PPU without an import row.
- [ ] `./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest` pass.

## Risks

| Risk | Mitigation |
|---|---|
| ISO `pread` broken on some SAF fds | Worker uses `DirectIsoSession` (lseek+read proven on boot) |
| `std::filesystem` cannot see virtual ISO dirs | INSTALL batch walks `fs::dir` |
| Partial cache looks “ready” | `all_complete` remains the only READY writer; empty cache resets Direct ISO lies |
| Boot still compiles late PRX | Out of scope; cards must still show any PRELAUNCH/INSTALL work before START |

## Handoff

Worker implements this file. Do not re-fake Direct ISO Ready to unstick START.
If ISO mount fails, mark FAILED and keep PREPARE/RETRY visible.
