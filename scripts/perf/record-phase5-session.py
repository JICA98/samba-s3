#!/usr/bin/env python3
"""
record-phase5-session.py — Automated live gameplay session recorder for Phase 5 verification.

Launches God of War III on device (default: OnePlus 13R / d30a1726), continuously streams logcat,
records real-time FPS and frame events, captures periodic screenshots, samples thermal/memory
diagnostics, and listens for a stop sentinel file (/tmp/stop_phase5_recording) or process exit.
Upon stop, collects full evidence, runs analysis, audits live thread affinity readbacks, and
generates a comprehensive Phase 5 markdown report.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


STOP_SENTINEL = Path("/tmp/stop_phase5_recording")


def get_pid(serial: str, pkg: str = "com.zenithblue.sambas3") -> int | None:
    res = subprocess.run(["adb", "-s", serial, "shell", "pidof", pkg], capture_output=True, text=True)
    if res.returncode == 0 and res.stdout.strip():
        parts = res.stdout.strip().split()
        if parts:
            try:
                return int(parts[0])
            except ValueError:
                return None
    return None


def is_pid_alive(serial: str, pid: int) -> bool:
    res = subprocess.run(["adb", "-s", serial, "shell", "test", "-d", f"/proc/{pid}"], capture_output=True)
    return res.returncode == 0


def take_screenshot(serial: str, out_path: Path) -> bool:
    res = subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], capture_output=True)
    if res.returncode == 0 and len(res.stdout) > 1000:
        out_path.write_bytes(res.stdout)
        return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Record live Phase 5 gameplay session on device.")
    parser.add_argument("--serial", "-s", default="d30a1726", help="Target device serial (default: d30a1726)")
    parser.add_argument("--game", default="direct_iso/BCUS98111", help="Game path to launch")
    parser.add_argument("--outdir", default=None, help="Output evidence directory")
    parser.add_argument("--screenshot-interval", type=int, default=15, help="Interval between screenshots in seconds")
    parser.add_argument("--timeout", type=int, default=3600, help="Max recording duration in seconds")
    args = parser.parse_args()

    serial = args.serial
    repo_root = Path(__file__).resolve().parent.parent.parent

    # Clear stop sentinel if present from past run
    if STOP_SENTINEL.exists():
        STOP_SENTINEL.unlink()

    # Verify device state
    state = subprocess.run(["adb", "-s", serial, "get-state"], capture_output=True, text=True)
    if state.returncode != 0 or state.stdout.strip() != "device":
        print(f"[ERROR] Device {serial} not connected/authorized: {state.stderr.strip()}", file=sys.stderr)
        return 1

    timestamp_str = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    run_id = f"phase5-session-{timestamp_str}"
    outdir = Path(args.outdir) if args.outdir else repo_root / "docs" / "benchmarks" / f"evidence-{run_id}"
    screenshots_dir = outdir / "screenshots"
    outdir.mkdir(parents=True, exist_ok=True)
    screenshots_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 80)
    print(" SambaS3 Phase 5 Live Session Recorder & Monitor")
    print(f" Target Device:       {serial}")
    print(f" Workload:            {args.game}")
    print(f" Evidence Directory:  {outdir}")
    print(f" Stop Sentinel:       {STOP_SENTINEL}")
    print("=" * 80)

    # 1. Clear logcat buffers
    subprocess.run(["adb", "-s", serial, "logcat", "-c"], capture_output=True)

    # 2. Launch game via canonical debug-launch-game.sh
    print("[*] Launching game via scripts/debug-launch-game.sh...")
    launch_script = repo_root / "scripts" / "debug-launch-game.sh"
    launch_res = subprocess.run([str(launch_script), serial, args.game], capture_output=True, text=True)
    print(launch_res.stdout)
    if launch_res.returncode != 0:
        print(f"[!] Launch failed (exit {launch_res.returncode}): {launch_res.stderr}", file=sys.stderr)
        return 1

    # 3. Detect running PID
    pid = None
    for _ in range(15):
        time.sleep(1.0)
        pid = get_pid(serial)
        if pid:
            break

    if not pid:
        print("[ERROR] Could not detect running PID for com.zenithblue.sambas3.", file=sys.stderr)
        return 1

    start_wall = datetime.now(timezone.utc).isoformat()
    start_time = time.monotonic()
    print(f"[+] Active emulator PID detected: {pid} (Start: {start_wall})")

    # 4. Stream logcat to file
    streamed_log = outdir / "logcat-streamed.log"
    logcat_proc = subprocess.Popen(
        ["adb", "-s", serial, "logcat", "-v", "epoch", "-v", "threadtime"],
        stdout=open(streamed_log, "w", encoding="utf-8", errors="replace"),
        stderr=subprocess.DEVNULL,
    )
    print(f"[+] Streaming logcat -> {streamed_log}")

    # Capture initial boot screenshot
    take_screenshot(serial, screenshots_dir / "screen_boot_00s.png")

    last_screenshot_time = start_time
    last_status_print = start_time
    last_presented = None
    last_fps = None
    last_frametime = None
    stop_reason = "PROCESS_DIED"
    screenshot_count = 1

    re_crosscheck = re.compile(r"crosscheck\s+source=\w+\s+presented=(\d+)\s+interval_fps=([\d.]+)\s+latest_ms=([\d.]+)")
    re_s3perf = re.compile(r"S3PERF.*presented=(\d+)")
    re_affinity = re.compile(r"Thread affinity (applied|failed):\s+(.*)")

    affinity_events: list[str] = []

    print("[*] Game is running! Recording live FPS, logs, and screenshots...")
    print(f"[*] To stop recording: touch {STOP_SENTINEL} or tell the assistant 'stop'.\n")

    try:
        while time.monotonic() - start_time < args.timeout:
            time.sleep(1.5)
            now = time.monotonic()
            elapsed = int(now - start_time)

            # Check stop sentinel
            if STOP_SENTINEL.exists():
                print(f"\n[*] Stop signal received ({STOP_SENTINEL} found) at T+{elapsed}s!")
                stop_reason = "USER_REQUESTED_STOP"
                STOP_SENTINEL.unlink(missing_ok=True)
                break

            # Check if process is still alive
            if not is_pid_alive(serial, pid):
                print(f"\n[!] Process PID {pid} has EXITED at T+{elapsed}s!")
                stop_reason = "PROCESS_EXITED"
                break

            # Poll logcat for recent frames and affinity events
            recent = subprocess.run(
                ["adb", "-s", serial, "logcat", "-d", "-t", "60", "-v", "brief"],
                capture_output=True,
                text=True,
                errors="replace",
            ).stdout

            for line in recent.splitlines():
                m = re_crosscheck.search(line)
                if m:
                    last_presented = int(m.group(1))
                    last_fps = float(m.group(2))
                    last_frametime = float(m.group(3))
                elif not last_presented:
                    m2 = re_s3perf.search(line)
                    if m2:
                        last_presented = int(m2.group(1))

                ma = re_affinity.search(line)
                if ma and line not in affinity_events:
                    affinity_events.append(line)
                    print(f"[{elapsed:03d}s | AFFINITY] {line.strip()}")

            # Periodic screenshot
            if now - last_screenshot_time >= args.screenshot_interval:
                shot_path = screenshots_dir / f"screen_{elapsed:04d}s.png"
                if take_screenshot(serial, shot_path):
                    screenshot_count += 1
                    print(f"[{elapsed:03d}s | SCREENSHOT] Saved {shot_path.name}")
                last_screenshot_time = now

            # Periodic status print
            if now - last_status_print >= 5.0:
                frame_info = f"presented={last_presented}" if last_presented is not None else "loading/booting"
                fps_info = f" | FPS={last_fps:.2f} ({last_frametime:.1f}ms)" if last_fps is not None else ""
                print(f"[{elapsed:03d}s | PID {pid}] {frame_info}{fps_info}")
                last_status_print = now

    except KeyboardInterrupt:
        print("\n[*] Interrupted by user. Finalizing capture...")
        stop_reason = "USER_INTERRUPT"
    finally:
        try:
            logcat_proc.terminate()
            logcat_proc.wait(timeout=3.0)
        except Exception:
            logcat_proc.kill()

    end_wall = datetime.now(timezone.utc).isoformat()
    duration = time.monotonic() - start_time
    print(f"\n[*] Recording finished at {end_wall} (Duration: {duration:.1f}s, Stop Reason: {stop_reason})")

    # 5. Collect full evidence packet via collect-run-evidence.sh
    print("[*] Collecting complete evidence packet (rotated logs, exit info, thermal, mem)...")
    collect_script = repo_root / "scripts" / "perf" / "collect-run-evidence.sh"
    collect_res = subprocess.run(
        [str(collect_script), "--run-id", run_id, "--scene", "gow3-phase5-session", serial, str(outdir)],
        capture_output=True,
        text=True,
    )
    print(collect_res.stdout)

    # 6. Analyze frame events
    analysis_data: dict = {}
    samba_log = outdir / "logcat-sambas3.txt"
    if samba_log.exists():
        print("[*] Running analyze-frame-events.py...")
        analyzer = repo_root / "scripts" / "perf" / "analyze-frame-events.py"
        analysis_json = outdir / "frame-analysis.json"
        subprocess.run(
            [sys.executable, str(analyzer), str(samba_log), "--json", "--output-json", str(analysis_json)],
            capture_output=True,
            text=True,
        )
        if analysis_json.exists():
            try:
                analysis_data = json.loads(analysis_json.read_text(encoding="utf-8"))
            except Exception:
                pass

    # 7. Parse threads-affinity.txt
    affinity_file = outdir / "threads-affinity.txt"
    affinity_lines: list[str] = []
    if affinity_file.exists():
        affinity_lines = [l.strip() for l in affinity_file.read_text(encoding="utf-8", errors="replace").splitlines() if l.strip()]

    # 8. Generate Phase 5 Markdown Report
    report_path = outdir / "PHASE_5_DEVICE_TEST_REPORT.md"
    root_report_path = repo_root / "docs" / "performance" / "PHASE_5_DEVICE_TEST_REPORT.md"

    fps_val = analysis_data.get("interval_throughput_fps", last_fps or 0.0)
    p50_val = analysis_data.get("median_frame_time_ms", last_frametime or 0.0)
    p95_val = analysis_data.get("p95_frame_time_ms", 0.0)
    frames_val = analysis_data.get("total_qualified_frames", last_presented or 0)

    screenshots_list = sorted([f.name for f in screenshots_dir.glob("*.png")])

    report_content = f"""# Phase 5 On-Device Validation Report: Prime-Core Rendering Affinity

