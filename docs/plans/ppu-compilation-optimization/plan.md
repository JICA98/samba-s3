# PPU / Runtime PPU Compilation Optimization Plan

## Goal

Reduce the wall-clock time users spend on **Install PPU** and **Runtime PPU / PRELAUNCH** preparation on Android without trading away boot reliability, cache correctness, or meaningful in-game PPU performance.

This is a measurement-first plan. Do not do a long compatibility sweep. Benchmark the games already present on the connected test device, make a small number of high-value changes, and rerun the same benchmark.

## Review anchor

- SambaS3 branch reviewed: `rdr-adreno750-fastpath-validation`
- Branch head at review: `afee167330b68e7001150d9e96a436ced9499cfe` (2026-09-07)
- RPCSX gitlink in that branch: `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`
- SambaS3 carries the native core delta in `patches/rpcsx-submodule-changes.patch`.
- The public RPCSX GitHub API no longer resolves that gitlink SHA. **Do not silently update the submodule to RPCSX master.** Baseline and optimized builds must use the same local/patched core. Record the local submodule HEAD and patch SHA-256 before testing.

## Code-review findings

### 1. SambaS3 intentionally restarts a worker process every small batch

`PpuRuntimeOrchestrator` and `PpuInstallOrchestrator` run each bounded batch in `:ppu_compile`, wait for that process to die, then bind a new worker. `PpuBatchPolicy.DEFAULT_BATCH_SIZE` is 16 objects.

This architecture was added for a real reason: the native patch records OOM/SIGABRT evidence on large titles, including a failed 32-object step. Keep 16 as the safety baseline until memory measurements prove another design safe.

However, every batch currently repeats process startup, native bootstrap, path/ISO setup, executable load/analyse, cache discovery and shutdown. There are also fixed delays around clean worker recycling (`150 ms` worker exit delay plus `150 ms` PRELAUNCH / `200 ms` INSTALL parent delay). Those fixed costs scale with the number of batches.

### 2. The core already parallelizes PPU module compilation

Current RPCSX PPU code creates a `PPUW.*` thread group and uses up to `min(workload, get_max_threads())`. `get_max_threads()` uses the configured `llvm_threads` limit, otherwise all detected hardware threads.

Therefore do **not** spend the first pass adding another Kotlin/native thread pool. First benchmark the existing worker count. On heterogeneous mobile CPUs, “all cores” can be slower than a smaller performance-core-heavy set because LLVM compilation is CPU and memory intensive.

### 3. PPU compile workers are deliberately lowered in priority

The RPCSX PPU worker path uses `thread_ctrl::scoped_priority low_prio(-1)`. That is reasonable for background desktop precompilation, but SambaS3’s PRELAUNCH path blocks the user from START until compilation is complete. On Android this policy is worth an A/B test: foreground blocking PPU preparation should not automatically run as background-priority work.

### 4. AArch64 does not currently run the x64-only `EarlyCSE` pass, but MCJIT codegen is still aggressive

In current RPCSX, the explicit `EarlyCSE` function pass is guarded by `ARCH_X64`; Android/AArch64 does not pay for it. Lowering IR passes is therefore not the first optimization target.

The shared `jit_compiler`, however, builds MCJIT with `llvm::CodeGenOptLevel::Aggressive` (`-O3` equivalent). A PPU-only `Default` (`-O2`) or, later, `Less` (`-O1`) profile may reduce compile time, but it can also reduce generated-code performance. Test this only after orchestration/code-emission improvements, scope it to PPU cache generation, and never globally downgrade SPU or normal runtime JIT by accident.

### 5. Each uncached PPU object creates a fresh `jit_compiler`

The PPU worker constructs a new `jit_compiler` per uncached module and calls `jit.add(module, cache_path)`. `jit.add()` installs an LLVM ObjectCache, calls MCJIT `generateCodeForModule()`, and MCJIT then emits an object **and loads/links it into executable memory**.

For SambaS3 INSTALL/PRELAUNCH worker processes, the product we need is the persistent object cache; that worker exits instead of executing the generated PPU code. This makes a **cache-only object emission path** the most promising core optimization: emit the exact same relocatable object/cache artifact without RuntimeDyld loading/linking and executable-memory allocation.

This should be prototyped, not assumed. The normal emulator boot must still be able to load the produced cache unchanged.

### 6. The existing LLVM object cache is valuable; do not replace it

