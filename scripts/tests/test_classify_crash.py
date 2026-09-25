#!/usr/bin/env python3
"""
Unit tests for scripts/perf/classify-crash.py.

Verifies:
1. Parsing of dumpsys activity exit-info blocks and byte conversions.
2. Exit matching by PID, session ID, and timestamp window (rejecting wrong PID and recycled PIDs).
3. Distinguishing :ppu_compile worker exits from primary emulator process.
4. Extraction of native crash details (signal, status, pc, backtrace) and Java stack traces.
5. All 8 typed classifications:
   - NATIVE_CRASH
   - JAVA_EXCEPTION
   - MEMORY_PRESSURE_KILL (verifying SIGKILL without memory pressure is NOT classified as LMKD)
   - ANR
   - DELIBERATE_STOP
   - VULKAN_DEVICE_LOSS
   - SYSTEM_SERVER_KILL
   - UNKNOWN
6. End-to-end evidence directory diagnosis on real benchmark artifacts.
"""

import importlib.util
import json
import sys
import unittest
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT_DIR / "scripts" / "perf"))

spec = importlib.util.spec_from_file_location(
    "classify_crash",
    ROOT_DIR / "scripts" / "perf" / "classify-crash.py"
)
cc = importlib.util.module_from_spec(spec)
sys.modules["classify_crash"] = cc
spec.loader.exec_module(cc)

ExitClassification = cc.ExitClassification
ProcessExitRecord = cc.ProcessExitRecord
parse_dumpsys_exit_info = cc.parse_dumpsys_exit_info
match_exit_record = cc.match_exit_record
classify_exit = cc.classify_exit
extract_native_crash_info = cc.extract_native_crash_info
extract_java_stack_trace = cc.extract_java_stack_trace
diagnose_evidence = cc.diagnose_evidence
parse_bytes = cc.parse_bytes


SAMPLE_DUMPSYS = """
ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)
Last Timestamp of Persistence Into Persistent Storage: 2026-09-25 02:39:26.765
  package: com.zenithblue.sambas3
    Historical Process Exit for uid=10463
        ApplicationExitInfo #0:
          timestamp=2026-09-25 02:41:35.649 pid=10950 realUid=10463 packageUid=10463 definingUid=10463 user=0
          process=com.zenithblue.sambas3 reason=16 (PACKAGE UPDATED) subreason=0 (UNKNOWN) status=0
          importance=400 pss=0.00 rss=270MB description=stop com.zenithblue.sambas3 due to installPackageLI state=empty trace=null
        ApplicationExitInfo #1:
          timestamp=2026-09-25 00:33:03.880 pid=4189 realUid=10463 packageUid=10463 definingUid=10463 user=0
          process=com.zenithblue.sambas3 reason=3 (LOW_MEMORY) subreason=0 (UNKNOWN) status=0
          importance=100 pss=0.00 rss=2.5GB description=null state=empty trace=null
        ApplicationExitInfo #2:
          timestamp=2026-09-24 23:26:31.481 pid=4475 realUid=10463 packageUid=10463 definingUid=10463 user=0
          process=com.zenithblue.sambas3 reason=2 (SIGNALED) subreason=0 (UNKNOWN) status=6
          importance=100 pss=0.00 rss=1.9GB description=null state=empty trace=null
        ApplicationExitInfo #3:
          timestamp=2026-09-24 23:24:02.592 pid=5586 realUid=10463 packageUid=10463 definingUid=10463 user=0
          process=com.zenithblue.sambas3:ppu_compile reason=2 (SIGNALED) subreason=0 (UNKNOWN) status=9
          importance=100 pss=0.00 rss=0.00 description=null state=empty trace=null
"""


