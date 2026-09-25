#!/usr/bin/env python3
"""
classify-crash.py — Robust offline crash & exit classification tool for SambaS3.

Parses dumpsys activity exit-info, logcat, tombstones, and run manifests to provide
deterministic failure diagnosis conforming to SambaS3 Ticket C02 and WORKER.md Section 6.

Rules:
- Match ApplicationExitInfo specifically by PID, process start timestamp, and session ID
  (never simply take the latest record which might be an old session or different process).
- Distinguish :ppu_compile process exits from the primary emulator process (com.zenithblue.sambas3).
- Extract tombstone trace details (signal, status, pc, backtrace) safely.
- Implement typed classification:
  * NATIVE_CRASH (signal, status, pc, backtrace)
  * JAVA_EXCEPTION (stack trace)
  * MEMORY_PRESSURE_KILL (LMKD / low memory)
  * ANR (Application Not Responding)
  * DELIBERATE_STOP (user exited, DEBUG_STOP_GAME, activity finish)
  * VULKAN_DEVICE_LOSS / GPU_RESET
  * SYSTEM_SERVER_KILL
  * UNKNOWN (when evidence cannot distinguish)
- Do NOT treat SIGKILL as definitively LMKD without memory pressure evidence.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import asdict, dataclass, field
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


class ExitClassification(str, Enum):
    NATIVE_CRASH = "NATIVE_CRASH"
    JAVA_EXCEPTION = "JAVA_EXCEPTION"
    MEMORY_PRESSURE_KILL = "MEMORY_PRESSURE_KILL"
    ANR = "ANR"
    DELIBERATE_STOP = "DELIBERATE_STOP"
    VULKAN_DEVICE_LOSS = "VULKAN_DEVICE_LOSS"
    SYSTEM_SERVER_KILL = "SYSTEM_SERVER_KILL"
    UNKNOWN = "UNKNOWN"


REASON_MAP = {
    0: "UNKNOWN",
    1: "EXIT_SELF",
    2: "SIGNALED",
    3: "LOW_MEMORY",
    4: "CRASH",
    5: "CRASH_NATIVE",
    6: "ANR",
    7: "INITIALIZATION_FAILURE",
    8: "PERMISSION_CHANGE",
    9: "EXCESSIVE_RESOURCE_USAGE",
    10: "USER_REQUESTED",
    11: "USER_STOPPED",
    12: "DEPENDENCY_DIED",
    13: "OTHER",
    14: "FREEZER",
    15: "PACKAGE_STATE_CHANGE",
    16: "PACKAGE_UPDATED",
}

SUBREASON_MAP = {
    0: "UNKNOWN",
    1: "WAIT_FOR_DEBUGGER",
    2: "TOO_MANY_CACHED_PROCS",
    3: "TOO_MANY_EMPTY_PROCS",
    4: "TRIM_EMPTY",
    5: "LARGE_CACHED",
    6: "MEMORY_PRESSURE",
    7: "EXCESSIVE_CPU",
    8: "SYSTEM_UPDATE_DONE",
    9: "KILL_BACKGROUND",
    10: "PACKAGE_UPDATE",
    11: "UNDELIVERED_BROADCAST",
    21: "FORCE_STOP",
    22: "REMOVE_TASK",
    23: "STOP_APP",
    24: "KILL_ALL_BG_EXCEPT",
    25: "KILL_ALL_FG",
}

SIGNAL_MAP = {
    1: "SIGHUP",
    2: "SIGINT",
    3: "SIGQUIT",
    4: "SIGILL",
    5: "SIGTRAP",
    6: "SIGABRT",
    7: "SIGBUS",
    8: "SIGFPE",
    9: "SIGKILL",
    10: "SIGUSR1",
    11: "SIGSEGV",
    12: "SIGUSR2",
    13: "SIGPIPE",
    14: "SIGALRM",
    15: "SIGTERM",
}

NATIVE_FATAL_SIGNALS = {4, 5, 6, 7, 8, 11}

GPU_FAULT_REGEX = re.compile(
    r"VK_ERROR_DEVICE_LOST|device lost|gpu reset|native renderer fatal|gpu fault|kgsl.*fault|ringbuffer hang",
    re.IGNORECASE,
)

MEMORY_PRESSURE_REGEX = re.compile(
    r"\b(lmkd|lowmemorykiller|am_low_memory|low_memory|Out of memory|OOM killer|Scudo OOM|std::bad_alloc)\b",
    re.IGNORECASE,
)

DELIBERATE_STOP_KEYWORDS = {
    "debug_stop_game",
    "ingameexit",
    "homestop",
    "apprecoverycleanup",
    "user-exit",
    "activity finish",
}


@dataclass
class ProcessExitRecord:
    pid: int
    process_name: str
    reason: int
    reason_name: str
    subreason: int = 0
    subreason_name: str = "UNKNOWN"
    status: int = 0
    importance: int = 0
    timestamp_str: str = ""
    timestamp_ms: int = 0
    pss_bytes: int = 0
    rss_bytes: int = 0
    description: Optional[str] = None
    trace: Optional[str] = None

    @property
    def is_ppu_compile(self) -> bool:
        return self.process_name.endswith(":ppu_compile")

    @property
    def is_primary_process(self) -> bool:
        return self.process_name == "com.zenithblue.sambas3"

    @property
    def signal_name(self) -> Optional[str]:
        if self.reason == 2:  # SIGNALED
            return SIGNAL_MAP.get(self.status, f"SIGNAL_{self.status}")
        return None


@dataclass
class ProcessExitDiagnosis:
    classification: ExitClassification
    pid: Optional[int] = None
    process_name: Optional[str] = None
    is_ppu_compile_process: bool = False
    signal: Optional[int] = None
    signal_name: Optional[str] = None
    status: Optional[int] = None
    pc: Optional[str] = None
    backtrace: List[str] = field(default_factory=list)
    stack_trace: Optional[str] = None
    reason_code: Optional[int] = None
    reason_name: Optional[str] = None
    subreason_code: Optional[int] = None
    subreason_name: Optional[str] = None
    importance: Optional[int] = None
    timestamp: Optional[str] = None
    pss_bytes: int = 0
    rss_bytes: int = 0
    description: Optional[str] = None
    summary: str = ""
    raw_trace: Optional[str] = None
    memory_pressure_evidence: bool = False
    matched_by: str = "unmatched"
    ppu_compile_exits: List[Dict[str, Any]] = field(default_factory=list)
    cleanup_attempted: bool = False
    cleanup_success: bool = False
    first_failure: Optional[str] = None
    affinity_check_required: bool = False
    affinity_notes: Optional[str] = None



def parse_bytes(s: Optional[str]) -> int:
    if not s or s in ("0.00", "0", "null"):
        return 0
    s = s.strip().upper()
    try:
        if s.endswith("GB"):
            return int(float(s[:-2]) * 1024 * 1024 * 1024)
        if s.endswith("MB"):
            return int(float(s[:-2]) * 1024 * 1024)
        if s.endswith("KB"):
            return int(float(s[:-2]) * 1024)
        if s.endswith("B"):
            return int(s[:-1])
        return int(float(s))
    except Exception:
        return 0


def parse_dumpsys_exit_info(text: str) -> List[ProcessExitRecord]:
    """Parses output from dumpsys activity exit-info <pkg>."""
    if not text:
        return []

    records: List[ProcessExitRecord] = []
    # Split on ApplicationExitInfo #<num>:
    blocks = re.split(r"(?m)^\s*ApplicationExitInfo\s+#\d+:", text)

    for block in blocks:
        block = block.strip()
        if not block:
            continue

        ts_match = re.search(r"timestamp=([0-9-]+\s+[0-9:.]+)\s+pid=(\d+)", block)
        if not ts_match:
            continue

        ts_str = ts_match.group(1)
        pid = int(ts_match.group(2))
        ts_ms = 0
        try:
            dt = datetime.strptime(ts_str, "%Y-%m-%d %H:%M:%S.%f")
            ts_ms = int(dt.timestamp() * 1000)
        except Exception:
            pass

        proc_match = re.search(r"process=([^\s]+)\s+reason=(\d+)(?:\s*\(([^)]+)\))?", block)
        process = proc_match.group(1) if proc_match else "unknown"
        reason_code = int(proc_match.group(2)) if proc_match else 0
        reason_name = (
            proc_match.group(3)
            if proc_match and proc_match.group(3)
            else REASON_MAP.get(reason_code, "UNKNOWN")
        )

        sub_match = re.search(r"subreason=(\d+)(?:\s*\(([^)]+)\))?", block)
        sub_code = int(sub_match.group(1)) if sub_match else 0
        sub_name = (
            sub_match.group(2)
            if sub_match and sub_match.group(2)
            else SUBREASON_MAP.get(sub_code, "UNKNOWN")
        )

        status_match = re.search(r"status=(\d+)", block)
        status = int(status_match.group(1)) if status_match else 0

        imp_match = re.search(r"importance=(\d+)", block)
        importance = int(imp_match.group(1)) if imp_match else 0

        pss_match = re.search(r"pss=([^\s]+)", block)
        pss_bytes = parse_bytes(pss_match.group(1) if pss_match else None)

        rss_match = re.search(r"rss=([^\s]+)", block)
        rss_bytes = parse_bytes(rss_match.group(1) if rss_match else None)

        desc_match = re.search(r"description=([^\n]+?)(?:\s+state=|$)", block)
        desc_raw = desc_match.group(1).strip() if desc_match else None
        description = None if desc_raw in (None, "", "null") else desc_raw

        trace_match = re.search(r"trace=([\s\S]+)$", block)
        trace_raw = trace_match.group(1).strip() if trace_match else None
        trace = None if trace_raw in (None, "", "null") else trace_raw

        records.append(
            ProcessExitRecord(
                pid=pid,
                process_name=process,
                reason=reason_code,
                reason_name=reason_name,
                subreason=sub_code,
                subreason_name=sub_name,
                status=status,
                importance=importance,
                timestamp_str=ts_str,
                timestamp_ms=ts_ms,
                pss_bytes=pss_bytes,
                rss_bytes=rss_bytes,
                description=description,
                trace=trace,
            )
        )

    return records


def extract_native_crash_info(
    exit_record: Optional[ProcessExitRecord], evidence: str
) -> Tuple[Optional[int], Optional[str], Optional[str], List[str]]:
    """Extracts signal number, signal name, PC, and backtrace frames."""
    sig_num: Optional[int] = exit_record.status if (exit_record and exit_record.reason in (2, 5)) else None
    sig_name: Optional[str] = SIGNAL_MAP.get(sig_num) if sig_num is not None else None

    # Check for signal pattern in text e.g. "Fatal signal 11 (SIGSEGV)" or "signal 6 (SIGABRT)"
    m = re.search(r"(?:Fatal signal|signal)\s+(\d+)\s*\((SIG\w+)\)", evidence, re.IGNORECASE)
    if m:
        try:
            sig_num = int(m.group(1))
            sig_name = m.group(2).upper()
        except Exception:
            pass
    elif not sig_name:
        fm = re.search(r"\b(SIGSEGV|SIGABRT|SIGBUS|SIGFPE|SIGILL|SIGTRAP)\b", evidence)
        if fm:
            sig_name = fm.group(1)
            for k, v in SIGNAL_MAP.items():
                if v == sig_name:
                    sig_num = k
                    break

    # Extract Program Counter (pc)
    pc_match = re.search(r"\bpc\s+([0-9a-fA-F]{6,16})\b", evidence)
    pc = pc_match.group(1) if pc_match else None

    # Extract backtrace frames
    backtrace: List[str] = []
    frame_re = re.compile(r"^\s*#\d+\s+pc\s+[0-9a-fA-F]+.*$")
    for line in evidence.splitlines():
        if frame_re.match(line):
            backtrace.append(line.strip())

    return sig_num, sig_name, pc, backtrace[:40]


def extract_java_stack_trace(trace: Optional[str], logs: str) -> Optional[str]:
    """Extracts unhandled Java exception message and stack trace."""
    source = trace if trace else logs
    if not source:
        return None

    lines = source.splitlines()
    start_idx = -1
    for i, line in enumerate(lines):
        if (
            "FATAL EXCEPTION:" in line
            or "Exception:" in line
            or "Error:" in line
        ):
            start_idx = i
            break

    if start_idx == -1:
        return None

    stack: List[str] = []
    found_frames_or_exc = False
    for i in range(start_idx, min(len(lines), start_idx + 50)):
        line = lines[i]
        trimmed = line.strip()
        if not trimmed:
            if found_frames_or_exc:
                break
            continue

        is_header_or_frame = (
            trimmed.lower().startswith("fatal exception")
            or trimmed.lower().startswith("process:")
            or trimmed.lower().startswith("pid:")
            or "exception" in trimmed.lower()
            or "error" in trimmed.lower()
            or trimmed.startswith("at ")
            or trimmed.startswith("Caused by:")
            or trimmed.startswith("...")
        )
        if is_header_or_frame:
            if trimmed.startswith("at ") or "exception" in trimmed.lower() or "error" in trimmed.lower():
                found_frames_or_exc = True
            stack.append(line)
        elif found_frames_or_exc:
            break

    res = "\n".join(stack).strip()
    return res if res else None


def match_exit_record(
    records: List[ProcessExitRecord],
    target_pid: Optional[int] = None,
    session_id: Optional[str] = None,
    package_name: str = "com.zenithblue.sambas3",
    session_start_ms: Optional[int] = None,
    session_end_ms: Optional[int] = None,
) -> Tuple[Optional[ProcessExitRecord], str]:
    """
    Matches ProcessExitRecord strictly according to Ticket C02:
    - Never pick the latest record when PID doesn't match!
    - Distinguish :ppu_compile exits from primary emulator process.
    - Reject stale records from previous invocations of a recycled PID.
    """
    if not records:
        return None, "no_records"

    # Separate primary emulator process exits from :ppu_compile workers
    primary_records = [
        r for r in records if not r.is_ppu_compile and (r.process_name == package_name or r.is_primary_process)
    ]

    # 1. Match by target PID if provided
    if target_pid is not None and target_pid > 0:
        candidates = [r for r in primary_records if r.pid == target_pid]
        if not candidates:
            # PID is known but not found in exit-info: DO NOT FALL BACK TO LATEST RECORD!
            return None, "pid_not_found_in_exit_info"

        # Check timestamp: must not have died before session started
        if session_start_ms is not None and session_start_ms > 0:
            valid_time = [r for r in candidates if r.timestamp_ms >= (session_start_ms - 5000)]
            if not valid_time:
                return None, "pid_recycled_prior_exit"
            candidates = valid_time

        # Match session ID if present in description or trace
        if session_id:
            for c in candidates:
                if (c.description and session_id in c.description) or (c.trace and session_id in c.trace):
                    return c, "pid_and_session_id"

        # Pick candidate closest to session end or session start
        target_time = session_end_ms or session_start_ms or 0
        best = min(candidates, key=lambda r: abs(r.timestamp_ms - target_time))
        return best, "pid_and_timestamp"

    # 2. Match by session ID if present
    if session_id:
        for r in primary_records:
            if (r.description and session_id in r.description) or (r.trace and session_id in r.trace):
                return r, "session_id_marker"

    # 3. Narrow time-window correlation when PID is missing
    if session_start_ms is not None and session_start_ms > 0:
        window_start = session_start_ms - 5000
        window_end = (session_end_ms or session_start_ms) + 60000
        window_matches = [r for r in primary_records if window_start <= r.timestamp_ms <= window_end]
        if len(window_matches) == 1:
            return window_matches[0], "unambiguous_timestamp_window"

    return None, "unmatched"


def classify_exit(
    exit_record: Optional[ProcessExitRecord],
    evidence_text: str = "",
    stop_reason: Optional[str] = None,
    matched_by: str = "unmatched",
    target_pid: Optional[int] = None,
    ppu_compile_exits: Optional[List[Dict[str, Any]]] = None,
) -> ProcessExitDiagnosis:
    """Classifies an exit into one of the 8 typed diagnoses per Ticket C02."""
    combined = []
    if stop_reason:
        combined.append(stop_reason)
    if exit_record:
        if exit_record.description:
            combined.append(exit_record.description)
        if exit_record.trace:
            combined.append(exit_record.trace)
    if evidence_text:
        combined.append(evidence_text)
    combined_str = "\n".join(combined)

    # 1. Vulkan Device Loss / GPU Reset (takes precedence over generic native abort when device lost triggered abort)
    if GPU_FAULT_REGEX.search(combined_str):
        sig_num, sig_name, pc, backtrace = extract_native_crash_info(exit_record, combined_str)
        cleanup_att = bool(stop_reason)
        cleanup_succ = bool(stop_reason and any(k in stop_reason.lower() for k in DELIBERATE_STOP_KEYWORDS))
        return ProcessExitDiagnosis(
            classification=ExitClassification.VULKAN_DEVICE_LOSS,
            pid=exit_record.pid if exit_record else target_pid,
            process_name=exit_record.process_name if exit_record else None,
            is_ppu_compile_process=exit_record.is_ppu_compile if exit_record else False,
            signal=sig_num,
            signal_name=sig_name,
            status=exit_record.status if exit_record else None,
            pc=pc,
            backtrace=backtrace,
            reason_code=exit_record.reason if exit_record else None,
            reason_name=exit_record.reason_name if exit_record else None,
            subreason_code=exit_record.subreason if exit_record else None,
            subreason_name=exit_record.subreason_name if exit_record else None,
            importance=exit_record.importance if exit_record else None,
            timestamp=exit_record.timestamp_str if exit_record else None,
            pss_bytes=exit_record.pss_bytes if exit_record else 0,
            rss_bytes=exit_record.rss_bytes if exit_record else 0,
            description=exit_record.description if exit_record else None,
            summary="Vulkan Device Loss / GPU Reset",
            raw_trace=exit_record.trace if exit_record else None,
            matched_by=matched_by,
            ppu_compile_exits=ppu_compile_exits or [],
            cleanup_attempted=cleanup_att,
            cleanup_success=cleanup_succ,
            first_failure="FAIL_CRASH",
        )

    # 2. Native Crash (hardware signals and fatal aborts)
    has_native_sig = (
        exit_record
        and (
            exit_record.reason == 5  # CRASH_NATIVE
            or (exit_record.reason == 2 and exit_record.status in NATIVE_FATAL_SIGNALS)
        )
    )
    has_tombstone = (
        "backtrace:" in combined_str.lower()
        or "fatal signal" in combined_str.lower()
        or "*** *** *** ***" in combined_str
    )
    if has_native_sig or has_tombstone:
        sig_num, sig_name, pc, backtrace = extract_native_crash_info(exit_record, combined_str)
        summary_sig = sig_name or (f"Signal {sig_num}" if sig_num else "Native Abort")
        pc_str = f" at pc {pc}" if pc else ""
        cleanup_att = bool(stop_reason)
        cleanup_succ = bool(stop_reason and any(k in stop_reason.lower() for k in DELIBERATE_STOP_KEYWORDS))

        is_sigill = (sig_name == "SIGILL" or sig_num == 4)
        affinity_check_req = is_sigill
        affinity_notes = (
            "SIGILL detected: verify process-allowed CPU capabilities and actual execution affinity for all threads per WORKER.md:465"
            if is_sigill
            else None
        )
        sigill_suffix = f" [Affinity check required: {affinity_notes}]" if is_sigill else ""

        return ProcessExitDiagnosis(
            classification=ExitClassification.NATIVE_CRASH,
            pid=exit_record.pid if exit_record else target_pid,
            process_name=exit_record.process_name if exit_record else None,
            is_ppu_compile_process=exit_record.is_ppu_compile if exit_record else False,
            signal=sig_num,
            signal_name=sig_name,
            status=exit_record.status if exit_record else None,
            pc=pc,
            backtrace=backtrace,
            reason_code=exit_record.reason if exit_record else None,
            reason_name=exit_record.reason_name if exit_record else None,
            subreason_code=exit_record.subreason if exit_record else None,
            subreason_name=exit_record.subreason_name if exit_record else None,
            importance=exit_record.importance if exit_record else None,
            timestamp=exit_record.timestamp_str if exit_record else None,
            pss_bytes=exit_record.pss_bytes if exit_record else 0,
            rss_bytes=exit_record.rss_bytes if exit_record else 0,
            description=exit_record.description if exit_record else None,
            summary=f"Native Crash ({summary_sig}{pc_str}){sigill_suffix}",
            raw_trace=exit_record.trace if exit_record else None,
            matched_by=matched_by,
            ppu_compile_exits=ppu_compile_exits or [],
            cleanup_attempted=cleanup_att,
            cleanup_success=cleanup_succ,
            first_failure="FAIL_CRASH",
            affinity_check_required=affinity_check_req,
            affinity_notes=affinity_notes,
        )

    # 3. Java Exception
    is_java_crash = (
        (exit_record and exit_record.reason == 4)  # CRASH
        or "FATAL EXCEPTION:" in combined_str
    )
    if is_java_crash:
        stack = extract_java_stack_trace(exit_record.trace if exit_record else None, combined_str)
        first_line = stack.splitlines()[0] if stack else "Unhandled Java Exception"
        cleanup_att = bool(stop_reason)
        cleanup_succ = bool(stop_reason and any(k in stop_reason.lower() for k in DELIBERATE_STOP_KEYWORDS))
        return ProcessExitDiagnosis(
            classification=ExitClassification.JAVA_EXCEPTION,
            pid=exit_record.pid if exit_record else target_pid,
            process_name=exit_record.process_name if exit_record else None,
            is_ppu_compile_process=exit_record.is_ppu_compile if exit_record else False,
            status=exit_record.status if exit_record else None,
            stack_trace=stack,
            reason_code=exit_record.reason if exit_record else None,
            reason_name=exit_record.reason_name if exit_record else None,
            subreason_code=exit_record.subreason if exit_record else None,
            subreason_name=exit_record.subreason_name if exit_record else None,
            importance=exit_record.importance if exit_record else None,
            timestamp=exit_record.timestamp_str if exit_record else None,
            pss_bytes=exit_record.pss_bytes if exit_record else 0,
            rss_bytes=exit_record.rss_bytes if exit_record else 0,
            description=exit_record.description if exit_record else None,
            summary=f"Java Exception: {first_line}",
            raw_trace=exit_record.trace if exit_record else None,
            matched_by=matched_by,
            ppu_compile_exits=ppu_compile_exits or [],
            cleanup_attempted=cleanup_att,
            cleanup_success=cleanup_succ,
            first_failure="FAIL_CRASH",
        )

    # 4. Deliberate Stop (only when NO fatal crash or exception occurred)
    is_deliberate = False
    deliberate_label = ""
    # Abnormal EXIT_SELF (status != 0) is NOT clean
    is_abnormal_exit_self = (exit_record and exit_record.reason == 1 and exit_record.status != 0)

    if not is_abnormal_exit_self:
        if exit_record:
            if exit_record.reason == 1 and exit_record.status == 0:  # EXIT_SELF clean
                is_deliberate = True
                deliberate_label = "Process exited cleanly (EXIT_SELF)"
            elif exit_record.reason in (10, 11):  # USER_REQUESTED / USER_STOPPED
                is_deliberate = True
                deliberate_label = f"User Requested Stop ({exit_record.subreason_name})"
            elif exit_record.is_ppu_compile and exit_record.status in (0, 9):
                is_deliberate = True
                deliberate_label = "PPU Worker Normal Recycling"
        if not is_deliberate and stop_reason and any(k in stop_reason.lower() for k in DELIBERATE_STOP_KEYWORDS):
            # Only accept manifest deliberate stop if exit_record doesn't contradict with fatal status
            if not exit_record or exit_record.status == 0:
                is_deliberate = True
                deliberate_label = f"Deliberate Stop ({stop_reason})"

    if is_deliberate:
        method = matched_by
        if not exit_record and (matched_by == "unmatched" or matched_by == "pid_not_found_in_exit_info"):
            method = "deliberate_stop_manifest" if stop_reason else "deliberate_stop_evidence"
        return ProcessExitDiagnosis(
            classification=ExitClassification.DELIBERATE_STOP,
            pid=exit_record.pid if exit_record else target_pid,
            process_name=exit_record.process_name if exit_record else "com.zenithblue.sambas3",
            is_ppu_compile_process=exit_record.is_ppu_compile if exit_record else False,
            signal=exit_record.status if (exit_record and exit_record.reason == 2) else None,
            signal_name=exit_record.signal_name if exit_record else None,
            status=exit_record.status if exit_record else 0,
            reason_code=exit_record.reason if exit_record else None,
            reason_name=exit_record.reason_name if exit_record else "CLEAN_STOP",
            subreason_code=exit_record.subreason if exit_record else None,
            subreason_name=exit_record.subreason_name if exit_record else "NORMAL",
            importance=exit_record.importance if exit_record else None,
            timestamp=exit_record.timestamp_str if exit_record else None,
            pss_bytes=exit_record.pss_bytes if exit_record else 0,
            rss_bytes=exit_record.rss_bytes if exit_record else 0,
            description=exit_record.description if exit_record else None,
            summary=deliberate_label,
            raw_trace=exit_record.trace if exit_record else None,
            matched_by=method,
            ppu_compile_exits=ppu_compile_exits or [],
            cleanup_attempted=True,
            cleanup_success=True,
        )

    # 4b. Abnormal EXIT_SELF with non-zero exit status
    if is_abnormal_exit_self and exit_record:
        return ProcessExitDiagnosis(
            classification=ExitClassification.UNKNOWN,
            pid=exit_record.pid,
            process_name=exit_record.process_name,
            status=exit_record.status,
            reason_code=exit_record.reason,
            reason_name=exit_record.reason_name,
            summary=f"Process exited abnormally (EXIT_SELF with status {exit_record.status})",
            raw_trace=exit_record.trace,
            matched_by=matched_by,
            ppu_compile_exits=ppu_compile_exits or [],
            first_failure="FAIL_CRASH",
        )

    # 5. ANR
    is_anr = (
        (exit_record and exit_record.reason == 6)  # ANR
        or "application not responding" in combined_str.lower()
        or "anr in com.zenithblue.sambas3" in combined_str.lower()
    )
    if is_anr:
        return ProcessExitDiagnosis(
            classification=ExitClassification.ANR,
            pid=exit_record.pid if exit_record else target_pid,
            process_name=exit_record.process_name if exit_record else None,
            is_ppu_compile_process=exit_record.is_ppu_compile if exit_record else False,
            status=exit_record.status if exit_record else None,
            reason_code=exit_record.reason if exit_record else None,
            reason_name=exit_record.reason_name if exit_record else None,
            subreason_code=exit_record.subreason if exit_record else None,
            subreason_name=exit_record.subreason_name if exit_record else None,
            importance=exit_record.importance if exit_record else None,
            timestamp=exit_record.timestamp_str if exit_record else None,
            pss_bytes=exit_record.pss_bytes if exit_record else 0,
            rss_bytes=exit_record.rss_bytes if exit_record else 0,
            description=exit_record.description if exit_record else None,
            summary="Application Not Responding (ANR)",
            raw_trace=exit_record.trace if exit_record else None,
            matched_by=matched_by,
            ppu_compile_exits=ppu_compile_exits or [],
        )

    # 6. Memory Pressure Kill
    is_low_mem_reason = (
        exit_record
        and (exit_record.reason == 3 or "LOW_MEMORY" in exit_record.subreason_name.upper())
    )
    has_mem_evidence = bool(MEMORY_PRESSURE_REGEX.search(combined_str))

    if is_low_mem_reason or (exit_record and exit_record.status == 9 and has_mem_evidence):
        return ProcessExitDiagnosis(
            classification=ExitClassification.MEMORY_PRESSURE_KILL,
            pid=exit_record.pid if exit_record else target_pid,
            process_name=exit_record.process_name if exit_record else None,
            is_ppu_compile_process=exit_record.is_ppu_compile if exit_record else False,
            signal=9 if (exit_record and exit_record.reason == 2) else None,
            signal_name="SIGKILL" if (exit_record and exit_record.reason == 2) else None,
            status=exit_record.status if exit_record else None,
            reason_code=exit_record.reason if exit_record else None,
            reason_name=exit_record.reason_name if exit_record else None,
            subreason_code=exit_record.subreason if exit_record else None,
            subreason_name=exit_record.subreason_name if exit_record else None,
            importance=exit_record.importance if exit_record else None,
            timestamp=exit_record.timestamp_str if exit_record else None,
            pss_bytes=exit_record.pss_bytes if exit_record else 0,
            rss_bytes=exit_record.rss_bytes if exit_record else 0,
            description=exit_record.description if exit_record else None,
            summary="Low Memory Killer (LMKD kill / out-of-memory)",
            raw_trace=exit_record.trace if exit_record else None,
            memory_pressure_evidence=True,
            matched_by=matched_by,
            ppu_compile_exits=ppu_compile_exits or [],
        )

    # 7. System Server Kill
    is_system_server = (
        exit_record
        and (
            exit_record.reason in (7, 8, 13, 15, 16)
            or (exit_record.description and "installPackageLI" in exit_record.description)
        )
    )
    if is_system_server:
        return ProcessExitDiagnosis(
            classification=ExitClassification.SYSTEM_SERVER_KILL,
            pid=exit_record.pid if exit_record else target_pid,
            process_name=exit_record.process_name if exit_record else None,
            is_ppu_compile_process=exit_record.is_ppu_compile if exit_record else False,
            status=exit_record.status if exit_record else None,
            reason_code=exit_record.reason if exit_record else None,
            reason_name=exit_record.reason_name if exit_record else None,
            subreason_code=exit_record.subreason if exit_record else None,
            subreason_name=exit_record.subreason_name if exit_record else None,
            importance=exit_record.importance if exit_record else None,
            timestamp=exit_record.timestamp_str if exit_record else None,
            pss_bytes=exit_record.pss_bytes if exit_record else 0,
            rss_bytes=exit_record.rss_bytes if exit_record else 0,
            description=exit_record.description if exit_record else None,
            summary=f"System Server Kill ({exit_record.reason_name}: {exit_record.description or 'OS termination'})",
            raw_trace=exit_record.trace if exit_record else None,
            matched_by=matched_by,
            ppu_compile_exits=ppu_compile_exits or [],
        )

    # 8. SIGKILL without memory pressure evidence -> UNKNOWN
    if exit_record and exit_record.reason == 2 and exit_record.status == 9:
        return ProcessExitDiagnosis(
            classification=ExitClassification.UNKNOWN,
            pid=exit_record.pid,
            process_name=exit_record.process_name,
            is_ppu_compile_process=exit_record.is_ppu_compile,
            signal=9,
            signal_name="SIGKILL",
            status=9,
            reason_code=exit_record.reason,
            reason_name=exit_record.reason_name,
            subreason_code=exit_record.subreason,
            subreason_name=exit_record.subreason_name,
            importance=exit_record.importance,
            timestamp=exit_record.timestamp_str,
            pss_bytes=exit_record.pss_bytes,
            rss_bytes=exit_record.rss_bytes,
            description=exit_record.description,
            summary="SIGKILL received without memory pressure evidence (unverified cause)",
            raw_trace=exit_record.trace,
            memory_pressure_evidence=False,
            matched_by=matched_by,
            ppu_compile_exits=ppu_compile_exits or [],
        )

    # 9. Fallback: Unknown
    return ProcessExitDiagnosis(
        classification=ExitClassification.UNKNOWN,
        pid=exit_record.pid if exit_record else target_pid,
        process_name=exit_record.process_name if exit_record else None,
        is_ppu_compile_process=exit_record.is_ppu_compile if exit_record else False,
        status=exit_record.status if exit_record else None,
        reason_code=exit_record.reason if exit_record else None,
        reason_name=exit_record.reason_name if exit_record else None,
        subreason_code=exit_record.subreason if exit_record else None,
        subreason_name=exit_record.subreason_name if exit_record else None,
        importance=exit_record.importance if exit_record else None,
        timestamp=exit_record.timestamp_str if exit_record else None,
        pss_bytes=exit_record.pss_bytes if exit_record else 0,
        rss_bytes=exit_record.rss_bytes if exit_record else 0,
        description=exit_record.description if exit_record else None,
        summary="Unknown (insufficient evidence to distinguish root cause)",
        raw_trace=exit_record.trace if exit_record else None,
        matched_by=matched_by,
        ppu_compile_exits=ppu_compile_exits or [],
    )


def diagnose_evidence(
    evidence_path: Path,
    target_pid: Optional[int] = None,
    session_id: Optional[str] = None,
    package_name: str = "com.zenithblue.sambas3",
) -> ProcessExitDiagnosis:
    """Main entrypoint to diagnose crash evidence from directory or file."""
    evidence_dir: Optional[Path] = None
    exit_info_file: Optional[Path] = None

    if evidence_path.is_dir():
        evidence_dir = evidence_path
        if (evidence_dir / "exit-info.txt").is_file():
            exit_info_file = evidence_dir / "exit-info.txt"
    elif evidence_path.is_file():
        if evidence_path.name == "exit-info.txt" or "exit" in evidence_path.name:
            exit_info_file = evidence_path
        else:
            evidence_dir = evidence_path.parent
    else:
        raise FileNotFoundError(f"Path does not exist: {evidence_path}")

    # Read manifest if present in directory
    session_start_ms: Optional[int] = None
    session_end_ms: Optional[int] = None
    stop_reason: Optional[str] = None

    if evidence_dir and (evidence_dir / "run-manifest.json").is_file():
        try:
            with open(evidence_dir / "run-manifest.json", "r", encoding="utf-8") as f:
                manifest = json.load(f)
                if not target_pid and "process" in manifest:
                    p_val = manifest["process"].get("pid")
                    if p_val and str(p_val).isdigit():
                        target_pid = int(p_val)
                if not session_id and "run_id" in manifest:
                    session_id = manifest["run_id"]
                if "collected_at" in manifest:
                    try:
                        c_dt = datetime.strptime(manifest["collected_at"], "%Y-%m-%dT%H:%M:%SZ")
                        session_end_ms = int(c_dt.timestamp() * 1000)
                    except Exception:
                        pass
                if not stop_reason:
                    if "stop_reason" in manifest:
                        stop_reason = str(manifest["stop_reason"])
                    elif manifest.get("clean_stop") is True or manifest.get("process", {}).get("clean_stop") is True:
                        stop_reason = "DEBUG_STOP_GAME"
        except Exception:
            pass

    if evidence_dir and (evidence_dir / "manifest.txt").is_file():
        try:
            with open(evidence_dir / "manifest.txt", "r", encoding="utf-8") as f:
                for line in f:
                    if line.startswith("pid=") and not target_pid:
                        val = line.strip().split("=")[1]
                        if val.isdigit():
                            target_pid = int(val)
        except Exception:
            pass

    # Parse exit records
    records: List[ProcessExitRecord] = []
    if exit_info_file and exit_info_file.is_file():
        try:
            with open(exit_info_file, "r", encoding="utf-8", errors="replace") as f:
                records = parse_dumpsys_exit_info(f.read())
        except Exception:
            pass

    # Extract secondary ppu_compile exits
    ppu_compile_exits = [
        asdict(r) for r in records if r.is_ppu_compile
    ]

    # Correlate exit record
    matched_record, match_method = match_exit_record(
        records=records,
        target_pid=target_pid,
        session_id=session_id,
        package_name=package_name,
        session_start_ms=session_start_ms,
        session_end_ms=session_end_ms,
    )

    # Gather log evidence from directory
    evidence_blobs: List[str] = []
    if evidence_dir:
        for log_name in (
            "logcat-crash.log",
            "logcat-sambas3.txt",
            "logcat-process.log",
            "tombstone.txt",
            "rpcsx_backend.log",
            "cache-RPCSX.log",
            "get-samba-logs.out",
        ):
            log_file = evidence_dir / log_name
            if log_file.is_file() and log_file.stat().st_size > 0:
                try:
                    # Read up to last 128KB of each log file
                    size = log_file.stat().st_size
                    with open(log_file, "r", encoding="utf-8", errors="replace") as f:
                        if size > 128 * 1024:
                            f.seek(size - 128 * 1024)
                        evidence_blobs.append(f.read())
                except Exception:
                    pass

    combined_evidence = "\n".join(evidence_blobs)

    return classify_exit(
        exit_record=matched_record,
        evidence_text=combined_evidence,
        stop_reason=stop_reason,
        matched_by=match_method,
        target_pid=target_pid,
        ppu_compile_exits=ppu_compile_exits,
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Classify SambaS3 process exits and crash evidence offline."
    )
    parser.add_argument(
        "evidence",
        nargs="?",
        type=Path,
        default=None,
        help="Path to evidence directory or dumpsys exit-info file",
    )
    parser.add_argument(
        "--evidence-dir",
        "--evidence",
        dest="evidence_dir",
        type=Path,
        default=None,
        help="Path to evidence directory or dumpsys exit-info file",
    )
    parser.add_argument("--pid", type=int, help="Target process PID to correlate")
    parser.add_argument("--session-id", type=str, help="Target session ID")
    parser.add_argument(
        "--package",
        type=str,
        default="com.zenithblue.sambas3",
        help="Package name (default: com.zenithblue.sambas3)",
    )
    parser.add_argument("--json", action="store_true", help="Emit structured JSON output")
    parser.add_argument(
        "--output-json",
        type=Path,
        help="Write JSON output to the specified file path",
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose details")

    args = parser.parse_args()

    evidence_target = args.evidence_dir or args.evidence
    if not evidence_target:
        parser.error("Must specify evidence path (positional or via --evidence-dir)")

    try:
        diagnosis = diagnose_evidence(
            evidence_path=evidence_target,
            target_pid=args.pid,
            session_id=args.session_id,
            package_name=args.package,
        )
    except Exception as e:
        print(f"Error during diagnosis: {e}", file=sys.stderr)
        return 1

    diag_dict = asdict(diagnosis)
    diag_dict["classification"] = diagnosis.classification.value

    if args.output_json:
        with open(args.output_json, "w", encoding="utf-8") as f:
            json.dump(diag_dict, f, indent=2)

    if args.json:
        print(json.dumps(diag_dict, indent=2))
    else:
        print("=" * 60)
        print("SambaS3 Crash & Exit Diagnosis Report")
        print("=" * 60)
        print(f"Classification : {diagnosis.classification.value}")
        print(f"Summary        : {diagnosis.summary}")
        print(f"Target PID     : {diagnosis.pid or 'Unknown'}")
        print(f"Process Name   : {diagnosis.process_name or 'N/A'}")
        print(f"Match Method   : {diagnosis.matched_by}")
        if diagnosis.reason_name:
            print(f"Exit Reason    : {diagnosis.reason_name} (code={diagnosis.reason_code})")
        if diagnosis.subreason_name:
            print(f"SubReason      : {diagnosis.subreason_name} (code={diagnosis.subreason_code})")
        if diagnosis.status is not None:
            print(f"Exit Status    : {diagnosis.status}")
        if diagnosis.signal_name:
            print(f"Signal         : {diagnosis.signal_name} ({diagnosis.signal})")
        if diagnosis.pc:
            print(f"Program Counter: pc {diagnosis.pc}")
        if diagnosis.rss_bytes:
            print(f"RSS Memory     : {diagnosis.rss_bytes / (1024 * 1024):.1f} MB")
        if diagnosis.memory_pressure_evidence:
            print("Memory Pressure: CONFIRMED (LMKD / low-memory events detected)")
        if diagnosis.backtrace:
            print("\nBacktrace Frames:")
            for frame in diagnosis.backtrace[:10]:
                print(f"  {frame}")
            if len(diagnosis.backtrace) > 10:
                print(f"  ... ({len(diagnosis.backtrace) - 10} more frames)")
        if diagnosis.stack_trace:
            print("\nJava Stack Trace:")
            for line in diagnosis.stack_trace.splitlines()[:12]:
                print(f"  {line}")
        if diagnosis.ppu_compile_exits:
            print(f"\nSecondary :ppu_compile Exits Detected: {len(diagnosis.ppu_compile_exits)}")
            for p in diagnosis.ppu_compile_exits[:3]:
                print(f"  PID {p['pid']}: {p['reason_name']} (status={p['status']}) at {p['timestamp_str']}")
        print("=" * 60)

    return 0


if __name__ == "__main__":
    sys.exit(main())
