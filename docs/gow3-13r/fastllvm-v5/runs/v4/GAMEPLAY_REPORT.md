# Fast LLVM v4 gameplay and memory probe — 2026-09-28

## Outcome

**Performance target not met.** The final screenshot shows combat at a HUD point of **12.0 FPS** and **10.2 GB total device RAM used** (v3b final point: 12.6 FPS and 11.0 GB). These are individual observations from uncontrolled gameplay, not matched benchmark averages. No FPS improvement is established.

Vulkan allocation packing improved in the sampled workload: maximum VMA unused block capacity was **454,688,448 bytes**, versus **1,514,029,760 bytes** in v3b. Maximum sampled reserved blocks were **1,407,188,992 bytes**, versus **2,785,017,856 bytes** in v3b. Live allocation sizes and gameplay differ; these maxima are not a paired allocation experiment.

No original-process loss or logged render-thread fatal was detected in the 45-second observation window after scripted entry. Frames continued presenting and screenshots changed. Canonical stop succeeded with `journal=CLEAN_STOP`; no game is left running. This bounded result does **not** resolve v3's intermittent invalid RSX report-address error.

## Candidate change

- Android VMA preferred large-heap blocks: 256 MiB default → 64 MiB.
- Android allocation strategy: best-fit even when advertised GPU heap load is low.
- Larger allocations/additional blocks remain permitted; no cap, eviction or altered resource-retirement rule.
- Source delta: `android-vma-packing.patch`; previous source preserved under `source-before/`.
- Existing sparse Vulkan/report diagnostics retained. Fast LLVM remains ON.

## Device and build identity

- OnePlus 13R `d30a1726`; standard release `2026.09.28-fastllvm-v4`.
- APK SHA-256 `f0c5bfdd74690fcb0e20708e71c108090179acf1466d68f7bbb642b91df1bcbc`.
- Native SHA-256 `607a30fd7cd0cbe58a9054b7b930f1bea9203250771040d75e79030bd1991d6a`.
- Source digest `2e8d2eb2383dceaed0cbe746cd23e36585ac6ad3812c6e4ffab012d0aca55c9a` matched the loaded S3CORE identity.
- Native/app builds, core/APK provenance, release signer and 16 KiB alignment passed.
- Installed with `adb install -r`; user data, saved slot and caches preserved.
- Qualcomm system Adreno 750 Vulkan driver retained; no Settings/UI interaction.

## Run identity and procedure

- PID `29885`; session `1790571671695-221d02d1`.
- Launch request `s3-1790571671547942009-443654-865`.
- Canonical slot-0 launch, followed by acknowledged Square/Cross/Circle pad scripts. Physical controller input was also observed, so this is not a deterministic workload comparison.
- First frame confirmed at epoch `1790571744.657`, approximately 73 seconds after accepted launch.
- Bounded original-PID/fatal monitoring, five-second system-memory samples and two passive screenshot milestones. No video/continuous screenshot recorder.
- Full session-aware logs collected before the canonical stop. Collector and stop exited 0.
- Clean-stop journal recorded at epoch `1790571822.299`.

## Measurements and limits

System RAM used is `MemTotal - MemAvailable`, matching the HUD. 8 sparse samples ranged from **10.192 to 10.954 GB**; raw sample count is in `counter-summary.json`. App RSS/PSS are separate metrics. The collector reported app PSS 3,210,468 KiB, RSS 3,618,524 KiB, native heap allocation 1,263,223 KiB and Graphics 1,421,868 KiB.

Surface-present counter delta was **13.597 Hz**, versus 13.480 Hz in v3b. This is a diagnostic present rate, not verified useful-gameplay FPS; scene/control differences preclude a gain claim. The 60 FPS / under-6 GB goal remains unmet.

VMA `usage_bytes` is a fallback equal to reserved block bytes because the memory-budget extension is not enabled. It excludes untracked driver allocations. System shared/unevictable memory still grows substantially during gameplay and falls after stop; its remaining ownership is unresolved. After stop, MemAvailable was 6,795,392 KiB and Shmem 627,736 KiB.

## Fast LLVM execution caveat

The exact-session native summary reports `attempted=0`, `emitted=0`, `tracked_fast_entries=0`, `tracked_optimized_entries=0`, and zero promotions. The warm cache replayed existing ordinary LLVM programs. Fast LLVM was configured ON, but this run **did not exercise its baseline-generated code**. The memory effect belongs to Vulkan allocation policy, not a demonstrated Fast LLVM execution improvement.

## Follow-up

Retain this as a measured allocator candidate, not a performance release approval. Investigate the remaining shared/driver memory and CPU bottleneck; distinguish warm LLVM execution from actual Fast LLVM baseline execution. If the render-address fatal returns, trace its report-context writer/reset rather than clamping invalid offsets or suppressing commands.

## Evidence

`gameplay.png`, `terminal.png`, `launch-logcat.txt`, `bounded-live.log`, `memory-samples.jsonl`, `stop-logcat.txt`, `all-logs/`, `capture-result.json`, `counter-summary.json`, build/signature manifests and `evidence-sha256.txt` preserve the run. Logs may include rotated older sessions; use the PID/session above for attribution.
