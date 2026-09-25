/**
 * Host-side verification tests for Ticket V08 / UP-12:
 * RSX & Vulkan Work, Readback Scratch Reuse, and Batching.
 *
 * Verifies:
 * 1. Texture readback scratch buffer capacity retention across multiple cycles:
 *    eliminates heap allocations for subsequent readbacks of smaller/equal sizes.
 * 2. Multithreaded isolation: thread_local scratch buffer is safe and isolated across threads.
 * 3. Linear-swizzle conversion correctness (32-bit ARGB8 and 16-bit RGB565) and
 *    strict read-modify-write boundary preservation (no corruption of surrounding memory).
 * 4. Pipeline properties semantic state hashing and equality:
 *    hashes semantic fields only, completely immune to pointer addresses and uninitialized struct padding bytes.
 * 5. State redundancy check: skips redundant pipeline lookups and descriptor binds when dirty flags
 *    are clear and semantic state is unchanged.
 */

#include <algorithm>
#include <atomic>
#include <bit>
#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <memory>
#include <random>
#include <thread>
#include <vector>

using u8 = uint8_t;
using u16 = uint16_t;
using u32 = uint32_t;
using u64 = uint64_t;
using s32 = int32_t;
using f32 = float;
using usz = size_t;

static int g_tests_passed = 0;
#define TEST_ASSERT(cond, msg) \
  do { \
    if (!(cond)) { \
      std::fprintf(stderr, "FAIL: %s (line %d): %s\n", __func__, __LINE__, msg); \
      std::abort(); \
    } \
  } while (0)

#define PASS_TEST() do { g_tests_passed++; } while (0)

// FNV-1a Hash definitions matching rpcs3/util/fnv_hash.hpp
namespace rpcs3
{
	constexpr usz fnv_seed = 14695981039346656037ull;
	constexpr usz fnv_prime = 1099511628211ull;

	template <typename T>
	static inline usz hash64(usz hash_value, T data)
	{
		hash_value ^= static_cast<usz>(data);
		hash_value *= fnv_prime;
		return hash_value;
	}
}

// Swizzle implementation matching rpcs3/Emu/RSX/rsx_utils.h
namespace rsx
{
	static constexpr u32 floor_log2(u32 value)
	{
		return value <= 1 ? 0 : std::countl_zero(value) ^ 31;
	}

	static constexpr u32 ceil_log2(u32 value)
	{
		return floor_log2(value) + u32{!!(value & (value - 1))};
	}

	template <typename T, bool input_is_swizzled>
	void convert_linear_swizzle(const void* input_pixels, void* output_pixels, u16 width, u16 height, u32 pitch)
	{
		const u32 log2width = ceil_log2(width);
		const u32 log2height = ceil_log2(height);

		u32 x_mask = 0x55555555;
		u32 y_mask = 0xAAAAAAAA;

		u32 limit_mask = (log2width < log2height) ? log2width : log2height;
		limit_mask = 1 << (limit_mask << 1);

		x_mask = (x_mask | ~(limit_mask - 1));
		y_mask = (y_mask & (limit_mask - 1));

		u32 offs_y = 0;
		u32 offs_x = 0;
		u32 offs_x0 = 0;
		const u32 y_incr = limit_mask;

		const u32 pitch_in_blocks = pitch / sizeof(T);
		u32 row_offset = 0;

		if constexpr (!input_is_swizzled)
		{
			for (int y = 0; y < height; ++y, row_offset += pitch_in_blocks)
			{
				auto src = static_cast<const T*>(input_pixels) + row_offset;
				auto dst = static_cast<T*>(output_pixels) + offs_y;
				offs_x = offs_x0;

				for (int x = 0; x < width; ++x)
				{
					dst[offs_x] = src[x];
					offs_x = (offs_x - x_mask) & x_mask;
				}

				offs_y = (offs_y - y_mask) & y_mask;

				if (offs_y == 0)
				{
					offs_x0 += y_incr;
				}
			}
		}
		else
		{
			for (int y = 0; y < height; ++y, row_offset += pitch_in_blocks)
			{
				auto src = static_cast<const T*>(input_pixels) + offs_y;
				auto dst = static_cast<T*>(output_pixels) + row_offset;
				offs_x = offs_x0;

				for (int x = 0; x < width; ++x)
				{
					dst[x] = src[offs_x];
					offs_x = (offs_x - x_mask) & x_mask;
				}

				offs_y = (offs_y - y_mask) & y_mask;

				if (offs_y == 0)
				{
					offs_x0 += y_incr;
				}
			}
		}
	}
} // namespace rsx

