#!/usr/bin/env python3
"""
monitor-gameplay-session.py — Real-time interactive session monitor and failure capture.

Watches a live gameplay session on device (e.g. OnePlus 13R), tracks frame presentation,
and upon process termination or crash, immediately captures the full failure packet:
- Streamed host logcat
- Dumpsys ApplicationExitInfo correlated by exact PID
- Tombstones and thread stacks
- Rotated emulator logs (rpcsx_backend.log, rpcsx_vulkan.log, rpcsx_app.log)
- Crash classification via classify-crash.py
- Frame event analysis via analyze-frame-events.py
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


def run_cmd(cmd: list[str], check: bool = True, capture_output: bool = True, text: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=check, capture_output=capture_output, text=text)


def get_pid(serial: str, pkg: str = "com.zenithblue.sambas3") -> Optional[int]:
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


def main() -> int:
    parser = argparse.ArgumentParser(description="Monitor live SambaS3 gameplay session and capture crash evidence.")
    parser.add_argument("--serial", "-s", default="d30a1726", help="Target device serial (default: d30a1726)")
    parser.add_argument("--game", default="direct_iso/BCUS98111", help="Game path to launch")
    parser.add_argument("--launch", action="store_true", default=True, help="Launch game via scripts/debug-launch-game.sh")
    parser.add_argument("--no-launch", action="store_false", dest="launch", help="Do not launch game; attach to existing session")
    parser.add_argument("--outdir", default=None, help="Output evidence directory")
    parser.add_argument("--timeout", type=int, default=1800, help="Max session duration in seconds (default: 30 minutes)")
    args = parser.parse_args()

    serial = args.serial
    repo_root = Path(__file__).resolve().parent.parent.parent

    # Check device connectivity
    state_res = subprocess.run(["adb", "-s", serial, "get-state"], capture_output=True, text=True)
    if state_res.returncode != 0 or state_res.stdout.strip() != "device":
        print(f"[ERROR] Device {serial} is not connected or unauthorized: {state_res.stderr.strip()}", file=sys.stderr)
        return 1

    timestamp_str = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    run_id = f"cr02-interactive-{timestamp_str}"
    outdir = Path(args.outdir) if args.outdir else repo_root / "docs" / "benchmarks" / f"evidence-{run_id}"
    outdir.mkdir(parents=True, exist_ok=True)

    print("=" * 80)
    print(f" SambaS3 Interactive Session Monitor — Crash & Exit Capture")
    print(f" Target Device: {serial}")
    print(f" Game:          {args.game}")
    print(f" Output Dir:    {outdir}")
    print("=" * 80)

    # Pre-clear logcat
    subprocess.run(["adb", "-s", serial, "logcat", "-c"], capture_output=True)

    # Launch game if requested
    if args.launch:
        print("[*] Launching game via scripts/debug-launch-game.sh...")
        launch_script = repo_root / "scripts" / "debug-launch-game.sh"
        launch_res = subprocess.run([str(launch_script), serial, args.game], capture_output=True, text=True)
        print(launch_res.stdout)
        if launch_res.returncode != 0:
            print(f"[!] Launch failed (exit {launch_res.returncode}): {launch_res.stderr}", file=sys.stderr)
            return 1
        print("[+] Game launch accepted and RPCSXActivity focused.")

    # Detect PID
    time.sleep(1.0)
    pid = get_pid(serial)
    if not pid:
        # Retry up to 10 seconds for PID to appear
        for _ in range(10):
            time.sleep(1.0)
            pid = get_pid(serial)
            if pid:
                break

    if not pid:
        print("[ERROR] Could not detect running PID for com.zenithblue.sambas3.", file=sys.stderr)
        return 1

    start_wall = datetime.now(timezone.utc).isoformat()
    start_time = time.monotonic()
    print(f"[+] Detected active emulator PID: {pid} (Start: {start_wall})")

    # Start host-side logcat streaming to file
    streamed_log_file = outdir / "logcat-streamed.log"
    logcat_proc = subprocess.Popen(
        ["adb", "-s", serial, "logcat", "-v", "epoch", "-v", "threadtime"],
        stdout=open(streamed_log_file, "w"),
        stderr=subprocess.DEVNULL,
    )
    print(f"[+] Host-side logcat streaming active -> {streamed_log_file}")
    print("[*] User is playing. Monitoring in real time for frame events, checkpoint progress, and crashes...\n")

    last_presented = None
    last_fps = None
    last_frametime = None
    last_status_print = start_time
    fatal_detected = False
    stop_reason = "PROCESS_DIED"

    re_crosscheck = re.compile(r"crosscheck\s+source=\w+\s+presented=(\d+)\s+interval_fps=([\d.]+)\s+latest_ms=([\d.]+)")
    re_s3perf = re.compile(r"S3PERF.*presented=(\d+)")
    re_fatal = re.compile(r"(Fatal signal|SIGSEGV|SIGBUS|SIGABRT|scudo ERROR|sys_crashdump|Dead FIFO)")

    try:
        while time.monotonic() - start_time < args.timeout:
            time.sleep(1.0)
            now = time.monotonic()
            elapsed = int(now - start_time)

            # Check if PID is still alive
            if not is_pid_alive(serial, pid):
                print(f"\n[!] Process PID {pid} has EXITED / TERMINATED at T+{elapsed}s!")
                break

            # Poll recent logcat buffer for frame updates & fatal events
            logcat_recent = subprocess.run(
                ["adb", "-s", serial, "logcat", "-d", "-t", "50", "-v", "brief"],
                capture_output=True,
                text=True,
            ).stdout

            # Check for crash markers
            fatal_match = re_fatal.search(logcat_recent)
            if fatal_match:
                fatal_detected = True
                stop_reason = f"FATAL_SIGNAL: {fatal_match.group(1)}"
                print(f"\n[!] Fatal crash signature detected in logcat: {fatal_match.group(1)} at T+{elapsed}s!")
                # Give process 1 second to write tombstone/flush before breaking
                time.sleep(1.5)
                break

            # Parse frame progress
            for line in logcat_recent.splitlines():
                m = re_crosscheck.search(line)
                if m:
                    last_presented = int(m.group(1))
                    last_fps = float(m.group(2))
                    last_frametime = float(m.group(3))
                elif not last_presented:
                    m2 = re_s3perf.search(line)
                    if m2:
                        last_presented = int(m2.group(1))

            # Periodic status print every 3 seconds
            if now - last_status_print >= 3.0:
                frame_info = f"presented={last_presented}" if last_presented is not None else "booting/initializing"
                fps_info = f" | FPS={last_fps:.2f} (latency={last_frametime:.1f}ms)" if last_fps is not None else ""
                print(f"[{elapsed:03d}s | PID {pid}] {frame_info}{fps_info}")
                last_status_print = now

    except KeyboardInterrupt:
        print("\n[*] Monitoring interrupted by user (Ctrl+C). Initiating capture...")
        stop_reason = "USER_INTERRUPT"
    finally:
        # Terminate logcat streaming
        try:
            logcat_proc.terminate()
            logcat_proc.wait(timeout=2.0)
        except Exception:
            logcat_proc.kill()

    end_wall = datetime.now(timezone.utc).isoformat()
    duration_s = time.monotonic() - start_time
    print(f"\n[*] Session ended at {end_wall} (Duration: {duration_s:.1f}s).")
    print("[*] Collecting complete failure packet and diagnostics...")

    # 1. Collect full run evidence
    collect_script = repo_root / "scripts" / "perf" / "collect-run-evidence.sh"
    collect_res = subprocess.run(
        [str(collect_script), "--run-id", run_id, "--scene", "gow3-gaia-interactive", serial, str(outdir)],
        capture_output=True,
        text=True,
    )
    print(collect_res.stdout)

    # 2. Run offline crash classifier
    classifier_script = repo_root / "scripts" / "perf" / "classify-crash.py"
    classify_cmd = [
        sys.executable,
        str(classifier_script),
        str(outdir),
        "--pid", str(pid),
        "--output-json", str(outdir / "crash-diagnosis.json"),
        "-v",
    ]
    classify_res = subprocess.run(classify_cmd, capture_output=True, text=True)
    print(classify_res.stdout)
    if classify_res.stderr:
        print(classify_res.stderr, file=sys.stderr)

    # 3. Run frame event analyzer if logcat-sambas3.txt exists
    samba_log = outdir / "logcat-sambas3.txt"
    if samba_log.exists():
        analyzer_script = repo_root / "scripts" / "perf" / "analyze-frame-events.py"
        analyze_cmd = [
            sys.executable,
            str(analyzer_script),
            str(samba_log),
            "--json",
            "--output-json", str(outdir / "frame-analysis.json"),
        ]
        analyze_res = subprocess.run(analyze_cmd, capture_output=True, text=True)
        if analyze_res.returncode == 0:
            print("[+] Frame event analysis completed successfully.")

    # 4. Extract crash backtrace / fault details
    exit_info_file = outdir / "exit-info.txt"
    backend_log_file = outdir / "rpcsx_backend.log"
    logcat_tail_file = outdir / "logcat-tail.log"

    fault_summary = []
    if exit_info_file.exists():
        text = exit_info_file.read_text()
        for block in text.split("ApplicationExitInfo"):
            if f"pid={pid}" in block:
                fault_summary.append("--- ApplicationExitInfo ---")
                for line in block.splitlines()[:15]:
                    fault_summary.append(line.strip())
                break

    # Search for crash backtrace in backend log or logcat tail
    trace_lines = []
    for f in [backend_log_file, logcat_tail_file, streamed_log_file]:
        if f and f.exists():
            with open(f, "r", errors="replace") as fh:
                content = fh.read()
                matches = re.findall(r"(Fatal signal \d+.*?(?:\n\s+#\d+.*)+)", content, re.MULTILINE)
                if matches:
                    trace_lines.extend(matches[-1].splitlines())
                    break
                # Scudo error
                scudo_matches = re.findall(r"(scudo ERROR:.*)", content)
                if scudo_matches:
                    trace_lines.extend(scudo_matches[-3:])
                    break
                # Dead FIFO
                fifo_matches = re.findall(r"(Dead FIFO commands queue state.*)", content)
                if fifo_matches:
                    trace_lines.extend(fifo_matches[-1:])
                    break

    # Build summary
    summary_md = outdir / "session-summary.md"
    with open(summary_md, "w") as fh:
        fh.write(f"# Interactive Session Summary — {run_id}\n\n")
        fh.write(f"- **Target Device:** {serial}\n")
        fh.write(f"- **Process PID:** {pid}\n")
        fh.write(f"- **Session Duration:** {duration_s:.1f}s\n")
        fh.write(f"- **Last Qualified Frames Presented:** {last_presented}\n")
        fh.write(f"- **Last Interactive FPS:** {last_fps}\n")
        fh.write(f"- **Exit Trigger:** {stop_reason}\n\n")
        if fault_summary:
            fh.write("## Exit Info\n```\n" + "\n".join(fault_summary) + "\n```\n\n")
        if trace_lines:
            fh.write("## Crash Stack Trace / Fault Signature\n```\n" + "\n".join(trace_lines) + "\n```\n\n")

    print("\n" + "=" * 80)
    print(" CRASH & EXIT CAPTURE COMPLETE")
    print(f" Evidence Directory: {outdir}")
    print(f" Last Presented Frame: {last_presented}")
    print(f" Last Observed FPS:    {last_fps}")
    print(f" Stop Reason:          {stop_reason}")
    if fault_summary:
        print("\nApplicationExitInfo:")
        for l in fault_summary:
            print("  " + l)
    if trace_lines:
        print("\nFault Stack Trace:")
        for l in trace_lines[:15]:
            print("  " + l)
    print("=" * 80)

    return 0


if __name__ == "__main__":
    sys.exit(main())
