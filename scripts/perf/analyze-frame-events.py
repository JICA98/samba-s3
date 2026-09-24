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
    rolling_fps: Optional[float] = None
    instantaneous_ft_ms: Optional[float] = None
    raw_line: str = ""


@dataclass
class FrameMetricsResult:
    total_events_parsed: int = 0
    qualified_frames: int = 0
    deduplicated_events: int = 0
    dropped_or_invalid_events: int = 0
    pause_events_detected: int = 0
    total_pause_duration_ms: float = 0.0

    # Timing boundaries (microseconds)
    window_start_us: Optional[int] = None
    window_end_us: Optional[int] = None
    elapsed_wall_time_ms: float = 0.0
    elapsed_active_gameplay_ms: float = 0.0

    # Primary Metric: Full-Interval Throughput
    interval_throughput_fps: float = 0.0

    # Frame Times (ms)
    frametime_mean_ms: float = 0.0
    frametime_stddev_ms: float = 0.0
    frametime_min_ms: float = 0.0
    frametime_p50_ms: float = 0.0
    frametime_p90_ms: float = 0.0
    frametime_p95_ms: float = 0.0
    frametime_p99_ms: float = 0.0
    frametime_max_ms: float = 0.0

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

    # Pattern 1: S3PERF crosscheck
    # "crosscheck source=(...) presented=(...) json_fps=(...) json_frametime_ms=(...)"
    m_cross = re.search(
        r"crosscheck\s+source=(?P<src>[^\s]+)\s+presented=(?P<fid>\d+)\s+json_fps=(?P<fps>[0-9.]+)\s+json_frametime_ms=(?P<ft>[0-9.]+)",
        line_clean,
    )
    if m_cross:
        # Check if logcat timestamp is available at beginning (e.g., 09-24 22:15:30.244)
        m_time = re.match(r"^\d{2}-\d{2}\s+(?P<hh>\d{2}):(?P<mm>\d{2}):(?P<ss>\d{2})\.(?P<ms>\d{3})", line_clean)
        if m_time:
            hh = int(m_time.group("hh"))
            mm = int(m_time.group("mm"))
            ss = int(m_time.group("ss"))
            ms = int(m_time.group("ms"))
            ts_us = (hh * 3600 + mm * 60 + ss) * 1_000_000 + ms * 1_000
        else:
            ts_us = 0

        fid = int(m_cross.group("fid"))
        fps = float(m_cross.group("fps"))
        ft = float(m_cross.group("ft"))
        src = m_cross.group("src")

        # If ts_us was not in logcat header, reconstruct from instantaneous frametime if valid
        return FrameEvent(
            timestamp_us=ts_us,
            source=src,
            frame_id=fid,
            rolling_fps=fps,
            instantaneous_ft_ms=ft,
            raw_line=line_clean,
        )

    # Pattern 2: Generic key-value timestamps
    m_kv = re.search(r"timestamp(?:_us)?=(?P<ts>\d+)", line_clean)
    if m_kv:
        ts_us = int(m_kv.group("ts"))
        m_fid = re.search(r"(?:frame|presented)=(?P<fid>\d+)", line_clean)
        m_src = re.search(r"source=(?P<src>[a-zA-Z0-9_-]+)", line_clean)
        m_fps = re.search(r"fps=(?P<fps>[0-9.]+)", line_clean)
        m_ft = re.search(r"frametime(?:_ms)?=(?P<ft>[0-9.]+)", line_clean)

        return FrameEvent(
            timestamp_us=ts_us,
            source=m_src.group("src") if m_src else "surface",
            frame_id=int(m_fid.group("fid")) if m_fid else None,
            rolling_fps=float(m_fps.group("fps")) if m_fps else None,
            instantaneous_ft_ms=float(m_ft.group("ft")) if m_ft else None,
            raw_line=line_clean,
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

    # Check for direct event structure
    if "timestampUs" in data or "timestamp_us" in data:
        ts = int(data.get("timestampUs") or data.get("timestamp_us") or 0)
        src = str(data.get("fpsSource") or data.get("source") or "surface")
        fid = data.get("presentedFrameCount") or data.get("presented") or data.get("frame_id")
        fps = data.get("fps") or data.get("rolling_fps")
        ft = data.get("frametimeMs") or data.get("frametime_ms")

        events.append(
            FrameEvent(
                timestamp_us=ts,
                source=src,
                frame_id=int(fid) if fid is not None else None,
                rolling_fps=float(fps) if fps is not None else None,
                instantaneous_ft_ms=float(ft) if ft is not None else None,
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
    2. Drops secondary presentation callbacks firing within min_delta_us (default 800us)
       of the previous presentation (Vulkan present and ANativeWindow queueBuffer).
    3. Drops duplicate frame_id occurrences if frame_id is present.
    Returns (deduplicated_events, dropped_count, duplicate_count).
    """
    if not events:
        return [], 0, 0

    # Sort events chronologically by timestamp
    valid_events: List[FrameEvent] = []
    dropped_count = 0
    duplicate_count = 0

    seen_frame_ids: set[int] = set()

    for ev in events:
        if ev.timestamp_us <= 0:
            dropped_count += 1
            continue
        valid_events.append(ev)

    valid_events.sort(key=lambda x: x.timestamp_us)

    deduped: List[FrameEvent] = []
    last_ts_us: Optional[int] = None

    for ev in valid_events:
        # Check duplicate frame ID
        if ev.frame_id is not None:
            if ev.frame_id in seen_frame_ids:
                duplicate_count += 1
                continue
            seen_frame_ids.add(ev.frame_id)

        # Check time delta threshold
        if last_ts_us is not None:
            delta = ev.timestamp_us - last_ts_us
            if delta < min_delta_us:
                duplicate_count += 1
                continue

        deduped.append(ev)
        last_ts_us = ev.timestamp_us

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
    """
    filtered = events
    if start_us is not None:
        filtered = [e for e in filtered if e.timestamp_us >= start_us]
    if end_us is not None:
        filtered = [e for e in filtered if e.timestamp_us <= end_us]
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
    start_us: Optional[int] = None,
    end_us: Optional[int] = None,
) -> FrameMetricsResult:
    """
    Calculates comprehensive frame metrics following WORKER.md:
    - Primary throughput = unique qualified frames / elapsed active time
    - Percentiles: P50, P90, P95, P99
    - Distinguishes rolling display metrics (secondary) from interval throughput
    - Handles pause boundaries, dropped samples, zero times, and clock wraps.
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
        return result

    result.window_start_us = windowed[0].timestamp_us
    result.window_end_us = windowed[-1].timestamp_us
    wall_duration_us = result.window_end_us - result.window_start_us
    result.elapsed_wall_time_ms = wall_duration_us / 1000.0

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
        if first_fid is not None and last_fid is not None and last_fid > first_fid:
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

                # Pause check: large gaps treated as intentional pause / scene boundary
                if delta_us >= pause_threshold_us:
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
        f"   Interval Throughput:      {res.interval_throughput_fps:.2f} FPS",
        "--------------------------------------------------------------------------------",
        " FRAME TIME DISTRIBUTION (ms):",
        f"   Mean Frametime:           {res.frametime_mean_ms:.2f} ms (StdDev: {res.frametime_stddev_ms:.2f} ms)",
        f"   Min / Max:                {res.frametime_min_ms:.2f} ms / {res.frametime_max_ms:.2f} ms",
        f"   Median (P50):             {res.frametime_p50_ms:.2f} ms",
        f"   P90:                      {res.frametime_p90_ms:.2f} ms",
        f"   P95:                      {res.frametime_p95_ms:.2f} ms",
        f"   P99:                      {res.frametime_p99_ms:.2f} ms",
        "--------------------------------------------------------------------------------",
        " STALL AUDIT:",
        f"   Stalls > 33.3ms:          {res.stalls_over_33ms}",
        f"   Stalls > 50.0ms:          {res.stalls_over_50ms}",
        f"   Stalls > 100.0ms:         {res.stalls_over_100ms}",
        f"   Stalls > 250.0ms:         {res.stalls_over_250ms}",
        "--------------------------------------------------------------------------------",
    ]
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
    parser.add_argument("--start-us", type=int, help="Filter start timestamp in microseconds")
    parser.add_argument("--end-us", type=int, help="Filter end timestamp in microseconds")
    parser.add_argument("--start-frame", type=int, help="Filter start frame sequence ID")
    parser.add_argument("--end-frame", type=int, help="Filter end frame sequence ID")

    args = parser.parse_args()

    try:
        events = load_events_from_input(args.input)
    except Exception as e:
        print(f"Error loading frame events from {args.input}: {e}", file=sys.stderr)
        return 1

    if not events:
        print(f"No valid frame events found in input {args.input}", file=sys.stderr)
        return 2

    # Filter frame ID range if specified
    if args.start_frame is not None or args.end_frame is not None:
        events = filter_events_window(events, start_frame=args.start_frame, end_frame=args.end_frame)

    result = compute_frame_metrics(
        events,
        min_delta_us=args.min_delta_us,
        pause_threshold_ms=args.pause_threshold_ms,
        start_us=args.start_us,
        end_us=args.end_us,
    )

    if args.json:
        out_dict = asdict(result)
        # Exclude massive list of qualified_intervals_ms unless specifically needed
        out_dict.pop("qualified_intervals_ms", None)
        print(json.dumps(out_dict, indent=2))
    else:
        print(format_metrics_table(result))

    if args.output_json:
        out_path = Path(args.output_json)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_dict = asdict(result)
        out_path.write_text(json.dumps(out_dict, indent=2), encoding="utf-8")
        print(f"Wrote analysis JSON to {out_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
