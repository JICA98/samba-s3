#!/usr/bin/env python3
"""
analyze-frame-events.py — Robust frame telemetry & timestamp event analyzer for SambaS3.

Parses raw presentation timestamps, applies deduplication (e.g. 800us window between Vulkan
present and ANativeWindow queueBuffer), filters scene boundaries, handles pauses/clock resets,
computes full-interval throughput (primary metric) separated from rolling display metrics (secondary),
and calculates statistical percentiles (P50, P95, P99, stalls).

Conforms to SambaS3 WORKER.md Section 5, 15, and 16.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, List, Optional, Tuple


@dataclass
class FrameEvent:
    timestamp_us: int
    source: str = "surface"
    frame_id: Optional[int] = None
    session_id: Optional[str] = None
    surface_id: Optional[str] = None
    rolling_fps: Optional[float] = None
    instantaneous_ft_ms: Optional[float] = None
    is_explicit_pause: bool = False
    raw_line: str = ""
    process_id: Optional[int] = None
    process_start_time: Optional[str] = None
    run_id: Optional[str] = None
    monotonic_ns: Optional[int] = None
    raw_guest_frame: bool = False
    guest_frame_record: bool = False
    synthetic: bool = False
    phase: Optional[str] = None


@dataclass
class FrameMetricsResult:
    total_events_parsed: int = 0
    qualified_frames: int = 0
    deduplicated_events: int = 0
    dropped_or_invalid_events: int = 0
    pause_events_detected: int = 0
    total_pause_duration_ms: float = 0.0

    # Sequence and stream integrity
    lost_frames_count: int = 0
    sequence_loss_detected: bool = False
    session_resets_detected: int = 0
    surface_recreations_detected: int = 0

    # Timing boundaries (microseconds)
    window_start_us: Optional[int] = None
    window_end_us: Optional[int] = None
    elapsed_wall_time_ms: float = 0.0
    elapsed_active_gameplay_ms: float = 0.0
    leading_capture_gap_ms: float = 0.0
    trailing_capture_gap_ms: float = 0.0

    # Primary Metric: Full-Interval Throughput
    interval_throughput_fps: float = 0.0
    wall_throughput_fps: float = 0.0
    active_throughput_fps: float = 0.0

    # Frame Times (ms)
    frametime_mean_ms: float = 0.0
    frametime_stddev_ms: float = 0.0
    frametime_min_ms: float = 0.0
    frametime_p50_ms: float = 0.0
    frametime_p90_ms: float = 0.0
    frametime_p95_ms: float = 0.0
    frametime_p99_ms: float = 0.0
    frametime_max_ms: float = 0.0

    # Tail coverage and sampling classification
    full_frame_percentiles_available: bool = True
    sampled_interval_p50_ms: Optional[float] = None
    sampled_interval_p90_ms: Optional[float] = None
    sampled_interval_p95_ms: Optional[float] = None
    sampled_interval_p99_ms: Optional[float] = None
    error: Optional[str] = None


    # Stalls and Thresholds
    stalls_over_33ms: int = 0
    stalls_over_50ms: int = 0
    stalls_over_100ms: int = 0
    stalls_over_250ms: int = 0

    # Secondary Metric: Rolling Display Telemetry (from UI / JSON crosscheck)
    rolling_fps_sample_count: int = 0
    rolling_fps_mean: Optional[float] = None
    rolling_fps_p50: Optional[float] = None
    rolling_fps_p95: Optional[float] = None
    rolling_fps_min: Optional[float] = None
    rolling_fps_max: Optional[float] = None

    # Thermals and Hardware Frequencies
    thermal_throttling_events: int = 0
    thermal_status_levels: List[str] = field(default_factory=list)
    cpu_frequencies_mhz: List[float] = field(default_factory=list)
    gpu_frequencies_mhz: List[float] = field(default_factory=list)

    # Individual qualified intervals (ms) for downstream visualization
    qualified_intervals_ms: List[float] = field(default_factory=list)


def parse_logcat_line(line: str) -> Optional[FrameEvent]:
    """
    Parses a logcat line for frame presentation evidence:
    - S3PERF crosscheck lines:
      '09-24 22:15:30.244  5454  5498 D S3PERF  : crosscheck source=surface presented=3564 json_fps=8.47363 json_frametime_ms=90.684'
    - S3PERF queueBuffer / timestamp lines:
      'queueBuffer interceptor fired win=0x... total=3564'
    - Generic timestamp lines:
      'frame_presented timestamp_us=12345678 source=vk_present frame=100'
    """
    line_clean = line.strip()
    if not line_clean:
        return None

    # Retain PID from Android logcat threadtime or epoch-formatted records.
    # Strict benchmark analysis uses it to bind guest-frame samples to the
    # exact process instance launched for this run.
    m_pid = re.match(
        r"^(?:\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}|\d{9,11}\.\d{3,6})\s+(?P<pid>\d+)\s+\d+\s+",
        line_clean,
    )
    process_id = int(m_pid.group("pid")) if m_pid else None

    # Pattern 1: S3PERF crosscheck
    # "crosscheck source=(...) presented=(...) json_fps=(...) json_frametime_ms=(...)"
    m_cross = re.search(
        r"crosscheck\s+source=(?P<src>[^\s]+)\s+presented=(?P<fid>\d+)\s+json_fps=(?P<fps>[0-9.]+)\s+json_frametime_ms=(?P<ft>[0-9.]+)",
        line_clean,
    )
    if m_cross:
        # Check if logcat timestamp is available at beginning (e.g., 09-24 22:15:30.244 or epoch 1790357440.935)
        m_time = re.match(r"^\d{2}-\d{2}\s+(?P<hh>\d{2}):(?P<mm>\d{2}):(?P<ss>\d{2})\.(?P<ms>\d{3})", line_clean)
        m_epoch = re.match(r"^\s*(?P<epoch>\d{9,11}\.\d{3,6})", line_clean)
        if m_time:
            hh = int(m_time.group("hh"))
            mm = int(m_time.group("mm"))
            ss = int(m_time.group("ss"))
            ms = int(m_time.group("ms"))
            ts_us = (hh * 3600 + mm * 60 + ss) * 1_000_000 + ms * 1_000
        elif m_epoch:
            ts_us = int(float(m_epoch.group("epoch")) * 1_000_000)
        else:
            ts_us = 0

        fid = int(m_cross.group("fid"))
        fps = float(m_cross.group("fps"))
        ft = float(m_cross.group("ft"))
        src = m_cross.group("src")

        m_sess = re.search(r"session(?:_id)?=(?P<sid>[^\s]+)", line_clean)
        m_surf = re.search(r"(?:surface|win)=(?P<surf>[^\s]+)", line_clean)

        return FrameEvent(
            timestamp_us=ts_us,
            source=src,
            frame_id=fid,
            session_id=m_sess.group("sid") if m_sess else None,
            surface_id=m_surf.group("surf") if m_surf else None,
            rolling_fps=fps,
            instantaneous_ft_ms=ft,
            raw_line=line_clean,
            process_id=process_id,
        )

    # Pattern 2: Generic key-value timestamps
    m_kv = re.search(r"timestamp(?:_us)?=(?P<ts>\d+)", line_clean)
    if m_kv:
        m_fid = re.search(r"(?:frame|presented)=(?P<fid>\d+)", line_clean)
        m_src = re.search(r"source=(?P<src>[a-zA-Z0-9_-]+)", line_clean)
        if m_fid is None and m_src is None and "S3PERF" not in line_clean:
            return None

        ts_us = int(m_kv.group("ts"))
        m_fps = re.search(r"fps=(?P<fps>[0-9.]+)", line_clean)
        m_ft = re.search(r"frametime(?:_ms)?=(?P<ft>[0-9.]+)", line_clean)
        m_sess = re.search(r"session(?:_id)?=(?P<sid>[^\s]+)", line_clean)
        m_surf = re.search(r"(?:surface|win)=(?P<surf>[^\s]+)", line_clean)

        return FrameEvent(
            timestamp_us=ts_us,
            source=m_src.group("src") if m_src else "surface",
            frame_id=int(m_fid.group("fid")) if m_fid else None,
            session_id=m_sess.group("sid") if m_sess else None,
            surface_id=m_surf.group("surf") if m_surf else None,
            rolling_fps=float(m_fps.group("fps")) if m_fps else None,
            instantaneous_ft_ms=float(m_ft.group("ft")) if m_ft else None,
            raw_line=line_clean,
            process_id=process_id,
        )

    return None


def parse_perf_json(text: str) -> List[FrameEvent]:
    """
    Parses native perf JSON exports from native-lib.cpp getPerfMetricsJson() or raw event dumps.
    """
    events: List[FrameEvent] = []
    text_clean = text.strip()
    if not text_clean:
        return events

    try:
        data = json.loads(text_clean)
    except json.JSONDecodeError:
        # Try JSON Lines
        for line in text_clean.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                item = json.loads(line)
                events.extend(parse_json_object(item))
            except json.JSONDecodeError:
                continue
        return events

    if isinstance(data, list):
        for item in data:
            events.extend(parse_json_object(item))
    elif isinstance(data, dict):
        events.extend(parse_json_object(data))

    return events


def parse_json_object(data: dict[str, Any]) -> List[FrameEvent]:
    events: List[FrameEvent] = []
    monotonic_ns_value = data.get("monotonicNs", data.get("monotonic_ns"))
    is_raw_guest_frame = (
        data.get("recordType") == "guest_frame"
        and data.get("schemaVersion") == 1
        and monotonic_ns_value is not None
        and data.get("guestFrameId", data.get("guest_frame_id")) is not None
        and data.get("processId", data.get("process_id")) is not None
        and data.get("processStartTime", data.get("process_start_time")) is not None
        and data.get("runId", data.get("run_id")) is not None
        and data.get("sessionId", data.get("session_id")) is not None
        and data.get("phase") is not None
    )

    # Check for direct event structure
    if "timestampUs" in data or "timestamp_us" in data or monotonic_ns_value is not None:
        monotonic_ns = int(monotonic_ns_value) if monotonic_ns_value is not None else None
        ts = monotonic_ns // 1000 if monotonic_ns is not None else int(data.get("timestampUs") or data.get("timestamp_us") or 0)
        src = str(data.get("fpsSource") or data.get("source") or "surface")
        fid = data.get("guestFrameId", data.get("guest_frame_id"))
        if fid is None:
            fid = data.get("presentedFrameCount") or data.get("presented") or data.get("frame_id")
        fps = data.get("fps") or data.get("rolling_fps")
        ft = data.get("frametimeMs") or data.get("frametime_ms")
        sid = data.get("sessionId") or data.get("session_id")
        surf = data.get("surfaceId") or data.get("surface_id") or data.get("window")

        events.append(
            FrameEvent(
                timestamp_us=ts,
                source=src,
                frame_id=int(fid) if fid is not None else None,
                session_id=str(sid) if sid is not None else None,
                surface_id=str(surf) if surf is not None else None,
                rolling_fps=float(fps) if fps is not None else None,
                instantaneous_ft_ms=float(ft) if ft is not None else None,
                process_id=int(data.get("processId", data.get("process_id"))) if data.get("processId", data.get("process_id")) is not None else None,
                process_start_time=str(data.get("processStartTime", data.get("process_start_time"))) if data.get("processStartTime", data.get("process_start_time")) is not None else None,
                run_id=str(data.get("runId", data.get("run_id"))) if data.get("runId", data.get("run_id")) is not None else None,
                monotonic_ns=monotonic_ns,
                raw_guest_frame=is_raw_guest_frame,
                guest_frame_record=data.get("recordType") == "guest_frame",
                synthetic=bool(data.get("synthetic", data.get("fixture", False))),
                phase=str(data.get("phase")) if data.get("phase") is not None else None,
            )
        )

    # Check for nested samples arrays (native-lib.cpp export)
    # frametimeSamples: [{"timestampUs": ..., "frametimeMs": ...}]
    if "frametimeSamples" in data and isinstance(data["frametimeSamples"], list):
        for s in data["frametimeSamples"]:
            ts = int(s.get("timestampUs") or 0)
            ft = float(s.get("frametimeMs") or 0.0)
            events.append(FrameEvent(timestamp_us=ts, instantaneous_ft_ms=ft, source="frametime_sample"))

    # fpsSamples: [{"timestampUs": ..., "fps": ...}]
    if "fpsSamples" in data and isinstance(data["fpsSamples"], list):
        for s in data["fpsSamples"]:
            ts = int(s.get("timestampUs") or 0)
            fps = float(s.get("fps") or 0.0)
            events.append(FrameEvent(timestamp_us=ts, rolling_fps=fps, source="fps_sample"))

    return events


def validate_raw_guest_frame_events(
    events: List[FrameEvent], *, expected_pid: int, expected_run_id: str,
    expected_process_start_time: str, expected_session_id: str,
    window_start_ns: int, window_end_ns: int,
) -> tuple[List[FrameEvent], Optional[str]]:
    """Accept complete, timestamped guest frames for the exact process/session window."""
    if not expected_session_id or window_start_ns <= 0 or window_end_ns <= window_start_ns:
        return [], "INCONCLUSIVE_CAPTURE_WINDOW_OR_SESSION_MISSING"

    if any(event.guest_frame_record and not event.raw_guest_frame for event in events):
        return [], "INCONCLUSIVE_MALFORMED_GUEST_FRAME_RECORD"
    if any(not event.guest_frame_record for event in events):
        return [], "INCONCLUSIVE_NONRAW_EVENT_IN_GUEST_FRAME_EXPORT"
    if any(event.monotonic_ns is None or not window_start_ns <= event.monotonic_ns <= window_end_ns for event in events):
        return [], "INCONCLUSIVE_GUEST_FRAME_OUTSIDE_CAPTURE_WINDOW"

    raw_window = [
        event for event in events
        if event.raw_guest_frame
        and event.monotonic_ns is not None
        and window_start_ns <= event.monotonic_ns <= window_end_ns
    ]
    if any(
        event.process_id != expected_pid
        or event.process_start_time != expected_process_start_time
        or event.run_id != expected_run_id
        for event in raw_window
    ):
        return [], "INCONCLUSIVE_PROCESS_OR_RUN_IDENTITY_MISMATCH"
    if any(event.session_id != expected_session_id for event in raw_window):
        return [], "INCONCLUSIVE_SESSION_ID_MISSING_OR_CHANGED"
    if any(event.synthetic for event in raw_window):
        return [], "INCONCLUSIVE_SYNTHETIC_GUEST_FRAME_RECORD"
    if any(event.source != "emu_flip" or event.phase != "gameplay" for event in raw_window):
        return [], "INCONCLUSIVE_GUEST_FRAME_SOURCE_OR_PHASE_MISMATCH"

    accepted = raw_window
    if len(accepted) < 3:
        return [], "INCONCLUSIVE_RAW_GUEST_FRAME_EVIDENCE_MISSING"

    frame_ids = [event.frame_id for event in accepted]
    timestamps = [event.monotonic_ns for event in accepted]
    if any(value is None for value in frame_ids + timestamps):
        return [], "INCONCLUSIVE_RAW_GUEST_FRAME_ID_OR_TIME_MISSING"
    if any(b <= a for a, b in zip(frame_ids, frame_ids[1:])):
        return [], "INCONCLUSIVE_GUEST_FRAME_ID_DUPLICATE_OR_RESET"
    if any(b != a + 1 for a, b in zip(frame_ids, frame_ids[1:])):
        return [], "INCONCLUSIVE_GUEST_FRAME_SEQUENCE_GAP"
    if any(b <= a for a, b in zip(timestamps, timestamps[1:])):
        return [], "INCONCLUSIVE_GUEST_MONOTONIC_TIME_DUPLICATE_OR_RESET"

    # Strict mode never accepts telemetry-derived FPS or frametime. The
    # interval values are computed from these monotonic timestamps below.
    for event in accepted:
        event.instantaneous_ft_ms = None
        event.rolling_fps = None
    return accepted, None


def load_capture_window(path: str, *, expected_pid: int, expected_run_id: str,
                        expected_process_start_time: str) -> tuple[Optional[dict[str, Any]], Optional[str]]:
    """Load the device-side CLOCK_MONOTONIC capture window emitted with raw frames."""
    try:
        raw_bytes = Path(path).read_bytes()
        data = json.loads(raw_bytes.decode("utf-8"))
    except (OSError, json.JSONDecodeError):
        return None, "INCONCLUSIVE_CAPTURE_WINDOW_MANIFEST_MISSING_OR_INVALID"
    if not isinstance(data, dict):
        return None, "INCONCLUSIVE_CAPTURE_WINDOW_MANIFEST_MISSING_OR_INVALID"
    required = (
        data.get("schemaVersion") == 1
        and data.get("recordType") == "guest_frame_capture_window"
        and data.get("clockDomain") == "CLOCK_MONOTONIC"
        and data.get("captureComplete") is True
        and data.get("runId") == expected_run_id
        and data.get("processId") == expected_pid
        and str(data.get("processStartTime")) == expected_process_start_time
        and isinstance(data.get("sessionId"), str)
        and bool(data.get("sessionId"))
        and isinstance(data.get("rawGuestFrameJsonlSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", data.get("rawGuestFrameJsonlSha256", "")) is not None
        and isinstance(data.get("captureAdmissionSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", data.get("captureAdmissionSha256", "")) is not None
    )
    start_ns = data.get("windowStartMonotonicNs")
    end_ns = data.get("windowEndMonotonicNs")
    first_frame = data.get("firstGuestFrameId")
    last_frame = data.get("lastGuestFrameId")
    frame_count = data.get("guestFrameCount")
    if (
        not required
        or type(start_ns) is not int
        or type(end_ns) is not int
        or start_ns <= 0
        or end_ns <= start_ns
        or type(first_frame) is not int
        or type(last_frame) is not int
        or type(frame_count) is not int
        or frame_count < 3
        or last_frame - first_frame + 1 != frame_count
    ):
        return None, "INCONCLUSIVE_CAPTURE_WINDOW_MANIFEST_MISSING_OR_INVALID"
    data["_path"] = str(Path(path))
    data["_sha256"] = hashlib.sha256(raw_bytes).hexdigest()
    return data, None


def load_capture_admission(path: str, *, expected_pid: int, expected_run_id: str,
                           expected_process_start_time: str,
                           expected_session_id: str,
                           requested_duration_ns: int) -> tuple[Optional[dict[str, Any]], Optional[str]]:
    """Load the separately admitted, device-clock gameplay window for this run."""
    try:
        raw_bytes = Path(path).read_bytes()
        data = json.loads(raw_bytes.decode("utf-8"))
    except (OSError, json.JSONDecodeError):
        return None, "INCONCLUSIVE_CAPTURE_ADMISSION_MISSING_OR_INVALID"
    if not isinstance(data, dict):
        return None, "INCONCLUSIVE_CAPTURE_ADMISSION_MISSING_OR_INVALID"
    start_ns = data.get("windowStartMonotonicNs")
    end_ns = data.get("windowEndMonotonicNs")
    producer_hash = data.get("producerSourceSha256")
    duration_ns = data.get("requestedDurationNs")
    valid = (
        data.get("schemaVersion") == 1
        and data.get("recordType") == "guest_frame_capture_admission"
        and data.get("clockDomain") == "CLOCK_MONOTONIC"
        and data.get("runId") == expected_run_id
        and data.get("processId") == expected_pid
        and str(data.get("processStartTime")) == expected_process_start_time
        and isinstance(data.get("sessionId"), str)
        and data.get("sessionId") == expected_session_id
        and type(start_ns) is int and type(end_ns) is int and start_ns > 0 and end_ns > start_ns
        and type(duration_ns) is int and duration_ns == requested_duration_ns
        and end_ns - start_ns == requested_duration_ns
        and isinstance(data.get("producer"), str) and bool(data.get("producer"))
        and isinstance(producer_hash, str) and re.fullmatch(r"[0-9a-f]{64}", producer_hash) is not None
    )
    if not valid:
        return None, "INCONCLUSIVE_CAPTURE_ADMISSION_MISSING_OR_INVALID"
    data["_path"] = str(Path(path))
    data["_sha256"] = hashlib.sha256(raw_bytes).hexdigest()
    return data, None


def load_raw_guest_frame_jsonl(path: str) -> tuple[List[FrameEvent], Optional[str]]:
    """Strictly parse an export containing only complete JSON guest-frame rows."""
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError:
        return [], "INCONCLUSIVE_RAW_GUEST_FRAME_EXPORT_MISSING"
    events: List[FrameEvent] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            return [], f"INCONCLUSIVE_RAW_GUEST_FRAME_EXPORT_INVALID_JSON_LINE_{line_number}"
        if not isinstance(item, dict) or item.get("recordType") != "guest_frame":
            return [], f"INCONCLUSIVE_NONRAW_EVENT_IN_GUEST_FRAME_EXPORT_LINE_{line_number}"
        parsed = parse_json_object(item)
        if len(parsed) != 1 or not parsed[0].raw_guest_frame:
            return [], f"INCONCLUSIVE_MALFORMED_GUEST_FRAME_RECORD_LINE_{line_number}"
        events.extend(parsed)
    if not events:
        return [], "INCONCLUSIVE_RAW_GUEST_FRAME_EVIDENCE_MISSING"
    return events, None


def apply_strict_window_metrics(result: FrameMetricsResult, events: List[FrameEvent], window: dict[str, Any]) -> None:
    """Use the declared full device-monotonic window, including its edge gaps."""
    start_ns = int(window["windowStartMonotonicNs"])
    end_ns = int(window["windowEndMonotonicNs"])
    duration_ns = end_ns - start_ns
    frame_ns = [int(event.monotonic_ns) for event in events]
    gaps_ms = [((b - a) / 1_000_000.0) for a, b in zip(frame_ns, frame_ns[1:])]
    leading_ms = max(0.0, (frame_ns[0] - start_ns) / 1_000_000.0)
    trailing_ms = max(0.0, (end_ns - frame_ns[-1]) / 1_000_000.0)
    edge_gaps = ([leading_ms] if leading_ms > 0 else []) + ([trailing_ms] if trailing_ms > 0 else [])
    duration_s = duration_ns / 1_000_000_000.0

    result.window_start_us = start_ns // 1000
    result.window_end_us = end_ns // 1000
    result.elapsed_wall_time_ms = duration_ns / 1_000_000.0
    result.elapsed_active_gameplay_ms = result.elapsed_wall_time_ms
    result.leading_capture_gap_ms = leading_ms
    result.trailing_capture_gap_ms = trailing_ms
    result.qualified_frames = len(events)
    result.interval_throughput_fps = len(events) / duration_s if duration_s > 0 else 0.0
    result.wall_throughput_fps = result.interval_throughput_fps
    result.active_throughput_fps = result.interval_throughput_fps
    result.full_frame_percentiles_available = True
    result.qualified_intervals_ms = gaps_ms
    result.frametime_min_ms = min(gaps_ms) if gaps_ms else 0.0
    result.frametime_max_ms = max(gaps_ms) if gaps_ms else 0.0
    result.frametime_mean_ms = sum(gaps_ms) / len(gaps_ms) if gaps_ms else 0.0
    variance = sum((x - result.frametime_mean_ms) ** 2 for x in gaps_ms) / len(gaps_ms) if gaps_ms else 0.0
    result.frametime_stddev_ms = math.sqrt(variance)
    result.frametime_p50_ms = percentile(gaps_ms, 50.0)
    result.frametime_p90_ms = percentile(gaps_ms, 90.0)
    result.frametime_p95_ms = percentile(gaps_ms, 95.0)
    result.frametime_p99_ms = percentile(gaps_ms, 99.0)
    scored_gaps = gaps_ms + edge_gaps
    result.stalls_over_33ms = sum(interval > 33.333 for interval in scored_gaps)
    result.stalls_over_50ms = sum(interval > 50.0 for interval in scored_gaps)
    result.stalls_over_100ms = sum(interval > 100.0 for interval in scored_gaps)
    result.stalls_over_250ms = sum(interval > 250.0 for interval in scored_gaps)


def validate_capture_window_frames(events: List[FrameEvent], window: dict[str, Any]) -> Optional[str]:
    """Bind the complete raw stream to the exact window record's frame range."""
    if not events:
        return "INCONCLUSIVE_RAW_GUEST_FRAME_EVIDENCE_MISSING"
    if (
        events[0].frame_id != window["firstGuestFrameId"]
        or events[-1].frame_id != window["lastGuestFrameId"]
        or len(events) != window["guestFrameCount"]
    ):
        return "INCONCLUSIVE_CAPTURE_WINDOW_FRAME_RANGE_MISMATCH"
    return None


