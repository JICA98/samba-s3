#!/usr/bin/env python3
"""
Unit tests for scripts/perf/analyze-frame-events.py.

Verifies:
1. Parsing of logcat crosscheck lines, generic key-value timestamps, JSON, and CSV.
2. Event deduplication within the 800us window and duplicate frame IDs.
3. Boundary handling with scene start/end filters.
4. Dropped samples and invalid / non-positive timestamps.
5. Pause detection and separation of pause intervals from active gameplay.
6. Separation of rolling display metrics from full-interval throughput.
7. Correctness of P50, P90, P95, P99 percentile calculations and stall bins.
"""

import sys
import unittest
from pathlib import Path

# Add scripts directory to import path
ROOT_DIR = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT_DIR / "scripts" / "perf"))

import importlib.util

spec = importlib.util.spec_from_file_location(
    "analyze_frame_events",
    ROOT_DIR / "scripts" / "perf" / "analyze-frame-events.py"
)
analyze = importlib.util.module_from_spec(spec)
sys.modules["analyze_frame_events"] = analyze
spec.loader.exec_module(analyze)

FrameEvent = analyze.FrameEvent
FrameMetricsResult = analyze.FrameMetricsResult
parse_logcat_line = analyze.parse_logcat_line
parse_perf_json = analyze.parse_perf_json
parse_csv_timestamps = analyze.parse_csv_timestamps
deduplicate_events = analyze.deduplicate_events
filter_events_window = analyze.filter_events_window
compute_frame_metrics = analyze.compute_frame_metrics
percentile = analyze.percentile


