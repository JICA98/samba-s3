#!/usr/bin/env python3
"""
Unit tests for run-gow3-benchmark.py scheduler mode configuration (Ticket S05 / Experiment E03).
Validates:
- CLI argument parsing for --scheduler (os, rpcs3, alt, invalid modes)
- YAML update and read helpers (preserving formatting, comments, sections)
- Scheduler mode string resolution and alias mapping
- Mocked device configuration flow
"""

import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "perf"))

import importlib.util
spec = importlib.util.spec_from_file_location("run_gow3_benchmark", str(REPO_ROOT / "scripts" / "perf" / "run-gow3-benchmark.py"))
bench = importlib.util.module_from_spec(spec)
sys.modules["run_gow3_benchmark"] = bench
spec.loader.exec_module(bench)


class TestSchedulerCliAndResolution(unittest.TestCase):
    def test_resolve_valid_modes(self):
        self.assertEqual(bench.resolve_scheduler_mode("os"), "Operating System")
        self.assertEqual(bench.resolve_scheduler_mode("OS"), "Operating System")
        self.assertEqual(bench.resolve_scheduler_mode("operating_system"), "Operating System")
        self.assertEqual(bench.resolve_scheduler_mode("Operating-System"), "Operating System")
        self.assertEqual(bench.resolve_scheduler_mode("rpcs3"), "RPCS3 Scheduler")
        self.assertEqual(bench.resolve_scheduler_mode("RPCS3"), "RPCS3 Scheduler")
        self.assertEqual(bench.resolve_scheduler_mode("old"), "RPCS3 Scheduler")
        self.assertEqual(bench.resolve_scheduler_mode("alt"), "RPCS3 Alternative Scheduler")
        self.assertEqual(bench.resolve_scheduler_mode("alternative"), "RPCS3 Alternative Scheduler")

    def test_resolve_none(self):
        self.assertIsNone(bench.resolve_scheduler_mode(None))
        self.assertIsNone(bench.resolve_scheduler_mode(""))

    def test_resolve_invalid_raises(self):
        with self.assertRaises(ValueError):
            bench.resolve_scheduler_mode("invalid_mode")
        with self.assertRaises(ValueError):
            bench.resolve_scheduler_mode("fast")


class TestYamlSchedulerManipulation(unittest.TestCase):
    def test_update_empty_yaml(self):
        res = bench.update_yaml_scheduler_mode("", "Operating System")
        self.assertIn("Core:\n  Thread Scheduler Mode: Operating System\n", res)
        self.assertEqual(bench.read_yaml_scheduler_mode(res), "Operating System")

    def test_update_existing_scheduler_mode(self):
        yaml_in = (
            "Core:\n"
            "  PPU Decoder: LLVM Recompiler (Legacy)\n"
            "  Thread Scheduler Mode: RPCS3 Scheduler\n"
            "  Max SPURS Threads: 6\n"
            "Video:\n"
            "  VSync: true\n"
        )
        updated = bench.update_yaml_scheduler_mode(yaml_in, "Operating System")
        self.assertIn("Thread Scheduler Mode: Operating System\n", updated)
        self.assertNotIn("RPCS3 Scheduler", updated)
        self.assertIn("PPU Decoder: LLVM Recompiler (Legacy)", updated)
        self.assertIn("Max SPURS Threads: 6", updated)
        self.assertIn("Video:\n  VSync: true\n", updated)
        self.assertEqual(bench.read_yaml_scheduler_mode(updated), "Operating System")

    def test_insert_into_existing_core_without_scheduler_mode(self):
        yaml_in = (
            "Core:\n"
            "  PPU Decoder: LLVM\n"
            "  Max SPURS Threads: 6\n"
            "Video:\n"
            "  VSync: true\n"
        )
        updated = bench.update_yaml_scheduler_mode(yaml_in, "RPCS3 Scheduler")
        self.assertIn("Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n  PPU Decoder: LLVM", updated)
        self.assertIn("Video:\n  VSync: true\n", updated)
        self.assertEqual(bench.read_yaml_scheduler_mode(updated), "RPCS3 Scheduler")

    def test_append_core_when_missing(self):
        yaml_in = (
            "Video:\n"
            "  VSync: true\n"
            "Audio:\n"
            "  Master Volume: 100\n"
        )
        updated = bench.update_yaml_scheduler_mode(yaml_in, "Operating System")
        self.assertIn("Video:\n  VSync: true\n", updated)
        self.assertIn("Core:\n  Thread Scheduler Mode: Operating System\n", updated)
        self.assertEqual(bench.read_yaml_scheduler_mode(updated), "Operating System")

    def test_read_with_quotes_and_comments(self):
        yaml_quoted = (
            "Core:\n"
            "  Thread Scheduler Mode: \"RPCS3 Scheduler\" # Comment\n"
        )
        self.assertEqual(bench.read_yaml_scheduler_mode(yaml_quoted), "RPCS3 Scheduler")

    def test_read_absent(self):
        yaml_none = (
            "Core:\n"
            "  PPU Decoder: LLVM\n"
            "Video:\n"
            "  VSync: true\n"
        )
        self.assertIsNone(bench.read_yaml_scheduler_mode(yaml_none))


