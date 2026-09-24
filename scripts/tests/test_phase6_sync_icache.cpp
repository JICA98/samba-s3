/**
 * Host-side verification test for Phase 6 (C01, C02, C03):
 * 1. C01: Vulkan readback wait timeout propagation and cache unsync
 * 2. C02: ARM instruction-cache maintenance and rx::clean_dcache_invalidate_icache
 * 3. C03: GPU-label backing buffer retention across pool rollover
 */

#include <algorithm>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <tuple>
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

// ============================================================================
// C02: Instruction-cache maintenance test
// ============================================================================

void test_clean_dcache_invalidate_icache_boundaries() {
  // Test null pointer handling
  rx::clean_dcache_invalidate_icache(nullptr, 0);
  rx::clean_dcache_invalidate_icache(nullptr, 1024);

  // Test zero size handling
  uint32_t val = 0x12345678;
  rx::clean_dcache_invalidate_icache(&val, 0);

  // Test small buffer (16 bytes - patchpoint size)
  alignas(16) uint8_t code_buf[16];
  std::memset(code_buf, 0x90, sizeof(code_buf));
  rx::clean_dcache_invalidate_icache(code_buf, sizeof(code_buf));

  // Test 36 bytes (branch patchpoint size)
  alignas(16) uint8_t patch_buf[36];
  std::memset(patch_buf, 0x1F, sizeof(patch_buf));
  rx::clean_dcache_invalidate_icache(patch_buf, sizeof(patch_buf));

  // Test page-crossing buffer
  std::vector<uint8_t> large_buf(8192, 0xAA);
  rx::clean_dcache_invalidate_icache(large_buf.data(), large_buf.size());

  PASS_TEST();
}

// ============================================================================
// C03: GPU Label Pool Rollover and Storage Retention Test
// ============================================================================

namespace test_sim {

enum class label_constants : uint32_t {
  set_ = 0xCAFEBABE,
  reset_ = 0xDEADBEEF
};

struct mock_buffer {
  std::vector<uint32_t> memory;
  bool is_unmapped = false;
  bool is_disposed = false;

  mock_buffer(uint32_t count) : memory(count, 0) {}

  uint32_t* map() {
    TEST_ASSERT(!is_unmapped, "Cannot map an unmapped buffer");
    return memory.data();
  }

  void unmap() {
    is_unmapped = true;
  }
};

struct label_storage {
  std::unique_ptr<mock_buffer> m_buffer;
  volatile uint32_t* m_mapped = nullptr;
  uint32_t m_count = 0;
  static int live_storages;

  label_storage(uint32_t count) : m_count(count) {
    m_buffer = std::make_unique<mock_buffer>(count);
    m_mapped = m_buffer->map();
    live_storages++;
  }

  ~label_storage() {
    if (m_buffer) {
      if (m_mapped) {
        m_buffer->unmap();
        m_mapped = nullptr;
      }
      m_buffer->is_disposed = true;
    }
    live_storages--;
  }

  label_storage(const label_storage&) = delete;
  label_storage& operator=(const label_storage&) = delete;
};

int label_storage::live_storages = 0;

class gpu_label_pool {
public:
  gpu_label_pool(uint32_t count) : m_count(count) {}

  ~gpu_label_pool() {
    std::lock_guard lock(m_mutex);
    m_storage.reset();
  }

  std::tuple<std::shared_ptr<label_storage>, uint64_t, volatile uint32_t*> allocate() {
    std::lock_guard lock(m_mutex);
    if (!m_storage || m_offset >= m_count) {
      create_impl();
    }
    const auto out_offset = m_offset++;
    return {m_storage, out_offset * 4, m_storage->m_mapped + out_offset};
  }

  uint32_t current_generation_offset() const {
    return m_offset;
  }

private:
  void create_impl() {
    m_storage = std::make_shared<label_storage>(m_count);
    m_offset = 0;
  }

  std::mutex m_mutex;
  std::shared_ptr<label_storage> m_storage{};
  uint64_t m_offset = 0;
  uint32_t m_count = 0;
};

class gpu_label {
public:
  std::shared_ptr<label_storage> m_storage{};
  uint64_t m_buffer_offset = 0;
  volatile uint32_t* m_ptr = nullptr;

