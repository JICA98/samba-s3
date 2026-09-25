#!/usr/bin/env python3
"""
Unit and regression tests for Ticket V08 / UP-12:
RSX & Vulkan Work, Readback Scratch Reuse, and Batching.

Validates:
1. Native C++ execution of scratch buffer reuse, capacity retention across readback cycles,
   multithreaded isolation, swizzle correctness, RMW memory boundary preservation,
   semantic state hashing, and state redundancy checking.
2. Source inspection of VKTextureCache.h, VKPipelineCompiler.h, VKGSRender.cpp,
   VKDraw.cpp, and VKTextureCache.cpp.
"""

import os
import subprocess
import tempfile
import unittest

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

class VulkanScratchReuseTests(unittest.TestCase):
    def test_cpp_unit_test(self):
        """Build and run the host C++ unit test validating scratch buffer reuse and Vulkan work."""
        cpp_file = os.path.join(ROOT_DIR, "scripts", "tests", "test_vulkan_scratch_reuse.cpp")
        with tempfile.TemporaryDirectory() as tmpdir:
            bin_path = os.path.join(tmpdir, "test_vulkan_scratch_reuse")
            compile_cmd = ["g++", "-O2", "-std=c++20", cpp_file, "-o", bin_path]
            res = subprocess.run(compile_cmd, capture_output=True, text=True)
            self.assertEqual(res.returncode, 0, f"Compilation failed: {res.stderr}")
            run_res = subprocess.run([bin_path], capture_output=True, text=True)
            self.assertEqual(run_res.returncode, 0, f"Execution failed: {run_res.stderr}")
            self.assertIn("ALL 5 VULKAN WORK & SCRATCH REUSE TESTS PASSED SUCCESSFULLY!", run_res.stdout)

    def test_vk_texture_cache_scratch_reuse(self):
        """Verify VKTextureCache.h reuses thread-owned scratch buffer storage with capacity retention."""
        header_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "RSX", "VK", "VKTextureCache.h")
        with open(header_file, "r") as f:
            content = f.read()

        # Check that per-flush dynamic allocation is eliminated
        self.assertNotIn("std::vector<u8> tmp_data(swiz_size);", content)
        # Check thread-local scratch buffer with capacity retention
        self.assertIn("static thread_local std::vector<u8> s_swizzle_scratch;", content)
        self.assertIn("if (s_swizzle_scratch.size() < swiz_size)", content)
        self.assertIn("s_swizzle_scratch.resize(swiz_size);", content)
        self.assertIn("std::memcpy(s_swizzle_scratch.data(), data, swiz_size);", content)
        self.assertIn("rsx::convert_linear_swizzle<u32, false>(s_swizzle_scratch.data(), data, width, height, rsx_pitch);", content)

    def test_vk_pipeline_compiler_semantic_hashing(self):
        """Verify VKPipelineCompiler.h hashes semantic properties directly and avoids pointer/padding hashing."""
        header_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "RSX", "VK", "VKPipelineCompiler.h")
        with open(header_file, "r") as f:
            content = f.read()

        self.assertIn("hash_struct<vk::pipeline_props>", content)
        # Ensure FNV-1a mixing across semantic fields
        self.assertIn("seed = fnv_seed;", content)
        self.assertIn("seed = hash64(seed, pipelineProperties.renderpass_key);", content)
        self.assertIn("seed = hash64(seed, static_cast<u32>(pipelineProperties.state.ia.topology));", content)
        self.assertIn("seed = hash64(seed, static_cast<u32>(pipelineProperties.state.rs.cullMode));", content)
        self.assertIn("seed = hash64(seed, static_cast<u32>(pipelineProperties.state.ds.depthCompareOp));", content)
        self.assertIn("seed = hash64(seed, pipelineProperties.state.cs.attachmentCount);", content)
        # Ensure pointers are NOT hashed
        self.assertNotIn("seed ^= hash_struct(ms_tmp);", content)
        self.assertNotIn("seed ^= hash_struct(tmp);", content)

    def test_vk_gs_render_redundant_state_filtering(self):
        """Verify VKGSRender.cpp elides redundant load_program lookups when semantic state is unchanged."""
        cpp_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "RSX", "VK", "VKGSRender.cpp")
        with open(cpp_file, "r") as f:
            content = f.read()

        self.assertIn("m_pipeline_properties.state.ia.topology == vertex_state.primitive", content)
        self.assertIn("m_pipeline_properties.state.ia.primitiveRestartEnable == static_cast<VkBool32>(vertex_state.restart_index_enabled)", content)
        self.assertIn("m_pipeline_properties.renderpass_key == m_current_renderpass_key", content)

    def test_vk_draw_descriptor_bind_batching(self):
        """Verify VKDraw.cpp gates descriptor binding with reload_state || update_descriptors."""
        cpp_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "RSX", "VK", "VKDraw.cpp")
        with open(cpp_file, "r") as f:
            content = f.read()

        self.assertIn("if (reload_state || update_descriptors)", content)
        self.assertIn("m_current_frame->descriptor_set.bind(*m_current_command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, m_program->pipeline_layout);", content)

    def test_vk_texture_cache_dma_copy_reuse(self):
        """Verify VKTextureCache.cpp reuses s_dma_copy_regions vector with capacity retention."""
        cpp_file = os.path.join(ROOT_DIR, "app", "src", "main", "cpp", "rpcsx", "rpcs3", "Emu", "RSX", "VK", "VKTextureCache.cpp")
        with open(cpp_file, "r") as f:
            content = f.read()

        self.assertIn("static thread_local std::vector<VkBufferCopy> s_dma_copy_regions;", content)
        self.assertIn("s_dma_copy_regions.clear();", content)
        self.assertIn("if (s_dma_copy_regions.capacity() < transfer_height)", content)
        self.assertIn("s_dma_copy_regions.reserve(transfer_height);", content)

if __name__ == "__main__":
    unittest.main()
