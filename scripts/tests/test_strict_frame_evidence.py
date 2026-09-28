#!/usr/bin/env python3
"""Regression tests for the fail-closed raw guest-frame qualification gate."""

import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT_DIR = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT_DIR / "scripts" / "perf"))

import importlib.util

spec = importlib.util.spec_from_file_location(
    "analyze_frame_events_strict",
    ROOT_DIR / "scripts" / "perf" / "analyze-frame-events.py",
)
analyze = importlib.util.module_from_spec(spec)
sys.modules["analyze_frame_events_strict"] = analyze
spec.loader.exec_module(analyze)


def raw_record(frame_id, monotonic_ns, **overrides):
    row = {
        "schemaVersion": 1,
        "recordType": "guest_frame",
        "runId": "run-123",
        "processId": 456,
        "processStartTime": "98765",
        "sessionId": "session-abc",
        "source": "emu_flip",
        "guestFrameId": frame_id,
        "monotonicNs": monotonic_ns,
        "phase": "gameplay",
    }
    row.update(overrides)
    return row


def parsed(rows):
    return analyze.parse_perf_json("\n".join(json.dumps(row) for row in rows))


class StrictRawGuestFrameGateTest(unittest.TestCase):
    def window(self, start=1, end=2_000_000_000, session="session-abc", first=10, last=12):
        return {
            "schemaVersion": 1,
            "recordType": "guest_frame_capture_window",
            "clockDomain": "CLOCK_MONOTONIC",
            "captureComplete": True,
            "runId": "run-123",
            "processId": 456,
            "processStartTime": "98765",
            "sessionId": session,
            "rawGuestFrameJsonlSha256": "0" * 64,
            "captureAdmissionSha256": "1" * 64,
            "windowStartMonotonicNs": start,
            "windowEndMonotonicNs": end,
            "firstGuestFrameId": first,
            "lastGuestFrameId": last,
            "guestFrameCount": last - first + 1,
        }

    def admission(self, start, end, session="session-abc"):
        return {
            "schemaVersion": 1,
            "recordType": "guest_frame_capture_admission",
            "clockDomain": "CLOCK_MONOTONIC",
            "runId": "run-123",
            "processId": 456,
            "processStartTime": "98765",
            "sessionId": session,
            "windowStartMonotonicNs": start,
            "windowEndMonotonicNs": end,
            "requestedDurationNs": end - start,
            "producer": "test fixture",
            "producerSourceSha256": "a" * 64,
        }

    def write_bound_capture(self, tmp, input_path, start, end, session="session-abc"):
        admission_path = Path(tmp) / "frames.capture-admission.json"
        window_path = Path(tmp) / "frames.capture-window.json"
        admission_path.write_text(json.dumps(self.admission(start, end, session)))
        window = self.window(start=start, end=end, session=session)
        window["rawGuestFrameJsonlSha256"] = analyze.hashlib.sha256(input_path.read_bytes()).hexdigest()
        window["captureAdmissionSha256"] = analyze.hashlib.sha256(admission_path.read_bytes()).hexdigest()
        window_path.write_text(json.dumps(window))
        return admission_path, window_path

    def validate(self, rows, window=None):
        window = window or self.window()
        return analyze.validate_raw_guest_frame_events(
            parsed(rows), expected_pid=456, expected_run_id="run-123",
            expected_process_start_time="98765", expected_session_id=window["sessionId"],
            window_start_ns=window["windowStartMonotonicNs"], window_end_ns=window["windowEndMonotonicNs"],
        )

    def test_valid_raw_run_bound_frames_are_accepted(self):
        accepted, reason = self.validate([
            raw_record(10, 950_000_000),
            raw_record(11, 966_666_667),
            raw_record(12, 983_333_334),
        ])
        self.assertIsNone(reason)
        self.assertEqual([event.frame_id for event in accepted], [10, 11, 12])

    def test_empty_and_legacy_counter_samples_are_inconclusive(self):
        accepted, reason = self.validate([])
        self.assertEqual(accepted, [])
        self.assertEqual(reason, "INCONCLUSIVE_RAW_GUEST_FRAME_EVIDENCE_MISSING")
        legacy = [{
            "version": 2, "timestampUs": 1000, "presentedFrameCount": 900,
            "fpsSource": "emu_flip", "fps": 60, "frametimeMs": 16.6,
        }] * 3
        accepted, reason = self.validate(legacy)
        self.assertEqual(accepted, [])
        self.assertEqual(reason, "INCONCLUSIVE_NONRAW_EVENT_IN_GUEST_FRAME_EXPORT")

    def test_frontend_presented_only_data_is_rejected(self):
        rows = [raw_record(n, n * 16_666_667, source="surface") for n in (1, 2, 3)]
        accepted, reason = self.validate(rows)
        self.assertEqual(accepted, [])
        self.assertEqual(reason, "INCONCLUSIVE_GUEST_FRAME_SOURCE_OR_PHASE_MISMATCH")

    def test_wrong_pid_run_or_process_instance_is_rejected(self):
        for override in (
            {"processId": 457},
            {"runId": "previous-run"},
            {"processStartTime": "old-instance"},
        ):
            with self.subTest(override=override):
                rows = [raw_record(n, n * 16_666_667, **override) for n in (1, 2, 3)]
                accepted, reason = self.validate(rows)
                self.assertEqual(accepted, [])
                self.assertEqual(reason, "INCONCLUSIVE_PROCESS_OR_RUN_IDENTITY_MISMATCH")

    def test_synthetic_records_and_duplicate_or_reset_counters_are_rejected(self):
        rows = [raw_record(n, n * 16_666_667, synthetic=True) for n in (1, 2, 3)]
        self.assertEqual(self.validate(rows)[1], "INCONCLUSIVE_SYNTHETIC_GUEST_FRAME_RECORD")
        duplicate = [raw_record(n, 1_000_000_000 + i * 16_000_000) for i, n in enumerate((4, 4, 5))]
        self.assertEqual(self.validate(duplicate)[1], "INCONCLUSIVE_GUEST_FRAME_ID_DUPLICATE_OR_RESET")
        reset = [raw_record(n, t) for n, t in ((4, 1_030_000_000), (5, 1_020_000_000), (6, 1_040_000_000))]
        self.assertEqual(self.validate(reset)[1], "INCONCLUSIVE_GUEST_MONOTONIC_TIME_DUPLICATE_OR_RESET")
        session_change = [
            raw_record(1, 1_000_000_000),
            raw_record(2, 1_016_666_667, sessionId="replacement-session"),
            raw_record(3, 1_033_333_334),
        ]
        self.assertEqual(self.validate(session_change)[1], "INCONCLUSIVE_SESSION_ID_MISSING_OR_CHANGED")

    def test_cli_writes_inconclusive_identity_for_present_only_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            input_path = Path(tmp) / "frames.raw.jsonl"
            output_path = Path(tmp) / "analysis.json"
            input_path.write_text("\n".join(json.dumps(raw_record(n, n * 16_000_000, source="vk_present")) for n in (1, 2, 3)))
            admission_path, window_path = self.write_bound_capture(tmp, input_path, 1, 100_000_000)
            argv = [
                "analyze-frame-events.py", str(input_path), "--require-raw-guest-frames",
                "--expected-pid", "456", "--run-id", "run-123",
                "--process-start-time", "98765", "--capture-window-json", str(window_path),
                "--capture-admission-json", str(admission_path), "--expected-session-id", "session-abc",
                "--requested-window-seconds", "0.099999999",
                "--output-json", str(output_path), "--json",
            ]
            with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                status = analyze.main()
            result = json.loads(output_path.read_text())
            self.assertEqual(status, 2)
            self.assertEqual(result["evaluation_status"], "INCONCLUSIVE_EVIDENCE")
            self.assertEqual(result["qualified_frames"], 0)
            self.assertEqual(result["measurement_identity"]["run_id"], "run-123")
            self.assertRegex(result["input_sha256"], r"^[0-9a-f]{64}$")

    def test_cli_uses_monotonic_window_and_ignores_injected_telemetry(self):
        with tempfile.TemporaryDirectory() as tmp:
            input_path = Path(tmp) / "frames.raw.jsonl"
            output_path = Path(tmp) / "analysis.json"
            input_path.write_text("\n".join(json.dumps(raw_record(
                n, t, frametimeMs=1.0, fps=1000.0
            )) for n, t in ((10, 2_000_000_000), (11, 2_016_666_667), (12, 2_033_333_334))))
            admission_path, window_path = self.write_bound_capture(tmp, input_path, 1_000_000_000, 3_000_000_000)
            argv = [
                "analyze-frame-events.py", str(input_path), "--require-raw-guest-frames",
                "--expected-pid", "456", "--run-id", "run-123",
                "--process-start-time", "98765", "--capture-window-json", str(window_path),
                "--capture-admission-json", str(admission_path), "--expected-session-id", "session-abc",
                "--requested-window-seconds", "2",
                "--output-json", str(output_path),
            ]
            with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()):
                status = analyze.main()
            result = json.loads(output_path.read_text())
            self.assertEqual(status, 0)
            self.assertEqual(result["evaluation_status"], "QUALIFIED")
            self.assertEqual(result["measurement_identity"]["session_id"], "session-abc")
            self.assertEqual(result["measurement_identity"]["guest_frame_records"], 3)
            self.assertEqual(result["elapsed_wall_time_ms"], 2000.0)
            self.assertGreater(result["leading_capture_gap_ms"], 900)
            self.assertGreater(result["trailing_capture_gap_ms"], 900)
            self.assertAlmostEqual(result["frametime_p50_ms"], 16.666667, places=3)
            self.assertLess(result["interval_throughput_fps"], 2)
            self.assertGreaterEqual(result["stalls_over_250ms"], 2)

    def test_wrong_capture_session_and_incomplete_window_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            window_path = Path(tmp) / "window.json"
            rows = [raw_record(n, t) for n, t in ((10, 950_000_000), (11, 966_000_000), (12, 982_000_000))]
            wrong_session = self.window(session="other-session")
            accepted, reason = self.validate(rows, wrong_session)
            self.assertEqual(accepted, [])
            self.assertEqual(reason, "INCONCLUSIVE_SESSION_ID_MISSING_OR_CHANGED")

            wrong_range = self.window(first=10, last=13)
            accepted, reason = self.validate(rows, wrong_range)
            self.assertIsNone(reason)
            self.assertEqual(analyze.validate_capture_window_frames(accepted, wrong_range),
                             "INCONCLUSIVE_CAPTURE_WINDOW_FRAME_RANGE_MISMATCH")

            invalid = self.window()
            invalid["clockDomain"] = "HOST_WALL_CLOCK"
            window_path.write_text(json.dumps(invalid))
            loaded, reason = analyze.load_capture_window(
                str(window_path), expected_pid=456, expected_run_id="run-123",
                expected_process_start_time="98765",
            )
            self.assertIsNone(loaded)
            self.assertEqual(reason, "INCONCLUSIVE_CAPTURE_WINDOW_MANIFEST_MISSING_OR_INVALID")

    def test_admission_must_match_runner_session_and_requested_window(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "admission.json"
            complete = self.admission(1_000_000_000, 61_000_000_000)
            path.write_text(json.dumps(complete))
            loaded, reason = analyze.load_capture_admission(
                str(path), expected_pid=456, expected_run_id="run-123",
                expected_process_start_time="98765", expected_session_id="session-abc",
                requested_duration_ns=60_000_000_000,
            )
            self.assertIsNone(reason)
            self.assertIsNotNone(loaded)

            short = self.admission(1_000_000_000, 2_000_000_000)
            path.write_text(json.dumps(short))
            loaded, reason = analyze.load_capture_admission(
                str(path), expected_pid=456, expected_run_id="run-123",
                expected_process_start_time="98765", expected_session_id="session-abc",
                requested_duration_ns=60_000_000_000,
            )
            self.assertIsNone(loaded)
            self.assertEqual(reason, "INCONCLUSIVE_CAPTURE_ADMISSION_MISSING_OR_INVALID")

            loaded, reason = analyze.load_capture_admission(
                str(path), expected_pid=456, expected_run_id="run-123",
                expected_process_start_time="98765", expected_session_id="wrong-session",
                requested_duration_ns=1_000_000_000,
            )
            self.assertIsNone(loaded)
            self.assertEqual(reason, "INCONCLUSIVE_CAPTURE_ADMISSION_MISSING_OR_INVALID")

    def test_missing_capture_window_is_inconclusive_and_filter_cannot_shrink_window(self):
        with tempfile.TemporaryDirectory() as tmp:
            input_path = Path(tmp) / "frames.raw.jsonl"
            output_path = Path(tmp) / "analysis.json"
            input_path.write_text("\n".join(json.dumps(raw_record(n, t)) for n, t in (
                (10, 950_000_000), (11, 966_666_667), (12, 983_333_334),
            )))
            missing_window = Path(tmp) / "missing-window.json"
            argv = [
                "analyze-frame-events.py", str(input_path), "--require-raw-guest-frames",
                "--expected-pid", "456", "--run-id", "run-123", "--process-start-time", "98765",
                "--capture-window-json", str(missing_window), "--output-json", str(output_path),
                "--capture-admission-json", str(Path(tmp) / "missing-admission.json"),
                "--expected-session-id", "session-abc", "--requested-window-seconds", "2",
            ]
            with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                status = analyze.main()
            result = json.loads(output_path.read_text())
            self.assertEqual(status, 2)
            self.assertEqual(result["evaluation_status"], "INCONCLUSIVE_EVIDENCE")
            self.assertEqual(result["inconclusive_reason"], "INCONCLUSIVE_CAPTURE_WINDOW_MANIFEST_MISSING_OR_INVALID")
            self.assertIsNone(result["capture_window_sha256"])

            argv += ["--start-us", "950000"]
            with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                status = analyze.main()
            self.assertEqual(status, 2)

    def test_summarizer_no_args_is_inconclusive(self):
        result = subprocess.run(
            [sys.executable, str(ROOT_DIR / "scripts/perf/summarize-run.py")],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stdout)["status"], "INCONCLUSIVE")

    def test_summarizer_demo_and_numeric_values_never_become_scores(self):
        demo = subprocess.run(
            [sys.executable, str(ROOT_DIR / "scripts/perf/summarize-run.py"), "--demo"],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(json.loads(demo.stdout)["status"], "DEMO_ONLY_NOT_EVIDENCE")
        numeric = subprocess.run(
            [sys.executable, str(ROOT_DIR / "scripts/perf/summarize-run.py"), "60", "61"],
            capture_output=True, text=True, check=False,
        )
        summary = json.loads(numeric.stdout)
        self.assertEqual(summary["status"], "UNVERIFIED_NUMERIC_SUMMARY_NOT_A_SCORE")
        self.assertIsNone(summary["score"])


if __name__ == "__main__":
    unittest.main()
