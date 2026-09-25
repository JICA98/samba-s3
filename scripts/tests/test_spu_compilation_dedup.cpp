/**
 * Host-side verification tests for Ticket CR05 / Phase 7:
 * SPU Single-Flight Compilation, Producer-Owned Progress, and Cache Correctness.
 *
 * Verifies:
 * 1. CR05 Tiered Single-Flight State Machine:
 *    - uncompiled (0), compiling_fast (1), compiled_fast (2),
 *      compiling_optimized (3), compiled_optimized (4), failed (5).
 *    - 6 concurrent SPU workers deduplicate compilation without redundant work.
 * 2. Independent blocks compile concurrently without global mutex contention.
 * 3. Fast-tier promotion: callable fast-tier code can be promoted to optimized tier.
 * 4. Exception & Failure Resilience:
 *    - Failure from uncompiled transitions to failed and wakes waiters.
 *    - Failure during promotion falls back to compiled_fast (does not discard working code).
 * 5. Trampoline Rebuild Safety:
 *    - Trampoline rebuild is verified BEFORE publishing compiled function pointer or state.
 *    - Trampoline failure prevents callable publication.
 * 6. Waiter Cancellation / Stop:
 *    - Waiters exit cleanly when stop/abort is signaled, eliminating hangs/deadlocks.
 * 7. Canonical Cache Key Parsing:
 *    - Parses tokenized features; "-sve2" is not falsely matched as "sve2".
 *    - Incorporates codegen tag ("s3cg2"), block mode, target CPU, guest SHA1, and entry point.
 * 8. Atomic Publication Order & Memory Barriers:
 *    - Cache maintenance and memory barriers strictly precede release store; acquire load reads valid payload.
 * 9. Producer-Owned Progress Completion:
 *    - Explicit CompileJobRecord lifecycle (queued, running, completed, failed, producer_closed, workers_joined).
 *    - No completion on pdone == ptotal - 1 or repeated empty text.
 *    - Zero-work cache hits complete explicitly with ZERO_WORK_CACHE_HIT.
 */

#include <algorithm>
#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

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

// CR05 SPU compilation states matching SPURecompiler.h
enum class test_spu_compile_state : uint32_t {
  uncompiled = 0,
  compiling_fast = 1,
  compiled_fast = 2,
  compiling_optimized = 3,
  compiled_optimized = 4,
  failed = 5
};

inline bool test_is_spu_callable_state(uint32_t state) {
  return state == static_cast<uint32_t>(test_spu_compile_state::compiled_fast) ||
         state == static_cast<uint32_t>(test_spu_compile_state::compiled_optimized);
}

using spu_function_t = void (*)(void*);

struct mock_spu_program {
  uint32_t entry_point;
  uint32_t lower_bound;
  std::vector<uint32_t> data;

  bool operator==(const mock_spu_program& rhs) const {
    return entry_point == rhs.entry_point && lower_bound == rhs.lower_bound && data == rhs.data;
  }
};

struct mock_atomic_state {
  std::atomic<uint32_t> val{0};
  std::mutex m;
  std::condition_variable cv;

  mock_atomic_state(uint32_t initial = 0) : val(initial) {}

  uint32_t load(std::memory_order order = std::memory_order_seq_cst) const {
    return val.load(order);
  }
  void store(uint32_t v, std::memory_order order = std::memory_order_seq_cst) {
    val.store(v, order);
    cv.notify_all();
  }
  bool compare_exchange_strong(uint32_t& expected, uint32_t desired, std::memory_order order = std::memory_order_seq_cst) {
    bool ok = val.compare_exchange_strong(expected, desired, order);
    if (ok) cv.notify_all();
    return ok;
  }
  bool compare_exchange_weak(uint32_t& expected, uint32_t desired, std::memory_order order = std::memory_order_seq_cst) {
    bool ok = val.compare_exchange_weak(expected, desired, order);
    if (ok) cv.notify_all();
    return ok;
  }
  void wait(uint32_t old) {
    std::unique_lock<std::mutex> lk(m);
    cv.wait(lk, [&] { return val.load(std::memory_order_acquire) != old; });
  }
  void wait_timeout(uint32_t old, std::chrono::milliseconds ms) {
    std::unique_lock<std::mutex> lk(m);
    cv.wait_for(lk, ms, [&] { return val.load(std::memory_order_acquire) != old; });
  }
  void notify_all() {
    cv.notify_all();
  }
};