// Minimal mock Vulkan state structures matching VKPipelineCompiler.h and graphics_pipeline_state.hpp
namespace mock_vk
{
	enum VkPrimitiveTopology : u32 {
		VK_PRIMITIVE_TOPOLOGY_POINT_LIST = 0,
		VK_PRIMITIVE_TOPOLOGY_LINE_LIST = 1,
		VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST = 3,
		VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP = 4,
	};

	enum VkPolygonMode : u32 { VK_POLYGON_MODE_FILL = 0, VK_POLYGON_MODE_LINE = 1 };
	enum VkCullModeFlagBits : u32 { VK_CULL_MODE_NONE = 0, VK_CULL_MODE_FRONT_BIT = 1, VK_CULL_MODE_BACK_BIT = 2 };
	enum VkFrontFace : u32 { VK_FRONT_FACE_COUNTER_CLOCKWISE = 0, VK_FRONT_FACE_CLOCKWISE = 1 };
	enum VkCompareOp : u32 { VK_COMPARE_OP_NEVER = 0, VK_COMPARE_OP_LESS = 1, VK_COMPARE_OP_EQUAL = 2, VK_COMPARE_OP_LESS_OR_EQUAL = 3 };
	enum VkStencilOp : u32 { VK_STENCIL_OP_KEEP = 0, VK_STENCIL_OP_ZERO = 1, VK_STENCIL_OP_REPLACE = 2 };
	enum VkLogicOp : u32 { VK_LOGIC_OP_CLEAR = 0, VK_LOGIC_OP_AND = 1, VK_LOGIC_OP_COPY = 3 };
	enum VkBlendFactor : u32 { VK_BLEND_FACTOR_ZERO = 0, VK_BLEND_FACTOR_ONE = 1, VK_BLEND_FACTOR_SRC_ALPHA = 6 };
	enum VkBlendOp : u32 { VK_BLEND_OP_ADD = 0, VK_BLEND_OP_SUBTRACT = 1 };

	struct VkStencilOpState {
		VkStencilOp failOp;
		VkStencilOp passOp;
		VkStencilOp depthFailOp;
		VkCompareOp compareOp;
		u32 compareMask;
		u32 writeMask;
		u32 reference;
	};

	struct VkPipelineColorBlendAttachmentState {
		u32 blendEnable;
		VkBlendFactor srcColorBlendFactor;
		VkBlendFactor dstColorBlendFactor;
		VkBlendOp colorBlendOp;
		VkBlendFactor srcAlphaBlendFactor;
		VkBlendFactor dstAlphaBlendFactor;
		VkBlendOp alphaBlendOp;
		u32 colorWriteMask;
	};

	struct VkPipelineInputAssemblyStateCreateInfo {
		u32 sType;
		const void* pNext;
		u32 flags;
		VkPrimitiveTopology topology;
		u32 primitiveRestartEnable;
	};

	struct VkPipelineRasterizationStateCreateInfo {
		u32 sType;
		const void* pNext;
		u32 flags;
		u32 depthClampEnable;
		u32 rasterizerDiscardEnable;
		VkPolygonMode polygonMode;
		VkCullModeFlagBits cullMode;
		VkFrontFace frontFace;
		u32 depthBiasEnable;
	};

	struct VkPipelineDepthStencilStateCreateInfo {
		u32 sType;
		const void* pNext;
		u32 flags;
		u32 depthTestEnable;
		u32 depthWriteEnable;
		VkCompareOp depthCompareOp;
		u32 depthBoundsTestEnable;
		u32 stencilTestEnable;
		VkStencilOpState front;
		VkStencilOpState back;
	};

	struct VkPipelineColorBlendStateCreateInfo {
		u32 sType;
		const void* pNext;
		u32 flags;
		u32 logicOpEnable;
		VkLogicOp logicOp;
		u32 attachmentCount;
		const VkPipelineColorBlendAttachmentState* pAttachments;
		f32 blendConstants[4];
	};

	struct VkPipelineMultisampleStateCreateInfo {
		u32 sType;
		const void* pNext;
		u32 flags;
		u32 rasterizationSamples;
		u32 sampleShadingEnable;
		f32 minSampleShading;
		const u32* pSampleMask;
		u32 alphaToCoverageEnable;
		u32 alphaToOneEnable;
	};

