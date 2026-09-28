# Fast LLVM v1 gameplay capture

## Result

- End condition: **clean_stop**, explicitly requested by the user.
- Performance verdict: **rejected by user**: no improvement, lower FPS, and RAM shown around 10.8 GB versus approximately 7 GB in earlier builds. The older-build observation has no matched capture in this run.
- End time: 2026-09-28T04:00:48.176758+00:00.
- Device: OnePlus 13R, `d30a1726`; original PID `21225`; session `1790567792091-45e8ec3a`.
- User controlled gameplay. The monitor sent no controls, stopped no game, and changed no settings.
- Terminal evidence: `         1790568046.249 21225 21250 I S3EXIT  : event=CLEAN_STOP sessionId=1790567792091-45e8ec3a activityInstanceId=1 stopRequestId=1 stopReason=HomeStop finishReason=ExplicitExit activeGame=direct_iso/BCUS98111 nativeState=Stopped journalState=CLEAN_STOP pendingRecovery=none fatalEventId=none timestamp=1790568046249`.

## Build and launch

- Release: `2026.09.28-fastllvm-v1`, standard release APK.
- APK SHA-256: `603fd743b046fb33eff1ec57d615a0e07d870130c8e428c4f5ea10963e9e9ae2`.
- Core SHA-256: `9b6860196091f8b898d55f664a42b4e67b5ad879d00c50cb48d98a8d34df09a6`.
- Build, core provenance, APK signature and 16 KiB alignment checks passed.
- Canonical slot-0 launch; runtime logged Fast LLVM enabled and a confirmed saved-game frame.
- Existing warm cache preserved. The proposed promotion-budget fix is not included in v1.
- Square/Cross acknowledged. Circle press logged, but the release acknowledgement timed out; [handoff screenshot](handoff-screen.png) independently shows the combat scene. No retry was sent.

## Performance evidence and limits

- Initial handoff HUD snapshot: 12.2 FPS, 66.6 ms frame, SPU 352%, app CPU 535%, thermal SEVERE. These are displayed point samples, not a calibrated average or an FPS gain.
- Capture interval: approximately 20 seconds between screenshots, 60 seconds between thread/thermal/system-memory snapshots. Capture overhead can affect gameplay.
- Heat was recorded without a heat-only stopping rule.
- Enabled configuration does not prove every block used the fast tier; warm cached blocks can use ordinary LLVM.

### Memory and regression findings

The final gameplay screenshot shows **RAM 10.8 G**, available **940 M**, app **RSS 3.6 G**, **PSS 4.0 G**, swap **2.6 G**, and **16.1 FPS / 49.4 ms**. RSS and PSS are independently sampled; they are not a synchronized accounting equation. The HUD RAM field is device-wide `MemTotal - MemAvailable` (AndroidSystemMetricsCollector.kt:200–203). The captured `/proc/meminfo` has MemTotal 11,492,264 kB and MemAvailable 939,048 kB, giving about 10.807 GB used across the device. This confirms the high device memory reading, without attributing all of it to emulator-private allocations. Thread `top` shows one process RSS around 3.3 GiB repeated across its threads; these rows must not be added together.

The run does not establish the cause or exact size of the regression against earlier builds. The final native summary reports **zero fast-tier attempts/emissions/promotions/entries**, despite the enabled flag. Startup replay built **2,787 programs** with the ordinary LLVM path. This run therefore provides no evidence of an actual Fast LLVM execution benefit. First frame was confirmed at epoch 1790567864.529; user-requested clean stop was confirmed at 1790568046.249.

### Native session summary

```text
         1790568045.962 21225 21247 D RPCS3   : ARM64_FAST_LLVM_SESSION execution_counter_unit=block_entry attempted=0 eligible=0 emitted=0 fallback=0 reject_invalid=0 reject_no_prefix=0 reject_codegen=0 promotions_admitted=0 promotions_published=0 promotions_failed=0 tracked_fast_entries=0 tracked_optimized_entries=0 fast_items_baseline_entered=0 fast_items_optimized_entered=0 fast_items_tracked=0 tracking_capacity=2048 tracking_full=0 tracking_overflow=0 dump_regions=0 dump_bytes=0
```

## Evidence

- [All-buffer live logcat](gameplay-all-buffers.log), [launch logcat](launch-logcat.txt), [post-launch logcat](post-launch-logcat.txt).
- [Full one-shot diagnostics](all-logs/), [collector output](collector-output.txt), [command capture status](capture-status.jsonl).
- Collector exit code: `0`. App/backend/Vulkan rotations, RPCSX/TTY logs, crash/main/system/events/kernel buffers, exit information, activity/window/SurfaceFlinger/input state, memory, thermal, pstore and crash/ANR metadata are requested. Protected Android data may be unavailable; an empty or permission-denied file is not evidence that no error occurred.
- Live logcat began after handoff and includes the buffered tail. The separate launch capture covers restore; continuous all-buffer coverage of the entire launch is not claimed.
- The collector can see a replacement process after a crash; the original PID/session above remains the gameplay identity.

### Screenshots

- [20260928T040029Z-gameplay.png](screenshots/20260928T040029Z-gameplay.png)
- [20260928T040048Z-terminal.png](screenshots/20260928T040048Z-terminal.png)
