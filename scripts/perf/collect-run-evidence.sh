#!/usr/bin/env bash
# collect-run-evidence.sh — Comprehensive SambaS3 run evidence collection.
# Wraps scripts/get-samba-logs.sh with structured run metadata, memory, thermal,
# thread snapshot, and application exit info extraction.
#
# Usage:
#   ./scripts/perf/collect-run-evidence.sh [--run-id ID] [--scene NAME] [SERIAL] [OUTDIR]

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

PKG="com.zenithblue.sambas3"
SERIAL=""
OUTDIR=""
RUN_ID=""
SCENE_NAME="gow3-gaia-combat"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-id)
      RUN_ID="${2:-}"; shift 2 ;;
    --scene)
      SCENE_NAME="${2:-}"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [--run-id ID] [--scene NAME] [SERIAL] [OUTDIR]"
      exit 0 ;;
    *)
      if [[ -z "$SERIAL" ]]; then
        SERIAL="$1"
      elif [[ -z "$OUTDIR" ]]; then
        OUTDIR="$1"
      fi
      shift ;;
  esac
done

# Device discovery
device_count() { adb devices | awk 'NR>1 && $2=="device"{c++} END{print c+0}'; }
first_device() { adb devices | awk 'NR>1 && $2=="device"{print $1; exit}'; }

if [[ -z "$SERIAL" ]]; then
  n=$(device_count)
  if [[ "$n" -eq 0 ]]; then echo "Error: No ADB device connected" >&2; exit 1; fi
  if [[ "$n" -gt 1 ]]; then echo "Error: Ambiguous ($n devices). Pass SERIAL explicitly" >&2; adb devices; exit 1; fi
  SERIAL="$(first_device)"
fi

[[ "$(adb -s "$SERIAL" get-state 2>/dev/null || true)" == "device" ]] || {
  echo "Error: Device $SERIAL not available" >&2
  exit 1
}

TIMESTAMP="$(date -u +%Y%m%d-%H%M%S)"
RUN_ID="${RUN_ID:-gow3-run-$TIMESTAMP}"
OUTDIR="${OUTDIR:-$ROOT_DIR/docs/benchmarks/evidence-$RUN_ID}"
mkdir -p "$OUTDIR"

echo "================================================================================"
echo " SambaS3 Run Evidence Collection"
echo " Target Device: $SERIAL"
echo " Run ID:        $RUN_ID"
echo " Scene:         $SCENE_NAME"
echo " Output Dir:    $OUTDIR"
echo "================================================================================"

# 1. Fetch current running PID
PID="$(adb -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)"

# 2. Thread snapshot (Volatile: capture immediately before process exits)
if [[ -n "$PID" && "$PID" =~ ^[0-9]+$ ]]; then
  echo "[*] Capturing thread snapshot for live PID $PID..."
  adb -s "$SERIAL" shell top -H -b -n 1 -p "$PID" > "$OUTDIR/threads-top.txt" 2>&1 || true
  adb -s "$SERIAL" shell "ls -d /proc/$PID/task/* 2>/dev/null | while read -r t; do [ -f \"\$t/comm\" ] && cat \"\$t/comm\" | sed \"s|^|\$(basename \"\$t\") : |\"; done" > "$OUTDIR/threads-names.txt" 2>&1 || true
else
  echo "[!] Process $PKG is not currently running (capturing post-exit diagnostics)"
  touch "$OUTDIR/threads-top.txt"
fi

# 2b. Screencap snapshot (capture rendering state while process is alive)
echo "[*] Capturing screencap snapshot..."
adb -s "$SERIAL" exec-out screencap -p > "$OUTDIR/device_screen.png" 2>/dev/null || true
if [[ -d "/home/abhaybyte/.gemini/antigravity-cli/brain/81b61061-cc69-40f0-8ee0-d16dcbef2009" ]]; then
  cp "$OUTDIR/device_screen.png" "/home/abhaybyte/.gemini/antigravity-cli/brain/81b61061-cc69-40f0-8ee0-d16dcbef2009/device_screen_fixed.png" 2>/dev/null || true
fi

# 3. Thermal snapshot
echo "[*] Capturing thermal & power diagnostics..."
adb -s "$SERIAL" shell dumpsys thermalservice > "$OUTDIR/thermal.txt" 2>&1 || true
adb -s "$SERIAL" shell dumpsys battery > "$OUTDIR/battery.txt" 2>&1 || true

# 4. Memory snapshots
echo "[*] Capturing memory distribution..."
adb -s "$SERIAL" shell dumpsys meminfo "$PKG" > "$OUTDIR/meminfo.txt" 2>&1 || true
adb -s "$SERIAL" shell cat /proc/meminfo > "$OUTDIR/proc-meminfo.txt" 2>&1 || true