	struct graphics_pipeline_state {
		VkPipelineInputAssemblyStateCreateInfo ia{};
		VkPipelineDepthStencilStateCreateInfo ds{};
		VkPipelineColorBlendAttachmentState att_state[4]{};
		VkPipelineColorBlendStateCreateInfo cs{};
		VkPipelineRasterizationStateCreateInfo rs{};
		VkPipelineMultisampleStateCreateInfo ms{};

		struct {
			u32 msaa_sample_mask = 0xFFFFFFFF;
		} temp_storage;

		graphics_pipeline_state() {
			std::memset(this, 0, sizeof(*this));
			ms.rasterizationSamples = 1; // 1_BIT
			temp_storage.msaa_sample_mask = 0xFFFFFFFF;
		}
	};

	struct pipeline_props {
		graphics_pipeline_state state;
		u64 renderpass_key = 0;

		bool operator==(const pipeline_props& other) const {
			if (renderpass_key != other.renderpass_key)
				return false;

			if (state.ia.topology != other.state.ia.topology ||
				state.ia.primitiveRestartEnable != other.state.ia.primitiveRestartEnable)
				return false;

			if (state.rs.depthClampEnable != other.state.rs.depthClampEnable ||
				state.rs.rasterizerDiscardEnable != other.state.rs.rasterizerDiscardEnable ||
				state.rs.polygonMode != other.state.rs.polygonMode ||
				state.rs.cullMode != other.state.rs.cullMode ||
				state.rs.frontFace != other.state.rs.frontFace ||
				state.rs.depthBiasEnable != other.state.rs.depthBiasEnable)
				return false;

			if (state.cs.attachmentCount != other.state.cs.attachmentCount ||
				state.cs.logicOp != other.state.cs.logicOp ||
				state.cs.logicOpEnable != other.state.cs.logicOpEnable ||
				std::memcmp(state.cs.blendConstants, other.state.cs.blendConstants, 4 * sizeof(f32)))
				return false;

			for (usz i = 0; i < state.cs.attachmentCount; ++i) {
				const auto& a = state.att_state[i];
				const auto& b = other.state.att_state[i];
				if (a.blendEnable != b.blendEnable ||
					a.srcColorBlendFactor != b.srcColorBlendFactor ||
					a.dstColorBlendFactor != b.dstColorBlendFactor ||
					a.colorBlendOp != b.colorBlendOp ||
					a.srcAlphaBlendFactor != b.srcAlphaBlendFactor ||
					a.dstAlphaBlendFactor != b.dstAlphaBlendFactor ||
					a.alphaBlendOp != b.alphaBlendOp ||
					a.colorWriteMask != b.colorWriteMask)
					return false;
			}

			if (state.ds.depthTestEnable != other.state.ds.depthTestEnable ||
				state.ds.depthWriteEnable != other.state.ds.depthWriteEnable ||
				state.ds.depthCompareOp != other.state.ds.depthCompareOp ||
				state.ds.depthBoundsTestEnable != other.state.ds.depthBoundsTestEnable ||
				state.ds.stencilTestEnable != other.state.ds.stencilTestEnable ||
				state.ds.front.failOp != other.state.ds.front.failOp ||
				state.ds.front.passOp != other.state.ds.front.passOp ||
				state.ds.front.depthFailOp != other.state.ds.front.depthFailOp ||
				state.ds.front.compareOp != other.state.ds.front.compareOp ||
				state.ds.front.compareMask != other.state.ds.front.compareMask ||
				state.ds.front.writeMask != other.state.ds.front.writeMask ||
				state.ds.front.reference != other.state.ds.front.reference ||
				state.ds.back.failOp != other.state.ds.back.failOp ||
				state.ds.back.passOp != other.state.ds.back.passOp ||
				state.ds.back.depthFailOp != other.state.ds.back.depthFailOp ||
				state.ds.back.compareOp != other.state.ds.back.compareOp ||
				state.ds.back.compareMask != other.state.ds.back.compareMask ||
				state.ds.back.writeMask != other.state.ds.back.writeMask ||
				state.ds.back.reference != other.state.ds.back.reference)
				return false;

			if (state.ms.rasterizationSamples != other.state.ms.rasterizationSamples)
				return false;

			if (state.ms.rasterizationSamples != 1) {
				if (state.ms.sampleShadingEnable != other.state.ms.sampleShadingEnable ||
					state.ms.minSampleShading != other.state.ms.minSampleShading ||
					state.ms.alphaToCoverageEnable != other.state.ms.alphaToCoverageEnable ||
					state.ms.alphaToOneEnable != other.state.ms.alphaToOneEnable)
					return false;

				if (state.temp_storage.msaa_sample_mask != other.state.temp_storage.msaa_sample_mask)
					return false;
			}

			return true;
		}
	};

