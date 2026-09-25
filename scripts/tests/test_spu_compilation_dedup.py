#!/usr/bin/env python3
"""
Unit tests for Ticket J07: Runtime JIT Latency & Compilation Deduplication.
Validates:
1. Native C++ execution of single-flight state machine under 6 concurrent SPU workers,
   publication order, barrier correctness, and cache key formatting.
2. Source inspection of SPULLVMRecompiler.cpp, SPURecompiler.h, and SPUThread.cpp.
"""

import os
import subprocess
import tempfile
import unittest

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

class SpuCompilationDedupTests(unittest.TestCase):
    def test_cpp_unit_test(self):
        """Build and run the host C++ unit test validating SPU LLVM compilation deduplication."""
        cpp_file = os.path.join(ROOT_DIR, "scripts", "tests", "test_spu_compilation_dedup.cpp")
        rx_inc = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rx", "include")
        with tempfile.TemporaryDirectory() as tmpdir:
            bin_path = os.path.join(tmpdir, "test_spu_compilation_dedup")
            compile_cmd = ["g++", "-O2", "-std=c++20", f"-I{rx_inc}", cpp_file, "-o", bin_path]
            res = subprocess.run(compile_cmd, capture_output=True, text=True)
            self.assertEqual(res.returncode, 0, f"Compilation failed: {res.stderr}")
            run_res = subprocess.run([bin_path], capture_output=True, text=True)
            self.assertEqual(run_res.returncode, 0, f"Execution failed: {run_res.stderr}")
            self.assertIn("ALL 6 SPU COMPILATION DEDUPLICATION & PUBLICATION TESTS PASSED!", run_res.stdout)

    def test_spu_recompiler_header_definitions(self):
        """Verify SPURecompiler.h defines spu_compile_state, compile_state, and thread-safe cache mutex."""
        header_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPURecompiler.h")
        with open(header_file, "r") as f:
            content = f.read()

        self.assertIn("enum class spu_compile_state : u32", content)
        self.assertIn("uncompiled = 0", content)
        self.assertIn("compiling = 1", content)
        self.assertIn("compiled = 2", content)
        self.assertIn("failed = 3", content)
        self.assertIn("compile_state = static_cast<u32>(spu_compile_state::uncompiled);", content)
        self.assertIn("std::unique_ptr<std::mutex> m_mutex", content)

    def test_spu_llvm_recompiler_single_flight(self):
        """Verify SPULLVMRecompiler.cpp implements single-flight state machine, publication barrier, and cache keys."""
        recompiler_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPULLVMRecompiler.cpp")
        with open(recompiler_file, "r") as f:
            content = f.read()

        # Fast path
        self.assertIn("if (const auto fn = add_loc->compiled.load())", content)

        # Single flight CAS transition
        self.assertIn("add_loc->compile_state.compare_exchange(expected, static_cast<u32>(spu_compile_state::compiling))", content)
        self.assertIn("add_loc->compile_state.wait(state);", content)

        # RAII failure guard
        self.assertIn("struct single_flight_guard", content)
        self.assertIn("item->compile_state.store(static_cast<u32>(spu_compile_state::failed));", content)

        # Cache key incorporating guest bytes, codegen identity, target CPU, active features
        self.assertIn("codegen_tag = \"s3cg1\";", content)
        self.assertIn("m_jit.get_effective_cpu()", content)
        self.assertIn("m_jit.get_effective_features()", content)
        self.assertIn("__spu-0x%05x-%s-%s-%s-%s", content)

        # Atomic publication order and release
        self.assertIn("add_loc->compiled = fn;", content)
        self.assertIn("add_loc->compile_state.store(static_cast<u32>(spu_compile_state::compiled));", content)
        self.assertIn("flight_guard.success = true;", content)
        self.assertIn("add_loc->compile_state.notify_all();", content)

        # Prompt memory and IR release
        self.assertIn("m_ir = nullptr;", content)
        self.assertIn("m_module = nullptr;", content)
        self.assertIn("m_engine->clearAllGlobalMappings();", content)

    def test_spu_common_recompiler_thread_safe_cache(self):
        """Verify SPUCommonRecompiler.cpp synchronizes cache.add with fine-grained mutex."""
        common_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPUCommonRecompiler.cpp")
        with open(common_file, "r") as f:
            content = f.read()

        self.assertIn("std::unique_lock<std::mutex> lock;", content)
        self.assertIn("lock = std::unique_lock<std::mutex>(*m_mutex);", content)

    def test_spu_thread_workers_documented(self):
        """Verify SPUThread.cpp documents 6 concurrent SPU worker single-flight deduplication."""
        thread_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "Cell", "SPUThread.cpp")
        with open(thread_file, "r") as f:
            content = f.read()

        self.assertIn("SPU single-flight state machine deduplicates block compilation across all 6 concurrent SPU workers", content)

if __name__ == "__main__":
    unittest.main()
