#!/usr/bin/env bash
# get-samba-logs.sh — one-shot SambaS3 evidence collector (all log types, one call).
# Covers: app/backend/vulkan(+rotated .1/.2), frontend/JNI errors, legacy
# RPCSX/TTY logs, shader log, crash buffer, full logcat, events, kernel,
# dropbox crashes, tombstones/ANR (best-effort), exit-info, activity/window/
# SurfaceFlinger/input state, thermal, memory, pstore.
# Usage: ./scripts/get-samba-logs.sh [SERIAL] [OUTDIR]
#   SERIAL defaults to the single connected device (refuses ambiguous multi-device).
#   OUTDIR defaults to /tmp/samba-logs-<timestamp>.
set -euo pipefail

device_count() { adb devices | awk 'NR>1 && $2=="device"{c++} END{print c+0}'; }
first_device() { adb devices | awk 'NR>1 && $2=="device"{print $1; exit}'; }

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  echo "Usage: $0 [SERIAL] [OUTDIR]"
  exit 0
fi

SERIAL="${1:-}"
OUTDIR="${2:-/tmp/samba-logs-$(date +%Y%m%d-%H%M%S)}"
if [[ -z "$SERIAL" ]]; then
  n=$(device_count)
  if [[ "$n" -eq 0 ]]; then echo "No device"; exit 1; fi
  if [[ "$n" -gt 1 ]]; then echo "Ambiguous: $n devices connected, pass SERIAL explicitly"; adb devices; exit 1; fi
  SERIAL="$(first_device)"
fi
if [[ -z "$SERIAL" ]]; then echo "No device"; exit 1; fi
mkdir -p "$OUTDIR"
BASE="/storage/emulated/0/Android/data/com.zenithblue.sambas3/files"
PKG="com.zenithblue.sambas3"

echo "[*] Collecting from $SERIAL -> $OUTDIR"
{
  echo "serial=$SERIAL"
  echo "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "pkg=$PKG"
  adb -s "$SERIAL" shell getprop ro.build.fingerprint 2>/dev/null | sed 's/^/fingerprint=/'
  adb -s "$SERIAL" shell getprop ro.soc.model 2>/dev/null | sed 's/^/soc=/'
  adb -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | sed 's/^/android=/'
  adb -s "$SERIAL" shell "dumpsys package $PKG 2>/dev/null | grep -E 'versionName|versionCode' | head -2"
} > "$OUTDIR/manifest.txt" 2>/dev/null || true

pull_one() { # remote -> local (best-effort, never fails the run)
  adb -s "$SERIAL" pull "$1" "$2" >/dev/null 2>&1 || true
}

# --- 1. App-managed logs, all rotated (BACKEND 25MB / VULKAN 15MB / APP 10MB) ---
for f in rpcsx_backend.log rpcsx_backend.log.1 rpcsx_backend.log.2 \
         rpcsx_vulkan.log rpcsx_vulkan.log.1 rpcsx_vulkan.log.2 \
         rpcsx_app.log rpcsx_app.log.1 rpcsx_app.log.2; do
  pull_one "$BASE/logs/$f" "$OUTDIR/$f"
done
adb -s "$SERIAL" shell "ls -l $BASE/logs/" > "$OUTDIR/logs-ls.txt" 2>/dev/null || true

# --- 2. Legacy emulator logs (cache) ---
for f in TTY.log RPCSX.log RPCSX.old.log RPCSX.log.gz; do
  adb -s "$SERIAL" shell "cat $BASE/cache/$f" > "$OUTDIR/cache-$f" 2>/dev/null || true
done
adb -s "$SERIAL" shell "ls -l $BASE/cache/; echo ---; ls $BASE/cache/shaderlog/ 2>/dev/null | head; echo ---; du -sh $BASE/cache/ppu_progs $BASE/cache/spu_progs 2>/dev/null" > "$OUTDIR/cache-ls.txt" 2>/dev/null || true