def parse_csv_timestamps(text: str) -> List[FrameEvent]:
    """
    Parses CSV/TSV data containing timestamps and optional frame IDs / frametimes.
    """
    events: List[FrameEvent] = []
    lines = text.strip().splitlines()
    if not lines:
        return events

    header = [h.strip().lower() for h in re.split(r"[,;\t]", lines[0])]
    ts_idx = -1
    fid_idx = -1
    fps_idx = -1
    ft_idx = -1

    col_name = ""
    for i, col in enumerate(header):
        if col in ("timestamp_us", "timestamp", "time_us", "ts_us", "time", "timestamp_ms", "time_ms", "timestamp_s", "time_s"):
            ts_idx = i
            col_name = col
        elif col in ("frame", "frame_id", "presented", "fid"):
            fid_idx = i
        elif col in ("fps", "rolling_fps"):
            fps_idx = i
        elif col in ("frametime_ms", "ft_ms", "frametime"):
            ft_idx = i

    start_line = 1 if ts_idx >= 0 else 0
    if ts_idx < 0:
        ts_idx = 0

    for line in lines[start_line:]:
        parts = [p.strip() for p in re.split(r"[,;\t]", line)]
        if len(parts) <= ts_idx or not parts[ts_idx]:
            continue
        try:
            raw_ts = float(parts[ts_idx])
            if col_name in ("timestamp_us", "time_us", "ts_us"):
                ts_us = int(raw_ts)
            elif col_name in ("timestamp_ms", "time_ms", "ts_ms"):
                ts_us = int(raw_ts * 1_000.0)
            elif col_name in ("timestamp_s", "time_s") or raw_ts < 100_000:
                ts_us = int(raw_ts * 1_000_000.0)
            else:
                ts_us = int(raw_ts)

            fid = int(parts[fid_idx]) if (fid_idx >= 0 and len(parts) > fid_idx and parts[fid_idx].isdigit()) else None
            fps = float(parts[fps_idx]) if (fps_idx >= 0 and len(parts) > fps_idx and parts[fps_idx]) else None
            ft = float(parts[ft_idx]) if (ft_idx >= 0 and len(parts) > ft_idx and parts[ft_idx]) else None

            events.append(
                FrameEvent(
                    timestamp_us=ts_us,
                    frame_id=fid,
                    rolling_fps=fps,
                    instantaneous_ft_ms=ft,
                )
            )
        except ValueError:
            continue

    return events


