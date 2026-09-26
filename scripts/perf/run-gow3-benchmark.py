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

# Pinned commit and release hashes for verification (Phase 7/8 Qualification)
EXPECTED_CORE_HASH = "c9c862ff354a1134bff5054dada5c2cf452979bd"
EXPECTED_APK_SHA256 = "f2a6648d598dc03bedd1721766edc509a58ff4eadb0c22fa199f69cf69bbaaa0"
EXPECTED_SO_SHA256 = "07d0892cb76886ab0c03cc664a21a3884f87c94a346bbd11d5addf178f64edb0"


def run_cmd(cmd: List[str], timeout: Optional[int] = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        errors="replace",
        timeout=timeout,
        cwd=str(ROOT_DIR),
    )


def adb_shell(serial: str, shell_cmd: str, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    return run_cmd(["adb", "-s", serial, "shell", shell_cmd], timeout=timeout)


@dataclass
class ProcessIdentity:
    pid: str
    start_time: str = ""
    boot_id: str = ""


def get_process_identity(serial: str, package_name: str) -> Optional[ProcessIdentity]:
    r_pid = adb_shell(serial, f"pidof -s {package_name}")
    pid = r_pid.stdout.strip()
    if not pid or not pid.isdigit():
        r_all = adb_shell(serial, f"pidof {package_name}")
        pids = r_all.stdout.strip().split()
        if not pids or not pids[0].isdigit():
            return None
        pid = pids[0]

    r_stat = adb_shell(serial, f"cat /proc/{pid}/stat 2>/dev/null")
    start_time = ""
    if r_stat.returncode == 0 and r_stat.stdout.strip():
        parts = r_stat.stdout.strip().rsplit(")", 1)
        if len(parts) > 1:
            rest = parts[1].split()
            if len(rest) >= 20:
                start_time = rest[19]

    r_boot = adb_shell(serial, "cat /proc/sys/kernel/random/boot_id 2>/dev/null || cat /proc/uptime 2>/dev/null")
    boot_id = r_boot.stdout.strip().split()[0] if r_boot.stdout.strip() else ""

    return ProcessIdentity(pid=pid, start_time=start_time, boot_id=boot_id)


def write_manifest_atomic(manifest_path: Path, m_data: dict[str, Any]) -> None:
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = manifest_path.with_suffix(".tmp")
    tmp_path.write_text(json.dumps(m_data, indent=2), encoding="utf-8")
    tmp_path.replace(manifest_path)


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

    if expected_apk_sha:
        if not prov.base_apk_sha256 or prov.base_apk_sha256 == "unknown":
            errors.append(f"base.apk SHA-256 missing/unknown on device {prov.serial}")
        elif prov.base_apk_sha256.lower() != expected_apk_sha.lower():
            errors.append(
                f"base.apk SHA-256 mismatch: expected {expected_apk_sha}, observed {prov.base_apk_sha256}"
            )

    if expected_so_sha:
        if not prov.librpcsx_so_sha256 or prov.librpcsx_so_sha256 == "unknown":
            errors.append(f"librpcsx-android.so SHA-256 missing/unknown on device {prov.serial}")
        elif prov.librpcsx_so_sha256.lower() != expected_so_sha.lower():
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
    parser.add_argument("--provenance-manifest", help="Path to build/provenance manifest JSON to source expected hashes from")
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

    expected_core = args.expected_core
    expected_apk_sha = args.expected_apk_sha
    expected_so_sha = args.expected_so_sha

    # Load dynamic hashes from provenance manifest if available
    prov_manifest_path = Path(args.provenance_manifest) if args.provenance_manifest else None
    if prov_manifest_path and prov_manifest_path.exists():
        try:
            p_data = json.loads(prov_manifest_path.read_text(encoding="utf-8"))
            pkg_data = p_data.get("package", {})
            git_data = p_data.get("git", {})
            if "librpcsx_so_sha256" in pkg_data and args.expected_so_sha == EXPECTED_SO_SHA256:
                expected_so_sha = pkg_data["librpcsx_so_sha256"]
            if "base_apk_sha256" in pkg_data and args.expected_apk_sha == EXPECTED_APK_SHA256:
                expected_apk_sha = pkg_data["base_apk_sha256"]
            if "rpcsx_submodule_head" in git_data and args.expected_core == EXPECTED_CORE_HASH:
                expected_core = git_data["rpcsx_submodule_head"]
        except Exception as e:
            print(f"[!] Warning: failed to parse provenance manifest at {prov_manifest_path}: {e}")

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
        expected_core=expected_core,
        expected_apk_sha=expected_apk_sha,
        expected_so_sha=expected_so_sha,
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
            outdir.mkdir(parents=True, exist_ok=True)
            manifest_path = outdir / "run-manifest.json"
            m_data = {
                "run_id": run_id,
                "first_failure": "FAIL_PROVENANCE",
                "verdict": "FAIL_PROVENANCE",
                "provenance_errors": errors,
                "strict_provenance": True,
            }
            write_manifest_atomic(manifest_path, m_data)
            return 3
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

    outdir.mkdir(parents=True, exist_ok=True)

    # 4a. Preserve prior logcat crash buffer before clear
    print("[*] Preserving prior logcat crash buffer before clear...")
    r_prior = adb_shell(args.serial, "logcat -d")
    if r_prior.stdout:
        (outdir / "logcat-prior-crash-buffer.log").write_text(r_prior.stdout, encoding="utf-8", errors="replace")

    # 4b. Clear logcat buffers
    print("[*] Clearing logcat buffers for clean session acquisition...")
    adb_shell(args.serial, "logcat -c")

    # 4c. Start host-streamed logcat in background
    host_log_file = open(outdir / "logcat-host-stream.log", "wb")
    host_log_proc: Optional[subprocess.Popen[Any]] = None
    try:
        host_log_proc = subprocess.Popen(
            ["adb", "-s", args.serial, "logcat", "-v", "threadtime"],
            stdout=host_log_file,
            stderr=subprocess.STDOUT
        )
    except Exception as e:
        print(f"[!] Warning: failed to start host logcat stream: {e}", file=sys.stderr)

    first_failure: Optional[str] = None
    launch_identity: Optional[ProcessIdentity] = None
    clean_stop = False
    run_state = "LAUNCHING"
    outdir_png = outdir / "device_screen.png"
    gameplay_png = ROOT_DIR / "docs" / "benchmarks" / "screenshots" / "gow3-phase10-gameplay.png"
    gameplay_png.parent.mkdir(parents=True, exist_ok=True)
    qual_start_us = int(time.time() * 1_000_000)
    qual_end_us = qual_start_us

    try:
        # 5. Launch game
        print(f"[*] Launching {args.game} on {args.serial}...")
        launch_cmd = [str(SCRIPTS_DIR / "debug-launch-game.sh"), args.serial, args.game]
        r_launch = run_cmd(launch_cmd, timeout=35)
        if r_launch.returncode != 0:
            print(f"Error: Game launch failed: {r_launch.stderr.strip()}", file=sys.stderr)
            first_failure = "FAIL_LAUNCH"
        else:
            print("[OK] Launch accepted and RPCSXActivity is focused!")
            launch_identity = get_process_identity(args.serial, PKG_NAME)
            if not launch_identity:
                print("[!] ERROR: Process did not start after launch command!", file=sys.stderr)
                first_failure = "FAIL_LAUNCH"

        if first_failure is None and launch_identity is not None:
            print(f"[OK] Launch verified with identity: PID {launch_identity.pid}, start: {launch_identity.start_time}")

            # 6. Configure monitor overlay
            time.sleep(1)
            configure_monitor(args.serial, args.monitor_preset)

            # 7. Progression loop (navigating intro and cutscenes to reach Gaia combat)
            run_state = "NAVIGATING"
            print("[*] Waiting for initial intro sequence and progression milestones...")
            time.sleep(30)
            deliver_pad_button(args.serial, "START", delay_s=5)

            print("[*] Advancing opening cutscene...")
            time.sleep(15)
            deliver_pad_button(args.serial, "START", delay_s=5)

            print("[*] Advancing dialogue / scene prompts...")
            time.sleep(25)
            deliver_pad_button(args.serial, "CROSS", delay_s=5)

            # Check if process is still alive and matches launch identity
            curr_id = get_process_identity(args.serial, PKG_NAME)
            if not curr_id:
                print("[!] ERROR: Process died before reaching gameplay window!", file=sys.stderr)
                first_failure = "FAIL_CRASH"
            elif curr_id.pid != launch_identity.pid or curr_id.start_time != launch_identity.start_time:
                print(f"[!] ERROR: Process replaced before gameplay window: launch {launch_identity.pid} != current {curr_id.pid}!", file=sys.stderr)
                first_failure = "FAIL_REPLACEMENT_PID"

        if first_failure is None and launch_identity is not None:
            run_state = "QUALIFYING"
            print(f"[*] Process alive (PID {launch_identity.pid}). Executing gameplay benchmark window ({args.duration}s)...")
            try:
                r_sc = subprocess.run(["adb", "-s", args.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=15)
                if r_sc.returncode == 0 and r_sc.stdout:
                    with open(outdir_png, "wb") as f:
                        f.write(r_sc.stdout)
                    print(f"[*] Captured initial gameplay screenshot to {outdir_png}")
            except Exception as e:
                print(f"[!] Warning: initial screencap failed: {e}")

            if not outdir_png.exists() or outdir_png.stat().st_size == 0:
                print("[!] Error: Initial screencap missing or empty.", file=sys.stderr)
                if first_failure is None:
                    first_failure = "INCONCLUSIVE_EVIDENCE"

            start_mono = time.monotonic()
            qual_start_us = int(time.time() * 1_000_000)
            next_pad_time = start_mono + 5.0

            while time.monotonic() - start_mono < args.duration:
                # Periodic pad inputs (SQUARE light attacks) to keep combat active
                if time.monotonic() >= next_pad_time:
                    deliver_pad_button(args.serial, "SQUARE", delay_s=0.5)
                    next_pad_time = time.monotonic() + 8.0

                # Check process liveness & exact PID/session identity
                loop_id = get_process_identity(args.serial, PKG_NAME)
                if not loop_id:
                    print("[!] CRASH DETECTED during benchmark gameplay window!", file=sys.stderr)
                    first_failure = "FAIL_CRASH"
                    break
                if loop_id.pid != launch_identity.pid or loop_id.start_time != launch_identity.start_time:
                    print(f"[!] Process replacement detected: launch PID {launch_identity.pid} != current PID {loop_id.pid}!", file=sys.stderr)
                    first_failure = "FAIL_REPLACEMENT_PID"
                    break

                # Capture updated in-combat screenshot during gameplay window
                if time.monotonic() - start_mono >= 10.0 and not (outdir / "device_screen_combat.png").exists():
                    combat_png = outdir / "device_screen_combat.png"
                    r_sc2 = subprocess.run(["adb", "-s", args.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=15)
                    if r_sc2.returncode == 0 and r_sc2.stdout:
                        combat_png.write_bytes(r_sc2.stdout)
                        outdir_png.write_bytes(r_sc2.stdout)
                        gameplay_png.write_bytes(r_sc2.stdout)
                        print(f"[*] Updated gameplay screenshot with live combat scene: {combat_png}")

                time.sleep(2.0)

            elapsed_gameplay = time.monotonic() - start_mono
            qual_end_us = int(time.time() * 1_000_000)
            print(f"[*] Benchmark window completed after {elapsed_gameplay:.1f}s.")

    finally:
        # 8. Evidence collection
        run_state = "COLLECTING"
        print(f"[*] Collecting run evidence to {outdir}...")
        collect_scene = f"{args.scene}-{first_failure}" if first_failure else args.scene
        collect_cmd = [
            str(PERF_DIR / "collect-run-evidence.sh"),
            "--run-id", run_id,
            "--scene", collect_scene,
            args.serial,
            str(outdir),
        ]
        r_collect = run_cmd(collect_cmd, timeout=60)
        if r_collect.returncode != 0:
            print(f"[!] Warning: collect-run-evidence.sh exited with code {r_collect.returncode}", file=sys.stderr)
            if first_failure is None:
                first_failure = "INCONCLUSIVE_EVIDENCE"

        # Capture thermal status
        r_thermal = adb_shell(args.serial, "dumpsys thermalservice 2>/dev/null")
        if r_thermal.returncode == 0 and r_thermal.stdout.strip():
            (outdir / "thermal-status.txt").write_text(r_thermal.stdout, encoding="utf-8")

        # 9. Deterministic clean stop & terminate host logger
        run_state = "CLEANUP"
        if host_log_proc:
            try:
                host_log_proc.terminate()
                host_log_proc.wait(timeout=3)
            except Exception:
                try:
                    host_log_proc.kill()
                except Exception:
                    pass
            try:
                host_log_file.close()
            except Exception:
                pass

        print("[*] Initiating clean game stop via debug-stop-game.sh...")
        try:
            r_stop = run_cmd([str(SCRIPTS_DIR / "debug-stop-game.sh"), args.serial], timeout=75)
            clean_stop = (r_stop.returncode == 0)
        except subprocess.TimeoutExpired:
            print("[!] Warning: debug-stop-game.sh timed out waiting for stop confirmation", file=sys.stderr)
            clean_stop = False

        # Refresh post-stop exit info in evidence directory
        r_exit = adb_shell(args.serial, f"dumpsys activity exit-info {PKG_NAME}")
        if r_exit.returncode == 0 and r_exit.stdout.strip():
            (outdir / "exit-info.txt").write_text(r_exit.stdout, encoding="utf-8")

        # 10. Run frame events analyzer
        analysis_json_path = outdir / "frame-analysis.json"
        candidate_logs = [outdir / "logcat-process.log", outdir / "logcat-host-stream.log", outdir / "logcat-sambas3.txt"]
        analyzed_ok = False
        thermal_file = outdir / "thermal-status.txt"

        for cand_log in candidate_logs:
            if cand_log.exists() and cand_log.stat().st_size > 0:
                print(f"[*] Running analyze-frame-events.py on collected session logs ({cand_log.name})...")
                analyze_cmd = [
                    str(PERF_DIR / "analyze-frame-events.py"),
                    str(cand_log),
                    "--json",
                    "--output-json", str(analysis_json_path),
                    "--start-us", str(qual_start_us),
                    "--end-us", str(qual_end_us),
                ]
                if thermal_file.exists():
                    analyze_cmd.extend(["--thermal-log", str(thermal_file)])

                r_an = run_cmd(analyze_cmd, timeout=30)
                if r_an.returncode == 0 and analysis_json_path.exists():
                    try:
                        analysis_data = json.loads(analysis_json_path.read_text(encoding="utf-8"))
                        if analysis_data.get("qualified_frames", 0) > 0:
                            print(f"[OK] Frame analysis complete using {cand_log.name}!")
                            analyzed_ok = True
                            break
                    except Exception:
                        pass

        if not analyzed_ok and first_failure is None:
            first_failure = "FAIL_GUEST_PROGRESS"

        verdict = "PASS" if (first_failure is None) else first_failure

        manifest_path = outdir / "run-manifest.json"
        m_data = {}
        if manifest_path.exists():
            try:
                m_data = json.loads(manifest_path.read_text(encoding="utf-8"))
            except Exception:
                m_data = {}

        m_data["run_id"] = run_id
        m_data["target_scene"] = args.scene
        m_data["clean_stop"] = clean_stop
        m_data["first_failure"] = first_failure
        m_data["verdict"] = verdict
        m_data["stop_reason"] = "DEBUG_STOP_GAME" if (clean_stop and first_failure is None) else (first_failure or "STOP_FAILED_OR_CRASH")
        m_data["cleanup"] = {
            "attempted": True,
            "clean_stop": clean_stop,
            "result": "SUCCESS" if clean_stop else "FAILED"
        }
        m_data["launch_identity"] = {
            "pid": launch_identity.pid,
            "start_time": launch_identity.start_time,
            "boot_id": launch_identity.boot_id,
        } if launch_identity else None
        if effective_scheduler:
            m_data["scheduler_mode"] = effective_scheduler

        try:
            write_manifest_atomic(manifest_path, m_data)
        except Exception as e:
            print(f"[!] Error writing run-manifest.json: {e}", file=sys.stderr)
            if first_failure is None:
                first_failure = "INCONCLUSIVE_EVIDENCE"
                verdict = "INCONCLUSIVE_EVIDENCE"

        # Publish screenshot only on verified PASS
        if verdict == "PASS" and outdir_png.exists():
            try:
                with open(outdir_png, "rb") as f_in, open(gameplay_png, "wb") as f_out:
                    f_out.write(f_in.read())
            except Exception:
                pass

        run_state = "FINISHED"

    print("================================================================================")
    print(" Benchmark Run Summary")
    print(f" Run ID:          {run_id}")
    print(f" Verdict:         {verdict}")
    if first_failure:
        print(f" First Failure:   {first_failure}")
    print(f" Cleanup Result:  {'SUCCESS' if clean_stop else 'FAILED'}")
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
    if verdict != "PASS":
        if first_failure in ("FAIL_CRASH", "FAIL_REPLACEMENT_PID"):
            return 4
        elif first_failure in ("FAIL_PROVENANCE", "FAIL_LAUNCH"):
            return 3
        elif first_failure in ("INCONCLUSIVE_EVIDENCE", "INCONCLUSIVE_SCENE"):
            return 2
        return 1
    return 0



if __name__ == "__main__":
    sys.exit(main())
