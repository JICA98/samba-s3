#!/usr/bin/env bash
# ============================================================
# SambaS3 — Build and Install Script
# Usage:
#   ./build_and_install.sh                       # SambaS3 release build + install
#   ./build_and_install.sh debug                 # SambaS3 debug build + install
#   ./build_and_install.sh build-only            # SambaS3 build only
#   ./build_and_install.sh bundle-only           # SambaS3 release app bundle only
#   ./build_and_install.sh --uninstall-first     # Uninstall existing app before install
#   ./build_and_install.sh --bundle-only-bump-version  # Increment versionCode, then build app bundle
# ============================================================
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ── Colors ───────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info()    { echo -e "${CYAN}[INFO]${NC} $*"; }
success() { echo -e "${GREEN}[OK]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }

# ── Defaults ─────────────────────────────────────────────────
VARIANT="release"
BUILD_ONLY=false
UNINSTALL_FIRST=false
ARTIFACT_KIND="apk"
BUMP_VERSION=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        debug|release)
            VARIANT="$1"
            ;;
        build-only)
            BUILD_ONLY=true
            VARIANT="release"
            ;;
        bundle-only)
            BUILD_ONLY=true
            VARIANT="release"
            ARTIFACT_KIND="bundle"
            ;;
        --bundle-only-bump-version)
            BUILD_ONLY=true
            VARIANT="release"
            ARTIFACT_KIND="bundle"
            BUMP_VERSION=true
            ;;
        --uninstall-first)
            UNINSTALL_FIRST=true
            ;;
        *)
            error "Unknown argument '$1'. Use: [debug|release|build-only|bundle-only|--bundle-only-bump-version] [--uninstall-first]"
            ;;
    esac
    shift
done

# ── Validate variant ─────────────────────────────────────────
if [[ "$VARIANT" != "debug" && "$VARIANT" != "release" ]]; then
    error "Unknown variant '$VARIANT'. Use: debug | release | build-only | bundle-only"
fi

VARIANT_CAP="${VARIANT^}"  # Capitalise first letter

APP_NAME="SambaS3"
PACKAGE="com.zenithblue.sambas3"
if [[ "$VARIANT" == "debug" ]]; then
    PACKAGE="com.zenithblue.sambas3.debug"
fi
ACTIVITY=".MainActivity"
ADB="adb.exe"

if [[ "$ARTIFACT_KIND" == "bundle" ]]; then
    GRADLE_TASK="bundle${VARIANT_CAP}"
    ARTIFACT_DIR="app/build/outputs/bundle/${VARIANT}"
    ARTIFACT_EXT="aab"
else
    GRADLE_TASK="assemble${VARIANT_CAP}"
    ARTIFACT_DIR="app/build/outputs/apk/${VARIANT}"
    ARTIFACT_EXT="apk"
fi

# ── Bump version code (if requested) ──────────────────────────
GRADLE_FILE="app/build.gradle.kts"

if $BUMP_VERSION; then
    OLD_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' "$GRADLE_FILE")
    NEW_CODE=$((OLD_CODE + 1))
    info "Bumping versionCode: ${OLD_CODE} → ${NEW_CODE}"
    sed -i "s/versionCode = ${OLD_CODE}/versionCode = ${NEW_CODE}/" "$GRADLE_FILE"
fi

# ── Step 1: Build ────────────────────────────────────────────
info "Building ${APP_NAME} ${ARTIFACT_EXT^^} variant: ${VARIANT_CAP}"
./gradlew "$GRADLE_TASK" --quiet

# Locate artifact
ARTIFACT_PATH=""

if [[ "$ARTIFACT_EXT" == "apk" ]]; then
    ARTIFACT_PATH=$(find "$ARTIFACT_DIR" -maxdepth 1 -type f -name "*.apk" ! -name "*-unsigned.apk" | sort | head -1)

    if [[ -z "$ARTIFACT_PATH" ]]; then
        ARTIFACT_PATH=$(find "$ARTIFACT_DIR" -maxdepth 1 -type f -name "*.apk" | sort | head -1)
    fi
else
    ARTIFACT_PATH=$(find "$ARTIFACT_DIR" -maxdepth 1 -type f -name "*.${ARTIFACT_EXT}" | sort | head -1)
fi

if [[ -z "$ARTIFACT_PATH" ]]; then
    error "${ARTIFACT_EXT^^} not found in $ARTIFACT_DIR"
fi

success "Build complete → ${ARTIFACT_PATH}"

# ── Step 2: Install (optional) ───────────────────────────────
if $BUILD_ONLY; then
    info "Skipping install (build-only mode)"
    exit 0
fi

# Check adb is available
if ! command -v "$ADB" &>/dev/null; then
    warn "$ADB not found in PATH — skipping install"
    warn "Install Android Platform Tools and add to PATH to enable auto-install"
    exit 0
fi

# Check device connected
DEVICE_LIST=$($ADB devices | tr -d '\r' | awk 'NR > 1 && $2 == "device" { print $1 }')
if [[ -z "$DEVICE_LIST" ]]; then
    warn "No Android device connected — skipping install"
    warn "Connect a device and enable USB debugging, then re-run this script"
    exit 0
fi

DEVICE_COUNT=$(echo "$DEVICE_LIST" | wc -w)
if [[ "$DEVICE_COUNT" -gt 1 ]]; then
    warn "Multiple devices connected ($DEVICE_COUNT) — installing on all of them"
fi

install_apk() {
    local device="$1"
    local output=""

    if $UNINSTALL_FIRST; then
        warn "Uninstall-first enabled on $device; existing app data will be removed"
        $ADB -s "$device" uninstall "$PACKAGE" >/dev/null 2>&1 || true
    fi

    if output=$($ADB -s "$device" install -r "$APK_PATH" 2>&1); then
        printf '%s\n' "$output"
        return 0
    fi

    printf '%s\n' "$output"
    if [[ "$output" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
        warn "Existing ${PACKAGE} on $device is signed with a different key"
        warn "Re-run with --uninstall-first or run: $ADB -s $device uninstall $PACKAGE"
    fi
    return 1
}

for DEVICE in $DEVICE_LIST; do
    info "Installing on device: $DEVICE…"
    APK_PATH="$ARTIFACT_PATH"
    install_apk "$DEVICE"

    info "Launching ${PACKAGE} on $DEVICE…"
    $ADB -s "$DEVICE" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && success "App launched on $DEVICE!"
done

success "${APP_NAME} (${VARIANT}) processing complete for all devices!"
