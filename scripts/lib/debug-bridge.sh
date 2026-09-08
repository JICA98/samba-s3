# Shared by debug launch/pad scripts. Caller uses set -euo pipefail.
PKG=com.zenithblue.sambas3
bridge_device() {
  if [[ -z "${SERIAL:-}" ]]; then
    mapfile -t bridge_devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    if [[ ${#bridge_devices[@]} != 1 ]]; then
      echo "Expected one device, found ${#bridge_devices[@]}; pass SERIAL explicitly." >&2
      return 1
    fi
    SERIAL="${bridge_devices[0]}"
  fi
  if [[ "$(timeout 10 adb -s "$SERIAL" get-state 2>/dev/null || true)" == device ]]; then
    return 0
  fi
  # ADB mDNS serials can lose their transport after a tablet sleeps. Reconnect
  # the named service once so every debug helper has the same recovery path.
  if [[ "$SERIAL" == *._adb-tls-connect._tcp ]]; then
    timeout 15 adb connect "$SERIAL" >/dev/null 2>&1 || true
    [[ "$(timeout 10 adb -s "$SERIAL" get-state 2>/dev/null || true)" == device ]]
    return
  fi
  echo "Device '$SERIAL' is not online; reconnect it or pass an online SERIAL." >&2
  return 1
}

# ADB shell reparses arguments remotely. Quote each argument for that shell.
bridge_shell() {
  local arg command=""
  for arg in "$@"; do
    command+=" '${arg//\'/\'\\\'\'}'"
  done
  timeout 10 adb -s "$SERIAL" shell "$command"
}

bridge_capture() {
  BRIDGE_TMP="$(mktemp -d /tmp/samba-bridge-XXXXXX)"
  BRIDGE_LOG="$BRIDGE_TMP/logcat.txt"
  adb -s "$SERIAL" logcat -T 20 -v brief "$1:V" '*:S' > "$BRIDGE_LOG" 2>&1 &
  BRIDGE_PID=$!
  sleep 0.1
}

bridge_cleanup() {
  if [[ -n "${BRIDGE_PID:-}" ]]; then
    kill "$BRIDGE_PID" 2>/dev/null || true
    wait "$BRIDGE_PID" 2>/dev/null || true
  fi
  [[ -z "${BRIDGE_LOG:-}" ]] || echo "Bridge evidence: $BRIDGE_LOG"
}

bridge_send() {
  REQUEST_ID="s3-$(date +%s%N)-$$-$RANDOM"
  bridge_shell am broadcast -a "$1" -p "$PKG" --es request_id "$REQUEST_ID" "${@:2}"
}

bridge_wait() {
  local expected="$1" deadline=$((SECONDS + $2)) lines
  while (( SECONDS < deadline )); do
    lines="$(grep -F "request_id=$REQUEST_ID" "$BRIDGE_LOG" || true)"
    if grep -Fq "$expected" <<< "$lines"; then
      echo "$lines"
      return 0
    fi
    sleep 0.2
  done
  echo "Missing '$expected' acknowledgement for request_id=$REQUEST_ID" >&2
  return 1
}