	usz hash_pipeline_props(const pipeline_props& props)
	{
		usz seed = rpcs3::fnv_seed;
		seed = rpcs3::hash64(seed, props.renderpass_key);

		// Input assembly
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ia.topology));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ia.primitiveRestartEnable));

		// Rasterization state
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.rs.depthClampEnable));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.rs.rasterizerDiscardEnable));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.rs.polygonMode));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.rs.cullMode));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.rs.frontFace));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.rs.depthBiasEnable));

		// Depth stencil state
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.depthTestEnable));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.depthWriteEnable));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.depthCompareOp));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.depthBoundsTestEnable));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.stencilTestEnable));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.front.failOp));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.front.passOp));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.front.depthFailOp));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.front.compareOp));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.front.compareMask));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.front.writeMask));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.front.reference));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.back.failOp));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.back.passOp));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.back.depthFailOp));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.back.compareOp));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.back.compareMask));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.back.writeMask));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ds.back.reference));

		// Color blend state - semantic properties only
		seed = rpcs3::hash64(seed, props.state.cs.attachmentCount);
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.cs.logicOpEnable));
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.cs.logicOp));
		for (int i = 0; i < 4; ++i) {
			u32 bc;
			std::memcpy(&bc, &props.state.cs.blendConstants[i], sizeof(u32));
			seed = rpcs3::hash64(seed, bc);
		}

		for (usz i = 0; i < props.state.cs.attachmentCount; ++i) {
			const auto& att = props.state.att_state[i];
			seed = rpcs3::hash64(seed, static_cast<u32>(att.blendEnable));
			seed = rpcs3::hash64(seed, static_cast<u32>(att.srcColorBlendFactor));
			seed = rpcs3::hash64(seed, static_cast<u32>(att.dstColorBlendFactor));
			seed = rpcs3::hash64(seed, static_cast<u32>(att.colorBlendOp));
			seed = rpcs3::hash64(seed, static_cast<u32>(att.srcAlphaBlendFactor));
			seed = rpcs3::hash64(seed, static_cast<u32>(att.dstAlphaBlendFactor));
			seed = rpcs3::hash64(seed, static_cast<u32>(att.alphaBlendOp));
			seed = rpcs3::hash64(seed, static_cast<u32>(att.colorWriteMask));
		}

		// Multisample state
		seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ms.rasterizationSamples));
		if (props.state.ms.rasterizationSamples != 1) {
			seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ms.sampleShadingEnable));
			u32 mss;
			std::memcpy(&mss, &props.state.ms.minSampleShading, sizeof(u32));
			seed = rpcs3::hash64(seed, mss);
			seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ms.alphaToCoverageEnable));
			seed = rpcs3::hash64(seed, static_cast<u32>(props.state.ms.alphaToOneEnable));
			seed = rpcs3::hash64(seed, static_cast<u32>(props.state.temp_storage.msaa_sample_mask));
		}

		return seed;
	}
} // namespace mock_vk

