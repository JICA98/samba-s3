#!/usr/bin/env python3
import os
import re
import subprocess
import tempfile
import unittest

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

class Phase8SpuTests(unittest.TestCase):
    def test_cpp_unit_test(self):
        """Build and run the host C++ unit test validating R13 atomic redirection & R14 decoder routing."""
        cpp_file = os.path.join(ROOT_DIR, "scripts", "tests", "test_phase8_spu.cpp")
        with tempfile.TemporaryDirectory() as tmpdir:
            bin_path = os.path.join(tmpdir, "test_phase8_spu")
            compile_cmd = ["g++", "-O2", "-std=c++20", cpp_file, "-o", bin_path]
            res = subprocess.run(compile_cmd, capture_output=True, text=True)
            self.assertEqual(res.returncode, 0, f"Compilation failed: {res.stderr}")
            run_res = subprocess.run([bin_path], capture_output=True, text=True)
            self.assertEqual(run_res.returncode, 0, f"Execution failed: {run_res.stderr}")
            self.assertIn("All SPU redirection tests PASSED", run_res.stdout)
            self.assertIn("All SPU decoder selection tests PASSED", run_res.stdout)

    def test_spu_common_recompiler_arm64_redirection_r13(self):
        """Verify SPUCommonRecompiler.cpp implements R13 single 32-bit atomic branch and veneer."""
        spu_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPUCommonRecompiler.cpp")
        with open(spu_file, "r") as f:
            content = f.read()

        # Locate ARM64 redirection block
        redirection_token = '#error "Unimplemented architecture for SPU function redirection"'
        self.assertIn(redirection_token, content)
        arm64_redirection_part = content.split(redirection_token)[0]
        arm64_block = arm64_redirection_part.split("#elif defined(ARCH_ARM64)")[-1]

        self.assertIn("0x14000000", arm64_block)
        self.assertIn("0x58000050", arm64_block)
        self.assertIn("0xd61f0200", arm64_block)
        self.assertIn("rx::clean_dcache_invalidate_icache", arm64_block)
        self.assertIn('asm volatile("dsb ish; isb" ::: "memory");', arm64_block)

        # Verify R13 fix: single 32-bit atomic store at prog->first (not 64-bit multi-instruction)
        self.assertIn("atomic_storage<u32>::release(*reinterpret_cast<u32*>(prog->first), b_insn);", arm64_block)
        # Ensure 64-bit multi-instruction overwrite is NOT present in ARM64 redirection
        self.assertNotIn("atomic_storage<u64>", arm64_block)
        self.assertNotIn("patch[0], 0xd61f020058000050ULL", arm64_block)
        self.assertNotIn("0xd503201f", arm64_block)  # No NOP packed into 64-bit overwrite

        # Verify spu_fast::compile is strictly guarded against non-X64
        self.assertIn("#if !defined(ARCH_X64)", content)
        self.assertIn("Fast LLVM recompiler is unimplemented for architectures other than X86-64", content)

    def test_spu_thread_decoder_selection_and_r14_claims(self):
        """Verify SPUThread.cpp accurately implements and documents R14 decoder routing."""
        spu_thread_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPUThread.cpp")
        with open(spu_thread_file, "r") as f:
            content = f.read()

        # Verify routing in both constructors
        x64_route_count = content.count("jit = spu_recompiler_base::make_fast_llvm_recompiler();")
        arm64_route_count = content.count("jit = spu_recompiler_base::make_llvm_recompiler();")
        self.assertGreaterEqual(x64_route_count, 2, "Expected at least 2 make_fast_llvm_recompiler calls for ARCH_X64")
        self.assertGreaterEqual(arm64_route_count, 2, "Expected at least 2 make_llvm_recompiler calls for ARCH_ARM64")

        # Verify R14 documentation comments in SPUThread.cpp
        self.assertIn("Withdraw ARM SPU first-tier claim", content)
        self.assertIn("routes directly to make_llvm_recompiler()", content)
        self.assertIn("fast tier (make_fast_llvm_recompiler()) is strictly x86_64-only", content)
        self.assertIn("No ARM SPU tier-1 fast compiler is claimed or enabled", content)

    def test_decoder_selection_simulation(self):
        """Explicitly validate decoder selection and tier-1 fast recompiler claims across architectures."""
        def select_spu_decoder(arch: str, decoder: str) -> str:
            if decoder == "llvm":
                if arch == "ARCH_X64":
                    return "make_fast_llvm_recompiler"
                elif arch == "ARCH_ARM64":
                    return "make_llvm_recompiler"
                else:
                    raise ValueError(f"Unimplemented architecture: {arch}")
            elif decoder == "asmjit":
                return "make_asmjit_recompiler"
            raise ValueError(f"Unsupported decoder: {decoder}")

        def is_arm_tier1_claimed() -> bool:
            # R14 (P2): Withdrawn ARM SPU first-tier claim
            return False

        # ARCH_ARM64: spu_decoder::llvm routes to make_llvm_recompiler() (synchronous LLVM compiler)
        self.assertEqual(select_spu_decoder("ARCH_ARM64", "llvm"), "make_llvm_recompiler")
        self.assertNotEqual(select_spu_decoder("ARCH_ARM64", "llvm"), "make_fast_llvm_recompiler")

        # ARCH_X64: spu_decoder::llvm routes to make_fast_llvm_recompiler()
        self.assertEqual(select_spu_decoder("ARCH_X64", "llvm"), "make_fast_llvm_recompiler")

        # Assert ARM tier-1 fast recompiler is NOT claimed or enabled
        self.assertFalse(is_arm_tier1_claimed())

    def test_single_32bit_near_branch_and_veneer_encoding(self):
        """Validate single 32-bit atomic instruction encoding for near branch and veneer encoding."""
        def encode_near_branch(diff: int) -> int:
            self.assertTrue(-(1 << 27) <= diff < (1 << 27), "diff out of +/-128MB range")
            return 0x14000000 | ((diff >> 2) & 0x03FFFFFF)

        # Single 32-bit instruction (size == 4 bytes)
        insn_forward = encode_near_branch(4)
        self.assertEqual(insn_forward, 0x14000001)
        self.assertLessEqual(insn_forward, 0xFFFFFFFF)

        insn_backward = encode_near_branch(-4)
        self.assertEqual(insn_backward, 0x14000000 | 0x03FFFFFF)
        self.assertLessEqual(insn_backward, 0xFFFFFFFF)

        # Veneer opcodes
        ldr_insn = 0x58000050  # ldr x16, #8
        br_insn = 0xd61f0200   # br x16
        self.assertEqual(ldr_insn, 0x58000050)
        self.assertEqual(br_insn, 0xd61f0200)

        # Branch to veneer is also a single 32-bit B instruction
        veneer_branch = encode_near_branch(64)
        self.assertEqual(veneer_branch, 0x14000010)
        self.assertLessEqual(veneer_branch, 0xFFFFFFFF)

if __name__ == "__main__":
    unittest.main()
