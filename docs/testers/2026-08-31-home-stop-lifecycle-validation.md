# Home STOP / close lifecycle validation

Date: 2026-08-31  
Device: `7d6afed8`  
Build: `standardDebug`  
Package: `com.zenithblue.sambas3`

## Result

The lifecycle repair builds and the targeted device scenarios pass. Save
recovery reached a playable GTA San Andreas frame after the repaired stop path.

## Automated checks

| Check | Result |
|---|---|
| Standard unit tests | PASS |
| Play Store unit tests | PASS |
| Coordinator single-flight / strict read / delayed stop / permanent failure | 5/5 PASS |
| APK build and install | PASS |
| Device in-game EXIT GAME | PASS |
| Device Home STOP, five rapid taps | PASS |
| Save recovery first frame | PASS |
| Final app reopen | PASS |

## Device evidence

The in-game exit produced one `InGameExit` request, one native kill, native
`Stopped` in about 0.5 seconds, `CLEAN_STOP`, host finish, and MainActivity.

The Home STOP run produced five rapid taps but only:

```text
S3STOP request id=2 reason=HomeStop
S3STOP id=2 kill-requested native=Paused
S3STOP id=2 state=Stopped elapsed=604
S3STOP id=2 finalize native=Stopped published=Stopped activeGame=null journal=CLEAN_STOP
S3STOP id=2 complete
```

The subsequent save launch logged `S3SAVE recovery-boot result=0`,
`S3HOMELOAD first-frame-confirmed`, and rendered GTA gameplay at approximately
59.7 FPS.

## Matrix

| Test | Pass 1 | Pass 2 | Native final state | Activity final state | Evidence |
|---|---:|---:|---|---|---|
| Normal STOP | PASS | — | Stopped | MainActivity | `03-home-stopped-state.png` |
| Rapid taps | PASS (5 taps) | — | Stopped | MainActivity | `device-logcat-final.log` |
| STOP after PS menu | PASS (menu cleanup via exit) | — | Stopped | MainActivity | `06-stop-after-menu.png` |
| STOP after SAVE/LOAD | PASS (load then exit/stop) | — | Stopped | MainActivity | `05-load-after-stop.png` |
| App reopen | PASS | — | Stopped | MainActivity | `03-home-stopped-state.png` |
| Fresh launch after STOP | PPU preparation observed | — | Loading/Preparing during capture | MainActivity | `02-home-ppu-preparing.png` |
| Save load after STOP | PASS, first frame confirmed | — | Running | RPCSXActivity | `05-load-after-stop.png` |

The full 20-cycle soak and second complete pass were not run in this session;
the device’s first fresh launch entered a long PPU compilation phase. The
targeted lifecycle, recovery, and launch-gating checks above are the recorded
device evidence; no `S3STOP` failure or timeout was observed.

## Artifacts

All screenshots and pulled app/backend/Vulkan logs are in
[`docs/testers/artifacts/2026-08-31-home-stop-repair/`](artifacts/2026-08-31-home-stop-repair/).
