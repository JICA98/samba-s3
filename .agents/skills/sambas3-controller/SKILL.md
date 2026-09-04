---
name: sambas3-controller
description: Deterministic SambaS3 pad input via ADB broadcast bridge (rotation-independent, no coordinates). Use for X/Circle/Start presses, menu automation, or gameplay input. Canonical tool is scripts/debug-pad.sh with DebugPad-log verification.
---

# SambaS3 Controller — Broadcast Bridge (canonical)

No coordinates. `PadOverlay` layout is proportional (`PadOverlay.kt:113`) and shifts with rotation/resolution — never compute tap positions.

## Canonical

```bash
SERIAL='7d6afed8'
./scripts/debug-pad.sh "$SERIAL" CROSS    # X pulse (120ms press → release)
./scripts/debug-pad.sh "$SERIAL" START
./scripts/debug-pad.sh --help
```

Single-device shorthand (refused when 0 or >1 devices — pass `SERIAL` explicitly then):

```bash
./scripts/debug-pad.sh START
```

## All buttons

`CROSS SQUARE CIRCLE TRIANGLE L1 R1 L2 R2 START SELECT PS UP DOWN LEFT RIGHT L3 R3`

Raw digital + sticks (menu navigation, holds, combos):

```bash
./scripts/debug-pad.sh "$SERIAL" --raw --ei d1 0 --ei d2 64 --ei lx 127 --ei ly 127 --ei rx 127 --ei ry 127  # CROSS hold
./scripts/debug-pad.sh "$SERIAL" --raw --ei d1 0 --ei d2 0  --ei lx 127 --ei ly 127 --ei rx 127 --ei ry 127  # release
./scripts/debug-pad.sh "$SERIAL" --raw --ei d1 16 --ei d2 64 --ei lx 127 --ei ly 127 --ei rx 127 --ei ry 127 # UP+CROSS
```

Bitfields (`RPCSX.kt`): Digital1 `SELECT 1, L3 2, R3 4, START 8, UP 16, RIGHT 32, DOWN 64, LEFT 128, PS 256`; Digital2 `L2 1, R2 2, L1 4, R1 8, TRIANGLE 16, CIRCLE 32, CROSS 64, SQUARE 128`. Sticks `0..255`, center `127`.

## Gameplay helper (`scripts/gamepad.sh`)

For sequences/holds/loops use the wrapper (same bridge, no coordinates):

```bash
./scripts/gamepad.sh "$SERIAL" list                 # physical gamepads + bridge status
./scripts/gamepad.sh "$SERIAL" press CROSS 3 4      # CROSS x3, 4s gap (EULA accept)
./scripts/gamepad.sh "$SERIAL" hold CROSS 2         # raw hold 2s then release
./scripts/gamepad.sh "$SERIAL" seq "CROSS,START,UP" # sequence, 2s gap
./scripts/gamepad.sh "$SERIAL" stick LEFT 2         # left-stick push then center
./scripts/gamepad.sh "$SERIAL" eula                 # CROSS x3 loop
```

## Verification (the script does this)

`am broadcast` printing `Broadcast completed: result=0` proves nothing about delivery. `debug-pad.sh` greps `logcat -b main` for `DebugPad.*<BTN>` after each send:

- Verified line → delivered (`BUTTON <BTN> d1=.. d2=.. press 120ms` → `release`, `Log.w("DebugPad")` → BACKEND file via `LogMonitor`).
- No `DebugPad` at all → receiver not registered: ensure debug APK (`DebugPadReceiver.kt`, registered in `MainActivity.kt:136` and `RPCSXActivity.kt:372`) and that `MainActivity`/`RPCSXActivity` is foregrounded (`dumpsys window mCurrentFocus`).
- Game frozen (`Emulation has been frozen!`) → pad cannot unfreeze it; collect logs.

## Loop pattern (GTA EULA example)

```bash
SERIAL='7d6afed8'
for _ in 1 2 3; do ./scripts/debug-pad.sh "$SERIAL" CROSS; sleep 4; done
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Unknown <BTN>` | Use the button list above or `--raw`. Shorthand-without-serial accepts every button. |
| `Ambiguous: N devices` | Pass `SERIAL` explicitly. |
| No `DebugPad` log | Reinstall debug APK, foreground `MainActivity`/`RPCSXActivity`, retry once. |
| Stick no-move | Center is `127`, not `0`. |

## References

- `debug/DebugPadReceiver.kt` (actions, 120ms pulse), `RPCSX.kt` (flags, `overlayPadData`), `scripts/debug-pad.sh`.