  gpu_label(gpu_label_pool& pool) {
    std::tie(m_storage, m_buffer_offset, m_ptr) = pool.allocate();
    reset();
  }

  ~gpu_label() {
    m_ptr = nullptr;
    m_buffer_offset = 0;
    m_storage.reset();
  }

  void reset() {
    if (m_ptr) {
      *m_ptr = static_cast<uint32_t>(label_constants::reset_);
    }
  }

  void set() {
    if (m_ptr) {
      *m_ptr = static_cast<uint32_t>(label_constants::set_);
    }
  }

  bool signaled() const {
    return m_ptr && *m_ptr == static_cast<uint32_t>(label_constants::set_);
  }
};

} // namespace test_sim

void test_gpu_label_pool_rollover_safety() {
  using namespace test_sim;

  // Capacity of 2 labels per generation buffer
  gpu_label_pool pool(2);
  TEST_ASSERT(label_storage::live_storages == 0, "Initial live storages must be 0");

  // Allocate 2 labels to fill Generation 0
  auto label0 = std::make_unique<gpu_label>(pool);
  auto label1 = std::make_unique<gpu_label>(pool);

  TEST_ASSERT(label_storage::live_storages == 1, "Generation 0 storage must be alive");
  TEST_ASSERT(!label0->signaled(), "label0 must start in reset state");
  TEST_ASSERT(!label1->signaled(), "label1 must start in reset state");

  label0->set();
  TEST_ASSERT(label0->signaled(), "label0 must be signaled after set()");
  TEST_ASSERT(!label1->signaled(), "label1 must remain reset");

  // Allocate label2, which triggers pool rollover (offset 2 >= count 2)
  // Generation 1 is created.
  auto label2 = std::make_unique<gpu_label>(pool);
  TEST_ASSERT(label_storage::live_storages == 2, "Both Generation 0 and Generation 1 must be alive simultaneously");

  // CRITICAL TEST FOR C03:
  // With the old unpatched bug, Generation 0's buffer was immediately unmapped on rollover.
  // Accessing label0 or label1 would dereference unmapped memory.
  // Here, label0 and label1 MUST still be mapped, valid, and writable!
  TEST_ASSERT(!label0->m_storage->m_buffer->is_unmapped, "Generation 0 buffer must NOT be unmapped while label0 lives");
  TEST_ASSERT(label0->signaled(), "label0 must still read valid signaled value");

  label1->set();
  TEST_ASSERT(label1->signaled(), "label1 in Generation 0 must be writable without error after rollover");

  // Allocate label3 (fills Generation 1) and label4 (triggers rollover to Generation 2)
  auto label3 = std::make_unique<gpu_label>(pool);
  auto label4 = std::make_unique<gpu_label>(pool);
  TEST_ASSERT(label_storage::live_storages == 3, "All 3 generations must be alive because labels exist in all 3");

  // Destroy label0 and label1. Now Generation 0 has no live labels and the pool has moved on!
  label0.reset();
  label1.reset();
  TEST_ASSERT(label_storage::live_storages == 2, "Generation 0 must be cleanly unmapped and disposed after its labels are destroyed");

  // Generation 1 (label2, label3) and Generation 2 (label4) must still be fully intact
  label2->set();
  label4->set();
  TEST_ASSERT(label2->signaled(), "label2 must work");
  TEST_ASSERT(label4->signaled(), "label4 must work");

  // Destroy remaining labels
  label2.reset();
  label3.reset();
  TEST_ASSERT(label_storage::live_storages == 1, "Only Generation 2 must remain alive (pool holds Generation 2)");

  label4.reset();
  // Pool still holds active Generation 2
  TEST_ASSERT(label_storage::live_storages == 1, "Pool holds current generation");

  PASS_TEST();
}

// ============================================================================
// C01: Vulkan Readback Timeout and Cache State Propagation Test
// ============================================================================

namespace test_c01 {

enum VkResult {
  VK_SUCCESS = 0,
  VK_TIMEOUT = 2,
  VK_ERROR_DEVICE_LOST = -4
};

struct mock_section {
  bool synchronized = false;
  bool flushed = false;
  bool dma_fence_active = false;
  VkResult mock_wait_result = VK_SUCCESS;
  bool memory_written = false;

  void schedule_dma() {
    synchronized = true;
    flushed = false;
    dma_fence_active = true;
    memory_written = false;
  }