# --- 3. logcat: crash + main/system + events + kernel (best-effort) ---
adb -s "$SERIAL" logcat -b crash -d > "$OUTDIR/logcat-crash.log" 2>/dev/null || true
adb -s "$SERIAL" logcat -d -b main,system -t 5000 > "$OUTDIR/logcat-tail.log" 2>/dev/null || true
adb -s "$SERIAL" logcat -d -b events -t 2000 > "$OUTDIR/logcat-events.log" 2>/dev/null || true
adb -s "$SERIAL" logcat -d -b kernel -t 500 > "$OUTDIR/logcat-kernel.log" 2>/dev/null || true
# sambas3-only slice for quick reading
grep -a -E "RPCS3|RPCSX|S3[A-Z]+|DebugPad|FATAL|AndroidRuntime" "$OUTDIR/logcat-tail.log" 2>/dev/null | tail -n 300 > "$OUTDIR/logcat-sambas3.txt" || true

# --- 4. Crash evidence: dropbox + tombstones + ANR (best-effort) ---
adb -s "$SERIAL" shell "dumpsys dropbox 2>/dev/null | grep -B2 -A8 -i 'sambas3\|data_app_crash\|data_app_anr' | head -n 120" > "$OUTDIR/dropbox.txt" 2>/dev/null || true
adb -s "$SERIAL" shell "ls -l /data/tombstones/ 2>&1 | head; echo ---; ls -l /data/anr/ 2>&1 | head" > "$OUTDIR/tombstone-anr-ls.txt" 2>/dev/null || true

# --- 5. Android lifecycle / system state ---
adb -s "$SERIAL" shell "dumpsys activity exit-info $PKG" > "$OUTDIR/exit-info.txt" 2>/dev/null || true
adb -s "$SERIAL" shell "dumpsys activity activities" > "$OUTDIR/activity-state.txt" 2>/dev/null || true
adb -s "$SERIAL" shell "dumpsys window" > "$OUTDIR/window-state.txt" 2>/dev/null || true
adb -s "$SERIAL" shell "dumpsys SurfaceFlinger" > "$OUTDIR/surfaceflinger.txt" 2>/dev/null || true
adb -s "$SERIAL" shell "dumpsys input" > "$OUTDIR/input-state.txt" 2>/dev/null || true
adb -s "$SERIAL" shell "dumpsys thermalservice" > "$OUTDIR/thermal.txt" 2>/dev/null || true
adb -s "$SERIAL" shell "dumpsys meminfo $PKG" > "$OUTDIR/meminfo.txt" 2>/dev/null || true
adb -s "$SERIAL" shell "cat /proc/meminfo" > "$OUTDIR/proc-meminfo.txt" 2>/dev/null || true
adb -s "$SERIAL" shell "ls -l /sys/fs/pstore/ 2>/dev/null; echo ---; cat /sys/fs/pstore/console-ramoops 2>/dev/null | tail -n 200" > "$OUTDIR/pstore.txt" 2>/dev/null || true

echo "[*] Files:"
ls -lh "$OUTDIR" | awk '{print $9, $5}'
echo ""
echo "--- Backend FATAL/Access violation (backend + rotated, last 30) ---"
cat "$OUTDIR"/rpcsx_backend.log* 2>/dev/null | grep -a -i -n "Access violation\|F \/\|Fatal signal\|SIGSEGV\|SIGABRT" | tail -n 30 || echo "(none)"
echo ""
echo "--- Frontend/JNI errors (app log + rotated, last 20) ---"
cat "$OUTDIR"/rpcsx_app.log* 2>/dev/null | grep -a -i -n "FATAL\|AndroidRuntime\|Emulation has been frozen\|renderer.*error\|frontend.*error" | tail -n 20 || echo "(none)"
echo ""
echo "--- Vulkan errors (vulkan + rotated, last 30) ---"
cat "$OUTDIR"/rpcsx_vulkan.log* 2>/dev/null | grep -a -i -n "vk_error\|device lost\|dequeueBuffer" | tail -n 30 || echo "(none)"
echo ""
echo "--- RSX sleepy (last 20) ---"
cat "$OUTDIR"/rpcsx_backend.log* 2>/dev/null | grep -a -n "rsx::thread.*sleepy" | tail -n 20 || echo "(none)"
echo ""
echo "--- Crash buffer (last 20) ---"
grep -a -i -n "FATAL\|sambas3\|Build fingerprint" "$OUTDIR/logcat-crash.log" 2>/dev/null | tail -n 20 || echo "(empty crash buffer)"
echo ""
echo "--- Exit-info summary ---"
grep -a -i -A3 "reason=\|rss=" "$OUTDIR/exit-info.txt" 2>/dev/null | head -n 40 || echo "(no exit-info)"
echo ""
echo "[*] Done: $OUTDIR"