def deduplicate_events(events: List[FrameEvent], min_delta_us: int = 800) -> Tuple[List[FrameEvent], int, int]:
    """
    Deduplicates raw events:
    1. Discards invalid / non-positive timestamps (t <= 0).
    2. Groups by session_id so multiple sessions or clock resets are not sorted
       into one artificial timeline.
    3. Drops duplicate frame_id occurrences within the same session.
    4. Drops secondary presentation callbacks firing within min_delta_us (default 800us)
       of the previous presentation when frame_id matches or is unnumbered. Valid distinct
       frame IDs are never dropped.
    Returns (deduplicated_events, dropped_count, duplicate_count).
    """
    if not events:
        return [], 0, 0

    valid_events: List[FrameEvent] = [e for e in events if e.timestamp_us > 0]
    dropped_count = len(events) - len(valid_events)
    duplicate_count = 0

    # Group by session_id preserving session appearance order
    sessions_order: List[Optional[str]] = []
    events_by_session: dict[Optional[str], List[FrameEvent]] = {}
    for ev in valid_events:
        sess = ev.session_id
        if sess not in events_by_session:
            sessions_order.append(sess)
            events_by_session[sess] = []
        events_by_session[sess].append(ev)

    deduped: List[FrameEvent] = []

    for sess in sessions_order:
        s_events = events_by_session[sess]
        s_events.sort(key=lambda x: x.timestamp_us)
        seen_frame_ids: set[int] = set()
        last_ev: Optional[FrameEvent] = None

        for ev in s_events:
            # Check duplicate frame ID within the same session
            if ev.frame_id is not None:
                if ev.frame_id in seen_frame_ids:
                    duplicate_count += 1
                    continue
                seen_frame_ids.add(ev.frame_id)

            if last_ev is not None:
                delta = ev.timestamp_us - last_ev.timestamp_us
                # If both are unnumbered and fired within min_delta_us: duplicate callback
                if ev.frame_id is None and last_ev.frame_id is None and delta < min_delta_us:
                    duplicate_count += 1
                    continue
                # If dual-source callbacks (e.g. vk_present vs surface) fired within min_delta_us
                if ev.source != last_ev.source and delta < min_delta_us and (ev.frame_id == last_ev.frame_id or ev.frame_id is None):
                    duplicate_count += 1
                    continue

            deduped.append(ev)
            last_ev = ev

    return deduped, dropped_count, duplicate_count