class TestAnalyzeFrameEvents(unittest.TestCase):

    def test_parse_logcat_crosscheck(self):
        line = "09-24 22:15:30.244  5454  5498 D S3PERF  : crosscheck source=surface presented=3564 json_fps=8.47363 json_frametime_ms=90.684"
        ev = parse_logcat_line(line)
        self.assertIsNotNone(ev)
        self.assertEqual(ev.source, "surface")
        self.assertEqual(ev.frame_id, 3564)
        self.assertAlmostEqual(ev.rolling_fps, 8.47363, places=4)
        self.assertAlmostEqual(ev.instantaneous_ft_ms, 90.684, places=3)
        # Expected timestamp in microseconds: 22h 15m 30s 244ms
        expected_us = (22 * 3600 + 15 * 60 + 30) * 1_000_000 + 244 * 1_000
        self.assertEqual(ev.timestamp_us, expected_us)

    def test_parse_generic_kv_line(self):
        line = "timestamp_us=5000000 source=vk_present frame=42 fps=60.0 frametime_ms=16.66"
        ev = parse_logcat_line(line)
        self.assertIsNotNone(ev)
        self.assertEqual(ev.timestamp_us, 5000000)
        self.assertEqual(ev.source, "vk_present")
        self.assertEqual(ev.frame_id, 42)
        self.assertAlmostEqual(ev.rolling_fps, 60.0)
        self.assertAlmostEqual(ev.instantaneous_ft_ms, 16.66)

    def test_parse_invalid_or_empty_lines(self):
        self.assertIsNone(parse_logcat_line(""))
        self.assertIsNone(parse_logcat_line("   \n"))
        self.assertIsNone(parse_logcat_line("Some random log line with no performance metrics"))

    def test_parse_perf_json(self):
        json_data = """{
            "version": 2,
            "timestampUs": 10000000,
            "presentedFrameCount": 100,
            "fpsSource": "vk_present",
            "fps": 30.0,
            "frametimeMs": 33.33,
            "fpsSamples": [
                {"timestampUs": 9000000, "fps": 29.5},
                {"timestampUs": 10000000, "fps": 30.0}
            ],
            "frametimeSamples": [
                {"timestampUs": 9500000, "frametimeMs": 33.2},
                {"timestampUs": 10000000, "frametimeMs": 33.5}
            ]
        }"""
        events = parse_perf_json(json_data)
        self.assertGreaterEqual(len(events), 1)
        main_ev = events[0]
        self.assertEqual(main_ev.timestamp_us, 10000000)
        self.assertEqual(main_ev.source, "vk_present")
        self.assertEqual(main_ev.frame_id, 100)
        self.assertAlmostEqual(main_ev.rolling_fps, 30.0)

    def test_parse_csv_timestamps(self):
        csv_data = """timestamp_us,frame_id,fps,frametime_ms
1000000,1,10.0,100.0
1100000,2,10.0,100.0
1200000,3,10.0,100.0
"""
        events = parse_csv_timestamps(csv_data)
        self.assertEqual(len(events), 3)
        self.assertEqual(events[1].timestamp_us, 1100000)
        self.assertEqual(events[1].frame_id, 2)
        self.assertAlmostEqual(events[1].instantaneous_ft_ms, 100.0)

    def test_event_deduplication(self):
        # Test case: Vulkan present and ANativeWindow queueBuffer firing within 400us (< 800us)
        events = [
            FrameEvent(timestamp_us=1000000, frame_id=1, source="vk_present"),
            FrameEvent(timestamp_us=1000400, frame_id=1, source="surface"), # Duplicate frame_id & <800us
            FrameEvent(timestamp_us=1050000, frame_id=2, source="surface"), # Valid event
            FrameEvent(timestamp_us=1050200, frame_id=3, source="vk_present"), # <800us from previous
            FrameEvent(timestamp_us=1100000, frame_id=4, source="surface"), # Valid event
        ]

        deduped, dropped_cnt, dup_cnt = deduplicate_events(events, min_delta_us=800)
        self.assertEqual(len(deduped), 3)
        self.assertEqual(dup_cnt, 2)
        self.assertEqual([e.frame_id for e in deduped], [1, 2, 4])

    def test_dropped_samples_and_zero_time(self):
        events = [
            FrameEvent(timestamp_us=0, frame_id=0),       # Invalid zero time
            FrameEvent(timestamp_us=-500, frame_id=-1),    # Invalid negative
            FrameEvent(timestamp_us=1000000, frame_id=1),  # Valid
            FrameEvent(timestamp_us=1050000, frame_id=2),  # Valid
        ]
        deduped, dropped_cnt, dup_cnt = deduplicate_events(events)
        self.assertEqual(dropped_cnt, 2)
        self.assertEqual(len(deduped), 2)

    def test_boundary_handling(self):
        events = [
            FrameEvent(timestamp_us=1000000, frame_id=1),
            FrameEvent(timestamp_us=2000000, frame_id=2),
            FrameEvent(timestamp_us=3000000, frame_id=3),
            FrameEvent(timestamp_us=4000000, frame_id=4),
            FrameEvent(timestamp_us=5000000, frame_id=5),
        ]
        filtered = filter_events_window(events, start_us=2000000, end_us=4000000)
        self.assertEqual(len(filtered), 3)
        self.assertEqual([e.frame_id for e in filtered], [2, 3, 4])

    def test_pauses_and_clock_changes(self):
        # 3 frames at 100ms interval, then a 3000ms pause (e.g. cutscene transition), then 3 frames at 100ms
        events = [
            FrameEvent(timestamp_us=1000000, frame_id=1),
            FrameEvent(timestamp_us=1100000, frame_id=2),
            FrameEvent(timestamp_us=1200000, frame_id=3),
            FrameEvent(timestamp_us=4200000, frame_id=4), # 3000ms gap > 1000ms pause threshold
            FrameEvent(timestamp_us=4300000, frame_id=5),
            FrameEvent(timestamp_us=4400000, frame_id=6),
        ]
        res = compute_frame_metrics(events, pause_threshold_ms=1000.0)

        self.assertEqual(res.pause_events_detected, 1)
        self.assertAlmostEqual(res.total_pause_duration_ms, 3000.0, places=1)
        # Wall time = 4400000 - 1000000 = 3400ms
        self.assertAlmostEqual(res.elapsed_wall_time_ms, 3400.0, places=1)
        # Active gameplay time = 100 + 100 + 100 + 100 = 400ms
        self.assertAlmostEqual(res.elapsed_active_gameplay_ms, 400.0, places=1)
        # Throughput = 4 intervals / 0.4s = 10.0 FPS
        self.assertAlmostEqual(res.interval_throughput_fps, 10.0, places=1)

    def test_separation_rolling_display_from_full_interval(self):
        # Simulation where rolling display metrics report 15 FPS, but actual interval throughput is 10 FPS
        events = []
        # 11 frames over 1.0 second = 10 FPS
        for i in range(11):
            events.append(
                FrameEvent(
                    timestamp_us=1000000 + i * 100000, # 100ms apart
                    frame_id=i,
                    rolling_fps=15.0, # Divergent display snapshot
                )
            )
        res = compute_frame_metrics(events)
        # Primary metric: 10 intervals / 1.0s = 10.0 FPS
        self.assertAlmostEqual(res.interval_throughput_fps, 10.0, places=2)
        # Secondary metric: Rolling display mean = 15.0 FPS
        self.assertIsNotNone(res.rolling_fps_mean)
        self.assertAlmostEqual(res.rolling_fps_mean, 15.0, places=2)
        self.assertNotEqual(res.interval_throughput_fps, res.rolling_fps_mean)

    def test_percentile_and_stall_classification(self):
        # Create 100 intervals with known distribution
        # 90 intervals at 16.66ms (~60 FPS)
        # 5 intervals at 40.0ms (stalls > 33.3ms)
        # 3 intervals at 60.0ms (stalls > 50ms)
        # 2 intervals at 120.0ms (stalls > 100ms)
        ft_list = [16.66] * 90 + [40.0] * 5 + [60.0] * 3 + [120.0] * 2
        events = [FrameEvent(timestamp_us=1000000, frame_id=0)]
        curr_us = 1000000
        for i, ft in enumerate(ft_list, 1):
            curr_us += int(ft * 1000.0)
            events.append(FrameEvent(timestamp_us=curr_us, frame_id=i))

        res = compute_frame_metrics(events)
        self.assertEqual(res.qualified_frames, 101)
        self.assertEqual(len(res.qualified_intervals_ms), 100)

        self.assertAlmostEqual(res.frametime_min_ms, 16.66, places=1)
        self.assertAlmostEqual(res.frametime_max_ms, 120.0, places=1)
        self.assertAlmostEqual(res.frametime_p50_ms, 16.66, places=1)
        self.assertAlmostEqual(res.frametime_p95_ms, 41.0, places=1)
        self.assertAlmostEqual(res.frametime_p99_ms, 120.0, places=1)

        # Stalls verification
        self.assertEqual(res.stalls_over_33ms, 10)  # 5 + 3 + 2 = 10
        self.assertEqual(res.stalls_over_50ms, 5)   # 3 + 2 = 5
        self.assertEqual(res.stalls_over_100ms, 2)  # 2
        self.assertEqual(res.stalls_over_250ms, 0)  # 0


if __name__ == "__main__":
    unittest.main()