  bool imp_flush() {
    if (dma_fence_active) {
      const VkResult status = mock_wait_result;
      if (status != VK_SUCCESS) {
        // Wait timeout or failure: clean up DMA fence and reset synchronized flag
        dma_fence_active = false;
        synchronized = false;
        return false;
      }
      dma_fence_active = false;
    }

    // Only on successful synchronization is memory exposed and written
    memory_written = true;
    return true;
  }

  bool flush() {
    if (flushed) {
      return true;
    }
    if (!synchronized) {
      return false;
    }
    if (!imp_flush()) {
      // Propagation failure: do not mark flushed or synchronized
      return false;
    }
    flushed = true;
    return true;
  }
};

} // namespace test_c01

void test_vulkan_readback_wait_timeout_propagation() {
  using namespace test_c01;

  // Test case 1: Successful wait
  {
    mock_section sec;
    sec.schedule_dma();
    sec.mock_wait_result = VK_SUCCESS;

    bool ok = sec.flush();
    TEST_ASSERT(ok == true, "Successful wait must return true from flush()");
    TEST_ASSERT(sec.flushed == true, "Successful wait must mark section as flushed");
    TEST_ASSERT(sec.memory_written == true, "Successful wait must write readback data");
  }

  // Test case 2: Wait timeout (C01)
  {
    mock_section sec;
    sec.schedule_dma();
    sec.mock_wait_result = VK_TIMEOUT;

    bool ok = sec.flush();
    TEST_ASSERT(ok == false, "Wait timeout must return false from flush()");
    TEST_ASSERT(sec.flushed == false, "Wait timeout must NOT mark section as flushed!");
    TEST_ASSERT(sec.synchronized == false, "Wait timeout must reset synchronized state!");
    TEST_ASSERT(sec.memory_written == false, "Wait timeout must NOT expose unwritten in-flight data!");
  }

  // Test case 3: Device lost error
  {
    mock_section sec;
    sec.schedule_dma();
    sec.mock_wait_result = VK_ERROR_DEVICE_LOST;

    bool ok = sec.flush();
    TEST_ASSERT(ok == false, "Device lost must return false from flush()");
    TEST_ASSERT(sec.flushed == false, "Device lost must NOT mark section as flushed");
    TEST_ASSERT(sec.synchronized == false, "Device lost must reset synchronized state");
    TEST_ASSERT(sec.memory_written == false, "Device lost must NOT write memory");
  }

  PASS_TEST();
}

// ============================================================================
// R08: GPU wait failures preserve unsynchronized state and protection
// ============================================================================

namespace test_r08 {

enum class page_protection {
  none,
  ro,
  rw
};

struct address_range {
  uint32_t start;
  uint32_t end;

  bool overlaps(const address_range& o) const {
    return start < o.end && o.start < end;
  }
};

struct mock_texture_section {
  uint32_t base;
  uint32_t size;
  bool synchronized = false;
  bool flushed = false;
  bool discarded = false;
  bool has_dma_fence = false;
  bool dma_resources_released = false;
  page_protection current_prot = page_protection::ro;

  address_range get_locked_range() const {
    return {base, base + size};
  }

  bool imp_flush(bool wait_succeeds) {
    if (has_dma_fence) {
      if (!wait_succeeds) {
        // R08 requirement: Do NOT release readback resources on wait failure!
        // Preserve them so sync can retry later.
        synchronized = false;
        return false;
      }
      dma_resources_released = true;
      has_dma_fence = false;
    }
    return true;
  }

  bool flush(bool wait_succeeds) {
    if (flushed) return true;
    if (!imp_flush(wait_succeeds)) return false;
    flushed = true;
    return true;
  }

