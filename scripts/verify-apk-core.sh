#!/usr/bin/env bash
# Verify APK contains the locally-built RPCSX core (deterministic build).
# Fails if ignored jniLibs stale or Gradle packaged wrong ABI.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-}"

if [[ -z "$APK" ]]; then
  # Find latest APK if not provided
  APK="$(ls -t "$ROOT/app/build/outputs/apk/"*/*/*.apk 2>/dev/null | head -1 || true)"
  APK="$(ls -t "$ROOT/app/build/outputs/apk/standard/debug/"*.apk "$ROOT/app/build/outputs/apk/standard/release/"*.apk 2>/dev/null | head -1 || echo "$APK")"
fi

if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "ERROR: APK not found. Usage: $0 <path-to-apk>" >&2
  exit 1
fi

# Locate locally built cores
ARM_LOCAL="$ROOT/app/src/main/jniLibs/arm64-v8a/librpcsx-android.so"
X64_LOCAL="$ROOT/app/src/main/jniLibs/x86_64/librpcsx-android.so"
if [[ ! -f "$ARM_LOCAL" ]]; then
  ARM_LOCAL="$(find "$ROOT/app/.cxx" -name "librpcsx-android.so" -path "*arm64*" | head -1 || true)"
fi
if [[ ! -f "$X64_LOCAL" ]]; then
  X64_LOCAL="$(find "$ROOT/app/.cxx" -name "librpcsx-android.so" -path "*x86_64*" | head -1 || true)"
fi

if [[ ! -f "$ARM_LOCAL" && ! -f "$X64_LOCAL" ]]; then
  echo "ERROR: No local librpcsx-android.so found. Run ./build_rpcsx.sh release first." >&2
  exit 1
fi

echo "APK: $APK"
echo "APK SHA-256: $(sha256sum "$APK" | awk '{print $1}')"
if [[ -f "$ROOT/patches/rpcsx-submodule-changes.patch" ]]; then
  echo "Patch SHA-256: $(sha256sum "$ROOT/patches/rpcsx-submodule-changes.patch" | awk '{print $1}')"
fi
echo "RPCSX pin: $(git -C "$ROOT/app/src/main/cpp/rpcsx" rev-parse HEAD 2>/dev/null || echo unknown)"
echo "Samba HEAD: $(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

# Extract APK libs
unzip -q -o "$APK" -d "$TMPDIR/apk" 2>/dev/null || {
  echo "ERROR: APK is not a valid zip" >&2
  exit 1
}

echo ""
echo "APK contents:"
unzip -l "$APK" | grep -E "lib/.*librpcsx-android.so|lib/.*libsambas3-android.so" || echo "(no librpcsx found in APK)"

FAIL=0

check_abi() {
  local abi="$1"
  local local_so="$2"
  local apk_so="$TMPDIR/apk/lib/$abi/librpcsx-android.so"
  if [[ ! -f "$local_so" ]]; then
    echo "SKIP $abi: no local $local_so"
    return 0
  fi
  local local_sha
  local_sha="$(sha256sum "$local_so" | awk '{print $1}')"
  echo ""
  echo "ABI $abi:"
  echo "  local: $local_so"
  echo "  local SHA-256: $local_sha"
  if [[ ! -f "$apk_so" ]]; then
    echo "  APK lib/$abi/librpcsx-android.so: MISSING"
    # Only fail if this ABI is expected in APK per abiFilters
    if unzip -l "$APK" | grep -q "lib/$abi/"; then
      echo "  FAIL: ABI $abi present in APK manifest but core missing"
      FAIL=1
    else
      echo "  SKIP: APK does not contain $abi (may be split APK)"
    fi
    return 0
  fi
  local apk_sha
  apk_sha="$(sha256sum "$apk_so" | awk '{print $1}')"
  echo "  APK lib/$abi SHA-256: $apk_sha"
  local local_bid=""
  local apk_bid=""
  if command -v strings >/dev/null 2>&1; then
    local_bid="$(strings "$local_so" | grep -E "rpcsx=.*samba=.*patch_sha256=" | head -1 || true)"
    apk_bid="$(strings "$apk_so" | grep -E "rpcsx=.*samba=.*patch_sha256=" | head -1 || true)"
    if [[ -n "$apk_bid" ]]; then
      echo "  S3CORE build ID (APK): $apk_bid"
    else
      echo "  S3CORE build ID: not found (old core?)"
    fi
    if [[ -n "$local_bid" ]]; then
      echo "  S3CORE build ID (local): $local_bid"
    fi
  fi
  # Primary check is S3CORE equality (deterministic provenance); SHA may differ due to non-reproducible timestamps
  if [[ -n "$local_bid" && -n "$apk_bid" ]]; then
    if [[ "$local_bid" != "$apk_bid" ]]; then
      echo "  FAIL: S3CORE mismatch for $abi"
      echo "    local: $local_bid"
      echo "    apk:   $apk_bid"
      FAIL=1
    elif [[ "$local_sha" != "$apk_sha" ]]; then
      echo "  WARN: SHA mismatch but S3CORE matches (non-reproducible build, treating as PASS)"
      echo "  PASS: $abi core S3CORE matches"
    else
      echo "  PASS: $abi core matches (SHA+S3CORE)"
    fi
  else
    if [[ "$local_sha" != "$apk_sha" ]]; then
      echo "  FAIL: SHA mismatch for $abi"
      FAIL=1
    else
      echo "  PASS: $abi core matches"
    fi
  fi
}

check_abi "arm64-v8a" "$ARM_LOCAL"
check_abi "x86_64" "$X64_LOCAL"

echo ""
if [[ $FAIL -ne 0 ]]; then
  echo "RESULT: FAIL — APK core is stale or mismatched. Run ./build_rpcsx.sh release before Gradle."
  exit 1
fi

echo "RESULT: PASS — APK core matches local build"
# Also check bundled drivers if present
if unzip -l "$APK" | grep -q "bundled_gpu_drivers"; then
  echo "Bundled drivers found in APK:"
  unzip -l "$APK" | grep bundled_gpu_drivers
else
  echo "No bundled_gpu_drivers in APK (expected for standard flavor until Turnip built)"
fi