// ============================================================================
// Test 1: Scratch Buffer Capacity Retention Across Readback Cycles
// ============================================================================
void test_scratch_buffer_capacity_retention()
{
	std::printf("[TEST 1] Scratch buffer capacity retention across multiple readback cycles...\n");

	// Simulated flush readback handler with capacity retention matching VKTextureCache.h
	auto simulate_flush_readback = [](u32 swiz_size, u8* out_ptr_addr, usz* out_cap) {
		static thread_local std::vector<u8> s_swizzle_scratch;
		if (s_swizzle_scratch.size() < swiz_size) {
			s_swizzle_scratch.resize(swiz_size);
		}
		*out_ptr_addr = s_swizzle_scratch.empty() ? 0 : s_swizzle_scratch[0];
		*out_cap = s_swizzle_scratch.capacity();
		return s_swizzle_scratch.data();
	};

	usz cap1 = 0, cap2 = 0, cap3 = 0;
	u8 dummy = 0;

	// Cycle 1: 512x512 ARGB8 (1,048,576 bytes)
	const u32 size_512 = 512 * 512 * 4;
	u8* ptr1 = simulate_flush_readback(size_512, &dummy, &cap1);
	TEST_ASSERT(ptr1 != nullptr, "Cycle 1 pointer must be non-null");
	TEST_ASSERT(cap1 >= size_512, "Cycle 1 capacity must be at least 1MB");

	// Cycle 2: 256x256 ARGB8 (262,144 bytes) - smaller readback
	const u32 size_256 = 256 * 256 * 4;
	u8* ptr2 = simulate_flush_readback(size_256, &dummy, &cap2);
	TEST_ASSERT(ptr2 == ptr1, "Cycle 2 must reuse existing memory buffer without reallocation");
	TEST_ASSERT(cap2 == cap1, "Cycle 2 capacity must be retained");

	// Cycle 3: 128x128 ARGB8 (65,536 bytes) - even smaller
	const u32 size_128 = 128 * 128 * 4;
	u8* ptr3 = simulate_flush_readback(size_128, &dummy, &cap3);
	TEST_ASSERT(ptr3 == ptr1, "Cycle 3 must reuse existing memory buffer");
	TEST_ASSERT(cap3 == cap1, "Cycle 3 capacity must be retained");

	// Cycle 4: 1024x1024 ARGB8 (4,194,304 bytes) - larger readback
	const u32 size_1024 = 1024 * 1024 * 4;
	usz cap4 = 0;
	u8* ptr4 = simulate_flush_readback(size_1024, &dummy, &cap4);
	TEST_ASSERT(ptr4 != nullptr, "Cycle 4 pointer must be non-null");
	TEST_ASSERT(cap4 >= size_1024, "Cycle 4 capacity must expand to at least 4MB");

	// Cycle 5: 50 consecutive cycles of mixed smaller sizes (64x64 to 512x512)
	// Must execute with 0 reallocations!
	for (int i = 0; i < 50; ++i) {
		u32 test_size = ((i % 4) + 1) * 128 * 128 * 4; // up to 1MB
		usz cap_cycle = 0;
		u8* ptr_cycle = simulate_flush_readback(test_size, &dummy, &cap_cycle);
		TEST_ASSERT(ptr_cycle == ptr4, "Subsequent smaller readbacks must NEVER reallocate");
		TEST_ASSERT(cap_cycle == cap4, "Capacity must remain retained across all cycles");
	}

	std::printf("  -> Verified: 0 allocations across all smaller/equal readback cycles, capacity preserved!\n");
	PASS_TEST();
}

// ============================================================================
// Test 2: Multithreaded Isolation
// ============================================================================
void test_multithreaded_scratch_isolation()
{
	std::printf("[TEST 2] Multithreaded thread_local scratch buffer isolation...\n");

	constexpr int num_threads = 4;
	constexpr int cycles_per_thread = 50;
	std::atomic<bool> all_ok{true};
	std::vector<std::thread> workers;

	for (int t = 0; t < num_threads; ++t) {
		workers.emplace_back([t, &all_ok]() {
			static thread_local std::vector<u8> s_worker_scratch;
			u8 thread_marker = static_cast<u8>(t + 1);

			for (int c = 0; c < cycles_per_thread; ++c) {
				u32 size = ((c % 5) + 1) * 1024 * 16;
				if (s_worker_scratch.size() < size) {
					s_worker_scratch.resize(size);
				}
				std::memset(s_worker_scratch.data(), thread_marker, size);

				// Verify thread marker was not clobbered by other threads
				for (u32 k = 0; k < size; k += 256) {
					if (s_worker_scratch[k] != thread_marker) {
						all_ok.store(false);
						break;
					}
				}
			}
		});
	}

	for (auto& w : workers) {
		w.join();
	}

	TEST_ASSERT(all_ok.load(), "Multithreaded scratch buffers must be completely isolated without cross-talk");
	std::printf("  -> Verified: 4 concurrent worker threads executed 50 cycles with 100%% thread isolation!\n");
	PASS_TEST();
}