def filter_events_window(
    events: List[FrameEvent],
    start_us: Optional[int] = None,
    end_us: Optional[int] = None,
    start_frame: Optional[int] = None,
    end_frame: Optional[int] = None,
) -> List[FrameEvent]:
    """
    Filters events to a specific scene window by timestamp or frame ID.
    Handles clock-domain normalization between epoch microseconds (>1e14)
    and time-of-day microseconds (<1e11).
    """
    filtered = events

    effective_start_us = start_us
    effective_end_us = end_us

    if events and (start_us is not None or end_us is not None):
        # Check if events use time-of-day timestamps (< 86_400_000_000 us, i.e. 24h)
        events_use_tod = any(0 < e.timestamp_us < 86_400_000_000 for e in events[:50])
        bounds_use_epoch = (start_us is not None and start_us > 1_000_000_000_000_000) or (end_us is not None and end_us > 1_000_000_000_000_000)

        if events_use_tod and bounds_use_epoch:
            import datetime
            if start_us is not None and start_us > 1_000_000_000_000_000:
                dt_s = datetime.datetime.fromtimestamp(start_us / 1_000_000.0)
                effective_start_us = (dt_s.hour * 3600 + dt_s.minute * 60 + dt_s.second) * 1_000_000 + dt_s.microsecond
            if end_us is not None and end_us > 1_000_000_000_000_000:
                dt_e = datetime.datetime.fromtimestamp(end_us / 1_000_000.0)
                effective_end_us = (dt_e.hour * 3600 + dt_e.minute * 60 + dt_e.second) * 1_000_000 + dt_e.microsecond
                if effective_start_us is not None and effective_end_us < effective_start_us:
                    # Midnight boundary wrap
                    effective_end_us += 86_400_000_000

    if effective_start_us is not None:
        filtered = [e for e in filtered if e.timestamp_us >= effective_start_us]
    if effective_end_us is not None:
        filtered = [e for e in filtered if e.timestamp_us <= effective_end_us]
    if start_frame is not None:
        filtered = [e for e in filtered if e.frame_id is None or e.frame_id >= start_frame]
    if end_frame is not None:
        filtered = [e for e in filtered if e.frame_id is None or e.frame_id <= end_frame]
    return filtered



