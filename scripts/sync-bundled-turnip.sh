#!/usr/bin/env bash
# Sync exactly one real Turnip 26.3 bundled driver (Samba S3)
# Pinned prebuilt, SHA-verified, ELF-validated, wrapper byte-identical.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_MAIN_DIR="$ROOT/app/src/main/assets/bundled_gpu_drivers"
ASSET_PLAY_DIR="$ROOT/app/src/playstore/assets/bundled_gpu_drivers"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Pinned provenance (do not change without deliberate update)
PINNED_TAG="v26.3.0-20260826-r3"
PINNED_ASSET="Turnip-v26.3.0-20260826-r3.zip"
PINNED_URL="https://github.com/The412Banner/Banners-Turnip/releases/download/${PINNED_TAG}/${PINNED_ASSET}"
PINNED_SHA256="94641a7e496f5d1f21d92d587d2f9336c0773582f38601e9d666b44240e3c8b8"
PINNED_SIZE="2625005"
PINNED_MESA="26.3.0"
PINNED_MESA_COMMIT="bb63b1797b657b64dac605391d251a22e1c9cefc"
PINNED_VULKAN="1.4.359"

# Allow override for testing, but default is pinned
UPSTREAM_ZIP="${1:-}"
if [[ -z "$UPSTREAM_ZIP" ]]; then
  # Use cached download if exists and matches SHA, else download
  CACHE="$ROOT/drivers/input/${PINNED_ASSET}"
  if [[ -f "$CACHE" ]] && echo "${PINNED_SHA256}  $CACHE" | sha256sum -c - >/dev/null 2>&1; then
    echo "Using cached $CACHE"
    UPSTREAM_ZIP="$CACHE"
  else
    UPSTREAM_ZIP="$WORK/upstream.zip"
    echo "Downloading $PINNED_URL"
    if command -v curl >/dev/null 2>&1; then
      curl -L -o "$UPSTREAM_ZIP" "$PINNED_URL"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$UPSTREAM_ZIP" "$PINNED_URL"
    else
      echo "ERROR: need curl or wget" >&2; exit 1
    fi
  fi
fi

# 1. Verify exact SHA-256
echo "Verifying SHA-256..."
ACTUAL_SHA="$(sha256sum "$UPSTREAM_ZIP" | awk '{print $1}')"
if [[ "$ACTUAL_SHA" != "$PINNED_SHA256" ]]; then
  echo "ERROR: SHA mismatch expected $PINNED_SHA256 got $ACTUAL_SHA" >&2
  exit 1
fi
echo "SHA OK $ACTUAL_SHA"

# 2. Verify size
ACTUAL_SIZE="$(stat -c%s "$UPSTREAM_ZIP" 2>/dev/null || stat -f%z "$UPSTREAM_ZIP")"
if [[ "$ACTUAL_SIZE" != "$PINNED_SIZE" ]]; then
  echo "WARNING: size $ACTUAL_SIZE != pinned $PINNED_SIZE (continuing if SHA matches)" >&2
fi
echo "Size $ACTUAL_SIZE bytes"

# 3. Verify ZIP can be opened
if ! unzip -l "$UPSTREAM_ZIP" >/dev/null 2>&1; then
  echo "ERROR: ZIP cannot be opened" >&2; exit 1
fi
echo "ZIP list:"
unzip -l "$UPSTREAM_ZIP" | head -n 20

# 4. Extract and inspect Vulkan ELF
EXTRACT_DIR="$WORK/extract"
mkdir -p "$EXTRACT_DIR"
unzip -q -o "$UPSTREAM_ZIP" -d "$EXTRACT_DIR"
# Find lib
LIB_PATH=""
if [[ -f "$EXTRACT_DIR/libvulkan_freedreno.so" ]]; then
  LIB_PATH="$EXTRACT_DIR/libvulkan_freedreno.so"
else
  LIB_PATH="$(find "$EXTRACT_DIR" -type f -name 'libvulkan*.so' | head -1 || true)"
  if [[ -z "$LIB_PATH" ]]; then
    echo "ERROR: no Vulkan library in ZIP" >&2; exit 1
  fi
  # Move to expected name if different? Keep original name for wrapper but ensure we preserve bytes
