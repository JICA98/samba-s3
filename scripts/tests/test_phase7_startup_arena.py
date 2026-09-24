#!/usr/bin/env python3
"""
Unit tests for Phase 7 of the SambaS3 ARM Backend Optimization Plan:
- Problem 10 (P02): Manifest validation / MSELF hashing overhead during startup
- Suspected 15 (P03): Repeated JIT arena teardown retaining excessive virtual address space
- Defect R06 (P1): Aborted PPU scan must not publish empty manifest; empty manifest rejected on load and removed
- Defect R07 (P1): Prevent stale MSELF reuse from size/mtime alone; verify content identity & non-MSELF entry digests
- Defect R10 (P1): Compiler logging & static string thread-safety in JITLLVM
"""

import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


class TestPhase7StartupAndArena(unittest.TestCase):
    def test_cpp_unit_test_binary(self):
        cpp_src = REPO_ROOT / "scripts" / "tests" / "test_phase7_startup_arena.cpp"
        bin_path = REPO_ROOT / "scripts" / "tests" / "test_phase7_startup_arena"

        self.assertTrue(cpp_src.exists(), f"Missing {cpp_src}")
        try:
            subprocess.run(
                ["g++", "-O2", "-std=c++20", "-pthread", str(cpp_src), "-o", str(bin_path)],
                check=True,
                capture_output=True,
                text=True,
            )
            res = subprocess.run([str(bin_path)], capture_output=True, text=True, check=True)
            self.assertIn("ALL 6 PHASE 7 TESTS PASSED!", res.stdout)
        finally:
            if bin_path.exists():
                bin_path.unlink()

    def test_jitllvm_arena_release_on_teardown(self):
        jitllvm_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "util"
            / "JITLLVM.cpp"
        )
        content = jitllvm_src.read_text(encoding="utf-8")
        # Check MemoryManager1 destructor releases memory rather than only decommitting
        self.assertIn("utils::memory_release(m_code_mems, c_max_size * 3)", content)
        self.assertIn("m_code_mems = nullptr;", content)
        self.assertNotIn("utils::memory_decommit(m_code_mems, c_max_size * 3);", content)

    def test_ppu_manifest_verification_ordering_and_mself_caching(self):
        ppu_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "Cell"
            / "PPUThread.cpp"
        )
        content = ppu_src.read_text(encoding="utf-8")
        # Verify ppu_mself_record struct and map
        self.assertIn("struct ppu_mself_record", content)
        self.assertIn("using ppu_mself_map = std::unordered_map<std::string, ppu_mself_record>;", content)

        # Verify ppu_manifest_load precedes ppu_manifest_source_inventory in ppu_precompile
        load_pos = content.find("ppu_manifest_load(manifest_path, manifest_key, manifest_roots")
        inv_pos = content.find("ppu_manifest_source_inventory(\n\t\t\t\tmanifest_roots,")
        self.assertNotEqual(load_pos, -1, "ppu_manifest_load not found in ppu_precompile")
        self.assertNotEqual(inv_pos, -1, "ppu_manifest_source_inventory not found in ppu_precompile")
        self.assertLess(load_pos, inv_pos, "ppu_manifest_load must precede ppu_manifest_source_inventory")

        # Verify cached MSELF reuse check
        self.assertIn("it->second.size == entry.size && it->second.mtime == entry.mtime && !it->second.digest.empty()", content)

        # Verify manifest version 3 support with mself_records
        self.assertIn('{"version", 3}', content)
        self.assertIn('document.contains("mself_records")', content)

    def test_aborted_scan_does_not_publish_empty_manifest(self):
        """R06: Track whether scan was aborted or file_queue is empty, do not call ppu_manifest_save, remove partial manifest."""
        ppu_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "Cell"
            / "PPUThread.cpp"
        )
        content = ppu_src.read_text(encoding="utf-8")

        self.assertIn("bool scan_aborted = false;", content)
        self.assertIn("scan_aborted || file_queue.empty() || Emu.IsStopped()", content)
        self.assertIn("fs::remove_file(manifest_path);", content)

        # Also verify ppu_manifest_save refuses empty entries
        self.assertIn("if (entries.empty())", content)

    def test_empty_manifest_rejected_and_removed_on_load(self):
        """R06: If manifest_entries.empty(), reject hit, remove empty manifest from disk, return false."""
        ppu_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "Cell"
            / "PPUThread.cpp"
        )
        content = ppu_src.read_text(encoding="utf-8")

        self.assertIn("if (manifest_entries.empty())", content)
        self.assertIn("fs::remove_file(path);", content)

    def test_same_size_same_mtime_content_verification(self):
        """R07: Prevent stale MSELF reuse from size/mtime alone via sample digest, and verify non-MSELF entry digests."""
        ppu_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "Cell"
            / "PPUThread.cpp"
        )
        content = ppu_src.read_text(encoding="utf-8")

        # Verify ppu_manifest_file_sample_digest helper
        self.assertIn("ppu_manifest_file_sample_digest(const std::string& path)", content)
        self.assertIn("ppu_manifest_file_sample_digest(path)", content)
        self.assertIn("header_digest", content)

        # Verify non-MSELF entry content digest validation in ppu_manifest_entry_is_valid
        self.assertIn('if (!fmt::to_upper(entry.path).ends_with(".MSELF"))', content)
        self.assertIn('value.contains("digest")', content)
        self.assertIn("ppu_manifest_file_digest(entry.path) != recorded_digest", content)

        # Verify non-MSELF entry content digest inclusion in ppu_manifest_save
        self.assertIn('entry_json["digest"] = ppu_manifest_file_digest(entry.path);', content)

    def test_jitllvm_static_thread_safety(self):
        """R10: Verify compiler logging & static string thread-safety in JITLLVM and AArch64Common."""
        jitllvm_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "util"
            / "JITLLVM.cpp"
        )
        jitllvm_content = jitllvm_src.read_text(encoding="utf-8")

        # Verify jit_compiler::cpu() has no shared mutable static strings
        cpu_fn_start = jitllvm_content.find("std::string jit_compiler::cpu(const std::string& _cpu)")
        cpu_fn_end = jitllvm_content.find("std::string jit_compiler::features1()")
        self.assertNotEqual(cpu_fn_start, -1)
        self.assertNotEqual(cpu_fn_end, -1)
        cpu_fn = jitllvm_content[cpu_fn_start:cpu_fn_end]
        self.assertNotIn("static std::string s_last_configured", cpu_fn)
        self.assertNotIn("static std::string s_last_effective", cpu_fn)

        # Verify features1() exists and is static method
        self.assertIn("std::string jit_compiler::features1()", jitllvm_content)

        # Verify fallback_cpu_detection uses static const std::string
        self.assertIn("static const std::string s_result =", jitllvm_content)

        # Verify AArch64Common atomic features
        aarch64_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "CPU"
            / "Backends"
            / "AArch64"
            / "AArch64Common.cpp"
        )
        aarch64_content = aarch64_src.read_text(encoding="utf-8")
        self.assertIn("static std::atomic<u32> s_effective_features", aarch64_content)
        self.assertIn("s_effective_features.load(std::memory_order_relaxed)", aarch64_content)
        self.assertIn("static const cpu_vendor_t s_vendors_list[]", aarch64_content)
        self.assertIn("static const cpu_entry_t s_cpu_list[]", aarch64_content)


if __name__ == "__main__":
    unittest.main()
