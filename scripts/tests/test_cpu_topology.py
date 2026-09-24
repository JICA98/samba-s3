#!/usr/bin/env python3
"""
Unit tests for ARM CPU topology discovery and affinity mask calculations (Phase 4 / A02).
Validates dynamic mask calculation across heterogeneous, offline, restricted-mask, and >8 core devices.
"""

import subprocess
import unittest
from enum import Enum
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


class ThreadClass(Enum):
    GENERAL = 0
    PPU = 1
    SPU = 2
    RSX = 0x55


class ThreadSchedulerMode(Enum):
    OS = "os"
    RPCS3 = "old"
    ALTERNATIVE = "alt"


def calculate_affinity_mask(group, sched_mode, topo=None, allowed_mask=None, custom_affinities=None):
    if not allowed_mask:
        allowed_mask = topo.get("allowed_mask", 0xFF) if topo else 0xFF

    custom_affinities = custom_affinities or []
    has_custom_affinity = any(a != ThreadClass.GENERAL for a in custom_affinities)

    if has_custom_affinity:
        user_mask = 0
        for i, a in enumerate(custom_affinities[:64]):
            if a == group or a == ThreadClass.GENERAL:
                user_mask |= (1 << i)

        user_mask &= allowed_mask
        if user_mask != 0:
            return user_mask
        return allowed_mask

    if sched_mode == ThreadSchedulerMode.OS:
        return allowed_mask

    if topo and topo.get("is_heterogeneous", False) and topo.get("performance_mask", 0) != 0:
        if group in (ThreadClass.SPU, ThreadClass.PPU, ThreadClass.RSX):
            perf_mask = topo["performance_mask"] & allowed_mask
            if perf_mask != 0:
                return perf_mask

    return allowed_mask


def parse_cpu_topology(allowed_mask, capacities=None, max_freqs=None, midrs=None):
    if not allowed_mask:
        allowed_mask = 0xFF

    capacities = capacities or []
    max_freqs = max_freqs or []
    midrs = midrs or []

    # 1. Capacity
    allowed_caps = [(cpu, cap) for cpu, cap in capacities if (allowed_mask & (1 << cpu))]
    if len(allowed_caps) >= 2:
        max_c = max(c for _, c in allowed_caps)
        min_c = min(c for _, c in allowed_caps)
        if max_c - min_c >= 100:
            threshold = min_c + (max_c - min_c) // 2
            perf = 0
            eff = 0
            for cpu, cap in allowed_caps:
                if cap >= threshold:
                    perf |= (1 << cpu)
                else:
                    eff |= (1 << cpu)
            if perf and eff:
                perf |= (allowed_mask & ~(perf | eff))
                return {
                    "allowed_mask": allowed_mask,
                    "performance_mask": perf & allowed_mask,
                    "efficiency_mask": eff & allowed_mask,
                    "is_heterogeneous": True,
                    "discovery_successful": True,
                }

    # 2. Max Freq
    allowed_freqs = [(cpu, freq) for cpu, freq in max_freqs if (allowed_mask & (1 << cpu))]
    if len(allowed_freqs) >= 2:
        max_f = max(f for _, f in allowed_freqs)
        min_f = min(f for _, f in allowed_freqs)
        if max_f - min_f >= 200000:
            threshold = min_f + (max_f - min_f) // 2
            perf = 0
            eff = 0
            for cpu, freq in allowed_freqs:
                if freq >= threshold:
                    perf |= (1 << cpu)
                else:
                    eff |= (1 << cpu)
            if perf and eff:
                perf |= (allowed_mask & ~(perf | eff))
                return {
                    "allowed_mask": allowed_mask,
                    "performance_mask": perf & allowed_mask,
                    "efficiency_mask": eff & allowed_mask,
                    "is_heterogeneous": True,
                    "discovery_successful": True,
                }

    # 3. MIDR
    little_parts = {0xd01, 0xd02, 0xd04, 0xd03, 0xd05, 0xd46, 0xd80, 0xd88}
    allowed_midrs = [(cpu, midr) for cpu, midr in midrs if (allowed_mask & (1 << cpu))]
    if allowed_midrs:
        perf = 0
        eff = 0
        for cpu, midr in allowed_midrs:
            part = (midr >> 4) & 0xFFF
            if part in little_parts:
                eff |= (1 << cpu)
            elif part != 0:
                perf |= (1 << cpu)
        if perf and eff:
            perf |= (allowed_mask & ~(perf | eff))
            return {
                "allowed_mask": allowed_mask,
                "performance_mask": perf & allowed_mask,
                "efficiency_mask": eff & allowed_mask,
                "is_heterogeneous": True,
                "discovery_successful": True,
            }

    # 4. Fallback: OS management
    return {
        "allowed_mask": allowed_mask,
        "performance_mask": allowed_mask,
        "efficiency_mask": 0,
        "is_heterogeneous": False,
        "discovery_successful": bool(capacities or max_freqs or midrs),
    }


