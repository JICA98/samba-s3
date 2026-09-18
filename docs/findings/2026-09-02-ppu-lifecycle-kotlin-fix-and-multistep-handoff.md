# Findings: PPU lifecycle Kotlin fix + Demon's Souls install crash → multistep handoff

**Date:** 2026-09-02  
**Branch:** `recovery/ingame-menu-fix`  
**Device:** `7d6afed8` (OPD2403)  
**Title:** Demon's Souls `BLUS30443`  
**Worker plan:** Kotlin-only shipping for crash / wrong Retry / post-compile black screen

---

## 1. Verdict (read this first)

| Layer | Status |
|---|---|
| Kotlin wrong-phase / headless / readiness / first-frame | **Shipped** (this commit) |
| Unit tests + `assembleStandardDebug` | **PASS** |
| Device Pass A/B (two full remove→boot cycles) | **BLOCKED** |
| Remaining crash | **Native Scudo OOM in LLVM JIT during install PPU** |
| Next work | **Reviewer handoff required: multistep install PPU architecture** |

**Do not treat this commit as “Demon's Souls compile crash fixed.”** It fixes the Kotlin lifecycle that starts the wrong compiler phase and marks Runtime ready without a real frame. The mid-install SIGABRT is a separate native memory/JIT problem that needs a multistep (or native JIT-memory) design.

---

## 2. What we shipped (Kotlin)

### Confirmed defects fixed
1. **PREPARE/RETRY manufactured `PreRuntime READY` and started headless Runtime PPU** for `NOT_DONE` / `INVALIDATED` / `FAILED`.
2. **Home routed NeedsPreparation and Failed through the same headless path.**
3. **Post-install auto-chained headless `prepareRuntimePpu`.**
4. **`IDLE_AFTER_COMPILE` from headless was treated as launch-ready** without first-frame proof.
5. **Compile watchdog could clear UI at 100% without a terminal** — now explicitly UI-only and never validates readiness.
6. **Fresh boot treated `BootResult.NoErrors` as enough** — now requires stable PixelCopy samples (`S3BOOTFRAME`) before `validatedByRealBootFrame`.

### Architecture after this commit
```text
IMPORT → Install PPU (PrecompilerService / FGS 3000)
      → real install terminal
      → PreRuntime=READY, Runtime=NOT_STARTED
      → automatic headless SUPPRESSED
      → Home / Launch Center: "Will prepare on start"
      → user START & PREPARE
      → RPCSXActivity (real Surface)
      → Runtime PPU if needed (FGS 2000)
      → stable first frame → validatedByRealBootFrame=true
```

Headless `prepareRuntimePpu` remains in the JNI API for diagnostics only (`startHeadlessForDiagnostics`); normal Home/Launch/post-install paths never call it.

### Key files
- `ppu/PpuUserActionDecision.kt` — phase action matrix
- `ppu/ImportPpuPreparationCoordinator.kt` — no READY manufacture; post-install suppress headless
- `PpuReadinessStore.kt` — `validatedByRealBootFrame` / `readinessVersion`
- `ppu/FreshBootFrameValidator.kt` + `RPCSXActivity.kt` — first-frame SM
- `CompileWatchdogLogic.kt` + `CompileProgressBridge.kt` — UI-only watchdog
- Launch Center / GamesScreen presentation — START & PREPARE / RE-IMPORT

### Tests
- `PpuUserActionDecisionTest`, `NoHeadlessNormalPathTest`, `FreshBootFrameValidatorTest`
- `PpuReadinessValidationMigrationTest`, `CompileWatchdogLogicTest`
- Updated Launch Center / eligibility / recovery tests  
`./gradlew :app:testStandardDebugUnitTest` and `assembleStandardDebug` succeeded.

---

## 3. Device evidence (7d6afed8 / BLUS30443)

### Pass A (partial)
1. Pre-remove Launch Center already showed **Install Ready / Runtime Will prepare on start / START & PREPARE** (legacy IDLE without validation marker).
2. Remove game succeeded; library entry + `ppu_state` entry cleared.
3. Re-import from `/sdcard/PS3/Demons Souls (USA)/Demon's Souls (USA).iso`.
4. Install PPU / FGS **3000** started; progress reached **module 99 of 233 (~42%)**.
5. **`headless_hits=0`** for the entire attempt (Kotlin fix held).
6. Process died: `APP CRASH(NATIVE)` / `SIGABRT` on `PPUW.1.4`.

