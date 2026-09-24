/**
 * Host-side verification test for rx::busy_wait, cycles_to_ticks, ns_to_ticks, ticks_to_ns
 * (Phase 5 / A03).
 */

#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>

#include "rx/asm.hpp"

static int g_tests_passed = 0;
#define TEST_ASSERT(cond, msg) \
  do { \
    if (!(cond)) { \
      std::fprintf(stderr, "FAIL: %s (line %d): %s\n", __func__, __LINE__, msg); \
      std::abort(); \
    } \
  } while (0)

#define PASS_TEST() do { g_tests_passed++; } while (0)

void test_nominal_frequency_constant() {
  TEST_ASSERT(rx::NOMINAL_X86_FREQ_HZ == 3'500'000'000ULL, "NOMINAL_X86_FREQ_HZ must be 3.5 GHz");
  PASS_TEST();
}

void test_snapdragon_19_2_mhz() {
  constexpr std::uint64_t freq = 19'200'000ULL;

  // Zero cycles
  TEST_ASSERT(rx::cycles_to_ticks(0, freq) == 0, "0 cycles must convert to 0 ticks");

  // Subtick values (< 91 cycles rounds down to 0)
  TEST_ASSERT(rx::cycles_to_ticks(1, freq) == 0, "1 cycle must round to 0 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(10, freq) == 0, "10 cycles must round to 0 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(50, freq) == 0, "50 cycles must round to 0 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(90, freq) == 0, "90 cycles must round to 0 ticks");

  // Half-tick boundary (3500 / 38.4 = 91.14 cycles)
  TEST_ASSERT(rx::cycles_to_ticks(92, freq) == 1, "92 cycles must round to 1 tick");

  // Codebase caller values
  TEST_ASSERT(rx::cycles_to_ticks(100, freq) == 1, "100 cycles must convert to 1 tick");
  TEST_ASSERT(rx::cycles_to_ticks(200, freq) == 1, "200 cycles must convert to 1 tick");
  TEST_ASSERT(rx::cycles_to_ticks(300, freq) == 2, "300 cycles must convert to 2 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(500, freq) == 3, "500 cycles must convert to 3 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(1500, freq) == 8, "1500 cycles must convert to 8 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(3000, freq) == 16, "3000 cycles must convert to 16 ticks");

  PASS_TEST();
}

void test_mediatek_24_mhz() {
  constexpr std::uint64_t freq = 24'000'000ULL;

  // Zero cycles
  TEST_ASSERT(rx::cycles_to_ticks(0, freq) == 0, "0 cycles must convert to 0 ticks");

  // Subtick values (< 73 cycles rounds down to 0)
  TEST_ASSERT(rx::cycles_to_ticks(1, freq) == 0, "1 cycle must round to 0 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(50, freq) == 0, "50 cycles must round to 0 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(72, freq) == 0, "72 cycles must round to 0 ticks");

  // Boundary (3500 / 48 = 72.9 cycles)
  TEST_ASSERT(rx::cycles_to_ticks(73, freq) == 1, "73 cycles must round to 1 tick");

  // Codebase caller values
  TEST_ASSERT(rx::cycles_to_ticks(100, freq) == 1, "100 cycles must convert to 1 tick");
  TEST_ASSERT(rx::cycles_to_ticks(200, freq) == 1, "200 cycles must convert to 1 tick");
  TEST_ASSERT(rx::cycles_to_ticks(300, freq) == 2, "300 cycles must convert to 2 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(500, freq) == 3, "500 cycles must convert to 3 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(1500, freq) == 10, "1500 cycles must convert to 10 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(3000, freq) == 21, "3000 cycles must convert to 21 ticks");

  // Comparison with old hardcoded /182:
  // Old code: 3000 / 182 = 16 ticks. At 24 MHz, 16 ticks = 667 ns (22% under-wait!).
  // New code: 21 ticks at 24 MHz = 875 ns (matches 3000 / 3.5 GHz = 857 ns).
  TEST_ASSERT(rx::cycles_to_ticks(3000, freq) > 16, "24 MHz must produce more ticks than old /182");

  PASS_TEST();
}

void test_100_mhz() {
  constexpr std::uint64_t freq = 100'000'000ULL;

  TEST_ASSERT(rx::cycles_to_ticks(0, freq) == 0, "0 cycles -> 0 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(10, freq) == 0, "10 cycles -> 0 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(100, freq) == 3, "100 cycles -> 3 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(200, freq) == 6, "200 cycles -> 6 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(300, freq) == 9, "300 cycles -> 9 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(500, freq) == 14, "500 cycles -> 14 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(1500, freq) == 43, "1500 cycles -> 43 ticks");
  TEST_ASSERT(rx::cycles_to_ticks(3000, freq) == 86, "3000 cycles -> 86 ticks");

  PASS_TEST();
}

void test_fallbacks_and_zero() {
  // host_freq == 0 falls back to 19.2 MHz
  TEST_ASSERT(rx::cycles_to_ticks(3000, 0) == rx::cycles_to_ticks(3000, 19'200'000ULL),
              "0 host_freq must fallback to 19.2 MHz");

  // ref_freq == 0 falls back to NOMINAL_X86_FREQ_HZ (3.5 GHz)
  TEST_ASSERT(rx::cycles_to_ticks(3000, 19'200'000ULL, 0) == 16,
              "0 ref_freq must fallback to 3.5 GHz");

  // cycles == 0 always returns 0 regardless of frequencies
  TEST_ASSERT(rx::cycles_to_ticks(0, 0, 0) == 0, "0 cycles must return 0");
  TEST_ASSERT(rx::cycles_to_ticks(0, 100'000'000ULL, 3'500'000'000ULL) == 0, "0 cycles must return 0");

  PASS_TEST();
}

void test_boundary_overflow_safety() {
  constexpr std::uint64_t max_u64 = std::numeric_limits<std::uint64_t>::max();
  constexpr std::uint64_t freq = 24'000'000ULL;

  // Max uint64 should saturate without crashing, zero-dividing, or wrapping negatively
  const std::uint64_t saturated = rx::cycles_to_ticks(max_u64, freq);
  TEST_ASSERT(saturated > 0, "max_u64 must produce non-zero saturated result");

  // High cycle counts (e.g. 100 billion cycles)
  constexpr std::uint64_t large_cycles = 100'000'000'000ULL;
  const std::uint64_t large_ticks = rx::cycles_to_ticks(large_cycles, freq);
  // 100e9 * 24M / 3.5B = 685,714,286 ticks
  TEST_ASSERT(large_ticks == 685'714'286ULL, "100B cycles conversion must match exact formula");

  PASS_TEST();
}

void test_duration_conversions() {
  constexpr std::uint64_t freq = 19'200'000ULL;

  TEST_ASSERT(rx::ns_to_ticks(0, freq) == 0, "0 ns must be 0 ticks");

  // 857 ns (~3000 cycles at 3.5 GHz)
  // 857 * 19.2M / 1e9 = 16.45 -> rounds to 16 ticks
  TEST_ASSERT(rx::ns_to_ticks(857, freq) == 16, "857 ns at 19.2 MHz must be 16 ticks");

  // Roundtrip
  const std::uint64_t ticks = rx::ns_to_ticks(1000, freq); // 1 us = 19.2 -> 19 ticks
  TEST_ASSERT(ticks == 19, "1000 ns must be 19 ticks at 19.2 MHz");
  const std::uint64_t ns_back = rx::ticks_to_ns(ticks, freq); // 19 * 1e9 / 19.2M = 989.58 -> 990 ns
  TEST_ASSERT(ns_back >= 980 && ns_back <= 1000, "ticks_to_ns roundtrip within 1 tick tolerance");

  // 24 MHz: 857 ns * 24M / 1e9 = 20.57 -> rounds to 21 ticks
  TEST_ASSERT(rx::ns_to_ticks(857, 24'000'000ULL) == 21, "857 ns at 24 MHz must be 21 ticks");

  PASS_TEST();
}

void test_counter_wrap_elapsed_comparison() {
  constexpr std::uint64_t u64_max = std::numeric_limits<std::uint64_t>::max();
  const std::uint64_t start = u64_max - 10; // 0xFFFFFFFFFFFFFFF5
  const std::uint64_t current = 5;
  const std::uint64_t ticks = 16ULL;

  // Unsigned 64-bit subtraction naturally handles wrapping
  const std::uint64_t elapsed = current - start;
  TEST_ASSERT(elapsed == 16ULL, "Elapsed counter across unsigned 64-bit wrap boundary must be 16 ticks");

  // Step-by-step loop simulation verifying (now - start) < ticks
  int iterations = 0;
  for (std::uint64_t now = start; (now - start) < ticks; now++) {
    iterations++;
  }
  TEST_ASSERT(iterations == 16, "Elapsed loop must execute exactly 16 steps across wrap boundary");

  // Demonstrate that the buggy old pattern (now < stop) failed immediately
  const std::uint64_t stop = start + ticks; // wraps to 5
  int buggy_iterations = 0;
  for (std::uint64_t now = start; now < stop; now++) {
    buggy_iterations++;
  }
  TEST_ASSERT(buggy_iterations == 0, "Old pattern now < stop terminated immediately on wrap");

  // Verify wrap-around across different boundaries
  TEST_ASSERT(static_cast<std::uint64_t>(0ULL - u64_max) == 1ULL, "0 - max must be 1 tick");
  TEST_ASSERT(static_cast<std::uint64_t>(100ULL - (u64_max - 50)) == 151ULL, "Wrap distance must be 151 ticks");

  PASS_TEST();
}

void test_saturated_tick_intervals() {
  constexpr std::uint64_t u64_max = std::numeric_limits<std::uint64_t>::max();
  constexpr std::uint64_t saturated_ticks = u64_max; // ~0ULL
  const std::uint64_t start = 1000ULL;

  // With elapsed comparison ((now - start) < ticks), saturated ticks (~0ULL)
  // evaluates to true for all non-saturated elapsed spans, preventing immediate return.
  TEST_ASSERT((start - start) < saturated_ticks, "0 elapsed must be < ~0ULL (no immediate return)");
  TEST_ASSERT(((start + 1ULL) - start) < saturated_ticks, "1 elapsed must be < ~0ULL");
  TEST_ASSERT(((start + 10'000ULL) - start) < saturated_ticks, "10k elapsed must be < ~0ULL");
  TEST_ASSERT(((start + (1ULL << 62)) - start) < saturated_ticks, "Large elapsed must still be < ~0ULL");

  // Contrast with old buggy pattern: stop = start + ~0ULL = start - 1
  const std::uint64_t buggy_stop = start + saturated_ticks;
  TEST_ASSERT(start >= buggy_stop, "Buggy stop was start - 1, causing immediate loop exit on first check");

  // Test wrapping counter with saturated ticks
  const std::uint64_t wrap_start = u64_max - 5;
  const std::uint64_t wrap_now = 5ULL;
  const std::uint64_t wrap_elapsed = wrap_now - wrap_start; // 11
  TEST_ASSERT(wrap_elapsed < saturated_ticks, "Wrapped elapsed (11) must be < ~0ULL");

  // Near boundary ticks: ~0ULL - 1 and halfway point
  constexpr std::uint64_t near_max_ticks = u64_max - 1;
  TEST_ASSERT(wrap_elapsed < near_max_ticks, "Wrapped elapsed must be < (~0ULL - 1)");
  constexpr std::uint64_t half_ticks = 1ULL << 63;
  TEST_ASSERT(wrap_elapsed < half_ticks, "Wrapped elapsed must be < (1ULL << 63)");

  PASS_TEST();
}

void test_frequency_conversion_dynamic_x86() {
  // 1. Host timer frequency detection
  const std::uint64_t host_freq = rx::get_timer_frequency();
  TEST_ASSERT(host_freq > 0, "rx::get_timer_frequency() must return non-zero host frequency");

  // 2. Conversion across dynamic x86 frequencies (2.4 GHz, 2.8 GHz, 3.2 GHz, 3.5 GHz, 4.0 GHz)
  constexpr std::uint64_t freqs[] = {
      2'400'000'000ULL, // 2.4 GHz
      2'800'000'000ULL, // 2.8 GHz
      3'200'000'000ULL, // 3.2 GHz
      3'500'000'000ULL, // 3.5 GHz (nominal reference)
      4'000'000'000ULL  // 4.0 GHz
  };

  for (const std::uint64_t f : freqs) {
    // 3500 nominal cycles at reference 3.5 GHz: ticks = 3500 * f / 3.5 GHz = f / 1'000'000
    const std::uint64_t expected_ticks = f / 1'000'000ULL;
    TEST_ASSERT(rx::cycles_to_ticks(3500, f) == expected_ticks,
                "cycles_to_ticks(3500) must scale accurately to host frequency");

    // 1000 ns (1 us) at frequency f: ticks = 1000 * f / 1e9 = f / 1'000'000
    TEST_ASSERT(rx::ns_to_ticks(1000, f) == expected_ticks,
                "ns_to_ticks(1000) must equal f / 1'000'000 ticks");

    // Roundtrip back to ns
    const std::uint64_t roundtrip_ns = rx::ticks_to_ns(expected_ticks, f);
    TEST_ASSERT(roundtrip_ns == 1000ULL, "ticks_to_ns roundtrip must produce 1000 ns");
  }

  // 3. Dynamic frequency override via rx::set_timer_frequency
  rx::set_timer_frequency(2'600'000'000ULL);
  TEST_ASSERT(rx::get_timer_frequency() == 2'600'000'000ULL,
              "rx::set_timer_frequency must override active frequency");
  rx::set_timer_frequency(0);
  TEST_ASSERT(rx::get_timer_frequency() == host_freq,
              "rx::set_timer_frequency(0) must reset back to detected host frequency");

  PASS_TEST();
}

void test_runtime_busy_wait_execution() {
  // Test actual busy_wait functions on host (x86_64)
  const auto t0 = std::chrono::steady_clock::now();
  rx::busy_wait(0);
  rx::busy_wait(100);
  rx::busy_wait(500);
  rx::busy_wait_ticks(10);
  rx::busy_wait_ns(100);
  const auto t1 = std::chrono::steady_clock::now();

  const auto elapsed_us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();
  // Total of all short waits should finish in under 10 ms (usually < 20 us)
  TEST_ASSERT(elapsed_us < 10000, "busy_wait execution must complete promptly");

  PASS_TEST();
}

int main() {
  std::printf("Running Phase 5 busy_wait unit tests...\n");

  test_nominal_frequency_constant();
  test_snapdragon_19_2_mhz();
  test_mediatek_24_mhz();
  test_100_mhz();
  test_fallbacks_and_zero();
  test_boundary_overflow_safety();
  test_duration_conversions();
  test_counter_wrap_elapsed_comparison();
  test_saturated_tick_intervals();
  test_frequency_conversion_dynamic_x86();
  test_runtime_busy_wait_execution();

  std::printf("ALL %d BUSY_WAIT TESTS PASSED!\n", g_tests_passed);
  return 0;
}
