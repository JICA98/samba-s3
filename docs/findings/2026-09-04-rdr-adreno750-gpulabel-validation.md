# RDR Adreno 750 gpu_label validation (updated 2026-09-05)

Status: **NOT FIXED — post-intro black output still reproduced.**
Do not equate the game's TTY `Time to Press Start` message with a visible menu.

## 2026-09-05 A/B result

The installed barrier build still reached a visible SPU-cache screen, then a
black output with the overlay alive. An LLDB attach localized the RSX thread to
`vk::command_buffer_chunk::poke()` → `VKGSRender::check_present_status()` while
polling the submitted swap-command-buffer fence. Android HWUI concurrently
reported repeated `dequeueBuffer failed, error = -110` messages. There was no
backend fatal or `VK_ERROR_DEVICE_LOST` in the captured process logs.

Two presentation/synchronization A/Bs were run on the requested OnePlus Pad 2
serial. Disabling Force FIFO present mode did not change the black result. A
diagnostic build with Android host events (the forced `gpu_label` fallback
disabled) also reproduced the same cache-to-black transition and the tablet
subsequently rebooted (`ro.boot.bootreason=reboot`). This rules out the forced
`gpu_label` selection as the sole cause of the observed post-cache GPU hang;
the Turnip/Adreno presentation path remains the open fault boundary.

### Telemetry/recovery follow-up

The Standard Debug APK was rebuilt and installed after replacing
`ActivityManager.getMemoryInfo()` with local `/proc/meminfo` parsing. During
the same black-output run the process remained alive and no new
`JNI DETECTED ERROR`, `Fatal signal`, or `DeadSystemException` was emitted;
the only new surface errors were repeated HWUI `dequeueBuffer failed,
error=-110` messages. The prior crash was a secondary CheckJNI abort while
`system_server` was already dead, so this removes that app-side crash vector.

An explicit stop then sent the native kill request, but the RSX thread never
reached `Stopped` within the coordinator's active/passive 60-second budget.
The failure-recovery path now terminates the fullscreen process after that
bounded timeout instead of retaining a black, non-interactive Activity. A
manual `am force-stop` was used for this validation run.

## Verified core and device

- OnePlus OPD2403 / Adreno 750, wireless serial
  `adb-7d6afed8-mU47CV._adb-tls-connect._tcp` (USB serial `7d6afed8`).
- BLUS30758, extracted game path ending in `files/config/games/BLUS30758`.
- Core build identity: Samba `ee1403a5dd8dfa73e2ef0328e99e5112097fd8e0`,
  RPCSX `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`, patch
  `0a68b5f439b33e8ed35d27c16fd088e90df61f4ebd48c4d233334630c61d7254`, Debug.
- Unstripped local core SHA256:
  `4d179815ed098e69792c8d3ef8a5e420279ac1271af3cd2c7f822e8d5ea49ba6`.
- Loaded installed core SHA256 and APK-extracted core SHA256 both:
  `6b9d9b12023a03d7069b93ddf286e25ee082d108e2085600c54b4a04c885bd8d`.
  The difference from the source artifact is APK symbol stripping.
- Driver package directory `turnip-26.3`; actual Vulkan driver reports **26.2.99**.
- `S3VKSYNC domain=host backend=gpu_label reason=android driver_vendor=0`
  appears in the current core log. The workaround is active, but insufficient.

## New failure evidence

Raw evidence directories:

- `/tmp/rdr-wireless-reconnect-20260905`: prior session ended with
  `VkPresent returned unexpected error code -4` at core time 23:50.
- `/tmp/rdr-wireless-black-20260905-1024`: black output; START press at
  10:24:14.756 and release at 10:24:22.750, despite the requested 120 ms pulse.
  Contains `lldb-threads.txt`, `process-maps.txt`, and original override backup.
- `/tmp/rdr-defaults-20260905-1035`: repeated black output with no START input;
  explicit RDR app/native overrides cleared and compatibility SPURS=4,
  wake-up delay=200 verified. Existing global FIFO accuracy remains Atomic and
  Force FIFO present mode remains true. This is not a factory-default profile.

One native backtrace captured RSX inside Turnip from
`vk::command_buffer_chunk::poke` (`vkGetFenceStatus`) → `reset` →
`VKGSRender::flush_command_queue` → `do_local_task`.
The game render PPU thread was in `sys_timer_usleep`.
This sample localizes the observed wait to command-buffer fence handling; it
does not prove the driver remained in that exact call throughout the stall.
No successful menu/world/movement/stability result is claimed.

`debuggerd -b` requires root on this device. LLDB attach via app `run-as`
succeeded, and the process was detached and resumed after collection.
Procedure follows the [LLDB remote server documentation](https://lldb.llvm.org/man/lldb-server.html).

## Harness changes and verification

- Boot uses only the in-process debug bridge; exact request acknowledgement
  plus activity focus is required. Already-running activity is rejected.
- Paths are quoted for the remote shell; no direct non-exported activity fallback.
- Button and raw input use unique request IDs. Pulses require release evidence.
- Raw arguments are validated. Interrupted holds attempt neutral release.
- Fixed successful single-press/EULA helpers returning failure on their final loop.
- Collector saves current-PID logs/top/maps before rotated files, bounds ADB
  calls, reads current native logs in its summary, and avoids misleading
  `no exit-info` output caused by SIGPIPE from `head`.
- Device verified neutral raw input, START pulse/release, exact RDR boot,
  rejection of launch into an already focused emulator, and CLEAR_ALL yielding
  `explicit={} native={}` with the RDR persisted entry removed.
- Four host tests cover shell metacharacters, stale acknowledgements, press-only
  evidence, matching release, and invalid raw values. Both flavor unit suites
  and Standard Debug APK build passed before the subsequent stop/SYSMEM harness addition.

## Still open

SYSMEM diagnostic A/B, any further native correction, visible main menu and
controllable world, movement/camera, 10/20-minute stability, repeat boot,
background/resume, clean exit, exact settings/driver restoration, and Mali
regression. The existing settingsGet probe passed (see artifact record), but
that does not establish RDR compatibility. Android-wide gpu_label safety is
not proven.

## 2026-09-05 second-device comparison

The updated Standard Debug APK was installed on
`adb-Y5WWBMJVOZSK4HU8-keJQIe._adb-tls-connect._tcp` (POCO 2311DRK48I,
Mali-G615 MC6). A fresh BLUS30758 boot reached the RDR2 render-thread path
(`Time to Press Start: 44.923 seconds`) with no Vulkan timeout, dequeue-buffer
failure, backend fatal, or access violation in the current evidence collected
at `/tmp/other-device-updated-20260905`. This is a useful control: the APK
and game image are viable on the Mali device, while the Pad 2 failure remains
Adreno/Turnip presentation-specific. It is not evidence that the Adreno 750
main menu or world is fixed.
