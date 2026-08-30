# Samba S3 monitoring, controller and recovery validation

## Source

- Root start: `b4be895725294ed3229387ef8e9397d0d47081038`
- RPCSX submodule: `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`
- Device: OnePlus tablet OPD2403, serial `adb-7d6afed8-mU47CV._adb-tls-connect._tcp`

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

The requested device is the OnePlus tablet. Two final-code passes are recorded below; each pass starts from a force-stopped app and uses the same Standard Debug APK.

### Pass 1

- [ ] Recovery/relaunch path
- [ ] Monitoring settings and in-game overlay
- [ ] Controller mapping/test UI
- [ ] Android Back, PS/Home and operation-lock regression
- [ ] SAVE/LOAD and long-play regression
- [ ] Fatal-log grep

### Pass 2

- [ ] Recovery/relaunch path
- [ ] Monitoring settings and in-game overlay
- [ ] Controller mapping/test UI
- [ ] Android Back, PS/Home and operation-lock regression
- [ ] SAVE/LOAD and long-play regression
- [ ] Fatal-log grep

## Known device-dependent limitation

The pinned local RPCSX core does not currently export the optional structured performance-metrics symbol. The app therefore hides unavailable emulator FPS/CPU/RSX values instead of fabricating them. Android RAM, process memory, swap/ZRAM, battery, thermal, CPU-frequency and best-effort GPU providers remain capability-driven.
