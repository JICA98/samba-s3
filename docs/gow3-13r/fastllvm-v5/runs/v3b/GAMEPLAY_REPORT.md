# Fast LLVM v3b diagnostic run — 2026-09-28

## Result

The 45-second observation window after scripted gameplay entry ended without a detected render-thread fatal or original-process loss. Canonical stop returned `stop completed ok=true`; the same process returned to MainActivity. This short run does not resolve v3’s intermittent invalid RSX report-address failure.

Terminal screenshot shows combat and a HUD point of **12.6 FPS**, **11.0 GB device-wide RAM used**, 731 MB available. App RSS 4.1 GB, PSS 3.8 GB and swap 1.6 GB are separate diagnostics. Neither target (60 FPS, <6 GB total device usage) was met. This was not a controlled FPS benchmark; no average or improvement claim is supported.

## Identity and procedure

- OnePlus 13R `d30a1726`; standard release `2026.09.28-fastllvm-v3b-probe`.
- PID `27569`, session `1790570891034-d4659b2c`.
- Launch request `s3-1790570890872405480-423351-5774`; saved slot 0, unchanged warm cache.
- Fast LLVM enabled; this setting alone does not demonstrate baseline native execution.
- Canonical launch and Square/Cross/Circle scripts with acknowledgments; passive screenshots only. No Settings interaction.
- Core SHA-256 `c1ea86e31025037b127827a2f2f6f4e6238637eb6e63f0f4a5bc021fc3f66c91`.
- Source digest `406e7c7b07877633e6d67edf19cb57c0c2fc6d8b22a8d0d43e58c22a90bcd11d`.
- APK SHA-256 `eb0b3948ec85f9828618b9f86efe10ae9d64d45e32446beba54e2d31f90db56b`.
- Native/app builds, core/APK provenance, release signature and 16 KiB alignment passed.

## Memory attribution

VMA heap 0 at epoch 1790571001.312 reserved **2,785,017,856 bytes** in blocks with **1,506,805,056 bytes** of live allocations: **1,278,212,800 bytes** of unused capacity. A later sample reserved 2,384,789,504 bytes with 870,759,744 allocated. These counters do not include all driver/internal memory. The allocator does not enable the memory-budget extension; its `usage_bytes` fallback equals reserved block bytes.

Render rings retained their initial sizes; no ring-growth event was observed. The undefined resource pool was about 90–145 MB in sampled telemetry. The current Vulkan driver is Qualcomm system Adreno 750, version 512.762.41; no driver selection was changed.

This establishes a candidate optimization: Android VMA smaller preferred blocks and best-fit placement. It does not establish that all system RAM growth comes from VMA or that allocator changes improve FPS.

## Render-address diagnostics

The first eight report commands used valid main-host DMA context `0xfeed0001`; offsets included `0x10e320` and `0x12e430`. No invalid-local report context was observed during the bounded window. v3 failed with local DMA `0x66626660` and offset `0x10dca0`; that cause remains under investigation. No report offset was clamped or command skipped.

## Evidence and limitations

`launch-logcat.txt`, `bounded-live.log`, `all-logs/`, `gameplay.png`, `terminal.png`, `capture-result.json`, `stop.txt`, and build manifests preserve this run. The full collector succeeded before stopping. Post-stop logcat was captured too late to retain the native session summary; executed Fast LLVM totals are unavailable, not zero. No continuous screenshot/video recorder was started.
