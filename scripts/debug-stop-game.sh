#!/usr/bin/env bash
# Use the same terminal stop coordinator as the app's Home button.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/debug-bridge.sh"
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  echo "Usage: $0 [SERIAL] (current debug APK; collect failure evidence first)"
  exit 0
fi
if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [SERIAL] (current debug APK; collect failure evidence first)" >&2
  exit 2
fi
SERIAL="${1:-}"
bridge_device
bridge_capture S3BOOT
trap bridge_cleanup EXIT
bridge_send "$PKG.DEBUG_STOP_GAME"
bridge_wait 'stop completed ok=true' 65