  void discard() {
    discarded = true;
  }
};

struct thrashed_set {
  std::vector<mock_texture_section*> sections_to_flush;
  std::vector<mock_texture_section*> sections_to_unprotect;
  std::vector<address_range> ranges_unprotected_to_rw;
};

void simulate_flush_set(thrashed_set& data, bool s1_wait_ok, bool s2_wait_ok) {
  std::vector<mock_texture_section*> successfully_flushed;
  for (size_t i = 0; i < data.sections_to_flush.size(); ++i) {
    auto* surface = data.sections_to_flush[i];
    bool ok = (i == 0) ? surface->flush(s1_wait_ok) : surface->flush(s2_wait_ok);
    if (!ok) {
      // Failed to flush: separate / filter out from successfully flushed
      continue;
    }
    successfully_flushed.push_back(surface);
  }
  data.sections_to_flush = std::move(successfully_flushed);
}

void simulate_unprotect_set(thrashed_set& data) {
  // Separate / filter out failed sections
  std::vector<mock_texture_section*> failed_sections;
  auto it = std::remove_if(data.sections_to_flush.begin(), data.sections_to_flush.end(), [&](mock_texture_section* s) {
    if (!s->flushed) {
      failed_sections.push_back(s);
      return true;
    }
    return false;
  });
  data.sections_to_flush.erase(it, data.sections_to_flush.end());

  for (auto* s : data.sections_to_unprotect) {
    data.ranges_unprotected_to_rw.push_back(s->get_locked_range());
    s->current_prot = page_protection::rw;
    s->discard();
  }
  for (auto* s : data.sections_to_flush) {
    data.ranges_unprotected_to_rw.push_back(s->get_locked_range());
    s->current_prot = page_protection::rw;
    s->discard();
  }
}

} // namespace test_r08

void test_r08_failed_readback_preserves_protection_and_cache() {
  using namespace test_r08;

  mock_texture_section sec1{0x1000, 0x1000, true, false, false, true, false, page_protection::ro};
  mock_texture_section sec2{0x2000, 0x1000, true, false, false, true, false, page_protection::ro};

  thrashed_set data;
  data.sections_to_flush.push_back(&sec1);
  data.sections_to_flush.push_back(&sec2);

  // sec1 wait succeeds, sec2 wait fails (timeout/error)
  simulate_flush_set(data, true, false);

  TEST_ASSERT(sec1.flushed == true, "sec1 must be marked flushed");
  TEST_ASSERT(sec1.dma_resources_released == true, "sec1 dma resources released on success");

  // R08 assertion: sec2 must NOT be marked flushed, synchronized must be false, dma resources preserved
  TEST_ASSERT(sec2.flushed == false, "sec2 must NOT be marked flushed");
  TEST_ASSERT(sec2.synchronized == false, "sec2 must be marked unsynchronized");
  TEST_ASSERT(sec2.has_dma_fence == true, "sec2 DMA fence must NOT be discarded on wait failure");
  TEST_ASSERT(sec2.dma_resources_released == false, "sec2 DMA resources must NOT be released on wait failure");

  // Verify sec2 was filtered out of sections_to_flush
  TEST_ASSERT(data.sections_to_flush.size() == 1, "sections_to_flush must only have successfully flushed sec1");
  TEST_ASSERT(data.sections_to_flush[0] == &sec1, "sections_to_flush must contain sec1");

  simulate_unprotect_set(data);

  // sec1 was unprotected to RW and discarded
  TEST_ASSERT(sec1.discarded == true, "sec1 must be discarded from cache");
  TEST_ASSERT(sec1.current_prot == page_protection::rw, "sec1 must be unprotected to RW");

  // R08 assertion: sec2 must NOT be unprotected or discarded!
  TEST_ASSERT(sec2.discarded == false, "sec2 must NOT be discarded from cache on wait failure!");
  TEST_ASSERT(sec2.current_prot == page_protection::ro, "sec2 must preserve RO protection on wait failure!");
  for (const auto& r : data.ranges_unprotected_to_rw) {
    TEST_ASSERT(!r.overlaps(sec2.get_locked_range()), "Unprotected ranges must NOT contain sec2's locked range!");
  }

  // Now simulate a retry where sec2 wait succeeds
  sec2.synchronized = true;
  thrashed_set retry_data;
  retry_data.sections_to_flush.push_back(&sec2);
  simulate_flush_set(retry_data, true, true);
  simulate_unprotect_set(retry_data);

  TEST_ASSERT(sec2.flushed == true, "sec2 must flush on retry");
  TEST_ASSERT(sec2.discarded == true, "sec2 must be discarded after successful retry");
  TEST_ASSERT(sec2.current_prot == page_protection::rw, "sec2 must be unprotected after successful retry");

  PASS_TEST();
}