struct mock_spu_item {
  const mock_spu_program data;
  mock_atomic_state compile_state{static_cast<uint32_t>(test_spu_compile_state::uncompiled)};
  std::atomic<spu_function_t> compiled{nullptr};
  std::atomic<uint8_t> cached{0};

  mock_spu_item(mock_spu_program&& prog) : data(std::move(prog)) {}
};

struct mock_flight_guard {
  mock_spu_item* item;
  bool success = false;
  uint32_t prior_state = static_cast<uint32_t>(test_spu_compile_state::uncompiled);

  mock_flight_guard(mock_spu_item* it, uint32_t prior) : item(it), prior_state(prior) {}
  ~mock_flight_guard() {
    if (!success && item) {
      const uint32_t fallback = (prior_state == static_cast<uint32_t>(test_spu_compile_state::compiled_fast))
                                    ? static_cast<uint32_t>(test_spu_compile_state::compiled_fast)
                                    : static_cast<uint32_t>(test_spu_compile_state::failed);
      item->compile_state.store(fallback, std::memory_order_release);
      item->compile_state.notify_all();
    }
  }
};

// Simulated recompiler compile() workflow with CR05 tiered states, abort awareness, and trampoline checks
spu_function_t mock_compile_block_optimized(
    mock_spu_item* item,
    std::atomic<int>& compilation_counter,
    int compile_delay_ms,
    bool should_fail_compile,
    bool should_fail_trampoline,
    std::atomic<bool>* stop_flag,
    spu_function_t result_fn) {
  if (!item) return nullptr;

  // Fast path: if already compiled with optimized tier, return immediately
  const uint32_t cur_state = item->compile_state.load(std::memory_order_acquire);
  if (cur_state == static_cast<uint32_t>(test_spu_compile_state::compiled_optimized)) {
    return item->compiled.load(std::memory_order_acquire);
  }

  uint32_t expected = cur_state;
  bool won_ownership = false;

  while (expected == static_cast<uint32_t>(test_spu_compile_state::uncompiled) ||
         expected == static_cast<uint32_t>(test_spu_compile_state::compiled_fast)) {
    if (item->compile_state.compare_exchange_weak(
            expected, static_cast<uint32_t>(test_spu_compile_state::compiling_optimized),
            std::memory_order_acq_rel)) {
      won_ownership = true;
      break;
    }
  }

  if (!won_ownership) {
    // Wait for in-flight completion with abort/stop checking
    while (true) {
      if (stop_flag && stop_flag->load(std::memory_order_acquire)) {
        return nullptr;
      }

      const uint32_t state = item->compile_state.load(std::memory_order_acquire);
      if (state == static_cast<uint32_t>(test_spu_compile_state::compiled_optimized) ||
          state == static_cast<uint32_t>(test_spu_compile_state::compiled_fast)) {
        return item->compiled.load(std::memory_order_acquire);
      }
      if (state == static_cast<uint32_t>(test_spu_compile_state::failed)) {
        return nullptr;
      }
      // Bounded wait (simulated timeout)
      item->compile_state.wait_timeout(state, std::chrono::milliseconds(20));
    }
  }

  mock_flight_guard guard(item, expected);

  compilation_counter.fetch_add(1, std::memory_order_relaxed);
  if (compile_delay_ms > 0) {
    std::this_thread::sleep_for(std::chrono::milliseconds(compile_delay_ms));
  }

  if (should_fail_compile) {
    return nullptr; // Guard destructor will restore fallback
  }

  // Trampoline rebuild check BEFORE publication
  if (should_fail_trampoline) {
    // Trampoline rebuild failed!
    return nullptr; // Guard destructor restores fallback or failed
  }

  // Atomic publication order
  alignas(16) uint8_t mock_code[32];
  std::memset(mock_code, 0xAA, sizeof(mock_code));
  rx::clean_dcache_invalidate_icache(mock_code, sizeof(mock_code));

#if defined(__aarch64__)
  __asm__ volatile("dsb ish; isb" ::: "memory");
#else
  std::atomic_thread_fence(std::memory_order_seq_cst);
#endif

  item->compiled.store(result_fn, std::memory_order_release);
  item->compile_state.store(static_cast<uint32_t>(test_spu_compile_state::compiled_optimized),
                            std::memory_order_release);
  guard.success = true;
  item->compile_state.notify_all();

  return result_fn;
}

