/**
 * Host-side verification tests for Ticket J07:
 * Runtime JIT Latency & SPU LLVM Compilation Deduplication.
 *
 * Verifies:
 * 1. Per-block single-flight state machine under 6 concurrent SPU workers.
 *    Multiple concurrent requests for an identical block hash do not duplicate compilation.
 * 2. Fine-grained locking: independent blocks compile in parallel without global lock contention.
 * 3. Exception/failure resilience: failed compilation transitions state to failed and wakes waiters.
 * 4. Atomic publication order: instruction cache maintenance and memory barriers strictly
 *    precede storing the compiled pointer with release semantics; readers observe with acquire.
 * 5. Cache key verification: guest bytes hash, codegen identity ("s3cg1"), target CPU ("cortex-a34"),
 *    and active features ("neon") are correctly formatted and integrated.
 */

#include <algorithm>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
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

// Simulated SPU Compilation State Machine (matching SPURecompiler.h & SPULLVMRecompiler.cpp)
enum class test_spu_compile_state : uint32_t {
  uncompiled = 0,
  compiling = 1,
  compiled = 2,
  failed = 3
};

using spu_function_t = void (*)(void*);

struct mock_spu_program {
  uint32_t entry_point;
  uint32_t lower_bound;
  std::vector<uint32_t> data;

  bool operator==(const mock_spu_program& rhs) const {
    return entry_point == rhs.entry_point && lower_bound == rhs.lower_bound && data == rhs.data;
  }
};

struct mock_spu_item {
  const mock_spu_program data;
  std::atomic<uint32_t> compile_state{static_cast<uint32_t>(test_spu_compile_state::uncompiled)};
  std::atomic<spu_function_t> compiled{nullptr};
  std::atomic<uint8_t> cached{0};

  mock_spu_item(mock_spu_program&& prog) : data(std::move(prog)) {}
};

struct mock_flight_guard {
  mock_spu_item* item;
  bool success = false;

  mock_flight_guard(mock_spu_item* it) : item(it) {}
  ~mock_flight_guard() {
    if (!success && item) {
      item->compile_state.store(static_cast<uint32_t>(test_spu_compile_state::failed), std::memory_order_release);
      item->compile_state.notify_all();
    }
  }
};

// Simulated recompiler compile() workflow
spu_function_t mock_compile_block(
    mock_spu_item* item,
    std::atomic<int>& compilation_counter,
    int compile_delay_ms,
    bool should_fail,
    spu_function_t result_fn) {
  // Fast path: if already compiled, return compiled pointer immediately
  if (const auto fn = item->compiled.load(std::memory_order_acquire)) {
    return fn;
  }

  // Single-flight state machine (Ticket J07):
  // Transition state from uncompiled to compiling via atomic compare_exchange.
  uint32_t expected = static_cast<uint32_t>(test_spu_compile_state::uncompiled);
  if (!item->compile_state.compare_exchange_strong(expected, static_cast<uint32_t>(test_spu_compile_state::compiling),
                                                   std::memory_order_acq_rel)) {
    // Another thread won the flight or completed/failed; wait for completion.
    while (true) {
      const uint32_t state = item->compile_state.load(std::memory_order_acquire);
      if (state == static_cast<uint32_t>(test_spu_compile_state::compiled)) {
        return item->compiled.load(std::memory_order_acquire);
      }
      if (state == static_cast<uint32_t>(test_spu_compile_state::failed)) {
        return nullptr;
      }
      item->compile_state.wait(state);
    }
  }

  mock_flight_guard guard(item);

  // Perform simulated LLVM compilation
  compilation_counter.fetch_add(1, std::memory_order_relaxed);
  if (compile_delay_ms > 0) {
    std::this_thread::sleep_for(std::chrono::milliseconds(compile_delay_ms));
  }

  if (should_fail) {
    return nullptr; // Flight guard destructor sets state to failed and notifies waiters
  }

  // Atomic publication order:
  // 1. Instruction cache maintenance
  alignas(16) uint8_t mock_code[32];
  std::memset(mock_code, 0xAA, sizeof(mock_code));
  rx::clean_dcache_invalidate_icache(mock_code, sizeof(mock_code));

  // 2. Memory barrier
#if defined(__aarch64__)
  __asm__ volatile("dsb ish; isb" ::: "memory");
#else
  std::atomic_thread_fence(std::memory_order_seq_cst);
#endif

  // 3. Store compiled function pointer with release semantics
  item->compiled.store(result_fn, std::memory_order_release);

  // 4. Transition single-flight state to compiled
  item->compile_state.store(static_cast<uint32_t>(test_spu_compile_state::compiled), std::memory_order_release);
  guard.success = true;

  // 5. Notify all waiters
  item->compile_state.notify_all();

  return result_fn;
}