class TestClassifyCrash(unittest.TestCase):

    def test_parse_dumpsys_exit_info(self):
        records = parse_dumpsys_exit_info(SAMPLE_DUMPSYS)
        self.assertEqual(len(records), 4)

        # Record 0: PACKAGE_UPDATED
        r0 = records[0]
        self.assertEqual(r0.pid, 10950)
        self.assertEqual(r0.process_name, "com.zenithblue.sambas3")
        self.assertEqual(r0.reason, 16)
        self.assertEqual(r0.reason_name, "PACKAGE UPDATED")
        self.assertEqual(r0.rss_bytes, 270 * 1024 * 1024)
        self.assertFalse(r0.is_ppu_compile)
        self.assertTrue(r0.is_primary_process)

        # Record 1: LOW_MEMORY
        r1 = records[1]
        self.assertEqual(r1.pid, 4189)
        self.assertEqual(r1.reason, 3)
        self.assertEqual(r1.reason_name, "LOW_MEMORY")
        self.assertEqual(r1.rss_bytes, int(2.5 * 1024 * 1024 * 1024))

        # Record 2: SIGNALED 6 (SIGABRT)
        r2 = records[2]
        self.assertEqual(r2.pid, 4475)
        self.assertEqual(r2.status, 6)
        self.assertEqual(r2.signal_name, "SIGABRT")

        # Record 3: :ppu_compile
        r3 = records[3]
        self.assertEqual(r3.pid, 5586)
        self.assertTrue(r3.is_ppu_compile)
        self.assertFalse(r3.is_primary_process)

    def test_parse_bytes(self):
        self.assertEqual(parse_bytes("0.00"), 0)
        self.assertEqual(parse_bytes("100KB"), 100 * 1024)
        self.assertEqual(parse_bytes("250MB"), 250 * 1024 * 1024)
        self.assertEqual(parse_bytes("3.5GB"), int(3.5 * 1024 * 1024 * 1024))
        self.assertEqual(parse_bytes("1024B"), 1024)

    def test_match_exact_pid_and_timestamp(self):
        records = parse_dumpsys_exit_info(SAMPLE_DUMPSYS)
        matched, method = match_exit_record(records, target_pid=4475)
        self.assertIsNotNone(matched)
        self.assertEqual(matched.pid, 4475)
        self.assertIn("pid", method)

    def test_reject_wrong_pid_no_fallback_to_latest(self):
        # Target PID 9999 has no record; must NOT return record 0 (10950)
        records = parse_dumpsys_exit_info(SAMPLE_DUMPSYS)
        matched, method = match_exit_record(records, target_pid=9999)
        self.assertIsNone(matched)
        self.assertEqual(method, "pid_not_found_in_exit_info")

    def test_reject_recycled_pid_before_session(self):
        records = [
            ProcessExitRecord(
                pid=5454,
                process_name="com.zenithblue.sambas3",
                reason=2,
                reason_name="SIGNALED",
                status=9,
                timestamp_ms=1000000,
            )
        ]
        # Session started at ms 2000000, 1000s after the exit
        matched, method = match_exit_record(
            records, target_pid=5454, session_start_ms=2000000
        )
        self.assertIsNone(matched)
        self.assertEqual(method, "pid_recycled_prior_exit")

    def test_distinguish_ppu_compile(self):
        records = parse_dumpsys_exit_info(SAMPLE_DUMPSYS)
        # Primary match must not match PID 5586 since it is :ppu_compile
        matched, method = match_exit_record(records, target_pid=5586)
        self.assertIsNone(matched)

    def test_classify_native_crash(self):
        tombstone = """
        pid: 4475, tid: 4475, name: com.zenithblue  >>> com.zenithblue.sambas3 <<<
        signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xdeadbeef
        Abort message: 'assertion failed in RSXThread.cpp:456'
            pc  0000007e0c4b2b2c  /apex/com.android.runtime/lib64/bionic/libc.so (abort+168)
        backtrace:
              #00 pc 000000000004fb2c  /apex/com.android.runtime/lib64/bionic/libc.so (abort+168)
              #01 pc 00000000005a3b20  /data/app/~~/lib/arm64/librpcsx-android.so (step+44)
        """
        record = ProcessExitRecord(
            pid=4475,
            process_name="com.zenithblue.sambas3",
            reason=5,
            reason_name="CRASH_NATIVE",
            status=11,
            trace=tombstone,
        )
        diag = classify_exit(record, evidence_text="")
        self.assertEqual(diag.classification, ExitClassification.NATIVE_CRASH)
        self.assertEqual(diag.signal, 11)
        self.assertEqual(diag.signal_name, "SIGSEGV")
        self.assertEqual(diag.pc, "0000007e0c4b2b2c")
        self.assertEqual(len(diag.backtrace), 2)
        self.assertIn("librpcsx-android.so", diag.backtrace[1])

    def test_classify_java_exception(self):
        java_trace = """
        FATAL EXCEPTION: main
        Process: com.zenithblue.sambas3, PID: 1234
        java.lang.IllegalStateException: Vulkan surface destroyed
            at com.zenithblue.sambas3.RPCSXActivity.onDestroy(RPCSXActivity.kt:50)
            at android.app.Activity.performDestroy(Activity.java:8500)
        """
        record = ProcessExitRecord(
            pid=1234,
            process_name="com.zenithblue.sambas3",
            reason=4,
            reason_name="CRASH",
            trace=java_trace,
        )
        diag = classify_exit(record, evidence_text=java_trace)
        self.assertEqual(diag.classification, ExitClassification.JAVA_EXCEPTION)
        self.assertIsNotNone(diag.stack_trace)
        self.assertIn("IllegalStateException", diag.stack_trace)
        self.assertIn("RPCSXActivity.onDestroy", diag.stack_trace)

    def test_classify_direct_low_memory(self):
        record = ProcessExitRecord(
            pid=4189,
            process_name="com.zenithblue.sambas3",
            reason=3,
            reason_name="LOW_MEMORY",
            rss_bytes=2560 * 1024 * 1024,
        )
        diag = classify_exit(record)
        self.assertEqual(diag.classification, ExitClassification.MEMORY_PRESSURE_KILL)
        self.assertTrue(diag.memory_pressure_evidence)

    def test_classify_sigkill_with_lmkd_evidence(self):
        record = ProcessExitRecord(
            pid=32654,
            process_name="com.zenithblue.sambas3",
            reason=2,
            reason_name="SIGNALED",
            status=9,
            rss_bytes=3200 * 1024 * 1024,
        )
        logs = "09-24 23:23:09.001 800 800 I lmkd: kill 'com.zenithblue.sambas3' (32654) to free 3355443kB"
        diag = classify_exit(record, evidence_text=logs)
        self.assertEqual(diag.classification, ExitClassification.MEMORY_PRESSURE_KILL)
        self.assertTrue(diag.memory_pressure_evidence)

    def test_sigkill_without_memory_pressure_is_not_lmkd(self):
        record = ProcessExitRecord(
            pid=5454,
            process_name="com.zenithblue.sambas3",
            reason=2,
            reason_name="SIGNALED",
            status=9,
        )
        diag = classify_exit(record, evidence_text="routine execution without errors")
        self.assertEqual(diag.classification, ExitClassification.UNKNOWN)
        self.assertFalse(diag.memory_pressure_evidence)

    def test_classify_anr(self):
        record = ProcessExitRecord(
            pid=8888,
            process_name="com.zenithblue.sambas3",
            reason=6,
            reason_name="ANR",
        )
        diag = classify_exit(record)
        self.assertEqual(diag.classification, ExitClassification.ANR)

    def test_classify_deliberate_stop(self):
        # 1. Clean stop keyword
        diag1 = classify_exit(None, stop_reason="DEBUG_STOP_GAME")
        self.assertEqual(diag1.classification, ExitClassification.DELIBERATE_STOP)

        # 2. User requested remove task
        record_user = ProcessExitRecord(
            pid=6927,
            process_name="com.zenithblue.sambas3",
            reason=10,
            reason_name="USER_REQUESTED",
            subreason=22,
            subreason_name="REMOVE_TASK",
        )
        diag2 = classify_exit(record_user)
        self.assertEqual(diag2.classification, ExitClassification.DELIBERATE_STOP)

        # 3. PPU compile worker normal recycling
        record_ppu = ProcessExitRecord(
            pid=5586,
            process_name="com.zenithblue.sambas3:ppu_compile",
            reason=2,
            reason_name="SIGNALED",
            status=9,
        )
        diag3 = classify_exit(record_ppu)
        self.assertEqual(diag3.classification, ExitClassification.DELIBERATE_STOP)
        self.assertTrue(diag3.is_ppu_compile_process)

    def test_classify_vulkan_device_loss(self):
        record = ProcessExitRecord(
            pid=4475,
            process_name="com.zenithblue.sambas3",
            reason=2,
            reason_name="SIGNALED",
            status=6,
        )
        evidence = "E/RPCSX: VK_ERROR_DEVICE_LOST: Device lost during vkQueueSubmit"
        diag = classify_exit(record, evidence_text=evidence)
        self.assertEqual(diag.classification, ExitClassification.VULKAN_DEVICE_LOSS)

    def test_classify_system_server_kill(self):
        record = ProcessExitRecord(
            pid=10950,
            process_name="com.zenithblue.sambas3",
            reason=16,
            reason_name="PACKAGE_UPDATED",
            description="stop com.zenithblue.sambas3 due to installPackageLI",
        )
        diag = classify_exit(record)
        self.assertEqual(diag.classification, ExitClassification.SYSTEM_SERVER_KILL)

    def test_diagnose_evidence_directory(self):
        evidence_dir = ROOT_DIR / "docs" / "benchmarks" / "evidence-e01-m01-metrics"
        if not evidence_dir.is_dir():
            self.skipTest("evidence directory not found")

        # In evidence-e01-m01-metrics, run-manifest PID is 22188
        diag = diagnose_evidence(evidence_dir)
        self.assertIsNotNone(diag)
        self.assertEqual(diag.pid, 22188)
        self.assertEqual(diag.matched_by, "pid_not_found_in_exit_info")
        self.assertGreaterEqual(len(diag.ppu_compile_exits), 1)

        # Diagnose explicit PID 4189 (LOW_MEMORY) in that directory
        diag_4189 = diagnose_evidence(evidence_dir, target_pid=4189)
        self.assertEqual(diag_4189.classification, ExitClassification.MEMORY_PRESSURE_KILL)
        self.assertEqual(diag_4189.pid, 4189)

    def test_native_crash_with_cleanup_stop_retains_crash(self):
        """CR01 / V01 / V02: A native crash followed by DEBUG_STOP_GAME cleanup must NOT pass as DELIBERATE_STOP."""
        record = ProcessExitRecord(
            pid=7777,
            process_name="com.zenithblue.sambas3",
            reason=5,  # CRASH_NATIVE
            reason_name="CRASH_NATIVE",
            status=11,  # SIGSEGV
            description="segmentation fault in librpcsx-android.so",
        )
        diag = classify_exit(record, stop_reason="DEBUG_STOP_GAME")
        self.assertEqual(diag.classification, ExitClassification.NATIVE_CRASH)
        self.assertEqual(diag.signal, 11)
        self.assertEqual(diag.signal_name, "SIGSEGV")
        self.assertTrue(diag.cleanup_attempted)
        self.assertTrue(diag.cleanup_success)
        self.assertEqual(diag.first_failure, "FAIL_CRASH")

    def test_java_crash_with_cleanup_stop_retains_java(self):
        """CR01: Java Exception followed by DEBUG_STOP_GAME cleanup retains JAVA_EXCEPTION."""
        record = ProcessExitRecord(
            pid=7778,
            process_name="com.zenithblue.sambas3",
            reason=4,  # CRASH
            reason_name="CRASH",
            trace="FATAL EXCEPTION: main\njava.lang.NullPointerException: Surface lost",
        )
        diag = classify_exit(record, evidence_text=record.trace, stop_reason="DEBUG_STOP_GAME")
        self.assertEqual(diag.classification, ExitClassification.JAVA_EXCEPTION)
        self.assertTrue(diag.cleanup_attempted)
        self.assertTrue(diag.cleanup_success)
        self.assertEqual(diag.first_failure, "FAIL_CRASH")

    def test_nonzero_exit_self_is_not_clean(self):
        """CR01: EXIT_SELF with non-zero status is an abnormal termination, not clean deliberate stop."""
        record = ProcessExitRecord(
            pid=7779,
            process_name="com.zenithblue.sambas3",
            reason=1,  # EXIT_SELF
            reason_name="EXIT_SELF",
            status=1,  # non-zero error exit
        )
        diag = classify_exit(record)
        self.assertNotEqual(diag.classification, ExitClassification.DELIBERATE_STOP)
        self.assertEqual(diag.classification, ExitClassification.UNKNOWN)
        self.assertIn("abnormally", diag.summary)

    def test_generic_clean_word_does_not_trigger_deliberate_stop(self):
        """CR01: Generic word 'clean' in stop_reason must NOT falsely trigger DELIBERATE_STOP."""
        diag = classify_exit(None, stop_reason="not a clean run, encountered unhandled error")
        self.assertNotEqual(diag.classification, ExitClassification.DELIBERATE_STOP)
        self.assertEqual(diag.classification, ExitClassification.UNKNOWN)

    def test_exact_package_matching_rejects_unrelated_process_without_colon(self):
        """CR01: Match exit record must strictly match package name, not any process without ':'."""
        records = [
            ProcessExitRecord(
                pid=999,
                process_name="com.android.systemui",  # Has no colon!
                reason=1,
                reason_name="EXIT_SELF",
                status=0,
            )
        ]
        matched, method = match_exit_record(records, target_pid=999, package_name="com.zenithblue.sambas3")
        self.assertIsNone(matched)


if __name__ == "__main__":
    unittest.main()