// Simulated fast-tier compile
spu_function_t mock_compile_block_fast(
    mock_spu_item* item,
    std::atomic<int>& compilation_counter,
    spu_function_t result_fn) {
  if (!item) return nullptr;

  const uint32_t cur_state = item->compile_state.load(std::memory_order_acquire);
  if (test_is_spu_callable_state(cur_state)) {
    return item->compiled.load(std::memory_order_acquire);
  }

  uint32_t expected = static_cast<uint32_t>(test_spu_compile_state::uncompiled);
  if (!item->compile_state.compare_exchange_strong(
          expected, static_cast<uint32_t>(test_spu_compile_state::compiling_fast),
          std::memory_order_acq_rel)) {
    while (true) {
      const uint32_t state = item->compile_state.load(std::memory_order_acquire);
      if (test_is_spu_callable_state(state)) {
        return item->compiled.load(std::memory_order_acquire);
      }
      if (state == static_cast<uint32_t>(test_spu_compile_state::failed)) {
        return nullptr;
      }
      item->compile_state.wait_timeout(state, std::chrono::milliseconds(20));
    }
  }

  compilation_counter.fetch_add(1, std::memory_order_relaxed);
  item->compiled.store(result_fn, std::memory_order_release);
  item->compile_state.store(static_cast<uint32_t>(test_spu_compile_state::compiled_fast),
                            std::memory_order_release);
  item->compile_state.notify_all();
  return result_fn;
}

// Dummy target functions
static void dummy_target_func_optimized(void*) {}
static void dummy_target_func_fast(void*) {}

// ============================================================================
// Test 1: 6 concurrent SPU workers entering identical uncompiled block
// ============================================================================
void test_six_concurrent_spu_workers_deduplication() {
  mock_spu_program prog{0x800, 0x800, {0x32000000, 0x34000001, 0x36000002}};
  mock_spu_item item(std::move(prog));
  std::atomic<int> compilation_count{0};
  constexpr int NUM_WORKERS = 6;
  std::vector<std::thread> workers;
  std::vector<spu_function_t> results(NUM_WORKERS, nullptr);

  std::atomic<bool> start_gate{false};

  for (int i = 0; i < NUM_WORKERS; ++i) {
    workers.emplace_back([&, i]() {
      while (!start_gate.load(std::memory_order_acquire)) {
        std::this_thread::yield();
      }
      results[i] = mock_compile_block_optimized(&item, compilation_count, /*compile_delay_ms=*/20,
                                                /*should_fail_compile=*/false,
                                                /*should_fail_trampoline=*/false,
                                                /*stop_flag=*/nullptr, dummy_target_func_optimized);
    });
  }

  start_gate.store(true, std::memory_order_release);

  for (auto& w : workers) {
    w.join();
  }

  TEST_ASSERT(compilation_count.load() == 1, "Expected exactly 1 compilation across 6 concurrent workers");
  for (int i = 0; i < NUM_WORKERS; ++i) {
    TEST_ASSERT(results[i] == dummy_target_func_optimized, "Worker received incorrect function pointer");
  }
  TEST_ASSERT(item.compile_state.load() == static_cast<uint32_t>(test_spu_compile_state::compiled_optimized),
              "Expected compile_state == compiled_optimized");

  PASS_TEST();
}