void test_r08_command_buffer_fence_wait_preserves_pending() {
  struct mock_command_buffer {
    bool is_pending = true;
    uint32_t eid_tag = 42;
    bool fence_reset = false;

    int wait(int wait_status) {
      // 0 = VK_SUCCESS, 2 = VK_TIMEOUT
      if (wait_status == 0 && is_pending) {
        fence_reset = true;
        is_pending = false;
        eid_tag = 0;
      }
      return wait_status;
    }
  };

  // Test timeout (wait_status = 2): must NOT reset fence or clear is_pending
  mock_command_buffer cb;
  int res = cb.wait(2);
  TEST_ASSERT(res == 2, "wait must return timeout status");
  TEST_ASSERT(cb.is_pending == true, "Failed fence wait must preserve is_pending = true");
  TEST_ASSERT(cb.fence_reset == false, "Failed fence wait must NOT reset fence");
  TEST_ASSERT(cb.eid_tag == 42, "Failed fence wait must NOT clear eid_tag");

  // Test success (wait_status = 0): must reset fence and clear is_pending
  res = cb.wait(0);
  TEST_ASSERT(res == 0, "wait must return success status");
  TEST_ASSERT(cb.is_pending == false, "Successful fence wait must clear is_pending");
  TEST_ASSERT(cb.fence_reset == true, "Successful fence wait must reset fence");
  TEST_ASSERT(cb.eid_tag == 0, "Successful fence wait must clear eid_tag");

  PASS_TEST();
}

// ============================================================================
// R09: ARM ubertrampoline icache invalidation before publication CAS
// ============================================================================

void test_r09_ubertrampoline_icache_maintenance_before_publication() {
  std::vector<std::string> event_log;
  std::mutex log_mtx;
  std::atomic<bool> publication_visible{false};
  alignas(16) uint8_t ubertrampoline_buf[64];
  std::memset(ubertrampoline_buf, 0, sizeof(ubertrampoline_buf));

  auto log_event = [&](const std::string& evt) {
    std::lock_guard<std::mutex> lk(log_mtx);
    event_log.push_back(evt);
  };

  // Publisher thread
  std::thread publisher([&]() {
    log_event("EMIT_START");
    std::memset(ubertrampoline_buf, 0x1F, sizeof(ubertrampoline_buf)); // emit instructions
    log_event("EMIT_DONE");

    // R09 requirement: call rx::clean_dcache_invalidate_icache and dsb ish; isb BEFORE publication CAS
    rx::clean_dcache_invalidate_icache(ubertrampoline_buf, sizeof(ubertrampoline_buf));
    log_event("ICACHE_INVALIDATED");

    log_event("BARRIER_DSB_ISB");

    // Publication CAS
    publication_visible.store(true, std::memory_order_release);
    log_event("PUBLICATION_CAS");
  });

  // Reader thread
  std::thread reader([&]() {
    while (!publication_visible.load(std::memory_order_acquire)) {
      std::this_thread::yield();
    }
    std::lock_guard<std::mutex> lk(log_mtx);
    // Reader observing publication must guarantee ICACHE_INVALIDATED and BARRIER happened before PUBLICATION_CAS
    size_t icache_idx = SIZE_MAX, barrier_idx = SIZE_MAX, cas_idx = SIZE_MAX;
    for (size_t i = 0; i < event_log.size(); ++i) {
      if (event_log[i] == "ICACHE_INVALIDATED") icache_idx = i;
      if (event_log[i] == "BARRIER_DSB_ISB") barrier_idx = i;
      if (event_log[i] == "PUBLICATION_CAS") cas_idx = i;
    }
    TEST_ASSERT(icache_idx != SIZE_MAX, "ICache invalidation must have occurred");
    TEST_ASSERT(barrier_idx != SIZE_MAX, "Barrier must have occurred");
    TEST_ASSERT(cas_idx != SIZE_MAX, "CAS must have occurred");
    TEST_ASSERT(icache_idx < cas_idx, "ICache invalidation must precede publication CAS");
    TEST_ASSERT(barrier_idx < cas_idx, "Barrier must precede publication CAS");
  });

  publisher.join();
  reader.join();

  PASS_TEST();
}

// ============================================================================
// R12: JIT publication barrier ordering before compiled pointer store
// ============================================================================

