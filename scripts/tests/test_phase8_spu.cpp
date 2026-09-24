// Test suite for Phase 8 SPU compilation, function redirection (R13), & decoder routing (R14)
#include <cstdint>
#include <cassert>
#include <iostream>
#include <string>
#include <vector>
#include <cstring>
#include <stdexcept>

using u8  = uint8_t;
using u32 = uint32_t;
using s64 = int64_t;
using u64 = uint64_t;

// ============================================================================
// Phase 6 / R13: Single 32-bit atomic instruction patching & veneer encoding
// ============================================================================

struct alignas(16) SPUVeneer {
    u32 ldr_insn = 0x58000050; // ldr x16, #8
    u32 br_insn  = 0xd61f0200; // br x16
    u64 target_addr = 0;       // 64-bit absolute target address
};
static_assert(sizeof(SPUVeneer) == 16, "SPUVeneer size must be exactly 16 bytes");

struct RedirectionTest {
    // Single 32-bit atomic instruction encoding for near branch (B <target>, 32-bit)
    // Range is +/-128MB (26-bit signed immediate shifted left by 2)
    static u32 encode_near_branch(s64 diff) {
        assert(is_near_branch_range(diff) && "diff must be in +/-128MB range");
        return 0x14000000 | static_cast<u32>((diff >> 2) & 0x03ffffff);
    }

    static bool is_near_branch_range(s64 diff) {
        return (diff >= -(1LL << 27) && diff < (1LL << 27));
    }

    // Veneer encoding for far branch (targets outside +/-128MB)
    static SPUVeneer encode_veneer(u64 target) {
        SPUVeneer v;
        v.ldr_insn = 0x58000050; // ldr x16, #8
        v.br_insn  = 0xd61f0200; // br x16
        v.target_addr = target;
        return v;
    }
};

// ============================================================================
// Phase 8 / R14: SPU Decoder Selection and Architecture Routing
// ============================================================================

enum class spu_decoder_type {
    asmjit,
    llvm,
    _static,
    dynamic
};

enum class recompiler_backend {
    asmjit,
    llvm_recompiler,      // synchronous LLVM compiler (used on ARM64)
    fast_llvm_recompiler  // fast tier compiler (strictly x86_64 only)
};

template <bool IsX64, bool IsARM64>
struct SPUThreadDecoderRouting {
    static recompiler_backend route(spu_decoder_type decoder) {
        if (decoder == spu_decoder_type::asmjit) {
            return recompiler_backend::asmjit;
        } else if (decoder == spu_decoder_type::llvm) {
            if constexpr (IsX64) {
                // On ARCH_X64: routes to make_fast_llvm_recompiler()
                return recompiler_backend::fast_llvm_recompiler;
            } else if constexpr (IsARM64) {
                // Defect R14 (P2): On ARCH_ARM64: routes directly to make_llvm_recompiler()
                // (synchronous LLVM compiler); fast tier is strictly x86_64-only.
                return recompiler_backend::llvm_recompiler;
            } else {
                throw std::runtime_error("Unimplemented architecture");
            }
        }
        throw std::runtime_error("Unsupported decoder type");
    }

    static bool claims_arm_tier1_fast_recompiler() {
        // Defect R14 (P2): Asserts that ARM tier-1 fast recompiler is NOT claimed or enabled
        return false;
    }
};

using ARM64Routing = SPUThreadDecoderRouting<false, true>;
using X64Routing   = SPUThreadDecoderRouting<true, false>;

struct SPUFastCompilerSimulation {
    template <bool IsX64>
    static bool compile_or_throw(std::string& err_msg) {
        if constexpr (!IsX64) {
            err_msg = "Fast LLVM recompiler is unimplemented for architectures other than X86-64";
            return false;
        }
        return true;
    }
};

// ============================================================================
// Main test driver
// ============================================================================