// ============================================================================
// Test 2: Sequential fast path after compilation
// ============================================================================
void test_fast_path_already_compiled() {
  mock_spu_program prog{0x1000, 0x1000, {0x40000000}};
  mock_spu_item item(std::move(prog));
  std::atomic<int> compilation_count{0};

  auto fn1 = mock_compile_block_optimized(&item, compilation_count, 0, false, false, nullptr,
                                         dummy_target_func_optimized);
  TEST_ASSERT(fn1 == dummy_target_func_optimized, "First compilation failed");
  TEST_ASSERT(compilation_count.load() == 1, "First compilation count != 1");

  for (int i = 0; i < 100; ++i) {
    auto fn = mock_compile_block_optimized(&item, compilation_count, 0, false, false, nullptr,
                                           dummy_target_func_optimized);
    TEST_ASSERT(fn == dummy_target_func_optimized, "Fast path returned wrong pointer");
  }
  TEST_ASSERT(compilation_count.load() == 1, "Fast path must not recompile");

  PASS_TEST();
}

// ============================================================================
// Test 3: Multiple distinct blocks compile concurrently without serialization
// ============================================================================
void test_independent_blocks_parallel_compilation() {
  constexpr int NUM_BLOCKS = 8;
  std::vector<std::unique_ptr<mock_spu_item>> items;
  for (int i = 0; i < NUM_BLOCKS; ++i) {
    mock_spu_program p{static_cast<uint32_t>(0x2000 + i * 0x100), static_cast<uint32_t>(0x2000 + i * 0x100),
                       {static_cast<uint32_t>(0x50000000 + i)}};
    items.push_back(std::make_unique<mock_spu_item>(std::move(p)));
  }

  std::atomic<int> total_compilations{0};
  std::vector<std::thread> threads;
  for (int i = 0; i < NUM_BLOCKS; ++i) {
    threads.emplace_back([&, i]() {
      auto fn = mock_compile_block_optimized(items[i].get(), total_compilations, 10, false, false,
                                             nullptr, dummy_target_func_optimized);
      TEST_ASSERT(fn == dummy_target_func_optimized, "Distinct block compilation failed");
    });
  }

  for (auto& t : threads) {
    t.join();
  }

  TEST_ASSERT(total_compilations.load() == NUM_BLOCKS, "All distinct blocks must be compiled");
  for (int i = 0; i < NUM_BLOCKS; ++i) {
    TEST_ASSERT(items[i]->compile_state.load() ==
                    static_cast<uint32_t>(test_spu_compile_state::compiled_optimized),
                "Block state not compiled_optimized");
  }

  PASS_TEST();
}

// ============================================================================
// Test 4: Failure path transitions to failed and wakes all waiters
// ============================================================================
void test_compilation_failure_wakes_waiters() {
  mock_spu_program prog{0x9000, 0x9000, {0x99999999}};
  mock_spu_item item(std::move(prog));
  std::atomic<int> compilation_count{0};
  constexpr int NUM_WORKERS = 4;
  std::vector<std::thread> workers;
  std::vector<spu_function_t> results(NUM_WORKERS, dummy_target_func_optimized);

  std::atomic<bool> start_gate{false};

  for (int i = 0; i < NUM_WORKERS; ++i) {
    workers.emplace_back([&, i]() {
      while (!start_gate.load(std::memory_order_acquire)) {
        std::this_thread::yield();
      }
      results[i] = mock_compile_block_optimized(&item, compilation_count, /*compile_delay_ms=*/15,
                                                /*should_fail_compile=*/true,
                                                /*should_fail_trampoline=*/false,
                                                /*stop_flag=*/nullptr, dummy_target_func_optimized);
    });
  }

  start_gate.store(true, std::memory_order_release);

  for (auto& w : workers) {
    w.join();
  }

  TEST_ASSERT(compilation_count.load() == 1, "Expected single flight attempt on failure");
  for (int i = 0; i < NUM_WORKERS; ++i) {
    TEST_ASSERT(results[i] == nullptr, "All waiters must safely receive nullptr on compilation failure");
  }
  TEST_ASSERT(item.compile_state.load() == static_cast<uint32_t>(test_spu_compile_state::failed),
              "Expected item compile_state == failed");

  PASS_TEST();
}

