#!/usr/bin/env bash
# gamepad.sh — SambaS3 gameplay input helper (no coordinate math).
# Built on the rotation-independent DebugPad broadcast bridge (see debug-pad.sh).
# Also discovers physical gamepads for manual-play verification.
#
# Usage:
#   ./scripts/gamepad.sh [SERIAL] list                    # physical gamepads + bridge status
#   ./scripts/gamepad.sh [SERIAL] press CROSS [times] [gap_s]
#   ./scripts/gamepad.sh [SERIAL] hold CROSS <seconds>     # raw hold then release
#   ./scripts/gamepad.sh [SERIAL] seq "CROSS,START,UP" [gap_s]
#   ./scripts/gamepad.sh [SERIAL] stick LEFT [seconds]     # left-stick push then center
#   ./scripts/gamepad.sh [SERIAL] eula [rounds]            # CROSS loop for EULA accept
#
# SERIAL may be omitted with a single device connected (refuses 0 or >1 devices).
# Buttons: CROSS SQUARE CIRCLE TRIANGLE L1 R1 L2 R2 START SELECT PS UP DOWN LEFT RIGHT L3 R3
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

device_count() { adb devices | awk 'NR>1 && $2=="device"{c++} END{print c+0}'; }
first_device() { adb devices | awk 'NR>1 && $2=="device"{print $1; exit}'; }

is_button() {
  case "$1" in
    CROSS|SQUARE|CIRCLE|TRIANGLE|L1|R1|L2|R2|START|SELECT|PS|UP|DOWN|LEFT|RIGHT|L3|R3) return 0 ;;
    *) return 1 ;;
  esac
}

usage() { sed -n '2,14p' "$0"; }

# --- SERIAL resolution: optional first arg unless it is a subcommand ---
SERIAL=""
if [[ $# -eq 0 ]]; then usage; exit 1; fi
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || "${1:-}" == "help" ]]; then usage; exit 0; fi
case "${1:-}" in
  list|press|hold|seq|stick|eula) ;;
  *)
    SERIAL="$1"; shift
    ;;
esac
if [[ -z "$SERIAL" ]]; then
  n=$(device_count)
  if [[ "$n" -eq 0 ]]; then echo "No device"; exit 1; fi
  if [[ "$n" -gt 1 ]]; then echo "Ambiguous: $n devices connected, pass SERIAL explicitly"; adb devices; exit 1; fi
  SERIAL="$(first_device)"
fi
CMD="${1:-}"; shift || true

pad() { "$SCRIPT_DIR/debug-pad.sh" "$SERIAL" "$@"; }
pad_raw() { "$SCRIPT_DIR/debug-pad.sh" "$SERIAL" --raw "$@"; }
release_pad() { pad_raw --ei d1 0 --ei d2 0 --ei lx 127 --ei ly 127 --ei rx 127 --ei ry 127; }
arm_release() {
  trap 'release_pad || true' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
}
finish_release() { release_pad; trap - EXIT INT TERM; }

cmd_list() {
  echo "[*] Physical input devices on $SERIAL:"
  adb -s "$SERIAL" shell dumpsys input 2>/dev/null \
    | grep -a -B1 -A6 -i "gamepad\|joystick\|xbox\|dualsense\|dualshock\|8bitdo\|controller" \
    | head -n 40 || echo "(no gamepad-class device found)"
  echo ""
  echo "[*] Bridge status (needs debug APK + MainActivity/RPCSXActivity foregrounded):"
  if adb -s "$SERIAL" shell "logcat -d -b main -t 1000 2>/dev/null | grep -qi DebugPad" 2>/dev/null; then
    echo "OK: DebugPad seen in recent logcat"
  else
    echo "WARNING: no DebugPad in last 300 lines — foreground the app / install debug APK"
  fi
  adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -a "mCurrentFocus" | head -1 || true
}

