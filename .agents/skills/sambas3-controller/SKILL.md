---
name: sambas3-controller
description: Agent ADB controller bridge for SambaS3 — bypass PadOverlay coordinate math via broadcast. Use when you need deterministic X/Circle/Start presses in loops, in-game menu automation, or debugging pad without touching the overlay. Pairs with sambas3-game-launch (which documents warm/cold launch + 1632,873 fallback) and sambas3-logs for triage.
---

# SambaS3 Controller — ADB Broadcast Bridge

**Why separate skill:** `PadOverlay.kt:69` is a `SurfaceView` with proportional layout (`PadOverlay.kt:113` `btnSize = min(display)/8`, `faceX/faceY` etc.). Taps like `input tap 840 830` broke after rotation (`1920×1080` vs `1080×2400`); the MediaTek calibration is `1632,873` (`docs/games/BLUS31584-GTA-San-Andreas.md`). The new in-app bridge `debug/DebugPadReceiver.kt` is **rotation-agnostic, no coordinates**, and works from any lifecycle (MainActivity + RPCSXActivity).

## 1. Prereqs (new debug build, 2026-08-24)

- APK contains `debug/DebugPadReceiver.kt` + `ui/debug/DebugControllerScreen.kt` (route `debug_controller`, Settings → Debug — Controller).
- Receiver registered in `MainActivity.kt:48` and `RPCSXActivity.kt:55` (`DebugPadReceiver.register(this)`). Log tag `DebugPad` → `LogMonitor` `BACKEND` file.
- Verify on device:

```bash
adb -s Y5WWBMJVOZSK4HU8 shell "dumpsys activity top | grep -i DebugPad || echo no-vm-check"
adb -s Y5WWBMJVOZSK4HU8 shell "logcat -d -b main -t 5 | grep DebugPad || echo not-yet-used"
adb -s Y5WWBMJVOZSK4HU8 shell "pm dump com.zenithblue.sambas3 | grep DEBUG_PAD | head"
```

## 2. ADB Commands (agent canonical)

### One-button pulse (120 ms press → release, PPU-safe)

```bash
# Cross = X (Digital2Flags.CELL_PAD_CTRL_CROSS, Digital1 0 + Digital2 64)
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_CROSS
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_CIRCLE
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_SQUARE
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_TRIANGLE
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_START
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_SELECT
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_PS
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_L1
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_R1
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_L2
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_R2
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_UP
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_DOWN
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_LEFT
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_RIGHT
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_L3
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_R3
```

Helper: `./scripts/debug-pad.sh Y5WWBMJVOZSK4HU8 CROSS` (wrapper for the above).

### Raw digital + sticks (for menu navigation, not just one press)

```bash
# d1 = Digital1 bitfield (PS/Select/Start/Dpad/L3/R3), d2 = Digital2 (Cross/Circle/.../L2/R2), lx/ly/rx/ry 0-255 (127 center)
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD --ei d1 0 --ei d2 64 --ei lx 127 --ei ly 127 --ei rx 127 --ei ry 127  # Cross hold
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD --ei d1 0 --ei d2 0 --ei lx 127 --ei ly 127 --ei rx 127 --ei ry 127  # release (all zero + centered sticks)
# Dpad up: d1 16
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD --ei d1 16 --ei d2 0 --ei lx 127 --ei ly 127 --ei rx 127 --ei ry 127
# Left stick left: lx 0
adb -s Y5WWBMJVOZSK4HU8 shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD --ei d1 0 --ei d2 0 --ei lx 0 --ei ly 127 --ei rx 127 --ei ry 127
```

- `Digital1Flags` (`RPCSX.kt:6`): `SELECT 1, L3 2, R3 4, START 8, UP 16, RIGHT 32, DOWN 64, LEFT 128, PS 256`
- `Digital2Flags` (`RPCSX.kt:20`): `L2 1, R2 2, L1 4, R1 8, TRIANGLE 16, CIRCLE 32, CROSS 64, SQUARE 128`
- Sticks: `0=low/left/up`, `127=center`, `255=high/right/down`. `PadOverlay.kt:87 state.leftStickX/Y` feed directly into `RPCSX.instance.overlayPadData(d1,d2,lx,ly,rx,ry)` (`RPCSX.kt:84`/`native-lib.cpp:158`).

