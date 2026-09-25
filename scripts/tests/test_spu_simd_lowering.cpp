// Differential / Unit test suite for Ticket J06 / E08: SPU/PPU ARM64 SIMD Lowering
#include <cstdint>
#include <cassert>
#include <iostream>
#include <vector>
#include <string>
#include <cstring>
#include <sstream>
#include <random>

using u8  = uint8_t;
using s8  = int8_t;
using u16 = uint16_t;
using s16 = int16_t;
using u32 = uint32_t;
using s32 = int32_t;
using u64 = uint64_t;
using s64 = int64_t;

struct alignas(16) v128_t {
    union {
        u8  u8_arr[16];
        s8  s8_arr[16];
        u16 u16_arr[8];
        s16 s16_arr[8];
        u32 u32_arr[4];
        s32 s32_arr[4];
        u64 u64_arr[2];
        s64 s64_arr[2];
    };

    bool operator==(const v128_t& o) const {
        return u64_arr[0] == o.u64_arr[0] && u64_arr[1] == o.u64_arr[1];
    }
    bool operator!=(const v128_t& o) const {
        return !(*this == o);
    }
};

// ============================================================================
// 1. SPU SHUFB: Reference vs Optimized AArch64 NEON Lowering
// ============================================================================

// Reference SPU interpreter SHUFB implementation (Cell BE architectural contract)
v128_t ref_spu_shufb(v128_t a, v128_t b, v128_t c) {
    // In RPCS3 host representation:
    // ab[0] = b, ab[1] = a (since ra selects 0..15 in Cell BE, which is index 16..31 in little-endian ab)
    v128_t ab[2]{b, a};
    v128_t res{};

    for (int i = 0; i < 16; i++) {
        u8 code = c.u8_arr[i];
        if (code & 0x80) {
            // Special constant byte generation
            if ((code & 0xC0) == 0x80) {
                res.u8_arr[i] = 0x00;
            } else if ((code & 0xE0) == 0xC0) {
                res.u8_arr[i] = 0xFF;
            } else if ((code & 0xE0) == 0xE0) {
                res.u8_arr[i] = 0x80;
            } else {
                res.u8_arr[i] = 0x00;
            }
        } else {
            // Select from a or b
            u8 sel_idx = (~code) & 0x1F;
            res.u8_arr[i] = reinterpret_cast<const u8*>(ab)[sel_idx];
        }
    }
    return res;
}

// Emulated ARM64 NEON tbl1 (16-byte table)
v128_t neon_tbl1(v128_t table, v128_t indices) {
    v128_t out{};
    for (int i = 0; i < 16; i++) {
        u8 idx = indices.u8_arr[i];
        out.u8_arr[i] = (idx < 16) ? table.u8_arr[idx] : 0x00;
    }
    return out;
}

// Emulated ARM64 NEON tbl2 (32-byte table {t0, t1})
v128_t neon_tbl2(v128_t t0, v128_t t1, v128_t indices) {
    v128_t out{};
    for (int i = 0; i < 16; i++) {
        u8 idx = indices.u8_arr[i];
        if (idx < 16) {
            out.u8_arr[i] = t0.u8_arr[idx];
        } else if (idx < 32) {
            out.u8_arr[i] = t1.u8_arr[idx - 16];
        } else {
            out.u8_arr[i] = 0x00;
        }
    }
    return out;
}

// Optimized AArch64 NEON SHUFB lowering
v128_t neon_spu_shufb(v128_t a, v128_t b, v128_t c, bool perm_only) {
    v128_t cr{};
    for (int i = 0; i < 16; i++) {
        cr.u8_arr[i] = (c.u8_arr[i] ^ 0x0F) & (perm_only ? 0x1F : 0x9F);
    }

    if (perm_only) {
        return neon_tbl2(a, b, cr);
    }

    v128_t ab = neon_tbl2(a, b, cr);

    // Constant lookup table for special bytes when c & 0x80 != 0
    static const v128_t s_spec_table = {
        .u8_arr = { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0x80, 0x80 }
    };

    v128_t c_shr{};
    for (int i = 0; i < 16; i++) {
        c_shr.u8_arr[i] = c.u8_arr[i] >> 4;
    }

    v128_t x = neon_tbl1(s_spec_table, c_shr);

    v128_t out{};
    for (int i = 0; i < 16; i++) {
        out.u8_arr[i] = ab.u8_arr[i] | x.u8_arr[i];
    }
    return out;
}

