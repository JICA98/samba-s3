#!/usr/bin/env bash
# reset-ppu-cache.sh --title-id <ID> [--manifest-only]
set -euo pipefail
TITLE_ID=""; MANIFEST_ONLY=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --title-id) TITLE_ID="$2"; shift 2;;
    --manifest-only) MANIFEST_ONLY=true; shift;;
    *) shift;;
  esac
done
if [[ -z "$TITLE_ID" ]]; then echo "ERROR: --title-id required and must be valid (e.g., BLUS31584)" >&2; exit 2; fi
if ! [[ "$TITLE_ID" =~ ^[A-Za-z]{4}[0-9]{5}$ ]]; then echo "ERROR: invalid titleId $TITLE_ID" >&2; exit 2; fi
if [[ "$TITLE_ID" == "" || "$TITLE_ID" == "*" ]]; then echo "ERROR:Refuse empty/wildcard" >&2; exit 2; fi
ROOT="${1:-/storage/emulated/0/Android/data/com.zenithblue.sambas3/files}"
echo "Resetting PPU cache for $TITLE_ID manifest_only=$MANIFEST_ONLY"
if adb shell ls "$ROOT/cache/cache/$TITLE_ID" 2>/dev/null; then
  if [[ "$MANIFEST_ONLY" == false ]]; then
    adb shell rm -rf "$ROOT/cache/cache/$TITLE_ID" 2>&1 | tail
  fi
fi
adb shell rm -f "$ROOT/cache/cache/ppu_manifest/$TITLE_ID.json" 2>&1 | tail || true
echo "Done"
