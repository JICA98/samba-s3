# SambaS3 Performance Baseline: God of War® III on OnePlus 13R

- **Date:** 2026-09-25
- **Document Role:** Authoritative Baseline Documentation (Work Plan Ticket B00 / E00)
- **Target Device:** OnePlus 13R (`CPH2691IN`, OP5D3BL1), Serial `d30a1726`
- **Workload:** *God of War® III* (`BCUS98111`, Disc v02.00) mounted via `direct_iso`
- **References:**
  - `docs/plans/gow3-optimization/samba-s3-optimization-review/WORKER.md` (Sections 1, 4, 15, 16)
  - `docs/plans/gow3-optimization/samba-s3-optimization-review/REVIEW.md` (Sections 1, 2, 9, 10)
  - `docs/benchmarks/2026-09-24-oneplus-13r-revised-gow3.md` (Revised benchmark report)

---

## 1. Pinned Source Revisions & Core Provenance

| Component | Pinned Identifier / Hash | Status / Tree State |
|---|---|---|
| **Frontend Repository** (`JICA98/samba-s3`) | `270d72a56a926375f16f6c9a22474acdda99cd8c` | `master` HEAD, clean working tree |
| **Backend Submodule** (`app/src/main/cpp/rpcsx`) | `ed8ba6c12c218249524a441b79f48d6bae842394` | `samba-android` HEAD, clean working tree |
| **Benchmark Embedded S3CORE ID** | `05ed8aed420f7cf4975835f4baebda91cec14748` | Ancestor of pinned backend commit |
| **Submodule Patch** (`patches/rpcsx-submodule-changes.patch`) | `none` (zero-diff / noop) | Applied/pinned in `samba-android` branch |
| **Libadrenotools Submodule** | `8fae8ce254dfc1344527e05301e43f37dea2df80` | `v1.0-18-g8fae8ce` pinned |

### Delta Analysis: Benchmark Core `05ed8aed4` vs Pinned Core `ed8ba6c12`

A strict git revision inspection between `05ed8aed420f7cf4975835f4baebda91cec14748` and `ed8ba6c12c218249524a441b79f48d6bae842394` reveals exactly one commit:

- **Commit:** `ed8ba6c12c218249524a441b79f48d6bae842394`
- **Author:** SambaS3 `<sambas3@example.com>`
- **Date:** Thu Sep 24 22:33:52 2026 +0530
- **Subject:** `rpcsx-android: load all yml/yaml patch files from patches directory`
- **File Changed:** `android/src/rpcsx-android.cpp` (20 insertions(+), 5 deletions(-))
  ```cpp
  static void load_all_patches(patch_engine::patch_map& full_patches) {
    const std::string patches_dir = patch_engine::get_patches_path();
    patch_engine::load(full_patches, patches_dir + "patch.yml");
    patch_engine::load(full_patches, patch_engine::get_imported_patch_path());

    fs::dir directory(patches_dir);
    if (directory) {
      for (const auto& entry : directory) {
        if (entry.is_directory || entry.name == "." || entry.name == "..") continue;
        if (entry.name == "patch.yml" || entry.name == "imported_patch.yml" || entry.name == "patch_config.yml") continue;
        if (entry.name.ends_with(".yml") || entry.name.ends_with(".yaml")) {
          patch_engine::load(full_patches, patches_dir + entry.name);
        }
      }
    }
  }
  ```
- **Impact Classification:**
  - **Performance:** None. `load_all_patches` is called strictly by JNI management routines (`_rpcsx_patchesList` and `_rpcsx_patchSetEnabledForTitle`) for UI settings interaction, never on the emulation, SPU/PPU dispatch, or RSX rendering critical paths.
  - **Correctness:** Improved patch discovery. Allows auxiliary `.yml` and `.yaml` patch files placed in the patches directory to be recognized and toggled.
  - **Patch-Manager-Only:** Yes. Confined purely to the patch engine enumeration interface.
  - **Build / Toolchain:** None. No CMake or compilation flag modifications.
  - **Binary & S3CORE Hash:** The embedded `S3CORE` identifier reflects `ed8ba6c...` instead of `05ed8aed...`, changing the resulting `librpcsx-android.so` SHA-256 digest from `30edf4a1...` to `571426c3...`.