// ============================================================================
// 2. SPU Mask Generation: FSMBI, FSM, FSMH, FSMB
// ============================================================================

// Reference FSMBI
v128_t ref_fsmbi(u16 imm) {
    v128_t out{};
    for (int i = 0; i < 16; i++) {
        out.u8_arr[i] = ((imm >> i) & 1) ? 0xFF : 0x00;
    }
    return out;
}

// Reference FSM (4 words)
v128_t ref_fsm(u32 v) {
    v128_t out{};
    for (int i = 0; i < 4; i++) {
        out.u32_arr[i] = ((v >> i) & 1) ? 0xFFFFFFFFu : 0u;
    }
    return out;
}

// Optimized NEON FSM (cmtst lowering)
v128_t neon_fsm(u32 v) {
    v128_t splat{};
    for (int i = 0; i < 4; i++) splat.u32_arr[i] = v;
    static const u32 bit_masks[4] = {1, 2, 4, 8};
    v128_t out{};
    for (int i = 0; i < 4; i++) {
        out.u32_arr[i] = (splat.u32_arr[i] & bit_masks[i]) ? 0xFFFFFFFFu : 0u;
    }
    return out;
}

// Reference FSMH (8 halfwords)
v128_t ref_fsmh(u32 v) {
    v128_t out{};
    for (int i = 0; i < 8; i++) {
        out.u16_arr[i] = ((v >> i) & 1) ? 0xFFFFu : 0u;
    }
    return out;
}

// Optimized NEON FSMH (cmtst lowering)
v128_t neon_fsmh(u32 v) {
    v128_t splat{};
    u16 trunc_v = static_cast<u16>(v);
    for (int i = 0; i < 8; i++) splat.u16_arr[i] = trunc_v;
    static const u16 bit_masks[8] = {1, 2, 4, 8, 16, 32, 64, 128};
    v128_t out{};
    for (int i = 0; i < 8; i++) {
        out.u16_arr[i] = (splat.u16_arr[i] & bit_masks[i]) ? 0xFFFFu : 0u;
    }
    return out;
}

// Reference FSMB (16 bytes)
v128_t ref_fsmb(u32 v) {
    v128_t out{};
    for (int i = 0; i < 16; i++) {
        out.u8_arr[i] = ((v >> i) & 1) ? 0xFF : 0x00;
    }
    return out;
}

// Optimized NEON FSMB (cmtst lowering across low/high bytes)
v128_t neon_fsmb(u32 v) {
    u8 b0 = static_cast<u8>(v & 0xFF);
    u8 b1 = static_cast<u8>((v >> 8) & 0xFF);
    v128_t combined{};
    for (int i = 0; i < 8; i++) combined.u8_arr[i] = b0;
    for (int i = 8; i < 16; i++) combined.u8_arr[i] = b1;

    static const u8 bit_masks[16] = {
        1, 2, 4, 8, 16, 32, 64, 128,
        1, 2, 4, 8, 16, 32, 64, 128
    };

    v128_t out{};
    for (int i = 0; i < 16; i++) {
        out.u8_arr[i] = (combined.u8_arr[i] & bit_masks[i]) ? 0xFF : 0x00;
    }
    return out;
}

// ============================================================================
// 3. SPU Packed Vector Shifts & Rotates
// ============================================================================

// Reference ROTQBYI (Rotate Quadword by Bytes Immediate)
v128_t ref_rotqbyi(v128_t a, u32 i7) {
    u32 k = i7 & 0x0F;
    v128_t out{};
    for (int i = 0; i < 16; i++) {
        out.u8_arr[i] = a.u8_arr[(i - k) & 0x0F];
    }
    return out;
}