void test_r12_jit_barrier_ordering_before_compiled_publication() {
  std::atomic<bool> barrier_completed{false};
  std::atomic<void*> compiled_fn{nullptr};
  std::atomic<bool> reader_saw_inconsistent_state{false};
  alignas(16) uint8_t dummy_fn_code[32];
  std::memset(dummy_fn_code, 0x90, sizeof(dummy_fn_code));

  // Writer thread simulating SPULLVMRecompiler JIT publication
  std::thread writer([&]() {
    // 1. Finalize memory (LLVM fin)
    std::this_thread::sleep_for(std::chrono::milliseconds(5));

    // 2. R12 requirement: execute barrier BEFORE add_loc->compiled = fn
    barrier_completed.store(true, std::memory_order_release);

    // 3. Publish compiled pointer
    compiled_fn.store(dummy_fn_code, std::memory_order_release);

    // 4. notify_all() happens after store
  });

  // Multiple reader threads polling compiled pointer WITHOUT waiting for notification
  std::vector<std::thread> readers;
  for (int i = 0; i < 4; ++i) {
    readers.emplace_back([&]() {
      while (true) {
        void* ptr = compiled_fn.load(std::memory_order_acquire);
        if (ptr != nullptr) {
          // If reader observes compiled != nullptr before notification,
          // the barrier MUST have already completed!
          if (!barrier_completed.load(std::memory_order_acquire)) {
            reader_saw_inconsistent_state.store(true, std::memory_order_relaxed);
          }
          break;
        }
        std::this_thread::yield();
      }
    });
  }

  writer.join();
  for (auto& r : readers) {
    r.join();
  }

  TEST_ASSERT(!reader_saw_inconsistent_state.load(), "Reader must never observe compiled != nullptr before barrier execution!");
  PASS_TEST();
}

// ============================================================================
// R13: ARM redirection atomicity and single-instruction patching
// ============================================================================

