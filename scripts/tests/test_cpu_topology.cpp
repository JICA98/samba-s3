#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <utility>
#include <algorithm>
#include <cassert>
#include <span>

using u32 = uint32_t;
using u64 = uint64_t;
constexpr u64 umax = ~0ull;

enum class thread_class : u32
{
	general = 0,
	ppu = 1,
	spu = 2,
	rsx = 0x55,
};

enum class thread_scheduler_mode
{
	os,
	old,
	alt
};

struct cpu_topology_info
{
	u64 allowed_mask = 0;
	u64 performance_mask = 0;
	u64 efficiency_mask = 0;
	bool is_heterogeneous = false;
	bool discovery_successful = false;
};

// Mirroring thread_ctrl::calculate_affinity_mask
u64 calculate_affinity_mask(
	thread_class group,
	thread_scheduler_mode sched_mode,
	const cpu_topology_info& topo,
	u64 allowed_mask,
	std::span<const thread_class> custom_affinities = {})
{
	if (!allowed_mask)
	{
		allowed_mask = topo.allowed_mask ? topo.allowed_mask : 0xFFull;
	}

	bool has_custom_affinity = false;
	for (const auto& a : custom_affinities)
	{
		if (a != thread_class::general)
		{
			has_custom_affinity = true;
			break;
		}
	}

	// If user explicitly configured per-core affinities in settings, honor them
	if (has_custom_affinity)
	{
		u64 user_mask = 0;
		for (std::size_t i = 0; i < std::min<std::size_t>(64, custom_affinities.size()); ++i)
		{
			if (custom_affinities[i] == group || custom_affinities[i] == thread_class::general)
			{
				user_mask |= (1ull << i);
			}
		}

		user_mask &= allowed_mask;
		if (user_mask != 0)
		{
			return user_mask;
		}
		return allowed_mask;
	}

	// Genuine OS Mode: when user selects OS scheduling mode (or default on Android),
	// genuinely avoid restricting backend affinity.
	if (sched_mode == thread_scheduler_mode::os)
	{
		return allowed_mask;
	}

	// Affinity explicitly requested (RPCS3 Scheduler or Alternative Scheduler mode)
	if (topo.is_heterogeneous && topo.performance_mask != 0)
	{
		if (group == thread_class::spu || group == thread_class::ppu || group == thread_class::rsx)
		{
			const u64 perf_mask = topo.performance_mask & allowed_mask;
			if (perf_mask != 0)
			{
				return perf_mask;
			}
		}
	}

	// Fallback to full process allowed mask (OS management)
	return allowed_mask;
}

