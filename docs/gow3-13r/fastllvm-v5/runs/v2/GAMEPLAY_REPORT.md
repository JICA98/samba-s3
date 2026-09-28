# Fast LLVM v2 gameplay capture

## Result

- Recording end condition: **stopped at the user’s request**.
- Performance verdict: **REJECTED**. User reports increased RAM usage and decreased FPS compared with the previous build.
- The assistant stopped the recorder; it did not issue a game-stop command in this turn.
- End time: 2026-09-28T04:17:50.528306+00:00.
- Device: OnePlus 13R, `d30a1726`; original PID `23712`; session `1790568831391-d9e3a487`.
- User controlled gameplay. The monitor sent no controls, stopped no game, and changed no settings.
- Recorder was interrupted intentionally. Its exit code 130 is the requested host-side interruption, not evidence of an emulator crash.

## Build and launch

- Release: `2026.09.28-fastllvm-v2`, standard release APK.
- APK SHA-256: `d1dfa03a88b70b49dcc148c5efcc4eab73c6b504b6c4c24a17afa9cf3c2283c6`.
- Core SHA-256: `31039ae86dc251b2ec282c1a2900d182eb97ed541f63e1eaa6d1f0a2e754032c`.
- Build, core provenance, APK signature and 16 KiB alignment checks passed.
- Canonical slot-0 launch; runtime logged Fast LLVM enabled and a confirmed saved-game frame.
- Existing warm cache preserved. V2 adds a one-time startup heap purge with before/after RSS logging; promotion policy is unchanged.
- Initial pad delivery and [handoff screenshot](gameplay.png) are preserved in this directory; subsequent gameplay is user-controlled.

## Performance evidence and limits

- Screenshots preserve displayed HUD values. They are point samples, not a calibrated FPS average or a controlled performance comparison. The startup purge returned success in 30.245 ms, but RSS increased from 1,381,625,856 to 1,395,859,456 bytes during that interval. This does not demonstrate a memory reduction. No FPS gain is established.
- Capture interval: approximately 20 seconds between screenshots, 60 seconds between thread/thermal/system-memory snapshots. Capture overhead can affect gameplay.
- Heat was recorded without a heat-only stopping rule.
- Enabled configuration does not prove every block used the fast tier; warm cached blocks can use ordinary LLVM.

### Native session summary

```text
No native Fast LLVM session summary captured; execution totals are unavailable.
```

## Evidence

- [All-buffer live logcat](gameplay-all-buffers.log), [launch logcat](launch-logcat.txt), [post-launch logcat](post-launch-logcat.txt).
- [Full one-shot diagnostics](all-logs/), [collector output](collector-output.txt), [command capture status](capture-status.jsonl).
- Collector exit code: `0`. App/backend/Vulkan rotations, RPCSX/TTY logs, crash/main/system/events/kernel buffers, exit information, activity/window/SurfaceFlinger/input state, memory, thermal, pstore and crash/ANR metadata are requested. Protected Android data may be unavailable; an empty or permission-denied file is not evidence that no error occurred.
- Launch and gameplay captures both request all accessible logcat buffers. The gameplay capture includes a buffered tail overlapping the handoff; duplicate lines must be removed before deriving timings.
- The collector can see a replacement process after a crash; the original PID/session above remains the gameplay identity.

### Screenshots

- [20260928T041546Z-gameplay.png](screenshots/20260928T041546Z-gameplay.png)
- [20260928T041610Z-gameplay.png](screenshots/20260928T041610Z-gameplay.png)
- [20260928T041633Z-gameplay.png](screenshots/20260928T041633Z-gameplay.png)
- [20260928T041656Z-gameplay.png](screenshots/20260928T041656Z-gameplay.png)
- [20260928T041719Z-gameplay.png](screenshots/20260928T041719Z-gameplay.png)
- [20260928T041742Z-gameplay.png](screenshots/20260928T041742Z-gameplay.png)
- [20260928T041750Z-terminal.png](screenshots/20260928T041750Z-terminal.png)
