# Official Backend Debugging — RPCSX Core → LogMonitor (Complete Logs)

This explains **how the RPCSX emulator core produces logs on Android** and how `SambaS3` captures them completely. Use it when you need verbose per-channel traces (RSX, PPU, `cellPamf`/`cellVdec`) for a per-game fix, not just the filtered tail.

## 1. Core architecture (`rpcs3/util/logs.hpp` → `rpcsx-android.cpp`)

- **Channels:** `LOG_CHANNEL(name)` macro creates a `logs::channel` with a name and an `enabled` level (`rpcs3/util/logs.hpp:195`). Example: `LOG_CHANNEL(rpcsx_android,"ANDROID")` (`android/src/rpcsx-android.cpp:105`), `LOG_CHANNEL(rsx_log,"RSX")`, `LOG_CHANNEL(cellPamf,"cellPamf")`, etc.
- **Message:** `channel.warning("fmt %s", val)` checks `if (*this <= enabled)` (`logs.hpp:144`) then `broadcast(fmt, ...)`.
- **Android bridge:** `struct LogListener : logs::listener` (`rpcsx-android.cpp:107`) registers via `logs::listener::add(this)`. Its `log(stamp,msg,prefix,text)` maps `logs::level::{fatal,error,todo,warning,notice,trace}` → `ANDROID_LOG_FATAL/ERROR/WARN/INFO/DEBUG` and calls `__android_log_print(ANDROID_LOG_ERROR, prefix.c_str(), "[%s] %s", ...)`. The `prefix` is the channel name (e.g. `RSX`, `cellVdec`, `PPU`, `RPCS3`), level char is `F/E/W/I/D`. All core logs therefore appear as **normal Android logcat entries** with tag `RSX`/`cellPamf`/etc., not as files.
- **File listener (desktop):** `logs::make_file_listener(path,max_size)` (`rpcs3/util/logs.cpp`) would write directly to `RPCS3.log`, but on Android it is **not used** — the Android path installs only the `android_log` listener. So there is no `rpcs3.log` on device; we reconstruct it from logcat.

## 2. App capture (`LogMonitor.kt`)

`MainActivity.kt:24 LogMonitor.start(this)` opens a long-running `logcat -v threadtime -b main,crash,system` process (`LogMonitor.kt:298`). Every line `MM-DD HH:MM:SS.mmm PID TID LEVEL TAG : MSG` is parsed (`linePattern`), `LogLevel.fromChar`, `classifyTag(tag)` (`LogMonitor.kt:62-161`) → `LogSource` → `LogFileCategory`:

| Source → File | Tags (excerpt) | File (inside `getExternalFilesDir("logs")`) |
|---|---|---|
| `RPCSX/KERNEL/CELL` → `BACKEND` | `RPCS3`, `RPCSX-UI`, `sys_tty`, `cellPamf`, `cellVdec`, `cellSpurs`, `ppu_log`, `HLE` | `rpcsx_backend.log` (25 MB) |
| `VULKAN/DRIVER` → `VULKAN` | `vulkan`, `adrenotools`, `mesa`, `turnip` | `rpcsx_vulkan.log` (15 MB) |
| `APP/OTHER` → `APP` | `Main`, `RPCSX State`, `DebugPad` | `rpcsx_app.log` (10 MB) |

A `Channel<LogEntry>(8192)` + `runConsumer` batches 20 lines, flushes `BufferedWriter`, rotates to `.1/.2` (`rotateIfNeeded`), and updates `StateFlow` for the in-app `LogMonitorScreen.kt:108` (filter by level/source, Share via `FileProvider`).

Legacy `TTY.log`/`RPCSX.log` under `files/cache/` are also mirrored for compatibility (`LogMonitor.kt:203`).

## 3. Getting complete logs (official)

### A. Via ADB (always complete, no UI needed) — `skills/sambas3-logs`

```bash
BASE=/storage/emulated/0/Android/data/com.zenithblue.sambas3/files
adb -s Y5WWBMJVOZSK4HU8 shell "ls -lh $BASE/logs/"
adb -s Y5WWBMJVOZSK4HU8 pull $BASE/logs/rpcsx_backend.log /tmp/b.log
adb -s Y5WWBMJVOZSK4HU8 pull $BASE/logs/rpcsx_vulkan.log /tmp/v.log
adb -s Y5WWBMJVOZSK4HU8 pull $BASE/logs/rpcsx_app.log /tmp/a.log
adb -s Y5WWBMJVOZSK4HU8 shell "cat $BASE/cache/TTY.log" > /tmp/tty.log
adb -s Y5WWBMJVOZSK4HU8 logcat -b crash -d > /tmp/crash.log  # tombstone for SIGSEGV pc 0x0
# live tail during reproduction
adb -s Y5WWBMJVOZSK4HU8 shell "tail -F $BASE/logs/rpcsx_backend.log" | tee /tmp/live.log
adb -s Y5WWBMJVOZSK4HU8 logcat -v threadtime -b main,crash,system | grep -E "RPCS3|RSX|cellPamf|Access violation" | tee /tmp/live2.log
# helper
./scripts/get-samba-logs.sh Y5WWBMJVOZSK4HU8 /tmp/out  # does pulls + triage greps
```

