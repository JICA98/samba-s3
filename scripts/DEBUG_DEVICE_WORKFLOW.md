# Debug device workflow

Build and install a current Standard Debug APK before using these scripts.
The launcher and controller require request-ID acknowledgements from
`DebugPadReceiver`; older APKs fail with a clear missing-acknowledgement error.

```bash
adb devices -l
SERIAL='adb-7d6afed8-mU47CV._adb-tls-connect._tcp'
GAME='/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS30758'
./scripts/debug-launch-game.sh "$SERIAL" "$GAME"
./scripts/debug-pad.sh "$SERIAL" START
./scripts/gamepad.sh "$SERIAL" stick UP 2
./scripts/get-samba-logs.sh "$SERIAL" /tmp/rdr-run-unique-name
./scripts/debug-stop-game.sh "$SERIAL"
```

Use the current `adb devices` serial, including the full wireless service name.
Omitting the serial is supported only with one connected device. Shell-quote
paths containing spaces. The launcher checks existence and then starts
MainActivity followed by the in-process boot broadcast. It never shell-starts
the non-exported RPCSXActivity and refuses to launch over a focused emulator.
An acknowledged boot gets 20 seconds to focus; that is not a gameplay verdict.

Input success means the native pad call returned and the corresponding request
was logged. A button pulse also requires a matching release. It does not prove
the game consumed the input; verify the visible transition. Raw input validates
bitfield/stick ranges and requires explicit neutralization after a hold.
`gamepad.sh` holds attempt release on exit or interruption.

Every bridge call retains a small `/tmp/samba-bridge-*/logcat.txt` evidence file.
Unacknowledged input returns nonzero. Do not retry blindly during a black screen.
Collect logs first, then stop through the coordinator. A failed/timed-out stop
must not be reported as a clean exit.

The collector saves PID-scoped logs, thread CPU usage, and process maps before
large rotated logs. Each ADB command is limited to 20 seconds (override with
`ADB_TIMEOUT_S`). Rotated app logs may be stale: check timestamps and the current
`cache-RPCSX.log`, `cache-TTY.log`, and `logcat-process.log`.

Host tests: `python3 -m unittest discover -s scripts/tests -p test_debug_bridge.py -v`.

## Turnip SYSMEM diagnostic

The debug APK accepts `com.zenithblue.sambas3.DEBUG_DRIVER_SYSMEM` with boolean
`enabled`. It requires a stopped core, updates the existing
`gpu_driver_force_sysmem` preference, and reapplies the stored driver. Record
the original value and restore it after the experiment. For a fresh driver
comparison, restart the process through MainActivity before booting a game.
Check both the acknowledgement and `GpuDriverSelection: TU_DEBUG=sysmem` log.
This is a diagnostic switch, not a compatibility recommendation.
