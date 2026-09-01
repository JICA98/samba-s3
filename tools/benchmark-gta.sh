#!/usr/bin/env bash
set -euo pipefail

# Low-overhead GTA sampler. Launch GTA through the normal UI first, then run:
#   ADB_SERIAL='adb-...' DURATION_SECONDS=60 ./tools/benchmark-gta.sh out.log
# The debug bridge samples the native emu_flip counter once per second and
# emits only S3BENCH lines; no performance overlay or Compose graph is used.

serial="${ADB_SERIAL:-}"
duration="${DURATION_SECONDS:-60}"
output="${OUTPUT_FILE:-${1:-}}"

if [[ -z "$serial" ]]; then
    echo "ADB_SERIAL is required" >&2
    exit 2
fi
if [[ -z "$output" ]]; then
    echo "usage: ADB_SERIAL=... DURATION_SECONDS=60 $0 OUTPUT_FILE" >&2
    exit 2
fi
if ! [[ "$duration" =~ ^[0-9]+$ ]] || (( duration < 5 )); then
    echo "DURATION_SECONDS must be an integer >= 5" >&2
    exit 2
fi

mkdir -p "$(dirname "$output")"
adb -s "$serial" logcat -c

timeout --signal=INT "$((duration + 5))" adb -s "$serial" logcat -v threadtime -s S3BENCH:I S3PERF:I >"$output" &
logcat_pid=$!
sleep 1
adb -s "$serial" shell am broadcast -a com.zenithblue.sambas3.DEBUG_BENCH_START >/dev/null

sleep "$duration"
adb -s "$serial" shell am broadcast -a com.zenithblue.sambas3.DEBUG_BENCH_STOP >/dev/null || true
wait "$logcat_pid" || true

if ! rg -q 'S3BENCH.*state=ready' "$output"; then
    echo "No ready S3BENCH samples captured; is a debug GTA session running?" >&2
    exit 1
fi
rg 'S3BENCH' "$output"
