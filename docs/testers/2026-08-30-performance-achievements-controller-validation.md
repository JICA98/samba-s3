# Performance, achievements, and controller validation

Date: 2026-08-30  
Branch: `recovery/ingame-menu-fix...origin/...`  
APK: `standardDebug`  

## Scope

This pass covers the monitoring lifecycle/performance path, controller configuration and capture, in-game menu touch ownership, trophy parsing/events, Launch Center achievements access, and a GTA San Andreas launch check.

## Build evidence

- `./build_rpcsx.sh debug` passed for `arm64-v8a` and `x86_64`.
- `./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest assembleStandardDebug --no-daemon` passed.
- Native exports verified in both packaged cores: `_rpcsx_setPerfMetricsEnabled`, `_rpcsx_getPerfMetricsJson`, `_rpcsx_getCurrentTrophies`, and `_rpcsx_getTrophiesForTitle`.
- APK: `app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk` (approximately 227 MiB).

The build emitted existing SDK XML/platform-location warnings and existing Kotlin style warnings; no compilation or test failures occurred.

## Device checks

### OPD2403 (`adb-7d6afed8-mU47CV._adb-tls-connect._tcp`)

- Installed the final APK and warm-launched through `MainActivity`.
- Existing GTA San Andreas library entry, firmware, GPU driver, save slots, and previews remained intact.
- Controller Settings opened successfully. Mapping/Test/Profiles tabs, schematic hotspots, remap capture, and live test state were visible and responsive.
- In-game menu opened over the game surface. Performance Monitor and Achievements routes were reachable; the menu panel received touch input while the pad remained underneath.
- Launch Center displayed GTA San Andreas, BLUS31584, launch settings, real save slots, and the Achievements action.
- Achievements invoked the stopped-title native parser and rendered the truthful result `No installed trophy set for this title`; no placeholder toast or fabricated trophy data is used.
- GTA reached the title/legal screen after the initial launch wait and remained alive when monitoring was disabled.

### 2311DRK48 (`adb-Y5WWBMJVOZSK4HU8-keJQIe._adb-tls-connect._tcp`)

- Installed the final APK.
- Completed the onboarding flow to the empty library without a crash.
- Settings remained correctly gated when firmware/runtime prerequisites were absent.

## Monitoring A/B notes

The old unconditional sampler was replaced with a settings-driven subscription. Disabled monitoring stops the native producer and system collector; the collector performs no periodic sampling while disabled. Expensive sources are cached at slower cadences (PSS, memory, battery/thermal, frequencies/GPU, swap, and RSS), while graphs are bounded to 60 points.

The controlled device comparison was qualitative rather than a numeric benchmark because the pre-change APK was not available in this workspace. With monitoring enabled, GTA reached the title/legal screen after the normal initial wait. After disabling monitoring in the in-game Performance Monitor, the same running title resumed and reached the same screen with no app-level fatal exception. The first cold launch also produced repeated device KGSL AHB bus-error lines; those did not reproduce as an app exception and are recorded as a device/native GPU-driver signal requiring separate driver investigation, not attributed to the monitor without a clean pre-change benchmark.

## Remaining tester follow-up

- Repeat three home/load cycles on a device with a populated trophy set and capture native unlock events, icons, timestamps, and platinum aggregation.
- Run a numeric FPS/frametime/power comparison using the same title, device thermal state, and fixed scene with monitoring off/on.
- Validate the stopped-title parser against multiple trophy-set directories, including whitespace/case variations in `TROPCONF.SFM` title IDs.