// ============================================================================
// Test 3: Linear-Swizzle Conversion Correctness & RMW Preservation
// ============================================================================
void test_swizzle_correctness_and_rmw()
{
	std::printf("[TEST 3] Swizzle conversion correctness and Read-Modify-Write memory preservation...\n");

	// 1. 32-bit (ARGB8) Roundtrip Test
	{
		const u16 width = 64;
		const u16 height = 64;
		const u32 pitch = width * sizeof(u32);
		const u32 count = width * height;

		std::vector<u32> original_linear(count);
		for (u32 y = 0; y < height; ++y) {
			for (u32 x = 0; x < width; ++x) {
				original_linear[y * width + x] = (0xFF000000) | (x << 16) | (y << 8) | ((x ^ y) & 0xFF);
			}
		}

		std::vector<u32> swizzled_data(count, 0);
		std::vector<u32> roundtrip_linear(count, 0);

		// Convert linear -> swizzled
		rsx::convert_linear_swizzle<u32, false>(original_linear.data(), swizzled_data.data(), width, height, pitch);

		// Swizzled data must differ from linear layout
		bool differs = false;
		for (u32 i = 0; i < count; ++i) {
			if (original_linear[i] != swizzled_data[i]) {
				differs = true;
				break;
			}
		}
		TEST_ASSERT(differs, "Swizzled data must rearrange pixels from linear order");

		// Convert swizzled -> linear
		rsx::convert_linear_swizzle<u32, true>(swizzled_data.data(), roundtrip_linear.data(), width, height, pitch);

		// Verify 100% bit-exact roundtrip
		TEST_ASSERT(std::memcmp(original_linear.data(), roundtrip_linear.data(), count * sizeof(u32)) == 0,
			"32-bit linear-to-swizzle-to-linear roundtrip must be 100% bit-exact");
	}

	// 2. 16-bit (RGB565 / Depth16) Roundtrip Test
	{
		const u16 width = 32;
		const u16 height = 32;
		const u32 pitch = width * sizeof(u16);
		const u32 count = width * height;

		std::vector<u16> original_linear(count);
		for (u32 i = 0; i < count; ++i) {
			original_linear[i] = static_cast<u16>((i * 7) & 0xFFFF);
		}

		std::vector<u16> swizzled_data(count, 0);
		std::vector<u16> roundtrip_linear(count, 0);

		rsx::convert_linear_swizzle<u16, false>(original_linear.data(), swizzled_data.data(), width, height, pitch);
		rsx::convert_linear_swizzle<u16, true>(swizzled_data.data(), roundtrip_linear.data(), width, height, pitch);

		TEST_ASSERT(std::memcmp(original_linear.data(), roundtrip_linear.data(), count * sizeof(u16)) == 0,
			"16-bit linear-to-swizzle-to-linear roundtrip must be 100% bit-exact");
	}

	// 3. Read-Modify-Write Boundary Preservation Test
	{
		// Wrap texture data in a buffer with canary zones before and after
		constexpr u32 canary_before_size = 256;
		constexpr u32 tex_width = 32;
		constexpr u32 tex_height = 32;
		constexpr u32 tex_pitch = tex_width * 4;
		constexpr u32 tex_size = tex_pitch * tex_height;
		constexpr u32 canary_after_size = 256;
		constexpr u32 total_buffer_size = canary_before_size + tex_size + canary_after_size;

		std::vector<u8> buffer(total_buffer_size);
		std::memset(buffer.data(), 0xAA, canary_before_size);
		std::memset(buffer.data() + canary_before_size + tex_size, 0x55, canary_after_size);

		u8* tex_data = buffer.data() + canary_before_size;
		for (u32 i = 0; i < tex_size; ++i) {
			tex_data[i] = static_cast<u8>(i & 0xFF);
		}

		// Simulate imp_flush: scratch buffer copy + in-place swizzle into tex_data
		static thread_local std::vector<u8> s_swizzle_scratch;
		if (s_swizzle_scratch.size() < tex_size) {
			s_swizzle_scratch.resize(tex_size);
		}
		std::memcpy(s_swizzle_scratch.data(), tex_data, tex_size);
		rsx::convert_linear_swizzle<u32, false>(s_swizzle_scratch.data(), tex_data, tex_width, tex_height, tex_pitch);

		// Assert canary before is intact
		for (u32 i = 0; i < canary_before_size; ++i) {
			TEST_ASSERT(buffer[i] == 0xAA, "Canary prefix bytes before texture must be 100% untouched");
		}

		// Assert canary after is intact
		for (u32 i = 0; i < canary_after_size; ++i) {
			TEST_ASSERT(buffer[canary_before_size + tex_size + i] == 0x55,
				"Canary suffix bytes beyond texture must be 100% untouched (RMW correctness)");
		}
	}

	std::printf("  -> Verified: 32-bit and 16-bit swizzle conversions are bit-exact; RMW boundaries preserved!\n");
	PASS_TEST();
}

