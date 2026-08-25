#!/usr/bin/env bash
# Package approved Turnip drivers into Play Store assets (Samba S3 ADPKG format).
# Does NOT download drivers. Reads only drivers/input/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INPUT_DIR="${ROOT}/drivers/input"
OUT_DIR="${ROOT}/app/src/playstore/assets/bundled_gpu_drivers"
LIC_DIR="${ROOT}/app/src/playstore/assets/licenses"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
# Allow pinned Mesa commit injection for reproducible provenance
MESA_COMMIT="${MESA_COMMIT:-unknown}"
if [[ "${1:-}" == "--mesa-commit" && -n "${2:-}" ]]; then
  MESA_COMMIT="$2"
  shift 2
fi

mkdir -p "$OUT_DIR" "$LIC_DIR"

find_input() {
  local exact="$1"
  shift
  if [[ -f "${INPUT_DIR}/${exact}" ]]; then
    echo "${INPUT_DIR}/${exact}"
    return 0
  fi
  local pattern
  for pattern in "$@"; do
    local match
    match="$(find "$INPUT_DIR" -maxdepth 1 -type f -iname "$pattern" 2>/dev/null | head -1 || true)"
    if [[ -n "$match" ]]; then
      # Reject sync A8XX package when looking for V29
      if [[ "$exact" == *V29* || "$exact" == *a8xx* ]]; then
        if [[ "$(basename "$match")" == *sync* || "$(basename "$match")" == *Sync* ]]; then
          continue
        fi
      fi
      echo "$match"
      return 0
    fi
  done
  return 1
}

require_input() {
  local label="$1"
  local exact="$2"
  shift 2
  local path
  if path="$(find_input "$exact" "$@")"; then
    echo "$path"
  else
    echo "ERROR: Missing approved driver package for ${label}" >&2
    echo "  Expected: ${INPUT_DIR}/${exact}" >&2
    echo "  (or matching patterns: $*)" >&2
    return 1
  fi
}

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

