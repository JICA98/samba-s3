#!/usr/bin/env bash
# Warm initialization through MainActivity, then the in-process debug boot bridge.
# RPCSXActivity is not exported. Focus alone does not prove a new boot was accepted.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/debug-bridge.sh"

usage() {
  echo "Usage: $0 [SERIAL] GAME_PATH (fresh boot; absolute path or registered direct_iso/<TITLE_ID>)"
  echo "       $0 --savestate-slot0 SERIAL GAME_PATH (explicit slot 0 restore; current APK required)"
}
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then usage; exit 0; fi
SAVESTATE_SLOT0=0
BOOT_ACK='boot started'
MAX_ATTEMPTS=2
if [[ "${1:-}" == --savestate-slot0 ]]; then
  shift
  if [[ $# != 2 || -z "$1" || -z "$2" ]]; then usage; exit 1; fi
  # Restore runs must be tied to the tester's explicitly leased device.
  SERIAL="$1"
  GAME="$2"
  SAVESTATE_SLOT0=1
  BOOT_ACK='status=accepted mode=UserSelectedSavestate slot=0'
  # An unacknowledged state restore is ambiguous; never submit it twice.
  MAX_ATTEMPTS=1
elif [[ $# == 1 ]]; then SERIAL=""; GAME="$1"
elif [[ $# == 2 ]]; then SERIAL="$1"; GAME="$2"
else usage; exit 1; fi
bridge_device
if [[ "$GAME" == direct_iso/* ]]; then
  GAMES_JSON='/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/games.json'
  bridge_shell test -f "$GAMES_JSON" || { echo "Game registry does not exist: $GAMES_JSON" >&2; exit 1; }
  bridge_shell cat "$GAMES_JSON" | grep -Fq "\"path\":\"$GAME\"" || {
    echo "Direct ISO is not registered: $GAME" >&2
    exit 1
  }
elif [[ "$GAME" == /* ]]; then
  bridge_shell test -e "$GAME" || { echo "Game path does not exist: $GAME" >&2; exit 1; }
else
  echo 'GAME must be an absolute on-device path or registered direct_iso/<TITLE_ID>' >&2
  exit 1
fi
if bridge_shell dumpsys window | grep -q 'mCurrentFocus.*RPCSXActivity'; then
  echo 'RPCSXActivity is already focused. Collect evidence and exit the current game before a new boot.' >&2
  exit 1
fi
bridge_capture S3BOOT
trap bridge_cleanup EXIT
bridge_shell am start -n "$PKG/.MainActivity"

wait_for_slot0_ack() {
  local deadline=$((SECONDS + 5)) lines
  while (( SECONDS < deadline )); do
    lines="$(grep -F "request_id=$REQUEST_ID" "$BRIDGE_LOG" || true)"
    if grep -Fq "$BOOT_ACK" <<< "$lines"; then
      echo "$lines"
      return 0
    fi
    if grep -Fq 'slot-restore rejected' <<< "$lines"; then
      echo "$lines" >&2
      return 2
    fi
    sleep 0.2
  done
  echo "Missing '$BOOT_ACK' acknowledgement for request_id=$REQUEST_ID" >&2
  return 1
}

# Fresh boots get at most two registration attempts. Slot restores get one because
# a lost acknowledgement cannot prove the Activity did not already accept them.
for (( attempt=1; attempt<=MAX_ATTEMPTS; attempt++ )); do
  sleep 2
  BOOT_EXTRAS=(--es path "$GAME" --es originalGamePath "$GAME")
  if (( SAVESTATE_SLOT0 )); then
    BOOT_EXTRAS+=(--es bootMode UserSelectedSavestate --ei savestateSlot 0)
  fi
  bridge_send "$PKG.DEBUG_BOOT_GAME" "${BOOT_EXTRAS[@]}"
  ACKED=0
  if (( SAVESTATE_SLOT0 )); then
    if wait_for_slot0_ack; then ACKED=1; else
      wait_status=$?
      if (( wait_status == 2 )); then
        echo 'Saved-state request was explicitly rejected; do not resend it.' >&2
        exit 1
      fi
    fi
  elif bridge_wait "$BOOT_ACK" 5; then
    ACKED=1
  fi
  if (( ACKED )); then
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
echo "No boot acknowledgement. Install a current APK with the adb-shell bridge and collect logs with scripts/get-samba-logs.sh $SERIAL" >&2
exit 1