This is what `sambas3-logs` skill documents; the three files are already rotated complete logs.

### B. Via in-app Share (no adb)

Settings → **Log Monitor** (or Settings → Logs in split pane) → Source filter `RPCSX` + `VULKAN`, Level `VERBOSE` → **Share** → sends all three files `text/plain` via `FileProvider` (`LogMonitorScreen.kt:570`, `${packageName}.provider`).

### C. Verbose per channel (when you need `pamf/vdec` traces)

Default channel level is `notice` (`logs.hpp:113 enabled = level::notice`). To see `trace` (e.g. `RSX`, `cellVdec`) for a game:

```bash
# Option 1: via engine settings JSON (requires running core, so do it while Paused/Running)
# Any channel name from `logs::get_channels()` is valid. Level strings: Fatal, Error, Todo, Success, Warning, Notice, Trace
adb shell "cat $BASE/config/config.yml" | grep -A2 Log
# then via JNI (in-app Debug → Log Levels or direct RPC)
# RPCSX.instance.settingsSet("Log@@RSX", "\"Trace\"")  — path is "Log@@<channel>"
# Verify: adb shell "grep -i 'rsx.*trace' $BASE/logs/rpcsx_backend.log"

# Option 2: edit log config file if present ($BASE/config/log.cfg) and restart
adb -s Y5WWBMJVOZSK4HU8 shell "echo 'RSX:Trace\ncellPamf:Trace\ncellVdec:Trace\nPPU:Trace' > $BASE/config/log.cfg"
adb shell am force-stop com.zenithblue.sambas3; am start ... # restart
```

In-app **Debug → Log Levels** (planned) exposes `logs::get_channels()` as chips; toggling calls `RPCSX.settingsSet("Log@@"+channel, "\"Trace\"")` + `logs::set_level`.

## 4. GTA SA cutscene example (complete window)

With default `notice`, the window already contains the relevant spam:

```
08-24 09:07:46.055 D/RPCS3: sys_tty_write(): “Too many objects without modelinfo structures“ << endl  (×8-40)
08-24 09:07:46.190 F/RPCS3: Access violation reading location 0x0 (unmapped memory)
08-24 09:07:46.190 W/RPCS3: Emulation has been frozen! ...
08-24 09:07:46.919 D/RPCS3: PPU Syscall Usage Stats: sys_timer_usleep ...
```

For `pamf/vdec`, enable trace and re-pull:

```bash
grep -i -n "pamf\|vdec\|avc\|CELL_EPERM" /tmp/b.log | tail -n 100
grep -B5 -A30 "Access violation" /tmp/b.log | tail -n 80
```

## 5. Pitfalls fixed in SambaS3

- **Cold `getState` SIGSEGV `pc 0x0` (`Java_com_zenithblue_sambas3_RPCSX_getState+32`):** before `dlopen`, `rpcsxLib.getState==nullptr` (`native-lib.cpp:198`). Fixed with null guard returning `0` (Stopped) + `RPCSXActivity.kt:52` cold `openLibrary`/`initialize` fallback (see `patches/rpcsx-submodule-changes.patch:107` for Android `LOG_CHANNEL` wiring).
- **TTY duplication:** `sys_tty_write` appears both as `D/RPCS3` (backend) and raw TTY file; `LogMonitor` routes the former to `rpcsx_backend.log`, so you do not need to `cat TTY.log` separately unless you want raw.
- **Rotation:** `LogListener` uses `__android_log_print` with `prefix` as tag; `LogMonitor` classifies `RPCSX`= `RPCS3/RPCS...`, `CELL`=`cell*`, so filtering by `LogSource.CELL` shows `cellPamf`.

## References

- `rpcs3/util/logs.hpp:195 LOG_CHANNEL`, `android/src/rpcsx-android.cpp:107 LogListener`, `rpcs3/util/logs.cpp: make_file_listener`
- `app/src/main/cpp/native-lib.cpp:198` guards, `app/src/main/java/com/zenithblue/sambas3/LogMonitor.kt:298`, `MainActivity.kt:24`, `scripts/get-samba-logs.sh`, `.agents/skills/sambas3-logs/SKILL.md`
```

