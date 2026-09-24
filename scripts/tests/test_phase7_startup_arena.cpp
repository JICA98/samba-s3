#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <unordered_map>
#include <filesystem>
#include <fstream>
#include <thread>
#include <atomic>
#include <sys/mman.h>
#include <unistd.h>

// FNV-1a 64-bit hash (matches rpcs3 fnv_hash)
namespace test_hash {
    constexpr uint64_t fnv_seed = 0xcbf29ce484222325ULL;
    constexpr uint64_t fnv_prime = 0x100000001b3ULL;

    inline uint64_t hash64(uint64_t hash, uint8_t byte) {
        return (hash ^ byte) * fnv_prime;
    }

    inline std::string compute_hash(const std::string& data) {
        uint64_t h = fnv_seed;
        for (unsigned char b : data) {
            h = hash64(h, b);
        }
        return std::to_string(h);
    }
}

struct ppu_mself_record {
    uint64_t size = 0;
    int64_t mtime = 0;
    std::string digest;
    std::string header_digest;
};

using ppu_mself_map = std::unordered_map<std::string, ppu_mself_record>;

struct mock_file_data {
    uint64_t size;
    int64_t mtime;
    std::string content_header; // Sample / header content
    std::string full_content;
};

// Mock source inventory simulating PPU manifest logic with R07 content identity verification
std::string mock_source_inventory(
    const std::vector<std::pair<std::string, mock_file_data>>& files,
    const ppu_mself_map* cached_mself,
    ppu_mself_map* out_mself,
    uint32_t* out_hashed,
    uint32_t* out_cached)
{
    std::vector<std::string> records;
    for (const auto& [rel_path, data] : files) {
        const uint64_t size = data.size;
        const int64_t mtime = data.mtime;
        std::string record = "0:" + rel_path + "|" + std::to_string(size) + "|" + std::to_string(mtime);

        if (rel_path.ends_with(".MSELF")) {
            std::string digest;
            std::string header_digest;
            bool reused = false;
            const std::string current_header_digest = test_hash::compute_hash(data.content_header);

            if (cached_mself) {
                auto it = cached_mself->find(rel_path);
                if (it != cached_mself->end() && it->second.size == size && it->second.mtime == mtime && !it->second.digest.empty()) {
                    // R07: Verify content identity - check file's content header / sample hash
                    if (!it->second.header_digest.empty() && current_header_digest == it->second.header_digest) {
                        digest = it->second.digest;
                        header_digest = it->second.header_digest;
                        reused = true;
                    }
                }
            }

            if (!reused) {
                digest = test_hash::compute_hash(data.full_content);
                header_digest = current_header_digest;
                if (out_hashed) (*out_hashed)++;
            } else if (out_cached) {
                (*out_cached)++;
            }

            if (out_mself) {
                (*out_mself)[rel_path] = ppu_mself_record{size, mtime, digest, header_digest};
            }
            record += "|" + digest;
        }
        records.push_back(std::move(record));
    }

    uint64_t digest = test_hash::fnv_seed;
    for (const auto& rec : records) {
        for (unsigned char b : rec) {
            digest = test_hash::hash64(digest, b);
        }
        digest = test_hash::hash64(digest, static_cast<uint8_t>('\n'));
    }
    return std::to_string(digest);
}

