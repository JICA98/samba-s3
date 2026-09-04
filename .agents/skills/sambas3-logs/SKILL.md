---
name: sambas3-logs
description: One-shot SambaS3 evidence collection + triage. Use after any boot failure, crash, hang, or perf run. Canonical tool is scripts/get-samba-logs.sh (rotated logs + exit-info/SurfaceFlinger/thermal/mem/pstore). Run once per failure before any relaunch.
---

# SambaS3 Logs — One-Shot Evidence

## Canonical (one call, no manual multi-pull)

```bash
SERIAL='7d6afed8'
./scripts/get-samba-logs.sh "$SERIAL" "/tmp/run-$(date +%Y%m%d-%H%M%S)"
```

Collector pulls in one call:

- Rotated app logs: `rpcsx_backend.log{,.1,.2}` (25MB), `rpcsx_vulkan.log{,.1,.2}` (15MB), `rpcsx_app.log{,.1,.2}` (10MB) — `LogMonitor.kt:53-54,243,270-277` — plus `logs-ls.txt`, `cache/TTY.log`, `cache/RPCSX.log`.
- `logcat-crash.log` (`-b crash`), `logcat-tail.log` (`-b main,system -t 5000`), `logcat-events.log` (`-b events -t 2000`).
- Android evidence: `exit-info.txt` (`dumpsys activity exit-info`), `activity-state.txt`, `window-state.txt`, `surfaceflinger.txt`, `thermal.txt` (`thermalservice`), `meminfo.txt` + `proc-meminfo.txt`, `pstore.txt` (best-effort).
- Prints triage summaries itself: backend FATAL/`Access violation`, Vulkan `VK_ERROR`/`device lost`/`dequeueBuffer`, `rsx::thread sleepy`, exit-info reasons.

In-app alternative (no adb): Settings → Logs → filter Source/Level → Share via `FileProvider`.

## Triage

```bash
OUT=/tmp/run-...  # dir printed by the collector
cat "$OUT"/rpcsx_backend.log* | grep -i -n "Access violation\|Fatal signal\|SIGSEGV\|SIGABRT" | tail -30
cat "$OUT"/rpcsx_vulkan.log*  | grep -i -n "vk_error\|device lost\|dequeueBuffer" | tail -30
grep -i -A3 "reason=" "$OUT/exit-info.txt" | head -40   # OOM vs crash vs kill
```

Interpretation notes:

- `F libc: Fatal signal 11 pc 0x0 ... Java_com_zenithblue_sambas3_RPCSX_getState` = cold boot without `MainActivity` init (`native-lib.cpp` null guard + `RPCSXActivity.kt` cold `openLibrary`). Fix is launch order, not GPU settings.
- `E/RPCS3: Thread [rsx::thread] is too sleepy` alone is a scheduling warning. `dequeueBuffer failed: No such device (-19)` alongside usually means surface destruction/pause/recreation — check `activity-state.txt` timestamps before blaming the driver.
- `VK_ERROR_DEVICE_LOST` counts only when literally present. Do not infer it from low FPS or shader churn.
- Vulkan confirmed only by `Found Vulkan-compatible GPU` + `Vulkan: Renderer initialized on device`. `Renderer: Cubeb` is audio.
- Game-data first: for I/O-shaped failures (e.g. repeated `CELL_ENOENT`/`CELL_EPERM` on read-only mounts, missing-object cascades), verify installed data sizes before tuning accuracy/readback settings.

## Rules

- Run the collector **once after each run**, **before** any relaunch.
- Never delete a live writer's log to "reset" — note the launch timestamp and use rotated `.1/.2` + `logcat -t` windows instead.
- File issues with the whole `OUT` dir (or at least backend+vulkan+exit-info+crash logcat).

## References

- `LogMonitor.kt` (categories, rotation, logcat reader), `ui/settings/LogMonitorScreen.kt` (filter/share), `scripts/get-samba-logs.sh`.
- Per-game notes: `docs/games/` (e.g. `BLUS31584-GTA-San-Andreas.md`), `docs/games/GPU-COMPATIBILITY.md`.
