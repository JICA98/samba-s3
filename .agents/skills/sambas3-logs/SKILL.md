---
name: sambas3-logs
description: Fetch and triage SambaS3 PS3 backend logs (RPCS3, Vulkan, App) from device or in-app Log Monitor. Use when diagnosing game crashes, boot failures, Vulkan/device-lost errors, PPU/SPU traps, shader compilation, or GTA San Andreas loading and performance. Provides exact file paths, adb pulls, in-app share flow, and grep filters for RSX, Mali, and MediaTek.
---

# SambaS3 Logs — Quick Get & Triage

## Log locations on device

| Source | File (inside app private dir) | ADB path |
|---|---|---|
| Backend (RPCS3, Cell, Kernel, PPU/SPU, RSX) | `files/logs/rpcsx_backend.log` (25 MB rotated) | `/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/logs/rpcsx_backend.log` |
| Vulkan / Driver (Mali, Turnip, freedreno) | `files/logs/rpcsx_vulkan.log` (15 MB) | `.../logs/rpcsx_vulkan.log` |
| App wrapper | `files/logs/rpcsx_app.log` (10 MB) | `.../logs/rpcsx_app.log` |
| Legacy | `files/cache/RPCSX.log`, `.../TTY.log` | `.../cache/RPCSX.log` |

All three active files are written live by `LogMonitor.kt:203` (`logcat -v threadtime -b main,crash,system` → `Channel` → per-category `BufferedWriter`). Rotated as `*.1`, `*.2` when `>maxBytes` (`LogMonitor.kt:264`).

In-app UI: Settings → Logs (`LogMonitorScreen.kt:108`) — filter by level/source, copy row, **Share** exports all three files via `FileProvider` (`LogMonitorScreen.kt:570`, authority `${packageName}.provider`).

## Fastest: adb (MediaTek device `Y5WWBMJVOZSK4HU8`)

```bash
# one-shot pull of all current logs
adb -s Y5WWBMJVOZSK4HU8 shell "ls -lh /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/logs/" 
adb -s Y5WWBMJVOZSK4HU8 pull /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/logs/rpcsx_backend.log /tmp/samba-backend.log
adb -s Y5WWBMJVOZSK4HU8 pull /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/logs/rpcsx_vulkan.log /tmp/samba-vulkan.log
adb -s Y5WWBMJVOZSK4HU8 pull /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/logs/rpcsx_app.log /tmp/samba-app.log
adb -s Y5WWBMJVOZSK4HU8 shell "cat /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/cache/TTY.log" > /tmp/samba-tty.log

# live tail while reproducing a crash (run in second terminal)
adb -s Y5WWBMJVOZSK4HU8 shell "tail -F /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/logs/rpcsx_backend.log" | tee /tmp/live-backend.log

# or just use logcat (what LogMonitor itself tails)
adb -s Y5WWBMJVOZSK4HU8 logcat -v threadtime -b main,crash,system | grep -E "RPCS3|RPCSX|FATAL|Access violation|rsx::|VK_|vulkan|dequeueBuffer" | tee /tmp/live-logcat.log
```

If only one device is connected, omit `-s SERIAL`.

## Fastest: in-app (no adb)

1. Play until the crash, do **not** force-stop the app.
2. Settings (gear in library) → `Advanced Settings` → `Log Monitor` (or `Settings → Logs` in split-pane).
3. Filter: Source `RPCSX` + `VULKAN`, Level `ERROR`/`FATAL` to isolate the cutscene frame.
4. Bottom bar → Share icon → sends `rpcsx_backend.log` + `rpcsx_vulkan.log` + `rpcsx_app.log` as `text/plain` via chooser (email, Drive, Telegram).

## Triage cheat-sheet

```bash
# counts
wc -l /tmp/samba-backend.log; grep -c "F/" /tmp/samba-backend.log; grep -c "Access violation" /tmp/samba-backend.log

# crash signatures
grep -i -n "Access violation\|F \/|Fatal signal\|SIGSEGV\|SIGABRT\|trap\|TRAP" /tmp/samba-backend.log | tail -n 30
grep -i -n "vk_error\|device lost\|VK_ERROR_DEVICE_LOST\|dequeueBuffer" /tmp/samba-vulkan.log | tail -n 30
grep -i -n "rsx.*error\|rsx::thread.*sleepy\|LLVM.*error\|cellVdec\|cellAtrac" /tmp/samba-backend.log | tail -n 30

# GTA SA cutscene specific (MediaTek Mali)
grep -i -n "cellpamf\|cellDmuxPamf\|vdec\|avc\|pamf\|sail\|ATRAC\|atrac\|PS3Data.obb" /tmp/samba-backend.log | tail -n 100
grep -n "sys_fs_open.*Shaders\|CELL_EPERM\|CELL_ENOENT" /tmp/samba-backend.log | tail -n 50

# timeline around crash (replace timestamp)
grep "08-24 08:1" /tmp/samba-backend.log | tail -n 200 > /tmp/cutscene-window.log
```

Known MediaTek findings for GTA SA (BLUS31584) on `mt6897` (Dimensity 8300 Ultra, Mali-G615 MC6):