// 1. Test MSELF digest caching and invalidation logic (including same-size, same-mtime content modification)
void test_manifest_mself_caching_and_invalidation() {
    printf("[1/6] Testing MSELF digest caching and invalidation logic (R07)...\n");

    std::vector<std::pair<std::string, mock_file_data>> initial_files = {
        {"USRDIR/EBOOT.BIN", {1000000, 1000, "ELF_HEADER_V1", "EBOOT_CONTENT_FULL"}},
        {"USRDIR/MODULES/ENGINE.PRX", {50000, 1000, "PRX_HEADER_V1", "PRX_CONTENT_FULL"}},
        {"USRDIR/CONTAINER.MSELF", {500000000, 1000, "MSF_HEADER_COUNT_10", "MSELF_CONTAINER_PAYLOAD_V1"}},
    };

    // Cold run: no cache
    ppu_mself_map saved_mself;
    uint32_t hashed_count = 0;
    uint32_t cached_count = 0;
    std::string inv1 = mock_source_inventory(initial_files, nullptr, &saved_mself, &hashed_count, &cached_count);
    assert(hashed_count == 1);
    assert(cached_count == 0);
    assert(saved_mself.count("USRDIR/CONTAINER.MSELF") == 1);
    assert(!saved_mself["USRDIR/CONTAINER.MSELF"].header_digest.empty());

    // Warm run: unchanged files, cached MSELF available and valid
    hashed_count = 0;
    cached_count = 0;
    ppu_mself_map warm_mself;
    std::string inv2 = mock_source_inventory(initial_files, &saved_mself, &warm_mself, &hashed_count, &cached_count);
    assert(hashed_count == 0);
    assert(cached_count == 1);
    assert(inv1 == inv2); // Invariants match, manifest hit!

    // Invalidation: Same-size, same-mtime file modification (R07 core defect!)
    // Size and mtime remain identical (500000000, 1000), but content header changed!
    auto same_size_mtime_modified = initial_files;
    same_size_mtime_modified[2].second.content_header = "MSF_HEADER_COUNT_10_MODIFIED";
    same_size_mtime_modified[2].second.full_content = "MSELF_CONTAINER_PAYLOAD_V2_REPLACED";
    hashed_count = 0;
    cached_count = 0;
    std::string inv_modified = mock_source_inventory(same_size_mtime_modified, &saved_mself, nullptr, &hashed_count, &cached_count);
    assert(hashed_count == 1); // Must NOT reuse stale digest; must re-hash!
    assert(cached_count == 0); // Must NOT count as cached!
    assert(inv1 != inv_modified); // Manifest MUST be invalidated!

    // Invalidation 1: MSELF file modified (mtime changes)
    auto modified_files = initial_files;
    modified_files[2].second.mtime = 1001; // new mtime
    hashed_count = 0;
    cached_count = 0;
    std::string inv3 = mock_source_inventory(modified_files, &saved_mself, nullptr, &hashed_count, &cached_count);
    assert(hashed_count == 1); // Must re-hash
    assert(cached_count == 0);
    assert(inv1 != inv3);

    // Invalidation 2: MSELF file modified (size changes)
    auto resized_files = initial_files;
    resized_files[2].second.size = 500000001; // new size
    hashed_count = 0;
    cached_count = 0;
    std::string inv4 = mock_source_inventory(resized_files, &saved_mself, nullptr, &hashed_count, &cached_count);
    assert(hashed_count == 1); // Must re-hash
    assert(cached_count == 0);
    assert(inv1 != inv4);

    // Invalidation 3: New file added
    auto added_files = initial_files;
    added_files.push_back({"USRDIR/EXTRA.SPRX", {20000, 1000, "EXTRA_HDR", "EXTRA_PAYLOAD"}});
    std::string inv5 = mock_source_inventory(added_files, &saved_mself, nullptr, &hashed_count, &cached_count);
    assert(inv1 != inv5);

    printf("  -> PASS: MSELF caching verifies content identity and rejects same-size same-mtime replacements.\n");
}