---

## 2. On-Device Artifact & Library Verification (`d30a1726`)

The device environment and installed application binaries were verified via ADB:

| Verification Target | Value / State on OnePlus 13R | Matches Repository Release Artifact |
|---|---|---|
| **Package Name** | `com.zenithblue.sambas3` | Match |
| **Package Version** | `versionName=2026.09.17`, `versionCode=20260917` | Match |
| **SDK Targets** | `minSdk=29`, `targetSdk=37` | Match |
| **Signatures** | `PackageSignatures{c7da946 version:2, signatures:[8fc6fc9e]}` | Match |
| **Code Path** | `/data/app/~~ReeIKJ9aj1PquhxWJncQ4A==/com.zenithblue.sambas3-vBk5Ki0ojOX-Kl-HyhZwgg==` | N/A |
| **`base.apk` SHA-256** | `749d0a165041884ca90d93176b04c9805b64c131def29ab86e11bce63230138a` | **Exact match** (`app/build/outputs/apk/standard/release/samba-s3-standard-release.apk`) |
| **`librpcsx-android.so` SHA-256** | `571426c33f8677ebec0f5fcc26c1c32d5e6ac6e9bdd2422bc0b1f0edb6afab09` | **Exact match** (packaged arm64 library digest) |
| **Core Embedded Build ID (`S3CORE`)** | `rpcsx=ed8ba6c12c218249524a441b79f48d6bae842394 samba=e93784d8c4e4aac796b72a63a8f5a127affa29791b58b8a453aa74eae5c1460c patch_sha256=none build_type=RelWithDebInfo abi=arm64-v8a ndk=30.0.14904198-beta1 cmake=3.22.1 compiler=Clang_21.0.0 flags=bd8a4ec75966-OFF` | Verified from on-device binary and build manifest |
| **`verify-apk-core.sh` Status** | **`PASS`** — packaged core matches per-ABI provenance | Verification passes closed |

Installed native libraries in `/lib/arm64/`:
- `libandroidx.graphics.path.so` (10,096 B)
- `libfile_redirect_hook.so` (3,984 B)
- `libgsl_alloc_hook.so` (4,280 B)
- `libhook_impl.so` (105,304 B)
- `libmain_hook.so` (4,200 B)
- `librpcsx-android.so` (1,290,107,616 B, SHA-256 `571426c3...`)
- `libsambas3-android.so` (851,536 B)

---

## 3. Target Hardware & Platform Specification

| Attribute | Specification on Device `d30a1726` |
|---|---|
| **Product Model** | OnePlus 13R (`CPH2691`, hardware variant `CPH2691IN`, OP5D3BL1) |
| **SoC** | Qualcomm Snapdragon 8 Gen 3 (`SM8650`, platform `pineapple`, hardware `qcom`) |
| **CPU Cluster Configuration** | 8 Cores (1+5+2 Architecture):<br>• 1× Cortex-X4 @ 3.30 GHz (`cpu7`, Part `0xd82`)<br>• 5× Cortex-A720 @ 2.96–3.15 GHz (`cpu2–cpu6`, Part `0xd81`)<br>• 2× Cortex-A520 @ 2.27 GHz (`cpu0–cpu1`, Part `0xd80`) |
| **Affinity Discovery** | `allowed=0xff, perf=0xfc, eff=0x3, heterogeneous=true` |
| **GPU Hardware** | Adreno 750 (Qualcomm vendor ID `0x5143` / `20803`) |
| **System Memory (RAM)** | 12 GB LPDDR5X (10.96 GiB available to Linux kernel) |
| **OS / Platform** | Android 16 (Build `UKQ1.231108.001`), Linux kernel `6.1.174-gf870a38a2536` |
| **Hardware Counter (TSC)** | Invariant ARM Generic Timer frequency: **19.2 MHz** (`0.019 GHz`) |
| **Active Graphics Driver** | **Mesa Turnip 26.2.99** (Driver Label: `turnip-26.3`, Vulkan API 1.4.359) injected via `adrenotools` hook (`libvulkan_freedreno.so`) |
| **System Driver Baseline** | Adreno 750 Qualcomm proprietary driver v512.748.0 (API 1.3.280) |

