#!/usr/bin/env python3
import os
import subprocess
import tempfile
import unittest

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

class SpuSimdLoweringTests(unittest.TestCase):
    def test_cpp_unit_test(self):
        """Build and run the host C++ unit test validating SPU ARM64 SIMD lowering equivalence."""
        cpp_file = os.path.join(ROOT_DIR, "scripts", "tests", "test_spu_simd_lowering.cpp")
        with tempfile.TemporaryDirectory() as tmpdir:
            bin_path = os.path.join(tmpdir, "test_spu_simd_lowering")
            compile_cmd = ["g++", "-O2", "-std=c++20", cpp_file, "-o", bin_path]
            res = subprocess.run(compile_cmd, capture_output=True, text=True)
            self.assertEqual(res.returncode, 0, f"Compilation failed: {res.stderr}")
            run_res = subprocess.run([bin_path], capture_output=True, text=True)
            self.assertEqual(run_res.returncode, 0, f"Execution failed: {run_res.stderr}")
            self.assertIn("SPU SHUFB AArch64 NEON lowering verified bit-exact!", run_res.stdout)
            self.assertIn("FSMBI verified bit-exact across all 65,536 values!", run_res.stdout)
            self.assertIn("FSM verified bit-exact across all 16 word masks!", run_res.stdout)
            self.assertIn("FSMH verified bit-exact across all 256 halfword masks!", run_res.stdout)
            self.assertIn("FSMB verified bit-exact across all 65,536 byte masks!", run_res.stdout)
            self.assertIn("ROTQBYI, SHLQBYI, ROTQMBYI verified bit-exact across all shift amounts!", run_res.stdout)
            self.assertIn("ROTMI and SHLI verified bit-exact across all shift ranges!", run_res.stdout)
            self.assertIn("ALL SPU SIMD LOWERING & TARGET FEATURE TESTS PASSED!", run_res.stdout)

    def test_source_lowering_tokens(self):
        """Verify SPULLVMRecompiler.cpp implements targeted ARM64 SIMD lowering."""
        recompiler_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPULLVMRecompiler.cpp")
        with open(recompiler_file, "r") as f:
            content = f.read()

        # SHUFB ARM64 tbl2 lowering
        self.assertIn("llvm::Intrinsic::aarch64_neon_tbl2", content)
        self.assertIn("(c ^ 0xf) & 0x1f", content)
        self.assertIn("(c ^ 0xf) & 0x9f", content)

        # FSM/FSMH/FSMB cmtst / bitmask lowering
        self.assertIn("const auto bit_mask = build<u32[4]>(1, 2, 4, 8);", content)
        self.assertIn("const auto bit_mask = build<u16[8]>(1, 2, 4, 8, 16, 32, 64, 128);", content)
        self.assertIn("const auto bit_mask = build<u8[16]>(1, 2, 4, 8, 16, 32, 64, 128, 1, 2, 4, 8, 16, 32, 64, 128);", content)

        # FSMBI constant vector build
        self.assertIn("m_op_const_mask & op.i16.data_mask()", content)

        # ROTQBYI / SHLQBYI / ROTQMBYI constant zshuffle lowering
        self.assertIn("m_op_const_mask & op.i7.data_mask()", content)

    def test_jit_llvm_engine_mattrs(self):
        """Verify JITLLVM.cpp forwards MAttrs on AArch64."""
        jit_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "util", "JITLLVM.cpp")
        with open(jit_file, "r") as f:
            content = f.read()

        self.assertIn("builder.setMAttrs(mattrs);", content)

    def test_aarch64_sve_hwcap_gating(self):
        """Verify AArch64Common.cpp gates SVE/SVE2 on OS getauxval."""
        common_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "CPU", "Backends", "AArch64", "AArch64Common.cpp")
        with open(common_file, "r") as f:
            content = f.read()

        self.assertIn("getauxval(AT_HWCAP)", content)
        self.assertIn("getauxval(AT_HWCAP2)", content)
        self.assertIn("has_os_sve", content)
        self.assertIn("has_os_sve2", content)

if __name__ == "__main__":
    unittest.main()