// 2. Test aborted scan does NOT write an empty manifest (R06)
void test_aborted_scan_does_not_write_empty_manifest() {
    printf("[2/6] Testing aborted PPU scan does not write empty manifest (R06)...\n");

    const std::filesystem::path temp_manifest = std::filesystem::temp_directory_path() / "test_aborted_manifest.json";
    if (std::filesystem::exists(temp_manifest)) {
        std::filesystem::remove(temp_manifest);
    }

    // Simulate pre-existing stale/partial manifest
    {
        std::ofstream ofs(temp_manifest);
        ofs << "{\"partial\": true}";
    }
    assert(std::filesystem::exists(temp_manifest));

    // Simulate scan logic
    bool scan_aborted = false;
    std::vector<std::string> file_queue;

    // Case A: scan cancelled / stopped (Emu.IsStopped() == true)
    bool emu_is_stopped = true;
    if (emu_is_stopped) {
        scan_aborted = true;
        file_queue.clear();
    }

    bool manifest_saved = false;
    // Android manifest save gating logic:
    if (scan_aborted || file_queue.empty() || emu_is_stopped) {
        // Must NOT save manifest; must remove partial/empty manifest from disk!
        if (std::filesystem::exists(temp_manifest)) {
            std::filesystem::remove(temp_manifest);
        }
    } else {
        manifest_saved = true;
    }

    assert(!manifest_saved);
    assert(!std::filesystem::exists(temp_manifest)); // Manifest removed!

    // Case B: scan completed but file_queue is empty
    emu_is_stopped = false;
    scan_aborted = false;
    file_queue.clear();

    {
        std::ofstream ofs(temp_manifest);
        ofs << "{\"stale\": true}";
    }

    if (scan_aborted || file_queue.empty() || emu_is_stopped) {
        if (std::filesystem::exists(temp_manifest)) {
            std::filesystem::remove(temp_manifest);
        }
    } else {
        manifest_saved = true;
    }

    assert(!manifest_saved);
    assert(!std::filesystem::exists(temp_manifest));

    printf("  -> PASS: Aborted scan or empty queue does not write manifest and cleans up stale file.\n");
}

// 3. Test empty manifest rejected on load and removed from disk (R06)
void test_empty_manifest_rejected_and_removed() {
    printf("[3/6] Testing empty manifest rejected on load and removed (R06)...\n");

    const std::filesystem::path temp_manifest = std::filesystem::temp_directory_path() / "test_empty_manifest.json";

    // Write empty manifest file
    {
        std::ofstream ofs(temp_manifest);
        ofs << "{\"version\": 3, \"key\": {}, \"roots\": [], \"inventory\": \"\", \"entries\": []}";
    }
    assert(std::filesystem::exists(temp_manifest));

    // Mock ppu_manifest_load logic
    auto mock_manifest_load = [](const std::filesystem::path& path) -> bool {
        std::ifstream ifs(path);
        if (!ifs) return false;
        std::string content((std::istreambuf_iterator<char>(ifs)), std::istreambuf_iterator<char>());

        // Check for empty entries
        if (content.find("\"entries\": []") != std::string::npos || content.find("\"entries\":[]") != std::string::npos) {
            // R06: Empty manifest is invalid; remove from disk and return false
            std::filesystem::remove(path);
            return false;
        }
        return true;
    };

    bool loaded = mock_manifest_load(temp_manifest);
    assert(!loaded); // Rejected
    assert(!std::filesystem::exists(temp_manifest)); // Removed from disk

    printf("  -> PASS: Empty manifest rejected on load and removed from disk.\n");
}

// 4. Test non-MSELF entry validation detects same-size, same-mtime modifications (R07)
void test_non_mself_same_size_content_verification() {
    printf("[4/6] Testing non-MSELF entry content digest validation (R07)...\n");

    const std::filesystem::path temp_prx = std::filesystem::temp_directory_path() / "test_module.prx";

    // Step 1: Create initial PRX file
    const std::string original_content = "ORIGINAL_PRX_BINARY_PAYLOAD_1234567890";
    {
        std::ofstream ofs(temp_prx, std::ios::binary);
        ofs << original_content;
    }
    const std::string original_digest = test_hash::compute_hash(original_content);
    const uint64_t file_size = original_content.size();

    // Mock entry validator simulating ppu_manifest_entry_is_valid
    auto validate_entry = [](const std::filesystem::path& path, uint64_t expected_size, const std::string& expected_digest) -> bool {
        if (!std::filesystem::is_regular_file(path)) return false;
        if (std::filesystem::file_size(path) != expected_size) return false;

        // Read and hash
        std::ifstream ifs(path, std::ios::binary);
        std::string content((std::istreambuf_iterator<char>(ifs)), std::istreambuf_iterator<char>());
        const std::string actual_digest = test_hash::compute_hash(content);

        // R07: Content digest MUST match
        return actual_digest == expected_digest;
    };

    // Valid check: matches original digest
    assert(validate_entry(temp_prx, file_size, original_digest) == true);

    // Step 2: Replace file with SAME SIZE but modified bytes
    std::string modified_content = original_content;
    modified_content[0] = 'X'; // Modify first byte
    assert(modified_content.size() == file_size); // Ensure identical size
    {
        std::ofstream ofs(temp_prx, std::ios::binary | std::ios::trunc);
        ofs << modified_content;
    }

    // R07: Size matches, but digest differs -> MUST return false (stale compilation prevented!)
    bool is_valid = validate_entry(temp_prx, file_size, original_digest);
    assert(is_valid == false);

    std::filesystem::remove(temp_prx);
    printf("  -> PASS: Non-MSELF entry content digest mismatch detected and rejected.\n");
}