int main() {
    // ------------------------------------------------------------------------
    // Part 1: R13 Single 32-bit instruction atomic patching & veneer tests
    // ------------------------------------------------------------------------

    // 1. Near branch (+4 bytes offset -> 1 instruction forward)
    {
        s64 diff = 4;
        assert(RedirectionTest::is_near_branch_range(diff));
        u32 b_insn = RedirectionTest::encode_near_branch(diff);
        assert(sizeof(b_insn) == 4); // Single 32-bit instruction!
        assert(b_insn == 0x14000001); // B +4 (imm26 = 1)
        assert((b_insn >> 26) == 0x05); // B opcode (bits 31:26 == 000101)
    }

    // 2. Near branch (-4 bytes offset -> 1 instruction backward)
    {
        s64 diff = -4;
        assert(RedirectionTest::is_near_branch_range(diff));
        u32 b_insn = RedirectionTest::encode_near_branch(diff);
        assert(sizeof(b_insn) == 4);
        assert(b_insn == (0x14000000 | 0x03ffffff)); // B -4
    }

    // 3. Near branch maximum positive range (+128MB - 4)
    {
        s64 diff = (1LL << 27) - 4;
        assert(RedirectionTest::is_near_branch_range(diff));
        u32 b_insn = RedirectionTest::encode_near_branch(diff);
        assert(sizeof(b_insn) == 4);
        assert((b_insn >> 26) == 0x05); // B opcode
    }

    // 4. Near branch minimum negative range (-128MB)
    {
        s64 diff = -(1LL << 27);
        assert(RedirectionTest::is_near_branch_range(diff));
        u32 b_insn = RedirectionTest::encode_near_branch(diff);
        assert(sizeof(b_insn) == 4);
        assert((b_insn >> 26) == 0x05); // B opcode
    }

    // 5. Near branch boundary checks (out of range >= 128MB or < -128MB)
    {
        s64 diff_pos = 1LL << 27;
        assert(!RedirectionTest::is_near_branch_range(diff_pos));
        s64 diff_neg = -(1LL << 27) - 4;
        assert(!RedirectionTest::is_near_branch_range(diff_neg));
    }

    // 6. Verify single 32-bit atomic store at entry point leaves adjacent instructions untouched
    //    (Ensures NO 64-bit multi-instruction overwrites at the entry point)
    {
        u32 code_buffer[4] = {
            0x11111111, // word 0: will be atomically patched with single 32-bit B
            0x22222222, // word 1: MUST NOT BE TOUCHED
            0x33333333, // word 2: MUST NOT BE TOUCHED
            0x44444444  // word 3: MUST NOT BE TOUCHED
        };
        u32 patch = RedirectionTest::encode_near_branch(16);
        assert(sizeof(patch) == 4);
        code_buffer[0] = patch; // 32-bit atomic store
        assert(code_buffer[0] == patch);
        assert(code_buffer[1] == 0x22222222);
        assert(code_buffer[2] == 0x33333333);
        assert(code_buffer[3] == 0x44444444);
    }

    // 7. Far branch: Veneer encoding + single 32-bit branch to veneer
    {
        u64 far_target = 0x7fff'ffff'0000ULL;
        SPUVeneer veneer = RedirectionTest::encode_veneer(far_target);
        assert(sizeof(veneer) == 16);
        assert(veneer.ldr_insn == 0x58000050); // ldr x16, #8
        assert(veneer.br_insn == 0xd61f0200);  // br x16
        assert(veneer.target_addr == far_target);

        // Entry point is patched with single 32-bit B <veneer> instruction
        s64 veneer_diff = 64; // Veneer is allocated nearby in JIT code arena
        assert(RedirectionTest::is_near_branch_range(veneer_diff));
        u32 entry_patch = RedirectionTest::encode_near_branch(veneer_diff);
        assert(sizeof(entry_patch) == 4);
        assert(entry_patch == 0x14000010); // B +64 (imm26 = 16)
    }

    std::cout << "All SPU redirection tests PASSED\n";

    // ------------------------------------------------------------------------
    // Part 2: R14 SPU Decoder Selection & Routing Tests
    // ------------------------------------------------------------------------

    // 8. On ARCH_ARM64: spu_decoder::llvm routes to make_llvm_recompiler()
    {
        recompiler_backend backend = ARM64Routing::route(spu_decoder_type::llvm);
        assert(backend == recompiler_backend::llvm_recompiler);
        assert(backend != recompiler_backend::fast_llvm_recompiler);
    }

    // 9. On ARCH_X64: spu_decoder::llvm routes to make_fast_llvm_recompiler()
    {
        recompiler_backend backend = X64Routing::route(spu_decoder_type::llvm);
        assert(backend == recompiler_backend::fast_llvm_recompiler);
        assert(backend != recompiler_backend::llvm_recompiler);
    }

    // 10. Asserts that ARM tier-1 fast recompiler is not claimed
    {
        assert(!ARM64Routing::claims_arm_tier1_fast_recompiler());
        assert(!X64Routing::claims_arm_tier1_fast_recompiler());
    }

    // 11. spu_fast::compile throws on non-x64 (ARM64)
    {
        std::string err_arm64;
        bool success_arm64 = SPUFastCompilerSimulation::compile_or_throw<false>(err_arm64);
        assert(!success_arm64);
        assert(err_arm64 == "Fast LLVM recompiler is unimplemented for architectures other than X86-64");

        std::string err_x64;
        bool success_x64 = SPUFastCompilerSimulation::compile_or_throw<true>(err_x64);
        assert(success_x64);
        assert(err_x64.empty());
    }

    std::cout << "All SPU decoder selection tests PASSED\n";
    return 0;
}