- The former `Access violation reading location 0x0` / `Too many objects without modelinfo structures` loading failure was **not Mali-specific**. The installed `PS3DataMain.obb` was truncated to `1,073,739,776` bytes; the source archive is `1,479,813,213` bytes. With the complete file and the temporary null-skip patch disabled, the title reaches correctly rendered Grove Street gameplay.
- WCB/RCB/WDB/RDB, Multithreaded RSX, Accurate RSX reservation, interpreter variants and the temporary PPU null-skip patch did not repair the incomplete-data failure. Do not present those settings as its fix. The skip patch is harmful with complete data because it can remove required world/collision setup.
- Verify GTA data before tuning: `PS3DataMain.obb = 1,479,813,213` bytes and `PS3Data.obb = 708,640,703` bytes.
- Vulkan use is confirmed only by lines such as `Found Vulkan-compatible GPU: 'Mali-G615 MC6' running on driver 44.1.0` and `Vulkan: Renderer initialized on device 'Mali-G615 MC6'`. `Renderer: Cubeb` refers to audio.
- The game repeatedly opens `/dev_bdvd/PS3_GAME/USRDIR/Shaders/*.vert|*.pix`; missing reads return `CELL_ENOENT` and create attempts return `CELL_EPERM` because the disc mount is read-only. Count these alongside `Program compiled successfully` to distinguish shader churn from steady-state performance.
- This reference device has thermal limits removed and the GPU clock forced to 1.400 GHz. Maximum OPP, temperature and `/proc/mtk_mali/gpu_utilization ACTIVE` are not reliable proof of throttling or saturation: an idle/background sample reported `ACTIVE=96` with `3D/TA/COMPUTE=0/0/0`. Use validated busy/total counters, per-frame timings and active-game thread samples.
- **Boot crash (not game crash):** `F libc: Fatal signal 11 pc 0x0 ... Java_com_zenithblue_sambas3_RPCSX_getState+32` → `rpcsxLib.getState()` nullptr before `dlopen` (cold `am force-stop` → direct `RPCSXActivity` without `MainActivity` init `RPCSX.kt:137 openLibrary()` `MainActivity.kt:62`). Warm via `MainActivity` first or rebuild with `native-lib.cpp:198` `if (!rpcsxLib.getState) return 0` guard + `RPCSXActivity.kt:52` cold `openLibrary+initialize`.
- `E/RPCS3: Thread [rsx::thread] is too sleepy` is a scheduling warning, not fatal alone. Vulkan `dequeueBuffer failed: No such device (-19)` at the same time usually accompanies surface destruction, app pause or activity recreation. Check lifecycle timestamps before blaming the driver.
- Treat `VK_ERROR_DEVICE_LOST` as a driver/GPU failure only when it is actually present in the captured run. Do not infer it from low FPS, shader compilation, `rsx::thread is too sleepy`, or `dequeueBuffer -19` alone.

## GTA San Andreas loading/performance — capture recipe

```bash
# start live capture in terminal 1; note the launch timestamp rather than deleting a live writer's file
adb -s Y5WWBMJVOZSK4HU8 shell "tail -F /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/logs/rpcsx_backend.log" | tee /tmp/gta-cutscene.log &

# terminal 2: launch game via app (or adb)
adb -s Y5WWBMJVOZSK4HU8 shell am start -n com.zenithblue.sambas3/.RPCSXActivity -e path "/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584"

# play through EULA → New Game → Grove Street; after the measured path, wait 5s then:
adb -s Y5WWBMJVOZSK4HU8 shell "logcat -b crash -d > /tmp/crash.log; cat /tmp/crash.log" | tail -n 80
adb -s Y5WWBMJVOZSK4HU8 pull /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/logs/rpcsx_backend.log /tmp/gta-backend-crash.log
adb -s Y5WWBMJVOZSK4HU8 pull /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/logs/rpcsx_vulkan.log /tmp/gta-vulkan-crash.log

# then grep:
grep -B5 -A30 "Access violation\|FATAL\|rsx.*failed\|VK_ERROR\|Program compiled successfully\|sys_fs_open.*Shaders" /tmp/gta-backend-crash.log | tail -n 160
```

Share those three files plus the cutscene window when filing the issue.

## Helper script (`scripts/get-samba-logs.sh`)

If present, it wraps the adb pulls above and prints the triage greps. If not, use the one-shot pull block verbatim.

## References

- `app/src/main/java/com/zenithblue/sambas3/LogMonitor.kt:1` — tag classification, file categories, rotation, logcat reader
- `app/src/main/java/com/zenithblue/sambas3/ui/settings/LogMonitorScreen.kt:1` — filter chips, share via FileProvider
- `app/src/main/java/com/zenithblue/sambas3/MainActivity.kt:23` + `LogMonitor.kt:203` — startup (`LogMonitor.start(this)`)
- Device: `Y5WWBMJVOZSK4HU8` (`2311DRK48I`/duchamp, `mt6897`, Dimensity 8300 Ultra, Mali-G615 MC6, 7.16 GiB); device performance telemetry is modified as documented above
- GTA status and evidence: `docs/games/BLUS31584-GTA-San-Andreas.md`; global GPU forecast: `docs/games/GPU-COMPATIBILITY.md`
