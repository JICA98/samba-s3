# SambaS3 monitor metric source audit

This audit covers the telemetry export used by the compact Compose overlay. The
native frame source is the `rsx::thread::flip()` `info.emu_flip` event. It is
deliberately separate from `vblank_count`: VBlank is retained only as a debug
cross-check and is never used to calculate the value labelled FPS.

| UI metric | Current source | Exact meaning | Refresh cadence | Independent validation | Verdict |
|---|---|---|---:|---|---|
| FPS | RPCSX `info.emu_flip` counter | Emulator guest frames presented per second | 250–1000 ms | Presented-frame vs VBlank synthetic test; device `S3PERF` source log | PASS source fixed; device pass required |
| Frame | Intervals between consecutive `emu_flip` events | Average presented-frame interval in the current window, milliseconds | 250–1000 ms | Per-frame ring preserves 52 ms synthetic spike | PASS source fixed; device pass required |
| CPU | `utils::cpu_stats::get_usage()` | RPCSX host CPU usage; 100% is one fully used host core | 250–1000 ms | `dumpsys cpuinfo` / `top` trend | PASS source family |
| PPU | PPU `thread_ctrl::get_cycles()` interval deltas weighted by RPCSX host CPU | PPU share of RPCSX host CPU | 250–1000 ms | Boot/gameplay/pause/resume dynamics | PASS; may be stable in a stable scene |
| SPU | SPU `thread_ctrl::get_cycles()` interval deltas weighted by RPCSX host CPU | SPU share of RPCSX host CPU | 250–1000 ms | Boot/gameplay/pause/resume dynamics | PASS; optional developer metric |
| RSX CPU | RSX `thread_ctrl::get_cycles()` interval delta weighted by RPCSX host CPU | RSX host CPU share, distinct from RSX load | 250–1000 ms | Boot/gameplay/pause/resume dynamics | PASS source fixed |
| RSX Load | `rsx::thread::get_load()` | RPCSX RSX workload estimate based on RSX idle time | Around 30 guest flips | Pause/gameplay comparison | PASS source; device pass required |
| PPU Threads | `idm::select<named_thread<ppu_thread>>` | Current PPU thread inventory | 250–1000 ms | Native thread inventory/logs | PASS; structural-ish |
| SPU Threads | `idm::select<named_thread<spu_thread>>` | Current SPU thread inventory | 250–1000 ms | Native thread inventory/logs | PASS; structural-ish |
| Host Threads | `utils::cpu_stats::get_current_thread_count()` | Current native process thread count | 250–1000 ms | `ps -T` / `top -H` | PASS; structural-ish |
| App CPU | `/proc/self/stat` utime+stime and elapsed wall time | Android process CPU; 100% equals one fully used CPU core | 500 ms | `dumpsys cpuinfo`, `top` | PASS; runtime `_SC_CLK_TCK` |
| CPU Max | `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` | Maximum current frequency among online CPU cores | 1 s | Readable sysfs frequency files | PASS label corrected |
| RAM Used | `ActivityManager.MemoryInfo.totalMem - availMem` | System memory used using Android's total/available model | 1 s | `dumpsys meminfo` / `/proc/meminfo` | PASS |
| RAM Available | `ActivityManager.MemoryInfo.availMem` | Android available memory, not strict Linux free pages | 1 s | `dumpsys meminfo` | PASS label corrected |
| RAM Total | `ActivityManager.MemoryInfo.totalMem` | Physical system memory | 1 s | `dumpsys meminfo` | PASS; structural |
| RSS | `/proc/self/status` `VmRSS` | Resident process memory | 1 s | `/proc/<pid>/status` | PASS |
| PSS | `Debug.getMemoryInfo().totalPss` | Android proportional process memory | 3 s | `dumpsys meminfo <package>` | PASS; slower cadence documented |
| Swap | `/proc/meminfo` `SwapTotal - SwapFree` | Current swap used | 2 s | `/proc/meminfo` | PASS |
| Swap Total | `/proc/meminfo` `SwapTotal` | Swap capacity | 2 s | `/proc/meminfo` | PASS; structural |
| ZRAM | `/sys/block/zram0/mm_stat` | Compressed zram usage | 2 s | zram `mm_stat` | PASS; optional |
| Battery % | `ACTION_BATTERY_CHANGED` sticky sample | Battery charge level | Broadcast-driven | `dumpsys battery` | PASS; slow-changing |
| BAT TEMP | `ACTION_BATTERY_CHANGED` temperature / 10 | Battery sensor temperature, not SoC temperature | Broadcast / 1 s power read | `dumpsys battery` | PASS label corrected |
| POWER | Battery voltage × `BATTERY_PROPERTY_CURRENT_NOW` | Electrical battery power only when vendor units are valid | 1 s | Raw current, voltage, charging state | PASS; hidden when invalid |
| THERMAL | `PowerManager.currentThermalStatus` | Android system thermal severity; may be driven by SoC/GPU/skin sensors | 1 s | `dumpsys thermalservice` | PASS semantics documented |
| Headroom | `PowerManager.getThermalHeadroom(0)` | Android thermal headroom value | 1 s | `dumpsys thermalservice` / API availability | PASS only when supported; optional |

## Counter audit

In this pinned RPCSX revision, `thread_base::get_cycles()` reads thread CPU
time and uses `m_cycles.exchange(cycles)`, returning the interval delta (the
first read returns zero). The Android export therefore uses the current
interval deltas for PPU, SPU, and RSX shares and resets all monitor baselines
when the monitor generation changes.

`rsx::thread::flip()` increments RPCS3's existing `performance_counters.sampled_frames`
only for `info.emu_flip`. SambaS3 now observes that same event through the
Android-only `_rpcsx_android_perf_frame_presented()` hook. The JSON payload
identifies `fpsSource=emu_flip` and `frameTimeSource=emu_flip_intervals`; a
version 1/unknown payload cannot populate the Kotlin FPS text field.

## Freshness and reset rules

The native payload includes `frameSampleFresh`, `presentedFrameCount`, and
timestamped bounded histories. When no frame is presented, FPS and frame time
are omitted. Kotlin treats `EmulatorState.Paused` as not-running for emulator
telemetry, clears the graph generation, and never renders a prior session's
values as current. `MonitoringSnapshot.metricDebug` records the collector
source and last polling time for validation diagnostics without adding text to
the normal overlay.