RPCSX already checks cached PPU objects and MCJIT supports an ObjectCache specifically to avoid recompilation. The problem to solve is cold compilation plus repeated batch/setup/cache-scan overhead, not a new cache format unless profiling proves the existing lookup itself is dominant.

## Baseline first

### Test set

Use **available games only**. Do not download anything for this task.

Choose at most three titles from the connected device:

1. one small/fast PPU set,
2. one medium title,
3. one large title; prefer `BLUS30758` (Red Dead Redemption) if present because this branch already has device evidence for it.

If only one or two games are available, use those. Do not block the task looking for a perfect matrix.

### Cold-run protocol

For each selected title:

1. Record device model/SoC, Samba commit, local RPCSX HEAD, patch SHA-256, build type, LLVM thread setting and game title ID.
2. Preserve or remove **only that title's PPU object cache**. Never wipe app data, firmware, saves, shader caches or game data.
3. Reset only the PPU readiness/session state needed to force real INSTALL + PRELAUNCH work.
4. Start PPU preparation using the same Home/Launch flow a user uses.
5. Capture logcat from first INSTALL session start through PRELAUNCH `FINAL_COMPLETED`.
6. Record once per title unless the result is obviously invalid. Repeat only an outlier or failed measurement.

### Required baseline metrics

Produce a compact table with:

- Install PPU wall time
- Runtime/PRELAUNCH PPU wall time
- combined preparation wall time
- total modules / cached-before / compiled count
- number of worker processes/batches
- effective objects/sec
- peak worker RSS and map count already exposed by `S3PPUBATCH`
- current LLVM thread count
- whether first START after preparation hits cache instead of opening another long PPU compile wall

Also derive these stage timings from existing timestamps or minimal instrumentation:

- parent bind/start -> worker ready
- worker ready -> native batch begin
- native batch begin -> native batch terminal
- native terminal -> worker death
- worker death -> next bind

If native time dominates, add one summary-only `S3PPUBENCH` timer around native prepare/analyse, PPU cache/workload scan, translate/codegen/cache-write, and link/load. Do not log per instruction or per function.

## Optimization order

### P0 — Remove avoidable orchestration latency

Do this first because it is low risk and easy to measure.

- Since the orchestrator already waits for worker death, remove the unconditional `150 ms` PRELAUNCH and `200 ms` INSTALL sleep after a clean expected exit unless a demonstrated race requires it.
- Re-evaluate the worker's `150 ms` delayed `killProcess()` after `onBatchFinished()` returns. Binder callbacks are synchronous; test a much shorter clean-exit handoff or an explicit service/process shutdown path. Preserve callback delivery and expected-death classification.
- Do not change retry backoff for actual bind/crash failures in this pass.
- Avoid duplicate filesystem/session writes inside a batch if profiling shows them on the critical path.

Keep this change if combined cold preparation improves measurably and cancellation/result delivery remains correct.

### P1 — Android PPU compile scheduling A/B

Current PPU workers can use every detected CPU and run at low priority. Test only a tiny matrix on the largest available title:

- baseline current setting,
- one bounded worker-count candidate (start with 5 on the Adreno 750 / Snapdragon-class test device if no explicit user override),
- optionally one adjacent value only if the first result is ambiguous.

Also A/B normal priority versus `low_prio(-1)` **only for blocking INSTALL/PRELAUNCH compile-origin workers**. Do not alter normal emulation-thread priority.

Prefer an origin-scoped compile policy rather than permanently overwriting the user's global `llvm_threads` setting. Do not add a complex topology scheduler in this task unless the simple A/B shows a clear win.

Keep only a setting that improves wall time without increasing OOM/crash rate or causing obviously worse thermal collapse during the same short run.

### P2 — Prototype cache-only PPU object emission

This is the main core optimization candidate.

Add a precompile-only path for `CompileOrigin::INSTALL` / `PRELAUNCH` that:

1. keeps the current PPU analyser and `PPUTranslator`,
2. uses the same LLVM target triple, CPU/features, data layout, relocation/code model and codegen level,
3. emits the relocatable object directly to a buffer,
4. writes it through the same cache naming/compression/accounting rules,
5. **does not** load/link the object into RuntimeDyld or allocate executable JIT memory in the worker process,
6. leaves the normal runtime `jit_compiler::add()` path untouched.

Acceptance for the prototype:

- generated object is accepted by an unmodified normal boot cache-load path,
- cached title boots without recompiling those same objects,
- no PPU symbol/link/cache errors,
- lower native batch time and/or RSS/map growth than baseline.

