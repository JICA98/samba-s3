---
name: sambas3-device-test
description: Deterministic SambaS3 on-device game test orchestrator — one launch path, strict budgets, evidence-first. Use when asked to test a game on device, reproduce a boot/crash, or run any multi-step device validation. Delegates to sambas3-game-launch (launch), sambas3-controller (input), sambas3-logs (evidence); agent-device only for milestone screenshots.
---

# SambaS3 Device Test — Orchestrator

Single deterministic path. No method-switching, no coordinate guessing, no open-ended waits.

## Canonical run (copy-paste)

```bash
SERIAL='7d6afed8'
GAME='/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584'

./scripts/debug-launch-game.sh "$SERIAL" "$GAME"

# One agent-device snapshot here, only to see current screen.

./scripts/debug-pad.sh "$SERIAL" START

./scripts/get-samba-logs.sh "$SERIAL" "/tmp/rdr-$(date +%Y%m%d-%H%M%S)"
```

## Rules (hard)

1. **Tool-call budget: ~10 commands max before the first launch attempt.** Resolve `SERIAL` + `GAME` from `adb devices` and `games.json`, then launch. No log spelunking, screenshot loops, or settings tours first.
2. **Launch only via `scripts/debug-launch-game.sh`.** It starts exported `MainActivity` (RPCSX `openLibrary` + `initialize`, `DebugPadReceiver` registered in `onResume`), then warm-starts `RPCSXActivity` with the exact path. Max **2 attempts**, **20s** focus cap each.
3. **Never shell-start `RPCSXActivity` directly** (`android:exported="false"` in `AndroidManifest.xml`; cold start after `force-stop` without `MainActivity` init risks the `getState` null crash). Never `am force-stop` before launch unless testing cold boot explicitly.
4. **Input only via `scripts/debug-pad.sh`.** Broadcast bridge is rotation-independent. No `input tap` coordinates, no `PadOverlay` math.
5. **agent-device only for milestone screenshots** (e.g. one after launch, one after input). Read `agent-device help workflow` first. Not a launch/input system.
6. **After any failure: collect logs before relaunching.** One call: `scripts/get-samba-logs.sh`. Max two launch attempts per hypothesis, then stop and report evidence dir.
7. **Verify pad delivery via `DebugPad` log**, not `Broadcast completed: result=0` (result 0 only means the broadcast was sent; the script already checks the log).

## Triage order on failure

1. `get-samba-logs.sh` output dir (it prints FATAL/Vulkan/RSX summaries itself).
2. `exit-info.txt` (OOM / crash / kill reason) + `logcat-crash.log`.
3. `activity-state.txt` / `window-state.txt` / `surfaceflinger.txt` for lifecycle vs driver.
4. Rotated `rpcsx_backend.log.1/.2` for pre-crash history (already grepped by the collector).

## References

- `sambas3-game-launch` (launch detail), `sambas3-controller` (button table), `sambas3-logs` (evidence detail).
- Scripts: `scripts/debug-launch-game.sh`, `scripts/debug-pad.sh`, `scripts/get-samba-logs.sh`.
- Code: `AndroidManifest.xml` (exported flags), `MainActivity.kt:134-137` (receiver register), `RPCSXActivity.kt:162-177` (cold-init fallback), `debug/DebugPadReceiver.kt` (bridge).
