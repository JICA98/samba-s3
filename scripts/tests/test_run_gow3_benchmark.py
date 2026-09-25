#!/usr/bin/env python3
"""
Unit tests for run-gow3-benchmark.py scheduler mode configuration (Ticket S05 / Experiment E03).
Validates:
- CLI argument parsing for --scheduler (os, rpcs3, alt, invalid modes)
- YAML update and read helpers (preserving formatting, comments, sections)
- Scheduler mode string resolution and alias mapping
- Mocked device configuration flow
"""

import json
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


class TestProvenanceValidation(unittest.TestCase):
    def test_validate_provenance_rejects_missing_apk_hash(self):
        prov = bench.DeviceProvenance(
            serial="test_serial",
            model="Test",
            soc="SM8650",
            android_ver="14",
            package_name=bench.PKG_NAME,
            version_name="1.0",
            version_code="1",
            code_path="/data/app/pkg",
            base_apk_sha256="",  # Missing hash
            librpcsx_so_sha256="abc",
            s3core_identity="141af96fa006f56c25ec335137e92c78b29b17e3",
        )
        ok, errors = bench.validate_provenance(prov, expected_apk_sha="123456")
        self.assertFalse(ok)
        self.assertTrue(any("base.apk SHA-256 missing" in e for e in errors))

    def test_validate_provenance_rejects_missing_so_hash(self):
        prov = bench.DeviceProvenance(
            serial="test_serial",
            model="Test",
            soc="SM8650",
            android_ver="14",
            package_name=bench.PKG_NAME,
            version_name="1.0",
            version_code="1",
            code_path="/data/app/pkg",
            base_apk_sha256="123456",
            librpcsx_so_sha256="",  # Missing hash
            s3core_identity="141af96fa006f56c25ec335137e92c78b29b17e3",
        )
        ok, errors = bench.validate_provenance(prov, expected_so_sha="abcdef")
        self.assertFalse(ok)
        self.assertTrue(any("librpcsx-android.so SHA-256 missing" in e for e in errors))