def percentile(data: List[float], p: float) -> float:
    """Computes the p-th percentile (0 <= p <= 100) using linear interpolation."""
    if not data:
        return 0.0
    sorted_data = sorted(data)
    n = len(sorted_data)
    if n == 1:
        return sorted_data[0]
    rank = (p / 100.0) * (n - 1)
    k = math.floor(rank)
    d = rank - k
    if k >= n - 1:
        return sorted_data[-1]
    return sorted_data[k] + d * (sorted_data[k + 1] - sorted_data[k])


def compute_frame_metrics(
    events: List[FrameEvent],
    min_delta_us: int = 800,
    pause_threshold_ms: float = 1000.0,
    allow_inferred_pauses: bool = False,
    start_us: Optional[int] = None,
    end_us: Optional[int] = None,
) -> FrameMetricsResult:
    """
    Calculates comprehensive frame metrics following WORKER.md:
    - Primary throughput = unique qualified frames / elapsed active time
    - Percentiles: P50, P90, P95, P99
    - Distinguishes rolling display metrics (secondary) from interval throughput
    - Handles pause boundaries, dropped samples, zero times, and clock wraps.
    - Stalls inside the window are retained (lowering throughput & counted in tails)
      unless explicitly marked as intentional pauses.
    """
    result = FrameMetricsResult(total_events_parsed=len(events))

    # Deduplicate and validate
    deduped, dropped_cnt, dup_cnt = deduplicate_events(events, min_delta_us=min_delta_us)
    result.dropped_or_invalid_events = dropped_cnt
    result.deduplicated_events = dup_cnt

    # Apply scene window bounds
    windowed = filter_events_window(deduped, start_us=start_us, end_us=end_us)
    if len(windowed) < 2:
        result.qualified_frames = len(windowed)
        result.error = "NO_EVENTS_IN_WINDOW" if not windowed else "INSUFFICIENT_EVENTS_IN_WINDOW"
        return result

    # Sequence loss and gap accounting
    last_fid: Optional[int] = None
    for ev in windowed:
        if ev.frame_id is not None:
            if last_fid is not None and ev.frame_id > last_fid + 1:
                gap = ev.frame_id - last_fid - 1
                result.lost_frames_count += gap
                result.sequence_loss_detected = True
            last_fid = ev.frame_id

    # Session and surface recreation accounting
    last_session: Optional[str] = None
    last_surface: Optional[str] = None
    for ev in windowed:
        if ev.session_id is not None:
            if last_session is not None and ev.session_id != last_session:
                result.session_resets_detected += 1
            last_session = ev.session_id
        if ev.surface_id is not None:
            if last_surface is not None and ev.surface_id != last_surface:
                result.surface_recreations_detected += 1
            last_surface = ev.surface_id

    result.window_start_us = windowed[0].timestamp_us
    result.window_end_us = windowed[-1].timestamp_us
    wall_duration_us = result.window_end_us - result.window_start_us
    result.elapsed_wall_time_ms = wall_duration_us / 1000.0

    # If explicit scene boundaries provided, wall time conforms to requested window
    if start_us is not None and end_us is not None and end_us > start_us:
        explicit_window_us = end_us - start_us
        wall_duration_us = explicit_window_us
        result.elapsed_wall_time_ms = explicit_window_us / 1000.0

    pause_threshold_us = pause_threshold_ms * 1000.0
    active_duration_us = 0
    pause_duration_us = 0
    pause_count = 0

    frametimes_ms: List[float] = []
    rolling_fps_vals: List[float] = []

    has_telemetry_ft = sum(1 for e in windowed if e.instantaneous_ft_ms is not None and e.instantaneous_ft_ms > 0) >= (len(windowed) // 2)

    if has_telemetry_ft:
        # Telemetry sampling stream (e.g. S3PERF periodic crosschecks)
        # Frametimes are directly provided by each sample, and throughput is calculated
        # from cumulative presented frame delta across the window.
        result.full_frame_percentiles_available = False
        for ev in windowed:
            if ev.rolling_fps is not None and ev.rolling_fps > 0:
                rolling_fps_vals.append(ev.rolling_fps)
            if ev.instantaneous_ft_ms is not None and ev.instantaneous_ft_ms > 0:
                frametimes_ms.append(ev.instantaneous_ft_ms)
                ft_ms = ev.instantaneous_ft_ms
                if ft_ms > 33.333:
                    result.stalls_over_33ms += 1
                if ft_ms > 50.0:
                    result.stalls_over_50ms += 1
                if ft_ms > 100.0:
                    result.stalls_over_100ms += 1
                if ft_ms > 250.0:
                    result.stalls_over_250ms += 1

        active_duration_us = wall_duration_us
        first_fid = next((e.frame_id for e in windowed if e.frame_id is not None), None)
        last_fid = next((e.frame_id for e in reversed(windowed) if e.frame_id is not None), None)
        if first_fid is not None and last_fid is not None and last_fid >= first_fid:
            if result.sequence_loss_detected:
                total_frames = max(0, (last_fid - first_fid) - result.lost_frames_count)
            else:
                total_frames = last_fid - first_fid
        else:
            total_frames = len(frametimes_ms)

        result.qualified_frames = total_frames
        result.elapsed_active_gameplay_ms = active_duration_us / 1000.0
        result.pause_events_detected = 0
        result.total_pause_duration_ms = 0.0
        result.qualified_intervals_ms = frametimes_ms
        if active_duration_us > 0:
            result.interval_throughput_fps = total_frames / (active_duration_us / 1_000_000.0)
        result.active_throughput_fps = result.interval_throughput_fps
        if wall_duration_us > 0:
            result.wall_throughput_fps = total_frames / (wall_duration_us / 1_000_000.0)

        if frametimes_ms:
            result.sampled_interval_p50_ms = percentile(frametimes_ms, 50.0)
            result.sampled_interval_p90_ms = percentile(frametimes_ms, 90.0)
            result.sampled_interval_p95_ms = percentile(frametimes_ms, 95.0)
            result.sampled_interval_p99_ms = percentile(frametimes_ms, 99.0)
    else:
        for i in range(len(windowed)):
            ev = windowed[i]
            if ev.rolling_fps is not None and ev.rolling_fps > 0:
                rolling_fps_vals.append(ev.rolling_fps)

            if i > 0:
                prev_ev = windowed[i - 1]
                delta_us = ev.timestamp_us - prev_ev.timestamp_us

                # Non-monotonic clock step check
                if delta_us <= 0:
                    result.dropped_or_invalid_events += 1
                    continue

                # Explicit pause check vs Stalls:
                # Per WORKER.md Section 6.2, stalls inside the gameplay window are retained
                # unless explicitly marked as intentional pauses.
                is_explicit_pause = ev.is_explicit_pause or (allow_inferred_pauses and delta_us >= pause_threshold_us)
                if is_explicit_pause:
                    pause_count += 1
                    pause_duration_us += delta_us
                    continue

                active_duration_us += delta_us
                ft_ms = delta_us / 1000.0
                frametimes_ms.append(ft_ms)

                # Stall classification
                if ft_ms > 33.333:
                    result.stalls_over_33ms += 1
                if ft_ms > 50.0:
                    result.stalls_over_50ms += 1
                if ft_ms > 100.0:
                    result.stalls_over_100ms += 1
                if ft_ms > 250.0:
                    result.stalls_over_250ms += 1

        # Check if stream is sparse (e.g. mean interval > 100ms without pauses) or has sequence loss
        is_sparse = False
        if frametimes_ms:
            mean_ft = sum(frametimes_ms) / len(frametimes_ms)
            if mean_ft > 100.0 and pause_count == 0:
                is_sparse = True

        if result.sequence_loss_detected or is_sparse or len(windowed) < 5:
            result.full_frame_percentiles_available = False
            if frametimes_ms:
                result.sampled_interval_p50_ms = percentile(frametimes_ms, 50.0)
                result.sampled_interval_p90_ms = percentile(frametimes_ms, 90.0)
                result.sampled_interval_p95_ms = percentile(frametimes_ms, 95.0)
                result.sampled_interval_p99_ms = percentile(frametimes_ms, 99.0)
        else:
            result.full_frame_percentiles_available = True


        result.qualified_frames = len(frametimes_ms) + 1 if frametimes_ms else len(windowed)
        result.pause_events_detected = pause_count
        result.total_pause_duration_ms = pause_duration_us / 1000.0
        result.elapsed_active_gameplay_ms = active_duration_us / 1000.0
        result.qualified_intervals_ms = frametimes_ms

        # Primary Metric: Full-Interval Throughput
        # FPS = qualified frame count / active duration in seconds
        if active_duration_us > 0 and frametimes_ms:
            result.interval_throughput_fps = len(frametimes_ms) / (active_duration_us / 1_000_000.0)
        elif wall_duration_us > 0:
            result.interval_throughput_fps = (len(windowed) - 1) / (wall_duration_us / 1_000_000.0)

        result.active_throughput_fps = result.interval_throughput_fps
        if wall_duration_us > 0:
            if start_us is not None and end_us is not None and end_us > start_us:
                result.wall_throughput_fps = result.qualified_frames / (wall_duration_us / 1_000_000.0)
            else:
                n_frames = len(frametimes_ms) if frametimes_ms else (len(windowed) - 1)
                result.wall_throughput_fps = n_frames / (wall_duration_us / 1_000_000.0)

    # Frame Times Distribution
    if frametimes_ms:
        result.frametime_min_ms = min(frametimes_ms)
        result.frametime_max_ms = max(frametimes_ms)
        result.frametime_mean_ms = sum(frametimes_ms) / len(frametimes_ms)
        variance = sum((x - result.frametime_mean_ms) ** 2 for x in frametimes_ms) / len(frametimes_ms)
        result.frametime_stddev_ms = math.sqrt(variance)

        result.frametime_p50_ms = percentile(frametimes_ms, 50.0)
        result.frametime_p90_ms = percentile(frametimes_ms, 90.0)
        result.frametime_p95_ms = percentile(frametimes_ms, 95.0)
        result.frametime_p99_ms = percentile(frametimes_ms, 99.0)

    # Secondary Metric: Rolling Display Telemetry
    if rolling_fps_vals:
        result.rolling_fps_sample_count = len(rolling_fps_vals)
        result.rolling_fps_min = min(rolling_fps_vals)
        result.rolling_fps_max = max(rolling_fps_vals)
        result.rolling_fps_mean = sum(rolling_fps_vals) / len(rolling_fps_vals)
        result.rolling_fps_p50 = percentile(rolling_fps_vals, 50.0)
        result.rolling_fps_p95 = percentile(rolling_fps_vals, 95.0)

    return result


def parse_thermal_log(content: str) -> Tuple[int, List[str], List[float], List[float]]:
    throttling_events = 0
    thermal_levels: List[str] = []
    cpu_freqs: List[float] = []
    gpu_freqs: List[float] = []
    has_explicit_transitions = False

    for line in content.splitlines():
        line_str = line.strip()
        if not line_str:
            continue

        # Look for explicit transitions (e.g. logcat traces or thermalservice transition events)
        m_trans = re.search(r"(?:onThermalStatusChanged|thermal_status_changed)[\s:=]+(?:status=)?(?P<st>\d+)", line_str, re.IGNORECASE)
        if m_trans:
            has_explicit_transitions = True
            st_val = int(m_trans.group("st"))
            if st_val > 0:
                throttling_events += 1
                thermal_levels.append(f"STATUS_{st_val}")
            continue

        m_status = re.search(r"mStatus=(?P<st>\d+)", line_str)
        if m_status:
            st_val = int(m_status.group("st"))
            if st_val > 0:
                thermal_levels.append(f"STATUS_{st_val}")

        m_named = re.search(r"THERMAL_STATUS_(?P<st>[A-Z_]+)", line_str)
        if m_named:
            st_name = m_named.group("st")
            thermal_levels.append(st_name)

        # Check CoolingDevice lines: CoolingDevice{mValue=..., mName=...}
        m_cd = re.search(r"CoolingDevice\{mValue=(?P<val>[0-9.]+)[^}]*mName=(?P<name>[^}]+)\}", line_str)
        if m_cd:
            name = m_cd.group("name").lower()
            val = float(m_cd.group("val"))
            if "cpufreq" in name or "cpu" in name:
                cpu_freqs.append(val)
            elif "gpu" in name or "devfreq" in name or "kgsl" in name:
                gpu_freqs.append(val)
            continue

        m_cpu = re.search(r"(?:cpufreq[-_]cpu\d+|cpu_freq)[\s=]+(?:mValue=)?(?P<freq>[0-9.]+)", line_str)
        if m_cpu:
            try:
                cpu_freqs.append(float(m_cpu.group("freq")))
            except ValueError:
                pass

        m_gpu = re.search(r"(?:devfreq[-_a-zA-Z0-9.,]+3d0|gpu_freq)[\s=]+(?:mValue=)?(?P<freq>[0-9.]+)", line_str)
        if m_gpu:
            try:
                gpu_freqs.append(float(m_gpu.group("freq")))
            except ValueError:
                pass

    if not has_explicit_transitions:
        # Static dumpsys snapshot: throttling_events represents the count of active throttled sensors
        throttling_events = len([l for l in thermal_levels if l not in ("STATUS_0", "NONE", "LIGHT")])

    return throttling_events, list(dict.fromkeys(thermal_levels)), cpu_freqs, gpu_freqs



def format_metrics_table(res: FrameMetricsResult) -> str:
    lines = [
        "================================================================================",
        " SambaS3 Frame Events & Performance Analysis Report",
        "================================================================================",
        f" Total Events Parsed:        {res.total_events_parsed}",
        f" Qualified Frames:           {res.qualified_frames}",
        f" Deduplicated / Redundant:   {res.deduplicated_events}",
        f" Dropped / Invalid:          {res.dropped_or_invalid_events}",
        f" Pauses Detected:            {res.pause_events_detected} (Total pause time: {res.total_pause_duration_ms:.2f} ms)",
        f" Elapsed Wall Duration:      {res.elapsed_wall_time_ms:.2f} ms ({res.elapsed_wall_time_ms/1000.0:.2f} s)",
        f" Elapsed Active Gameplay:    {res.elapsed_active_gameplay_ms:.2f} ms ({res.elapsed_active_gameplay_ms/1000.0:.2f} s)",
        "--------------------------------------------------------------------------------",
        " PRIMARY METRIC: Full-Interval Active Throughput",
        f"   Active Throughput:        {res.active_throughput_fps:.2f} FPS",
        f"   Wall-Time Throughput:     {res.wall_throughput_fps:.2f} FPS",
        "--------------------------------------------------------------------------------",
        " FRAME TIME DISTRIBUTION (ms):",
        f"   Mean Frametime:           {res.frametime_mean_ms:.2f} ms (StdDev: {res.frametime_stddev_ms:.2f} ms)",
        f"   Min / Max:                {res.frametime_min_ms:.2f} ms / {res.frametime_max_ms:.2f} ms",
        f"   Median (P50):             {res.frametime_p50_ms:.2f} ms",
        f"   P90:                      {res.frametime_p90_ms:.2f} ms",
        f"   P95:                      {res.frametime_p95_ms:.2f} ms",
        f"   P99:                      {res.frametime_p99_ms:.2f} ms",
    ]
    if not res.full_frame_percentiles_available:
        lines.extend([
            "   [!] NOTE: Percentiles derived from sampled telemetry crosschecks.",
            "             Full-frame interval tails unavailable.",
        ])
    lines.extend([
        "--------------------------------------------------------------------------------",
        " STREAM INTEGRITY & STALL AUDIT:",
        f"   Sequence Loss:            {res.lost_frames_count} missing frame(s)" if res.sequence_loss_detected else "   Sequence Integrity:       Intact (no gaps detected)",
        f"   Session Resets:           {res.session_resets_detected}",
        f"   Surface Recreations:      {res.surface_recreations_detected}",
        f"   Stalls > 33.3ms:          {res.stalls_over_33ms}",
        f"   Stalls > 50.0ms:          {res.stalls_over_50ms}",
        f"   Stalls > 100.0ms:         {res.stalls_over_100ms}",
        f"   Stalls > 250.0ms:         {res.stalls_over_250ms}",
        "--------------------------------------------------------------------------------",
    ])
    if res.thermal_status_levels or res.thermal_throttling_events > 0:
        lines.extend([
            " THERMAL & HARDWARE STATE:",
            f"   Thermal Levels:           {', '.join(res.thermal_status_levels)}" if res.thermal_status_levels else "   Thermal Levels:           Nominal",
            f"   Throttling Events:        {res.thermal_throttling_events}",
            "--------------------------------------------------------------------------------",
        ])
    if res.rolling_fps_sample_count > 0:
        lines.extend([
            " SECONDARY METRIC: Rolling Display Telemetry (UI Snapshot Samples):",
            f"   Samples Count:            {res.rolling_fps_sample_count}",
            f"   Mean Rolling FPS:         {res.rolling_fps_mean:.2f} FPS" if res.rolling_fps_mean is not None else "",
            f"   Median (P50) Rolling FPS: {res.rolling_fps_p50:.2f} FPS" if res.rolling_fps_p50 is not None else "",
            f"   P95 Rolling FPS:          {res.rolling_fps_p95:.2f} FPS" if res.rolling_fps_p95 is not None else "",
            f"   Min / Max Rolling FPS:    {res.rolling_fps_min:.2f} / {res.rolling_fps_max:.2f} FPS" if res.rolling_fps_min is not None else "",
            "--------------------------------------------------------------------------------",
        ])
    lines.append("================================================================================")
    return "\n".join(lines)


def load_events_from_input(source: str) -> List[FrameEvent]:
    events: List[FrameEvent] = []
    content = ""
    if source == "-" or not source:
        content = sys.stdin.read()
    else:
        path = Path(source)
        if not path.exists():
            raise FileNotFoundError(f"Input file not found: {source}")
        content = path.read_text(encoding="utf-8", errors="replace")

    # Try JSON
    if content.strip().startswith(("{", "[")):
        events = parse_perf_json(content)
        if events:
            return events

    # Try logcat parsing line by line
    for line in content.splitlines():
        ev = parse_logcat_line(line)
        if ev is not None:
            events.append(ev)

    if events:
        return events

    # Try CSV / TSV fallback
    return parse_csv_timestamps(content)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Analyze raw SambaS3 frame timestamps, logcat crosschecks, and perf telemetry."
    )
    parser.add_argument("input", nargs="?", default="-", help="Input file path or '-' for stdin")
    parser.add_argument("--json", action="store_true", help="Output machine-readable JSON")
    parser.add_argument("--output-json", help="Path to write JSON analysis output")
    parser.add_argument("--min-delta-us", type=int, default=800, help="Deduplication minimum delta in microseconds (default: 800us)")
    parser.add_argument("--pause-threshold-ms", type=float, default=1000.0, help="Pause detection threshold in milliseconds (default: 1000ms)")
    parser.add_argument("--allow-inferred-pauses", action="store_true", help="Allow inferring pauses from gaps >= pause-threshold-ms (default: False, treats gaps as stalls)")
    parser.add_argument("--start-us", type=int, help="Filter start timestamp in microseconds")
    parser.add_argument("--end-us", type=int, help="Filter end timestamp in microseconds")
    parser.add_argument("--start-frame", type=int, help="Filter start frame sequence ID")
    parser.add_argument("--end-frame", type=int, help="Filter end frame sequence ID")
    parser.add_argument("--thermal-log", help="Path to thermal service dump or log for thermal/freq correlation")

    parser.add_argument("--require-guest-frames", "--require-raw-guest-frames", dest="require_guest_frames", action="store_true",
                        help="Require raw, run/process/session-bound guest-frame records; presentation telemetry is diagnostic only")
    parser.add_argument("--expected-pid", type=int, help="Launched process PID required by --require-guest-frames")
    parser.add_argument("--run-id", help="Benchmark run/session identity for strict analysis")
    parser.add_argument("--process-start-time", help="Launched process start time for strict analysis")
    parser.add_argument("--capture-window-json", help="Device CLOCK_MONOTONIC capture-window sidecar required for strict analysis")
    parser.add_argument("--capture-admission-json", help="Independent device-clock run/session/window admission record required for strict analysis")
    parser.add_argument("--expected-session-id", help="App session independently captured by the runner's boot log")
    parser.add_argument("--requested-window-seconds", type=float, help="Runner-declared gameplay window; admission must match exactly")
    args = parser.parse_args()

    if args.require_guest_frames and any(value is not None for value in (
        args.start_us, args.end_us, args.start_frame, args.end_frame,
    )):
        print("Error: strict guest-frame analysis uses only its capture-window sidecar; caller filters are not allowed", file=sys.stderr)
        return 2

    try:
        events = load_events_from_input(args.input)
    except Exception as e:
        print(f"Error loading frame events from {args.input}: {e}", file=sys.stderr)
        return 1

    input_sha256 = None
    if args.input != "-":
        try:
            input_sha256 = hashlib.sha256(Path(args.input).read_bytes()).hexdigest()
        except OSError:
            input_sha256 = None

    measurement_identity = None
    strict_window = None
    if args.require_guest_frames:
        if (args.expected_pid is None or args.expected_pid <= 0 or not args.run_id
                or not args.process_start_time or not args.capture_window_json or not args.capture_admission_json
                or not args.expected_session_id or args.requested_window_seconds is None
                or args.requested_window_seconds <= 0):
            print("Error: strict analysis requires process/run/session identity, capture window/admission, and requested gameplay duration", file=sys.stderr)
            return 2
        events, strict_error = load_raw_guest_frame_jsonl(args.input) if args.input != "-" else ([], "INCONCLUSIVE_STRICT_INPUT_MUST_BE_A_HASHED_FILE")
        if strict_error is None and args.input != "-" and hashlib.sha256(Path(args.input).read_bytes()).hexdigest() != input_sha256:
            strict_error = "INCONCLUSIVE_RAW_EXPORT_CHANGED_DURING_ANALYSIS"
        if strict_error is None:
            strict_window, strict_error = load_capture_window(
                args.capture_window_json,
                expected_pid=args.expected_pid,
                expected_run_id=args.run_id,
                expected_process_start_time=args.process_start_time,
            )
        if strict_error is None:
            admission, strict_error = load_capture_admission(
                args.capture_admission_json,
                expected_pid=args.expected_pid,
                expected_run_id=args.run_id,
                expected_process_start_time=args.process_start_time,
                expected_session_id=args.expected_session_id,
                requested_duration_ns=round(args.requested_window_seconds * 1_000_000_000),
            )
        else:
            admission = None
        if strict_error is None and strict_window is not None and strict_window["rawGuestFrameJsonlSha256"] != input_sha256:
            strict_error = "INCONCLUSIVE_CAPTURE_WINDOW_RAW_EXPORT_HASH_MISMATCH"
        if strict_error is None and strict_window is not None and admission is not None:
            if strict_window.get("captureAdmissionSha256") != admission["_sha256"]:
                strict_error = "INCONCLUSIVE_CAPTURE_WINDOW_ADMISSION_HASH_MISMATCH"
            elif (
                strict_window.get("sessionId") != admission.get("sessionId")
                or strict_window.get("windowStartMonotonicNs") != admission.get("windowStartMonotonicNs")
                or strict_window.get("windowEndMonotonicNs") != admission.get("windowEndMonotonicNs")
            ):
                strict_error = "INCONCLUSIVE_CAPTURE_WINDOW_ADMISSION_MISMATCH"
        if strict_error is None and strict_window is not None:
            events, strict_error = validate_raw_guest_frame_events(
                events,
                expected_pid=args.expected_pid,
                expected_run_id=args.run_id,
                expected_process_start_time=args.process_start_time,
                expected_session_id=strict_window["sessionId"],
                window_start_ns=int(strict_window["windowStartMonotonicNs"]),
                window_end_ns=int(strict_window["windowEndMonotonicNs"]),
            )
        if strict_error is None and strict_window is not None:
            strict_error = validate_capture_window_frames(events, strict_window)
        if strict_error:
            report = {
                "evaluation_status": "INCONCLUSIVE_EVIDENCE",
                "inconclusive_reason": strict_error,
                "qualified_frames": 0,
                "measurement_identity": {
                    "run_id": args.run_id,
                    "process_id": args.expected_pid,
                    "process_start_time": args.process_start_time,
                    "accepted_frame_source": "emu_flip",
                },
                "input_sha256": input_sha256,
                "capture_window_sha256": strict_window.get("_sha256") if strict_window else None,
                "capture_admission_sha256": admission.get("_sha256") if admission else None,
            }
            if args.output_json:
                out_path = Path(args.output_json)
                out_path.parent.mkdir(parents=True, exist_ok=True)
                out_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            if args.json:
                print(json.dumps(report, indent=2))
            print(f"Error: {strict_error}", file=sys.stderr)
            return 2
        measurement_identity = {
            "run_id": args.run_id,
            "process_id": args.expected_pid,
            "process_start_time": args.process_start_time,
            "accepted_frame_source": "emu_flip",
            "session_id": events[0].session_id,
            "capture_window_monotonic_ns": [
                strict_window["windowStartMonotonicNs"],
                strict_window["windowEndMonotonicNs"],
            ],
            "capture_window_sha256": strict_window["_sha256"],
            "capture_admission_sha256": admission["_sha256"],
            "guest_frame_first": events[0].frame_id,
            "guest_frame_last": events[-1].frame_id,
            "guest_frame_records": len(events),
        }
    elif not events:
        print(f"No valid frame events found in input {args.input}", file=sys.stderr)
        return 2

    # Filter frame ID range if specified
    if args.start_frame is not None or args.end_frame is not None:
        events = filter_events_window(events, start_frame=args.start_frame, end_frame=args.end_frame)

    result = compute_frame_metrics(
        events,
        min_delta_us=0 if args.require_guest_frames else args.min_delta_us,
        pause_threshold_ms=args.pause_threshold_ms,
        allow_inferred_pauses=args.allow_inferred_pauses,
        start_us=(int(strict_window["windowStartMonotonicNs"]) // 1000) if strict_window else args.start_us,
        end_us=(int(strict_window["windowEndMonotonicNs"]) // 1000) if strict_window else args.end_us,
    )
    if strict_window is not None:
        apply_strict_window_metrics(result, events, strict_window)

    if args.thermal_log:
        thermal_path = Path(args.thermal_log)
        if thermal_path.exists():
            t_events, t_levels, c_freqs, g_freqs = parse_thermal_log(
                thermal_path.read_text(encoding="utf-8", errors="replace")
            )
            result.thermal_throttling_events = t_events
            result.thermal_status_levels = t_levels
            result.cpu_frequencies_mhz = c_freqs
            result.gpu_frequencies_mhz = g_freqs

    if args.json:
        out_dict = asdict(result)
        if measurement_identity is not None:
            out_dict["measurement_identity"] = measurement_identity
            out_dict["evaluation_status"] = "QUALIFIED" if result.qualified_frames >= 3 else "INCONCLUSIVE_EVIDENCE"
            out_dict["input_sha256"] = input_sha256
            out_dict["capture_window_sha256"] = measurement_identity["capture_window_sha256"]
        # Exclude massive list of qualified_intervals_ms unless specifically needed
        out_dict.pop("qualified_intervals_ms", None)
        print(json.dumps(out_dict, indent=2))
    else:
        print(format_metrics_table(result))

    if args.output_json:
        out_path = Path(args.output_json)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_dict = asdict(result)
        if measurement_identity is not None:
            out_dict["measurement_identity"] = measurement_identity
            out_dict["evaluation_status"] = "QUALIFIED" if result.qualified_frames >= 3 else "INCONCLUSIVE_EVIDENCE"
            out_dict["input_sha256"] = input_sha256
            out_dict["capture_window_sha256"] = measurement_identity["capture_window_sha256"]
        out_path.write_text(json.dumps(out_dict, indent=2), encoding="utf-8")
        print(f"Wrote analysis JSON to {out_path}")

    if result.qualified_frames < 2 and (args.start_us is not None or args.end_us is not None):
        print(f"Error: Insufficient or no frame events in specified window: {result.error}", file=sys.stderr)
        return 2

    return 0



if __name__ == "__main__":
    sys.exit(main())
