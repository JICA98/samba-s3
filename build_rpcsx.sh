#!/bin/bash
set -e

# Build the RPCSX emulator .so from submodule and copy to jniLibs
# Usage: ./build_rpcsx.sh [debug|release] [--allow-dirty]
# Default: release

BUILD_TYPE="release"
ALLOW_DIRTY="${ALLOW_DIRTY:-0}"

for arg in "$@"; do
    case "$arg" in
        debug)
            BUILD_TYPE="debug"
            ;;
        release)
            BUILD_TYPE="release"
            ;;
        --allow-dirty)
            ALLOW_DIRTY=1
            ;;
        *)
            echo "Usage: $0 [debug|release] [--allow-dirty]"
            exit 1
            ;;
    esac
done

if [ "$BUILD_TYPE" = "debug" ]; then
    CMAKE_BUILD_TYPE="Debug"
elif [ "$BUILD_TYPE" = "release" ]; then
    CMAKE_BUILD_TYPE="RelWithDebInfo"
else
    echo "Usage: $0 [debug|release] [--allow-dirty]"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RPCSX_DIR="$SCRIPT_DIR/app/src/main/cpp/rpcsx"
RPCSX_ANDROID_DIR="$RPCSX_DIR/android"
JNILIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"
PROVENANCE_PY="$SCRIPT_DIR/scripts/lib/core_provenance.py"
PATCH_FILE="$SCRIPT_DIR/patches/rpcsx-submodule-changes.patch"

# R05: Require clean committed source for release builds unless --allow-dirty is passed
if [ "$BUILD_TYPE" = "release" ] && [ "$ALLOW_DIRTY" != "1" ]; then
    python3 "$PROVENANCE_PY" check-clean --rpcsx-dir "$RPCSX_DIR"
fi

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

if [ ! -f "$RPCSX_DIR/3rdparty/fmtlib/CMakeLists.txt" ]; then
    echo "Initializing rpcsx submodules..."
    cd "$SCRIPT_DIR"
    git submodule update --init --recursive app/src/main/cpp/rpcsx
fi

python3 "$PROVENANCE_PY" apply-patch --rpcsx-dir "$RPCSX_DIR" --patch-file "$PATCH_FILE"

if [ -n "${TARGET_ABI:-}" ]; then
    ABIS=("$TARGET_ABI")
else
    ABIS=("arm64-v8a")
fi

NINJA_BIN="$(command -v ninja || echo "")"
CMAKE_GENERATOR_ARGS=()
if [ -n "$NINJA_BIN" ]; then
    CMAKE_GENERATOR_ARGS=(-GNinja -DCMAKE_MAKE_PROGRAM="$NINJA_BIN")
fi

EXTRA_CMAKE_ARGS=()
if [ -n "${USE_ARCH:-}" ]; then
    EXTRA_CMAKE_ARGS+=("-DUSE_ARCH=$USE_ARCH")
fi

for ABI in "${ABIS[@]}"; do
    echo "Building RPCSX for ABI: $ABI ($CMAKE_BUILD_TYPE)"

    BUILD_DIR="$SCRIPT_DIR/app/.cxx/rpcsx/$ABI/$BUILD_TYPE"
    mkdir -p "$BUILD_DIR"

    # R04: Configure CMake first to resolve effective compiler and compile/link options
    cmake \
        "${CMAKE_GENERATOR_ARGS[@]}" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=android-$MIN_SDK \
        -DCMAKE_BUILD_TYPE="$CMAKE_BUILD_TYPE" \
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$BUILD_DIR/out" \
        -DSAMBA_BUILD_ID_CPP="$BUILD_DIR/samba-build-id.cpp" \
        "${EXTRA_CMAKE_ARGS[@]}" \
        -B "$BUILD_DIR" \
        "$RPCSX_ANDROID_DIR"

    STAMP_ARGS=(
        python3 "$PROVENANCE_PY" stamp
        --output "$BUILD_DIR/samba-build-id.cpp"
        --rpcsx-dir "$RPCSX_DIR"
        --root "$SCRIPT_DIR"
        --abi "$ABI"
        --build-type "$CMAKE_BUILD_TYPE"
        --ndk-dir "$NDK_DIR"
        --patch-file "$PATCH_FILE"
        --build-dir "$BUILD_DIR"
    )
    if [ "$ALLOW_DIRTY" = "1" ]; then
        STAMP_ARGS+=(--allow-dirty)
    fi
    "${STAMP_ARGS[@]}"
    IDENTITY="$(tr -d '\n' < "$BUILD_DIR/samba-build-id.txt")"

    cmake --build "$BUILD_DIR" --target rpcsx-android -j$(nproc 2>/dev/null || echo 4)

    mkdir -p "$JNILIBS_DIR/$ABI"
    cp "$BUILD_DIR/out/librpcsx-android.so" "$JNILIBS_DIR/$ABI/librpcsx-android.so"
    python3 "$PROVENANCE_PY" manifest \
        --output "$JNILIBS_DIR/$ABI/librpcsx-android.manifest.json" \
        --library "$JNILIBS_DIR/$ABI/librpcsx-android.so" \
        --rpcsx-dir "$RPCSX_DIR" \
        --root "$SCRIPT_DIR" \
        --abi "$ABI" \
        --build-type "$CMAKE_BUILD_TYPE" \
        --ndk-dir "$NDK_DIR" \
        --patch-file "$PATCH_FILE" \
        --build-dir "$BUILD_DIR" \
        --identity "$IDENTITY"
    echo "Copied librpcsx-android.so to $JNILIBS_DIR/$ABI/"
done

echo "RPCSX build complete ($BUILD_TYPE) for ABIs: ${ABIS[*]}"