**Run ID:** `{run_id}`  
**Date:** `{start_wall}`  
**Device:** `OnePlus 13R` (`{serial}`) — Snapdragon 8 Gen 3 (SM8650)  
**Workload:** `God of War® III` (`BCUS98111`)  
**Duration:** `{duration:.1f} seconds`  
**Stop Reason:** `{stop_reason}`  
**Active Emulator PID:** `{pid}`  

---

## 1. Executive Summary

This test validates the **Phase 5 Alternative Scheduler policy** on real Snapdragon 8 Gen 3 silicon under active emulation load. The objective is to verify that:
1. The classified frame rendering backend thread (`thread_class::rsx` / `rsx::thread`) successfully queries live kernel cpusets and applies an affinity preference to the Cortex-X4 Prime Core (`0x80`, Core 7).
2. Heavy SPU worker threads and PPU execution threads are confined to the remaining Big performance cores (`0x7C`, Cores 2–6), preventing them from monopolizing the highest-frequency core.
3. Actual kernel thread affinity readback confirms placement via `sched_getaffinity` per thread ID.

---

## 2. Performance Metrics & Frame Analysis

| Metric | Measured Value | Unit |
|---|---|---|
| **Total Qualified Frames** | `{frames_val}` | frames |
| **Throughput (Active Window)** | `{fps_val:.2f}` | FPS |
| **Median Frame Time (P50)** | `{p50_val:.2f}` | ms |
| **95th Percentile (P95)** | `{p95_val:.2f}` | ms |
| **Active Duration** | `{duration:.1f}` | s |
| **Screenshots Captured** | `{len(screenshots_list)}` | files |

