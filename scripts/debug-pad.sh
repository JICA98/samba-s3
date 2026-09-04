#!/usr/bin/env bash
# debug-pad.sh — agent ADB pad bridge for SambaS3 (no coordinate math)
# Requires app with DebugPadReceiver (MainActivity/RPCSXActivity register).
# Canonical: ./scripts/debug-pad.sh SERIAL BUTTON  (e.g. ./scripts/debug-pad.sh 7d6afed8 START)
# Shorthand (single device only): ./scripts/debug-pad.sh START
# Raw: ./scripts/debug-pad.sh SERIAL --raw --ei d2 64 --ei lx 100
set -euo pipefail

ALL_BTNS="CROSS|SQUARE|CIRCLE|TRIANGLE|L1|R1|L2|R2|START|SELECT|PS|UP|DOWN|LEFT|RIGHT|L3|R3"

is_button() {
  case "$1" in
    CROSS|SQUARE|CIRCLE|TRIANGLE|L1|R1|L2|R2|START|SELECT|PS|UP|DOWN|LEFT|RIGHT|L3|R3) return 0 ;;
    *) return 1 ;;
  esac
}

device_count() {
  adb devices | awk 'NR>1 && $2=="device"{c++} END{print c+0}'
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  echo "Usage: $0 [SERIAL] BUTTON"
  echo "  BUTTON: $ALL_BTNS"
  echo "  e.g.: $0 7d6afed8 START | $0 START (single device only)"
  echo "  raw: $0 SERIAL --raw --ei d2 64 ..."
  exit 0
fi

first_device() {
  adb devices | awk 'NR>1 && $2=="device"{print $1; exit}'
}

# --raw passthrough: ./scripts/debug-pad.sh [SERIAL] --raw <am broadcast extras>
if [[ "${1:-}" == "--raw" ]] || [[ "${2:-}" == "--raw" ]]; then
  if [[ "${1:-}" == "--raw" ]]; then
    shift
    n=$(device_count)
    if [[ "$n" -eq 0 ]]; then echo "No device"; exit 1; fi
    if [[ "$n" -gt 1 ]]; then echo "Ambiguous: $n devices connected, pass SERIAL explicitly"; adb devices; exit 1; fi
    SERIAL="$(first_device)"
  else
    SERIAL="$1"; shift
    shift # drop --raw
  fi
  if [[ -z "${SERIAL:-}" ]]; then echo "No device"; exit 1; fi
  adb -s "$SERIAL" shell "am broadcast -a com.zenithblue.sambas3.DEBUG_PAD $*" 2>&1 | tail -1
  exit 0
fi

# Parse [SERIAL] BUTTON
SERIAL=""
BTN=""
if [[ $# -eq 1 ]]; then
  if is_button "$1"; then
    BTN="$1"
    n=$(device_count)
    if [[ "$n" -eq 0 ]]; then echo "No device"; exit 1; fi
    if [[ "$n" -gt 1 ]]; then echo "Ambiguous: $n devices connected, pass SERIAL explicitly"; adb devices; exit 1; fi
    SERIAL="$(first_device)"
  else
    echo "Unknown button '$1' — use $ALL_BTNS or --raw"
    exit 1
  fi
elif [[ $# -eq 2 ]]; then
  # Allow either order, but BUTTON must be a known button.
  if is_button "$1" && ! is_button "$2"; then
    BTN="$1"; SERIAL="$2"
  elif is_button "$2"; then
    SERIAL="$1"; BTN="$2"
  else
    echo "Unknown button '$2' — use $ALL_BTNS or --raw"
    exit 1
  fi
else
  echo "Usage: $0 [SERIAL] BUTTON"
  echo "  BUTTON: $ALL_BTNS"
  echo "  e.g.: $0 7d6afed8 START | $0 START (single device only)"
  exit 1
fi

if [[ -z "$SERIAL" ]]; then echo "No device"; exit 1; fi
if ! is_button "$BTN"; then echo "Unknown $BTN — use $ALL_BTNS or --raw"; exit 1; fi

OUT="$(adb -s "$SERIAL" shell "am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_$BTN" 2>&1 | tail -1)"
echo "$OUT"
# Verify actual delivery via DebugPad log instead of trusting "Broadcast completed".
sleep 1
if adb -s "$SERIAL" shell "logcat -d -b main -t 200 2>/dev/null | grep -q 'DebugPad.*$BTN'" 2>/dev/null; then
  echo "[$SERIAL] sent $BTN (verified: DebugPad log shows $BTN)"
else
  # Fall back to any recent DebugPad activity so silent failure is visible.
  if adb -s "$SERIAL" shell "logcat -d -b main -t 200 2>/dev/null | grep -q DebugPad" 2>/dev/null; then
    echo "[$SERIAL] sent $BTN (warning: DebugPad active but no $BTN line in last 200 — receiver may be unregistered/foregrounded elsewhere)"
  else
    echo "[$SERIAL] sent $BTN (WARNING: no DebugPad log — receiver not registered? Ensure MainActivity/RPCSXActivity is foregrounded with debug APK)"
  fi
fi