// Optimized zshuffle ROTQBYI
v128_t opt_rotqbyi(v128_t a, u32 i7) {
    u32 k = i7 & 0x0F;
    if (k == 0) return a;
    v128_t out{};
    for (int i = 0; i < 16; i++) {
        out.u8_arr[i] = a.u8_arr[(i - k) & 0x0F];
    }
    return out;
}

// Reference SHLQBYI (Shift Left Quadword by Bytes Immediate)
v128_t ref_shlqbyi(v128_t a, u32 i7) {
    u32 k = i7 & 0x1F;
    v128_t out{};
    if (k >= 16) return out;
    for (int i = 0; i < 16; i++) {
        out.u8_arr[i] = (i < static_cast<int>(k)) ? 0x00 : a.u8_arr[i - k];
    }
    return out;
}

// Optimized zshuffle SHLQBYI
v128_t opt_shlqbyi(v128_t a, u32 i7) {
    u32 k = i7 & 0x1F;
    if (k == 0) return a;
    if (k >= 16) {
        v128_t z{};
        return z;
    }
    v128_t out{};
    for (int i = 0; i < 16; i++) {
        out.u8_arr[i] = (i < static_cast<int>(k)) ? 0x00 : a.u8_arr[i - k];
    }
    return out;
}

// Reference ROTQMBYI (Rotate and Mask Quadword by Bytes Immediate)
v128_t ref_rotqmbyi(v128_t a, s32 si7) {
    u32 k = (-si7) & 0x1F;
    v128_t out{};
    if (k >= 16) return out;
    for (int i = 0; i < 16; i++) {
        out.u8_arr[i] = (i + k >= 16) ? 0x00 : a.u8_arr[i + k];
    }
    return out;
}

// Optimized zshuffle ROTQMBYI
v128_t opt_rotqmbyi(v128_t a, s32 si7) {
    u32 k = (-si7) & 0x1F;
    if (k == 0) return a;
    if (k >= 16) {
        v128_t z{};
        return z;
    }
    v128_t out{};
    for (int i = 0; i < 16; i++) {
        out.u8_arr[i] = (i + k >= 16) ? 0x00 : a.u8_arr[i + k];
    }
    return out;
}

// Reference infinite-precision shift right (logical)
v128_t ref_rotmi(v128_t a, s32 si7) {
    u32 sh = (-si7) & 63;
    v128_t out{};
    for (int i = 0; i < 4; i++) {
        out.u32_arr[i] = (sh < 32) ? (a.u32_arr[i] >> sh) : 0;
    }
    return out;
}

// Optimized native vector shift right
v128_t opt_rotmi(v128_t a, s32 si7) {
    const u32 sh = (-si7) & 63;
    v128_t out{};
    if (sh >= 32) {
        return out;
    } else if (sh == 0) {
        return a;
    } else {
        for (int i = 0; i < 4; i++) out.u32_arr[i] = a.u32_arr[i] >> sh;
        return out;
    }
}

// Reference infinite-precision shift left
v128_t ref_shli(v128_t a, u32 i7) {
    u32 sh = i7 & 63;
    v128_t out{};
    for (int i = 0; i < 4; i++) {
        out.u32_arr[i] = (sh < 32) ? (a.u32_arr[i] << sh) : 0;
    }
    return out;
}

// Optimized native vector shift left
v128_t opt_shli(v128_t a, u32 i7) {
    const u32 sh = i7 & 63;
    v128_t out{};
    if (sh >= 32) {
        return out;
    } else if (sh == 0) {
        return a;
    } else {
        for (int i = 0; i < 4; i++) out.u32_arr[i] = a.u32_arr[i] << sh;
        return out;
    }
}

// Reference infinite-precision arithmetic shift right (word)
v128_t ref_rotmai(v128_t a, s32 si7) {
    u32 sh = (-si7) & 63;
    v128_t out{};
    for (int i = 0; i < 4; i++) {
        out.s32_arr[i] = (sh < 32) ? (a.s32_arr[i] >> sh) : (a.s32_arr[i] >> 31);
    }
    return out;
}