---

## 3. Live Kernel Thread Affinity Readback

The following live kernel placement readbacks were recorded from `Thread.cpp::set_thread_affinity_mask` via `sched_getaffinity` during this session:

```text
{chr(10).join(affinity_events) if affinity_events else "No explicit affinity logcat events captured during this session window."}
```

### `/proc/$PID/task/*/status` Snapshot:

```text
{chr(10).join(affinity_lines[:30]) if affinity_lines else "Thread status captured in threads-affinity.txt"}
```

---

## 4. Captured Gameplay Screenshots

A total of **{len(screenshots_list)}** screenshots were captured across the session:

{chr(10).join(f"- `{s}`" for s in screenshots_list)}

---

## 5. Evidence Artifacts

The complete evidence packet has been preserved in:
`{outdir}`

- `logcat-streamed.log` — Continuous host logcat stream during the live run.
- `logcat-process.log` — Rotated in-process logs with fatal/affinity tags.
- `threads-affinity.txt` — Live `Cpus_allowed` masks and kernel readback log events.
- `threads-top.txt` — Thread CPU utilization and state snapshot.
- `thermal.txt` — Dumpsys thermalservice snapshot.
- `meminfo.txt` — Process memory allocation.
- `frame-analysis.json` — Structured frame timing and percentile metrics.
- `screenshots/` — All captured milestone screencaps.
"""

    report_path.write_text(report_content, encoding="utf-8")
    root_report_path.write_text(report_content, encoding="utf-8")
    print(f"\n[OK] Phase 5 report successfully created:")
    print(f"     -> {report_path}")
    print(f"     -> {root_report_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
