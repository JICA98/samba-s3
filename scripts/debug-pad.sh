#!/usr/bin/env bash
# Deterministic debug pad bridge. Requires request-id acknowledgements in the APK.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/debug-bridge.sh"
BUTTONS='CROSS SQUARE CIRCLE TRIANGLE L1 R1 L2 R2 START SELECT PS UP DOWN LEFT RIGHT L3 R3'
usage() {
  echo "Usage: $0 [SERIAL] BUTTON"
  echo "Buttons: $BUTTONS"
  echo "Raw: $0 [SERIAL] --raw --ei d1 0 --ei d2 0 --ei lx 127 --ei ly 127 --ei rx 127 --ei ry 127"
  echo 'Raw holds must be released/centered; delivery failures return nonzero.'
}
is_button() { [[ " $BUTTONS " == *" $1 "* ]]; }
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then usage; exit 0; fi
SERIAL=""
if [[ $# -gt 1 ]] && ! is_button "$1" && [[ "$1" != --raw ]]; then SERIAL="$1"; shift; fi
if [[ $# == 2 ]] && is_button "$1"; then SERIAL="$2"; set -- "$1"; fi
[[ $# -gt 0 ]] || { usage; exit 1; }
mode="$1"; shift
raw_args=()
if [[ "$mode" == --raw ]]; then
  while (( $# )); do
    [[ $# -ge 3 && "$1" == --ei ]] || { usage; exit 1; }
    case "$2" in d1|d2|lx|ly|rx|ry) ;; *) echo "Unknown raw field: $2" >&2; exit 1;; esac
    [[ "$3" =~ ^[0-9]{1,3}$ ]] || { echo "Invalid integer: $3" >&2; exit 1; }
    value=$((10#$3)); limit=255
    [[ "$2" != d1 ]] || limit=511
    (( value <= limit )) || { echo "Out of range: $2=$value" >&2; exit 1; }
    raw_args+=(--ei "$2" "$value"); shift 3
  done
  action="$PKG.DEBUG_PAD"; expected='PAD d1='
else
  is_button "$mode" && [[ $# == 0 ]] || { usage; exit 1; }
  action="$PKG.DEBUG_PAD_$mode"; expected="BUTTON $mode release"
fi
bridge_device
bridge_capture DebugPad
trap bridge_cleanup EXIT
bridge_send "$action" "${raw_args[@]}"
# A button pulse succeeds only after release is acknowledged, not just press.
bridge_wait "$expected" 10
echo "[OK] $mode delivered to $SERIAL"
