#!/usr/bin/env bash
# debug-launch-game.sh — deterministic SambaS3 game launch for agents.
# Canonical path: MainActivity (exported, inits RPCSX + registers DebugPadReceiver)
#   -> warm-start RPCSXActivity with exact path (shell explicit intent).
# NOTE: There is no DEBUG_BOOT_GAME handler in DebugPadReceiver.kt as of this
# revision. The script first probes for it (future-proof); the supported path
# today is the warm start below, which achieves the same ordering guarantee:
# MainActivity init before RPCSXActivity boot. Do NOT shell-start
# RPCSXActivity cold (after force-stop) without MainActivity first.
#
# Usage: ./scripts/debug-launch-game.sh SERIAL GAME_PATH
#   SERIAL: adb serial (required when >1 device; optional single-device shorthand)
#   GAME_PATH: exact on-device path, e.g.
#     /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584
#
# Behavior: max 2 launch attempts. RPCSXActivity gets at most 20s to appear
# per attempt. On failure: stop and collect logs (do not loop launch methods).
set -euo pipefail

PKG="com.zenithblue.sambas3"
MAX_ATTEMPTS=2
FOCUS_TIMEOUT_S=20

device_count() { adb devices | awk 'NR>1 && $2=="device"{c++} END{print c+0}'; }
first_device() { adb devices | awk 'NR>1 && $2=="device"{print $1; exit}'; }

usage() {
  echo "Usage: $0 SERIAL GAME_PATH"
  echo "  e.g.: $0 7d6afed8 /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584"
}

if [[ $# -eq 1 && ( "${1:-}" == "-h" || "${1:-}" == "--help" ) ]]; then usage; exit 0; fi

SERIAL="${1:-}"
GAME="${2:-}"

# Single-device shorthand: ./scripts/debug-launch-game.sh GAME_PATH
if [[ -z "$GAME" && -n "$SERIAL" && "$SERIAL" == /* ]]; then
  GAME="$SERIAL"; SERIAL=""
fi
if [[ -z "$SERIAL" ]]; then
  n=$(device_count)
  if [[ "$n" -eq 0 ]]; then echo "No device"; exit 1; fi
  if [[ "$n" -gt 1 ]]; then echo "Ambiguous: $n devices connected, pass SERIAL explicitly"; adb devices; exit 1; fi
  SERIAL="$(first_device)"
fi
if [[ -z "${GAME:-}" ]]; then usage; exit 1; fi
if [[ "$GAME" != /* ]]; then echo "GAME must be an exact on-device absolute path, got: $GAME"; exit 1; fi

echo "[*] launch serial=$SERIAL game=$GAME (max $MAX_ATTEMPTS attempts, ${FOCUS_TIMEOUT_S}s focus cap each)"

wait_for_focus() {
  local deadline=$((SECONDS + FOCUS_TIMEOUT_S))
  while [[ $SECONDS -lt $deadline ]]; do
    if adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus.*RPCSXActivity"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_for_main() {
  # Brief wait for MainActivity init: DebugPad registered log (MainActivity.onResume).
  local tries=8
  for ((i=1; i<=tries; i++)); do
    if adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus.*MainActivity"; then
      return 0
    fi
    if adb -s "$SERIAL" shell "logcat -d -b main -t 300 2>/dev/null | grep -q 'DebugPad.*registered'" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  # Non-fatal: MainActivity may already be resumed without fresh log line.
  return 0
}

attempt=0
while [[ $attempt -lt $MAX_ATTEMPTS ]]; do
  attempt=$((attempt + 1))
  echo "[*] attempt $attempt/$MAX_ATTEMPTS: start MainActivity (exported, RPCSX init)"
  adb -s "$SERIAL" shell "am start -n $PKG/.MainActivity" 2>&1 | tail -1
  wait_for_main || true
  sleep 2

  # Future-proof probe: if a DEBUG_BOOT_GAME handler ever lands in
  # DebugPadReceiver, prefer it (in-process start bypasses exported check).
  echo "[*] probe DEBUG_BOOT_GAME (no-op on current builds without the handler)"
  adb -s "$SERIAL" shell "am broadcast -a $PKG.DEBUG_BOOT_GAME --es path \"$GAME\" --es originalGamePath \"$GAME\"" 2>&1 | tail -1 || true
  sleep 2
  if adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus.*RPCSXActivity"; then
    echo "[OK] RPCSXActivity appeared via DEBUG_BOOT_GAME probe"
    exit 0
  fi

  echo "[*] warm-start RPCSXActivity with exact path"
  START_OUT="$(adb -s "$SERIAL" shell "am start -n $PKG/.RPCSXActivity --es path \"$GAME\" --es originalGamePath \"$GAME\" --es bootMode FreshGame" 2>&1 || true)"
  echo "$START_OUT" | tail -3
  if echo "$START_OUT" | grep -qi "SecurityException\|Permission Denial\|not exported"; then
    echo "[FAIL] RPCSXActivity start blocked (exported=false enforced on this build)."
    echo "       Do NOT retry other launch methods — collect evidence instead:"
    echo "       ./scripts/get-samba-logs.sh \"$SERIAL\" \"/tmp/run-\$(date +%Y%m%d-%H%M%S)\""
    exit 1
  fi

  echo "[*] waiting up to ${FOCUS_TIMEOUT_S}s for RPCSXActivity focus..."
  if wait_for_focus; then
    echo "[OK] RPCSXActivity focused (attempt $attempt)"
    adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep "mCurrentFocus" | head -1 || true
    exit 0
  fi
  echo "[WARN] attempt $attempt: RPCSXActivity did not appear within ${FOCUS_TIMEOUT_S}s"
  adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep "mCurrentFocus" | head -1 || true
done

echo "[FAIL] launch failed after $MAX_ATTEMPTS attempts. Stop trying launch methods and collect evidence:"
echo "  ./scripts/get-samba-logs.sh \"$SERIAL\" \"/tmp/run-\$(date +%Y%m%d-%H%M%S)\""
exit 1
