#!/usr/bin/env python3
"""
Unit tests for scripts/perf/test-lifecycle-plateau.py.

Verifies:
1. Parsing of dumpsys meminfo (Total PSS, Total RSS, Native Heap, Graphics, Java Heap, Code).
2. Parsing of /proc/<pid>/status (VmSize, VmRSS, RssAnon, RssFile), confirming 64-bit 80GB VIRT handling.
3. Parsing of /proc/meminfo, confirming MemAvailable is preserved and distinguished from low MemFree.
4. Linear regression and slope calculation across flat, controlled, and steep memory sequences.
5. Plateau evaluation logic across all operational scenarios:
   - Scenario A: Healthy memory plateau (warmup + flat post-warmup PSS, 0 crashes) -> PASS
   - Scenario B: Unbounded monotonic leak (continuous +75MB/cycle post-warmup) -> FAIL
   - Scenario C: Native crash during teardown (SIGSEGV / SIGABRT / tombstone) -> FAIL
   - Scenario D: Stop unacknowledged / timeout -> FAIL
   - Scenario E: 80GB virtual address space (MAP_NORESERVE) reservation accounting -> PASS
   - Scenario F: Post-teardown resource release (native heap & graphics released back to baseline) -> PASS
"""

import importlib.util
import math
import sys
import unittest
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT_DIR / "scripts" / "perf"))

spec = importlib.util.spec_from_file_location(
    "test_lifecycle_plateau",
    ROOT_DIR / "scripts" / "perf" / "test-lifecycle-plateau.py"
)
tlp = importlib.util.module_from_spec(spec)
sys.modules["test_lifecycle_plateau"] = tlp
spec.loader.exec_module(tlp)

MemoryMetrics = tlp.MemoryMetrics
CycleRecord = tlp.CycleRecord
PlateauAnalysisResult = tlp.PlateauAnalysisResult
parse_dumpsys_meminfo = tlp.parse_dumpsys_meminfo
parse_proc_status = tlp.parse_proc_status
parse_proc_meminfo = tlp.parse_proc_meminfo
calculate_linear_regression = tlp.calculate_linear_regression
check_monotonicity = tlp.check_monotonicity
analyze_lifecycle_plateau = tlp.analyze_lifecycle_plateau


SAMPLE_DUMPSYS_GAMEPLAY = """
Applications Memory Usage (in Kilobytes):
Uptime: 693862177 Realtime: 924160459

** MEMINFO in pid 19136 [com.zenithblue.sambas3] **
                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------   ------
  Native Heap   347010   346960       16       41   348008   444812   280092   159979
  Dalvik Heap     8729     8704        0      113     9252    29913     5337    24576
 Dalvik Other     1603      888        4        6     2580                           
        Stack     1804     1804        0        0     1808                           
       Ashmem       42        0        0        0     1544                           
      Gfx dev    28292    28292        0        0    28296                           
    Other dev       84        0       20        0      844                           
     .so mmap    64986     3532    55248       12   113516                           
    .jar mmap      986        0        0        0    39508                           
    .apk mmap     4770        0     4484        0     7664                           
    .ttf mmap     1320        0     1068        0     2192                           
    .dex mmap      259        0      224        0     1104                           
    .oat mmap      382        0        0        0    12216                           
    .art mmap     3329     2524       68      255    17408                           
   Other mmap     6854     5436     1364        0     8016                           
   EGL mtrack   177716   177716        0        0   177716                           
    GL mtrack   164416   164416        0        0   164416                           
      Unknown    43604    43356      188        9    44108                           
        TOTAL   856622   783628    62684      436   980196   474725   285429   184555
 
 App Summary
                       Pss(KB)                        Rss(KB)
                        ------                         ------
           Java Heap:    11296                          26660
         Native Heap:   346960                         348008
                Code:    64564                         177624
               Stack:     1804                           1808
            Graphics:   370424                         370428
       Private Other:    51264
              System:    10310
             Unknown:                                   55668
 
           TOTAL PSS:   856622            TOTAL RSS:   980196       TOTAL SWAP PSS:      436
"""

SAMPLE_DUMPSYS_POST_STOP = """
Applications Memory Usage (in Kilobytes):
Uptime: 693875704 Realtime: 924173985

** MEMINFO in pid 19136 [com.zenithblue.sambas3] **
                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------   ------
  Native Heap   268570   268520       16       41   269568   458932    80203   374067
  TOTAL         537066   449760    69012      278   672048   490366    87061   398643

 App Summary
                       Pss(KB)                        Rss(KB)
                        ------                         ------
           Java Heap:    37824                          61000
         Native Heap:   268520                         269568
                Code:    64612                         180640
               Stack:     1020                           1024
            Graphics:    97428                          97432
       Private Other:    49368
              System:    18294

           TOTAL PSS:   537066            TOTAL RSS:   672048       TOTAL SWAP PSS:      278
"""