class TestBenchmarkRunnerExecutionFixtures(unittest.TestCase):
    def setUp(self):
        import tempfile
        self.temp_dir = tempfile.TemporaryDirectory()
        self.outdir = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def _base_provenance(self):
        return bench.DeviceProvenance(
            serial="test_dev",
            model="TestModel",
            soc="SM8650",
            android_ver="14",
            package_name=bench.PKG_NAME,
            version_name="1.0",
            version_code="100",
            code_path="/data/app/pkg",
            base_apk_sha256="apk_hash",
            librpcsx_so_sha256="so_hash",
            s3core_identity=bench.EXPECTED_CORE_HASH,
        )

    @patch("time.sleep", return_value=None)
    @patch.object(bench, "fetch_device_provenance")
    @patch.object(bench, "run_cmd")
    @patch.object(bench, "adb_shell")
    @patch.object(bench, "get_process_identity")
    @patch("subprocess.run")
    def test_crash_then_cleanup_fixture(self, mock_subproc, mock_get_id, mock_adb, mock_run_cmd, mock_prov, mock_sleep):
        """
        WORKER.md Acceptance Gate:
        Simulate qualification crash followed by successful debug-stop-game.sh cleanup.
        Runner MUST return non-zero exit code (exit 4), manifest verdict MUST be FAIL_CRASH,
        and stop_reason MUST NOT be overridden by cleanup success.
        """
        mock_prov.return_value = self._base_provenance()
        mock_adb.return_value = MagicMock(returncode=0, stdout="test")
        mock_run_cmd.return_value = MagicMock(returncode=0, stdout="device\n")

        # Screencap succeeds and creates device_screen.png
        def fake_screencap(*args, **kwargs):
            (self.outdir / "device_screen.png").write_bytes(b"PNGDATA")
            return MagicMock(returncode=0, stdout=b"PNGDATA")
        mock_subproc.side_effect = fake_screencap

        # Process dies during qualification loop
        id_launch = bench.ProcessIdentity(pid="4321", start_time="999", boot_id="boot_a")
        mock_get_id.side_effect = [
            id_launch,  # Initial post-launch check
            id_launch,  # Pre-gameplay check
            None,       # Qualification loop crash!
        ]

        # Dummy log and analysis output
        (self.outdir / "logcat-process.log").write_text("dummy log")
        (self.outdir / "frame-analysis.json").write_text('{"interval_throughput_fps": 0.0}')

        test_args = [
            "run-gow3-benchmark.py",
            "--serial", "test_dev",
            "--outdir", str(self.outdir),
            "--no-strict-hashes",
            "--duration", "1",
        ]
        with patch.object(sys, "argv", test_args):
            exit_code = bench.main()

        # 1. Runner must return nonzero exit code 4 for crash
        self.assertEqual(exit_code, 4)

        # 2. Manifest must record FAIL_CRASH
        manifest_path = self.outdir / "run-manifest.json"
        self.assertTrue(manifest_path.exists())
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(manifest["first_failure"], "FAIL_CRASH")
        self.assertEqual(manifest["verdict"], "FAIL_CRASH")
        self.assertEqual(manifest["stop_reason"], "FAIL_CRASH")

        # 3. Cleanup success must be recorded without overriding failure
        self.assertTrue(manifest["cleanup"]["clean_stop"])
        self.assertEqual(manifest["cleanup"]["result"], "SUCCESS")

    @patch("time.sleep", return_value=None)
    @patch.object(bench, "fetch_device_provenance")
    @patch.object(bench, "run_cmd")
    @patch.object(bench, "adb_shell")
    @patch.object(bench, "get_process_identity")
    @patch("subprocess.run")
    def test_pid_replacement_fixture(self, mock_subproc, mock_get_id, mock_adb, mock_run_cmd, mock_prov, mock_sleep):
        """
        Simulate original process death followed by a replacement process taking over.
        Runner MUST catch PID change and fail with FAIL_REPLACEMENT_PID.
        """
        mock_prov.return_value = self._base_provenance()
        mock_adb.return_value = MagicMock(returncode=0, stdout="test")
        mock_run_cmd.return_value = MagicMock(returncode=0, stdout="device\n")

        (self.outdir / "device_screen.png").write_bytes(b"PNGDATA")
        mock_subproc.return_value = MagicMock(returncode=0, stdout=b"PNGDATA")

        id_launch = bench.ProcessIdentity(pid="1000", start_time="100", boot_id="boot_a")
        id_replaced = bench.ProcessIdentity(pid="2000", start_time="200", boot_id="boot_a")
        mock_get_id.side_effect = [
            id_launch,    # Initial post-launch check
            id_launch,    # Pre-gameplay check
            id_replaced,  # Process replaced during qualification loop!
        ]

        (self.outdir / "logcat-process.log").write_text("dummy log")
        (self.outdir / "frame-analysis.json").write_text('{"interval_throughput_fps": 0.0}')

        test_args = [
            "run-gow3-benchmark.py",
            "--serial", "test_dev",
            "--outdir", str(self.outdir),
            "--no-strict-hashes",
            "--duration", "1",
        ]
        with patch.object(sys, "argv", test_args):
            exit_code = bench.main()

        self.assertEqual(exit_code, 4)
        manifest_path = self.outdir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(manifest["first_failure"], "FAIL_REPLACEMENT_PID")
        self.assertEqual(manifest["verdict"], "FAIL_REPLACEMENT_PID")

    @patch("time.sleep", return_value=None)
    @patch.object(bench, "fetch_device_provenance")
    @patch.object(bench, "run_cmd")
    @patch.object(bench, "adb_shell")
    @patch.object(bench, "get_process_identity")
    @patch("subprocess.run")
    def test_missing_evidence_fixture(self, mock_subproc, mock_get_id, mock_adb, mock_run_cmd, mock_prov, mock_sleep):
        """
        Missing evidence (e.g. screencap fails) must result in INCONCLUSIVE_EVIDENCE,
        never a false PASS.
        """
        mock_prov.return_value = self._base_provenance()
        mock_adb.return_value = MagicMock(returncode=0, stdout="test")
        mock_run_cmd.return_value = MagicMock(returncode=0, stdout="device\n")

        # Screencap fails
        mock_subproc.return_value = MagicMock(returncode=1, stdout=b"")

        id_launch = bench.ProcessIdentity(pid="1000", start_time="100", boot_id="boot_a")
        mock_get_id.return_value = id_launch

        (self.outdir / "logcat-process.log").write_text("dummy log")
        (self.outdir / "frame-analysis.json").write_text('{"interval_throughput_fps": 60.0}')

        test_args = [
            "run-gow3-benchmark.py",
            "--serial", "test_dev",
            "--outdir", str(self.outdir),
            "--no-strict-hashes",
            "--duration", "1",
        ]
        with patch.object(sys, "argv", test_args):
            exit_code = bench.main()

        # Nonzero exit code 2 for inconclusive evidence
        self.assertEqual(exit_code, 2)
        manifest_path = self.outdir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(manifest["first_failure"], "INCONCLUSIVE_EVIDENCE")
        self.assertEqual(manifest["verdict"], "INCONCLUSIVE_EVIDENCE")

    @patch("time.sleep", return_value=None)
    @patch.object(bench, "fetch_device_provenance")
    @patch.object(bench, "run_cmd")
    @patch.object(bench, "adb_shell")
    @patch.object(bench, "get_process_identity")
    @patch("subprocess.run")
    def test_clean_run_pass_fixture(self, mock_subproc, mock_get_id, mock_adb, mock_run_cmd, mock_prov, mock_sleep):
        """
        Complete clean run with intact process identity, valid evidence, and clean stop
        must yield PASS with exit code 0.
        """
        mock_prov.return_value = self._base_provenance()
        mock_adb.return_value = MagicMock(returncode=0, stdout="test")
        mock_run_cmd.return_value = MagicMock(returncode=0, stdout="device\n")

        def fake_screencap(*args, **kwargs):
            (self.outdir / "device_screen.png").write_bytes(b"PNGDATA")
            return MagicMock(returncode=0, stdout=b"PNGDATA")
        mock_subproc.side_effect = fake_screencap

        id_launch = bench.ProcessIdentity(pid="1000", start_time="100", boot_id="boot_a")
        mock_get_id.return_value = id_launch

        (self.outdir / "logcat-process.log").write_text("dummy log")
        (self.outdir / "frame-analysis.json").write_text('{"interval_throughput_fps": 60.0, "qualified_frames": 60}')

        test_args = [
            "run-gow3-benchmark.py",
            "--serial", "test_dev",
            "--outdir", str(self.outdir),
            "--no-strict-hashes",
            "--duration", "1",
        ]
        with patch.object(sys, "argv", test_args), patch.object(bench, "ROOT_DIR", self.outdir):
            exit_code = bench.main()

        self.assertEqual(exit_code, 0)
        manifest_path = self.outdir / "run-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertIsNone(manifest["first_failure"])
        self.assertEqual(manifest["verdict"], "PASS")
        self.assertEqual(manifest["cleanup"]["result"], "SUCCESS")
        self.assertEqual(manifest["launch_identity"]["pid"], "1000")

    @patch("time.sleep")
    @patch.object(bench, "fetch_device_provenance")
    @patch.object(bench, "run_cmd")
    @patch.object(bench, "adb_shell")
    @patch.object(bench, "get_process_identity")
    def test_early_launch_failure_writes_manifest(self, mock_get_id, mock_adb, mock_run_cmd, mock_prov, mock_sleep):
        """CR01: Launch command failure must write manifest with FAIL_LAUNCH and return exit 3."""
        mock_prov.return_value = self._base_provenance()
        mock_adb.return_value = MagicMock(returncode=0, stdout="test")

        def fake_run(cmd, timeout=None):
            if any("debug-launch-game.sh" in str(c) for c in cmd):
                return MagicMock(returncode=1, stderr="Activity failed to start")
            return MagicMock(returncode=0, stdout="device\n")

        mock_run_cmd.side_effect = fake_run

        test_args = [
            "run-gow3-benchmark.py",
            "--serial", "test_dev",
            "--outdir", str(self.outdir),
            "--no-strict-hashes",
        ]
        with patch.object(sys, "argv", test_args), patch.object(bench, "ROOT_DIR", self.outdir):
            exit_code = bench.main()

        self.assertEqual(exit_code, 3)
        manifest_path = self.outdir / "run-manifest.json"
        self.assertTrue(manifest_path.exists())
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(manifest["first_failure"], "FAIL_LAUNCH")
        self.assertEqual(manifest["verdict"], "FAIL_LAUNCH")
        self.assertIsNone(manifest["launch_identity"])

    @patch("time.sleep")
    @patch.object(bench, "fetch_device_provenance")
    @patch.object(bench, "run_cmd")
    @patch.object(bench, "adb_shell")
    @patch.object(bench, "get_process_identity")
    @patch("subprocess.run")
    def test_zero_qualified_frames_fails_guest_progress(self, mock_subproc, mock_get_id, mock_adb, mock_run_cmd, mock_prov, mock_sleep):
        """CR01 / CR03: Zero qualified frames during gameplay window must latch FAIL_GUEST_PROGRESS and exit 1."""
        mock_prov.return_value = self._base_provenance()
        mock_adb.return_value = MagicMock(returncode=0, stdout="test")
        mock_run_cmd.return_value = MagicMock(returncode=0, stdout="device\n")

        def fake_screencap(*args, **kwargs):
            (self.outdir / "device_screen.png").write_bytes(b"PNGDATA")
            return MagicMock(returncode=0, stdout=b"PNGDATA")
        mock_subproc.side_effect = fake_screencap

        mock_get_id.return_value = bench.ProcessIdentity(pid="1000", start_time="100", boot_id="boot_a")

        (self.outdir / "logcat-process.log").write_text("dummy log")
        # Qualified frames == 0
        (self.outdir / "frame-analysis.json").write_text('{"interval_throughput_fps": 0.0, "qualified_frames": 0}')

        test_args = [
            "run-gow3-benchmark.py",
            "--serial", "test_dev",
            "--outdir", str(self.outdir),
            "--no-strict-hashes",
            "--duration", "1",
        ]
        with patch.object(sys, "argv", test_args), patch.object(bench, "ROOT_DIR", self.outdir):
            exit_code = bench.main()

        self.assertEqual(exit_code, 1)
        manifest = json.loads((self.outdir / "run-manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["first_failure"], "FAIL_GUEST_PROGRESS")
        self.assertEqual(manifest["verdict"], "FAIL_GUEST_PROGRESS")

    @patch("time.sleep")
    @patch.object(bench, "fetch_device_provenance")
    @patch.object(bench, "run_cmd")
    def test_provenance_manifest_rejection(self, mock_run_cmd, mock_prov, mock_sleep):
        """CR00 / CR01: Strict provenance rejection against manifest returns exit 3 and writes manifest."""
        mock_prov.return_value = self._base_provenance()
        mock_run_cmd.return_value = MagicMock(returncode=0, stdout="device\n")

        prov_manifest = self.outdir / "test-prov-manifest.json"
        prov_manifest.write_text(json.dumps({
            "package": {"base_apk_sha256": "mismatch_sha", "librpcsx_so_sha256": "mismatch_so"},
            "git": {"rpcsx_submodule_head": "mismatch_core"}
        }), encoding="utf-8")

        test_args = [
            "run-gow3-benchmark.py",
            "--serial", "test_dev",
            "--outdir", str(self.outdir),
            "--provenance-manifest", str(prov_manifest),
        ]
        with patch.object(sys, "argv", test_args), patch.object(bench, "ROOT_DIR", self.outdir):
            exit_code = bench.main()

        self.assertEqual(exit_code, 3)
        manifest = json.loads((self.outdir / "run-manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["first_failure"], "FAIL_PROVENANCE")
        self.assertEqual(manifest["verdict"], "FAIL_PROVENANCE")

    @patch("time.sleep")
    @patch.object(bench, "fetch_device_provenance")
    @patch.object(bench, "run_cmd")
    @patch.object(bench, "adb_shell")
    @patch.object(bench, "get_process_identity")
    def test_prior_logcat_crash_buffer_preserved(self, mock_get_id, mock_adb, mock_run_cmd, mock_prov, mock_sleep):
        """CR01: Prior logcat crash buffer must be preserved to file before clearing."""
        mock_prov.return_value = self._base_provenance()
        mock_run_cmd.return_value = MagicMock(returncode=0, stdout="device\n")

        def fake_adb(serial, cmd):
            if cmd == "logcat -d":
                return MagicMock(returncode=0, stdout="PRIOR_CRASH_LOGCAT_LINES")
            return MagicMock(returncode=0, stdout="test")
        mock_adb.side_effect = fake_adb

        mock_get_id.return_value = None  # Die at launch

        test_args = [
            "run-gow3-benchmark.py",
            "--serial", "test_dev",
            "--outdir", str(self.outdir),
            "--no-strict-hashes",
        ]
        with patch.object(sys, "argv", test_args), patch.object(bench, "ROOT_DIR", self.outdir):
            bench.main()

        prior_log = self.outdir / "logcat-prior-crash-buffer.log"
        self.assertTrue(prior_log.exists())
        self.assertIn("PRIOR_CRASH_LOGCAT_LINES", prior_log.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()