### Crash classification (decisive)
```text
Abort message: 'Scudo ERROR: internal map failure (error desc=Out of memory)'
Thread: PPUW.1.4
Frames: jit_compiler::add → llvm::MCJIT::generateCodeForModule → RuntimeDyldELF::…
Origin: INSTALL PPU (PrecompilerService / CompilationQueue), NOT headless Runtime
```

Device had ~8 GB `MemAvailable` at the time — this is consistent with **Scudo map / VA / fragmentation / concurrent JIT arena pressure**, not a simple “phone out of RAM” story.

### What this proves
- Wrong-phase headless routing is **not** required to reproduce Demon's Souls install crash.
- Removing headless from the normal path is necessary but **not sufficient** for AC4.
- Remaining fix is **outside the Kotlin-only worker boundary**.

---

## 4. Why reviewer handoff is required (multistep)

### Recommended next architecture: multistep install PPU
Goal: finish 233 modules for large titles **without a single process holding peak LLVM/JIT memory for the whole queue**.

Sketch (for the next worker / design review — **not implemented here**):

```text
Install PPU job
  → discover module/executable manifest (already partly present)
  → compile in bounded STEPS (e.g. N modules or M MiB JIT budget per step)
  → after each step:
        persist cache objects already written
        release / reset FXO+VM+JIT arena (native quiescence)
        optionally recycle process OR hard-reset emulator core
        update Kotlin progress: step k/K, modules done
  → only after final step: emit install terminal → PreRuntime READY
```

### Why this needs design review (not a drive-by patch)
1. **Native ownership:** Scudo abort is inside `librpcsx-android.so` LLVM paths; Kotlin cannot free JIT arenas it does not own.
2. **Lifecycle races:** install terminal, FGS 3000, `CompilationQueue`, and progress callbacks must survive step boundaries without duplicate jobs or fake READY.
3. **Cache correctness:** partial caches must be resume-safe and fingerprint-valid (`ppu_manifest` / ABI / llvm_cpu / patches).
4. **Product UX:** user must see truthful progress across steps; force-stop mid-step must recover to FAILED/retryable, not READY.
5. **Scope:** original worker forbade native edits; multistep almost certainly needs Kotlin orchestration **plus** native compile-budget / teardown hooks.

### Out of scope for this handoff package
- Implementing multistep in this commit
- Native/C++/RPCSX submodule edits
- Claiming Demon's Souls install crash is fixed

---

## 5. Read-only native notes (headless path)

Inspected `PreparePpuOnly` / `prepareRuntimePpu` (no edits):
- Creates VM (`vm::init`), FXO PPU base, progress server, runs `ppu_precompile`.
- Teardown: `g_fxo->reset()` → `vm::close()` → `Stopped`.
- Compile workers are joined before return.
- **`jit_runtime::finalize()` is NOT called** on the headless path (unlike full Kill).
- Reinforces: even if headless returns “Stopped”, process-global JIT arena state may remain — another reason Runtime validation must be real-boot + first-frame.

---

## 6. Scope proof

Task-authored shipping edits are **`.kt` only**.  
RPCSX gitlink unchanged: `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`.  
No Manifest / XML / Gradle / CMake changes in this commit.

---

## 7. Handoff checklist for the next worker

- [ ] Design multistep install PPU (Kotlin orchestration + native step boundary API).
- [ ] Prove step teardown actually returns Scudo/JIT memory (measure PSS/RSS + map failures).
- [ ] Resume-from-partial-cache for BLUS30443 after crash at ~99/233.
- [ ] Re-run worker Pass A + Pass B on `7d6afed8` with 0 process crashes.
- [ ] Keep this commit’s phase-aware / no-headless / first-frame rules intact.

---

## 8. Suggested commit message (already applied if pushed)

```text
fix(ppu): phase-aware lifecycle; no headless on normal paths

Stop manufacturing PreRuntime READY and chaining headless Runtime PPU
from Home/Launch/post-install. Validate Runtime only after real boot
first-frame. Document Demon's Souls install Scudo OOM as native
multistep handoff.
```

---

## 9. Critical Invariant: Checkpoint 97a1909 / 3415585 Watchdog Contract & Game Loading Page Duplicate Compile Prevention