// Mirroring thread_ctrl::parse_cpu_topology
cpu_topology_info parse_cpu_topology(
	u64 allowed_mask,
	const std::vector<std::pair<u32, u64>>& capacities,
	const std::vector<std::pair<u32, u64>>& max_freqs,
	const std::vector<std::pair<u32, u64>>& midrs)
{
	cpu_topology_info topo;
	if (!allowed_mask)
	{
		allowed_mask = 0xFFull;
	}

	topo.allowed_mask = allowed_mask;
	topo.performance_mask = allowed_mask;
	topo.efficiency_mask = 0;
	topo.is_heterogeneous = false;
	topo.discovery_successful = false;

	// 1. Try cpu_capacity (Linux/Android Energy-Aware Scheduling)
	{
		u64 max_cap = 0;
		u64 min_cap = umax;
		u32 count = 0;

		for (const auto& [cpu, cap] : capacities)
		{
			if (cpu < 64 && (allowed_mask & (1ull << cpu)))
			{
				if (cap > max_cap) max_cap = cap;
				if (cap < min_cap) min_cap = cap;
				count++;
			}
		}

		if (count >= 2 && max_cap > min_cap && (max_cap - min_cap) >= 100)
		{
			const u64 threshold = min_cap + (max_cap - min_cap) / 2;
			u64 perf = 0;
			u64 eff = 0;

			for (const auto& [cpu, cap] : capacities)
			{
				if (cpu < 64 && (allowed_mask & (1ull << cpu)))
				{
					if (cap >= threshold)
					{
						perf |= (1ull << cpu);
					}
					else
					{
						eff |= (1ull << cpu);
					}
				}
			}

			if (perf && eff)
			{
				perf |= (allowed_mask & ~(perf | eff));
				topo.performance_mask = perf & allowed_mask;
				topo.efficiency_mask = eff & allowed_mask;
				topo.is_heterogeneous = true;
				topo.discovery_successful = true;
				return topo;
			}
		}
	}

	// 2. Try cpuinfo_max_freq
	{
		u64 max_f = 0;
		u64 min_f = umax;
		u32 count = 0;

		for (const auto& [cpu, freq] : max_freqs)
		{
			if (cpu < 64 && (allowed_mask & (1ull << cpu)))
			{
				if (freq > max_f) max_f = freq;
				if (freq < min_f) min_f = freq;
				count++;
			}
		}

		if (count >= 2 && max_f > min_f && (max_f - min_f) >= 200000)
		{
			const u64 threshold = min_f + (max_f - min_f) / 2;
			u64 perf = 0;
			u64 eff = 0;

			for (const auto& [cpu, freq] : max_freqs)
			{
				if (cpu < 64 && (allowed_mask & (1ull << cpu)))
				{
					if (freq >= threshold)
					{
						perf |= (1ull << cpu);
					}
					else
					{
						eff |= (1ull << cpu);
					}
				}
			}

			if (perf && eff)
			{
				perf |= (allowed_mask & ~(perf | eff));
				topo.performance_mask = perf & allowed_mask;
				topo.efficiency_mask = eff & allowed_mask;
				topo.is_heterogeneous = true;
				topo.discovery_successful = true;
				return topo;
			}
		}
	}

	// 3. Try MIDR_EL1 part numbers
	{
		u64 perf = 0;
		u64 eff = 0;

		for (const auto& [cpu, midr] : midrs)
		{
			if (cpu < 64 && (allowed_mask & (1ull << cpu)) && midr != 0 && midr != umax)
			{
				const u32 part = (midr >> 4) & 0xfff;
				if (part == 0xd01 || part == 0xd02 || part == 0xd04 || part == 0xd03 ||
				    part == 0xd05 || part == 0xd46 || part == 0xd80 || part == 0xd88)
				{
					eff |= (1ull << cpu);
				}
				else
				{
					perf |= (1ull << cpu);
				}
			}
		}

		if (perf && eff)
		{
			perf |= (allowed_mask & ~(perf | eff));
			topo.performance_mask = perf & allowed_mask;
			topo.efficiency_mask = eff & allowed_mask;
			topo.is_heterogeneous = true;
			topo.discovery_successful = true;
			return topo;
		}
	}

	// 4. Homogeneous or unclassifiable layout: fall back to full allowed mask
	topo.performance_mask = allowed_mask;
	topo.efficiency_mask = 0;
	topo.is_heterogeneous = false;
	topo.discovery_successful = (!capacities.empty() || !max_freqs.empty() || !midrs.empty());
	return topo;
}

