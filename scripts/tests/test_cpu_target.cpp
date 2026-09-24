#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <string>
#include <algorithm>
#include <cassert>

using u32 = uint32_t;
using u64 = uint64_t;
constexpr u64 umax = ~0ull;

enum aarch64_features : u32
{
	FEAT_BASELINE = 1 << 0, // ARMv8.0-A baseline: FP, NEON, CRC32, AES, SHA2
	FEAT_LSE      = 1 << 1, // Atomics (v8.1-A)
	FEAT_FP16     = 1 << 2, // Half-precision FP (v8.2-A)
	FEAT_DOTPROD  = 1 << 3, // Dot Product (v8.2-A)
	FEAT_RDM      = 1 << 4, // Rounding Double Multiply Accumulate (v8.1-A)
	FEAT_RCPC     = 1 << 5, // RCpc (v8.2-A)
	FEAT_BF16     = 1 << 6, // BFloat16
	FEAT_I8MM     = 1 << 7, // Int8 Matrix Mul
	FEAT_SVE      = 1 << 8, // SVE
	FEAT_SVE2     = 1 << 9, // SVE2
};

enum aarch64_tier : u32
{
	TIER_ARMV8_0 = 0,
	TIER_ARMV8_2 = 1,
	TIER_ARMV8_4 = 2,
	TIER_ARMV9_0 = 3,
	TIER_ARMV9_2 = 4,
};

constexpr u32 FEAT_V8_2 = FEAT_BASELINE | FEAT_LSE | FEAT_FP16 | FEAT_DOTPROD | FEAT_RDM | FEAT_RCPC;
constexpr u32 FEAT_V8_4 = FEAT_V8_2 | FEAT_BF16 | FEAT_I8MM;
constexpr u32 FEAT_V9_0 = FEAT_V8_4 | FEAT_SVE | FEAT_SVE2;
constexpr u32 FEAT_V9_2 = FEAT_V9_0;

struct cpu_entry_t
{
	u32 vendor;
	u32 part;
	const char* arch;
	const char* family;
	const char* name;
	aarch64_tier tier;
	u32 features;
	u32 rank;
};

static const cpu_entry_t s_test_cpu_list[] =
{
	{0x41, 0xd02, "armv8-a", "", "cortex-a34", TIER_ARMV8_0, FEAT_BASELINE, 10},
	{0x41, 0xd03, "armv8-a+crc+simd", "", "Cortex-A53", TIER_ARMV8_0, FEAT_BASELINE, 12},
	{0x41, 0xd05, "armv8.2-a+fp16+dotprod", "", "Cortex-A55", TIER_ARMV8_2, FEAT_V8_2, 20},
	{0x41, 0xd09, "armv8-a+crc+simd", "", "Cortex-A73", TIER_ARMV8_0, FEAT_BASELINE, 16},
	{0x41, 0xd0b, "armv8.2-a+fp16+dotprod", "", "Cortex-A76", TIER_ARMV8_2, FEAT_V8_2, 23},
	{0x41, 0xd41, "armv8.2-a+fp16+dotprod", "", "Cortex-A78", TIER_ARMV8_2, FEAT_V8_2, 25},
	{0x41, 0xd44, "armv8.2-a+fp16+dotprod", "", "Cortex-X1", TIER_ARMV8_2, FEAT_V8_2, 28},
	{0x41, 0xd46, "armv9-a+fp16+bf16+i8mm", "", "cortex-a510", TIER_ARMV9_0, FEAT_V9_0, 40},
	{0x41, 0xd47, "armv9-a+fp16+bf16+i8mm", "", "Cortex-A710", TIER_ARMV9_0, FEAT_V9_0, 42},
	{0x41, 0xd4d, "armv9.2-a", "", "Cortex-A715", TIER_ARMV9_2, FEAT_V9_2, 43},
	{0x41, 0xd4e, "armv9-a+fp16+bf16+i8mm", "", "Cortex-X3", TIER_ARMV9_0, FEAT_V9_0, 48},
};

