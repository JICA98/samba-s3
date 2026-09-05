#!/usr/bin/env bash
# Automated test runner for Samba S3 Performance Monitoring Overlay
# Validates positions, layouts, presets, individual metrics, graphs, opacity, text scale, and hideWithMenu.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERIAL="${1:-}"
if [[ -z "$SERIAL" ]]; then
  mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ ${#devices[@]} == 1 ]]; then
    SERIAL="${devices[0]}"
  else
    echo "Usage: $0 SERIAL" >&2
    exit 1
  fi
fi

OUTDIR="docs/testers/artifacts/monitoring_overlay"
mkdir -p "$OUTDIR"

echo "=== Running Performance Monitoring Test Matrix on $SERIAL ==="

cap() {
  local name="$1"
  sleep 0.8
  adb -s "$SERIAL" exec-out screencap -p > "$OUTDIR/$name.png"
  echo "Captured $OUTDIR/$name.png"
}

set_mon() {
  "$SCRIPT_DIR/debug-monitor.sh" "$SERIAL" "$@"
}

echo "--- 1. Testing Master Enable: ON / OFF ---"
set_mon --ez enabled false
cap "01_master_disabled"
set_mon --ez enabled true
cap "01_master_enabled"

echo "--- 2. Testing All Six Positions ---"
for pos in TopLeft TopCenter TopRight BottomLeft BottomCenter BottomRight; do
  echo "Testing Position: $pos"
  set_mon --es position "$pos"
  cap "pos_$pos"
done

echo "--- 3. Testing All Three Layouts ---"
for layout in Compact Grid Detailed; do
  echo "Testing Layout: $layout"
  set_mon --es position TopRight --es layout "$layout"
  cap "layout_$layout"
done

echo "--- 4. Testing Presets ---"
for preset in Minimal Performance Developer; do
  echo "Testing Preset: $preset"
  set_mon --es position TopRight --es layout Grid --es preset "$preset"
  cap "preset_$preset"
done

echo "--- 5. Testing Graphs (FPS, FRAME, None, Both) ---"
echo "Graphs: None"
set_mon --es graphs none
cap "graphs_none"
echo "Graphs: FPS only"
set_mon --es graphs Fps
cap "graphs_fps_only"
echo "Graphs: FrameTime only"
set_mon --es graphs FrameTime
cap "graphs_frametime_only"
echo "Graphs: Both"
set_mon --es graphs Fps,FrameTime
cap "graphs_both"

echo "--- 6. Testing Text Scale and Opacity ---"
echo "Opacity 0.35, Text scale 0.8"
set_mon --ef opacity 0.35 --ef text_scale 0.80
cap "appearance_min"
echo "Opacity 1.0, Text scale 1.25"
set_mon --ef opacity 1.0 --ef text_scale 1.25
cap "appearance_max"
echo "Resetting appearance to defaults"
set_mon --ef opacity 0.72 --ef text_scale 0.88

echo "--- 7. Testing Individual Metrics ---"
echo "Single metric: FPS"
set_mon --es metrics Fps --es graphs none
cap "metric_single_fps"
echo "Single metric: GpuHardwareLoad (testing unavailable marker)"
set_mon --es metrics GpuHardwareLoad
cap "metric_single_gpu_load"
echo "Single metric: BatteryTemperature"
set_mon --es metrics BatteryTemperature
cap "metric_single_bat_temp"

echo "--- 8. Testing In-game Menu Hide/Show ---"
echo "Hide with menu = true"
set_mon --ez hide_with_menu true --es preset Performance --es position TopRight
cap "menu_before_open"
echo "Opening in-game menu via PS button..."
"$SCRIPT_DIR/debug-pad.sh" "$SERIAL" PS
sleep 1.0
cap "menu_open_overlay_hidden"
echo "Closing in-game menu via PS button..."
"$SCRIPT_DIR/debug-pad.sh" "$SERIAL" PS
sleep 1.0
cap "menu_closed_overlay_restored"

echo "--- 9. Restoring Performance preset ---"
set_mon --ez enabled true --es preset Performance --es position TopLeft --es layout Compact

echo "=== All test matrix steps completed successfully! ==="