---

## 4. Current Baseline Performance Metrics Summary

Workload: *God of War® III* (`BCUS98111`, Disc v02.00) via `direct_iso`.

### Progression & Scene Windows

1. **System & Warning Screens:** 60.0 FPS, 16.1–16.7 ms frametime.
2. **Mount Olympus Intro Fly-through (Cutscenes):** 28.0–42.3 FPS, 21.0–37.4 ms frametime.
3. **Mount Olympus Summit / Titan War:** 4.2–7.0 FPS, 118.6–310.6 ms frametime.
4. **Interactive 3D Gameplay on Gaia's Back:**
   - **Historical Baseline (`657b26a0`):** Mean 3.97 FPS (P50 4.10 FPS), Mean Frametime 390.5 ms; ended after 15.7s in RSX FIFO desync (`last cmd = 0x42ca685c`) and crash at unmapped address `0x37600000`.
   - **Post-Optimization Baseline (`05ed8aed4` / `ed8ba6c12`):**
     - **Matched Gameplay Window (~14s):**
       - Presentation FPS: **6.78 FPS Mean** (+70.8% over historical), **6.31 FPS Median (P50)** (+53.9%).
       - Interval Frametime: **168.7 ms Mean** (−56.8%), **188.7 ms Median (P50)** (−20.1%).
       - Stability: **0 FIFO desyncs, 0 access violations, 0 crashes**.
     - **Sustained Gameplay Window (~81s, 20 checkpoints):**
       - Presentation FPS: **7.44 FPS Mean**, **7.40 FPS Median (P50)**, **9.94 FPS Max**.
       - Interval Frametime: **140.5 ms Mean**, **142.6 ms Median (P50)**, **75.7 ms Min**.
       - Presented Frames: **3,588 frames**. Clean termination via `DEBUG_STOP_GAME`.

---

## 5. Critical Engineering Observations & Identified Bottlenecks

As detailed in `REVIEW.md` and `WORKER.md`:

1. **Hot Auxiliary Worker (`DefaultDispatch`):**
   In the raw thread snapshot, coroutine worker `DefaultDispatch` (TID 5498) consumes **85.7% CPU**, on par with the hottest SPU worker. Must be profiled with managed/native stacks to eliminate unnecessary UI/telemetry/logging overhead.
2. **Performance Core Contention (`0xFC` Affinity Mask):**
   The benchmark selected `RPCS3 Scheduler` with PPU, RSX, and 6 guest SPUs all pinned to performance cores `0xFC`. This creates heavy contention across the 6 performance CPUs. Capacity-aware scheduling and OS-managed scheduling must be benchmarked.
3. **SPU Architecture Difference:**
   `SPUThread.cpp` selects `make_llvm_recompiler()` (synchronous LLVM compilation) on ARM64, whereas x86-64 uses `make_fast_llvm_recompiler()`. Runtime compile misses cause substantial startup and scene-transition stalls on ARM.
4. **Thermal Throttling Reality:**
   Sustained emulation load drives the SoC to `ThermalStatus: 3` (Severe), with skin temperatures reaching 50.1°C / 58.3°C and CPU cores peaking near 95.0°C. Throttling is an active physical constraint on sustained 60 FPS.
