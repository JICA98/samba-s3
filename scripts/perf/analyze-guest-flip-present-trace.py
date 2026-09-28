#!/usr/bin/env python3
"""Summarize source-backed GOW3 guest-flip-to-present cadence as diagnostics only."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import sys
from pathlib import Path
from typing import Any

LOGCAT = re.compile(
    r"^\s*(?P<epoch>\d+\.\d+)\s+(?P<pid>\d+)\s+(?P<tid>\d+)\s+"
    r"(?P<level>[VDIWEF])\s+(?P<tag>[^:]+):\s+(?P<message>.*)$"
)
TRACE = re.compile(
    r"^GOW3_GUEST_PRESENT session_ns=(?P<session>\d+) guest_flip_seq=(?P<seq>\d+) "
    r"guest_buffer_id=(?P<buffer>\d+) renderer_frame_id=(?P<frame>\d+) "
    r"flip_ns=(?P<flip>\d+) present_begin_ns=(?P<begin>\d+) "
    r"present_end_ns=(?P<end>\d+) vk_result=(?P<result>-?\d+) "
    r"wsi_accepted=(?P<accepted>[01])$"
)


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return value


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    items = sorted(values)
    if len(items) == 1:
        return items[0]
    rank = (p / 100.0) * (len(items) - 1)
    lo = math.floor(rank)
    fraction = rank - lo
    return items[lo] + fraction * (items[lo + 1] - items[lo])


def _has_app_binding(lines: list[str], pid: int, request_id: str, app_session_id: str) -> bool:
    request_seen = False
    app_session_seen = False
    for line in lines:
        match = LOGCAT.match(line)
        if not match or int(match.group("pid")) != pid:
            continue
        tag = match.group("tag").strip()
        message = match.group("message")
        if tag == "S3BOOT" and "boot started" in message and f"request_id={request_id}" in message:
            request_seen = True
        if tag in {"S3SESSION", "S3EXIT"} and (
            f"session={app_session_id}" in message or f"sessionId={app_session_id}" in message
        ):
            app_session_seen = True
    return request_seen and app_session_seen


def analyze(log_path: Path, run_path: Path, window_path: Path, producer_source_path: Path) -> dict[str, Any]:
    raw = log_path.read_bytes()
    raw_sha = hashlib.sha256(raw).hexdigest()
    log_text = raw.decode("utf-8", errors="strict")
    lines = log_text.splitlines()
    run = load_json(run_path)
    window = load_json(window_path)
    launch = run.get("launch_identity")
    if not isinstance(launch, dict):
        raise ValueError("Run manifest lacks launch_identity")
    pid = int(launch.get("pid", 0))
    process_start = str(launch.get("start_time", ""))
    run_id = str(run.get("run_id", ""))
    if pid <= 0 or not process_start or not run_id:
        raise ValueError("Run manifest lacks PID, process start time, or run ID")

    required = {
        "schema_version": 1,
        "record_type": "guest_flip_present_capture",
        "clock_domain": "CLOCK_MONOTONIC",
        "run_id": run_id,
        "process_id": pid,
        "process_start_time": process_start,
        "log_sha256": raw_sha,
    }
    for key, expected in required.items():
        if window.get(key) != expected:
            raise ValueError(f"Capture manifest {key} does not match the run/log identity")

    request_id = str(window.get("app_request_id", ""))
    app_session_id = str(window.get("app_session_id", ""))
    renderer_session = window.get("renderer_session_ns")
    start_ns = window.get("window_start_monotonic_ns")
    end_ns = window.get("window_end_monotonic_ns")
    if not request_id or not app_session_id or type(renderer_session) is not int:
        raise ValueError("Capture manifest lacks exact app request/session or renderer session")
    if type(start_ns) is not int or type(end_ns) is not int or start_ns <= 0 or end_ns <= start_ns:
        raise ValueError("Capture manifest has invalid monotonic window bounds")
    if not _has_app_binding(lines, pid, request_id, app_session_id):
        raise ValueError("Raw log lacks the exact PID-bound app boot request/session markers")

    producer_sha = sha256_file(producer_source_path)
    source_claim = window.get("producer_source_sha256")
    if source_claim != producer_sha:
        raise ValueError("Capture manifest producer source hash does not match inspected source")

    all_session_rows: list[dict[str, int]] = []
    other_pid_same_renderer = 0
    for line_number, line in enumerate(lines, start=1):
        match = LOGCAT.match(line)
        if not match or match.group("tag").strip() != "RPCS3":
            continue
        msg = match.group("message")
        if not msg.startswith("GOW3_GUEST_PRESENT "):
            continue
        values = TRACE.match(msg)
        if not values:
            raise ValueError("Malformed GOW3_GUEST_PRESENT row in source log")
        row = {k: int(v) for k, v in values.groupdict().items()}
        row["pid"] = int(match.group("pid"))
        row["source_line"] = line_number
        if row["session"] == renderer_session and row["pid"] != pid:
            other_pid_same_renderer += 1
        if row["pid"] == pid and row["session"] == renderer_session:
            all_session_rows.append(row)
    if other_pid_same_renderer:
        raise ValueError("Renderer session appeared under a different process PID")
    if not all_session_rows:
        raise ValueError("No GOW3 guest-present rows match PID and renderer session")

    in_window = [r for r in all_session_rows if start_ns <= r["flip"] <= end_ns]
    outside_window = len(all_session_rows) - len(in_window)
    if not in_window:
        raise ValueError("No matching guest-present rows fall inside the attested monotonic window")
    if any(b["flip"] <= a["flip"] for a, b in zip(in_window, in_window[1:])):
        raise ValueError("Source-order guest flip timestamps duplicate, reverse, or reset")
    if any(r["end"] < r["begin"] or r["begin"] < r["flip"] for r in in_window):
        raise ValueError("Trace contains non-monotonic flip/present timestamps")

    # Keep only accepted WSI presentations. Do not bridge the skipped/aborted rows
    # when deriving adjacent flip intervals.
    accepted: list[dict[str, int]] = []
    rejected_wsi = 0
    malformed_timing = 0
    for row in in_window:
        if row["accepted"] != 1 or row["result"] not in {0, 1000001003}:
            rejected_wsi += 1
            continue
        if row["begin"] < row["flip"] or row["end"] < row["begin"]:
            malformed_timing += 1
            continue
        accepted.append(row)
    accepted.sort(key=lambda r: r["flip"])
    if any(b["flip"] <= a["flip"] for a, b in zip(accepted, accepted[1:])):
        raise ValueError("Accepted flip timestamps duplicate or reset")

    flip_intervals: list[float] = []
    sequence_gaps = 0
    for a, b in zip(accepted, accepted[1:]):
        consecutive = b["seq"] == a["seq"] + 1 and b["frame"] == a["frame"] + 1
        if not consecutive:
            sequence_gaps += 1
            continue
        flip_intervals.append((b["flip"] - a["flip"]) / 1_000_000.0)
    flip_to_present_begin = [(r["begin"] - r["flip"]) / 1_000_000.0 for r in accepted]
    present_service = [(r["end"] - r["begin"]) / 1_000_000.0 for r in accepted]
    flip_to_present_end = [(r["end"] - r["flip"]) / 1_000_000.0 for r in accepted]

    # A separate reviewer attestation binds visible gameplay to this exact
    # process/request/session/window. Missing or mismatching proof never prevents
    # cadence reporting, but leaves the gameplay-window result inconclusive.
    proof_status = "INCONCLUSIVE_GAMEPLAY_PROOF_MISSING"
    proof = window.get("gameplay_review")
    if isinstance(proof, dict) and proof.get("decision") == "APPROVE":
        proof_file = Path(str(proof.get("review_record_path", "")))
        screenshot_file = Path(str(proof.get("screenshot_path", "")))
        try:
            review = load_json(proof_file)
            checkpoint = review.get("visible_checkpoint", {})
            review_matches = (
                sha256_file(proof_file) == proof.get("review_record_sha256")
                and sha256_file(screenshot_file) == checkpoint.get("screenshot_sha256")
                and review.get("decision") == "APPROVE"
                and review.get("run_id") == run_id
                and review.get("process_id") == pid
                and str(review.get("process_start_time")) == process_start
                and review.get("app_request_id") == request_id
                and review.get("app_session_id") == app_session_id
                and review.get("renderer_session_ns") == renderer_session
                and review.get("window_start_monotonic_ns") == start_ns
                and review.get("window_end_monotonic_ns") == end_ns
                and type(checkpoint.get("captured_monotonic_ns")) is int
                and start_ns <= checkpoint["captured_monotonic_ns"] <= end_ns
                and isinstance(checkpoint.get("observation"), str)
                and len(checkpoint["observation"].strip()) >= 12
            )
            if review_matches:
                proof_status = "INDEPENDENT_REVIEW_ATTESTATION_MATCHED"
        except (OSError, ValueError, json.JSONDecodeError):
            proof_status = "INCONCLUSIVE_GAMEPLAY_REVIEW_BINDING_INVALID"

    source_identity = {
        "path": str(producer_source_path),
        "sha256": producer_sha,
        "steady_clock_source_line": 50,
        "emu_flip_and_not_skipped_gate_line": 507,
        "present_call_log_lines": [170, 174, 178],
        "interpretation": "Only non-skipped emu_flip events reaching VKGSRender::present are logged; source does not count skipped frames.",
    }
    return {
        "schema_version": 1,
        "status": "DIAGNOSTIC_ONLY",
        "diagnostic_kind": "guest_flip_present_trace",
        "gameplay_window_attestation": proof_status,
        "cadence_window_interpretation": (
            "INDEPENDENTLY_VERIFIED_GAMEPLAY_WINDOW"
            if proof_status == "INDEPENDENT_REVIEW_ATTESTATION_MATCHED"
            else "CAPTURE_WINDOW_NOT_VERIFIED_AS_GAMEPLAY"
        ),
        "gameplay_scoring_eligible": False,
        "full_guest_frame_qualification": False,
        "fps_score": None,
        "run_id": run_id,
        "process_identity": {"pid": pid, "process_start_time": process_start},
        "app_request_id": request_id,
        "app_session_id": app_session_id,
        "renderer_session_ns": renderer_session,
        "source_identity": source_identity,
        "raw_log": {"path": str(log_path), "sha256": raw_sha},
        "capture_window": {"clock_domain": "CLOCK_MONOTONIC", "start_ns": start_ns, "end_ns": end_ns,
                           "duration_ms": (end_ns - start_ns) / 1_000_000.0},
        "rows": {
            "matching_renderer_session": len(all_session_rows),
            "inside_window": len(in_window),
            "outside_window_excluded": outside_window,
            "first_source_line": min((r["source_line"] for r in in_window), default=None),
            "last_source_line": max((r["source_line"] for r in in_window), default=None),
            "accepted_presentations": len(accepted),
            "wsi_rejected_or_aborted_excluded": rejected_wsi,
            "malformed_timing_excluded": malformed_timing,
            "nonconsecutive_intervals_excluded": sequence_gaps,
            "skipped_frame_records": None,
            "skipped_frame_note": "The source gate omits info.skip_frame=true; skipped flips are not counted by this trace.",
        },
        "guest_flip_interval_ms": {
            "count": len(flip_intervals), "mean": statistics.fmean(flip_intervals) if flip_intervals else None,
            "p50": percentile(flip_intervals, 50), "p90": percentile(flip_intervals, 90),
            "p95": percentile(flip_intervals, 95), "p99": percentile(flip_intervals, 99),
        },
        "capture_edge_gaps_ms": {
            "leading_to_first_trace": max(0.0, (in_window[0]["flip"] - start_ns) / 1_000_000.0),
            "trailing_from_last_trace": max(0.0, (end_ns - in_window[-1]["flip"]) / 1_000_000.0),
        },
        "flip_to_present_begin_ms": {
            "count": len(flip_to_present_begin), "mean": statistics.fmean(flip_to_present_begin) if flip_to_present_begin else None,
            "p50": percentile(flip_to_present_begin, 50), "p95": percentile(flip_to_present_begin, 95),
        },
        "present_service_ms": {
            "count": len(present_service), "mean": statistics.fmean(present_service) if present_service else None,
            "p50": percentile(present_service, 50), "p95": percentile(present_service, 95),
        },
        "flip_to_present_end_ms": {
            "count": len(flip_to_present_end), "mean": statistics.fmean(flip_to_present_end) if flip_to_present_end else None,
            "p50": percentile(flip_to_present_end, 50), "p95": percentile(flip_to_present_end, 95),
        },
        "limitations": [
            "Accepted WSI calls do not prove visible/nonblack guest content or gameplay.",
            "This source does not emit raw per-guest-frame records and cannot produce guest-frame completeness or FPS scores.",
            "The source excludes skip_frame events before logging, so dropped/skipped guest frames cannot be counted.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("guest_present_log", type=Path)
    parser.add_argument("run_manifest", type=Path)
    parser.add_argument("capture_manifest", type=Path)
    parser.add_argument("--producer-source", type=Path, default=Path("app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKPresent.cpp"))
    parser.add_argument("--output-json", type=Path)
    args = parser.parse_args()
    try:
        result = analyze(args.guest_present_log, args.run_manifest, args.capture_manifest, args.producer_source)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        result = {
            "schema_version": 1, "status": "INCONCLUSIVE_EVIDENCE",
            "diagnostic_kind": "guest_flip_present_trace", "gameplay_scoring_eligible": False,
            "full_guest_frame_qualification": False, "fps_score": None,
            "inconclusive_reason": str(exc),
        }
        print(f"Inconclusive guest-present trace: {exc}", file=sys.stderr)
        if args.output_json:
            args.output_json.parent.mkdir(parents=True, exist_ok=True)
            args.output_json.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        return 2
    text = json.dumps(result, indent=2) + "\n"
    if args.output_json:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
