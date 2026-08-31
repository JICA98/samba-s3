# Performance overlay polish validation — 2026-08-31

## Result

Validated on the requested Android device:

- Serial: `adb-7d6afed8-mU47CV._adb-tls-connect._tcp`
- Model: `OPD2403`
- Build: `standardDebug`
- Game: GTA San Andreas `BLUS31584`

The monitor is now visible in the actual game framebuffer. The root cause of the prior “UI nodes exist but no pixels” report was the full-screen controller `SurfaceView` compositor layer covering the sibling Compose monitor. `PadOverlay` is now a normal custom `View`, while the XML/translation-Z stack keeps the intended ordering: game surface → monitor → controller → utility/menu controls.

## Device pass

1. Installed `app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk`.
2. Dismissed the stale crash recovery card and launched GTA from the library.
3. Confirmed the game progressed through the Rockstar EULA/menu and loaded Grove Street gameplay.
4. Confirmed visible monitor pixels in screenshots, including `FPS`, `FRAME`, `CPU`, `RSX`, `RAM`, `TEMP`, and the FPS/frame-time graphs. The final source build omits the device's unsupported near-zero battery power value instead of displaying `0.0W`.
5. Confirmed live telemetry: approximately `58–63 FPS`, `16–17 ms` frame time, and Vulkan-backed gameplay with no new fatal signal, access violation, device-lost, or frozen-emulation message in the captured log window.
6. Revealed the touch controller after its idle fade; the controller renders above the monitor and the monitor remains visible below it. Opening the in-game menu hides the monitor as configured by `hideWithMenu`.

## Automated verification

```text
./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest assembleStandardDebug --no-daemon
BUILD SUCCESSFUL
```

`git diff --check` also passes.

## Evidence

Evidence is stored in `docs/testers/artifacts/2026-08-31-monitor-overlay-polish/`:

- `01-settings-all-metrics.webp` — settings/preview coverage
- `12-monitor-under-controller.webp` — monitor and controller compositing
- `13-controller-overlap.webp` — controller visible over the monitor region
- `14-monitor-gameplay.webp` — loaded Grove Street gameplay with live monitor
- `device-logcat.log` — filtered device telemetry and boot/runtime evidence
