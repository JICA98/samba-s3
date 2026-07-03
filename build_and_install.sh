#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

APK_PATH="app/build/outputs/apk/debug/samba-s3-debug.apk"
PACKAGE="com.zenithblue.sambas3"
ACTIVITY="${PACKAGE}/.MainActivity"

echo "==> Building APK..."
./gradlew assembleDebug --no-daemon

echo ""
echo "==> Checking connected devices..."
ADB=adb.exe
DEVICES=$($ADB devices | tail -n +2 | grep -v "^$" | awk '{print $1}')
DEVICE_COUNT=$(echo "$DEVICES" | grep -c . || true)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "No devices connected. Aborting."
    exit 1
fi

if [ "$DEVICE_COUNT" -eq 1 ]; then
    SELECTED="$DEVICES"
else
    echo "Multiple devices found:"
    echo ""
    DEVICE_ARRAY=()
    i=1
    while IFS= read -r dev; do
        model=$($ADB -s "$dev" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
        echo "  [$i] $dev ($model)"
        DEVICE_ARRAY+=("$dev")
        ((i++))
    done <<< "$DEVICES"
    echo "  [a] All devices"
    echo "  [n] None (exit)"
    echo ""
    read -rp "Choose device(s): " choice

    case "$choice" in
        a|A)
            SELECTED="$DEVICES"
            ;;
        n|N)
            echo "Aborted."
            exit 0
            ;;
        *)
            idx=$((choice))
            if [ "$idx" -ge 1 ] 2>/dev/null && [ "$idx" -le "$DEVICE_COUNT" ]; then
                SELECTED="${DEVICE_ARRAY[$idx-1]}"
            else
                echo "Invalid choice."
                exit 1
            fi
            ;;
    esac
fi

echo ""
while IFS= read -r dev; do
    echo "==> Installing on $dev..."
    $ADB -s "$dev" install -r "$APK_PATH"
    echo "==> Launching on $dev..."
    $ADB -s "$dev" shell am start -n "$ACTIVITY"
done <<< "$SELECTED"

echo ""
echo "Done."