### The Regression Pattern (Do Not Repeat!)
A recurring regression occurred across multiple iterations where:
1. PPU compilation and Runtime PPU preparation completed successfully in the Launcher Card / Home (`PpuInstallOrchestrator` -> `PreRuntime=READY`, `PpuRuntimeOrchestrator` -> `Runtime=IDLE_AFTER_COMPILE`).
2. When starting the game via START, the game loading page (`RPCSXActivity` transition overlay) still displayed PPU compilation ("Preparing game… Processing game modules") and blocked the user, recompiling or waiting in `WaitingForRuntimePpu`.

### Root Cause 1: Premature Watchdog Clearing of `ppuActive` (Checkpoint 97a1909 / Commit 3415585)
In `CompileProgressBridge.kt`, `clearStuckPpuUiIfComplete` was erroneously modified to set `_state.value = cur.copy(ppuActive = false, outcome = CompileOutcome.NONE, jobId = jobId)` and clear `ppuJobId = null`.
- When the watchdog cleared `ppuActive` before the authentic native `COMPLETED` terminal event was handled by the main thread, the subsequent `COMPLETED` event was dropped as an unknown job (`ppuJobId != ev.jobId`).
- `outcome` remained `NONE`, preventing `PpuReadinessStore` from persisting `IDLE_AFTER_COMPILE` or `READY`.
- The launcher card or boot coordinator then detected incomplete readiness and re-requested compilation, triggering duplicate PPU compilation in the loading page upon game launch.
- **Invariant:** `clearStuckPpuUiIfComplete` MUST keep `ppuActive = true` at 99% with `"Verifying cache… waiting for compiler process"` and PRESERVE `ppuJobId` until the native `COMPLETED` event arrives to transition `outcome` to `COMPLETED`.

### Root Cause 2: RPCSXActivity Transition Overlay Regressions & Clobbered Text Routes
1. **The Clobbered Loading Screen ("Waiting for game output"):**
   In commit `2de3bd1`, an `else if (!freshBootFrameValidated)` branch was added to show `"Starting game… - 100% / Waiting for game output"`. Because StateFlow emits an initial state where `progress.ppuActive == false`, this immediately clobbered `"Preparing game…"` on frame 0, permanently displaying "Starting game… - 100% Waiting for game output" before the emulator even initialized or loaded modules.
2. **The `isTitlePrecompiled` Blindness Hazard:**
   An attempt was made to suppress PPU loading overlays when `isTitlePrecompiled(titleId) == true`. However, on a fresh install or when firmware SPRX modules (e.g. `libfont.sprx`, `libfreetype.sprx`, `libhttp.sprx`, `libpngenc.sprx`) have not yet been compiled, native RPCSX during game boot DOES compile 70+ firmware modules (taking up to 5 minutes). Suppressing the UI when `isTitlePrecompiled == true` blinded the user: the loading screen remained frozen at "Starting game… - 100% Waiting for game output" while the notification bar showed 78 files being compiled in the background.
3. **The Definitive Contract for Loading Screen Handover:**
   - Whenever `progress.ppuActive` is true, the transition overlay MUST display the live module compilation progress (`updateTransitionProgress(messages..., progress.ppuPercent)`).
   - Use `hadPpuWork`: `"Starting game… - 100% / Waiting for game output"` must ONLY be set after PPU work has actually taken place and completed (`hadPpuWork && !progress.ppuActive`). It must NEVER execute on initial boot before work has even begun.
   - When `freshBootFrameValidated` arrives (stable first frame confirmed), the transition overlay is cleanly dismissed via `releaseFreshBootTransitionOverlayIfVisible()`.
   - `hasBlockingCompileWork()` in `FreshBootFrameValidator` must accurately check `ppuActive` to allow PPU compilation to complete before frame validation begins.

### Root Cause 3: FGS Notification Panel Missing Progress & Time Remaining
Foreground services (`PpuBatchWorkerService` FGS 2003 and `CompilationMonitorService` FGS 2000) previously showed static indeterminate notifications ("Compiling PPU", `setProgress(0, 0, true)`):
- `PpuBatchWorkerService` now builds and updates notifications dynamically with module counts (`done/total`), actual progress percentage (`setProgress(100, percent, false)`), and smoothed time remaining from `PpuRemainingTime.progressLine(msg, remaining)`.
- `PpuBatchWorkerConnection` persists and forwards the latest progress across isolated process recycling so batch transitions do not flicker back to indeterminate progress.
- `CompilationMonitorService` now observes `installState` alongside `prelaunchState` and `state`, ensuring continuous live notification reporting across all PPU preparation phases.
