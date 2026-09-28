#!/usr/bin/env python3
"""Tests for diagnostic-only, process-bound GOW3 guest/present cadence parsing."""
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "guest_flip_present_trace",
    ROOT / "scripts/perf/analyze-guest-flip-present-trace.py",
)
trace = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(trace)


class GuestFlipPresentTraceTest(unittest.TestCase):
    def test_accepted_guest_flip_cadence_is_diagnostic_not_gameplay_score(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "VKPresent.cpp"
            source.write_text("producer fixture source\n")
            source_sha = hashlib.sha256(source.read_bytes()).hexdigest()
            rows = [
                "1790000000.000 100 100 W S3BOOT  : boot started request_id=req-1",
                "1790000000.010 100 100 I S3SESSION: journal state=RUNNING session=app-1",
            ]
            for seq, flip in enumerate((1_000_000, 17_666_667, 34_333_334), start=1):
                rows.append(
                    f"1790000000.{seq:03d} 100 101 D RPCS3   : GOW3_GUEST_PRESENT "
                    f"session_ns=700 guest_flip_seq={seq} guest_buffer_id={seq % 2} "
                    f"renderer_frame_id={seq-1} flip_ns={flip} present_begin_ns={flip+1000} "
                    f"present_end_ns={flip+2000} vk_result=1000001003 wsi_accepted=1"
                )
            log = root / "logcat.txt"
            raw = "\n".join(rows) + "\n"
            log.write_text(raw)
            run = root / "run.json"
            run.write_text(json.dumps({
                "run_id": "run-1",
                "launch_identity": {"pid": 100, "start_time": "44"},
            }))
            window = root / "window.json"
            window.write_text(json.dumps({
                "schema_version": 1,
                "record_type": "guest_flip_present_capture",
                "clock_domain": "CLOCK_MONOTONIC",
                "run_id": "run-1",
                "process_id": 100,
                "process_start_time": "44",
                "log_sha256": hashlib.sha256(log.read_bytes()).hexdigest(),
                "app_request_id": "req-1",
                "app_session_id": "app-1",
                "renderer_session_ns": 700,
                "window_start_monotonic_ns": 1,
                "window_end_monotonic_ns": 40_000_000,
                "producer_source_sha256": source_sha,
            }))
            result = trace.analyze(log, run, window, source)
            self.assertEqual(result["status"], "DIAGNOSTIC_ONLY")
            self.assertFalse(result["gameplay_scoring_eligible"])
            self.assertFalse(result["full_guest_frame_qualification"])
            self.assertIsNone(result["fps_score"])
            self.assertEqual(result["gameplay_window_attestation"], "INCONCLUSIVE_GAMEPLAY_PROOF_MISSING")
            self.assertEqual(result["rows"]["accepted_presentations"], 3)
            self.assertAlmostEqual(result["guest_flip_interval_ms"]["p50"], 16.666667, places=3)

    def test_log_hash_or_exact_process_session_binding_mismatch_rejects(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.cpp"
            source.write_text("producer\n")
            log = root / "logcat.txt"
            log.write_text("1790000000.000 1 1 I RPCS3: GOW3_GUEST_PRESENT malformed\n")
            run = root / "run.json"
            run.write_text(json.dumps({"run_id": "run-1", "launch_identity": {"pid": 1, "start_time": "2"}}))
            window = root / "window.json"
            window.write_text(json.dumps({
                "schema_version": 1, "record_type": "guest_flip_present_capture",
                "clock_domain": "CLOCK_MONOTONIC", "run_id": "run-1", "process_id": 1,
                "process_start_time": "2", "log_sha256": "0" * 64,
            }))
            with self.assertRaisesRegex(ValueError, "log_sha256"):
                trace.analyze(log, run, window, source)


if __name__ == "__main__":
    unittest.main()
