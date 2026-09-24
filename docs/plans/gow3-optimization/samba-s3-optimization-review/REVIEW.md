# Independent review: what the current evidence does and does not establish

Reviewed 2026-09-24. Frontend `270d72a56a926375f16f6c9a22474acdda99cd8c`; backend `ed8ba6c12c218249524a441b79f48d6bae842394`. References are indexed in SOURCE_REFERENCES.json. The implementation work order and acceptance criteria are authoritative in WORKER.md.

## Verdict

The useful next work is not a generic 'use less CPU' change or an immediate driver rewrite. The evidence identifies a hot auxiliary worker, a real x86/ARM SPU compilation-path difference, a contention-prone benchmark scheduling configuration, incomplete metric handling and insufficient long-gameplay crash qualification. Those deserve targeted optimization and crash investigation. The evidence does **not** establish a single cause for every reported 1–2-minute exit, nor a demonstrated route to an eightfold speedup.

A sustained 60 FPS target remains the engineering objective. Improving the reported 7.44 FPS to 60 needs about 8.06 times the throughput. There is no measured basis to promise that any one setting, driver change or frontend fix supplies that increase.

## 1. Publication and build status have improved

The frontend currently pins the published backend HEAD. Its current Gradle code invokes core provenance verification, has arm64 native outputs and adds packaged-artifact verification tasks. The old 'core rebuild skipped because both libraries exist' finding is not the current code. This is real progress, not another defect to fix again. [S01, S05]

The revised benchmark's runtime S3CORE identifies `05ed8aed420f7cf4975835f4baebda91cec14748`, not the current `ed8ba6c...`. That does not make its result invalid, but it is a different source/artifact identity. Compare revisions and rerun the current APK before treating the report as certification of the latest branch. [S02]

I have not independently run the 74 Python tests, 60 Kotlin tests or package verification; those results remain reported by the project. Source checks or JVM tests do not by themselves prove native instruction publication, readback synchronization or long-running game correctness. [S24]

## 2. The thread snapshot contains a missed optimization lead

The raw `threads-top.txt` includes:

| Role | Sample CPU percentage |
|---|---:|
| SPU TID 6047 | 85.7 |
| DefaultDispatch TID 5498 | 85.7 |
| SPU TID 6046 | 78.5 |
| SPU TID 6049 | 75.0 |
| RSX TID 5633 | 64.2 |
| SPU TID 6054 | 57.1 |
| SPU TID 6051 | 46.4 |
| PPU TID 5635 | 42.8 |
| SPU TID 6050 | 35.7 |

The six displayed SPUs sum to 378.4 percentage points, approximately 3.784 fully busy logical-core equivalents in that sample. They are not all at 75–85%. The hot DefaultDispatch worker is comparable to the hottest SPU. This is a strong reason to obtain that worker's managed/native stack, but not proof that it is unnecessary UI work: a coroutine worker can be executing native emulation, blocking JNI, profiling or log handling. [S03]

The `800%cpu 461%user ... 157%sys ...` line is a global CPU summary, not the app's process sum. Adding user+system to get 618% cannot certify the app-only CPU cost. Separate process and system metrics in subsequent evidence.

## 3. GPU utilization handling has a concrete format problem

`AndroidSystemMetricsCollector.kt` discovers `gpu_busy_percentage`, `gpu_busy` and `gpubusy`, then reads the first integer using a single parser. That cannot correctly interpret all sources if one returns a percentage and another returns a busy/total pair. The implementation also accepts generic fallback files without source-specific typed parsing. [S07]

Correct the parser and record the actual selected path/raw output. For a verified windowed busy/total pair, use its ratio; for a cumulative source, use valid deltas. Do not claim that the user's reported 50% came from this defective branch without confirming the source. Even a correct global busy percentage does not directly measure per-frame GPU work or driver CPU overhead.

## 4. The telemetry observer has avoidable-risk paths

Thermal headroom is called in the one-second power sample, with an immediate second forecast request after a non-finite response. Android's documented rate guidance is much slower. This can produce unavailable/misleading telemetry and unnecessary calls. Cache headroom on its own schedule instead. [S07, S19]

PSS is sampled every three seconds. Monitoring uses Dispatchers.Default, builds per-sample debug maps and republishes history. These are profile targets, not proven explanations for 85.7% CPU. Establish cost with monitor-off/minimal/full A/B captures before changing large parts of the UI. [S06–S07]

The reviewed LogMonitor path copies four lists and flushes file writers at each batch or empty-channel boundary; normal EOF can restart logcat without the exception path's delay. Determine whether this path is active in the tested build before blaming it. Then bound refresh/flush/restart rates while preserving crash evidence. [S08]

## 5. Current GOW Fast Mode is not a complete optimization preset

The GOW entries select three patch names. The engine-settings map contains GTA entries but no GOW entry, so GOW is currently patch-only. The code persists the enabled flag before patch processing, catches errors and returns true without asserting that any target was found or applied. Consequently, an ON preference is not an effective-patch receipt. [S09]

Expose requested versus effective status and native application receipts tied to the actual executable/update/hash. Benchmark MLAA and motion blur separately. Skip-intro is not a gameplay optimization. Removing a visual effect must be labeled as a quality change, even when no unintended glitch occurs.