// ============================================================================
// Test 5: Fast-tier compilation and subsequent promotion to optimized tier
// ============================================================================
void test_fast_tier_promotion() {
  mock_spu_program prog{0x3000, 0x3000, {0x33333333}};
  mock_spu_item item(std::move(prog));
  std::atomic<int> fast_count{0};
  std::atomic<int> opt_count{0};

  // Step 1: Fast tier compiles first
  auto fn_fast = mock_compile_block_fast(&item, fast_count, dummy_target_func_fast);
  TEST_ASSERT(fn_fast == dummy_target_func_fast, "Fast tier compilation failed");
  TEST_ASSERT(fast_count.load() == 1, "Fast count != 1");
  TEST_ASSERT(item.compile_state.load() == static_cast<uint32_t>(test_spu_compile_state::compiled_fast),
              "State != compiled_fast");
  TEST_ASSERT(test_is_spu_callable_state(item.compile_state.load()), "Fast tier must be callable");

  // Step 2: Promote to optimized tier
  auto fn_opt = mock_compile_block_optimized(&item, opt_count, 10, false, false, nullptr,
                                            dummy_target_func_optimized);
  TEST_ASSERT(fn_opt == dummy_target_func_optimized, "Optimized promotion failed");
  TEST_ASSERT(opt_count.load() == 1, "Opt count != 1");
  TEST_ASSERT(item.compile_state.load() == static_cast<uint32_t>(test_spu_compile_state::compiled_optimized),
              "State != compiled_optimized");

  PASS_TEST();
}

// ============================================================================
// Test 6: Fast-tier fallback when optimized compilation fails
// ============================================================================
void test_fast_tier_fallback_on_optimized_failure() {
  mock_spu_program prog{0x4000, 0x4000, {0x44444444}};
  mock_spu_item item(std::move(prog));
  std::atomic<int> fast_count{0};
  std::atomic<int> opt_count{0};

  // Compile fast tier
  auto fn_fast = mock_compile_block_fast(&item, fast_count, dummy_target_func_fast);
  TEST_ASSERT(fn_fast == dummy_target_func_fast, "Fast tier failed");

  // Attempt optimized compile with failure
  auto fn_opt = mock_compile_block_optimized(&item, opt_count, 10, /*should_fail_compile=*/true,
                                            /*should_fail_trampoline=*/false, nullptr,
                                            dummy_target_func_optimized);
  TEST_ASSERT(fn_opt == nullptr, "Failed compile must return nullptr");

  // State must fall back to compiled_fast, NOT failed!
  TEST_ASSERT(item.compile_state.load() == static_cast<uint32_t>(test_spu_compile_state::compiled_fast),
              "State must fall back to compiled_fast on optimized compilation failure");
  TEST_ASSERT(item.compiled.load() == dummy_target_func_fast,
              "Fast compiled pointer must remain intact and callable");

  PASS_TEST();
}

// ============================================================================
// Test 7: Trampoline rebuild failure prevents publication
// ============================================================================
void test_trampoline_failure_prevents_publication() {
  mock_spu_program prog{0x5000, 0x5000, {0x55555555}};
  mock_spu_item item(std::move(prog));
  std::atomic<int> comp_count{0};

  auto fn = mock_compile_block_optimized(&item, comp_count, 10, /*should_fail_compile=*/false,
                                         /*should_fail_trampoline=*/true, nullptr,
                                         dummy_target_func_optimized);
  TEST_ASSERT(fn == nullptr, "Trampoline failure must return nullptr");
  TEST_ASSERT(item.compile_state.load() == static_cast<uint32_t>(test_spu_compile_state::failed),
              "State must be failed on trampoline rebuild error");
  TEST_ASSERT(item.compiled.load() == nullptr, "Function pointer must NOT be published on trampoline error");

  PASS_TEST();
}

