# Worker: PPU Compilation Optimization

## Mission

Implement `plan.md` on `rdr-adreno750-fastpath-validation` and reduce **Install PPU + Runtime/PRELAUNCH PPU preparation wall time** on Android.

Keep this task short. Measure first, make only high-value changes, measure again, then stop.

## Hard limits

- Use games already available on the connected device. **Do not download games.**
- Benchmark at most **3 titles**: small/medium/large if available. Prefer `BLUS30758` for the large title if present.
- Do not wipe app data, firmware, saves, shader caches, or game data.
- Clear/rename only the selected title's PPU object cache when a true cold PPU run is required.
- Maximum **3 implementation rounds**.
- Intermediate validation: **1 representative large title** only.
- Final validation: one cold run on the **same titles used for baseline**.
- Do not perform a long compatibility matrix or long gameplay sessions.
- Do not touch unrelated GPU/Turnip/RDR rendering code.
- Do not silently update the RPCSX submodule to current upstream.

## 0. Freeze the baseline identity

Before editing code, record:

```bash
git branch --show-current
git rev-parse HEAD
git -C app/src/main/cpp/rpcsx rev-parse HEAD
sha256sum patches/rpcsx-submodule-changes.patch
adb shell getprop ro.product.model
adb shell getprop ro.soc.model
```

Review anchor before these documentation commits was Samba `afee167330b68e7001150d9e96a436ced9499cfe`, with RPCSX gitlink `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`.

The public RPCSX repository currently does not resolve that pinned SHA through GitHub's normal commit API. If the local checkout already contains the pinned/patched core, use it. **Do not replace it with RPCSX master to make the build easier.** If the exact baseline core cannot be reconstructed, report that explicitly instead of changing the comparison baseline.

Read before implementation:

- `docs/plans/ppu-compilation-optimization/plan.md`
- `AGENTS.md`
- `.agents/skills/sambas3-device-test/SKILL.md`
- `.agents/skills/sambas3-game-launch/SKILL.md`
- `.agents/skills/sambas3-logs/SKILL.md`
- `PpuRuntimeOrchestrator.kt`
- `PpuInstallOrchestrator.kt`
- `PpuBatchWorkerService.kt`
- `PpuBatchPolicy.kt`
- `patches/rpcsx-submodule-changes.patch`

## 1. Baseline before code changes

### Pick the titles

Inspect the games already registered/available on the connected device. Pick at most three. Do not spend time finding a perfect matrix.

### Build and run the current branch

Use the existing project/device workflow. Build the same configuration that will be used after optimization.

### Cold PPU benchmark

For each selected title:

1. Preserve the current PPU cache if useful, then clear/rename **only that title's PPU object cache**.
2. Reset only the PPU readiness/session state necessary to force real INSTALL + PRELAUNCH work.
3. Clear logcat.
4. Start PPU preparation through the real Home/Launch flow.
5. Capture from the first INSTALL session start through PRELAUNCH `FINAL_COMPLETED`.
6. Start the title once after preparation and confirm already-prepared PPU objects are loaded from cache rather than generating another long PPU compilation wall.

One valid run per title is enough. Repeat only an obvious failed/outlier run.

### Required baseline table

Create a working table with:

| Title | Modules | Install PPU | Runtime PPU | Combined | Batches | Objects/s | Peak RSS | Maps | LLVM threads |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|

Use `S3PPUSESSION`, `S3PPUBATCH`, `S3PPUPROG` and existing timestamps.

Also calculate these gaps for the representative title:

- parent bind/start -> worker ready
- worker ready -> native batch begin
- native batch begin -> native batch terminal
- native terminal -> worker death
- worker death -> next bind

If existing logs cannot separate the native cost, add **summary-only** `S3PPUBENCH` timers around:

- prepare/load/analyse
- cache/workload discovery
- translation/codegen/cache write
- object load/link if applicable

Do not add per-instruction or noisy per-function tracing.

## 2. Decide from the baseline

Do not implement every idea blindly.

- If worker/process gaps are a meaningful share of total time: do **Round A / P0** first.
- If native compilation dominates and CPU scheduling looks poor: do **Round B / P1**.
- If codegen plus memory pressure/batch recycling dominates: do **Round B / P2**.
- P2 is preferred over speculative compiler-option changes when MCJIT loading/linking is visible in the critical path.

## 3. Round A — remove avoidable batch latency

Current code has clean-path fixed delays around process recycling.

Implement the smallest safe change:

- remove the unconditional `delay(150)` in Runtime/PRELAUNCH after an expected clean worker exit,
- remove the unconditional `delay(200)` in INSTALL after an expected clean worker exit,
- inspect `PpuBatchWorkerService.scheduleExit()` and reduce/remove its `150 ms` delay only if the Binder result callback is reliably delivered and `ExpectedAfterResult` remains correctly classified,
- leave actual crash/bind retry backoff intact.

Then:

1. build,
2. cold-run the representative title once,
3. compare combined wall time and batch handoff gaps,
4. verify cancellation/result delivery and first-start cache use.

Keep the change only if it is measurably better and stable.