class CpuTopologyTestCase(unittest.TestCase):
    def test_poco_x6_pro_dimensity_8300(self):
        # 4x A510 (350) + 4x A715 (1024)
        caps = [(0, 350), (1, 350), (2, 350), (3, 350), (4, 1024), (5, 1024), (6, 1024), (7, 1024)]
        topo = parse_cpu_topology(0xFF, capacities=caps)
        self.assertTrue(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0xF0)  # Cores 4-7, NOT 0xFC!
        self.assertEqual(topo["efficiency_mask"], 0x0F)
        self.assertEqual(topo["performance_mask"] & ~topo["allowed_mask"], 0)

    def test_oneplus_13r_snapdragon_8_gen_2(self):
        # 3x A510 (320) + 5x big/prime (800-1024)
        caps = [(0, 320), (1, 320), (2, 320), (3, 800), (4, 800), (5, 850), (6, 850), (7, 1024)]
        topo = parse_cpu_topology(0xFF, capacities=caps)
        self.assertTrue(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0xF8)  # Cores 3-7, NOT 0xFC!
        self.assertEqual(topo["efficiency_mask"], 0x07)
        self.assertEqual(topo["performance_mask"] & ~topo["allowed_mask"], 0)

    def test_snapdragon_8_gen_3(self):
        # 2x A520 (300) + 6x big/prime (850-1024)
        caps = [(0, 300), (1, 300), (2, 850), (3, 850), (4, 850), (5, 850), (6, 850), (7, 1024)]
        topo = parse_cpu_topology(0xFF, capacities=caps)
        self.assertTrue(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0xFC)
        self.assertEqual(topo["efficiency_mask"], 0x03)

    def test_snapdragon_8_gen_3_realistic_eas_capacities(self):
        # OnePlus 13R / SM8650 actual Energy Aware Scheduling capacities
        # CPUs 0-1: Cortex-A520 (240), CPUs 2-3: Cortex-A720 (780), CPUs 4-6: Cortex-A720 (840), CPU 7: Cortex-X4 (1024)
        caps = [(0, 240), (1, 240), (2, 780), (3, 780), (4, 840), (5, 840), (6, 840), (7, 1024)]
        topo = parse_cpu_topology(0xFF, capacities=caps)
        self.assertTrue(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0xFC)
        self.assertEqual(topo["efficiency_mask"], 0x03)
        self.assertEqual(topo["performance_mask"] & ~topo["allowed_mask"], 0)

    def test_snapdragon_8_gen_3_cluster_frequencies(self):
        # OnePlus 13R / SM8650 max frequencies across 3 clusters
        # CPUs 0-1: 2265600 kHz, CPUs 2-3: 2956800 kHz, CPUs 4-6: 3148800 kHz, CPU 7: 3300000 kHz
        freqs = [(0, 2265600), (1, 2265600), (2, 2956800), (3, 2956800), (4, 3148800), (5, 3148800), (6, 3148800), (7, 3300000)]
        topo = parse_cpu_topology(0xFF, max_freqs=freqs)
        self.assertTrue(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0xFC)
        self.assertEqual(topo["efficiency_mask"], 0x03)
        self.assertEqual(topo["performance_mask"] & ~topo["allowed_mask"], 0)

    def test_snapdragon_8_gen_3_arm_midrs(self):
        # OnePlus 13R / SM8650 MIDR_EL1 part numbers
        # 0xd80 = Cortex-A520 (cores 0-1), 0xd81 = Cortex-A720 (cores 2-6), 0xd82 = Cortex-X4 (core 7)
        midrs = [
            (0, 0x410FD800), (1, 0x410FD800),
            (2, 0x410FD810), (3, 0x410FD810), (4, 0x410FD810), (5, 0x410FD810), (6, 0x410FD810),
            (7, 0x410FD820)
        ]
        topo = parse_cpu_topology(0xFF, midrs=midrs)
        self.assertTrue(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0xFC)
        self.assertEqual(topo["efficiency_mask"], 0x03)
        self.assertEqual(topo["performance_mask"] & ~topo["allowed_mask"], 0)

    def test_snapdragon_8_gen_3_scheduler_modes_and_affinity(self):
        topo = {
            "allowed_mask": 0xFF,
            "performance_mask": 0xFC,
            "efficiency_mask": 0x03,
            "is_heterogeneous": True,
        }
        # In OS mode, all thread groups (SPU, PPU, RSX, General) receive full allowed_mask (0xFF)
        for group in (ThreadClass.GENERAL, ThreadClass.SPU, ThreadClass.PPU, ThreadClass.RSX):
            self.assertEqual(calculate_affinity_mask(group, ThreadSchedulerMode.OS, topo, 0xFF), 0xFF)

        # In RPCS3 mode, SPU, PPU, RSX are pinned to performance mask (0xFC)
        self.assertEqual(calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo, 0xFF), 0xFC)
        self.assertEqual(calculate_affinity_mask(ThreadClass.PPU, ThreadSchedulerMode.RPCS3, topo, 0xFF), 0xFC)
        self.assertEqual(calculate_affinity_mask(ThreadClass.RSX, ThreadSchedulerMode.RPCS3, topo, 0xFF), 0xFC)
        self.assertEqual(calculate_affinity_mask(ThreadClass.GENERAL, ThreadSchedulerMode.RPCS3, topo, 0xFF), 0xFF)

        # Offline Prime core 7 (allowed_mask = 0x7F) -> SPU, PPU, RSX receive 0x7C (cores 2-6 only)
        self.assertEqual(calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo, 0x7F), 0x7C)
        self.assertEqual(calculate_affinity_mask(ThreadClass.PPU, ThreadSchedulerMode.RPCS3, topo, 0x7F), 0x7C)

        # Restricted cpuset to efficiency cores (allowed_mask = 0x03) -> clean fallback to 0x03
        self.assertEqual(calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo, 0x03), 0x03)

    def test_google_tensor_g3_9_cores(self):
        # 4x A510 (350) + 5x big/prime (850-1024) across 9 cores
        caps = [(0, 350), (1, 350), (2, 350), (3, 350), (4, 850), (5, 850), (6, 850), (7, 850), (8, 1024)]
        topo = parse_cpu_topology(0x1FF, capacities=caps)
        self.assertTrue(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0x1F0)
        self.assertEqual(topo["efficiency_mask"], 0x0F)
        self.assertEqual(topo["performance_mask"] & ~topo["allowed_mask"], 0)

    def test_homogeneous_smp(self):
        caps = [(i, 1024) for i in range(8)]
        topo = parse_cpu_topology(0xFF, capacities=caps)
        self.assertFalse(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0xFF)

    def test_restricted_cpuset(self):
        # Process allowed only cores 0-3
        caps = [(0, 350), (1, 350), (2, 350), (3, 350), (4, 1024), (5, 1024), (6, 1024), (7, 1024)]
        topo = parse_cpu_topology(0x0F, capacities=caps)
        self.assertFalse(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0x0F)
        self.assertEqual(topo["performance_mask"] & ~topo["allowed_mask"], 0)

    def test_offline_core(self):
        # Core 7 offline -> allowed mask 0x7F
        caps = [(0, 350), (1, 350), (2, 350), (3, 350), (4, 1024), (5, 1024), (6, 1024)]
        topo = parse_cpu_topology(0x7F, capacities=caps)
        self.assertTrue(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0x70)
        self.assertEqual(topo["performance_mask"] & (1 << 7), 0)

    def test_unavailable_discovery_fallback(self):
        topo = parse_cpu_topology(0xFF)
        self.assertFalse(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0xFF)
        self.assertFalse(topo["discovery_successful"])

    def test_frequency_based_discovery(self):
        freqs = [(0, 1800000), (1, 1800000), (2, 1800000), (3, 1800000),
                 (4, 3000000), (5, 3000000), (6, 3000000), (7, 3000000)]
        topo = parse_cpu_topology(0xFF, max_freqs=freqs)
        self.assertTrue(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0xF0)
        self.assertEqual(topo["efficiency_mask"], 0x0F)

    def test_midr_based_discovery(self):
        # 0xd46 = Cortex-A510 (little), 0xd4d = Cortex-A715 (big)
        midrs = [(0, 0x410FD460), (1, 0x410FD460), (2, 0x410FD460), (3, 0x410FD460),
                 (4, 0x410FD4D0), (5, 0x410FD4D0), (6, 0x410FD4D0), (7, 0x410FD4D0)]
        topo = parse_cpu_topology(0xFF, midrs=midrs)
        self.assertTrue(topo["is_heterogeneous"])
        self.assertEqual(topo["performance_mask"], 0xF0)
        self.assertEqual(topo["efficiency_mask"], 0x0F)

    def test_scheduler_mode_os_no_pinning(self):
        topo = {
            "allowed_mask": 0xFF,
            "performance_mask": 0xF0,
            "efficiency_mask": 0x0F,
            "is_heterogeneous": True,
        }
        for group in (ThreadClass.GENERAL, ThreadClass.SPU, ThreadClass.PPU, ThreadClass.RSX):
            mask = calculate_affinity_mask(group, ThreadSchedulerMode.OS, topo, 0xFF)
            self.assertEqual(mask, 0xFF)

    def test_scheduler_mode_rpcs3_perf_pinning(self):
        topo = {
            "allowed_mask": 0xFF,
            "performance_mask": 0xF0,
            "efficiency_mask": 0x0F,
            "is_heterogeneous": True,
        }
        self.assertEqual(calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo, 0xFF), 0xF0)
        self.assertEqual(calculate_affinity_mask(ThreadClass.PPU, ThreadSchedulerMode.RPCS3, topo, 0xFF), 0xF0)
        self.assertEqual(calculate_affinity_mask(ThreadClass.RSX, ThreadSchedulerMode.RPCS3, topo, 0xFF), 0xF0)
        self.assertEqual(calculate_affinity_mask(ThreadClass.GENERAL, ThreadSchedulerMode.RPCS3, topo, 0xFF), 0xFF)

    def test_scheduler_mode_alternative(self):
        topo = {
            "allowed_mask": 0xFF,
            "performance_mask": 0xF8,
            "efficiency_mask": 0x07,
            "is_heterogeneous": True,
        }
        self.assertEqual(calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.ALTERNATIVE, topo, 0xFF), 0xF8)
        self.assertEqual(calculate_affinity_mask(ThreadClass.PPU, ThreadSchedulerMode.ALTERNATIVE, topo, 0xFF), 0xF8)
        self.assertEqual(calculate_affinity_mask(ThreadClass.RSX, ThreadSchedulerMode.ALTERNATIVE, topo, 0xFF), 0xF8)
        self.assertEqual(calculate_affinity_mask(ThreadClass.GENERAL, ThreadSchedulerMode.ALTERNATIVE, topo, 0xFF), 0xFF)

    def test_scheduler_mode_fallback_homogeneous_and_empty_overlap(self):
        topo_homo = {
            "allowed_mask": 0xFF,
            "performance_mask": 0xFF,
            "efficiency_mask": 0,
            "is_heterogeneous": False,
        }
        self.assertEqual(calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo_homo, 0xFF), 0xFF)

        topo_het = {
            "allowed_mask": 0xFF,
            "performance_mask": 0xF0,
            "efficiency_mask": 0x0F,
            "is_heterogeneous": True,
        }
        # Disjoint allowed_mask (0x0F) and performance_mask (0xF0) -> fallback to allowed_mask
        self.assertEqual(calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo_het, 0x0F), 0x0F)

    def test_custom_affinity_cpus_8_to_63_not_force_added(self):
        topo = {
            "allowed_mask": 0xFFFFFFFFFFFFFFFF,
            "performance_mask": 0xF0,
            "efficiency_mask": 0x0F,
            "is_heterogeneous": True,
        }
        # 8 cores configured: cores 0-3 SPU, cores 4-7 PPU
        custom = [ThreadClass.SPU] * 4 + [ThreadClass.PPU] * 4
        all_cores = 0xFFFFFFFFFFFFFFFF
        spu_mask = calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.OS, topo, all_cores, custom)
        ppu_mask = calculate_affinity_mask(ThreadClass.PPU, ThreadSchedulerMode.OS, topo, all_cores, custom)
        rsx_mask = calculate_affinity_mask(ThreadClass.RSX, ThreadSchedulerMode.OS, topo, all_cores, custom)

        self.assertEqual(spu_mask, 0x0F)
        self.assertEqual(ppu_mask, 0xF0)
        # Verify bits 8-63 are NOT force-added (defect R17)
        self.assertEqual(spu_mask & (~0 & ((1 << 64) - (1 << 8))), 0)
        self.assertEqual(ppu_mask & (~0 & ((1 << 64) - (1 << 8))), 0)
        # RSX unmapped -> fallback to allowed_mask
        self.assertEqual(rsx_mask, all_cores)

    def test_custom_affinity_selective_cpu_8_to_63_inclusion_exclusion(self):
        topo = {"allowed_mask": 0xFFFFFFFFFFFFFFFF}
        # 12 cores: 0-1 general, 2-3 rsx, 4-5 ppu, 6-7 spu, 8-9 spu, 10 ppu, 11 general
        custom = [
            ThreadClass.GENERAL, ThreadClass.GENERAL,
            ThreadClass.RSX, ThreadClass.RSX,
            ThreadClass.PPU, ThreadClass.PPU,
            ThreadClass.SPU, ThreadClass.SPU,
            ThreadClass.SPU, ThreadClass.SPU,  # 8, 9 SPU
            ThreadClass.PPU,                   # 10 PPU
            ThreadClass.GENERAL,               # 11 General
        ]
        all_cores = 0xFFFFFFFFFFFFFFFF
        spu_mask = calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo, all_cores, custom)
        ppu_mask = calculate_affinity_mask(ThreadClass.PPU, ThreadSchedulerMode.RPCS3, topo, all_cores, custom)

        # SPU includes cores 0, 1, 6, 7, 8, 9, 11
        expected_spu = (1 << 0) | (1 << 1) | (1 << 6) | (1 << 7) | (1 << 8) | (1 << 9) | (1 << 11)
        self.assertEqual(spu_mask, expected_spu)
        self.assertTrue(spu_mask & (1 << 8))
        self.assertTrue(spu_mask & (1 << 9))
        self.assertFalse(spu_mask & (1 << 10))  # Excluded

        # PPU includes cores 0, 1, 4, 5, 10, 11
        expected_ppu = (1 << 0) | (1 << 1) | (1 << 4) | (1 << 5) | (1 << 10) | (1 << 11)
        self.assertEqual(ppu_mask, expected_ppu)
        self.assertTrue(ppu_mask & (1 << 10))
        self.assertFalse(ppu_mask & (1 << 8))   # Excluded
        self.assertFalse(ppu_mask & (1 << 9))   # Excluded

        # Cores >= 12 are not set
        self.assertEqual(spu_mask & (~0 & ((1 << 64) - (1 << 12))), 0)
        self.assertEqual(ppu_mask & (~0 & ((1 << 64) - (1 << 12))), 0)

    def test_custom_affinity_allowed_mask_intersection(self):
        topo = {"allowed_mask": 0xFF}
        # Core 8 assigned to SPU, cores 0-7 general
        custom = [ThreadClass.GENERAL] * 8 + [ThreadClass.SPU]
        spu_mask = calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo, 0xFF, custom)
        self.assertEqual(spu_mask & (1 << 8), 0)
        self.assertEqual(spu_mask, 0xFF)

    def test_custom_affinity_empty_intersection_fallback(self):
        topo = {"allowed_mask": 0x0F}
        custom = [ThreadClass.PPU] * 8 + [ThreadClass.SPU]
        # Core 8 mapped to SPU, allowed_mask is 0x0F -> empty intersection -> fallback to 0x0F
        spu_mask = calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo, 0x0F, custom)
        self.assertEqual(spu_mask, 0x0F)

    def test_tensor_g3_9_cores_scheduler_and_allowed_mask_intersection(self):
        caps = [(0, 350), (1, 350), (2, 350), (3, 350), (4, 850), (5, 850), (6, 850), (7, 850), (8, 1024)]
        topo = parse_cpu_topology(0x1FF, capacities=caps)
        self.assertEqual(topo["performance_mask"], 0x1F0)

        # Full allowed mask = 0x1FF
        spu_mask = calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo, 0x1FF)
        self.assertEqual(spu_mask, 0x1F0)
        self.assertTrue(spu_mask & (1 << 8))

        # Core 8 offline/restricted: allowed_mask = 0x0FF
        spu_restricted = calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo, 0x0FF)
        self.assertEqual(spu_restricted, 0x0F0)
        self.assertFalse(spu_restricted & (1 << 8))

        # Prime core only among perf cores: allowed_mask = 0x101
        spu_prime_only = calculate_affinity_mask(ThreadClass.SPU, ThreadSchedulerMode.RPCS3, topo, 0x101)
        self.assertEqual(spu_prime_only, 0x100)

    def test_non_empty_subset_guarantee(self):
        topo = {
            "allowed_mask": 0x55,
            "performance_mask": 0x50,
            "efficiency_mask": 0x05,
            "is_heterogeneous": True,
        }
        for mode in (ThreadSchedulerMode.OS, ThreadSchedulerMode.RPCS3, ThreadSchedulerMode.ALTERNATIVE):
            for cls in (ThreadClass.GENERAL, ThreadClass.SPU, ThreadClass.PPU, ThreadClass.RSX):
                mask = calculate_affinity_mask(cls, mode, topo, 0x55)
                self.assertNotEqual(mask, 0)
                self.assertEqual(mask & ~0x55, 0)

    def test_cpp_unit_test_binary(self):
        cpp_src = REPO_ROOT / "scripts" / "tests" / "test_cpu_topology.cpp"
        bin_path = REPO_ROOT / "scripts" / "tests" / "test_cpu_topology"
        if cpp_src.exists():
            subprocess.run(["g++", "-O2", "-std=c++20", str(cpp_src), "-o", str(bin_path)], check=True)
            res = subprocess.run([str(bin_path)], capture_output=True, text=True, check=True)
            self.assertIn("ALL 24 CPU TOPOLOGY AND SCHEDULER TESTS PASSED!", res.stdout)
            if bin_path.exists():
                bin_path.unlink()


if __name__ == "__main__":
    unittest.main()

