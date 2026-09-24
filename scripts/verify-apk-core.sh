#!/usr/bin/env bash
# Verify APK/AAB contains the packaging-stage RPCSX core (per-ABI provenance).
# Required ABIs: arm64-v8a and x86_64. No .cxx fallback, no identity-only SHA waiver.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT="${1:-}"

ARGS=(python3 "$ROOT/scripts/lib/core_provenance.py" verify-package --root "$ROOT")
if [[ -n "$ARTIFACT" ]]; then
  ARGS+=(--artifact "$ARTIFACT")
fi
exec "${ARGS[@]}"