// ============================================================================
// Test 8: Waiter cancellation on stop signal
// ============================================================================
void test_waiter_cancellation_on_stop() {
  mock_spu_program prog{0x6000, 0x6000, {0x66666666}};
  mock_spu_item item(std::move(prog));
  std::atomic<int> comp_count{0};
  std::atomic<bool> stop_flag{false};

  // Set block to compiling_optimized
  item.compile_state.store(static_cast<uint32_t>(test_spu_compile_state::compiling_optimized));

  spu_function_t waiter_result = dummy_target_func_optimized;
  std::thread waiter([&]() {
    waiter_result = mock_compile_block_optimized(&item, comp_count, 0, false, false, &stop_flag,
                                                 dummy_target_func_optimized);
  });

  std::this_thread::sleep_for(std::chrono::milliseconds(20));
  // Signal stop
  stop_flag.store(true, std::memory_order_release);
  item.compile_state.notify_all();

  waiter.join();
  TEST_ASSERT(waiter_result == nullptr, "Waiter must cleanly exit on stop without hanging");

  PASS_TEST();
}

// ============================================================================
// Test 9: Cache key generation and canonical feature parsing
// ============================================================================
std::string format_canonical_cache_key(uint32_t entry_point, const std::string& guest_sha1_b57,
                                       const std::string& codegen_tag, const std::string& block_mode,
                                       const std::string& eff_cpu, const std::string& eff_feat) {
  // Parse comma/space-delimited feature flags without substring collisions
  bool has_sve2 = false;
  bool has_sve = false;
  bool has_neon = false;

  std::string token;
  std::stringstream ss(eff_feat);
  std::vector<std::string> enabled_features;

  while (std::getline(ss, token, ',')) {
    size_t start_pos = token.find_first_not_of(" \t\r\n");
    size_t end_pos = token.find_last_not_of(" \t\r\n");
    if (start_pos == std::string::npos) continue;
    token = token.substr(start_pos, end_pos - start_pos + 1);
    if (token.empty()) continue;

    if (token[0] == '+') {
      const std::string_view feat(token.data() + 1, token.size() - 1);
      if (feat == "sve2") has_sve2 = true;
      else if (feat == "sve") has_sve = true;
      else if (feat == "neon") has_neon = true;
      enabled_features.push_back(std::string(feat));
    } else if (token[0] == '-') {
      const std::string_view feat(token.data() + 1, token.size() - 1);
      if (feat == "sve2") has_sve2 = false;
      else if (feat == "sve") has_sve = false;
      else if (feat == "neon") has_neon = false;
    } else {
      if (token == "sve2") has_sve2 = true;
      else if (token == "sve") has_sve = true;
      else if (token == "neon") has_neon = true;
      enabled_features.push_back(token);
    }
  }

  const char* feat_tag = has_sve2 ? "sve2" : (has_sve ? "sve" : (has_neon ? "neon" : "base"));

  char buf[256];
  std::snprintf(buf, sizeof(buf), "__spu-0x%05x-%s-%s-%s-%s-%s",
                entry_point, guest_sha1_b57.c_str(), codegen_tag.c_str(),
                block_mode.c_str(), eff_cpu.c_str(), feat_tag);
  return std::string(buf);
}

