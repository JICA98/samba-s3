#!/usr/bin/env bash
# Debug-only Turnip diagnostic selector. Applies on the next warm boot.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/debug-bridge.sh"

usage() {
  echo "Usage: $0 SERIAL TU_DEBUG_OPTIONS"
  echo "Example: $0 SERIAL flushall,syncdraw"
  echo "Use '-' to clear the persisted diagnostic options. Core must be stopped."
}
[[ $# == 2 ]] || { usage; exit 1; }
SERIAL="$1"
value="$2"
bridge_device
bridge_capture S3BOOT
trap bridge_cleanup EXIT
bridge_send "$PKG.DEBUG_DRIVER_TU_DEBUG" --es value "$value"
bridge_wait 'tu_debug value=' 10
echo "[OK] TU_DEBUG diagnostic set to '$value' on $SERIAL"
