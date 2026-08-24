#!/usr/bin/env bash
# debug-pad.sh — agent ADB pad bridge for SambaS3 (no coordinate math)
# Requires app with DebugPadReceiver (MainActivity/RPCSXActivity register).
# Usage: ./scripts/debug-pad.sh [SERIAL] CROSS|UP|DOWN|...
#        ./scripts/debug-pad.sh Y5WWBMJVOZSK4HU8 CROSS
#        ./scripts/debug-pad.sh --raw --ei d2 64 --ei lx 100
set -euo pipefail
SERIAL="${1:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
if [[ "$SERIAL" == "--raw" ]]; then
  SERIAL="${2:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
  shift 2
  adb -s "$SERIAL" shell "am broadcast -a com.zenithblue.sambas3.DEBUG_PAD $*" 2>&1 | tail -1
  exit 0
fi
BTN="${2:-CROSS}"
# default SERIAL when first arg is btn
if [[ "$SERIAL" == "CROSS" || "$SERIAL" == "UP" || "$SERIAL" == "CIRCLE" ]]; then
  BTN="$SERIAL"
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi
if [[ -z "$SERIAL" ]]; then echo "No device"; exit 1; fi
case "$BTN" in
  CROSS|SQUARE|CIRCLE|TRIANGLE|L1|R1|L2|R2|START|SELECT|PS|UP|DOWN|LEFT|RIGHT|L3|R3)
    adb -s "$SERIAL" shell "am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_$BTN" 2>&1 | tail -1
    ;;
  *)
    echo "Unknown $BTN — use CROSS|UP|etc. or --raw"
    exit 1
    ;;
esac
echo "[$SERIAL] sent $BTN (check adb logcat -s DebugPad)"
