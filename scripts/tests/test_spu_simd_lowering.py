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
            self.assertIn("ROTMI, ROTMAI, and SHLI verified bit-exact across all shift ranges!", run_res.stdout)
            self.assertIn("ROTHMI, ROTMAHI, and SHLHI verified bit-exact across all shift ranges!", run_res.stdout)
            self.assertIn("ARM64 NEON PSHUFB emulation verified bit-exact against SSSE3 across all 256 index codes!", run_res.stdout)
            self.assertIn("Constant vs Non-Constant lowering equivalence verified for all 128 immediate values!", run_res.stdout)
            self.assertIn("ALL SPU SIMD LOWERING & TARGET FEATURE TESTS PASSED!", run_res.stdout)

    def test_source_lowering_tokens(self):
        """Verify SPULLVMRecompiler.cpp implements targeted ARM64 SIMD lowering without tbl2 emergency spill."""
        recompiler_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPULLVMRecompiler.cpp")
        with open(recompiler_file, "r") as f:
            content = f.read()

        # FSM/FSMH/FSMB cmtst / bitmask lowering
        self.assertIn("const auto bit_mask = build<u32[4]>(1, 2, 4, 8);", content)
        self.assertIn("const auto bit_mask = build<u16[8]>(1, 2, 4, 8, 16, 32, 64, 128);", content)
        self.assertIn("const auto bit_mask = build<u8[16]>(1, 2, 4, 8, 16, 32, 64, 128, 1, 2, 4, 8, 16, 32, 64, 128);", content)

        # FSMBI constant vector build
        self.assertIn("m_op_const_mask & op.i16.data_mask()", content)

        # ROTQBYI / SHLQBYI / ROTQMBYI constant zshuffle lowering
        self.assertIn("m_op_const_mask & op.i7.data_mask()", content)

        # tbl2 eliminated to prevent register scavenging emergency spill crash on ARM64
        self.assertNotIn("llvm::Intrinsic::aarch64_neon_tbl2", content)

    def test_jit_llvm_engine_mattrs(self):
        """Verify JITLLVM.cpp avoids unsafe MAttrs on AArch64 that trigger emergency spill crashes."""
        jit_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "util", "JITLLVM.cpp")
        with open(jit_file, "r") as f:
            content = f.read()

        # Verify builder does not inject unsafe SVE/SVE2 mattrs
        self.assertNotIn("builder.setMAttrs(mattrs);", content)

    def test_aarch64_sve_hwcap_gating(self):
        """Verify AArch64Common.cpp does not expose SVE/SVE2 to LLVM backend."""
        common_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "CPU", "Backends", "AArch64", "AArch64Common.cpp")
        with open(common_file, "r") as f:
            content = f.read()

        # SVE/SVE2 are eliminated from feature string to prevent emergency spill crash on ARM64
        self.assertNotIn("+sve", content)
        self.assertNotIn("+sve2", content)

    def test_actual_generated_arm64_code(self):
        """Inspect actual generated ARM64 instructions emitted by LLVM targeting Cortex-X4."""
        which_llc = subprocess.run(["which", "llc"], capture_output=True, text=True)
        if which_llc.returncode != 0:
            self.skipTest("llc not available on host")

        llvm_ir = """
target triple = "aarch64-linux-android"

define <4 x i32> @fsm(i32 %v) {
  %splat.ins = insertelement <4 x i32> poison, i32 %v, i32 0
  %splat = shufflevector <4 x i32> %splat.ins, <4 x i32> poison, <4 x i32> zeroinitializer
  %and = and <4 x i32> %splat, <i32 1, i32 2, i32 4, i32 8>
  %cmp = icmp ne <4 x i32> %and, zeroinitializer
  %res = sext <4 x i1> %cmp to <4 x i32>
  ret <4 x i32> %res
}

define <8 x i16> @fsmh(i32 %v) {
  %trunc = trunc i32 %v to i16
  %splat.ins = insertelement <8 x i16> poison, i16 %trunc, i32 0
  %splat = shufflevector <8 x i16> %splat.ins, <8 x i16> poison, <8 x i32> zeroinitializer
  %and = and <8 x i16> %splat, <i16 1, i16 2, i16 4, i16 8, i16 16, i16 32, i16 64, i16 128>
  %cmp = icmp ne <8 x i16> %and, zeroinitializer
  %res = sext <8 x i1> %cmp to <8 x i16>
  ret <8 x i16> %res
}

define <16 x i8> @fsmb(i32 %v) {
  %b0 = trunc i32 %v to i8
  %v.sh = lshr i32 %v, 8
  %b1 = trunc i32 %v.sh to i8
  %v0.ins = insertelement <16 x i8> poison, i8 %b0, i32 0
  %v0 = shufflevector <16 x i8> %v0.ins, <16 x i8> poison, <16 x i32> zeroinitializer
  %v1.ins = insertelement <16 x i8> poison, i8 %b1, i32 0
  %v1 = shufflevector <16 x i8> %v1.ins, <16 x i8> poison, <16 x i32> zeroinitializer
  %combined = shufflevector <16 x i8> %v0, <16 x i8> %v1, <16 x i32> <i32 0, i32 1, i32 2, i32 3, i32 4, i32 5, i32 6, i32 7, i32 24, i32 25, i32 26, i32 27, i32 28, i32 29, i32 30, i32 31>
  %and = and <16 x i8> %combined, <i8 1, i8 2, i8 4, i8 8, i8 16, i8 32, i8 64, i8 -128, i8 1, i8 2, i8 4, i8 8, i8 16, i8 32, i8 64, i8 -128>
  %cmp = icmp ne <16 x i8> %and, zeroinitializer
  %res = sext <16 x i1> %cmp to <16 x i8>
  ret <16 x i8> %res
}

declare <16 x i8> @llvm.aarch64.neon.tbl1.v16i8(<16 x i8>, <16 x i8>)
define <16 x i8> @pshufb_neon(<16 x i8> %data, <16 x i8> %index) {
  %and = and <16 x i8> %index, <i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113, i8 -113>
  %tbl = call <16 x i8> @llvm.aarch64.neon.tbl1.v16i8(<16 x i8> %data, <16 x i8> %and)
  ret <16 x i8> %tbl
}

define <16 x i8> @rotqbyi_const(<16 x i8> %a) {
  %shuf = shufflevector <16 x i8> %a, <16 x i8> poison, <16 x i32> <i32 13, i32 14, i32 15, i32 0, i32 1, i32 2, i32 3, i32 4, i32 5, i32 6, i32 7, i32 8, i32 9, i32 10, i32 11, i32 12>
  ret <16 x i8> %shuf
}

define <4 x i32> @shli_const(<4 x i32> %a) {
  %sh = shl <4 x i32> %a, <i32 4, i32 4, i32 4, i32 4>
  ret <4 x i32> %sh
}

define <4 x i32> @rotmai_const(<4 x i32> %a) {
  %sh = ashr <4 x i32> %a, <i32 4, i32 4, i32 4, i32 4>
  ret <4 x i32> %sh
}

define <4 x i32> @rotmi_const(<4 x i32> %a) {
  %sh = lshr <4 x i32> %a, <i32 4, i32 4, i32 4, i32 4>
  ret <4 x i32> %sh
}

define <8 x i16> @rothmi_const(<8 x i16> %a) {
  %sh = lshr <8 x i16> %a, <i16 4, i16 4, i16 4, i16 4, i16 4, i16 4, i16 4, i16 4>
  ret <8 x i16> %sh
}

define <8 x i16> @rotmahi_const(<8 x i16> %a) {
  %sh = ashr <8 x i16> %a, <i16 4, i16 4, i16 4, i16 4, i16 4, i16 4, i16 4, i16 4>
  ret <8 x i16> %sh
}

define <8 x i16> @shlhi_const(<8 x i16> %a) {
  %sh = shl <8 x i16> %a, <i16 4, i16 4, i16 4, i16 4, i16 4, i16 4, i16 4, i16 4>
  ret <8 x i16> %sh
}
"""
        with tempfile.TemporaryDirectory() as tmpdir:
            ll_path = os.path.join(tmpdir, "test.ll")
            s_path = os.path.join(tmpdir, "test.s")
            with open(ll_path, "w") as f:
                f.write(llvm_ir)

            cmd = ["llc", "-march=aarch64", "-mcpu=cortex-x4", "-O2", ll_path, "-o", s_path]
            res = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(res.returncode, 0, f"llc compilation failed: {res.stderr}")

            with open(s_path, "r") as f:
                asm = f.read()

            # Verify vector instructions generated without scalarization or spills
            self.assertIn("cmtst\tv0.4s, v0.4s, v1.4s", asm)
            self.assertIn("cmtst\tv0.8h, v0.8h, v1.8h", asm)
            self.assertIn("cmtst\tv0.16b, v0.16b, v1.16b", asm)
            self.assertIn("tbl\tv0.16b, { v0.16b }, v1.16b", asm)
            self.assertIn("ext\tv0.16b, v0.16b, v0.16b, #13", asm)
            self.assertIn("shl\tv0.4s, v0.4s, #4", asm)
            self.assertIn("sshr\tv0.4s, v0.4s, #4", asm)
            self.assertIn("ushr\tv0.4s, v0.4s, #4", asm)
            self.assertIn("ushr\tv0.8h, v0.8h, #4", asm)
            self.assertIn("sshr\tv0.8h, v0.8h, #4", asm)
            self.assertIn("shl\tv0.8h, v0.8h, #4", asm)

            # Verify no emergency register spill or stack allocation
            self.assertNotIn("str\tq", asm)
            self.assertNotIn("sub\tsp, sp", asm)

if __name__ == "__main__":
    unittest.main()