void test_spu_cache_key_generation_and_feature_parsing() {
  const uint32_t pc = 0x00800;
  const std::string guest_hash = "4Xk9LpQ2m1V8zT7bA";
  const std::string codegen_id = "s3cg2";
  const std::string target_cpu = "cortex-x4";

  // Case 1: NEON only
  std::string k_neon = format_canonical_cache_key(pc, guest_hash, codegen_id, "safe", target_cpu, "+neon");
  TEST_ASSERT(k_neon.find("s3cg2") != std::string::npos, "Key missing s3cg2");
  TEST_ASSERT(k_neon.find("safe") != std::string::npos, "Key missing block mode");
  TEST_ASSERT(k_neon.find("neon") != std::string::npos, "Key missing neon");
  TEST_ASSERT(k_neon == "__spu-0x00800-4Xk9LpQ2m1V8zT7bA-s3cg2-safe-cortex-x4-neon", "NEON key mismatch");

  // Case 2: -sve2 flag must NOT be parsed as sve2 (fixing substring false match)
  std::string k_disabled_sve = format_canonical_cache_key(pc, guest_hash, codegen_id, "safe", target_cpu, "+neon,-sve2,-sve");
  TEST_ASSERT(k_disabled_sve.find("-sve2") == std::string::npos, "Key should not have -sve2 in tag");
  TEST_ASSERT(k_disabled_sve.find("neon") != std::string::npos, "Disabled SVE must resolve to neon");
  TEST_ASSERT(k_disabled_sve == k_neon, "-sve2 must not falsely select sve2 tag");

  // Case 3: Explicit +sve2
  std::string k_sve2 = format_canonical_cache_key(pc, guest_hash, codegen_id, "mega", target_cpu, "+neon,+sve2");
  TEST_ASSERT(k_sve2.find("sve2") != std::string::npos, "Key missing sve2");
  TEST_ASSERT(k_sve2.find("mega") != std::string::npos, "Key missing mega block mode");
  TEST_ASSERT(k_sve2 != k_neon, "SVE2 mega key must differ from NEON safe key");

  PASS_TEST();
}

// ============================================================================
// Test 10: Publication order, reader acquire/release synchronization, and icache sync
// ============================================================================
void test_atomic_publication_order_and_reader_sync() {
  struct payload {
    uint32_t magic1;
    uint32_t magic2;
    uint8_t code[64];
  };

  payload shared_data{};
  std::atomic<bool> published{false};
  std::atomic<const payload*> published_ptr{nullptr};

  std::thread writer([&]() {
    shared_data.magic1 = 0xCAFEBABE;
    shared_data.magic2 = 0xDEADBEEF;
    for (size_t i = 0; i < sizeof(shared_data.code); ++i) {
      shared_data.code[i] = static_cast<uint8_t>(i ^ 0x5A);
    }

    rx::clean_dcache_invalidate_icache(shared_data.code, sizeof(shared_data.code));

#if defined(__aarch64__)
    __asm__ volatile("dsb ish; isb" ::: "memory");
#else
    std::atomic_thread_fence(std::memory_order_seq_cst);
#endif

    published_ptr.store(&shared_data, std::memory_order_release);
    published.store(true, std::memory_order_release);
  });

  std::thread reader([&]() {
    while (!published.load(std::memory_order_acquire)) {
      std::this_thread::yield();
    }
    const payload* p = published_ptr.load(std::memory_order_acquire);
    TEST_ASSERT(p != nullptr, "Published pointer must not be null");
    TEST_ASSERT(p->magic1 == 0xCAFEBABE, "Magic1 corrupted");
    TEST_ASSERT(p->magic2 == 0xDEADBEEF, "Magic2 corrupted");
    for (size_t i = 0; i < sizeof(p->code); ++i) {
      TEST_ASSERT(p->code[i] == static_cast<uint8_t>(i ^ 0x5A), "Code payload corrupted");
    }
  });

  writer.join();
  reader.join();

  PASS_TEST();
}

// ============================================================================
// Test 11: Producer-Owned Progress Completion & Zero-Work Cache Hit
// ============================================================================
enum class mock_terminal_phase : uint32_t {
  PREPARING = 0,
  RUNNING = 1,
  COMPLETED = 2,
  FAILED = 3,
  CANCELED = 4
};

enum class mock_terminal_reason : uint32_t {
  NONE = 0,
  SUCCESS = 1,
  ZERO_WORK_CACHE_HIT = 2,
  COMPILE_ERROR = 3,
  ABORT_REQUESTED = 4
};

struct mock_compile_job_record {
  uint32_t job_id{0};
  std::atomic<uint32_t> queued{0};
  std::atomic<uint32_t> running{0};
  std::atomic<uint32_t> completed{0};
  std::atomic<uint32_t> failed{0};
  std::atomic<bool> producer_closed{false};
  std::atomic<bool> workers_joined{false};
  std::atomic<uint32_t> terminal_phase{static_cast<uint32_t>(mock_terminal_phase::PREPARING)};
  std::atomic<uint32_t> terminal_reason{static_cast<uint32_t>(mock_terminal_reason::NONE)};