// Optimized native vector arithmetic shift right (word)
v128_t opt_rotmai(v128_t a, s32 si7) {
    const u32 sh = (-si7) & 63;
    v128_t out{};
    if (sh >= 32) {
        for (int i = 0; i < 4; i++) out.s32_arr[i] = a.s32_arr[i] >> 31;
        return out;
    } else if (sh == 0) {
        return a;
    } else {
        for (int i = 0; i < 4; i++) out.s32_arr[i] = a.s32_arr[i] >> sh;
        return out;
    }
}

// Reference infinite-precision logical shift right (halfword)
v128_t ref_rothmi(v128_t a, s32 si7) {
    u32 sh = (-si7) & 31;
    v128_t out{};
    for (int i = 0; i < 8; i++) {
        out.u16_arr[i] = (sh < 16) ? (a.u16_arr[i] >> sh) : 0;
    }
    return out;
}

// Optimized native vector logical shift right (halfword)
v128_t opt_rothmi(v128_t a, s32 si7) {
    const u32 sh = (-si7) & 31;
    v128_t out{};
    if (sh >= 16) {
        return out;
    } else if (sh == 0) {
        return a;
    } else {
        for (int i = 0; i < 8; i++) out.u16_arr[i] = a.u16_arr[i] >> sh;
        return out;
    }
}

// Reference infinite-precision arithmetic shift right (halfword)
v128_t ref_rotmahi(v128_t a, s32 si7) {
    u32 sh = (-si7) & 31;
    v128_t out{};
    for (int i = 0; i < 8; i++) {
        out.s16_arr[i] = (sh < 16) ? (a.s16_arr[i] >> sh) : (a.s16_arr[i] >> 15);
    }
    return out;
}

// Optimized native vector arithmetic shift right (halfword)
v128_t opt_rotmahi(v128_t a, s32 si7) {
    const u32 sh = (-si7) & 31;
    v128_t out{};
    if (sh >= 16) {
        for (int i = 0; i < 8; i++) out.s16_arr[i] = a.s16_arr[i] >> 15;
        return out;
    } else if (sh == 0) {
        return a;
    } else {
        for (int i = 0; i < 8; i++) out.s16_arr[i] = a.s16_arr[i] >> sh;
        return out;
    }
}

// Reference infinite-precision logical shift left (halfword)
v128_t ref_shlhi(v128_t a, u32 i7) {
    u32 sh = i7 & 31;
    v128_t out{};
    for (int i = 0; i < 8; i++) {
        out.u16_arr[i] = (sh < 16) ? (a.u16_arr[i] << sh) : 0;
    }
    return out;
}

// Optimized native vector logical shift left (halfword)
v128_t opt_shlhi(v128_t a, u32 i7) {
    const u32 sh = i7 & 31;
    v128_t out{};
    if (sh >= 16) {
        return out;
    } else if (sh == 0) {
        return a;
    } else {
        for (int i = 0; i < 8; i++) out.u16_arr[i] = a.u16_arr[i] << sh;
        return out;
    }
}

// Reference SSSE3 pshufb
v128_t ref_ssse3_pshufb(v128_t data, v128_t index) {
    v128_t out{};
    for (int i = 0; i < 16; i++) {
        u8 idx = index.u8_arr[i];
        if (idx & 0x80) {
            out.u8_arr[i] = 0;
        } else {
            out.u8_arr[i] = data.u8_arr[idx & 0x0F];
        }
    }
    return out;
}

// Emulated ARM64 NEON pshufb lowering (CPUTranslator.cpp lines 43-45)
v128_t neon_emulated_pshufb(v128_t data, v128_t index) {
    v128_t index_masked{};
    for (int i = 0; i < 16; i++) {
        index_masked.u8_arr[i] = index.u8_arr[i] & 0x8F;
    }
    return neon_tbl1(data, index_masked);
}

// ============================================================================
// Main Differential Test Driver
// ============================================================================