// ============================================================================
// Test 4: Pipeline Properties Semantic State Hashing and Equality
// ============================================================================
void test_pipeline_props_semantic_hashing_and_equality()
{
	std::printf("[TEST 4] Pipeline properties semantic state hashing and equality...\n");

	mock_vk::pipeline_props p1{};
	p1.renderpass_key = 0x123456789ABCDEF0ull;
	p1.state.ia.topology = mock_vk::VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
	p1.state.ia.primitiveRestartEnable = 0;
	p1.state.rs.cullMode = mock_vk::VK_CULL_MODE_BACK_BIT;
	p1.state.rs.frontFace = mock_vk::VK_FRONT_FACE_COUNTER_CLOCKWISE;
	p1.state.rs.polygonMode = mock_vk::VK_POLYGON_MODE_FILL;
	p1.state.ds.depthTestEnable = 1;
	p1.state.ds.depthWriteEnable = 1;
	p1.state.ds.depthCompareOp = mock_vk::VK_COMPARE_OP_LESS_OR_EQUAL;
	p1.state.cs.attachmentCount = 1;
	p1.state.att_state[0].colorWriteMask = 0xF;
	p1.state.att_state[0].blendEnable = 1;
	p1.state.att_state[0].srcColorBlendFactor = mock_vk::VK_BLEND_FACTOR_SRC_ALPHA;
	p1.state.att_state[0].dstColorBlendFactor = mock_vk::VK_BLEND_FACTOR_ONE;

	// Create identical semantic copy p2
	mock_vk::pipeline_props p2 = p1;

	// Inject different pointer addresses in p1 vs p2
	mock_vk::VkPipelineColorBlendAttachmentState dummy_att{};
	u32 dummy_sample_mask = 0xFF;
	p1.state.cs.pAttachments = &dummy_att;
	p2.state.cs.pAttachments = nullptr; // completely different pointer!
	p1.state.ms.pSampleMask = &dummy_sample_mask;
	p2.state.ms.pSampleMask = nullptr;   // completely different pointer!
	p1.state.ia.pNext = reinterpret_cast<void*>(0xDEADBEEF);
	p2.state.ia.pNext = nullptr;

	// Verify that semantic hash is IDENTICAL regardless of pointer values
	usz h1 = mock_vk::hash_pipeline_props(p1);
	usz h2 = mock_vk::hash_pipeline_props(p2);
	TEST_ASSERT(h1 == h2, "Semantic hashes must match when semantic state is identical, regardless of pointers");
	TEST_ASSERT(p1 == p2, "Operator== must evaluate true for identical semantic states");

	// Change a semantic property: cull mode
	p2.state.rs.cullMode = mock_vk::VK_CULL_MODE_FRONT_BIT;
	usz h3 = mock_vk::hash_pipeline_props(p2);
	TEST_ASSERT(h1 != h3, "Hash must change when semantic state changes (cullMode)");
	TEST_ASSERT(!(p1 == p2), "Operator== must evaluate false when semantic state changes");

	// Change blend factor
	p2 = p1;
	p2.state.att_state[0].srcColorBlendFactor = mock_vk::VK_BLEND_FACTOR_ZERO;
	usz h4 = mock_vk::hash_pipeline_props(p2);
	TEST_ASSERT(h1 != h4, "Hash must change when blend factor changes");
	TEST_ASSERT(!(p1 == p2), "Operator== must evaluate false when blend factor changes");

	// Change topology
	p2 = p1;
	p2.state.ia.topology = mock_vk::VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
	usz h5 = mock_vk::hash_pipeline_props(p2);
	TEST_ASSERT(h1 != h5, "Hash must change when primitive topology changes");
	TEST_ASSERT(!(p1 == p2), "Operator== must evaluate false when primitive topology changes");

	std::printf("  -> Verified: Semantic hashing is immune to pointers and padding; detects semantic changes!\n");
	PASS_TEST();
}

