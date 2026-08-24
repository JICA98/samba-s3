#!/usr/bin/env bash
# get-samba-logs.sh — pull SambaS3 logs from connected device and triage
# Usage: ./scripts/get-samba-logs.sh [SERIAL] [OUTDIR]
#   SERIAL defaults to first device (adb devices)
#   OUTDIR defaults to /tmp/samba-logs-$(date +%s)
set -euo pipefail
SERIAL="${1:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
OUTDIR="${2:-/tmp/samba-logs-$(date +%Y%m%d-%H%M%S)}"
if [[ -z "$SERIAL" ]]; then echo "No device"; exit 1; fi
mkdir -p "$OUTDIR"
BASE="/storage/emulated/0/Android/data/com.zenithblue.sambas3/files"
echo "[*] Pulling from $SERIAL -> $OUTDIR"
adb -s "$SERIAL" pull "$BASE/logs/rpcsx_backend.log" "$OUTDIR/" 2>&1 | tail -1
adb -s "$SERIAL" pull "$BASE/logs/rpcsx_vulkan.log" "$OUTDIR/" 2>&1 | tail -1
adb -s "$SERIAL" pull "$BASE/logs/rpcsx_app.log" "$OUTDIR/" 2>&1 | tail -1
adb -s "$SERIAL" shell "cat $BASE/cache/TTY.log" > "$OUTDIR/TTY.log" 2>/dev/null || true
adb -s "$SERIAL" shell "cat $BASE/cache/RPCSX.log" > "$OUTDIR/RPCSX.log" 2>/dev/null || true
adb -s "$SERIAL" logcat -b crash -d > "$OUTDIR/logcat-crash.log" 2>/dev/null || true
adb -s "$SERIAL" logcat -d -b main,system -t 2000 > "$OUTDIR/logcat-tail.log" 2>/dev/null || true
echo "[*] Files:"
ls -lh "$OUTDIR" | awk '{print $9, $5}'
echo ""
echo "--- Backend FATAL/Access violation (last 30) ---"
grep -i -n "Access violation\|F \/\|Fatal signal\|SIGSEGV\|SIGABRT" "$OUTDIR/rpcsx_backend.log" 2>/dev/null | tail -n 30 || echo "(none)"
echo ""
echo "--- Vulkan errors (last 30) ---"
grep -i -n "vk_error\|device lost\|dequeueBuffer" "$OUTDIR/rpcsx_vulkan.log" 2>/dev/null | tail -n 30 || echo "(none)"
echo ""
echo "--- RSX sleepy (last 20) ---"
grep -n "rsx::thread.*sleepy" "$OUTDIR/rpcsx_backend.log" 2>/dev/null | tail -n 20 || echo "(none)"
echo ""
echo "--- GTA SA pamf/vdec (last 30) ---"
grep -i -n "pamf\|vdec\|avc.*error" "$OUTDIR/rpcsx_backend.log" 2>/dev/null | tail -n 30 || echo "(none)"
echo ""
echo "[*] Done: $OUTDIR"