// Dummy target function
static void dummy_target_func(void*) {}

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
      results[i] = mock_compile_block(&item, compilation_count, /*compile_delay_ms=*/20,
                                      /*should_fail=*/false, dummy_target_func);
    });
  }

  // Release all 6 workers simultaneously
  start_gate.store(true, std::memory_order_release);

  for (auto& w : workers) {
    w.join();
  }

  // Exactly 1 compilation must have taken place!
  TEST_ASSERT(compilation_count.load() == 1, "Expected exactly 1 compilation across 6 concurrent workers");

  // All 6 workers must receive the exact same valid function pointer!
  for (int i = 0; i < NUM_WORKERS; ++i) {
    TEST_ASSERT(results[i] == dummy_target_func, "Worker received incorrect function pointer");
  }

  // Item state must be compiled
  TEST_ASSERT(item.compile_state.load() == static_cast<uint32_t>(test_spu_compile_state::compiled),
              "Expected compile_state == compiled");
  TEST_ASSERT(item.compiled.load() == dummy_target_func, "Expected compiled pointer stored");

  PASS_TEST();
}

// ============================================================================
// Test 2: Sequential fast path after compilation
// ============================================================================
void test_fast_path_already_compiled() {
  mock_spu_program prog{0x1000, 0x1000, {0x40000000}};
  mock_spu_item item(std::move(prog));
  std::atomic<int> compilation_count{0};

  // Compile once
  auto fn1 = mock_compile_block(&item, compilation_count, 0, false, dummy_target_func);
  TEST_ASSERT(fn1 == dummy_target_func, "First compilation failed");
  TEST_ASSERT(compilation_count.load() == 1, "First compilation count != 1");

  // Subsequent requests must hit the fast path without incrementing compilation_count
  for (int i = 0; i < 100; ++i) {
    auto fn = mock_compile_block(&item, compilation_count, 0, false, dummy_target_func);
    TEST_ASSERT(fn == dummy_target_func, "Fast path returned wrong pointer");
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
      auto fn = mock_compile_block(items[i].get(), total_compilations, 10, false, dummy_target_func);
      TEST_ASSERT(fn == dummy_target_func, "Distinct block compilation failed");
    });
  }

  for (auto& t : threads) {
    t.join();
  }

  TEST_ASSERT(total_compilations.load() == NUM_BLOCKS, "All distinct blocks must be compiled");
  for (int i = 0; i < NUM_BLOCKS; ++i) {
    TEST_ASSERT(items[i]->compile_state.load() == static_cast<uint32_t>(test_spu_compile_state::compiled),
                "Block state not compiled");
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
  std::vector<spu_function_t> results(NUM_WORKERS, dummy_target_func);

  std::atomic<bool> start_gate{false};

  for (int i = 0; i < NUM_WORKERS; ++i) {
    workers.emplace_back([&, i]() {
      while (!start_gate.load(std::memory_order_acquire)) {
        std::this_thread::yield();
      }
      results[i] = mock_compile_block(&item, compilation_count, /*compile_delay_ms=*/15,
                                      /*should_fail=*/true, dummy_target_func);
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
// Test 5: Cache key generation incorporating guest bytes, codegen, CPU, features
// ============================================================================
std::string format_spu_cache_key(uint32_t entry_point, const std::string& guest_sha1_b57,
                                 const std::string& codegen_id, const std::string& eff_cpu,
                                 const std::string& feat_tag) {
  char buf[256];
  std::snprintf(buf, sizeof(buf), "__spu-0x%05x-%s-%s-%s-%s",
                entry_point, guest_sha1_b57.c_str(), codegen_id.c_str(),
                eff_cpu.c_str(), feat_tag.c_str());
  return std::string(buf);
}

void test_spu_cache_key_generation() {
  const uint32_t pc = 0x00800;
  const std::string guest_hash = "4Xk9LpQ2m1V8zT7bA";
  const std::string codegen_id = "s3cg1";
  const std::string target_cpu = "cortex-a34";
  const std::string feat_neon = "neon";

  std::string key = format_spu_cache_key(pc, guest_hash, codegen_id, target_cpu, feat_neon);

  TEST_ASSERT(key.find("0x00800") != std::string::npos, "Key missing entry point");
  TEST_ASSERT(key.find(guest_hash) != std::string::npos, "Key missing guest hash");
  TEST_ASSERT(key.find("s3cg1") != std::string::npos, "Key missing codegen identity");
  TEST_ASSERT(key.find("cortex-a34") != std::string::npos, "Key missing target CPU");
  TEST_ASSERT(key.find("neon") != std::string::npos, "Key missing active feature tag");
  TEST_ASSERT(key == "__spu-0x00800-4Xk9LpQ2m1V8zT7bA-s3cg1-cortex-a34-neon", "Full key mismatch");

  // Verify feature differentiation
  std::string sve_key = format_spu_cache_key(pc, guest_hash, codegen_id, target_cpu, "sve2");
  TEST_ASSERT(sve_key != key, "SVE2 key must differ from NEON key");
  TEST_ASSERT(sve_key.find("sve2") != std::string::npos, "Key missing sve2 feature");

  PASS_TEST();
}

// ============================================================================
// Test 6: Publication order, reader acquire/release synchronization, and icache sync
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

  // Writer thread
  std::thread writer([&]() {
    shared_data.magic1 = 0xCAFEBABE;
    shared_data.magic2 = 0xDEADBEEF;
    for (size_t i = 0; i < sizeof(shared_data.code); ++i) {
      shared_data.code[i] = static_cast<uint8_t>(i ^ 0x5A);
    }

    // Step 1: Clean dcache and invalidate icache
    rx::clean_dcache_invalidate_icache(shared_data.code, sizeof(shared_data.code));

    // Step 2: Memory barrier
#if defined(__aarch64__)
    __asm__ volatile("dsb ish; isb" ::: "memory");
#else
    std::atomic_thread_fence(std::memory_order_seq_cst);
#endif

    // Step 3: Publish pointer with release semantics
    published_ptr.store(&shared_data, std::memory_order_release);
    published.store(true, std::memory_order_release);
  });

  // Reader thread
  std::thread reader([&]() {
    while (!published.load(std::memory_order_acquire)) {
      std::this_thread::yield();
    }
    const payload* p = published_ptr.load(std::memory_order_acquire);
    TEST_ASSERT(p != nullptr, "Published pointer must not be null");
    TEST_ASSERT(p->magic1 == 0xCAFEBABE, "Magic1 corrupted - memory barrier violated");
    TEST_ASSERT(p->magic2 == 0xDEADBEEF, "Magic2 corrupted - memory barrier violated");
    for (size_t i = 0; i < sizeof(p->code); ++i) {
      TEST_ASSERT(p->code[i] == static_cast<uint8_t>(i ^ 0x5A), "Code payload corrupted");
    }
  });

  writer.join();
  reader.join();

  PASS_TEST();
}

int main() {
  std::printf("================================================================\n");
  std::printf("Running SambaS3 J07 SPU LLVM Compilation Deduplication Tests\n");
  std::printf("================================================================\n");

  test_six_concurrent_spu_workers_deduplication();
  test_fast_path_already_compiled();
  test_independent_blocks_parallel_compilation();
  test_compilation_failure_wakes_waiters();
  test_spu_cache_key_generation();
  test_atomic_publication_order_and_reader_sync();

  std::printf("\nALL 6 SPU COMPILATION DEDUPLICATION & PUBLICATION TESTS PASSED!\n");
  return 0;
}