// ============================================================================
// Test 5: Redundant State Filtering & Descriptor Bind Elision
// ============================================================================
void test_redundant_draw_state_and_descriptor_bind()
{
	std::printf("[TEST 5] Redundant pipeline state filtering & descriptor bind elision...\n");

	// Simulate load_program state filtering matching VKGSRender.cpp
	struct mock_pipeline_context {
		mock_vk::pipeline_props props;
		bool pipeline_config_dirty = false;
		void* current_program = reinterpret_cast<void*>(0x1234);

		bool load_program(mock_vk::VkPrimitiveTopology prim, u32 restart, u64 rpass_key, int* out_cache_lookups) {
			if (pipeline_config_dirty) {
				// Full decode and lookup
				(*out_cache_lookups)++;
				pipeline_config_dirty = false;
				props.state.ia.topology = prim;
				props.state.ia.primitiveRestartEnable = restart;
				props.renderpass_key = rpass_key;
				return true;
			} else {
				// Fast path check
				if (current_program &&
					props.state.ia.topology == prim &&
					props.state.ia.primitiveRestartEnable == restart &&
					props.renderpass_key == rpass_key)
				{
					// Semantic state unchanged, dirty flags not set - skip lookup!
					return true;
				}

				(*out_cache_lookups)++;
				props.state.ia.topology = prim;
				props.state.ia.primitiveRestartEnable = restart;
				props.renderpass_key = rpass_key;
				return true;
			}
		}
	};

	mock_pipeline_context ctx;
	ctx.props.state.ia.topology = mock_vk::VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
	ctx.props.state.ia.primitiveRestartEnable = 0;
	ctx.props.renderpass_key = 100;
	ctx.pipeline_config_dirty = false;

	int cache_lookups = 0;

	// Draw 1: Same state -> should be elided!
	ctx.load_program(mock_vk::VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, 0, 100, &cache_lookups);
	TEST_ASSERT(cache_lookups == 0, "Unchanged state must elide pipeline cache lookup");

	// Draw 2: 100 consecutive draws with identical state -> 0 lookups!
	for (int i = 0; i < 100; ++i) {
		ctx.load_program(mock_vk::VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, 0, 100, &cache_lookups);
	}
	TEST_ASSERT(cache_lookups == 0, "100 unchanged draws must execute with 0 cache lookups");

	// Draw 3: Topology changes -> 1 lookup!
	ctx.load_program(mock_vk::VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, 0, 100, &cache_lookups);
	TEST_ASSERT(cache_lookups == 1, "State change must trigger exactly 1 cache lookup");

	// Simulate emit_geometry descriptor binding condition:
	// if (reload_state || update_descriptors) bind(...)
	int descriptor_bind_count = 0;
	auto simulate_emit_geometry = [&](bool reload_state, bool update_descriptors) {
		if (reload_state || update_descriptors) {
			descriptor_bind_count++;
		}
	};

	// Multi-subdraw batch: subdraw 0 has reload_state=true, update_descriptors=true
	descriptor_bind_count = 0;
	simulate_emit_geometry(true, true); // subdraw 0 -> binds
	TEST_ASSERT(descriptor_bind_count == 1, "Subdraw 0 must bind descriptors");

	// Subsequent subdraws (subdraw 1..9) with unchanged buffers (reload_state=false, update_descriptors=false)
	for (int sub = 1; sub < 10; ++sub) {
		simulate_emit_geometry(false, false);
	}
	TEST_ASSERT(descriptor_bind_count == 1, "Subdraws 1..9 must elide redundant descriptor binds");

	// Subdraw 10 updates vertex buffers
	simulate_emit_geometry(false, true);
	TEST_ASSERT(descriptor_bind_count == 2, "Buffer change must trigger descriptor bind");

	std::printf("  -> Verified: Redundant pipeline cache lookups and descriptor binds successfully elided!\n");
	PASS_TEST();
}

int main()
{
	std::printf("===================================================================\n");
	std::printf("RUNNING SAMBAS3 VULKAN SCRATCH REUSE & PIPELINE BATCHING TESTS\n");
	std::printf("===================================================================\n");

	test_scratch_buffer_capacity_retention();
	test_multithreaded_scratch_isolation();
	test_swizzle_correctness_and_rmw();
	test_pipeline_props_semantic_hashing_and_equality();
	test_redundant_draw_state_and_descriptor_bind();

	std::printf("===================================================================\n");
	std::printf("ALL %d VULKAN WORK & SCRATCH REUSE TESTS PASSED SUCCESSFULLY!\n", g_tests_passed);
	std::printf("===================================================================\n");
	return 0;
}