fi
LIB_NAME="$(basename "$LIB_PATH")"
echo "Found library $LIB_NAME size $(stat -c%s "$LIB_PATH" 2>/dev/null || stat -f%z "$LIB_PATH")"

UPSTREAM_SO_SHA="$(sha256sum "$LIB_PATH" | awk '{print $1}')"
echo "Upstream .so SHA $UPSTREAM_SO_SHA"

# 5. Strict ELF validation
python3 - "$LIB_PATH" <<'PY'
import sys
path = sys.argv[1]
with open(path, 'rb') as f:
  data = f.read(64)
  if len(data) < 64:
    print("ERROR: file too small for ELF", file=sys.stderr); sys.exit(1)
  if data[0:4] != b'\x7fELF':
    print("ERROR: not ELF magic", file=sys.stderr); sys.exit(1)
  if data[4] != 2:
    print(f"ERROR: EI_CLASS {data[4]} != 2 ELF64", file=sys.stderr); sys.exit(1)
  if data[5] != 1:
    print(f"ERROR: EI_DATA {data[5]} != 1 LE", file=sys.stderr); sys.exit(1)
  e_machine = int.from_bytes(data[18:20], 'little')
  if e_machine != 183:
    print(f"ERROR: e_machine {e_machine} != 183 AArch64", file=sys.stderr); sys.exit(1)
  size = __import__('os').path.getsize(path)
  if size < 500*1024:
    print(f"ERROR: size {size} < 500 KiB", file=sys.stderr); sys.exit(1)
  if b'stub libvulkan' in open(path,'rb').read():
    print("ERROR: stub marker found", file=sys.stderr); sys.exit(1)
  print(f"ELF validation OK: class=ELF64 LE machine=AArch64 size={size}")
PY

# 6. Create Samba wrapper (preserve upstream .so byte-identical)
WRAP_DIR="$WORK/wrap"
mkdir -p "$WRAP_DIR"
cp "$LIB_PATH" "$WRAP_DIR/libvulkan_freedreno.so"
# Preserve or adapt meta.json
if [[ -f "$EXTRACT_DIR/meta.json" ]]; then
  cp "$EXTRACT_DIR/meta.json" "$WRAP_DIR/meta.json"
  # Ensure it has expected fields; if missing, rewrite minimal
  if ! grep -q "libraryName" "$WRAP_DIR/meta.json"; then
    cat >"$WRAP_DIR/meta.json" <<META
{
  "schemaVersion": 1,
  "name": "Turnip 26.3 — Recommended",
  "author": "SambaS3 / Mesa",
  "packageVersion": "1",
  "vendor": "Mesa",
  "driverVersion": "$PINNED_VULKAN",
  "minApi": 28,
  "description": "Turnip $PINNED_MESA latest prebaked for Adreno 6xx/7xx. System remains default.",
  "libraryName": "libvulkan_freedreno.so"
}
META
  fi
else
  cat >"$WRAP_DIR/meta.json" <<META
{
  "schemaVersion": 1,
  "name": "Turnip 26.3 — Recommended",
  "author": "SambaS3 / Mesa",
  "packageVersion": "1",
  "vendor": "Mesa",
  "driverVersion": "$PINNED_VULKAN",
  "minApi": 28,
  "description": "Turnip $PINNED_MESA latest prebaked for Adreno 6xx/7xx. System remains default.",
  "libraryName": "libvulkan_freedreno.so"
}
META
fi
# SOURCE.txt provenance
cat >"$WRAP_DIR/SOURCE.txt" <<SRC
Samba S3 bundled Turnip package
===============================
Source version: Mesa $PINNED_MESA
Source release: $PINNED_TAG
Source asset: $PINNED_ASSET
Source repository: https://github.com/The412Banner/Banners-Turnip
Source commit: $PINNED_MESA_COMMIT
Vulkan: $PINNED_VULKAN
Upstream ZIP SHA-256: $PINNED_SHA256
Upstream .so SHA-256: $UPSTREAM_SO_SHA