static const cpu_entry_t* find_cpu_part(u64 vendor, u64 part)
{
	for (const auto& cpu : s_test_cpu_list)
	{
		if (cpu.vendor == vendor && cpu.part == part)
		{
			return &cpu;
		}
	}
	return nullptr;
}

// Logic mirroring AArch64Common::get_cpu_name with testable midr/affinity injection
std::string evaluate_cpu_name(u64 allowed_mask, const std::vector<u64>& midrs)
{
	if (allowed_mask == 0)
	{
		return "cortex-a34";
	}

	std::vector<const cpu_entry_t*> allowed_cores;
	u32 intersected_features = ~0u;
	aarch64_tier min_tier = TIER_ARMV9_2;
	bool saw_unknown_core = false;

	for (size_t i = 0; i < midrs.size(); ++i)
	{
		if (!(allowed_mask & (1ull << i)))
		{
			continue;
		}

		const auto midr = midrs[i];
		if (midr == umax || midr == 0)
		{
			// Skip unknown/unreadable MIDR, do NOT break
			continue;
		}

		const auto implementer_id = (midr >> 24) & 0xff;
		const auto part_id = (midr >> 4) & 0xfff;

		const auto part_info = find_cpu_part(implementer_id, part_id);
		if (!part_info)
		{
			saw_unknown_core = true;
			continue;
		}

		allowed_cores.push_back(part_info);
		intersected_features &= part_info->features;
		if (part_info->tier < min_tier)
		{
			min_tier = part_info->tier;
		}
	}

	if (allowed_cores.empty() || saw_unknown_core)
	{
		return "cortex-a34";
	}

	bool all_identical = true;
	for (size_t i = 1; i < allowed_cores.size(); ++i)
	{
		if (allowed_cores[i]->part != allowed_cores[0]->part ||
		    allowed_cores[i]->vendor != allowed_cores[0]->vendor)
		{
			all_identical = false;
			break;
		}
	}

	if (all_identical)
	{
		std::string name = allowed_cores[0]->name;
		std::transform(name.begin(), name.end(), name.begin(), ::tolower);
		return name;
	}

	// Heterogeneous: derive safe common denominator (lowest rank / little core)
	const cpu_entry_t* lowest_core = allowed_cores[0];
	for (size_t i = 1; i < allowed_cores.size(); ++i)
	{
		if (allowed_cores[i]->rank < lowest_core->rank)
		{
			lowest_core = allowed_cores[i];
		}
	}

	if ((lowest_core->features & ~intersected_features) != 0)
	{
		if ((intersected_features & (FEAT_LSE | FEAT_FP16 | FEAT_DOTPROD)) == (FEAT_LSE | FEAT_FP16 | FEAT_DOTPROD))
		{
			return "cortex-a55";
		}
		return "cortex-a34";
	}

	std::string name = lowest_core->name;
	std::transform(name.begin(), name.end(), name.begin(), ::tolower);
	return name;
}

// Emulate CPUTranslator feature handling
struct translator_features
{
	bool m_use_avx = false;
	bool m_use_fma = false;
	bool m_use_avx512 = false;
	u32 vector_stride = 16;
};

translator_features evaluate_translator_features(const std::string& cpu, bool is_aarch64)
{
	translator_features tf;
	if (is_aarch64)
	{
		tf.m_use_fma = true;
		tf.m_use_avx = false;
		tf.m_use_avx512 = false;
	}
	else
	{
		if (cpu == "haswell" || cpu == "skylake")
		{
			tf.m_use_fma = true;
			tf.m_use_avx = true;
		}
	}

	if (tf.m_use_avx512)
		tf.vector_stride = 64;
	else if (tf.m_use_avx)
		tf.vector_stride = 32;
	else
		tf.vector_stride = 16;

	return tf;
}

