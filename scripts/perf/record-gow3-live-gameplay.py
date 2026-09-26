#!/usr/bin/env python3
"""
record-gow3-live-gameplay.py — Continuous gameplay monitoring and telemetry collector.
Records screenshots, thermal telemetry, and streams logcat while the user plays God of War III.
"""

import os
import sys
import time
import subprocess
from datetime import datetime
from pathlib import Path

SERIAL = "d30a1726"
OUTDIR = Path("docs/benchmarks/evidence-candidate-perf-v2-gow3")
OUTDIR.mkdir(parents=True, exist_ok=True)

def run_adb(cmd, capture=True):
    full_cmd = ["adb", "-s", SERIAL] + cmd
    return subprocess.run(full_cmd, capture_output=capture, text=True)

def main():
    print(f"[*] Starting live gameplay telemetry to {OUTDIR}...")
    
    # Start streaming logcat in background
    logcat_proc_file = open(OUTDIR / "logcat-process.log", "w")
    logcat_proc = subprocess.Popen(
        ["adb", "-s", SERIAL, "logcat", "-v", "threadtime", "*:V"],
        stdout=logcat_proc_file,
        stderr=subprocess.DEVNULL
    )

    logcat_sambas3_file = open(OUTDIR / "logcat-sambas3.txt", "w")
    logcat_s3 = subprocess.Popen(
        ["adb", "-s", SERIAL, "logcat", "-v", "threadtime", "-s", "S3PERF", "S3VKSYNC", "S3BOOT", "RPCS3", "RPCSX", "RPCSX-UI", "S3PAD"],
        stdout=logcat_sambas3_file,
        stderr=subprocess.DEVNULL
    )

    thermal_log = open(OUTDIR / "thermal-telemetry.csv", "w")
    thermal_log.write("timestamp,battery_temp_c,thermal_status,cpu_freqs_khz,overlay_fps,overlay_ms\n")
    thermal_log.flush()

    shot_idx = 2
    start_time = time.time()
    max_duration = 180  # 3 minutes maximum recording window

    try:
        while time.time() - start_time < max_duration:
            # Check if RPCSXActivity is still active
            focus = run_adb(["shell", "dumpsys", "window"]).stdout
            if "RPCSXActivity" not in focus:
                print("[*] RPCSXActivity is no longer focused. Exiting loop.")
                break

            now_str = datetime.now().strftime("%Y%m%d_%H%M%S")
            shot_name = f"gameplay_{shot_idx:02d}_{now_str}.png"
            shot_path = OUTDIR / shot_name
            
            print(f"[{time.strftime('%H:%M:%S')}] Capturing screenshot {shot_name}...")
            run_adb(["exec-out", "screencap", "-p"], capture=False) # wait, direct to file
            with open(shot_path, "wb") as f:
                subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"], stdout=f)
            
            # Capture thermals
            therm = run_adb(["shell", "dumpsys", "thermalservice"]).stdout
            bat = run_adb(["shell", "dumpsys", "battery"]).stdout
            
            bat_temp = "N/A"
            for line in bat.splitlines():
                if "temperature:" in line:
                    try:
                        bat_temp = f"{float(line.split(':')[1].strip()) / 10.0:.1f}"
                    except Exception:
                        pass

            therm_status = "N/A"
            for line in therm.splitlines():
                if "Thermal Status:" in line:
                    therm_status = line.split(":")[-1].strip()

            # CPU frequencies
            freqs = run_adb(["shell", "cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null"]).stdout.split()
            freq_str = ";".join(freqs) if freqs else "N/A"

            # Overlay FPS readout from recent logcat
            recent_perf = run_adb(["logcat", "-d", "-s", "S3PERF:D", "-t", "5"]).stdout
            fps_readout = "N/A"
            ms_readout = "N/A"
            for pline in reversed(recent_perf.splitlines()):
                if "crosscheck" in pline and "json_fps=" in pline:
                    parts = pline.split()
                    for p in parts:
                        if p.startswith("json_fps="):
                            fps_readout = p.split("=")[1]
                        elif p.startswith("json_frametime_ms="):
                            ms_readout = p.split("=")[1]
                    break
                elif "crosscheck" in pline and "ui_snapshot_fps=" in pline:
                    parts = pline.split()
                    for p in parts:
                        if p.startswith("ui_snapshot_fps="):
                            fps_readout = p.split("=")[1]
                        elif p.startswith("ui_snapshot_frametime_ms="):
                            ms_readout = p.split("=")[1]
                    break

            thermal_log.write(f"{now_str},{bat_temp},{therm_status},{freq_str},{fps_readout},{ms_readout}\n")
            thermal_log.flush()
            print(f"    Thermals: Bat={bat_temp}°C Status={therm_status} FPS={fps_readout} ({ms_readout}ms)")

            shot_idx += 1
            # Sleep 15 seconds before next sample
            time.sleep(15)

    except KeyboardInterrupt:
        print("[*] Monitoring interrupted by user.")
    finally:
        logcat_proc.terminate()
        logcat_s3.terminate()
        logcat_proc_file.close()
        logcat_sambas3_file.close()
        thermal_log.close()
        print("[*] Monitoring completed.")

if __name__ == "__main__":
    main()
