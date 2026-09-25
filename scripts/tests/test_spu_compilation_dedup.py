#!/usr/bin/env python3
"""
Unit and regression tests for Ticket CR05 / Phase 7:
SPU Single-Flight Compilation, Producer-Owned Progress, and Cache Key Identity.

Validates:
1. Native C++ execution of tiered single-flight state machine, publication order,
   waiter abort/stop handling, trampoline failure isolation, canonical feature
   parsing without substring false matches, and producer-owned progress completion.
2. Source inspection of SPURecompiler.h, SPULLVMRecompiler.cpp, SPUASMJITRecompiler.cpp,
   compile_progress.hpp, system_progress.cpp, RPCSX.kt, and CompileProgressBridge.kt.
"""

import os
import subprocess
import tempfile
import unittest

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

class SpuCompilationDedupTests(unittest.TestCase):
    def test_cpp_unit_test(self):
        """Build and run the host C++ unit test validating CR05 SPU single-flight & progress."""
        cpp_file = os.path.join(ROOT_DIR, "scripts", "tests", "test_spu_compilation_dedup.cpp")
        rx_inc = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rx", "include")
        with tempfile.TemporaryDirectory() as tmpdir:
            bin_path = os.path.join(tmpdir, "test_spu_compilation_dedup")
            compile_cmd = ["g++", "-O2", "-std=c++20", f"-I{rx_inc}", cpp_file, "-o", bin_path]
            res = subprocess.run(compile_cmd, capture_output=True, text=True)
            self.assertEqual(res.returncode, 0, f"Compilation failed: {res.stderr}")
            run_res = subprocess.run([bin_path], capture_output=True, text=True)
            self.assertEqual(run_res.returncode, 0, f"Execution failed: {run_res.stderr}")
            self.assertIn("ALL 11 SPU SINGLE-FLIGHT & PROGRESS TESTS PASSED SUCCESSFULLY!", run_res.stdout)

    def test_spu_recompiler_header_definitions(self):
        """Verify SPURecompiler.h defines CR05 tiered states and callable helper."""
        header_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPURecompiler.h")
        with open(header_file, "r") as f:
            content = f.read()

        self.assertIn("enum class spu_compile_state : u32", content)
        self.assertIn("uncompiled = 0", content)
        self.assertIn("compiling_fast = 1", content)
        self.assertIn("compiled_fast = 2", content)
        self.assertIn("compiling_optimized = 3", content)
        self.assertIn("compiled_optimized = 4", content)
        self.assertIn("failed = 5", content)
        self.assertIn("is_spu_callable_state", content)

    def test_spu_llvm_recompiler_single_flight(self):
        """Verify SPULLVMRecompiler.cpp implements tiered single flight, abort waiter, and canonical cache key."""
        recompiler_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPULLVMRecompiler.cpp")
        with open(recompiler_file, "r") as f:
            content = f.read()

        # Fast path checks compiled_optimized
        self.assertIn("spu_compile_state::compiled_optimized", content)

        # CAS into compiling_optimized
        self.assertIn("add_loc->compile_state.compare_exchange", content)
        self.assertIn("spu_compile_state::compiling_optimized", content)

        # Waiter checks Emu.IsStopped() || aborting
        self.assertIn("Emu.IsStopped() || thread_ctrl::state() == thread_state::aborting", content)
        self.assertIn("atomic_wait_timeout(50000000ull)", content)

        # Guard fallback restores compiled_fast if prior was fast
        self.assertIn("prior_state == static_cast<u32>(spu_compile_state::compiled_fast)", content)

        # Canonical feature parser
        self.assertIn("token[0] == '+'", content)
        self.assertIn("token[0] == '-'", content)
        self.assertIn("s3cg2", content)
        self.assertIn("spu_block_size", content)

        # Trampoline rebuild strictly before publication of compiled_optimized
        self.assertIn("m_spurt->rebuild_ubertrampoline(func.data[0])", content)
        self.assertIn("add_loc->compile_state.store(static_cast<u32>(spu_compile_state::compiled_optimized)", content)

    def test_spu_asmjit_recompiler_single_flight(self):
        """Verify SPUASMJITRecompiler.cpp implements fast tier single flight and abort check."""
        asmjit_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPUASMJITRecompiler.cpp")
        with open(asmjit_file, "r") as f:
            content = f.read()

        self.assertIn("is_spu_callable_state", content)
        self.assertIn("spu_compile_state::compiling_fast", content)
        self.assertIn("Emu.IsStopped() || thread_ctrl::state() == thread_state::aborting", content)
        self.assertIn("rebuild_ubertrampoline", content)
        self.assertIn("add_loc->compile_state.store(static_cast<u32>(spu_compile_state::compiled_fast)", content)

    def test_producer_owned_progress_completion(self):
        """Verify system_progress.cpp and compile_progress.hpp enforce producer-owned completion."""
        sys_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "system_progress.cpp")
        with open(sys_file, "r") as f:
            sys_content = f.read()

        # Prohibited shortcuts must NOT exist
        self.assertNotIn("pdone >= ptotal - 1", sys_content)
        self.assertNotIn("empty_text_grace_count >= 50", sys_content)

        # Strict producer-owned completion condition in system_progress.cpp
        self.assertIn("ftotal == fdone && ptotal == pdone", sys_content)

        hdr_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "compile_progress.hpp")
        with open(hdr_file, "r") as f:
            hdr_content = f.read()

        self.assertIn("CompileDomain", hdr_content)
        self.assertIn("CompileJobRecord", hdr_content)
        self.assertIn("producer_closed", hdr_content)
        self.assertIn("workers_joined", hdr_content)
        self.assertIn("ZERO_WORK_CACHE_HIT", hdr_content)

        spu_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPUCommonRecompiler.cpp")
        with open(spu_file, "r") as f:
            spu_content = f.read()

        self.assertIn("start_compile_job", spu_content)
        self.assertIn("finish_compile_job", spu_content)
        self.assertIn("workers.join()", spu_content)
        self.assertNotIn("g_progr_pdone = g_progr_ptotal.load()", spu_content)

    def test_kotlin_compile_progress_bridge(self):
        """Verify RPCSX.kt and CompileProgressBridge.kt handle COMPILE_DOMAIN_SPU = 2."""
        rpcsx_file = os.path.join(ROOT_DIR, "app", "src", "main", "java", "com", "zenithblue", "sambas3", "RPCSX.kt")
        with open(rpcsx_file, "r") as f:
            rpcsx_content = f.read()
        self.assertIn("COMPILE_DOMAIN_SPU = 2", rpcsx_content)

        bridge_file = os.path.join(ROOT_DIR, "app", "src", "main", "java", "com", "zenithblue", "sambas3", "CompileProgressBridge.kt")
        with open(bridge_file, "r") as f:
            bridge_content = f.read()
        self.assertIn("RPCSX.COMPILE_DOMAIN_SPU", bridge_content)

if __name__ == "__main__":
    unittest.main()
