#!/usr/bin/env bash
# Warm initialization through MainActivity, then the in-process debug boot bridge.
# RPCSXActivity is not exported. Focus alone does not prove a new boot was accepted.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/debug-bridge.sh"

usage() { echo "Usage: $0 [SERIAL] GAME_PATH (absolute on-device path; current debug APK required)"; }
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then usage; exit 0; fi
if [[ $# == 1 ]]; then SERIAL=""; GAME="$1"
elif [[ $# == 2 ]]; then SERIAL="$1"; GAME="$2"
else usage; exit 1; fi
[[ "$GAME" == /* ]] || { echo 'GAME must be an absolute on-device path' >&2; exit 1; }
bridge_device
bridge_shell test -e "$GAME" || { echo "Game path does not exist: $GAME" >&2; exit 1; }
if bridge_shell dumpsys window | grep -q 'mCurrentFocus.*RPCSXActivity'; then
  echo 'RPCSXActivity is already focused. Collect evidence and exit the current game before a new boot.' >&2
  exit 1
fi
bridge_capture S3BOOT
trap bridge_cleanup EXIT
bridge_shell am start -n "$PKG/.MainActivity"
# Two registration attempts maximum. A rejected or acknowledged boot is never resent.
for attempt in 1 2; do
  sleep 2
  bridge_send "$PKG.DEBUG_BOOT_GAME" --es path "$GAME" --es originalGamePath "$GAME"
  if bridge_wait 'boot started' 5; then
    deadline=$((SECONDS + 20))
    while (( SECONDS < deadline )); do
      if bridge_shell dumpsys window | grep 'mCurrentFocus.*RPCSXActivity'; then
        echo "[OK] Boot accepted and RPCSXActivity focused: $GAME"
        exit 0
      fi
      sleep 1
    done
    echo 'Boot accepted, but activity focus timed out. Do not resend the boot.' >&2
    exit 1
  fi
  if grep -F "request_id=$REQUEST_ID" "$BRIDGE_LOG" | grep -q 'boot blocked'; then
    echo 'Core rejected boot: exit the current session before retrying.' >&2
    exit 1
  fi
done
echo "No boot acknowledgement. Install a current debug APK and collect logs with scripts/get-samba-logs.sh $SERIAL" >&2
exit 1