int main()
{
	// Test 1: Empty allowed mask falls back to cortex-a34
	{
		std::vector<u64> midrs = {0x410FD050, 0x410FD050, 0x410FD410, 0x410FD410};
		assert(evaluate_cpu_name(0x0, midrs) == "cortex-a34");
		printf("PASS: Test 1 - Empty allowed mask fallback\n");
	}

	// Test 2: Unreadable MIDR (umax) on core 0 skips to next core without breaking
	{
		std::vector<u64> midrs = {umax, 0x410FD050, 0x410FD050, 0x410FD050};
		assert(evaluate_cpu_name(0xF, midrs) == "cortex-a55");
		printf("PASS: Test 2 - Unreadable MIDR skips without breaking\n");
	}

	// Test 3: All cores unreadable falls back to cortex-a34
	{
		std::vector<u64> midrs = {umax, umax, umax, umax};
		assert(evaluate_cpu_name(0xF, midrs) == "cortex-a34");
		printf("PASS: Test 3 - All cores unreadable fallback\n");
	}

	// Test 4: Unknown core present triggers safe baseline fallback
	{
		std::vector<u64> midrs = {0x410FD410, 0x410FDEAD};
		assert(evaluate_cpu_name(0x3, midrs) == "cortex-a34");
		printf("PASS: Test 4 - Unknown core baseline fallback\n");
	}

	// Test 5: Heterogeneous big.LITTLE (A55 + A78) with all allowed cores -> selects A55 (safe common denominator)
	{
		// 4x Cortex-A55 (0xd05) + 4x Cortex-A78 (0xd41)
		std::vector<u64> midrs = {
			0x410FD050, 0x410FD050, 0x410FD050, 0x410FD050,
			0x410FD410, 0x410FD410, 0x410FD410, 0x410FD410
		};
		assert(evaluate_cpu_name(0xFF, midrs) == "cortex-a55");
		printf("PASS: Test 5 - Heterogeneous A55+A78 selects cortex-a55\n");
	}

	// Test 6: Restricted affinity to Big cores only (cores 4-7) -> selects A78
	{
		std::vector<u64> midrs = {
			0x410FD050, 0x410FD050, 0x410FD050, 0x410FD050,
			0x410FD410, 0x410FD410, 0x410FD410, 0x410FD410
		};
		assert(evaluate_cpu_name(0xF0, midrs) == "cortex-a78");
		printf("PASS: Test 6 - Restricted to big cores selects cortex-a78\n");
	}

	// Test 7: Heterogeneous ARMv9 OnePlus 13R / Snapdragon 8 Gen 2 (3x A510 + 4x A715 + 1x X3)
	{
		std::vector<u64> midrs = {
			0x410FD460, 0x410FD460, 0x410FD460, // A510
			0x410FD4D0, 0x410FD4D0, 0x410FD4D0, 0x410FD4D0, // A715
			0x410FD4E0 // X3
		};
		assert(evaluate_cpu_name(0xFF, midrs) == "cortex-a510");
		printf("PASS: Test 7 - ARMv9 OnePlus 13R selects cortex-a510\n");
	}

	// Test 8: Mixed ARMv8.0 and ARMv8.2 (A53 + A76) intersects to ARMv8.0 baseline
	{
		std::vector<u64> midrs = {
			0x410FD030, 0x410FD030, // A53 (ARMv8.0)
			0x410FD0B0, 0x410FD0B0  // A76 (ARMv8.2)
		};
		assert(evaluate_cpu_name(0xF, midrs) == "cortex-a53");
		printf("PASS: Test 8 - Mixed ARMv8.0 and ARMv8.2 selects cortex-a53\n");
	}

	// Test 9: CPUTranslator AArch64 keeps m_use_avx=false, m_use_fma=true, stride=16
	{
		auto tf_arm = evaluate_translator_features("cortex-a55", true);
		assert(!tf_arm.m_use_avx);
		assert(tf_arm.m_use_fma);
		assert(tf_arm.vector_stride == 16);

		auto tf_x86 = evaluate_translator_features("haswell", false);
		assert(tf_x86.m_use_avx);
		assert(tf_x86.m_use_fma);
		assert(tf_x86.vector_stride == 32);
		printf("PASS: Test 9 - CPUTranslator vector stride safe for NEON\n");
	}

	printf("ALL 9 CPU TARGET AND FEATURE TESTS PASSED!\n");
	return 0;
}