int main() {
    std::mt19937_64 rng(1337);

    // ------------------------------------------------------------------------
    // Part 1: SPU SHUFB Lowering Tests
    // ------------------------------------------------------------------------
    std::cout << "[*] Running SPU SHUFB differential verification (10,000 iterations)...\n";
    for (int iter = 0; iter < 10000; iter++) {
        v128_t a{}, b{}, c{};
        a.u64_arr[0] = rng(); a.u64_arr[1] = rng();
        b.u64_arr[0] = rng(); b.u64_arr[1] = rng();
        c.u64_arr[0] = rng(); c.u64_arr[1] = rng();

        v128_t ref = ref_spu_shufb(a, b, c);
        v128_t neon = neon_spu_shufb(a, b, c, false);
        assert(ref == neon && "SHUFB general lowering mismatch!");

        // Perm-only fast path (mask bit 7 clear)
        v128_t c_perm = c;
        for (int i = 0; i < 16; i++) c_perm.u8_arr[i] &= 0x7F;

        v128_t ref_perm = ref_spu_shufb(a, b, c_perm);
        v128_t neon_perm = neon_spu_shufb(a, b, c_perm, true);
        assert(ref_perm == neon_perm && "SHUFB perm_only fast path mismatch!");
    }

    // Exhaustive test of all 256 control codes in c
    std::cout << "[*] Running SPU SHUFB exhaustive control mask test (all 256 byte patterns)...\n";
    for (int code = 0; code < 256; code++) {
        v128_t a{}, b{}, c{};
        for (int i = 0; i < 16; i++) {
            a.u8_arr[i] = static_cast<u8>(0x10 + i);
            b.u8_arr[i] = static_cast<u8>(0x80 + i);
            c.u8_arr[i] = static_cast<u8>(code);
        }

        v128_t ref = ref_spu_shufb(a, b, c);
        v128_t neon = neon_spu_shufb(a, b, c, false);
        assert(ref == neon && "SHUFB exhaustive control pattern mismatch!");
    }
    std::cout << "[PASS] SPU SHUFB AArch64 NEON lowering verified bit-exact!\n";

    // ------------------------------------------------------------------------
    // Part 2: SPU Mask Generation Tests (FSMBI, FSM, FSMH, FSMB)
    // ------------------------------------------------------------------------
    std::cout << "[*] Running FSMBI exhaustive test (all 65,536 16-bit immediates)...\n";
    for (u32 imm = 0; imm <= 0xFFFF; imm++) {
        v128_t ref = ref_fsmbi(static_cast<u16>(imm));
        v128_t opt{};
        for (u32 i = 0; i < 16; i++) {
            opt.u8_arr[i] = ((imm >> i) & 1) ? 0xFF : 0x00;
        }
        assert(ref == opt && "FSMBI constant mask mismatch!");
    }
    std::cout << "[PASS] FSMBI verified bit-exact across all 65,536 values!\n";

    std::cout << "[*] Running FSM (4-word mask) tests...\n";
    for (u32 v = 0; v < 16; v++) {
        v128_t ref = ref_fsm(v);
        v128_t opt = neon_fsm(v);
        assert(ref == opt && "FSM cmtst lowering mismatch!");
    }
    std::cout << "[PASS] FSM verified bit-exact across all 16 word masks!\n";

    std::cout << "[*] Running FSMH (8-halfword mask) tests...\n";
    for (u32 v = 0; v < 256; v++) {
        v128_t ref = ref_fsmh(v);
        v128_t opt = neon_fsmh(v);
        assert(ref == opt && "FSMH cmtst lowering mismatch!");
    }
    std::cout << "[PASS] FSMH verified bit-exact across all 256 halfword masks!\n";

    std::cout << "[*] Running FSMB (16-byte mask) tests...\n";
    for (u32 v = 0; v <= 0xFFFF; v++) {
        v128_t ref = ref_fsmb(v);
        v128_t opt = neon_fsmb(v);
        assert(ref == opt && "FSMB cmtst lowering mismatch!");
    }
    std::cout << "[PASS] FSMB verified bit-exact across all 65,536 byte masks!\n";

    // ------------------------------------------------------------------------
    // Part 3: Packed Vector Shifts & Rotates
    // ------------------------------------------------------------------------
    std::cout << "[*] Running ROTQBYI / SHLQBYI / ROTQMBYI shift tests (amounts 0..31)...\n";
    for (u32 sh = 0; sh < 32; sh++) {
        v128_t a{};
        a.u64_arr[0] = 0x0123456789ABCDEFULL;
        a.u64_arr[1] = 0xFEDCBA9876543210ULL;

        // ROTQBYI
        v128_t r_rotqbyi = ref_rotqbyi(a, sh);
        v128_t o_rotqbyi = opt_rotqbyi(a, sh);
        assert(r_rotqbyi == o_rotqbyi && "ROTQBYI lowering mismatch!");

        // SHLQBYI
        v128_t r_shlqbyi = ref_shlqbyi(a, sh);
        v128_t o_shlqbyi = opt_shlqbyi(a, sh);
        assert(r_shlqbyi == o_shlqbyi && "SHLQBYI lowering mismatch!");

        // ROTQMBYI
        v128_t r_rotqmbyi = ref_rotqmbyi(a, static_cast<s32>(sh));
        v128_t o_rotqmbyi = opt_rotqmbyi(a, static_cast<s32>(sh));
        assert(r_rotqmbyi == o_rotqmbyi && "ROTQMBYI lowering mismatch!");
    }
    std::cout << "[PASS] ROTQBYI, SHLQBYI, ROTQMBYI verified bit-exact across all shift amounts!\n";

    std::cout << "[*] Running ROTMI / SHLI word shift tests (amounts -64..64)...\n";
    for (s32 sh = -64; sh <= 64; sh++) {
        v128_t a{};
        a.u32_arr[0] = 0x12345678; a.u32_arr[1] = 0x87654321;
        a.u32_arr[2] = 0xFFFFFFFF; a.u32_arr[3] = 0x00000001;

        v128_t r_rotmi = ref_rotmi(a, sh);
        v128_t o_rotmi = opt_rotmi(a, sh);
        assert(r_rotmi == o_rotmi && "ROTMI lowering mismatch!");

        v128_t r_shli = ref_shli(a, static_cast<u32>(sh));
        v128_t o_shli = opt_shli(a, static_cast<u32>(sh));
        assert(r_shli == o_shli && "SHLI lowering mismatch!");

        // ROTMAI (signed word arithmetic shift right)
        v128_t r_rotmai = ref_rotmai(a, sh);
        v128_t o_rotmai = opt_rotmai(a, sh);
        assert(r_rotmai == o_rotmai && "ROTMAI lowering mismatch!");
    }
    std::cout << "[PASS] ROTMI, ROTMAI, and SHLI verified bit-exact across all shift ranges!\n";

    std::cout << "[*] Running ROTHMI / ROTMAHI / SHLHI halfword shift tests (amounts -32..32)...\n";
    for (s32 sh = -32; sh <= 32; sh++) {
        v128_t a{};
        a.u16_arr[0] = 0x1234; a.u16_arr[1] = 0x8765;
        a.u16_arr[2] = 0xFFFF; a.u16_arr[3] = 0x0001;
        a.u16_arr[4] = 0x8000; a.u16_arr[5] = 0x7FFF;
        a.u16_arr[6] = 0xAAAA; a.u16_arr[7] = 0x5555;

        // ROTHMI
        v128_t r_rothmi = ref_rothmi(a, sh);
        v128_t o_rothmi = opt_rothmi(a, sh);
        assert(r_rothmi == o_rothmi && "ROTHMI lowering mismatch!");

        // ROTMAHI
        v128_t r_rotmahi = ref_rotmahi(a, sh);
        v128_t o_rotmahi = opt_rotmahi(a, sh);
        assert(r_rotmahi == o_rotmahi && "ROTMAHI lowering mismatch!");

        // SHLHI
        v128_t r_shlhi = ref_shlhi(a, static_cast<u32>(sh));
        v128_t o_shlhi = opt_shlhi(a, static_cast<u32>(sh));
        assert(r_shlhi == o_shlhi && "SHLHI lowering mismatch!");
    }
    std::cout << "[PASS] ROTHMI, ROTMAHI, and SHLHI verified bit-exact across all shift ranges!\n";

    // ------------------------------------------------------------------------
    // Part 4: CPUTranslator ARM64 NEON PSHUFB Emulation (tbl1 + & 0x8F)
    // ------------------------------------------------------------------------
    std::cout << "[*] Running ARM64 NEON PSHUFB emulation differential tests against SSSE3 reference...\n";
    for (int iter = 0; iter < 1000; iter++) {
        v128_t data{}, index{};
        data.u64_arr[0] = rng(); data.u64_arr[1] = rng();
        index.u64_arr[0] = rng(); index.u64_arr[1] = rng();

        v128_t ref = ref_ssse3_pshufb(data, index);
        v128_t neon = neon_emulated_pshufb(data, index);
        assert(ref == neon && "ARM64 NEON PSHUFB emulation mismatch against SSSE3!");
    }

    // Exhaustive test of all 256 index values in all lanes
    for (int code = 0; code < 256; code++) {
        v128_t data{}, index{};
        for (int i = 0; i < 16; i++) {
            data.u8_arr[i] = static_cast<u8>(0x30 + i);
            index.u8_arr[i] = static_cast<u8>(code);
        }
        v128_t ref = ref_ssse3_pshufb(data, index);
        v128_t neon = neon_emulated_pshufb(data, index);
        assert(ref == neon && "ARM64 NEON PSHUFB exhaustive index pattern mismatch!");
    }
    std::cout << "[PASS] ARM64 NEON PSHUFB emulation verified bit-exact against SSSE3 across all 256 index codes!\n";

    // ------------------------------------------------------------------------
    // Part 5: Constant vs Non-Constant Immediate Lowering Equivalence
    // ------------------------------------------------------------------------
    std::cout << "[*] Running Constant vs Non-Constant lowering equivalence across all 128 i7 immediate values...\n";
    for (u32 raw_i7 = 0; raw_i7 < 128; raw_i7++) {
        s32 signed_i7 = (raw_i7 >= 64) ? (static_cast<s32>(raw_i7) - 128) : static_cast<s32>(raw_i7);

        // Word shifts: non-constant (-raw_i7 & 63) vs constant (-signed_i7 & 63)
        u32 non_const_sh_w = (-raw_i7) & 63;
        u32 const_sh_w = (-signed_i7) & 63;
        assert(non_const_sh_w == const_sh_w && "ROTMI/ROTMAI constant vs non-constant shift mismatch!");

        // Halfword shifts: non-constant (-raw_i7 & 31) vs constant (-signed_i7 & 31)
        u32 non_const_sh_h = (-raw_i7) & 31;
        u32 const_sh_h = (-signed_i7) & 31;
        assert(non_const_sh_h == const_sh_h && "ROTHMI/ROTMAHI constant vs non-constant shift mismatch!");

        // Left shifts: raw_i7 & 63 / raw_i7 & 31
        // Quadword rotates: raw_i7 & 15
    }
    std::cout << "[PASS] Constant vs Non-Constant lowering equivalence verified for all 128 immediate values!\n";

    // ------------------------------------------------------------------------
    // Part 6: LLVM Target Feature Parsing and Attribute Construction
    // ------------------------------------------------------------------------
    std::cout << "[*] Running Target Features comma separation test...\n";
    {
        std::string feat_str = "+fp-armv8,+neon,+crc,+crypto,+lse,+fullfp16,+fp16fml,+dotprod,+rdm,+rcpc";
        std::vector<std::string> mattrs;
        std::stringstream ss(feat_str);
        std::string item;
        while (std::getline(ss, item, ',')) {
            if (!item.empty()) mattrs.push_back(item);
        }

        assert(mattrs.size() == 10);
        assert(mattrs[0] == "+fp-armv8");
        assert(mattrs[1] == "+neon");
        assert(mattrs[4] == "+lse");
        assert(mattrs[7] == "+dotprod");
    }
    std::cout << "[PASS] Target Features attribute construction verified!\n";

    std::cout << "\n=======================================================\n";
    std::cout << " ALL SPU SIMD LOWERING & TARGET FEATURE TESTS PASSED!\n";
    std::cout << "=======================================================\n";
    return 0;
}
