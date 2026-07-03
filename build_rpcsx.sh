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
NDK_DIR="/home/jica/android-sdk/ndk/30.0.14904198-linux"
TOOLCHAIN="$NDK_DIR/build/cmake/android.toolchain.cmake"
MIN_SDK=29

# Initialize rpcsx submodules if not already done
if [ ! -f "$RPCSX_DIR/3rdparty/fmtlib/CMakeLists.txt" ]; then
    echo "Initializing rpcsx submodules..."
    cd "$SCRIPT_DIR"
    git submodule update --init --recursive app/src/main/cpp/rpcsx
fi

echo "Building RPCSX for ABI: arm64-v8a ($CMAKE_BUILD_TYPE)"

BUILD_DIR="$SCRIPT_DIR/app/.cxx/rpcsx/arm64-v8a/$BUILD_TYPE"
mkdir -p "$BUILD_DIR"

cmake \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-$MIN_SDK \
    -DCMAKE_BUILD_TYPE="$CMAKE_BUILD_TYPE" \
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$BUILD_DIR/out" \
    -B "$BUILD_DIR" \
    "$RPCSX_ANDROID_DIR"

cmake --build "$BUILD_DIR" --target rpcsx-android -j$(nproc 2>/dev/null || echo 4)

mkdir -p "$JNILIBS_DIR/arm64-v8a"
cp "$BUILD_DIR/out/librpcsx-android.so" "$JNILIBS_DIR/arm64-v8a/librpcsx-android.so"
echo "Copied librpcsx-android.so to $JNILIBS_DIR/arm64-v8a/"

echo "RPCSX build complete ($BUILD_TYPE)"