class TestBenchmarkRunnerSchedulerIntegration(unittest.TestCase):
    @patch.object(bench, "adb_shell")
    @patch.object(bench, "run_cmd")
    def test_configure_scheduler_pushes_updated_config(self, mock_run_cmd, mock_adb_shell):
        mock_adb_shell.side_effect = [
            # 1. cat global config.yml
            MagicMock(returncode=0, stdout="Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n"),
            # 2. mkdir -p
            MagicMock(returncode=0, stdout=""),
            # 3. cat custom config_BCUS98111.yml
            MagicMock(returncode=0, stdout="Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n"),
        ]
        mock_run_cmd.return_value = MagicMock(returncode=0, stderr="")

        ok = bench.configure_scheduler("device123", "os", "direct_iso/BCUS98111")
        self.assertTrue(ok)

        # Verified adb push was invoked for global config and custom config
        push_calls = [c for c in mock_run_cmd.call_args_list if "push" in c[0][0]]
        self.assertGreaterEqual(len(push_calls), 2)
        # Check destinations
        self.assertEqual(push_calls[0][0][0][5], bench.GLOBAL_CONFIG_PATH_DEVICE)
        self.assertIn("config_BCUS98111.yml", push_calls[1][0][0][5])

    @patch.object(bench, "adb_shell")
    def test_inspect_device_scheduler_prefers_custom_over_global(self, mock_adb_shell):
        mock_adb_shell.side_effect = [
            # Custom config for BCUS98111 has RPCS3 Scheduler
            MagicMock(returncode=0, stdout="Core:\n  Thread Scheduler Mode: RPCS3 Scheduler\n"),
        ]
        mode = bench.inspect_device_scheduler("device123", "direct_iso/BCUS98111")
        self.assertEqual(mode, "RPCS3 Scheduler")

    @patch.object(bench, "adb_shell")
    def test_inspect_device_scheduler_falls_back_to_global(self, mock_adb_shell):
        mock_adb_shell.side_effect = [
            # Custom config missing
            MagicMock(returncode=1, stdout=""),
            # Global config has Operating System
            MagicMock(returncode=0, stdout="Core:\n  Thread Scheduler Mode: Operating System\n"),
        ]
        mode = bench.inspect_device_scheduler("device123", "direct_iso/BCUS98111")
        self.assertEqual(mode, "Operating System")


class TestCliArgumentParsing(unittest.TestCase):
    def test_cli_scheduler_default_is_none(self):
        with patch.object(sys, "argv", ["run-gow3-benchmark.py", "--dry-run"]):
            with patch.object(bench, "run_cmd") as mock_rc, patch.object(bench, "fetch_device_provenance") as mock_dp, patch.object(bench, "validate_provenance") as mock_vp:
                mock_rc.return_value = MagicMock(returncode=0, stdout="device\n")
                mock_dp.return_value = MagicMock(model="Test", soc="SM8650", android_ver="14", version_name="1", version_code="1", base_apk_sha256="", librpcsx_so_sha256="", s3core_identity=bench.EXPECTED_CORE_HASH)
                mock_vp.return_value = (True, [])
                ret = bench.main()
                self.assertEqual(ret, 0)

    def test_cli_scheduler_os_mode(self):
        with patch.object(sys, "argv", ["run-gow3-benchmark.py", "--scheduler", "os", "--dry-run"]):
            with patch.object(bench, "run_cmd") as mock_rc, patch.object(bench, "fetch_device_provenance") as mock_dp, patch.object(bench, "validate_provenance") as mock_vp:
                mock_rc.return_value = MagicMock(returncode=0, stdout="device\n")
                mock_dp.return_value = MagicMock(model="Test", soc="SM8650", android_ver="14", version_name="1", version_code="1", base_apk_sha256="", librpcsx_so_sha256="", s3core_identity=bench.EXPECTED_CORE_HASH)
                mock_vp.return_value = (True, [])
                ret = bench.main()
                self.assertEqual(ret, 0)

    def test_cli_scheduler_rpcs3_mode(self):
        with patch.object(sys, "argv", ["run-gow3-benchmark.py", "--scheduler", "rpcs3", "--dry-run"]):
            with patch.object(bench, "run_cmd") as mock_rc, patch.object(bench, "fetch_device_provenance") as mock_dp, patch.object(bench, "validate_provenance") as mock_vp:
                mock_rc.return_value = MagicMock(returncode=0, stdout="device\n")
                mock_dp.return_value = MagicMock(model="Test", soc="SM8650", android_ver="14", version_name="1", version_code="1", base_apk_sha256="", librpcsx_so_sha256="", s3core_identity=bench.EXPECTED_CORE_HASH)
                mock_vp.return_value = (True, [])
                ret = bench.main()
                self.assertEqual(ret, 0)

    def test_cli_scheduler_invalid_mode_fails(self):
        with patch.object(sys, "argv", ["run-gow3-benchmark.py", "--scheduler", "turbo"]):
            with self.assertRaises(SystemExit) as cm:
                bench.main()
            self.assertEqual(cm.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
