#!/bin/bash
set -e

# Build the RPCSX emulator .so from submodule and copy to jniLibs
# Usage: ./build_rpcsx.sh [debug|release]
# Default: release

BUILD_TYPE="${1:-release}"

if [ "$BUILD_TYPE" = "debug" ]; then
    CMAKE_BUILD_TYPE="Debug"
elif [ "$BUILD_TYPE" = "release" ]; then
    CMAKE_BUILD_TYPE="RelWithDebInfo"
else
    echo "Usage: $0 [debug|release]"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RPCSX_DIR="$SCRIPT_DIR/app/src/main/cpp/rpcsx"
RPCSX_ANDROID_DIR="$RPCSX_DIR/android"
JNILIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"
if ! command -v cmake &>/dev/null; then
    if [ -d "/opt/android-sdk/cmake/3.22.1/bin" ]; then
        export PATH="/opt/android-sdk/cmake/3.22.1/bin:$PATH"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/cmake/3.22.1/bin" ]; then
        export PATH="$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
    fi
fi

if [ -z "${NDK_DIR:-}" ]; then
    if [ -d "/opt/android-sdk/ndk/30.0.14904198" ]; then
        NDK_DIR="/opt/android-sdk/ndk/30.0.14904198"
    elif [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        NDK_DIR="$ANDROID_NDK_HOME"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/30.0.14904198" ]; then
        NDK_DIR="$ANDROID_HOME/ndk/30.0.14904198"
    elif [ -d "$HOME/android-sdk/ndk/30.0.14904198-linux" ]; then
        NDK_DIR="$HOME/android-sdk/ndk/30.0.14904198-linux"
    else
        echo "Error: NDK 30.0.14904198 not found"
        exit 1
    fi
fi
TOOLCHAIN="$NDK_DIR/build/cmake/android.toolchain.cmake"
MIN_SDK=29

# Initialize rpcsx submodules if not already done
if [ ! -f "$RPCSX_DIR/3rdparty/fmtlib/CMakeLists.txt" ]; then
    echo "Initializing rpcsx submodules..."
    cd "$SCRIPT_DIR"
    git submodule update --init --recursive app/src/main/cpp/rpcsx
fi

# Apply local RPCSX engine patches (compile-progress JNI, install helpers, etc.).
# Reverse-check: if the patch is already applied, skip. Forward-apply otherwise.
# Fail the build if neither direction is clean so a stale patch cannot silently
# produce an old core where supportsCompileProgressEvents() is false.
PATCH_FILE="$SCRIPT_DIR/patches/rpcsx-submodule-changes.patch"
if [ -f "$PATCH_FILE" ]; then
    if git -C "$RPCSX_DIR" apply --check --reverse "$PATCH_FILE" >/dev/null 2>&1; then
        echo "RPCSX submodule patch already applied"
    elif git -C "$RPCSX_DIR" apply --check "$PATCH_FILE" >/dev/null 2>&1; then
        git -C "$RPCSX_DIR" apply "$PATCH_FILE"
        echo "Applied patches/rpcsx-submodule-changes.patch"
    else
        echo "Error: patches/rpcsx-submodule-changes.patch does not apply cleanly (neither forward nor reverse)."
        echo "Regenerate it from the rpcsx submodule after your engine edits:"
        echo "  git -C app/src/main/cpp/rpcsx add -A"
        echo "  git -C app/src/main/cpp/rpcsx diff --cached > patches/rpcsx-submodule-changes.patch"
        echo "  git -C app/src/main/cpp/rpcsx reset"
        exit 1
    fi
fi

if [ -n "${TARGET_ABI:-}" ]; then
    ABIS=("$TARGET_ABI")
else
    ABIS=("arm64-v8a" "x86_64")
fi

NINJA_BIN="$(command -v ninja || echo "")"
CMAKE_GENERATOR_ARGS=()
if [ -n "$NINJA_BIN" ]; then
    CMAKE_GENERATOR_ARGS=(-GNinja -DCMAKE_MAKE_PROGRAM="$NINJA_BIN")
fi

for ABI in "${ABIS[@]}"; do
    echo "Building RPCSX for ABI: $ABI ($CMAKE_BUILD_TYPE)"

    BUILD_DIR="$SCRIPT_DIR/app/.cxx/rpcsx/$ABI/$BUILD_TYPE"
    mkdir -p "$BUILD_DIR"

    cmake \
        "${CMAKE_GENERATOR_ARGS[@]}" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-$MIN_SDK \
        -DCMAKE_BUILD_TYPE="$CMAKE_BUILD_TYPE" \
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$BUILD_DIR/out" \
        -B "$BUILD_DIR" \
        "$RPCSX_ANDROID_DIR"

    cmake --build "$BUILD_DIR" --target rpcsx-android -j$(nproc 2>/dev/null || echo 4)

    mkdir -p "$JNILIBS_DIR/$ABI"
    cp "$BUILD_DIR/out/librpcsx-android.so" "$JNILIBS_DIR/$ABI/librpcsx-android.so"
    echo "Copied librpcsx-android.so to $JNILIBS_DIR/$ABI/"
done

echo "RPCSX build complete ($BUILD_TYPE) for ABIs: ${ABIS[*]}"
