#!/usr/bin/env python3
"""
Unit tests for Phase 6 of the SambaS3 ARM Backend Optimization Plan:
- Problem 8 (C01): Vulkan readback wait timeout propagation
- Suspected 13 (C02): ARM instruction-cache maintenance during generated-code patching
- Suspected 14 (C03): GPU-label backing buffer retention across pool rollover
"""

import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


class TestPhase6Correctness(unittest.TestCase):
    def test_cpp_unit_test_binary(self):
        cpp_src = REPO_ROOT / "scripts" / "tests" / "test_phase6_sync_icache.cpp"
        bin_path = REPO_ROOT / "scripts" / "tests" / "test_phase6_sync_icache"
        rx_inc = REPO_ROOT / "app" / "src" / "main" / "cpp" / "rpcsx" / "rx" / "include"

        self.assertTrue(cpp_src.exists(), f"Missing {cpp_src}")
        try:
            subprocess.run(
                ["g++", "-O2", "-std=c++20", f"-I{rx_inc}", str(cpp_src), "-o", str(bin_path)],
                check=True,
                capture_output=True,
                text=True,
            )
            res = subprocess.run([str(bin_path)], capture_output=True, text=True, check=True)
            self.assertIn("ALL 8 PHASE 6 CORRECTNESS TESTS PASSED!", res.stdout)
        finally:
            if bin_path.exists():
                bin_path.unlink()

    def test_spu_patch_sites_have_cache_invalidation(self):
        recompiler_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "Cell"
            / "SPUCommonRecompiler.cpp"
        )
        content = recompiler_src.read_text(encoding="utf-8")
        self.assertIn("rx::clean_dcache_invalidate_icache(patch_fn, 36)", content)
        self.assertIn("rx::clean_dcache_invalidate_icache(rip, 16)", content)
        # R09: Ubertrampoline icache invalidation and barrier
        self.assertIn("rx::clean_dcache_invalidate_icache(wxptr, raw - wxptr);", content)
        self.assertIn('asm volatile("dsb ish; isb" ::: "memory");', content)
        # R13: Single 32-bit instruction atomic patching & veneer
        arm_redirect_code = (
            content[content.find("Redirect old function") : content.find("Redirect old function") + 3500]
            .split("#elif defined(ARCH_ARM64)")[1]
            .split("#else")[0]
        )
        self.assertIn("atomic_storage<u32>::release(*reinterpret_cast<u32*>(prog->first), b_insn);", arm_redirect_code)
        self.assertIn("veneer_u32[0] = 0x58000050; // ldr x16, #8", arm_redirect_code)
        self.assertIn("veneer_u32[1] = 0xd61f0200; // br x16", arm_redirect_code)
        self.assertNotIn("atomic_storage<u64>", arm_redirect_code)

    def test_r12_jit_publication_barrier_ordering(self):
        llvm_recompiler_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "Cell"
            / "SPULLVMRecompiler.cpp"
        )
        content = llvm_recompiler_src.read_text(encoding="utf-8")
        # R12: The cache finalization barrier dsb ish; isb must execute BEFORE add_loc->compiled publication
        barrier_str = 'asm volatile("dsb ish; isb" ::: "memory");'
        self.assertIn(barrier_str, content)

        idx_barrier = content.find(barrier_str)
        idx_compiled = content.find("add_loc->compiled.store(fn", idx_barrier)
        if idx_compiled == -1:
            idx_compiled = content.find("add_loc->compiled = fn;", idx_barrier)
        self.assertNotEqual(
            idx_compiled,
            -1,
            "R12 defect: Cache finalization barrier must execute BEFORE add_loc->compiled publication!",
        )

    def test_vulkan_texture_cache_checks_wait_for_event(self):
        vk_cache_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "RSX"
            / "VK"
            / "VKTextureCache.h"
        )
        content = vk_cache_src.read_text(encoding="utf-8")
        self.assertIn("const VkResult status = vk::wait_for_event(dma_fence.get(), GENERAL_WAIT_TIMEOUT);", content)
        self.assertIn("if (status != VK_SUCCESS)", content)
        self.assertIn("synchronized = false;", content)
        self.assertIn("return false;", content)

        # R08: Check that release_dma_resources() is NOT called when status != VK_SUCCESS
        err_block_idx = content.find("if (status != VK_SUCCESS)")
        err_block = content[err_block_idx : err_block_idx + 250]
        self.assertNotIn(
            "release_dma_resources()",
            err_block,
            "R08 defect: imp_flush must not discard readback resources on wait failure",
        )

        # R08: Check texture_cache.h separates failed sections
        tex_cache_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "RSX"
            / "Common"
            / "texture_cache.h"
        )
        tc_content = tex_cache_src.read_text(encoding="utf-8")
        self.assertIn("std::vector<section_storage_type*> successfully_flushed;", tc_content)
        self.assertIn("ranges_to_unprotect.exclude(failed->get_locked_range());", tc_content)

        # R08: Check VKGSRenderTypes.hpp command buffer fence wait
        render_types_src = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "RSX"
            / "VK"
            / "VKGSRenderTypes.hpp"
        )
        rt_content = render_types_src.read_text(encoding="utf-8")
        self.assertIn("if (ret == VK_SUCCESS && is_pending)", rt_content)

    def test_gpu_label_pool_has_shared_storage(self):
        sync_h = (
            REPO_ROOT
            / "app"
            / "src"
            / "main"
            / "cpp"
            / "rpcsx"
            / "rpcs3"
            / "Emu"
            / "RSX"
            / "VK"
            / "vkutils"
            / "sync.h"
        )
        content = sync_h.read_text(encoding="utf-8")
        self.assertIn("std::shared_ptr<label_storage> m_storage", content)
        self.assertIn("std::tuple<std::shared_ptr<label_storage>, VkBuffer, u64, volatile u32*> allocate();", content)


if __name__ == "__main__":
    unittest.main()
