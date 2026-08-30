# Samba S3 monitoring, controller and recovery validation

## Source

- Root start: `b4be895725294ed3229387ef8e9397d0d47081038`
- RPCSX submodule: `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`
- Device: OnePlus tablet OPD2403, serial `adb-7d6afed8-mU47CV._adb-tls-connect._tcp`
- APK: Standard Debug, SHA-256 `487c74bbaa2e55e34cef82ccff568ba2270a3e880c6b78635fc5812ca7ca99fe`

## Implemented scope

- Previous-session diagnostics now render immediately; bounded summary collection runs on `Dispatchers.IO`.
- Core stop-before-boot recovery is serialized and bounded at 15 seconds. A timeout never starts a new boot.
- Session records include process identity and activity/session diagnostics.
- Optional structured RPCSX metrics bridge and Kotlin system telemetry collectors were added. Older cores gracefully report emulator metrics as unavailable.
- Monitoring overlay/settings route and responsive visual controller mapping/test screen were added.
- Shared `GamepadMapper` is used by controller test observation and gameplay input translation; PS/Guide remains frontend-reserved.

## Automated validation

The following completed successfully on the final source state:

```text
./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest \
  :app:assembleStandardDebug :app:assemblePlaystoreDebug
```

Coverage includes bounded recovery preflight, legacy controller migration, digital mapping, PS reservation, and interaction-safe state handling.

## Device validation

The requested device is the OnePlus tablet. Two passes were run against the same Standard Debug APK after force-stop/fresh launch.

### Pass 1

- [x] Fresh Launch Center and Continue Slot 0 reached `RPCSXActivity`.
- [x] In-game menu showed `SETTINGS`, `PERFORMANCE MONITOR`, `CONTROLLER`, and restored `ACHIEVEMENTS`.
- [x] Monitoring settings page opened; Enabled/Developer/Graphs controls were visible.
- [x] Monitoring overlay rendered Android telemetry (`TEMP`, RAM, process RAM, swap); emulator FPS/frametime remained `—`.
- [x] Controller mapping page opened and showed logical controls, physical names, stick tuning, and live test readout.
- [x] PS/Home opened the in-game menu and menu navigation returned to gameplay.
- [x] Achievements page opened and reported `No trophies available` for this title/core.
- [x] No app fatal/ANR signatures in the captured logcat.

### Pass 2

- [x] Force-stop/fresh Launch Center and Continue Slot 0 reached `RPCSXActivity`.
- [x] In-game menu again showed `SETTINGS`, `PERFORMANCE MONITOR`, `CONTROLLER`, and `ACHIEVEMENTS`.
- [x] Monitoring settings page opened and the overlay remained visible in gameplay.
- [x] Controller mapping page opened with `CONTROLLER MAPPING`, `Mapping`, `Test`, `Profiles`, and `Cross → KEYCODE_BUTTON_A`.
- [x] Achievements page opened; the page reported `No trophies available` without crashing.
- [x] No app fatal/ANR signatures in the captured logcat.

## Results and limitations

- The pinned local RPCSX core does not export `_rpcsx_getPerfMetricsJson`; the overlay correctly displays unavailable emulator FPS/frametime/PPU/SPU/RSX values as `—`. This is not an FPS-pass: a native core export is still required for those metrics.
- Android telemetry was visible, including battery temperature, RAM, process RAM and swap. The tablet denied reads for ZRAM/GPU sysfs paths, so GPU hardware load/frequency are unavailable rather than fabricated.
- The Xbox Wireless Controller appeared in the first device enumeration, then disconnected at the Android Bluetooth/input layer during the automated input attempt and could not be reconnected without physical controller interaction. The UI/mapping/unit coverage passed, but live physical button/axis validation remains blocked by device state.
- The existing long-play savestate baseline and earlier two-pass Back/PS/Home checks remain protected; this follow-up did not rerun a new 20-minute save/load session after the menu-only change.
