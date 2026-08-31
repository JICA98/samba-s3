# SambaS3 monitor truth/final validation

Date: 2026-08-31  
Device: `adb-7d6afed8-mU47CV._adb-tls-connect._tcp` (OPD2403)  
APK: `standardDebug`, SHA-256 `82a75afa7987ecc3c01c0250fa2500a7c0460b2cdcc57d6f1503d0cc03eafb32`

## Result

PASS. The final APK installed and launched GTA San Andreas. The monitor is
visible above the emulator surface, the controller overlay remains usable,
and the graph now renders a monotonic time series instead of repeatedly
replaying the full native ring.

## Device evidence

| Check | Result |
|---|---|
| 30 FPS profile | UI screenshot showed `31.6 FPS`, `33.3 ms`; native and Kotlin `emu_flip` logs agreed |
| 60 FPS profile | UI screenshot showed `58.2 FPS`, `17.2 ms` after shader warm-up; native and Kotlin logs agreed |
| Graph after regression fix | 30 FPS screenshot shows smooth left-to-right FPS and frame-time traces; 60-profile screenshot also renders without path reversal |
| Pause/menu | Pause menu showed `RESUME` and `PERFORMANCE MONITOR`; monitor was hidden by the configured menu policy |
| Resume | Monitor returned with fresh FPS/frame-time values; no pause-duration frame-time spike was shown |
| Boot/loading | GTA reached the EULA and live Grove Street gameplay without a loading crash |
| Crash/Vulkan triage | No fatal signal, SIGSEGV, SIGABRT, access violation, or device-lost entry in the captured final logcat/backend evidence |

The 60-profile run can fall below 60 during thermal/shader load; the monitor
reports the actual presented rate rather than the configured cap. The earlier
warm 60 capture reached 58.2 FPS, while the post-fix 60 capture remained
truthful at the lower observed rate.

## Graph defect and fix

`MonitoringHistory` previously appended every timestamped sample from every
native poll. Because each poll contains the full bounded ring, this duplicated
old points and made timestamps reverse repeatedly. The history owner now sorts
and appends only samples strictly newer than its current tail. A regression
test covers repeated ring snapshots and verifies both timestamp order and
values.

## Automated validation

`./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest :app:assembleStandardDebug --no-daemon` — PASS.

The build verified both native cores (`arm64-v8a` and `x86_64`) and produced
the APK identified above. Raw output is in
[`build-and-test.txt`](artifacts/2026-08-31-monitor-truth-final/build-and-test.txt).

## Artifacts

- [30 FPS overlay](artifacts/2026-08-31-monitor-truth-final/30-profile-overlay.png)
- [60 FPS overlay](artifacts/2026-08-31-monitor-truth-final/60-profile-overlay.png)
- [post-fix 30 FPS graph](artifacts/2026-08-31-monitor-truth-final/graph-fixed-30fps.png)
- [post-fix 60 profile graph](artifacts/2026-08-31-monitor-truth-final/graph-fixed-60.png)
- [paused menu](artifacts/2026-08-31-monitor-truth-final/paused-menu-overlay-hidden.png)
- [native emu_flip CSV](artifacts/2026-08-31-monitor-truth-final/native-emu-flip.csv)
- [post-fix native emu_flip CSV](artifacts/2026-08-31-monitor-truth-final/native-emu-flip-graph-fix.csv)
- [post-fix Kotlin emu_flip CSV](artifacts/2026-08-31-monitor-truth-final/kotlin-emu-flip-graph-fix.csv)
- [final logcat](artifacts/2026-08-31-monitor-truth-final/logcat-graph-fix-final.txt)
- [metric source audit](../2026-08-31-monitor-metric-source-audit.md)