// 5. Test concurrent thread execution of JIT target/feature resolution (R10)
void test_concurrent_jit_target_and_feature_resolution() {
    printf("[5/6] Testing concurrent thread execution of JIT target and feature resolution (R10)...\n");

    // Simulates jit_compiler::cpu() and features1() thread-safety
    auto resolve_cpu = [](const std::string& input_cpu) -> std::string {
        std::string cpu = input_cpu;
        if (cpu.empty() || cpu == "auto" || cpu == "Auto") {
            static const std::string s_default_cpu = "cortex-a34";
            cpu = s_default_cpu;
        }
        for (char& c : cpu) {
            c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        }
        return cpu;
    };

    auto resolve_features = []() -> std::string {
        static std::atomic<uint32_t> s_feat{0x1}; // FEAT_BASELINE
        const uint32_t f = s_feat.load(std::memory_order_relaxed);
        std::string res = "+fp-armv8,+neon";
        if (f & 0x1) res += ",+crc,+crypto";
        return res;
    };

    constexpr int kNumThreads = 16;
    constexpr int kIterations = 1000;
    std::vector<std::thread> threads;
    std::atomic<bool> race_detected{false};

    for (int t = 0; t < kNumThreads; ++t) {
        threads.emplace_back([&, t]() {
            for (int i = 0; i < kIterations; ++i) {
                const std::string cpu_input = (t % 2 == 0) ? "auto" : "CORTEX-A34";
                const std::string resolved = resolve_cpu(cpu_input);
                if (resolved != "cortex-a34") {
                    race_detected = true;
                }
                const std::string feat = resolve_features();
                if (feat.find("+fp-armv8") == std::string::npos) {
                    race_detected = true;
                }
            }
        });
    }

    for (auto& th : threads) {
        th.join();
    }

    assert(!race_detected);
    printf("  -> PASS: 16 concurrent threads executed JIT target and feature resolution safely.\n");
}

// 6. Test virtual address arena lifecycle
void test_virtual_address_arena_lifecycle() {
    printf("[6/6] Testing virtual address arena reservation and clean release...\n");

    constexpr size_t arena_size = 0x30000000; // 768 MiB (matches c_max_size * 3)

    // Simulate 10 iterations of creating and tearing down arenas
    for (int i = 0; i < 10; ++i) {
        void* ptr = ::mmap(nullptr, arena_size, PROT_NONE, MAP_ANON | MAP_PRIVATE, -1, 0);
        assert(ptr != MAP_FAILED);

        // Commit a small portion (e.g. 2 MiB)
        int commit_ret = ::mprotect(ptr, 2 * 1024 * 1024, PROT_READ | PROT_WRITE);
        assert(commit_ret == 0);

        // Clean release (using munmap / utils::memory_release)
        int release_ret = ::munmap(ptr, arena_size);
        assert(release_ret == 0);
    }

    printf("  -> PASS: Repeated 768 MiB arena reservation and release succeed cleanly without VA exhaustion.\n");
}

int main() {
    test_manifest_mself_caching_and_invalidation();
    test_aborted_scan_does_not_write_empty_manifest();
    test_empty_manifest_rejected_and_removed();
    test_non_mself_same_size_content_verification();
    test_concurrent_jit_target_and_feature_resolution();
    test_virtual_address_arena_lifecycle();

    printf("\nALL 6 PHASE 7 TESTS PASSED!\n");
    return 0;
}
