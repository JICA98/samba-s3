#!/usr/bin/env bash
# Deprecated wrapper — previously packaged 3 drivers from drivers/input/.
# Now the single pinned Turnip 26.3 is managed by sync-bundled-turnip.sh.
# This script delegates to maintain CI compatibility.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
echo "NOTE: package-bundled-turnip-drivers.sh is deprecated. Delegating to sync-bundled-turnip.sh (single Turnip 26.3 latest)" >&2
exec "$ROOT/scripts/sync-bundled-turnip.sh" "$@"