If this materially lowers memory pressure, then test a larger batch conservatively (for example 24 or 32) on the large title. The previous 32-object OOM evidence applies to MCJIT's load path; it does **not** justify increasing the batch until the cache-only path proves lower peak memory.

If direct object emission becomes a large rewrite or cache compatibility is uncertain, stop and keep the measured P0/P1 gains instead of extending the task.

### P3 — PPU-only codegen latency profile, only if still worthwhile

The current backend uses `CodeGenOptLevel::Aggressive` (-O3 equivalent). If P2 still leaves codegen as the dominant cost, test `Default` (-O2) for PPU cache generation.

Rules:

- make the level explicit and PPU-specific; do not globally change `jit_compiler` behavior for SPU/other JIT users,
- bump/include a cache compatibility discriminator if objects produced by different profiles could otherwise mix,
- compare compile time on one large title,
- do a short fixed-scene runtime sanity comparison (FPS/frame-time plus successful gameplay entry),
- keep O2 only if compile-time gain is meaningful and runtime regression is negligible for the sampled title.

Do not test O0/O1/FastISel in the first worker pass unless O2 is clearly promising and the remaining task is still small. LLVM documents FastISel as intentionally producing poor code quickly; that trade is too aggressive for a persistent emulator cache without evidence.

### P4 — Repeated scan/resume optimization only if profiling points there

Because a fresh process is created every 16 objects, the title/module/cache discovery path can be repeated many times. If `S3PPUBENCH` shows scan/analyse time is a significant fraction of total time, add the smallest safe resume mechanism:

- persist a deterministic module manifest/cursor for the logical PPU session, or
- allow a worker to consume more than one 16-object step while RSS/maps remain under a conservative threshold, then recycle the process.

Do not implement both. Do not remove the process-isolation safety net.

## Fast implementation loop

The worker should do no more than three implementation batches:

1. **P0** orchestration cleanup + one representative cold run.
2. **P1 and/or P2** depending on baseline stage data + one representative cold run.
3. Final same-game baseline matrix using the selected available titles.

P3/P4 are conditional, not mandatory. If P0/P2 already produce a clear win, finish and report rather than continuing to experiment.

## Acceptance criteria

- Same selected titles and cache-reset method used before/after.
- No app-data wipe and no unrelated GPU/Turnip/RDR rendering changes.
- INSTALL and PRELAUNCH both reach truthful terminal completion.
- First START after preparation uses the produced PPU cache; no duplicate long PPU wall for already-prepared modules.
- No new worker crash/OOM on the largest tested title.
- Final report includes before/after wall times and percentage delta per title plus combined/median improvement.
- Any codegen-profile change includes a brief runtime performance sanity check.
- Standard debug build succeeds; run the focused PPU unit tests touched by the change. Do not run the entire long device matrix.

## Stop conditions

Stop optimization work and report if any of these occur:

- cache-only objects are not byte/format compatible with normal cache loading,
- worker OOM/crash frequency rises,
- a compile-time win creates a clear runtime PPU performance regression,
- the next optimization requires a broad ORC-JIT migration or large architecture rewrite,
- two consecutive experiments each improve combined cold preparation by less than ~5%.

## Research references

- LLVM MCJIT design: https://llvm.org/docs/MCJITDesignAndImplementation.html
- LLVM `ObjectCache`: https://llvm.org/doxygen/classllvm_1_1ObjectCache.html
- LLVM codegen optimization levels (`None/Less/Default/Aggressive` = O0/O1/O2/O3): https://llvm.org/docs/doxygen/CodeGen_8h_source.html
- LLVM FastISel design/tradeoff: https://llvm.org/docs/doxygen/FastISel_8cpp_source.html
- LLVM ORC concurrency/lazy compilation (future reference, not first-pass migration): https://llvm.org/docs/ORCv2.html
- LLVM New Pass Manager / PassBuilder: https://llvm.org/docs/NewPassManager.html
- RPCS3 request for headless LLVM cache creation, confirming first-launch cache compilation remains a user-visible concern: https://github.com/RPCS3/rpcs3/issues/18987
- RPCS3 request for broader PPU/SPU precompilation: https://github.com/RPCS3/rpcs3/issues/16883
- Current RPCSX Android build uses LLVM 20.1.2: https://github.com/RPCSX/rpcsx/blob/master/android/CMakeLists.txt
- Current public RPCSX PPU/JIT reference used for review: `rpcs3/Emu/Cell/PPUThread.cpp`, `rpcs3/util/JITLLVM.cpp`, `rpcs3/Emu/system_utils.cpp`.