## 6. ARM and x86 SPU compilation paths genuinely differ

The current SPU constructor and save-state constructor route the LLVM decoder to `make_fast_llvm_recompiler()` on x86 and `make_llvm_recompiler()` on ARM. The code explicitly withdraws the ARM fast-tier claim. This is a real architectural difference relevant to runtime compile misses and startup stalls. [S11]

Do not confuse it with a demonstrated steady-state execution defect. When most slow-frame CPU time is spent executing already-compiled SPU blocks, the work is instruction lowering, synchronization, guest workload and scheduling. When it is compiling, the work is single-flight compilation, cache validity, pass/codegen cost and potentially a real ARM fast tier. Profiling must distinguish those cases.

The reviewed compilation path includes LLVM optimization, code finalization, function publication and trampoline rebuilding. The current ARM barrier occurs before the compiled pointer is stored. Preserve existing correctness improvements; audit lifetime and readers under stress rather than deleting barriers for a speculative speed gain. [S13]

## 7. Scheduling is improved but the measured policy may still be suboptimal

The current Thread.cpp supports a genuine OS-managed mask, custom allowed-mask intersection and topology-derived performance masks. The old universal mask critique is obsolete. [S10]

The benchmark nevertheless chose RPCS3 Scheduler with PPU/SPU/RSX restricted to six performance CPUs. Six SPU contexts plus PPU/RSX and background work can contend there. Whether OS scheduling or another capacity-aware arrangement is better must be measured through runnable delay, wakeups and frame completion. Reducing guest SPU count blindly is not an acceptable fix.

## 8. Readbacks offer both correctness and performance work

`VKTextureCache.h::imp_flush()` now checks the wait status and returns failure without releasing DMA resources on non-success. This is a positive change; a whole-lifecycle audit must still examine caller retries, protection, stop and failure recovery. [S12]

A swizzled readback path allocates a temporary vector, copies bytes and performs CPU conversion. That is a concrete candidate for scratch reuse or other optimization when it is hot. Do not remove the copy/conversion until its read-modify-write and guest-visible memory semantics are preserved. No device profile reviewed here proves this path dominates GOW.

## 9. The benchmark's scope is narrower than its verdict

The report describes about 81 seconds of scored 3D gameplay and roughly six minutes from launch through movies/scenes to stop. One such window cannot establish that 1–2-minute gameplay crashes are solved in all games. Its matched-window comparison uses six post-run checkpoints against eleven baseline checkpoints and a crashing baseline. Treat the improvement as preliminary, not a stable product-wide percentage. [S02]

The 3,588 frame count also needs an explicit window. At 7.44 FPS, 81 seconds corresponds to roughly 603 frames if interpreted as interval throughput; 3,588/81 is about 44.3 FPS. Rolling checkpoint means are not full-interval throughput, so these numbers are not automatically a proof of bad data, but they cannot be freely combined. Recompute from raw timestamps and separate cumulative startup/movie/gameplay frames.

The report's FIFO row uses a grep for `vk_error|device lost` as part of its zero-desync claim. Those terms do not match every FIFO/desync error. Expand the check and associate all rotated logs with the correct run. Absence of one error pattern is not evidence that all synchronization failures are impossible. [S02]

## 10. Crash and thermal conclusions need narrower wording

The archived exit history includes signal-9 exits, force stops, package changes, background low-memory exits and PPU-worker process deaths. It contains no exit record for the report's active PID 5454 in the inspected file. This history does not identify one cause for the user's latest gameplay failures. [S04]

Android provides reason/status/importance and, when available, native tombstone traces to distinguish classes of exit. Some memory kills can be represented as SIGKILL, so a signal alone is insufficient. [S20]

The report records ThermalStatus 3 and high thermal sensor readings. That supports a thermal-performance concern; it does not prove a specific crash was thermal shutdown. Keep stock protection, measure frequency and thermal-state occupancy, and optimize useful work per joule/frame. Do not equate 80GB virtual address space or 679MB MemFree with a proven physical-memory leak. [S02–S04, S19]

## 11. What a Samba-specific Turnip build should accomplish

Build it after identifying driver cost or a reproducible driver fault, not simply because GPU busy is around 50%. The first specialized build should improve reproducibility, symbols and traceability, with a stock-source control. Then test targeted IR3/shader, state emission, GMEM/SYSMEM or pipeline changes separately. [S21–S23]

Mesa documents Turnip tracing and exposes debug mechanisms, but the phone's KGSL interface may not provide the same tracing/counter/replay facilities as DRM/MSM. Verify the actual build and available producers. A debug flag that avoids one hang or increases one scene's FPS is not automatically a production optimization.

## Recommended decision

Start with verified current APK + raw timing/CPU data, classify the real crash, identify the hot DefaultDispatch work, and test scheduler/patch efficacy. Then optimize the measured SPU/JIT or RSX/readback critical path. Only attribute a driver win after controlled comparison. WORKER.md specifies the implementation, experiments, native correctness gates and the sustained 60 FPS acceptance contract.