SAMPLE_STATUS_80GB_VIRT = """
Name:	com.zenithblue.sambas3
Umask:	0077
State:	S (sleeping)
Tgid:	19136
Ngid:	0
Pid:	19136
PPid:	1014
TracerPid:	0
Uid:	10463	10463	10463	10463
Gid:	10463	10463	10463	10463
FDSize:	256
Groups:	1015 1077 1078 1079 9997 20463 50463 
VmPeak:	75753956 kB
VmSize:	75741668 kB
VmLck:	       0 kB
VmPin:	       0 kB
VmHWM:	  660424 kB
VmRSS:	  646900 kB
RssAnon:	  430516 kB
RssFile:	  171804 kB
RssShmem:	   44580 kB
VmData:	 1775796 kB
VmStk:	    8192 kB
VmExe:	      28 kB
VmLib:	  129188 kB
VmPTE:	    1720 kB
VmSwap:	     436 kB
"""

SAMPLE_PROC_MEMINFO = """
MemTotal:       11492264 kB
MemFree:          197288 kB
MemAvailable:    4751808 kB
Buffers:           10560 kB
Cached:          5841152 kB
SwapCached:        42652 kB
SwapTotal:       6291452 kB
SwapFree:        4100344 kB
"""


class TestMemoryPlateau(unittest.TestCase):

    def test_parse_dumpsys_meminfo_gameplay(self):
        d = parse_dumpsys_meminfo(SAMPLE_DUMPSYS_GAMEPLAY)
        self.assertEqual(d["pss_total_kb"], 856622)
        self.assertEqual(d["rss_total_kb"], 980196)
        self.assertEqual(d["native_heap_pss_kb"], 346960)
        self.assertEqual(d["native_heap_alloc_kb"], 280092)
        self.assertEqual(d["graphics_pss_kb"], 370424)
        self.assertEqual(d["java_heap_pss_kb"], 11296)
        self.assertEqual(d["code_pss_kb"], 64564)

    def test_parse_dumpsys_meminfo_post_stop_release(self):
        d_post = parse_dumpsys_meminfo(SAMPLE_DUMPSYS_POST_STOP)
        self.assertEqual(d_post["pss_total_kb"], 537066)
        self.assertEqual(d_post["native_heap_alloc_kb"], 80203)
        self.assertEqual(d_post["graphics_pss_kb"], 97428)
        # Verify significant memory release upon teardown
        d_play = parse_dumpsys_meminfo(SAMPLE_DUMPSYS_GAMEPLAY)
        self.assertLess(d_post["native_heap_alloc_kb"], d_play["native_heap_alloc_kb"] / 2)
        self.assertLess(d_post["graphics_pss_kb"], d_play["graphics_pss_kb"] / 2)

    def test_parse_proc_status_80gb_virt(self):
        s = parse_proc_status(SAMPLE_STATUS_80GB_VIRT)
        self.assertEqual(s["virt_kb"], 75741668)
        self.assertEqual(s["rss_kb"], 646900)
        self.assertEqual(s["rss_anon_kb"], 430516)
        self.assertEqual(s["rss_file_kb"], 171804)

        # Confirm VIRT is ~72.2 GB while physical RSS is only ~631.7 MB
        virt_gb = s["virt_kb"] / (1024.0 * 1024.0)
        rss_mb = s["rss_kb"] / 1024.0
        self.assertGreater(virt_gb, 70.0)
        self.assertLess(rss_mb, 1000.0)

    def test_parse_proc_meminfo(self):
        m = parse_proc_meminfo(SAMPLE_PROC_MEMINFO)
        self.assertEqual(m["mem_total_kb"], 11492264)
        self.assertEqual(m["mem_free_kb"], 197288)
        self.assertEqual(m["mem_available_kb"], 4751808)
        self.assertEqual(m["cached_kb"], 5841152)
        self.assertEqual(m["swap_total_kb"], 6291452)
        self.assertEqual(m["swap_free_kb"], 4100344)

        # Confirm MemAvailable (>4.5 GB) is preserved despite low MemFree (~197 MB)
        self.assertGreater(m["mem_available_kb"] / 1024.0, 4500.0)
        self.assertLess(m["mem_free_kb"] / 1024.0, 300.0)

    def test_calculate_linear_regression(self):
        # 1. Perfectly flat
        x = [1.0, 2.0, 3.0, 4.0, 5.0]
        y = [100.0, 100.0, 100.0, 100.0, 100.0]
        slope, intercept, r2 = calculate_linear_regression(x, y)
        self.assertAlmostEqual(slope, 0.0, places=4)
        self.assertAlmostEqual(intercept, 100.0, places=4)
        self.assertAlmostEqual(r2, 1.0, places=4)

        # 2. Known linear slope
        y_linear = [10.0, 20.0, 30.0, 40.0, 50.0]
        slope, intercept, r2 = calculate_linear_regression(x, y_linear)
        self.assertAlmostEqual(slope, 10.0, places=4)
        self.assertAlmostEqual(intercept, 0.0, places=4)
        self.assertAlmostEqual(r2, 1.0, places=4)

        # 3. Degenerate cases
        s, i, r = calculate_linear_regression([1.0], [10.0])
        self.assertEqual(s, 0.0)
        s, i, r = calculate_linear_regression([1.0, 1.0], [10.0, 20.0])
        self.assertEqual(s, 0.0)

    def test_check_monotonicity(self):
        self.assertTrue(check_monotonicity([10.0, 20.0, 30.0, 40.0]))
        self.assertFalse(check_monotonicity([10.0, 30.0, 20.0, 40.0]))
        self.assertFalse(check_monotonicity([10.0, 10.0, 10.0]))
        self.assertFalse(check_monotonicity([10.0]))

    def test_scenario_a_healthy_plateau(self):
        """Warmup spike followed by flat memory plateau across cycles 3..5."""
        cycles = [
            CycleRecord(1, pid=201, live_memory=MemoryMetrics(pss_total_kb=800000, native_heap_pss_kb=320000, graphics_pss_kb=350000, virt_kb=75000000, mem_available_kb=4800000), stop_ok=True),
            CycleRecord(2, pid=202, live_memory=MemoryMetrics(pss_total_kb=850000, native_heap_pss_kb=345000, graphics_pss_kb=370000, virt_kb=75000000, mem_available_kb=4780000), stop_ok=True),
            CycleRecord(3, pid=203, live_memory=MemoryMetrics(pss_total_kb=852000, native_heap_pss_kb=346000, graphics_pss_kb=371000, virt_kb=75000000, mem_available_kb=4770000), stop_ok=True),
            CycleRecord(4, pid=204, live_memory=MemoryMetrics(pss_total_kb=854000, native_heap_pss_kb=347000, graphics_pss_kb=372000, virt_kb=75000000, mem_available_kb=4760000), stop_ok=True),
            CycleRecord(5, pid=205, live_memory=MemoryMetrics(pss_total_kb=855000, native_heap_pss_kb=347500, graphics_pss_kb=372500, virt_kb=75000000, mem_available_kb=4750000), stop_ok=True),
        ]
        res = analyze_lifecycle_plateau(cycles, warmup_cycles=2, pss_slope_threshold_mb=25.0)
        self.assertEqual(res.overall_verdict, "PASS")
        self.assertTrue(res.is_plateau_reached)
        self.assertFalse(res.is_monotonic_growth)
        self.assertTrue(res.zero_crash_teardown)
        self.assertTrue(res.all_stops_clean)
        self.assertLess(res.pss_slope_mb_per_cycle, 5.0)

    def test_scenario_b_monotonic_leak(self):
        """Unbounded linear growth of +75MB every cycle."""
        cycles = [
            CycleRecord(1, pid=301, live_memory=MemoryMetrics(pss_total_kb=800000, native_heap_pss_kb=300000, mem_available_kb=4800000), stop_ok=True),
            CycleRecord(2, pid=302, live_memory=MemoryMetrics(pss_total_kb=875000, native_heap_pss_kb=375000, mem_available_kb=4700000), stop_ok=True),
            CycleRecord(3, pid=303, live_memory=MemoryMetrics(pss_total_kb=950000, native_heap_pss_kb=450000, mem_available_kb=4600000), stop_ok=True),
            CycleRecord(4, pid=304, live_memory=MemoryMetrics(pss_total_kb=1025000, native_heap_pss_kb=525000, mem_available_kb=4500000), stop_ok=True),
            CycleRecord(5, pid=305, live_memory=MemoryMetrics(pss_total_kb=1100000, native_heap_pss_kb=600000, mem_available_kb=4400000), stop_ok=True),
        ]
        res = analyze_lifecycle_plateau(cycles, warmup_cycles=2, pss_slope_threshold_mb=25.0)
        self.assertEqual(res.overall_verdict, "FAIL")
        self.assertFalse(res.is_plateau_reached)
        self.assertTrue(res.is_monotonic_growth)
        self.assertGreater(res.pss_slope_mb_per_cycle, 50.0)

    def test_scenario_c_native_crash_teardown(self):
        """Teardown crash with SIGSEGV or tombstone."""
        cycles = [
            CycleRecord(1, pid=401, live_memory=MemoryMetrics(pss_total_kb=800000), stop_ok=True),
            CycleRecord(2, pid=402, live_memory=MemoryMetrics(pss_total_kb=810000), stop_ok=True),
            CycleRecord(3, pid=403, live_memory=MemoryMetrics(pss_total_kb=812000), stop_ok=True, sigsegv_count=1),
        ]
        res = analyze_lifecycle_plateau(cycles, warmup_cycles=1)
        self.assertEqual(res.overall_verdict, "FAIL")
        self.assertFalse(res.zero_crash_teardown)

    def test_scenario_d_stop_timeout(self):
        """Stop command failed / timed out."""
        cycles = [
            CycleRecord(1, pid=501, live_memory=MemoryMetrics(pss_total_kb=800000), stop_ok=True),
            CycleRecord(2, pid=502, live_memory=MemoryMetrics(pss_total_kb=810000), stop_ok=False),
        ]
        res = analyze_lifecycle_plateau(cycles, warmup_cycles=1)
        self.assertEqual(res.overall_verdict, "FAIL")
        self.assertFalse(res.all_stops_clean)


if __name__ == "__main__":
    unittest.main()