extract_or_copy() {
  local src_zip="$1"
  local dest_dir="$2"
  mkdir -p "$dest_dir"
  unzip -q -o "$src_zip" -d "$dest_dir"
  # Flatten one nested directory if needed
  local count
  count="$(find "$dest_dir" -mindepth 1 -maxdepth 1 | wc -l)"
  if [[ "$count" -eq 1 ]]; then
    local only
    only="$(find "$dest_dir" -mindepth 1 -maxdepth 1 -type d | head -1 || true)"
    if [[ -n "$only" ]]; then
      shopt -s dotglob
      mv "$only"/* "$dest_dir"/
      rmdir "$only" 2>/dev/null || true
      shopt -u dotglob
    fi
  fi
}

find_vulkan_lib() {
  local dir="$1"
  if [[ -f "$dir/libvulkan_freedreno.so" ]]; then
    echo "libvulkan_freedreno.so"
    return 0
  fi
  local found
  found="$(find "$dir" -maxdepth 2 -type f -name 'libvulkan*.so' | head -1 || true)"
  if [[ -n "$found" ]]; then
    local base
    base="$(basename "$found")"
    if [[ "$(dirname "$found")" != "$dir" ]]; then
      mv "$found" "$dir/$base"
    fi
    echo "$base"
    return 0
  fi
  return 1
}

write_meta() {
  local dest="$1"
  local name="$2"
  local description="$3"
  local version="$4"
  local library="$5"
  cat >"$dest/meta.json" <<EOF
{
  "schemaVersion": 1,
  "name": "${name}",
  "author": "SambaS3 / Mesa",
  "packageVersion": "1",
  "vendor": "Mesa",
  "driverVersion": "${version}",
  "minApi": 28,
  "description": "${description}",
  "libraryName": "${library}"
}
EOF
}

write_source() {
  local dest="$1"
  local version="$2"
  local repo="$3"
  local commit="$4"
  local notes="$5"
  cat >"$dest/SOURCE.txt" <<EOF
Samba S3 bundled Turnip package
===============================
Source version: ${version}
Source repository: ${repo}
Source commit: ${commit}
Notes: ${notes}

This binary is redistributed under the applicable Mesa / MIT / other upstream
licenses. See LICENSE-MESA when present, and app open-source notices.
EOF
}

ensure_license() {
  local dest="$1"
  if [[ ! -f "$dest/LICENSE-MESA" ]]; then
    if [[ -f "$INPUT_DIR/LICENSE-MESA" ]]; then
      cp "$INPUT_DIR/LICENSE-MESA" "$dest/LICENSE-MESA"
    elif [[ -f "$ROOT/LICENSE" ]]; then
      # Minimal placeholder pointing at Mesa license obligation
      cat >"$dest/LICENSE-MESA" <<'EOF'
Mesa / Turnip redistributable components are licensed under MIT and related
permissive licenses. Upstream Mesa license texts must be preserved with the
binary. Obtain the full Mesa license set from https://gitlab.freedesktop.org/mesa/mesa
EOF
    fi
  fi
}

package_one() {
  local id="$1"
  local display_name="$2"
  local description="$3"
  local version="$4"
  local out_name="$5"
  local src_zip="$6"
  local repo="$7"
  local commit="$8"
  local notes="$9"

  local stage="${WORKDIR}/${id}"
  rm -rf "$stage"
  mkdir -p "$stage"
  extract_or_copy "$src_zip" "$stage"

  local lib
  lib="$(find_vulkan_lib "$stage")" || {
    echo "ERROR: ${src_zip} missing Vulkan library" >&2
    exit 1
  }

  write_meta "$stage" "$display_name" "$description" "$version" "$lib"
  write_source "$stage" "$version" "$repo" "$commit" "$notes"
  ensure_license "$stage"

  local out_zip="${OUT_DIR}/${out_name}"
  rm -f "$out_zip"
  (
    cd "$stage"
    zip -q -9 "$out_zip" meta.json "$lib" SOURCE.txt
    [[ -f LICENSE-MESA ]] && zip -q -9 "$out_zip" LICENSE-MESA
    # Include any additional .so dependencies without renaming
    find . -maxdepth 1 -type f -name '*.so' ! -name "$lib" -print0 | while IFS= read -r -d '' f; do
      zip -q -9 "$out_zip" "${f#./}"
    done
    find . -maxdepth 1 -type f -name '*.json' ! -name 'meta.json' -print0 | while IFS= read -r -d '' f; do
      zip -q -9 "$out_zip" "${f#./}"
    done
  )
  echo "$out_zip"
}

main() {
  echo "Packaging bundled Turnip drivers from ${INPUT_DIR}"

  local missing=0
  local z2614 z2534 za8xx
  # Prefer 26.3 (latest) over 26.1.4; support both for backward compat
  if ! z2614="$(require_input "Turnip 26.3" "turnip-26.3.zip" "*26.3*.zip" "*turnip*26.3*.zip")" 2>/dev/null; then
    z2614="$(require_input "Turnip 26.1.4" "turnip-26.1.4.zip" "*26.1.4*.zip" "*turnip*26.1.4*.zip")" || missing=1
  fi
  z2534="$(require_input "Turnip 25.3.4" "turnip-25.3.4.zip" "*25.3.4*.zip" "*turnip*25.3.4*.zip")" || missing=1
  za8xx="$(require_input "Turnip A8XX v29" "a8xx-turnip-gen8-V29.zip" "*a8xx*V29*.zip" "*a8xx-turnip*V29*.zip" "*gen8*V29*.zip")" || missing=1

  if [[ "$missing" -ne 0 ]]; then
    echo ""
    echo "Cannot produce Play Store bundled assets without all three approved packages."
    echo "See drivers/input/README.md"
    exit 2
  fi

  # Reject sync package explicitly
  if [[ "$(basename "$za8xx")" == *sync* || "$(basename "$za8xx")" == *Sync* ]]; then
    echo "ERROR: A8XX sync package is not allowed. Use normal/non-sync a8xx-turnip-gen8-V29.zip" >&2
    exit 1
  fi

  local p2614 p2534 pa8xx
  # Detect whether we are packaging 26.3 or fallback 26.1.4 for correct catalog metadata
  local ver2614="26.3"
  local id2614="turnip-26.3"
  local disp2614="Turnip 26.3 — Recommended"
  local pkg2614="turnip-26.3-sambas3.zip"
  if [[ "$(basename "$z2614")" == *"26.1.4"* ]]; then
    ver2614="26.1.4"
    id2614="turnip-26.1.4"
    disp2614="Turnip 26.1.4 — Recommended"
    pkg2614="turnip-26.1.4-sambas3.zip"
  fi
  p2614="$(package_one \
    "$id2614" \
    "$disp2614" \
    "Included with Samba S3. Recommended Turnip for Adreno 6xx and 7xx." \
    "$ver2614" \
    "$pkg2614" \
    "$z2614" \
    "https://gitlab.freedesktop.org/mesa/mesa" \
    "$MESA_COMMIT" \
    "Mesa Turnip $ver2614 — recommended")"

  p2534="$(package_one \
    "turnip-25.3.4" \
    "Turnip 25.3.4" \
    "Included with Samba S3. Compatibility Turnip for devices or games that regress on 26.1.4." \
    "25.3.4" \
    "turnip-25.3.4-sambas3.zip" \
    "$z2534" \
    "https://gitlab.freedesktop.org/mesa/mesa" \
    "$MESA_COMMIT" \
    "Mesa Turnip 25.3.4 — compatibility")"

  pa8xx="$(package_one \
    "turnip-a8xx-v29" \
    "Turnip A8XX v29" \
    "Included with Samba S3. Experimental support for Adreno 8xx (830/840). Not the default." \
    "A8XX v29" \
    "turnip-a8xx-v29-sambas3.zip" \
    "$za8xx" \
    "upstream A8XX Turnip package (a8xx-turnip-gen8-V29)" \
    "$MESA_COMMIT" \
    "Turnip A8XX v29 normal/non-sync — experimental")"

  local h2614 h2534 ha8xx
  h2614="$(sha256_file "$p2614")"
  h2534="$(sha256_file "$p2534")"
  ha8xx="$(sha256_file "$pa8xx")"

  # Determine 26.3 vs 26.1.4 catalog fields based on which zip was used
  local catId2614="turnip-26.3"
  local catDisp2614="Turnip 26.3 — Recommended"
  local catPkg2614="turnip-26.3-sambas3.zip"
  local catVer2614="Mesa 26.3"
  if [[ "$(basename "$z2614")" == *"26.1.4"* ]]; then
    catId2614="turnip-26.1.4"
    catDisp2614="Turnip 26.1.4 — Recommended"
    catPkg2614="turnip-26.1.4-sambas3.zip"
    catVer2614="Mesa 26.1.4"
  fi
  cat >"${OUT_DIR}/catalog.json" <<EOF
{
  "schemaVersion": 1,
  "drivers": [
    {
      "id": "${catId2614}",
      "displayName": "${catDisp2614}",
      "role": "recommended",
      "packageFile": "${catPkg2614}",
      "libraryName": "libvulkan_freedreno.so",
      "supportedGpuFamilies": ["adreno6xx", "adreno7xx"],
      "experimental": false,
      "sha256": "${h2614}",
      "sourceVersion": "${catVer2614}",
      "sourceCommit": "${MESA_COMMIT}",
      "sourceRepo": "https://gitlab.freedesktop.org/mesa/mesa",
      "notes": "Default recommended Turnip for Adreno 6xx/7xx. Not auto-selected on first launch."
    },
    {
      "id": "turnip-25.3.4",
      "displayName": "Turnip 25.3.4 — Compatibility",
      "role": "compatibility",
      "packageFile": "turnip-25.3.4-sambas3.zip",
      "libraryName": "libvulkan_freedreno.so",
      "supportedGpuFamilies": ["adreno6xx", "adreno7xx"],
      "experimental": false,
      "sha256": "${h2534}",
      "sourceVersion": "Mesa 25.3.4",
      "sourceCommit": "${MESA_COMMIT}",
      "sourceRepo": "https://gitlab.freedesktop.org/mesa/mesa",
      "notes": "Fallback when ${catVer2614#Mesa } regresses."
    },
    {
      "id": "turnip-a8xx-v29",
      "displayName": "Turnip A8XX v29 — Experimental",
      "role": "experimental",
      "packageFile": "turnip-a8xx-v29-sambas3.zip",
      "libraryName": "libvulkan_freedreno.so",
      "supportedGpuIds": ["810", "825", "829", "830", "840"],
      "forceSysmemGpuIds": ["830"],
      "experimental": true,
      "sha256": "${ha8xx}",
      "sourceVersion": "A8XX v29",
      "sourceCommit": "${MESA_COMMIT}",
      "sourceRepo": "upstream a8xx-turnip-gen8-V29 (normal/non-sync)",
      "notes": "Experimental. Never auto-selected. Adreno 830 uses SYSMEM when selected."
    }
  ]
}
EOF

  # Open-source notice for Play listing / in-app
  cat >"${LIC_DIR}/mesa-turnip-NOTICE.txt" <<EOF
Samba S3 Play Store builds may include Mesa Turnip Vulkan drivers:

- ${catVer2614}
- Mesa Turnip 25.3.4
- Turnip A8XX v29 (experimental)

Mesa is available from https://gitlab.freedesktop.org/mesa/mesa and is licensed
under MIT and other permissive licenses. Corresponding source offers follow the
upstream project terms. Packaged SHA-256 checksums are recorded in
bundled_gpu_drivers/catalog.json.

This notice does not claim Google Play policy compliance by itself.
EOF

  echo "Packaged:"
  echo "  $p2614  sha256=$h2614"
  echo "  $p2534  sha256=$h2534"
  echo "  $pa8xx  sha256=$ha8xx"
  echo "  ${OUT_DIR}/catalog.json"
}

main "$@"