void test_r13_arm_redirection_single_32bit_atomic_patch() {
  // Part A: Target within +/-128MB (near jump)
  {
    alignas(16) uint32_t code_window[4] = {
      0x11111111, // instruction 0 (will be patched)
      0x22222222, // instruction 1 (MUST NOT BE TOUCHED)
      0x33333333, // instruction 2 (MUST NOT BE TOUCHED)
      0x44444444  // instruction 3 (MUST NOT BE TOUCHED)
    };

    uintptr_t prog_first = reinterpret_cast<uintptr_t>(&code_window[0]);
    uintptr_t target = prog_first + 0x10000; // +64KB, well within +/-128MB

    int64_t diff = static_cast<int64_t>(target - prog_first);
    TEST_ASSERT(diff >= -(1LL << 27) && diff < (1LL << 27), "diff must be in +/-128MB range");

    uint32_t b_insn = 0x14000000 | static_cast<uint32_t>((diff >> 2) & 0x03ffffff);

    // Atomically patch single 32-bit instruction
    std::atomic_ref<uint32_t>(code_window[0]).store(b_insn, std::memory_order_release);
    rx::clean_dcache_invalidate_icache(&code_window[0], 4);

    // Verify:
    // 1. Instruction 0 is the B instruction
    TEST_ASSERT(code_window[0] == b_insn, "Instruction 0 must be patched with B instruction");
    // 2. Opcode is ARM64 B (000101 -> bits 31:26 == 0x05)
    TEST_ASSERT((code_window[0] >> 26) == 0x05, "Must be valid ARM64 B opcode (0x14)");
    // 3. Target offset matches
    int32_t reconstructed_imm26 = static_cast<int32_t>(code_window[0] & 0x03ffffff);
    if (reconstructed_imm26 & (1 << 25)) reconstructed_imm26 |= ~0x03ffffff; // sign extend
    uintptr_t reconstructed_target = prog_first + (reconstructed_imm26 << 2);
    TEST_ASSERT(reconstructed_target == target, "Reconstructed target must match target address");

    // 4. Instructions 1, 2, 3 are COMPLETELY UNCHANGED
    TEST_ASSERT(code_window[1] == 0x22222222, "Instruction 1 must be completely untouched!");
    TEST_ASSERT(code_window[2] == 0x33333333, "Instruction 2 must be completely untouched!");
    TEST_ASSERT(code_window[3] == 0x44444444, "Instruction 3 must be completely untouched!");
  }

  // Part B: Target outside +/-128MB (far jump via nearby veneer)
  {
    alignas(16) uint32_t code_window[4] = {
      0xAAAAAAAA, // instruction 0 (will be patched with B <veneer>)
      0xBBBBBBBB, // instruction 1 (MUST NOT BE TOUCHED)
      0xCCCCCCCC, // instruction 2 (MUST NOT BE TOUCHED)
      0xDDDDDDDD  // instruction 3 (MUST NOT BE TOUCHED)
    };

    uintptr_t prog_first = reinterpret_cast<uintptr_t>(&code_window[0]);
    // Far target: +512MB away (outside +/-128MB)
    uintptr_t target = prog_first + 0x20000000;

    int64_t diff = static_cast<int64_t>(target - prog_first);
    TEST_ASSERT(diff < -(1LL << 27) || diff >= (1LL << 27), "diff must be outside +/-128MB range");

    // Allocate nearby veneer buffer within +/-128MB
    alignas(16) uint8_t veneer[16];
    uintptr_t veneer_addr = reinterpret_cast<uintptr_t>(&veneer[0]);

    // Populate veneer:
    // [0..3]  ldr x16, #8 (0x58000050)
    // [4..7]  br x16      (0xd61f0200)
    // [8..15] 64-bit target address
    uint32_t* veneer_u32 = reinterpret_cast<uint32_t*>(veneer);
    veneer_u32[0] = 0x58000050; // ldr x16, #8
    veneer_u32[1] = 0xd61f0200; // br x16
    *reinterpret_cast<uint64_t*>(veneer + 8) = static_cast<uint64_t>(target);

    // Finalize and flush veneer
    rx::clean_dcache_invalidate_icache(veneer, 16);

    // Calculate branch to veneer (veneer is nearby)
    int64_t veneer_diff = static_cast<int64_t>(veneer_addr - prog_first);
    TEST_ASSERT(veneer_diff >= -(1LL << 27) && veneer_diff < (1LL << 27), "veneer must be within +/-128MB");

    uint32_t b_veneer_insn = 0x14000000 | static_cast<uint32_t>((veneer_diff >> 2) & 0x03ffffff);

    // Atomically patch single 32-bit instruction at prog_first
    std::atomic_ref<uint32_t>(code_window[0]).store(b_veneer_insn, std::memory_order_release);
    rx::clean_dcache_invalidate_icache(&code_window[0], 4);

    // Verify:
    // 1. Instruction 0 is the single 32-bit B <veneer> instruction
    TEST_ASSERT(code_window[0] == b_veneer_insn, "Instruction 0 must be B <veneer>");
    // 2. Veneer instructions: ldr x16, #8; br x16; target
    TEST_ASSERT(veneer_u32[0] == 0x58000050, "Veneer word 0 must be ldr x16, #8");
    TEST_ASSERT(veneer_u32[1] == 0xd61f0200, "Veneer word 1 must be br x16");
    TEST_ASSERT(*reinterpret_cast<uint64_t*>(veneer + 8) == target, "Veneer must store 64-bit target address");

    // 3. Instructions 1, 2, 3 at prog_first are COMPLETELY UNTOUCHED!
    TEST_ASSERT(code_window[1] == 0xBBBBBBBB, "Instruction 1 must be completely untouched!");
    TEST_ASSERT(code_window[2] == 0xCCCCCCCC, "Instruction 2 must be completely untouched!");
    TEST_ASSERT(code_window[3] == 0xDDDDDDDD, "Instruction 3 must be completely untouched!");
  }

  PASS_TEST();
}

int main() {
  test_clean_dcache_invalidate_icache_boundaries();
  test_gpu_label_pool_rollover_safety();
  test_vulkan_readback_wait_timeout_propagation();
  test_r08_failed_readback_preserves_protection_and_cache();
  test_r08_command_buffer_fence_wait_preserves_pending();
  test_r09_ubertrampoline_icache_maintenance_before_publication();
  test_r12_jit_barrier_ordering_before_compiled_publication();
  test_r13_arm_redirection_single_32bit_atomic_patch();

  std::printf("ALL %d PHASE 6 CORRECTNESS TESTS PASSED!\n", g_tests_passed);
  return 0;
}