int main()
{
	printf("Running CPU topology unit tests...\n");

	// Test 1: Poco X6 Pro (Dimensity 8300: 4x A510 [350] at 0-3, 4x A715 [1024] at 4-7)
	{
		std::vector<std::pair<u32, u64>> caps = {
			{0, 350}, {1, 350}, {2, 350}, {3, 350},
			{4, 1024}, {5, 1024}, {6, 1024}, {7, 1024}
		};
		auto topo = parse_cpu_topology(0xFF, caps, {}, {});
		assert(topo.is_heterogeneous);
		assert(topo.performance_mask == 0xF0); // Cores 4-7, NOT 0xFC!
		assert(topo.efficiency_mask == 0x0F);
		assert((topo.performance_mask & ~topo.allowed_mask) == 0);
		printf("Test 1 (Poco X6 Pro Dimensity 8300): PASS (perf=0x%llx, eff=0x%llx)\n",
			topo.performance_mask, topo.efficiency_mask);
	}

	// Test 2: OnePlus 13R (Snapdragon 8 Gen 2: 3x A510 [320] at 0-2, 5x big/prime [800-1024] at 3-7)
	{
		std::vector<std::pair<u32, u64>> caps = {
			{0, 320}, {1, 320}, {2, 320},
			{3, 800}, {4, 800}, {5, 850}, {6, 850}, {7, 1024}
		};
		auto topo = parse_cpu_topology(0xFF, caps, {}, {});
		assert(topo.is_heterogeneous);
		assert(topo.performance_mask == 0xF8); // Cores 3-7, NOT 0xFC!
		assert(topo.efficiency_mask == 0x07);
		assert((topo.performance_mask & ~topo.allowed_mask) == 0);
		printf("Test 2 (OnePlus 13R Snapdragon 8 Gen 2): PASS (perf=0x%llx, eff=0x%llx)\n",
			topo.performance_mask, topo.efficiency_mask);
	}

	// Test 3: Snapdragon 8 Gen 3 (2x A520 [300] at 0-1, 6x big/prime [850-1024] at 2-7)
	{
		std::vector<std::pair<u32, u64>> caps = {
			{0, 300}, {1, 300},
			{2, 850}, {3, 850}, {4, 850}, {5, 850}, {6, 850}, {7, 1024}
		};
		auto topo = parse_cpu_topology(0xFF, caps, {}, {});
		assert(topo.is_heterogeneous);
		assert(topo.performance_mask == 0xFC); // Cores 2-7
		assert(topo.efficiency_mask == 0x03);
		printf("Test 3 (Snapdragon 8 Gen 3): PASS (perf=0x%llx, eff=0x%llx)\n",
			topo.performance_mask, topo.efficiency_mask);
	}

	// Test 3b: Snapdragon 8 Gen 3 Cluster Frequencies (2x A520, 2x A720 lower, 3x A720 higher, 1x X4 prime)
	{
		std::vector<std::pair<u32, u64>> freqs = {
			{0, 2265600}, {1, 2265600},
			{2, 2956800}, {3, 2956800},
			{4, 3148800}, {5, 3148800}, {6, 3148800},
			{7, 3300000}
		};
		auto topo = parse_cpu_topology(0xFF, {}, freqs, {});
		assert(topo.is_heterogeneous);
		assert(topo.performance_mask == 0xFC);
		assert(topo.efficiency_mask == 0x03);
		printf("Test 3b (SD8Gen3 Frequencies): PASS (perf=0x%llx, eff=0x%llx)\n",
			topo.performance_mask, topo.efficiency_mask);
	}

	// Test 3c: Snapdragon 8 Gen 3 MIDRs (0xd80 A520, 0xd81 A720, 0xd82 X4)
	{
		std::vector<std::pair<u32, u64>> midrs = {
			{0, 0x410FD800}, {1, 0x410FD800},
			{2, 0x410FD810}, {3, 0x410FD810}, {4, 0x410FD810}, {5, 0x410FD810}, {6, 0x410FD810},
			{7, 0x410FD820}
		};
		auto topo = parse_cpu_topology(0xFF, {}, {}, midrs);
		assert(topo.is_heterogeneous);
		assert(topo.performance_mask == 0xFC);
		assert(topo.efficiency_mask == 0x03);
		printf("Test 3c (SD8Gen3 MIDRs): PASS (perf=0x%llx, eff=0x%llx)\n",
			topo.performance_mask, topo.efficiency_mask);
	}

	// Test 3d: Snapdragon 8 Gen 3 Scheduler Modes (OS mode vs RPCS3 mode vs offline core)
	{
		cpu_topology_info topo;
		topo.allowed_mask = 0xFF;
		topo.performance_mask = 0xFC;
		topo.efficiency_mask = 0x03;
		topo.is_heterogeneous = true;

		// OS mode: all thread groups receive unconstrained 0xFF
		assert(calculate_affinity_mask(thread_class::general, thread_scheduler_mode::os, topo, 0xFF) == 0xFF);
		assert(calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::os, topo, 0xFF) == 0xFF);
		assert(calculate_affinity_mask(thread_class::ppu, thread_scheduler_mode::os, topo, 0xFF) == 0xFF);
		assert(calculate_affinity_mask(thread_class::rsx, thread_scheduler_mode::os, topo, 0xFF) == 0xFF);

		// RPCS3 mode: SPU/PPU/RSX pinned to 0xFC, general is 0xFF
		assert(calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, 0xFF) == 0xFC);
		assert(calculate_affinity_mask(thread_class::ppu, thread_scheduler_mode::old, topo, 0xFF) == 0xFC);
		assert(calculate_affinity_mask(thread_class::rsx, thread_scheduler_mode::old, topo, 0xFF) == 0xFC);
		assert(calculate_affinity_mask(thread_class::general, thread_scheduler_mode::old, topo, 0xFF) == 0xFF);

		// Offline core 7: allowed_mask = 0x7F -> SPU/PPU/RSX pinned to 0x7C
		assert(calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, 0x7F) == 0x7C);

		// Restricted to little cores 0-1: allowed_mask = 0x03 -> fallback to 0x03
		assert(calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, 0x03) == 0x03);

		printf("Test 3d (SD8Gen3 Scheduler Modes): PASS\n");
	}

	// Test 4: Google Tensor G3 (9 cores: 4x A510 [350] at 0-3, 5x big/prime [850-1024] at 4-8)
	{
		std::vector<std::pair<u32, u64>> caps = {
			{0, 350}, {1, 350}, {2, 350}, {3, 350},
			{4, 850}, {5, 850}, {6, 850}, {7, 850}, {8, 1024}
		};
		auto topo = parse_cpu_topology(0x1FF, caps, {}, {});
		assert(topo.is_heterogeneous);
		assert(topo.performance_mask == 0x1F0); // Cores 4-8 (>8 cores)
		assert(topo.efficiency_mask == 0x0F);
		assert((topo.performance_mask & ~topo.allowed_mask) == 0);
		printf("Test 4 (Tensor G3 9-core): PASS (perf=0x%llx, eff=0x%llx)\n",
			topo.performance_mask, topo.efficiency_mask);
	}

	// Test 5: Homogeneous CPU (e.g. Snapdragon 8 Elite or x86 SMP)
	{
		std::vector<std::pair<u32, u64>> caps = {
			{0, 1000}, {1, 1000}, {2, 1024}, {3, 1024},
			{4, 1024}, {5, 1024}, {6, 1024}, {7, 1024}
		};
		auto topo = parse_cpu_topology(0xFF, caps, {}, {});
		assert(!topo.is_heterogeneous);
		assert(topo.performance_mask == 0xFF); // Full allowed mask
		printf("Test 5 (Homogeneous / SMP): PASS (perf=0x%llx)\n", topo.performance_mask);
	}

	// Test 6: Restricted cpuset mask (process allowed only cores 0-3)
	{
		std::vector<std::pair<u32, u64>> caps = {
			{0, 350}, {1, 350}, {2, 350}, {3, 350},
			{4, 1024}, {5, 1024}, {6, 1024}, {7, 1024}
		};
		auto topo = parse_cpu_topology(0x0F, caps, {}, {});
		// All allowed cores have capacity 350 -> not heterogeneous within allowed cpuset
		assert(!topo.is_heterogeneous);
		assert(topo.performance_mask == 0x0F);
		assert((topo.performance_mask & ~topo.allowed_mask) == 0);
		printf("Test 6 (Restricted cpuset 0x0F): PASS (perf=0x%llx)\n", topo.performance_mask);
	}

	// Test 7: Offline core (e.g. core 7 offline -> allowed = 0x7F)
	{
		std::vector<std::pair<u32, u64>> caps = {
			{0, 350}, {1, 350}, {2, 350}, {3, 350},
			{4, 1024}, {5, 1024}, {6, 1024}
		};
		auto topo = parse_cpu_topology(0x7F, caps, {}, {});
		assert(topo.is_heterogeneous);
		assert(topo.performance_mask == 0x70); // Cores 4-6, core 7 offline
		assert((topo.performance_mask & (1ull << 7)) == 0);
		assert((topo.performance_mask & ~topo.allowed_mask) == 0);
		printf("Test 7 (Offline core 7): PASS (perf=0x%llx)\n", topo.performance_mask);
	}

	// Test 8: Discovery unavailable -> OS management fallback
	{
		auto topo = parse_cpu_topology(0xFF, {}, {}, {});
		assert(!topo.is_heterogeneous);
		assert(topo.performance_mask == 0xFF);
		assert(!topo.discovery_successful);
		printf("Test 8 (Unavailable discovery fallback): PASS (perf=0x%llx)\n", topo.performance_mask);
	}

	// Test 9: Frequency-based discovery
	{
		std::vector<std::pair<u32, u64>> freqs = {
			{0, 1800000}, {1, 1800000}, {2, 1800000}, {3, 1800000},
			{4, 3000000}, {5, 3000000}, {6, 3000000}, {7, 3000000}
		};
		auto topo = parse_cpu_topology(0xFF, {}, freqs, {});
		assert(topo.is_heterogeneous);
		assert(topo.performance_mask == 0xF0);
		assert(topo.efficiency_mask == 0x0F);
		printf("Test 9 (Frequency-based discovery): PASS (perf=0x%llx, eff=0x%llx)\n",
			topo.performance_mask, topo.efficiency_mask);
	}

	// Test 10: MIDR-based discovery
	{
		// 0xd46 = Cortex-A510 (little), 0xd4d = Cortex-A715 (big)
		std::vector<std::pair<u32, u64>> midrs = {
			{0, 0x410fd460}, {1, 0x410fd460}, {2, 0x410fd460}, {3, 0x410fd460},
			{4, 0x410fd4d0}, {5, 0x410fd4d0}, {6, 0x410fd4d0}, {7, 0x410fd4d0}
		};
		auto topo = parse_cpu_topology(0xFF, {}, {}, midrs);
		assert(topo.is_heterogeneous);
		assert(topo.performance_mask == 0xF0);
		assert(topo.efficiency_mask == 0x0F);
		printf("Test 10 (MIDR-based discovery): PASS (perf=0x%llx, eff=0x%llx)\n",
			topo.performance_mask, topo.efficiency_mask);
	}

	// Test 11: OS Mode - returns allowed_mask without backend pinning for all thread classes
	{
		cpu_topology_info topo;
		topo.allowed_mask = 0xFF;
		topo.performance_mask = 0xF0;
		topo.efficiency_mask = 0x0F;
		topo.is_heterogeneous = true;

		assert(calculate_affinity_mask(thread_class::general, thread_scheduler_mode::os, topo, 0xFF) == 0xFF);
		assert(calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::os, topo, 0xFF) == 0xFF);
		assert(calculate_affinity_mask(thread_class::ppu, thread_scheduler_mode::os, topo, 0xFF) == 0xFF);
		assert(calculate_affinity_mask(thread_class::rsx, thread_scheduler_mode::os, topo, 0xFF) == 0xFF);
		printf("Test 11 (OS Mode no pinning): PASS\n");
	}

	// Test 12: RPCS3 Mode - pins SPU, PPU, RSX to performance cores; general uses allowed_mask
	{
		cpu_topology_info topo;
		topo.allowed_mask = 0xFF;
		topo.performance_mask = 0xF0;
		topo.efficiency_mask = 0x0F;
		topo.is_heterogeneous = true;

		assert(calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, 0xFF) == 0xF0);
		assert(calculate_affinity_mask(thread_class::ppu, thread_scheduler_mode::old, topo, 0xFF) == 0xF0);
		assert(calculate_affinity_mask(thread_class::rsx, thread_scheduler_mode::old, topo, 0xFF) == 0xF0);
		assert(calculate_affinity_mask(thread_class::general, thread_scheduler_mode::old, topo, 0xFF) == 0xFF);
		printf("Test 12 (RPCS3 Mode perf core pinning): PASS\n");
	}

	// Test 13: RPCS3 Mode on Homogeneous Topology - falls back to allowed_mask
	{
		cpu_topology_info topo;
		topo.allowed_mask = 0xFF;
		topo.performance_mask = 0xFF;
		topo.efficiency_mask = 0;
		topo.is_heterogeneous = false;

		assert(calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, 0xFF) == 0xFF);
		assert(calculate_affinity_mask(thread_class::ppu, thread_scheduler_mode::old, topo, 0xFF) == 0xFF);
		assert(calculate_affinity_mask(thread_class::rsx, thread_scheduler_mode::old, topo, 0xFF) == 0xFF);
		assert(calculate_affinity_mask(thread_class::general, thread_scheduler_mode::old, topo, 0xFF) == 0xFF);
		printf("Test 13 (RPCS3 Mode homogeneous fallback): PASS\n");
	}

	// Test 14: RPCS3 Mode with zero perf/allowed overlap - falls back to allowed_mask
	{
		cpu_topology_info topo;
		topo.allowed_mask = 0xFF;
		topo.performance_mask = 0xF0; // Cores 4-7
		topo.efficiency_mask = 0x0F;
		topo.is_heterogeneous = true;

		// Process restricted to cores 0-3 (0x0F); perf_mask & allowed_mask is 0
		const u64 restricted_allowed = 0x0F;
		assert(calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, restricted_allowed) == restricted_allowed);
		assert(calculate_affinity_mask(thread_class::ppu, thread_scheduler_mode::old, topo, restricted_allowed) == restricted_allowed);
		assert(calculate_affinity_mask(thread_class::rsx, thread_scheduler_mode::old, topo, restricted_allowed) == restricted_allowed);
		printf("Test 14 (RPCS3 Mode empty perf overlap fallback): PASS\n");
	}

	// Test 15: Alternative Scheduler Mode - pins SPU/PPU/RSX to performance cores, general to allowed_mask
	{
		cpu_topology_info topo;
		topo.allowed_mask = 0xFF;
		topo.performance_mask = 0xF8; // OnePlus 13R cores 3-7
		topo.efficiency_mask = 0x07;
		topo.is_heterogeneous = true;

		assert(calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::alt, topo, 0xFF) == 0xF8);
		assert(calculate_affinity_mask(thread_class::ppu, thread_scheduler_mode::alt, topo, 0xFF) == 0xF8);
		assert(calculate_affinity_mask(thread_class::rsx, thread_scheduler_mode::alt, topo, 0xFF) == 0xF8);
		assert(calculate_affinity_mask(thread_class::general, thread_scheduler_mode::alt, topo, 0xFF) == 0xFF);
		printf("Test 15 (Alternative Scheduler Mode): PASS\n");
	}

	// Test 16: Custom Affinity - 8-core config does NOT force-add CPUs 8-63 (Defect R17 fix)
	{
		cpu_topology_info topo;
		topo.allowed_mask = ~0ull; // All 64 cores allowed
		topo.performance_mask = 0xF0;
		topo.efficiency_mask = 0x0F;
		topo.is_heterogeneous = true;

		// 8 cores configured: cores 0-3 SPU, cores 4-7 PPU
		std::vector<thread_class> custom = {
			thread_class::spu, thread_class::spu, thread_class::spu, thread_class::spu,
			thread_class::ppu, thread_class::ppu, thread_class::ppu, thread_class::ppu
		};

		const u64 spu_mask = calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::os, topo, ~0ull, custom);
		const u64 ppu_mask = calculate_affinity_mask(thread_class::ppu, thread_scheduler_mode::os, topo, ~0ull, custom);
		const u64 rsx_mask = calculate_affinity_mask(thread_class::rsx, thread_scheduler_mode::os, topo, ~0ull, custom);

		// Cores 0-3 for SPU, cores 4-7 for PPU
		assert(spu_mask == 0x0F);
		assert(ppu_mask == 0xF0);

		// CPUs 8-63 MUST NOT BE SET! In the buggy implementation, bits 8-63 were force-added.
		assert((spu_mask & (~0ull << 8)) == 0);
		assert((ppu_mask & (~0ull << 8)) == 0);

		// RSX was not assigned to any core and no general cores exist -> fallback to allowed_mask
		assert(rsx_mask == ~0ull);
		printf("Test 16 (Custom Affinity CPUs 8-63 not force-added): PASS\n");
	}

	// Test 17: Custom Affinity - CPUs 8-63 can be selectively included or excluded
	{
		cpu_topology_info topo;
		topo.allowed_mask = ~0ull;

		// 12-core system: cores 8-9 SPU, core 10 PPU, core 11 general, cores 0-7 various
		std::vector<thread_class> custom = {
			thread_class::general, thread_class::general, // 0, 1
			thread_class::rsx, thread_class::rsx,         // 2, 3
			thread_class::ppu, thread_class::ppu,         // 4, 5
			thread_class::spu, thread_class::spu,         // 6, 7
			thread_class::spu, thread_class::spu,         // 8, 9 (SPU explicitly on CPUs 8-9)
			thread_class::ppu,                            // 10 (PPU explicitly on CPU 10)
			thread_class::general                         // 11 (General on CPU 11)
		};

		const u64 spu_mask = calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, ~0ull, custom);
		const u64 ppu_mask = calculate_affinity_mask(thread_class::ppu, thread_scheduler_mode::old, topo, ~0ull, custom);

		// SPU should include: general cores 0, 1, 11; SPU cores 6, 7, 8, 9
		const u64 expected_spu = (1ull << 0) | (1ull << 1) | (1ull << 6) | (1ull << 7) |
		                         (1ull << 8) | (1ull << 9) | (1ull << 11);
		assert(spu_mask == expected_spu);

		// CPU 8 and 9 are INCLUDED for SPU, but CPU 10 is EXCLUDED
		assert((spu_mask & (1ull << 8)) != 0);
		assert((spu_mask & (1ull << 9)) != 0);
		assert((spu_mask & (1ull << 10)) == 0);

		// PPU should include: general cores 0, 1, 11; PPU cores 4, 5, 10
		const u64 expected_ppu = (1ull << 0) | (1ull << 1) | (1ull << 4) | (1ull << 5) |
		                         (1ull << 10) | (1ull << 11);
		assert(ppu_mask == expected_ppu);

		// CPU 10 is INCLUDED for PPU, but CPUs 8 and 9 are EXCLUDED
		assert((ppu_mask & (1ull << 10)) != 0);
		assert((ppu_mask & (1ull << 8)) == 0);
		assert((ppu_mask & (1ull << 9)) == 0);

		// Cores 12-63 must NOT be set
		assert((spu_mask & (~0ull << 12)) == 0);
		assert((ppu_mask & (~0ull << 12)) == 0);

		printf("Test 17 (Custom Affinity selective CPU 8-63 inclusion/exclusion): PASS\n");
	}

	// Test 18: Custom Affinity with restricted allowed_mask intersection
	{
		cpu_topology_info topo;
		topo.allowed_mask = 0xFF; // Only cores 0-7 allowed in cpuset

		// Custom affinity mapping core 8 to SPU
		std::vector<thread_class> custom = {
			thread_class::general, thread_class::general, thread_class::general, thread_class::general,
			thread_class::general, thread_class::general, thread_class::general, thread_class::general,
			thread_class::spu // Core 8
		};

		const u64 spu_mask = calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, 0xFF, custom);
		// Core 8 was mapped, but not in allowed_mask (0xFF), so it is masked out
		assert((spu_mask & (1ull << 8)) == 0);
		assert(spu_mask == 0xFF); // Cores 0-7 (general)
		printf("Test 18 (Custom Affinity allowed_mask intersection): PASS\n");
	}

	// Test 19: Custom Affinity fallback to allowed_mask when custom mask is empty after intersection
	{
		cpu_topology_info topo;
		topo.allowed_mask = 0x0F;

		// Custom affinity assigns SPU exclusively to core 8
		std::vector<thread_class> custom(9, thread_class::ppu);
		custom[8] = thread_class::spu;

		// Allowed mask only allows cores 0-3 (0x0F). Core 8 is disallowed.
		// Intersection for SPU yields 0 -> must fall back to allowed_mask (0x0F)
		const u64 spu_mask = calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, 0x0F, custom);
		assert(spu_mask == 0x0F);
		printf("Test 19 (Custom Affinity empty intersection fallback): PASS\n");
	}

	// Test 20: Heterogeneous topology with CPUs > 7 (Google Tensor G3: 9 cores, allowed_mask 0x1FF)
	{
		std::vector<std::pair<u32, u64>> caps = {
			{0, 350}, {1, 350}, {2, 350}, {3, 350},
			{4, 850}, {5, 850}, {6, 850}, {7, 850}, {8, 1024}
		};
		auto topo = parse_cpu_topology(0x1FF, caps, {}, {});
		assert(topo.is_heterogeneous);
		assert(topo.performance_mask == 0x1F0); // Cores 4-8

		// Full allowed_mask = 0x1FF
		u64 spu_mask = calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, 0x1FF);
		assert(spu_mask == 0x1F0);
		assert((spu_mask & (1ull << 8)) != 0); // Core 8 included in performance mask

		// Restricted allowed_mask: core 8 offline/excluded (allowed_mask = 0x0FF)
		u64 spu_mask_restricted = calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, 0x0FF);
		assert(spu_mask_restricted == 0x0F0); // Cores 4-7; core 8 excluded by intersection!
		assert((spu_mask_restricted & (1ull << 8)) == 0);

		// Restricted allowed_mask: only core 8 and core 0 allowed (allowed_mask = 0x101)
		u64 spu_mask_prime_only = calculate_affinity_mask(thread_class::spu, thread_scheduler_mode::old, topo, 0x101);
		assert(spu_mask_prime_only == 0x100); // Only core 8 is both perf and allowed

		printf("Test 20 (Tensor G3 9-core scheduler modes and allowed_mask intersection): PASS\n");
	}

	// Test 21: Non-empty subset guarantee across all modes and edge cases
	{
		cpu_topology_info topo_het;
		topo_het.allowed_mask = 0x55;
		topo_het.performance_mask = 0x50;
		topo_het.efficiency_mask = 0x05;
		topo_het.is_heterogeneous = true;

		for (auto mode : {thread_scheduler_mode::os, thread_scheduler_mode::old, thread_scheduler_mode::alt})
		{
			for (auto cls : {thread_class::general, thread_class::spu, thread_class::ppu, thread_class::rsx})
			{
				u64 mask = calculate_affinity_mask(cls, mode, topo_het, 0x55);
				assert(mask != 0);
				assert((mask & ~0x55ull) == 0); // Subset of allowed_mask
			}
		}
		printf("Test 21 (Non-empty subset guarantee): PASS\n");
	}

	printf("\nALL 24 CPU TOPOLOGY AND SCHEDULER TESTS PASSED!\n");
	return 0;
}