This binary is redistributed under Mesa / MIT licenses.
Wrapper adds only metadata; Vulkan .so bytes are unchanged.
SRC
# LICENSE if available else minimal placeholder
if [[ -f "$ROOT/LICENSE" ]]; then
  cp "$ROOT/LICENSE" "$WRAP_DIR/LICENSE-MESA" 2>/dev/null || true
else
  cat >"$WRAP_DIR/LICENSE-MESA" <<'LIC'
Mesa / Turnip redistributable components are licensed under MIT and related
permissive licenses. Upstream Mesa license texts must be preserved with the
binary. Obtain the full Mesa license set from https://gitlab.freedesktop.org/mesa/mesa
LIC
fi

# 7. Ensure wrapper .so identical
WRAPPED_SO_SHA="$(sha256sum "$WRAP_DIR/libvulkan_freedreno.so" | awk '{print $1}')"
if [[ "$WRAPPED_SO_SHA" != "$UPSTREAM_SO_SHA" ]]; then
  echo "ERROR: wrapped .so SHA $WRAPPED_SO_SHA != upstream $UPSTREAM_SO_SHA (must be byte-identical)" >&2
  exit 1
fi
echo "Wrapper .so byte-identical OK"

# 8. Build wrapper ZIP
mkdir -p "$ASSET_MAIN_DIR" "$ASSET_PLAY_DIR"
WRAPPER_ZIP_MAIN="$ASSET_MAIN_DIR/turnip-26.3-sambas3.zip"
WRAPPER_ZIP_PLAY="$ASSET_PLAY_DIR/turnip-26.3-sambas3.zip"
rm -f "$WRAPPER_ZIP_MAIN" "$WRAPPER_ZIP_PLAY"
( cd "$WRAP_DIR" && zip -q -9 "$WRAPPER_ZIP_MAIN" meta.json libvulkan_freedreno.so SOURCE.txt )
if [[ -f "$WRAP_DIR/LICENSE-MESA" ]]; then
  ( cd "$WRAP_DIR" && zip -q -9 "$WRAPPER_ZIP_MAIN" LICENSE-MESA )
fi
# Also copy to playstore dir
cp "$WRAPPER_ZIP_MAIN" "$WRAPPER_ZIP_PLAY"

WRAPPER_SHA="$(sha256sum "$WRAPPER_ZIP_MAIN" | awk '{print $1}')"
WRAPPER_SIZE="$(stat -c%s "$WRAPPER_ZIP_MAIN" 2>/dev/null || stat -f%z "$WRAPPER_ZIP_MAIN")"
echo "Wrapper ZIP $WRAPPER_ZIP_MAIN sha=$WRAPPER_SHA size=$WRAPPER_SIZE"

# 9. Remove old fake packages
for old in "$ASSET_MAIN_DIR"/turnip-25.3.4-sambas3.zip "$ASSET_MAIN_DIR"/turnip-a8xx-v29-sambas3.zip \
           "$ASSET_PLAY_DIR"/turnip-25.3.4-sambas3.zip "$ASSET_PLAY_DIR"/turnip-a8xx-v29-sambas3.zip \
           "$ASSET_PLAY_DIR"/turnip-26.3-sambas3.zip "$ASSET_MAIN_DIR"/turnip-26.1.4-sambas3.zip \
           "$ASSET_PLAY_DIR"/turnip-26.1.4-sambas3.zip; do
  # Keep only the one we just created in play dir (already copied), but remove others
  if [[ "$old" == "$WRAPPER_ZIP_PLAY" ]]; then continue; fi
  if [[ -f "$old" ]]; then rm -f "$old"; echo "Removed old $old"; fi
done
# Also remove any other leftover turnip zips except wrapper
find "$ASSET_MAIN_DIR" -maxdepth 1 -name 'turnip-*.zip' ! -name 'turnip-26.3-sambas3.zip' -delete 2>/dev/null || true
find "$ASSET_PLAY_DIR" -maxdepth 1 -name 'turnip-*.zip' ! -name 'turnip-26.3-sambas3.zip' -delete 2>/dev/null || true