## 4. Round B — choose P1 or P2 from evidence

### P1: compile scheduling

The core already creates parallel `PPUW.*` workers, so do not add another thread pool.

For the representative title, test a tiny matrix only:

1. current policy,
2. one bounded LLVM worker count; start with **5** on the Snapdragon/Adreno 750-class device if no explicit user override exists,
3. one adjacent count only if result #2 is ambiguous.

Also A/B `low_prio(-1)` versus normal priority **only for blocking INSTALL/PRELAUNCH PPU workers**.

Requirements:

- scope the policy to compile origin; do not overwrite the user's global setting permanently,
- do not change normal emulator PPU/SPU/RSX thread priorities,
- compare wall time, peak RSS and any thermal collapse/crash signal.

Keep only the winning simple policy. Do not build a complex CPU-topology scheduler in this task.

### P2: cache-only object emission

This is the main core candidate if native codegen/load is dominant.

For INSTALL/PRELAUNCH only, prototype a path that:

1. keeps the existing PPU analyser and `PPUTranslator`,
2. uses the same target triple, CPU/features, data layout, relocation/code model and codegen level,
3. emits the relocatable LLVM object directly,
4. stores it using the same object-cache naming/compression/accounting rules,
5. **does not load/link the emitted object through RuntimeDyld and does not allocate executable JIT memory in the short-lived precompile worker**,
6. leaves the normal runtime `jit_compiler::add()` path unchanged.

Likely touched core areas are `rpcs3/util/JIT.h`, `rpcs3/util/JITLLVM.cpp`, `rpcs3/Emu/Cell/PPUThread.cpp`, plus Samba compile-origin plumbing if needed. Regenerate `patches/rpcsx-submodule-changes.patch` correctly after core edits.

Validate immediately on one representative title:

- cache objects are produced,
- the normal unmodified boot cache-load path accepts them,
- no symbol/link/cache errors appear,
- first START does not recompile the same objects,
- native batch time and/or RSS/map growth improves.

Only if peak memory clearly falls, cautiously test a larger batch such as 24 or 32. The existing 16-object limit has real prior OOM/SIGABRT evidence. Do not increase it merely to reduce process count.

If cache-only emission turns into a large rewrite or cache compatibility is uncertain, stop that experiment and keep the measured P0/P1 gains.

## 5. Round C — conditional only

Do one of these only if the profiler still shows a clear remaining bottleneck and the task is still small.

### P3: PPU-only codegen level

Current MCJIT codegen uses LLVM `Aggressive` (O3-equivalent). A/B `Default` (O2-equivalent) for **PPU cache generation only**.

- Do not globally downgrade SPU/other JIT paths.
- Ensure cache identity cannot silently mix incompatible compiler profiles if that matters to the current cache key.
- Compare cold compile time on the representative title.
- Do a short fixed-scene FPS/frame-time sanity check after boot.
- Keep O2 only for a meaningful compile win with negligible sampled runtime regression.

Do not test O0/O1/FastISel in this worker pass unless O2 is clearly valuable and everything else is already complete.

### P4: repeated scan/analyse overhead

If repeated worker startup causes large repeated scan/analyse cost, implement only the smallest of:

- deterministic session manifest/cursor so the next worker resumes discovery, **or**
- allow one worker to consume multiple bounded 16-object steps while RSS/maps stay below a conservative threshold, then recycle it.

Do not implement both. Keep process isolation as the OOM safety boundary.

## 6. Final validation

After the last kept implementation:

1. build Standard Debug and the native core as required,
2. run focused PPU unit tests affected by the change,
3. perform one cold PPU benchmark on the same baseline titles,
4. perform one first-start cache smoke per title.

Do **not** run the whole device/game matrix.

If one result is obviously an outlier (>10% unexplained swing or interrupted run), repeat only that title once.

If the PPU codegen level changed, add one short fixed-scene runtime sanity comparison. No long gameplay test is needed here.

## 7. Required result file

Write:

`docs/results/ppu-compilation-optimization-result.md`

Include:

| Title | Baseline Install | Optimized Install | Δ | Baseline Runtime | Optimized Runtime | Δ | Baseline Combined | Optimized Combined | Total Δ | Peak RSS before/after | Batches before/after |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|

Then list briefly:

- exact Samba commit/build tested,
- local RPCSX HEAD and patch SHA-256,
- device/SoC,
- optimization(s) kept,
- experiment(s) rejected and why,
- first-start cache result,
- focused tests/build result,
- any remaining bottleneck visible in `S3PPUBENCH`.

Report percentage change as:

`(baseline - optimized) / baseline * 100`

## Stop conditions

Stop and report instead of continuing if:

- two consecutive experiments improve combined cold PPU time by less than about **5%**,
- cache-only objects are not accepted by the normal runtime cache loader,
- OOM/crash frequency rises,
- a compile-time win creates an obvious runtime PPU performance loss,
- the next step needs a broad ORC-JIT migration or architecture rewrite,
- the selected available games already demonstrate a clear stable win.

The objective is a measured improvement, not exhaustive compiler experimentation.
