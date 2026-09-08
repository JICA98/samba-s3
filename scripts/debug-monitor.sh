#!/usr/bin/env bash
# Debug helper to configure the performance monitoring overlay in real-time.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/debug-bridge.sh"

usage() {
  echo "Usage: $0 [SERIAL] [EXTRAS...]"
  echo "Examples:"
  echo "  $0 SERIAL --ez enabled true --es preset Performance"
  echo "  $0 SERIAL --es position TopRight --es layout Grid"
  echo "  $0 SERIAL --es metrics all"
  echo "  $0 SERIAL --es graphs Fps,FrameTime"
}

if [[ $# -gt 0 && ! "$1" =~ ^-- ]]; then
  SERIAL="$1"; shift
fi

bridge_device
bridge_capture S3MONITOR_DEBUG
trap bridge_cleanup EXIT
bridge_send "$PKG.DEBUG_MONITOR_SET" "$@"
bridge_wait 'applied enabled=' 10
echo "[OK] Monitor settings updated on $SERIAL"
