#!/usr/bin/env python3
"""
Unit tests for ARM busy-wait timing and counter frequency conversion (Phase 5 / A03).
Validates dynamic counter frequency conversion math across various SoC frequencies
(19.2 MHz Snapdragon, 24 MHz Dimensity/Poco X6 Pro, 100 MHz), boundary inputs,
zero/subtick values, and compiles/runs the C++ host test binary.
"""

import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

NOMINAL_X86_FREQ_HZ = 3_500_000_000
DEFAULT_ARM64_TIMER_FREQ = 19_200_000
UINT64_MAX = 0xFFFFFFFFFFFFFFFF


def cycles_to_ticks(cycles: int, host_freq: int, ref_freq: int = NOMINAL_X86_FREQ_HZ) -> int:
    """Python reference implementation of rx::cycles_to_ticks."""
    if cycles == 0:
        return 0
    if host_freq == 0:
        host_freq = DEFAULT_ARM64_TIMER_FREQ
    if ref_freq == 0:
        ref_freq = NOMINAL_X86_FREQ_HZ

    product = cycles * host_freq
    rounded = product + (ref_freq // 2)
    result = rounded // ref_freq
    return min(result, UINT64_MAX)


def ns_to_ticks(ns: int, host_freq: int) -> int:
    """Python reference implementation of rx::ns_to_ticks."""
    if ns == 0:
        return 0
    if host_freq == 0:
        host_freq = DEFAULT_ARM64_TIMER_FREQ

    ns_per_sec = 1_000_000_000
    product = ns * host_freq
    rounded = product + (ns_per_sec // 2)
    result = rounded // ns_per_sec
    return min(result, UINT64_MAX)


def ticks_to_ns(ticks: int, host_freq: int) -> int:
    """Python reference implementation of rx::ticks_to_ns."""
    if ticks == 0 or host_freq == 0:
        return 0

    ns_per_sec = 1_000_000_000
    product = ticks * ns_per_sec
    rounded = product + (host_freq // 2)
    result = rounded // host_freq
    return min(result, UINT64_MAX)


class TestBusyWaitConversion(unittest.TestCase):
    def test_nominal_constant(self):
        self.assertEqual(NOMINAL_X86_FREQ_HZ, 3_500_000_000)

    def test_zero_cycles(self):
        # 0 cycles should always yield 0 ticks across all frequencies
        for freq in [0, 19_200_000, 24_000_000, 25_000_000, 26_000_000, 50_000_000, 100_000_000]:
            self.assertEqual(cycles_to_ticks(0, freq), 0)

    def test_snapdragon_19_2_mhz(self):
        freq = 19_200_000

        # Subtick values: 19.2M / 3.5B = 1 tick per ~182.29 cycles.
        # Rounding threshold: 3.5B / (2 * 19.2M) = 91.14 cycles.
        self.assertEqual(cycles_to_ticks(1, freq), 0)
        self.assertEqual(cycles_to_ticks(10, freq), 0)
        self.assertEqual(cycles_to_ticks(50, freq), 0)
        self.assertEqual(cycles_to_ticks(90, freq), 0)
        self.assertEqual(cycles_to_ticks(92, freq), 1)

        # Codebase callers
        self.assertEqual(cycles_to_ticks(100, freq), 1)
        self.assertEqual(cycles_to_ticks(200, freq), 1)
        self.assertEqual(cycles_to_ticks(300, freq), 2)
        self.assertEqual(cycles_to_ticks(500, freq), 3)
        self.assertEqual(cycles_to_ticks(1500, freq), 8)
        self.assertEqual(cycles_to_ticks(3000, freq), 16)

        # Verify delay accuracy: 3000 cycles at 3.5 GHz is 857.14 ns.
        # 16 ticks at 19.2 MHz is 16 * 1e9 / 19.2M = 833.33 ns (~2.8% delta).
        delay_ns = 3000 * 1e9 / NOMINAL_X86_FREQ_HZ
        actual_ns = 16 * 1e9 / freq
        self.assertAlmostEqual(actual_ns, delay_ns, delta=55.0)

    def test_mediatek_dimensity_24_mhz(self):
        freq = 24_000_000  # Poco X6 Pro (Dimensity 8300 Ultra)

        # Subtick threshold: 3.5B / (2 * 24M) = 72.91 cycles.
        self.assertEqual(cycles_to_ticks(1, freq), 0)
        self.assertEqual(cycles_to_ticks(50, freq), 0)
        self.assertEqual(cycles_to_ticks(72, freq), 0)
        self.assertEqual(cycles_to_ticks(73, freq), 1)

        # Codebase callers
        self.assertEqual(cycles_to_ticks(100, freq), 1)
        self.assertEqual(cycles_to_ticks(200, freq), 1)
        self.assertEqual(cycles_to_ticks(300, freq), 2)
        self.assertEqual(cycles_to_ticks(500, freq), 3)
        self.assertEqual(cycles_to_ticks(1500, freq), 10)
        self.assertEqual(cycles_to_ticks(3000, freq), 21)

        # Contrast with old hardcoded /182:
        # Old code: 3000 / 182 = 16 ticks -> 16 / 24M = 666.67 ns (22.2% under-wait!)
        # New code: 21 ticks -> 21 / 24M = 875.00 ns (matches 857.14 ns design delay within 2.1%)
        old_ticks = 3000 // 182
        new_ticks = cycles_to_ticks(3000, freq)
        self.assertEqual(old_ticks, 16)
        self.assertEqual(new_ticks, 21)
        self.assertGreater(new_ticks, old_ticks)

    def test_100_mhz(self):
        freq = 100_000_000

        self.assertEqual(cycles_to_ticks(0, freq), 0)
        self.assertEqual(cycles_to_ticks(10, freq), 0)
        self.assertEqual(cycles_to_ticks(100, freq), 3)
        self.assertEqual(cycles_to_ticks(200, freq), 6)
        self.assertEqual(cycles_to_ticks(300, freq), 9)
        self.assertEqual(cycles_to_ticks(500, freq), 14)
        self.assertEqual(cycles_to_ticks(1500, freq), 43)
        self.assertEqual(cycles_to_ticks(3000, freq), 86)

    def test_intermediate_frequencies(self):
        # 25 MHz
        self.assertEqual(cycles_to_ticks(3000, 25_000_000), 21)
        # 26 MHz
        self.assertEqual(cycles_to_ticks(3000, 26_000_000), 22)
        # 50 MHz
        self.assertEqual(cycles_to_ticks(3000, 50_000_000), 43)

    def test_fallbacks(self):
        # host_freq == 0 fallback to 19.2 MHz
        self.assertEqual(cycles_to_ticks(3000, 0), cycles_to_ticks(3000, 19_200_000))
        self.assertEqual(cycles_to_ticks(3000, 0), 16)

        # ref_freq == 0 fallback to 3.5 GHz
        self.assertEqual(cycles_to_ticks(3000, 19_200_000, 0), 16)

    def test_boundary_overflow(self):
        # UINT64_MAX saturation
        sat = cycles_to_ticks(UINT64_MAX, 24_000_000)
        self.assertGreater(sat, 0)
        self.assertLessEqual(sat, UINT64_MAX)

        # 100 billion cycles
        large = cycles_to_ticks(100_000_000_000, 24_000_000)
        self.assertEqual(large, 685_714_286)

    def test_duration_conversions(self):
        freq = 19_200_000
        self.assertEqual(ns_to_ticks(0, freq), 0)
        self.assertEqual(ns_to_ticks(857, freq), 16)
        self.assertEqual(ns_to_ticks(857, 24_000_000), 21)

        # Roundtrip
        t = ns_to_ticks(1000, freq)
        self.assertEqual(t, 19)
        ns_back = ticks_to_ns(t, freq)
        self.assertTrue(980 <= ns_back <= 1000)

    def test_counter_wrap_elapsed_comparison(self):
        start = UINT64_MAX - 10
        current = 5
        ticks = 16

        # (now - start) mod 2^64
        elapsed = (current - start) & UINT64_MAX
        self.assertEqual(elapsed, 16)

        # Loop simulation of ((now - start) < ticks)
        steps = 0
        now = start
        while ((now - start) & UINT64_MAX) < ticks:
            steps += 1
            now = (now + 1) & UINT64_MAX
        self.assertEqual(steps, 16)

        # Buggy old pattern: stop = start + ticks
        stop = (start + ticks) & UINT64_MAX  # wraps to 5
        buggy_steps = 0
        now = start
        while now < stop:
            buggy_steps += 1
            now = (now + 1) & UINT64_MAX
        self.assertEqual(buggy_steps, 0)  # terminated immediately!

        # Wraparounds
        self.assertEqual((0 - UINT64_MAX) & UINT64_MAX, 1)
        self.assertEqual((100 - (UINT64_MAX - 50)) & UINT64_MAX, 151)

    def test_saturated_tick_intervals(self):
        saturated_ticks = UINT64_MAX
        start = 1000

        # With elapsed comparison, (now - start) < UINT64_MAX does not return immediately
        self.assertTrue(((start - start) & UINT64_MAX) < saturated_ticks)
        self.assertTrue((((start + 1) - start) & UINT64_MAX) < saturated_ticks)
        self.assertTrue((((start + 10_000) - start) & UINT64_MAX) < saturated_ticks)
        self.assertTrue((((start + (1 << 62)) - start) & UINT64_MAX) < saturated_ticks)

        # Buggy old pattern: stop = (start + UINT64_MAX) & UINT64_MAX = start - 1
        buggy_stop = (start + saturated_ticks) & UINT64_MAX
        self.assertFalse(start < buggy_stop)

        # Wrapping counter with saturated ticks
        wrap_start = UINT64_MAX - 5
        wrap_now = 5
        wrap_elapsed = (wrap_now - wrap_start) & UINT64_MAX
        self.assertEqual(wrap_elapsed, 11)
        self.assertTrue(wrap_elapsed < saturated_ticks)
        self.assertTrue(wrap_elapsed < (UINT64_MAX - 1))
        self.assertTrue(wrap_elapsed < (1 << 63))

    def test_frequency_conversion_dynamic_x86(self):
        freqs = [
            2_400_000_000,
            2_800_000_000,
            3_200_000_000,
            3_500_000_000,
            4_000_000_000,
        ]
        for f in freqs:
            expected_ticks = f // 1_000_000
            self.assertEqual(cycles_to_ticks(3500, f), expected_ticks)
            self.assertEqual(ns_to_ticks(1000, f), expected_ticks)
            self.assertEqual(ticks_to_ns(expected_ticks, f), 1000)

    def test_cpp_unit_test_binary(self):
        cpp_src = REPO_ROOT / "scripts" / "tests" / "test_busy_wait.cpp"
        bin_path = REPO_ROOT / "scripts" / "tests" / "test_busy_wait"
        rx_inc = REPO_ROOT / "app" / "src" / "main" / "cpp" / "rpcsx" / "rx" / "include"

        self.assertTrue(cpp_src.exists(), f"Missing {cpp_src}")
        subprocess.run(
            ["g++", "-O2", "-std=c++20", f"-I{rx_inc}", str(cpp_src), "-o", str(bin_path)],
            check=True,
        )
        res = subprocess.run([str(bin_path)], capture_output=True, text=True, check=True)
        self.assertIn("ALL 11 BUSY_WAIT TESTS PASSED!", res.stdout)
        if bin_path.exists():
            bin_path.unlink()


if __name__ == "__main__":
    unittest.main()
