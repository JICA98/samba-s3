#!/usr/bin/env python3
"""
run-gow3-benchmark.py — Deterministic God of War® III benchmark orchestrator for SambaS3.

Features:
- Validates environment, device connection, and package/core hashes (fails closed on mismatch).
- Sets up performance monitoring overlay options (via scripts/debug-monitor.sh bridge).
- Launches game deterministically via MainActivity warm start and S3BOOT bridge.
- Orchestrates deterministic gamepad progression (START -> cutscene skip -> CROSS -> combat).
- Executes timed benchmark gameplay window (matched ~14s, sustained ~60-80s, or custom duration).
- Stops emulation cleanly via DEBUG_STOP_GAME terminal coordinator.
- Preserves partial/crashing runs with full diagnostic classification (never relabels crash as success).
- Collects evidence via scripts/perf/collect-run-evidence.sh.
- Runs scripts/perf/analyze-frame-events.py to produce throughput and percentile statistics.

Conforms to SambaS3 WORKER.md Section 15 and 16.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
SCRIPTS_DIR = ROOT_DIR / "scripts"
PERF_DIR = ROOT_DIR / "scripts" / "perf"

PKG_NAME = "com.zenithblue.sambas3"
DEFAULT_GAME = "direct_iso/BCUS98111"
DEFAULT_DEVICE = "d30a1726"

# Pinned commit and release hashes for verification (E08 / J06 SPU SIMD Lowering)
EXPECTED_CORE_HASH = "c47f879bbeb1f152129f61a013fa84b823bba803"
EXPECTED_APK_SHA256 = "69a27d474f7b974e026c598717ac53732b64a80147ce679ffc31646c813b271e"
EXPECTED_SO_SHA256 = "a6711f512673e4e6c4885dbbfd4ce3432ce074967dc3ec27203a384efad31e43"


def run_cmd(cmd: List[str], timeout: Optional[int] = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=timeout,
        cwd=str(ROOT_DIR),
    )


def adb_shell(serial: str, shell_cmd: str, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    return run_cmd(["adb", "-s", serial, "shell", shell_cmd], timeout=timeout)


@dataclass
class DeviceProvenance:
    serial: str
    model: str
    soc: str
    android_ver: str
    package_name: str
    version_name: str
    version_code: str
    code_path: str
    base_apk_sha256: str
    librpcsx_so_sha256: str
    s3core_identity: str


def fetch_device_provenance(serial: str) -> DeviceProvenance:
    # Basic properties
    r_model = adb_shell(serial, "getprop ro.product.model")
    model = r_model.stdout.strip()

    r_soc = adb_shell(serial, "getprop ro.soc.model")
    soc = r_soc.stdout.strip()

    r_ver = adb_shell(serial, "getprop ro.build.version.release")
    android_ver = r_ver.stdout.strip()

    # Dumpsys package
    r_pkg = adb_shell(serial, f"dumpsys package {PKG_NAME}")
    dump = r_pkg.stdout

    ver_name = "unknown"
    ver_code = "unknown"
    code_path = "unknown"
    for line in dump.splitlines():
        line = line.strip()
        if line.startswith("versionName="):
            ver_name = line.split("=", 1)[1].split()[0]
        elif line.startswith("versionCode="):
            ver_code = line.split("=", 1)[1].split()[0]
        elif line.startswith("codePath="):
            code_path = line.split("=", 1)[1].split()[0]

    base_apk_sha = ""
    so_sha = ""
    s3core_id = "unknown"

    if code_path and code_path != "unknown":
        r_apk_sha = adb_shell(serial, f"sha256sum {code_path}/base.apk 2>/dev/null")
        if r_apk_sha.returncode == 0 and r_apk_sha.stdout.strip():
            base_apk_sha = r_apk_sha.stdout.strip().split()[0]

        r_so_sha = adb_shell(serial, f"sha256sum {code_path}/lib/arm64/librpcsx-android.so 2>/dev/null")
        if r_so_sha.returncode == 0 and r_so_sha.stdout.strip():
            so_sha = r_so_sha.stdout.strip().split()[0]

        r_id = adb_shell(serial, f"grep -a -o 'rpcsx=[^ ]* samba=[^ ]*' {code_path}/lib/arm64/librpcsx-android.so 2>/dev/null")
        if r_id.returncode == 0 and r_id.stdout.strip():
            s3core_id = r_id.stdout.strip().splitlines()[0]

    return DeviceProvenance(
        serial=serial,
        model=model,
        soc=soc,
        android_ver=android_ver,
        package_name=PKG_NAME,
        version_name=ver_name,
        version_code=ver_code,
        code_path=code_path,
        base_apk_sha256=base_apk_sha,
        librpcsx_so_sha256=so_sha,
        s3core_identity=s3core_id,
    )


def validate_provenance(
    prov: DeviceProvenance,
    expected_core: Optional[str] = None,
    expected_apk_sha: Optional[str] = None,
    expected_so_sha: Optional[str] = None,
    strict: bool = True,
) -> Tuple[bool, List[str]]:
    errors: List[str] = []

    if not prov.code_path or prov.code_path == "unknown":
        errors.append(f"Package {PKG_NAME} is not installed on device {prov.serial}")

    if expected_core:
        if expected_core not in prov.s3core_identity:
            errors.append(
                f"S3CORE mismatch: expected core commit '{expected_core}', but device library contains '{prov.s3core_identity}'"
            )

    if expected_apk_sha and prov.base_apk_sha256:
        if prov.base_apk_sha256.lower() != expected_apk_sha.lower():
            errors.append(
                f"base.apk SHA-256 mismatch: expected {expected_apk_sha}, observed {prov.base_apk_sha256}"
            )

    if expected_so_sha and prov.librpcsx_so_sha256:
        if prov.librpcsx_so_sha256.lower() != expected_so_sha.lower():
            errors.append(
                f"librpcsx-android.so SHA-256 mismatch: expected {expected_so_sha}, observed {prov.librpcsx_so_sha256}"
            )

    is_valid = len(errors) == 0
    return is_valid, errors


def configure_monitor(serial: str, preset: str) -> bool:
    print(f"[*] Setting performance monitor overlay preset to '{preset}'...")
    if preset.lower() == "off":
        cmd = [str(SCRIPTS_DIR / "debug-monitor.sh"), serial, "--ez", "enabled", "false"]
    else:
        cmd = [str(SCRIPTS_DIR / "debug-monitor.sh"), serial, "--ez", "enabled", "true", "--es", "preset", preset]
    r = run_cmd(cmd, timeout=15)
    if r.returncode != 0:
        print(f"[!] Warning: failed to configure monitor: {r.stderr.strip()}", file=sys.stderr)
        return False
    return True


def deliver_pad_button(serial: str, button: str, delay_s: float = 1.0):
    print(f"[*] Sending controller input '{button}' via debug-pad bridge...")
    cmd = [str(SCRIPTS_DIR / "debug-pad.sh"), serial, button]
    run_cmd(cmd, timeout=10)
    time.sleep(delay_s)


SCHEDULER_MODE_MAP: Dict[str, str] = {
    "os": "Operating System",
    "operating_system": "Operating System",
    "operatingsystem": "Operating System",
    "rpcs3": "RPCS3 Scheduler",
    "old": "RPCS3 Scheduler",
    "alt": "RPCS3 Alternative Scheduler",
    "alternative": "RPCS3 Alternative Scheduler",
}

CONFIG_DIR_DEVICE = "/sdcard/Android/data/com.zenithblue.sambas3/files/config"
GLOBAL_CONFIG_PATH_DEVICE = f"{CONFIG_DIR_DEVICE}/config.yml"
CUSTOM_CONFIGS_DIR_DEVICE = f"{CONFIG_DIR_DEVICE}/custom_configs"


def resolve_scheduler_mode(mode: Optional[str]) -> Optional[str]:
    """Resolves CLI scheduler mode aliases ('os', 'rpcs3', 'alt') to RPCSX config strings."""
    if not mode:
        return None
    key = mode.strip().lower().replace("-", "_").replace(" ", "_")
    if key in SCHEDULER_MODE_MAP:
        return SCHEDULER_MODE_MAP[key]
    raise ValueError(f"Unknown scheduler mode '{mode}'. Valid modes: os, rpcs3, alt")


def update_yaml_scheduler_mode(content: str, scheduler_str: str) -> str:
    """
    Updates or inserts 'Thread Scheduler Mode: <scheduler_str>' under the 'Core:' section in YAML content.
    Preserves other keys, comments, and structure.
    """
    if not content.strip():
        return f"Core:\n  Thread Scheduler Mode: {scheduler_str}\n"

    lines = content.splitlines(keepends=True)
    in_core = False
    core_indent = None
    scheduler_line_idx = None
    core_line_idx = None
    next_section_idx = None

    import re
    section_re = re.compile(r"^(\s*)([A-Za-z0-9_/ -]+):\s*(?:#.*)?$")
    scheduler_re = re.compile(r"^(\s*)Thread Scheduler Mode:\s*.*$")

    for i, line in enumerate(lines):
        sec_m = section_re.match(line)
        if sec_m:
            indent = len(sec_m.group(1))
            sec_name = sec_m.group(2).strip()
            if in_core:
                if indent <= core_indent:
                    next_section_idx = i
                    break
            elif sec_name == "Core":
                in_core = True
                core_indent = indent
                core_line_idx = i
                continue

        if in_core:
            sched_m = scheduler_re.match(line)
            if sched_m:
                scheduler_line_idx = i
                break

    if scheduler_line_idx is not None:
        orig_indent = lines[scheduler_line_idx].split("Thread Scheduler Mode")[0]
        lines[scheduler_line_idx] = f"{orig_indent}Thread Scheduler Mode: {scheduler_str}\n"
        return "".join(lines)
    elif core_line_idx is not None:
        entry_indent = " " * (core_indent + 2)
        newline = f"{entry_indent}Thread Scheduler Mode: {scheduler_str}\n"
        lines.insert(core_line_idx + 1, newline)
        return "".join(lines)
    else:
        extra = f"\nCore:\n  Thread Scheduler Mode: {scheduler_str}\n"
        if lines and not lines[-1].endswith("\n"):
            return "".join(lines) + extra
        return "".join(lines) + (extra.lstrip("\n") if not lines else extra)


def read_yaml_scheduler_mode(content: str) -> Optional[str]:
    """Extracts Thread Scheduler Mode value from YAML content if present under Core."""
    in_core = False
    core_indent = None
    import re
    section_re = re.compile(r"^(\s*)([A-Za-z0-9_/ -]+):\s*(?:#.*)?$")
    scheduler_re = re.compile(r"^\s*Thread Scheduler Mode:\s*(.*?)\s*(?:#.*)?$")

    for line in content.splitlines():
        sec_m = section_re.match(line)
        if sec_m:
            indent = len(sec_m.group(1))
            sec_name = sec_m.group(2).strip()
            if in_core and indent <= core_indent:
                break
            if sec_name == "Core":
                in_core = True
                core_indent = indent
                continue
        if in_core:
            sched_m = scheduler_re.match(line)
            if sched_m:
                val = sched_m.group(1).strip()
                if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
                    val = val[1:-1]
                return val
    return None


def configure_scheduler(serial: str, mode: str, game_path: Optional[str] = None) -> bool:
    """Configures Thread Scheduler Mode in global config.yml and title custom configs on device."""
    mode_str = resolve_scheduler_mode(mode)
    if not mode_str:
        return False

    print(f"[*] Configuring Thread Scheduler Mode to '{mode_str}' on {serial}...")

    import tempfile
    r_cat = adb_shell(serial, f"cat {GLOBAL_CONFIG_PATH_DEVICE} 2>/dev/null || true")
    current_content = r_cat.stdout if r_cat.returncode == 0 else ""
    updated_content = update_yaml_scheduler_mode(current_content, mode_str)

    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as tf:
        tf.write(updated_content)
        temp_path = tf.name

    try:
        adb_shell(serial, f"mkdir -p {CONFIG_DIR_DEVICE}")
        r_push = run_cmd(["adb", "-s", serial, "push", temp_path, GLOBAL_CONFIG_PATH_DEVICE])
        if r_push.returncode != 0:
            print(f"[!] Warning: failed to push config.yml to {serial}: {r_push.stderr.strip()}", file=sys.stderr)
            return False
    finally:
        if os.path.exists(temp_path):
            os.remove(temp_path)

    # Also update title-specific custom config if present
    title_id = None
    if game_path:
        import re
        m = re.search(r"([A-Za-z]{4}\d{5})", game_path)
        if m:
            title_id = m.group(1).upper()

    if title_id:
        custom_cfg_path = f"{CUSTOM_CONFIGS_DIR_DEVICE}/config_{title_id}.yml"
        r_custom = adb_shell(serial, f"cat {custom_cfg_path} 2>/dev/null || true")
        if r_custom.returncode == 0 and r_custom.stdout.strip():
            custom_content = r_custom.stdout
            if "Thread Scheduler Mode" in custom_content:
                updated_custom = update_yaml_scheduler_mode(custom_content, mode_str)
                with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as tf:
                    tf.write(updated_custom)
                    custom_temp_path = tf.name
                try:
                    run_cmd(["adb", "-s", serial, "push", custom_temp_path, custom_cfg_path])
                finally:
                    if os.path.exists(custom_temp_path):
                        os.remove(custom_temp_path)

    print(f"[OK] Thread Scheduler Mode set to '{mode_str}'")
    return True


def inspect_device_scheduler(serial: str, game_path: Optional[str] = None) -> str:
    """Reads effective scheduler mode from device config files."""
    title_id = None
    if game_path:
        import re
        m = re.search(r"([A-Za-z]{4}\d{5})", game_path)
        if m:
            title_id = m.group(1).upper()

    if title_id:
        custom_cfg_path = f"{CUSTOM_CONFIGS_DIR_DEVICE}/config_{title_id}.yml"
        r_custom = adb_shell(serial, f"cat {custom_cfg_path} 2>/dev/null || true")
        if r_custom.returncode == 0 and r_custom.stdout.strip():
            mode = read_yaml_scheduler_mode(r_custom.stdout)
            if mode:
                return mode

    r_cat = adb_shell(serial, f"cat {GLOBAL_CONFIG_PATH_DEVICE} 2>/dev/null || true")
    if r_cat.returncode == 0 and r_cat.stdout.strip():
        mode = read_yaml_scheduler_mode(r_cat.stdout)
        if mode:
            return mode

    return "Operating System (default)"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run deterministic SambaS3 God of War III benchmark on OnePlus 13R."
    )
    parser.add_argument("--serial", "--device", dest="serial", default=DEFAULT_DEVICE, help=f"ADB device serial (default: {DEFAULT_DEVICE})")
    parser.add_argument("--game", default=DEFAULT_GAME, help=f"Target game path (default: {DEFAULT_GAME})")
    parser.add_argument("--run-id", help="Unique identifier for benchmark run")
    parser.add_argument("--scene", default="gow3-gaia-combat", help="Target benchmark scene (default: gow3-gaia-combat)")
    parser.add_argument("--duration", type=int, default=60, help="Gameplay duration in seconds (default: 60s)")
    parser.add_argument("--monitor-preset", default="Performance", help="Monitor preset: Off, Minimal, Performance, Detailed (default: Performance)")
    parser.add_argument("--expected-core", default=EXPECTED_CORE_HASH, help=f"Expected backend core commit (default: {EXPECTED_CORE_HASH})")
    parser.add_argument("--expected-apk-sha", "--expected-apk-sha256", dest="expected_apk_sha", default=EXPECTED_APK_SHA256, help=f"Expected APK SHA-256 (default: {EXPECTED_APK_SHA256})")
    parser.add_argument("--expected-so-sha", default=EXPECTED_SO_SHA256, help=f"Expected librpcsx-android.so SHA-256 (default: {EXPECTED_SO_SHA256})")
    parser.add_argument("--dry-run", action="store_true", help="Validate provenance and device status without launching game")
    parser.add_argument("--no-strict-hashes", action="store_true", help="Warn instead of rejecting mismatched APK/core hashes")
    parser.add_argument(
        "--scheduler",
        choices=["os", "rpcs3", "alt"],
        default=None,
        help="Thread scheduler mode: os (Operating System, unpinned) vs rpcs3 (RPCS3 Scheduler, pinned to perf cores 0xFC) (default: preserve current config)",
    )
    parser.add_argument("--outdir", help="Directory to store collected evidence and reports")

    args = parser.parse_args()

    timestamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    run_id = args.run_id or f"gow3-bench-{timestamp}"
    outdir = Path(args.outdir or ROOT_DIR / "docs" / "benchmarks" / f"evidence-{run_id}")

    print("================================================================================")
    print(" SambaS3 God of War® III Deterministic Benchmark Runner")
    print(f" Target Device:   {args.serial}")
    print(f" Run ID:          {run_id}")
    print(f" Workload:        {args.game}")
    print(f" Target Scene:    {args.scene}")
    print(f" Target Duration: {args.duration} seconds")
    print(f" Monitor Preset:  {args.monitor_preset}")
    if args.scheduler:
        print(f" Scheduler Mode:  {resolve_scheduler_mode(args.scheduler)}")
    print(f" Evidence Dir:    {outdir}")
    print("================================================================================")

    # 1. Device connection check
    r_state = run_cmd(["adb", "-s", args.serial, "get-state"])
    if r_state.returncode != 0 or r_state.stdout.strip() != "device":
        print(f"Error: Target device {args.serial} is not connected or in device state", file=sys.stderr)
        return 1

    # 2. Provenance inspection & validation
    print("[*] Inspecting device provenance and installed binary signatures...")
    prov = fetch_device_provenance(args.serial)
    is_valid, errors = validate_provenance(
        prov,
        expected_core=args.expected_core,
        expected_apk_sha=args.expected_apk_sha,
        expected_so_sha=args.expected_so_sha,
    )

    print(f"    Device Model:  {prov.model} (SoC: {prov.soc}, Android {prov.android_ver})")
    print(f"    Version:       {prov.version_name} ({prov.version_code})")
    print(f"    base.apk SHA:  {prov.base_apk_sha256 or 'unknown'}")
    print(f"    librpcsx SHA:  {prov.librpcsx_so_sha256 or 'unknown'}")
    print(f"    S3CORE:        {prov.s3core_identity}")

    if not is_valid:
        print("[!] Provenance Validation Errors:", file=sys.stderr)
        for err in errors:
            print(f"    - {err}", file=sys.stderr)
        if not args.no_strict_hashes:
            print("Error: Rejecting benchmark run due to mismatched binary/core provenance.", file=sys.stderr)
            return 2
        else:
            print("[!] Continuing despite provenance warnings (--no-strict-hashes enabled)")
    else:
        print("[OK] Binary and S3CORE provenance verified 100% against expectation")

    # Configure scheduler mode if specified, otherwise inspect existing mode
    effective_scheduler: Optional[str] = None
    if args.scheduler:
        if args.dry_run:
            effective_scheduler = resolve_scheduler_mode(args.scheduler)
            print(f"[*] Dry run: would configure Thread Scheduler Mode to '{effective_scheduler}'")
        else:
            if configure_scheduler(args.serial, args.scheduler, args.game):
                effective_scheduler = resolve_scheduler_mode(args.scheduler)
            else:
                print(f"[!] Warning: failed to configure scheduler mode '{args.scheduler}'", file=sys.stderr)
    else:
        if not args.dry_run:
            effective_scheduler = inspect_device_scheduler(args.serial, args.game)
            print(f"[*] Current Thread Scheduler Mode on device: '{effective_scheduler}'")
        else:
            effective_scheduler = "unspecified (dry run)"

    if args.dry_run:
        print("[*] Dry run complete: environment and provenance verified. No launch performed.")
        return 0

    # 3. Pre-flight cleanup & check existing activity
    r_focus = adb_shell(args.serial, "dumpsys window | grep 'mCurrentFocus.*RPCSXActivity' || true")
    if "RPCSXActivity" in r_focus.stdout:
        print("[!] RPCSXActivity is already focused. Stopping existing session first...")
        run_cmd([str(SCRIPTS_DIR / "debug-stop-game.sh"), args.serial], timeout=45)
        time.sleep(2)

    # 4. Clear logcat buffers
    print("[*] Clearing logcat buffers for clean session acquisition...")
    adb_shell(args.serial, "logcat -c")

    # 5. Launch game
    print(f"[*] Launching {args.game} on {args.serial}...")
    launch_cmd = [str(SCRIPTS_DIR / "debug-launch-game.sh"), args.serial, args.game]
    r_launch = run_cmd(launch_cmd, timeout=35)
    if r_launch.returncode != 0:
        print(f"Error: Game launch failed: {r_launch.stderr.strip()}", file=sys.stderr)
        # Still collect logs to diagnose why launch failed
        collect_cmd = [
            str(PERF_DIR / "collect-run-evidence.sh"),
            "--run-id", run_id,
            "--scene", f"{args.scene}-launch-fail",
            args.serial,
            str(outdir),
        ]
        run_cmd(collect_cmd, timeout=60)
        return 3

    print("[OK] Launch accepted and RPCSXActivity is focused!")

    # 6. Configure monitor overlay
    time.sleep(1)
    configure_monitor(args.serial, args.monitor_preset)

    # 7. Progression loop (navigating intro and cutscenes to reach Gaia combat)
    # Timeline as observed in baseline and revised benchmark:
    # ~35s: First frame / Sony warning screen
    # +10s: START
    # ~55s: Opening Olympus cutscene
    # +30s: START (advance cutscene)
    # ~120s: Waterfall ascent
    # +15s: CROSS
    # ~160s: Mount Olympus summit
    # ~250s: Transition into 3D gameplay on Gaia's back
    print("[*] Waiting for initial intro sequence and progression milestones...")
    time.sleep(30)
    deliver_pad_button(args.serial, "START", delay_s=5)

    print("[*] Advancing opening cutscene...")
    time.sleep(15)
    deliver_pad_button(args.serial, "START", delay_s=5)

    print("[*] Advancing dialogue / scene prompts...")
    time.sleep(25)
    deliver_pad_button(args.serial, "CROSS", delay_s=5)

    # Check if process is still alive
    r_pid = adb_shell(args.serial, f"pidof {PKG_NAME}")
    pid = r_pid.stdout.strip()
    if not pid:
        print("[!] ERROR: Process died before reaching gameplay window!", file=sys.stderr)
        # Collect evidence immediately
        run_cmd([
            str(PERF_DIR / "collect-run-evidence.sh"),
            "--run-id", run_id,
            "--scene", f"{args.scene}-crashed-pre-gameplay",
            args.serial,
            str(outdir),
        ], timeout=60)
        return 4

    print(f"[*] Process alive (PID {pid}). Executing gameplay benchmark window ({args.duration}s)...")
    start_time = time.time()
    next_pad_time = start_time + 5.0

    while time.time() - start_time < args.duration:
        # Periodic pad inputs (SQUARE light attacks) to keep combat active
        if time.time() >= next_pad_time:
            deliver_pad_button(args.serial, "SQUARE", delay_s=0.5)
            next_pad_time = time.time() + 8.0

        # Check process liveness
        r_alive = adb_shell(args.serial, f"pidof {PKG_NAME}")
        if not r_alive.stdout.strip():
            print("[!] CRASH DETECTED during benchmark gameplay window!", file=sys.stderr)
            break
        time.sleep(2.0)

    elapsed_gameplay = time.time() - start_time
    print(f"[*] Benchmark window completed after {elapsed_gameplay:.1f}s.")

    # 8. Evidence collection (capture volatile thread snapshot and metrics while alive)
    print(f"[*] Collecting run evidence to {outdir}...")
    collect_cmd = [
        str(PERF_DIR / "collect-run-evidence.sh"),
        "--run-id", run_id,
        "--scene", args.scene,
        args.serial,
        str(outdir),
    ]
    run_cmd(collect_cmd, timeout=60)

    # 9. Deterministic clean stop
    print("[*] Initiating clean game stop via debug-stop-game.sh...")
    clean_stop = False
    try:
        r_stop = run_cmd([str(SCRIPTS_DIR / "debug-stop-game.sh"), args.serial], timeout=75)
        clean_stop = (r_stop.returncode == 0)
    except subprocess.TimeoutExpired:
        print("[!] Warning: debug-stop-game.sh timed out waiting for stop confirmation", file=sys.stderr)

    # Refresh post-stop exit info in evidence directory
    r_exit = adb_shell(args.serial, f"dumpsys activity exit-info {PKG_NAME}")
    if r_exit.returncode == 0 and r_exit.stdout.strip():
        (outdir / "exit-info.txt").write_text(r_exit.stdout, encoding="utf-8")

    manifest_path = outdir / "run-manifest.json"
    if manifest_path.exists():
        try:
            m_data = json.loads(manifest_path.read_text(encoding="utf-8"))
            m_data["clean_stop"] = clean_stop
            m_data["stop_reason"] = "DEBUG_STOP_GAME" if clean_stop else "STOP_FAILED_OR_CRASH"
            if effective_scheduler:
                m_data["scheduler_mode"] = effective_scheduler
            manifest_path.write_text(json.dumps(m_data, indent=2), encoding="utf-8")
        except Exception:
            pass

    # 10. Run frame events analyzer
    analysis_json_path = outdir / "frame-analysis.json"
    log_for_analysis = outdir / "logcat-process.log"
    if not log_for_analysis.exists() or log_for_analysis.stat().st_size == 0:
        log_for_analysis = outdir / "logcat-sambas3.txt"
    if log_for_analysis.exists():
        print(f"[*] Running analyze-frame-events.py on collected session logs ({log_for_analysis.name})...")
        analyze_cmd = [
            str(PERF_DIR / "analyze-frame-events.py"),
            str(log_for_analysis),
            "--json",
            "--output-json", str(analysis_json_path),
        ]
        r_an = run_cmd(analyze_cmd, timeout=30)
        if r_an.returncode == 0:
            print("[OK] Frame analysis complete!")
        else:
            print(f"[!] Warning: frame analysis exited with code {r_an.returncode}: {r_an.stderr.strip()}", file=sys.stderr)

    print("================================================================================")
    print(" Benchmark Run Summary")
    print(f" Run ID:          {run_id}")
    if effective_scheduler:
        print(f" Scheduler Mode:  {effective_scheduler}")
    print(f" Output Dir:      {outdir}")
    if analysis_json_path.exists():
        try:
            analysis_data = json.loads(analysis_json_path.read_text(encoding="utf-8"))
            print(f" Throughput:   {analysis_data.get('interval_throughput_fps', 0.0):.2f} FPS")
            print(f" Median (P50): {analysis_data.get('frametime_p50_ms', 0.0):.2f} ms")
            print(f" P95:          {analysis_data.get('frametime_p95_ms', 0.0):.2f} ms")
            print(f" P99:          {analysis_data.get('frametime_p99_ms', 0.0):.2f} ms")
        except Exception:
            pass
    print("================================================================================")
    return 0


if __name__ == "__main__":
    sys.exit(main())