# 10. Update catalog.json in both dirs (single entry)
for CAT in "$ASSET_MAIN_DIR/catalog.json" "$ASSET_PLAY_DIR/catalog.json"; do
mkdir -p "$(dirname "$CAT")"
cat >"$CAT" <<CATJSON
{
  "schemaVersion": 1,
  "drivers": [
    {
      "id": "turnip-26.3",
      "displayName": "Turnip 26.3 — Latest",
      "role": "recommended",
      "packageFile": "turnip-26.3-sambas3.zip",
      "libraryName": "libvulkan_freedreno.so",
      "supportedGpuFamilies": ["adreno6xx", "adreno7xx"],
      "experimental": false,
      "sha256": "$WRAPPER_SHA",
      "sourceVersion": "Mesa $PINNED_MESA",
      "sourceCommit": "$PINNED_MESA_COMMIT",
      "sourceRepo": "https://github.com/The412Banner/Banners-Turnip",
      "notes": "Prebaked latest Turnip for Adreno 6xx/7xx. System Vulkan remains default; not auto-selected. Wrapper .so SHA $WRAPPED_SO_SHA matches upstream.",
      "upstreamZipSha256": "$PINNED_SHA256",
      "upstreamSoSha256": "$UPSTREAM_SO_SHA",
      "wrapperSoSha256": "$WRAPPED_SO_SHA",
      "vulkanVersion": "$PINNED_VULKAN",
      "releaseTag": "$PINNED_TAG"
    }
  ]
}
CATJSON
echo "Wrote $CAT"
done

cat >"$ASSET_MAIN_DIR/README.md" <<'RMD'
# Bundled GPU drivers — Samba S3

This directory is populated by:

```bash
./scripts/sync-bundled-turnip.sh
```

Pinned provenance: The412Banner/Banners-Turnip v26.3.0-20260826-r3
Upstream asset: Turnip-v26.3.0-20260826-r3.zip
SHA-256: 94641a7e496f5d1f21d92d587d2f9336c0773582f38601e9d666b44240e3c8b8

Contains exactly one prebaked package:

```text
catalog.json
turnip-26.3-sambas3.zip   // wrapper with identical libvulkan_freedreno.so bytes
```

System Vulkan remains default; Turnip is never auto-selected.
RMD
cp "$ASSET_MAIN_DIR/README.md" "$ASSET_PLAY_DIR/README.md"

# Update license notice
LIC_NOTICE_DIR="$ROOT/app/src/playstore/assets/licenses"
mkdir -p "$LIC_NOTICE_DIR"
cat >"$LIC_NOTICE_DIR/mesa-turnip-NOTICE.txt" <<NOTICE
Samba S3 Play Store builds include Mesa Turnip Vulkan driver:

- Mesa Turnip $PINNED_MESA ($PINNED_TAG, Vulkan $PINNED_VULKAN)
  Source: https://github.com/The412Banner/Banners-Turnip
  Upstream ZIP SHA-256: $PINNED_SHA256
  Wrapped ZIP SHA-256: $WRAPPER_SHA
  Vulkan .so SHA-256: $WRAPPED_SO_SHA (byte-identical to upstream)

Mesa is available from https://gitlab.freedesktop.org/mesa/mesa and is licensed
under MIT and other permissive licenses. Corresponding source offers follow the
upstream project terms. Packaged SHA-256 checksums are recorded in
bundled_gpu_drivers/catalog.json.

This notice does not claim Google Play policy compliance by itself.
NOTICE

echo "=== Sync complete ==="
echo "Upstream ZIP SHA: $PINNED_SHA256"
echo "Upstream .so SHA: $UPSTREAM_SO_SHA"
echo "Wrapper ZIP SHA : $WRAPPER_SHA"
echo "Wrapper .so SHA : $WRAPPED_SO_SHA (must equal upstream)"
echo "Byte identical: $([ "$WRAPPED_SO_SHA" = "$UPSTREAM_SO_SHA" ] && echo YES || echo NO)"
ls -lh "$ASSET_MAIN_DIR"
echo "Catalog:"
cat "$ASSET_MAIN_DIR/catalog.json"