# 5. Application Exit Info
echo "[*] Capturing ApplicationExitInfo..."
adb -s "$SERIAL" shell dumpsys activity exit-info "$PKG" > "$OUTDIR/exit-info.txt" 2>&1 || true

# 6. Capture device and binary provenance
echo "[*] Capturing device provenance and library checksums..."
MODEL="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
SOC="$(adb -s "$SERIAL" shell getprop ro.soc.model | tr -d '\r')"
FINGERPRINT="$(adb -s "$SERIAL" shell getprop ro.build.fingerprint | tr -d '\r')"
ANDROID_VER="$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"

PKG_DUMP="$(adb -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null || true)"
VERSION_NAME="$(echo "$PKG_DUMP" | grep 'versionName=' | head -1 | sed -E 's/.*versionName=([^ ]+).*/\1/' || echo "unknown")"
VERSION_CODE="$(echo "$PKG_DUMP" | grep 'versionCode=' | head -1 | sed -E 's/.*versionCode=([0-9]+).*/\1/' || echo "unknown")"
CODE_PATH="$(echo "$PKG_DUMP" | grep 'codePath=' | head -1 | sed -E 's/.*codePath=([^ ]+).*/\1/' || echo "unknown")"

BASE_APK_SHA=""
RPCSX_SO_SHA=""
S3CORE_BUILD_ID="unknown"

if [[ -n "$CODE_PATH" && "$CODE_PATH" != "unknown" ]]; then
  BASE_APK_SHA="$(adb -s "$SERIAL" shell sha256sum "$CODE_PATH/base.apk" 2>/dev/null | awk '{print $1}' || echo "")"
  RPCSX_SO_SHA="$(adb -s "$SERIAL" shell sha256sum "$CODE_PATH/lib/arm64/librpcsx-android.so" 2>/dev/null | awk '{print $1}' || echo "")"
  S3CORE_BUILD_ID="$(adb -s "$SERIAL" shell "grep -a -o 'rpcsx=[^ ]* samba=[^ ]*' $CODE_PATH/lib/arm64/librpcsx-android.so 2>/dev/null" || echo "unknown")"
fi

# 7. Delegate rotated log retrieval and crash buffers to get-samba-logs.sh
echo "[*] Delegating rotated logs collection to scripts/get-samba-logs.sh..."
"$ROOT_DIR/scripts/get-samba-logs.sh" "$SERIAL" "$OUTDIR" > "$OUTDIR/get-samba-logs.out" 2>&1 || true

# 8. Extract primary metrics if available via analyze-frame-events.py
ANALYSIS_JSON="$OUTDIR/frame-analysis.json"
if [[ -f "$OUTDIR/logcat-sambas3.txt" ]] && grep -q "crosscheck" "$OUTDIR/logcat-sambas3.txt" 2>/dev/null; then
  echo "[*] Running frame analysis on logcat evidence..."
  "$SCRIPT_DIR/analyze-frame-events.py" "$OUTDIR/logcat-sambas3.txt" --json --output-json "$ANALYSIS_JSON" > /dev/null 2>&1 || true
fi

# 9. Build structured run-manifest.json
cat <<EOF > "$OUTDIR/run-manifest.json"
{
  "run_id": "$RUN_ID",
  "scene": "$SCENE_NAME",
  "collected_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "device": {
    "serial": "$SERIAL",
    "model": "$MODEL",
    "soc": "$SOC",
    "android_version": "$ANDROID_VER",
    "fingerprint": "$FINGERPRINT"
  },
  "package": {
    "package_name": "$PKG",
    "version_name": "$VERSION_NAME",
    "version_code": "$VERSION_CODE",
    "code_path": "$CODE_PATH",
    "base_apk_sha256": "$BASE_APK_SHA",
    "librpcsx_so_sha256": "$RPCSX_SO_SHA",
    "s3core_identity": "$S3CORE_BUILD_ID"
  },
  "process": {
    "pid": "${PID:-null}"
  },
  "artifacts": {
    "threads_top": "threads-top.txt",
    "thermal": "thermal.txt",
    "battery": "battery.txt",
    "meminfo": "meminfo.txt",
    "proc_meminfo": "proc-meminfo.txt",
    "exit_info": "exit-info.txt",
    "logcat_process": "logcat-process.log",
    "logcat_sambas3": "logcat-sambas3.txt",
    "logcat_crash": "logcat-crash.log",
    "backend_log": "rpcsx_backend.log",
    "cache_log": "cache-RPCSX.log",
    "frame_analysis_json": "frame-analysis.json"
  }
}
EOF

echo "[OK] Run evidence collection complete: $OUTDIR"
echo "[*] Run manifest: $OUTDIR/run-manifest.json"
