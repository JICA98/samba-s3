#!/usr/bin/env python3
"""
Unit tests for ARM CPU target model selection and feature intersection (Defects R11 & R16).
Validates allowed CPU mask intersection, baseline fallback, and vector stride safety.
"""

import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


class CpuTargetTestCase(unittest.TestCase):
    def test_cpp_unit_test_binary(self):
        cpp_src = REPO_ROOT / "scripts" / "tests" / "test_cpu_target.cpp"
        bin_path = REPO_ROOT / "scripts" / "tests" / "test_cpu_target"
        self.assertTrue(cpp_src.exists(), f"Source file {cpp_src} must exist")

        subprocess.run(["g++", "-O2", "-std=c++20", str(cpp_src), "-o", str(bin_path)], check=True)
        try:
            res = subprocess.run([str(bin_path)], capture_output=True, text=True, check=True)
            self.assertIn("ALL 9 CPU TARGET AND FEATURE TESTS PASSED!", res.stdout)
        finally:
            if bin_path.exists():
                bin_path.unlink()


if __name__ == "__main__":
    unittest.main()