### Fallback: coordinate tap (when broadcast unavailable — old APK or before cold fix)

```bash
# MediaTek Y5WWBMJVOZSK4HU8 override 1920×1080 → Cross 1632,873 (python calc in sambas3-game-launch § Example)
adb -s Y5WWBMJVOZSK4HU8 shell input tap 1632 873
# Never use portrait 823,2021 for this landscape game (see that skill’s derivation).
```

## 3. Agent Loop Pattern (GTA SA EULA → in-game after cutscene)

```bash
SERIAL=Y5WWBMJVOZSK4HU8
GAME=/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584
adb -s $SERIAL shell "am start -n com.zenithblue.sambas3/.RPCSXActivity -e path $GAME"
sleep 25  # wait for Rockstar North → EULA (or poll Snapshot for PRESS)
for _ in 1 2 3; do
  adb -s $SERIAL shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_CROSS
  sleep 4
done
# then watch for cutscene → in-game
for i in 1 2 3 4 5 6; do sleep 5; adb -s $SERIAL shell "grep -E 'modelinfo|Access violation|Emulation.*frozen' .../logs/rpcsx_backend.log | tail -3"; done
./scripts/get-samba-logs.sh $SERIAL /tmp/out
```

With a complete `PS3DataMain.obb` (`1,479,813,213` bytes) and the temporary null-skip patch disabled, this loop now reaches correctly rendered Grove Street gameplay on Mali-G615 MC6. The former `Access violation 0x0` / `modelinfo` failure documented in Iter 02–13 was caused by the truncated installed archive, not the controller or GPU. If it returns, validate archive size before settings changes.

## 4. In-App Debug UI (human + copyable cheat-sheet)

Settings → **Debug — Controller** (`AppNavHost.kt debug_controller`):

- Top card: ADB one-liner copy (selectable monospace, grey on `RPCSXColors.surface`).
- Middle: full GTA sequence loop (copy).
- Bottom: live test buttons `X / UP / Sticks` that call `overlayPadData` directly (120 ms pulse for X/UP, sticky for sticks). Last inject shown as `lastInject` text.
- Detail pane `SettingsDetailPane(focusedKey=debug_controller)` explains the bridge.

Use it to **verify** the receiver without a game: open Debug screen, tap `X`, `adb logcat -s DebugPad` should show `BUTTON CROSS d1=0 d2=64 press 120ms` → `release`, or tap via adb and see `lastInject` update.

## 5. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Broadcast completed: result 0` but no log / no game reaction | Receiver not registered (old APK) or `force-stop` before register | Re-install debug APK (`./gradlew :app:installStandardDebug`), ensure `MainActivity` or `RPCSXActivity` is foregrounded (`dumpsys window mCurrentFocus`). |
| `logcat` shows `DebugPad` press but game frozen | Game is already in `Emulation has been frozen!`; pad cannot unfreeze it. | Pull logs, then validate game-data sizes before changing `config.yml`. For GTA, check `PS3DataMain.obb = 1,479,813,213` bytes and ensure the null-skip patch is disabled. |
| Stick doesn’t move | Used `0` for center (should be `127`); or `overlayPadData` called with `null` lib before `dlopen` | Check `native-lib.cpp:158 guard if (!overlayPadData) return false`. Use `127` center. |
| Need Dpad + button combo | Broadcast is one-shot; send raw `DEBUG_PAD` with both `d1` and `d2` set. | `adb shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD --ei d1 16 --ei d2 64` (UP+CROSS). |

## 6. References

- `debug/DebugPadReceiver.kt:1` (action constants, `buttonToBits`, 120 ms handler), `RPCSX.kt:6/20/84`, `PadOverlay.kt:87/172/387`, `RPCSXActivity.kt:55` + `MainActivity.kt:48` registration, `ui/debug/DebugControllerScreen.kt:1`, `scripts/debug-pad.sh:1`.
- Device calibration `Y5WWBMJVOZSK4HU8` `1920×1080 → 1632,873` derived from `PadOverlay` `faceX=1430 faceY=576`.
- Pair with `sambas3-game-launch` § Controller logic (pad physics) and `sambas3-logs` for crash capture; per-game log registry `docs/games/`.