cmd_press() {
  BTN="${1:?press needs BUTTON}"; TIMES="${2:-1}"; GAP="${3:-1}"
  is_button "$BTN" || { echo "Unknown button '$BTN'"; exit 1; }
  for ((i=1; i<=TIMES; i++)); do
    echo "[*] $BTN ($i/$TIMES)"
    pad "$BTN" | tail -1
    if [[ "$i" -lt "$TIMES" ]]; then sleep "$GAP"; fi
  done
}

cmd_hold() { # hold BTN seconds — raw press, sleep, release (centered sticks)
  BTN="${1:?hold needs BUTTON}"; SECS="${2:?hold needs seconds}"
  is_button "$BTN" || { echo "Unknown button '$BTN'"; exit 1; }
  case "$BTN" in
    CROSS) D="--ei d2 64";; CIRCLE) D="--ei d2 32";; SQUARE) D="--ei d2 128";; TRIANGLE) D="--ei d2 16";;
    L1) D="--ei d2 4";; R1) D="--ei d2 8";; L2) D="--ei d2 1";; R2) D="--ei d2 2";;
    START) D="--ei d1 8";; SELECT) D="--ei d1 1";; PS) D="--ei d1 256";;
    UP) D="--ei d1 16";; DOWN) D="--ei d1 64";; LEFT) D="--ei d1 128";; RIGHT) D="--ei d1 32";;
    L3) D="--ei d1 2";; R3) D="--ei d1 4";;
  esac
  arm_release
  # shellcheck disable=SC2086
  pad_raw $D --ei lx 127 --ei ly 127 --ei rx 127 --ei ry 127 | tail -1
  echo "[*] holding $BTN for ${SECS}s..."
  sleep "$SECS"
  finish_release
  echo "[*] released $BTN"
}

cmd_seq() { # seq "BTN,BTN" [gap]
  SEQ="${1:?seq needs \"BTN,BTN,...\"}"; GAP="${2:-2}"
  IFS=',' read -ra BTNS <<< "$SEQ"
  for b in "${BTNS[@]}"; do
    b="$(echo "$b" | tr -d ' ')"
    is_button "$b" || { echo "Unknown button '$b' in sequence"; exit 1; }
    echo "[*] seq: $b"
    pad "$b" | tail -1
    sleep "$GAP"
  done
}

cmd_stick() { # stick LEFT|RIGHT|UP|DOWN [seconds]
  DIR="${1:?stick needs LEFT|RIGHT|UP|DOWN}"; SECS="${2:-2}"
  case "$DIR" in
    LEFT) LX=0; LY=127;; RIGHT) LX=255; LY=127;; UP) LX=127; LY=0;; DOWN) LX=127; LY=255;;
    *) echo "stick direction must be LEFT|RIGHT|UP|DOWN"; exit 1;;
  esac
  arm_release
  pad_raw --ei d1 0 --ei d2 0 --ei lx "$LX" --ei ly "$LY" --ei rx 127 --ei ry 127 | tail -1
  echo "[*] left stick $DIR for ${SECS}s..."
  sleep "$SECS"
  finish_release
  echo "[*] stick centered"
}

cmd_eula() { # eula [rounds=3] [gap=4] — GTA-style EULA accept loop
  ROUNDS="${1:-3}"; GAP="${2:-4}"
  for ((i=1; i<=ROUNDS; i++)); do
    echo "[*] EULA accept CROSS ($i/$ROUNDS)"
    pad CROSS | tail -1
    if [[ "$i" -lt "$ROUNDS" ]]; then sleep "$GAP"; fi
  done
}

case "$CMD" in
  list) cmd_list;;
  press) cmd_press "$@";;
  hold) cmd_hold "$@";;
  seq) cmd_seq "$@";;
  stick) cmd_stick "$@";;
  eula) cmd_eula "$@";;
  -h|--help|help|"") usage;;
  *) echo "Unknown command '$CMD'"; usage; exit 1;;
esac
