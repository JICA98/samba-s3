#!/usr/bin/env bash
# capture-sambas3.sh --label --title-id --duration --output
set -euo pipefail
LABEL=""; TITLE_ID=""; DURATION=10; OUTPUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --label) LABEL="$2"; shift 2;;
    --title-id) TITLE_ID="$2"; shift 2;;
    --duration) DURATION="$2"; shift 2;;
    --output) OUTPUT="$2"; shift 2;;
    *) shift;;
  esac
done
OUTPUT="${OUTPUT:-/tmp/samba-perf-$(date +%s)}"
mkdir -p "$OUTPUT"
echo "label=$LABEL title_id=$TITLE_ID duration=$DURATION" > "$OUTPUT/environment.txt"
adb shell getprop ro.product.model > "$OUTPUT/device.txt" 2>&1 || true
adb shell dumpsys battery > "$OUTPUT/battery.txt" 2>&1 || true
adb shell dumpsys thermalservice > "$OUTPUT/thermal.txt" 2>&1 || true
adb shell dumpsys meminfo com.zenithblue.sambas3 > "$OUTPUT/meminfo.txt" 2>&1 || true
adb logcat -c 2>/dev/null || true
adb logcat -v epoch -t 100 > "$OUTPUT/logcat.txt" 2>&1 || true
echo "Captured to $OUTPUT"