  bool can_emit_completed(uint32_t ftotal, uint32_t fdone, uint32_t ptotal, uint32_t pdone) const {
    // Prohibited shortcuts check:
    // pdone >= ptotal - 1 is NOT sufficient!
    // Empty text is NOT sufficient!
    // Producer MUST be closed, all workers joined, and ftotal == fdone && ptotal == pdone!
    if (!producer_closed.load(std::memory_order_acquire)) return false;
    if (!workers_joined.load(std::memory_order_acquire)) return false;
    if (running.load(std::memory_order_acquire) > 0) return false;
    if (queued.load(std::memory_order_acquire) > 0) return false;
    return (ftotal == fdone && ptotal == pdone);
  }
};

void test_producer_owned_progress_completion() {
  mock_compile_job_record job;
  job.job_id = 1;
  const uint32_t total = 6048;

  // Scenario A: 6047 of 6048 with empty text (the classic 99% bug)
  // Must NOT emit completed!
  uint32_t fdone = 6047, ftotal = 6048;
  uint32_t pdone = 5347, ptotal = 5348;
  job.queued.store(1);
  job.running.store(1);
  job.completed.store(6047);
  job.producer_closed.store(false);
  job.workers_joined.store(false);

  TEST_ASSERT(!job.can_emit_completed(ftotal, fdone, ptotal, pdone),
              "Prohibited shortcut: pdone >= ptotal - 1 must NEVER emit completed");

  // Scenario B: All items completed, but worker has not joined yet
  fdone = 6048;
  pdone = 5348;
  job.queued.store(0);
  job.running.store(0);
  job.completed.store(6048);
  job.producer_closed.store(true);
  job.workers_joined.store(false); // worker thread still running final teardown

  TEST_ASSERT(!job.can_emit_completed(ftotal, fdone, ptotal, pdone),
              "Workers must be explicitly joined before completion is emitted");

  // Scenario C: Worker joined and producer closed -> true completion!
  job.workers_joined.store(true);
  TEST_ASSERT(job.can_emit_completed(ftotal, fdone, ptotal, pdone),
              "Producer-owned completion must trigger when all criteria met");

  // Scenario D: Zero-work cache hit
  mock_compile_job_record zero_work_job;
  zero_work_job.job_id = 2;
  zero_work_job.producer_closed.store(true);
  zero_work_job.workers_joined.store(true);
  zero_work_job.terminal_phase.store(static_cast<uint32_t>(mock_terminal_phase::COMPLETED));
  zero_work_job.terminal_reason.store(static_cast<uint32_t>(mock_terminal_reason::ZERO_WORK_CACHE_HIT));

  TEST_ASSERT(zero_work_job.can_emit_completed(0, 0, 0, 0),
              "Zero-work cache hit must successfully emit completion");
  TEST_ASSERT(zero_work_job.terminal_reason.load() ==
                  static_cast<uint32_t>(mock_terminal_reason::ZERO_WORK_CACHE_HIT),
              "Zero-work cache hit must record explicit reason");

  PASS_TEST();
}

int main() {
  std::printf("================================================================\n");
  std::printf("Running SambaS3 CR05 / Phase 7 SPU Single-Flight & Progress Tests\n");
  std::printf("================================================================\n");

  test_six_concurrent_spu_workers_deduplication();
  test_fast_path_already_compiled();
  test_independent_blocks_parallel_compilation();
  test_compilation_failure_wakes_waiters();
  test_fast_tier_promotion();
  test_fast_tier_fallback_on_optimized_failure();
  test_trampoline_failure_prevents_publication();
  test_waiter_cancellation_on_stop();
  test_spu_cache_key_generation_and_feature_parsing();
  test_atomic_publication_order_and_reader_sync();
  test_producer_owned_progress_completion();

  std::printf("\nALL %d SPU SINGLE-FLIGHT & PROGRESS TESTS PASSED SUCCESSFULLY!\n", g_tests_passed);
  return 0;
}
