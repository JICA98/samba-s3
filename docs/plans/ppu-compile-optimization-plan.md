# Plan: ppu-compile-optimization

> Samba S3 PPU compilation acceleration — port upstream concurrency fixes, eliminate ARM64 wasted work, add Android-aware scheduling, avoid runtime compilation via pre-discovery, and benchmark codegen/verify/compression. Based on pinned RPCSX `e8ae1481` (LLVM 20.1.3, 30-bucket JIT manager, x64-only EarlyCSE) vs current RPCS3 (LLVM 22.1, 256 buckets, #18774 locking fix).

## Task Summary

Reduce **PPU LLVM compilation time** in two stages:

1. **Pre-runtime** (game import / `ppu_precompile` / `ppu_initialize` cold cache build) — wall-clock completion speed.
2. **Runtime** (PRX/overlay/self discovered mid-gameplay) — eliminate stalls rather than merely accelerating them.

The biggest wins are **concurrency/scheduling, avoiding unnecessary work, and preventing runtime compilation**, not adding more LLVM optimization passes. Upstream RPCS3 #18774 (July 1, 2026 — `jit_allocator` locking + 30→256 buckets + caller-thread recycling) is the most obvious missing optimization. Samba can additionally outperform desktop RPCS3 with Android big.LITTLE-aware worker scheduling, verification of already-gated ARM64 PPU pass setup, and aggressive executable manifest pre-discovery (RPCS3 issue #16883). Lower-priority experiments benchmark the SPU ARM64 pass pipeline, cheaper `CodeGenOptLevel`, `verifyModule`, and cache compression; tiered Interpreter→LLVM and function deduplication are R&D.

Non-goals (phase 1): blind LLVM 20→22 upgrade (macOS ARM64 segfault regression #19103), enabling `EarlyCSE` on ARM64 (RPCS3 closed — 4:51→5:32 regression, no size win), or introducing new LLVM passes.

## Research Sources

**Local repo evidence (read/grep):**

- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUThread.cpp:5840-5865>` — `ppu_initialize2()` constructs `LoopAnalysisManager, FunctionAnalysisManager, CGSCCAnalysisManager, ModuleAnalysisManager, PassBuilder` and `EarlyCSEPass()` **guarded by `#ifdef ARCH_X64` for the whole block** (5840 `#ifdef ARCH_X64` ... 5865 `#endif` with guarded `fpm.run` at 5891-5894/5909-5912). PPU ARM64 wasted-work is already compiled out — verification-only.
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/SPULLVMRecompiler.cpp:2705-2733>` — **unconditional** on ARM64: `LoopAnalysisManager lam; FunctionAnalysisManager fam; CGSCCAnalysisManager cgam; ModuleAnalysisManager mam; PassBuilder pb; pb.register* ; fpm.addPass(EarlyCSEPass(true))` at 2728 plus `2729 SimplifyCFGPass`, `2730 DSEPass`, `2731 LICM` (`createFunctionToLoopPassAdaptor(LICMPass(LICMOptions()),true)`), `2732 ADCEPass` — 5 passes, not just EarlyCSE. Actual verify via `grep -n "addPass" SPULLVMRecompiler.cpp:2728-2732` (see also `LICMPass` alias). This is the ARM64 benchmark candidate; no removal is approved without compile/runtime evidence.
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUThread.cpp:3816-3852>` — `jit_core_allocator { thread_count, sem, shared_mtx }` and `jit_module_manager { std::array<bucket_t, 30> buckets; bucket_t& get_bucket(hash % 30) }` — current pinned state (30 buckets).
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUThread.cpp:5533-5622>` — PPU worker `thread_op`: `scoped_priority(-1)` low priority, `core_lock` semaphore taken in `named_thread_group` predicate, `shared_lock` path, `jit_compiler jit2({}, g_cfg.llvm_cpu, 0x1)` per-module created/destroyed, `work_cv` atomic index.
- `<source: app/src/main/cpp/rpcsx/rpcs3/util/JITLLVM.cpp:697>` — `setOptLevel(llvm::CodeGenOptLevel::Aggressive)` — hardest codegen level for every module.
- `<source: app/src/main/cpp/rpcsx/rpcs3/util/JITLLVM.cpp:400-512>` — `ObjectCache::notifyObjectCompiled` compresses via `zip()` (non-multithreaded default) and `ObjectCache::load` decompresses via `unzip()` per-module `.gz`.
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUThread.cpp:5928-5948>` — `verifyModule(*_module, &out)` at 5939 gated `#ifndef ANDROID` starting at 5928 — on Android already skipped (so verification overhead is desktop-only). Note discrepancy vs prompt. Acceptance grep must check guard line 5928 and call line 5939.
- `<source: app/src/main/cpp/rpcsx/3rdparty/llvm/CMakeLists.txt:3,15-16>` — `USE_LLVM_VERSION 20.1.3`, `LLVM_TARGETS_TO_BUILD "AArch64"` only on Android — stripping already done.
- `<source: app/src/main/cpp/native-lib.cpp:18-54>` — JNI bridge resolves ~25 symbols, downloads `.so` separately, `dlopen` at runtime.
- `<source: patches/rpcsx-submodule-changes.patch>` — local Android patches already maintain `atomic-file-copier`, `iso-install-manifest`, `staged-game-installer`; must extend for PPU changes.
- `<source: .gitmodules:1-3>` — submodule `app/src/main/cpp/rpcsx` → `https://github.com/RPCSX/rpcsx.git` at `e8ae1481ab7ba04d5c6bef89dd852aabba2c88ff` (`git log --oneline -5`).
- `<source: bash: grep -rn "g_cfg.core.llvm_threads" rpcs3/Emu/Cell/PPUThread.cpp:3818,4278,5517>` — `llvm_threads` config drives `thread_count = min(llvm_threads, limit())`, `get_max_threads()` for workers, `software_thread_limit = min(llvm_threads, file_queue.size)`.

**External / upstream (prompt-cited + GitHub, verified via `/tmp/opencode/rpcs3` clone 2026-08-24):**

- `<source: https://github.com/RPCS3/rpcs3/blob/master/3rdparty/llvm/CMakeLists.txt>` — RPCS3 master uses LLVM 22.1, Android-only `AArch64` already (no extra strip benefit).
- `<source: https://github.com/RPCS3/rpcs3/pull/18774>` — "PPU LLVM: Fix core_lock locking" merged July 1, 2026. Verified 3 commits on `refs/pull/18774/head` (`pr-18774`): `53fefc82c514ad92f09b4358074d3d090c222e88` (Fix core_lock locking — fixes `named_thread_group` predicate double-check + unlock), `503131c8f989b8ea337dce626121d110b34299df` (Fix concurrency weakness of jit_module_manager — 30→256 buckets, `rpcs3::hash_array` + `sv.size()` xor, removal of `shared_mtx`), `356a3a481c1ee9344fa9ee65f94c07c50d665b82` (Recycle current thread for execution — `thread_count = max(min(workload, get_max_threads()),1)-1` + caller-thread `cur_op()` reuse, `work_cv` u32→u64 + `work_done`). Diff confirms 30→256, `fnv_hash.hpp` `hash_array`, and caller recycle.
- `<source: https://github.com/RPCS3/rpcs3/issues/16883>` — Feature request: better PPU/SPU precompilation — discover all executables before gameplay, avoid runtime compilation.
- `<source: https://github.com/RPCS3/rpcs3/wiki/Roadmap>` — Historical roadmap mentions reusing LLVM IR generator around parametrized PPU/SPU interpreters (tiering prerequisite).
- `<source: https://github.com/RPCS3/rpcs3/issues/19103>` — July 28, 2026 regression: LLVM 22 PPU segfault on macOS ARM64, cache requires multiple launches.
- `<source: https://github.com/RPCS3/rpcs3/pull/15308>` — Experimental draft: deduplicate identical PPU functions (>40% duplication observed) — byte-identical ≠ semantic identical.
- Android scheduling / thermal / memory-bandwidth rationale: local hypothesis, to be validated by profiling (no external docs beyond generic big.LITTLE).
- LLVM `PassBuilder` / `EarlyCSEPass` / `CodeGenOptLevel` / `verifyModule` semantics: standard LLVM 20 docs (ctx7 not queried — noted in risks).

**Commands executed:**

- `git -C app/src/main/cpp/rpcsx rev-parse HEAD` → `e8ae1481...`
- `grep -rn buckets|jit_core_allocator PPUThread.cpp` → confirmed 30-bucket manager
- `grep -rn PassBuilder|EarlyCSE|LoopAnalysisManager PPUThread.cpp` → confirmed x64-guarded block (PPU) and unconditional SPULLVM 5-pass block 2705-2733
- `grep -rn CodeGenOptLevel JITLLVM.cpp` → `Aggressive`
- `git clone https://github.com/RPCS3/rpcs3 /tmp/opencode/rpcs3 --depth 100 && git -C /tmp/opencode/rpcs3 fetch origin pull/18774/head:pr-18774 && git -C /tmp/opencode/rpcs3 show 53fefc82c / 503131c8f / 356a3a481 -- rpcs3/Emu/Cell/PPUThread.cpp` → pinned 3-commit chain (see External §PR 18774), verified 30→256 + hash_array + caller recycle

## Current Architecture

```
Kotlin Compose UI → native-lib.cpp (libsambas3-android.so) dlsym → runtime-loaded RPCSX .so (dlopen)
                                                      ↕
PPU pipeline: ppu_precompile(dir_queue) → ppu_initialize(info, check_only) → workload[] + link_workload[]
                → named_thread_group PPUW (thread_count = min(llvm_threads, get_max_threads(), workload.size))
                    → thread_op { core_lock (sem), shared_mtx, memory_limit.acquire(fn_size*16KiB), jit_compiler per module, ppu_initialize2() }
                        → Module(obj_name) → PPUTranslator per function → (x64: EarlyCSEPass) → verifyModule (non-Android) → jit.add()
                → jits[] creation (c_moudles_per_jit=?) → fin() → symbol_resolvers → write .gz per-module via zip()
              → ppu_finalize / linking per-module .gz load via unzip()
LLVM: MCJIT + RTDyldMemoryManager (MemoryManager1/2), CodeGenOptLevel::Aggressive, AArch64-only on Android
Pre-discovery: ppu_precompile scans dir_queue + mod_list + Prx/Overlay on demand; placement in installIso → g_compilationQueue.push
```

Authoritative files investigated:

- `app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUThread.cpp` (p.5793-5956 `ppu_initialize2`, 3816-3852 allocators, 5533-5625 workers, 5400-5700 `ppu_initialize`)
- `app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUTranslator.cpp` + `PPUTranslator.h`
- `app/src/main/cpp/rpcsx/rpcs3/util/JITLLVM.cpp` + `JIT.h`
- `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp` ( `ppu_precompile` call site, `g_compilationQueue`)
- `app/src/main/cpp/rpcsx/3rdparty/llvm/CMakeLists.txt`
- `app/src/main/cpp/native-lib.cpp`

Existing behavior & constraints:

- Emulator core **not in APK** — downloaded Gh releases `.so`, so submodule changes must be captured in `patches/rpcsx-submodule-changes.patch` for reproducible builds.
- Min SDK 29, Target 35, NDK 30, ABIs arm64-v8a/x86_64; 8-core big.LITTLE typical.
- `jit_module_manager` 30 buckets contended; `jit_core_allocator.sem` sized to `thread_count` (or `get_thread_count()` number_of_cores). Workers run `scoped_priority(-1)` (low) — desktop-friendly but stalls foreground Android compilation on little cores.
- `ppu_initialize2` constructs new `jit_compiler` per module (heavy LLVM context/target machine) then destroys; per-module `PassBuilder` machinery (even if x64-guarded, verify ARM64 path has no residual overhead — profile point).
- Cache is per-module `.gz` (compressed object), ~hundreds of tiny files → `open/stat/close` overhead on warm boot.
- Runtime PRX/overlay compilation blocks gameplay (no tiering — interpreter exists but not used as fallback while LLVM compiles).

## Affected Components & Dependencies

| Component | Impact |
|---|---|
| `app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUThread.cpp` | Primary: `jit_core_allocator`, `jit_module_manager`, `ppu_initialize2`, `ppu_initialize`, worker pool `thread_op`, profiling timers, foreground/background policy, codegen-level plumbing, verify gate, dedup R&D hook |
| `app/src/main/cpp/rpcsx/rpcs3/util/JITLLVM.cpp` + `JIT.h` | `setOptLevel` enum/config, `ObjectCache` compression policy, persistent worker state experiment |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUTranslator.cpp/.h` + `SPULLVMRecompiler.cpp` | ARM64 `PassBuilder` verification and Phase 2 benchmark experiment (mirrors PPU path) |
| `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp` | Pre-discovery manifest: scan `EBOOT.BIN/*.SELF/*.SPRX/*.PRX/*.ELF`/MSELF/overlay list at import/precompile time; persistent manifest keyed by exe hash + firmware + cache ABI + llvm_cpu + patch state |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUModule.cpp/.h` | Executable discovery helpers (`ppu_load_exec/prx/overlay`, `get_funcs`, relocs) reused by manifest |
| `app/src/main/cpp/native-lib.cpp` | Expose new PPU tunables via `settingsGet/Set` if needed (optional: `ppu_codegen_mode`, `ppu_worker_policy`) |
| `app/src/main/java/com/zenithblue/sambas3/**` | Optional Kotlin settings UI for worker count / codegen mode + profiling overlay (logcat already) — not required for correctness but helps manual QA |
| `patches/rpcsx-submodule-changes.patch` | **Must regenerate** for every `app/src/main/cpp/rpcsx` change |
| `build_rpcsx.sh`, `gradle/libs.versions.toml`, `app/src/main/cpp/CMakeLists.txt` | `build_rpcsx.sh` auto-applies patch; LLVM remain 20.1.3 for phase 1 |
| Dependencies | `llvm::PassBuilder`, `EarlyCSEPass`, `verifyModule`, `MCJIT`, `zlib` (zip/unzip), `vm::`, `g_cfg.core.{llvm_threads,llvm_cpu,llvm_precompilation}`, `utils::get_thread_count/get_max_threads`, `thread_ctrl::scoped_priority`, `__android_log_print` |

## Implementation Steps (ordered, smallest correct change)

### Phase 0 — Instrumentation (P0) — do first, measure everything

**Step 0.1 — Add detailed PPU compile profiling**
- Instrument `ppu_initialize()` and `ppu_initialize2()` with scoped timers (steady_clock or `perf_meter`/`__android_log_print`) for: `analysis` (analyser loop), `ir_translation` (per `PPUTranslator::Translate`), `pass_manager_setup` (PassBuilder/FAM construction), `fpm.run` (EarlyCSE on x64), `verifyModule` (non-Android path), `llvm_codegen` (`jit.add()` → `generateCodeForModule`), `compression` (`zip` in `notifyObjectCompiled`), `disk_write` (`pending_file.commit`), `object_load_link` (`jit.add(path)`).
- Add counters: module count, `num_func`, `guest_code_size`, `workload.size`, `thread_count`, `bucket` contention hint.
- Gate verbose tracing behind `g_cfg.core.llvm_logs` or new `ppu_profiling` flag to avoid log spam in production.
- **Verify:** `adb logcat | grep -E "PPU.*LLVM|PPU_PROFILE"` shows per-module breakdown on a 30-bucket baseline (cold cache game). This is diagnostic-only — very low risk.

### Phase 1 — Low-risk correctness/performance fixes (P1)

**Step 1.1 — Port RPCS3 #18774 PPU concurrency changes (REVISED per review R5)**
- In `PPUThread.cpp` `jit_module_manager`:
  - `std::array<bucket_t, 30>` at 3851 → `std::array<bucket_t, 256>` (or upstream constant `num_buckets = 256`).
  - Update hash: `hash % buckets.size()` at 3855 and any debug dumps iterating `buckets` (e.g., `remove()` error dump at 3878-3884).
  - Pull improved `jit_core_allocator` locking: eliminate unnecessary core-lock layer, reduce semaphore contention (diff upstream commit `ppu: fix core_lock locking` — run `git clone https://github.com/RPCS3/rpcs3 /tmp/opencode/rpcs3 && git log --oneline --grep=18774 --grep="core_lock" && git show <merge_sha> -- rpcs3/Emu/Cell/PPUThread.cpp` and record exact merge SHA in `Research Sources` post-clone; reviewer to verify SHA pinned).
  - Adopt **caller-thread recycling**: reserve one worker slot and use current thread as additional compiler worker (instead of creating N workers and leaving caller waiting). Replicate `thread_op` construction / `named_thread_group` predicate changes verbatim where possible (see `PPUThread.cpp:5613-5622` predicate `work_cv < workload.size()`).
- Keep behavior flag-gated? No — direct port, matching upstream ABI. Validate `g_fxo->get<jit_module_manager>` persists correctly.
- **Verify:** Unit: cold cache build still produces identical `.obj` hashes vs baseline (except bucket distribution). Perf: wall-clock on 8-core Snapdragon, same title, 3 runs median. Research Sources updated with `git show <pin>` SHA.

**Step 1.2 — Android-aware compile worker scheduling (foreground vs background) (REVISED per review R6)**
- Introduce `enum ppu_compile_policy { FOREGROUND, BACKGROUND }` determined by caller: `ppu_precompile` during `CompilationQueue` serial execution vs on-demand `ppu_initialize` while game `Running`/`is_being_used_in_emulation`.
- **Foreground** (user waiting at "Compiling PPU modules"):
  - `thread_ctrl::scoped_priority(0)` normal priority, **prefer performance cores** — do not hardcode `get_thread_count()` number_of_cores.
  - Adaptive worker count: `workers = min(detected_big_plus_mid_cores, thermal_compile_limit, g_cfg.core.llvm_threads ? g_cfg.core.llvm_threads : limit())`. Estimate `detected_big_plus_mid_cores` via `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` or `utils::get_thread_count` stratified (phase 1 heuristic: `max(2, min(6, get_thread_count()-2))` to avoid saturating memory/thermal — then benchmark 2..all).
  - Optionally set thread affinity mask to big/mid cluster for foreground workers (measure — may conflict with scheduler).
- **Background** (game already running / idle cache generation):
  - Keep `scoped_priority(-1)`, allow efficiency cores, thermal/battery-aware throttling (respect `g_cfg` or system thermal callback stub).
- Expose `ppu_worker_policy` + `thermal_compile_limit` tuning knobs via `g_cfg.core` (optional, default auto).
- Change `jit_core_allocator::limit()` (`PPUThread.cpp:3826-3829`) usage to policy-aware `effective_thread_count(policy)`. Edit point is **only** `PPUThread.cpp:5517` `const u32 thread_count = std::min(::size32(workload), rpcs3::utils::get_max_threads());` and `PPUThread.cpp:3818` `thread_count = min(llvm_threads, limit())` via new helper `effective_thread_count(policy)`; **do not** edit `Emu/system_utils.cpp:22-28` `get_max_threads()` double-capping `llvm_threads` — `get_max_threads()` already does `min(llvm_threads, hw)`; worker cap is single layer at call site to avoid `min(min(...))` confusion. Document which helper owns the final `min`.
- **Verify:** A/B on the same device/title across candidate worker counts (2..all available, including the initial 4–6 candidate on an 8-core device), monitor `dumpsys cpuinfo`/thermal throttling, and record the policy-selected effective worker count. The selected count must never exceed `get_max_threads()`. FPS after compile must remain identical and background policy must show no regression.

**Step 1.3 — Verify PPU ARM64 PassBuilder gating (REVISED per review R1/R2/R8)**
- In `PPUThread.cpp:5840-5865` the entire `LoopAnalysisManager/FAM/CGSCC/MAM/PassBuilder/FPM` block is **already** `#ifdef ARCH_X64` (plus guarded `fpm.run` at 5891-5894/5909-5912) — PPU ARM64 path is already compiled out. **No PPU edit needed** beyond verification-only `grep`; do not churn `patches/rpcsx-submodule-changes.patch` for this file.
- In `SPULLVMRecompiler.cpp:2705-2733` the block is **unconditional**: 5 passes — `2728 EarlyCSEPass(true)`, `2729 SimplifyCFGPass`, `2730 DSEPass`, `2731 LICMPass` (`createFunctionToLoopPassAdaptor(LICMPass(LICMOptions()),true)`), `2732 ADCEPass` (exact via `grep -n addPass SPULLVMRecompiler.cpp:2728-2732`). This is an identified ARM64 candidate, not an approved P1 removal.
- The PPU EarlyCSE ARM64 regression evidence does not establish that removing the complete SPU five-pass pipeline improves gameplay. Do not gate or remove the SPU passes in Phase 1.3. Retain the current pipeline until the Phase 2 A/B experiment measures both compile time and runtime performance.
- **Verify:** PPU `grep` remains unchanged and confirms the existing `#ifdef ARCH_X64`; ARM64 builds retain the SPU pipeline. The SPU change is benchmark-only in Phase 2 and must not be included in the Phase 1 patch.

**Step 1.4 — Improve executable/PRX/overlay pre-discovery (attack runtime stalls)**
- At game import (`android/src/rpcsx-android.cpp: installIso → g_compilationQueue.push`) and during `ppu_precompile` startup, build a **persistent executable manifest**:
  ```
  EBOOT.BIN
  *.SELF
  *.SPRX
  *.PRX
  *.ELF
  MSELF entries
  overlay executables
  dynamically referenced firmware modules
  ```
  using `Emu.GetBoot()/VFS`, `ppu_load_exec/prx/overlay` analysis helpers, and directory scan. Deduplicate via content hash / path canonicalization.
- Manifest persisted under `rpcs3::utils::get_cache_dir()` (`Emu/system_utils.cpp:125-130` already returns the Android platform cache root with RPCSX's `cache/` suffix) + `"ppu_manifest/<title_id>.json"` (no leading slash; do not hard-code another `cache/` or `dev_hdd0/game/<id>/` path). Keyed by: `game exe hash`, `firmware version/hash`, `RPCSX PPU cache ABI version (v7-kusa at PPUThread.cpp:5457)`, `llvm_cpu` (`JITLLVM.cpp:514/ cpu()`), `ppu relevant config` (`sysinfo`, `accurate_*`, `fixup` flags via `ppu_settings` enum at PPUThread.cpp:5410-5426), `patch state hash` (hash of `util/bin_patch.h:31` `patch_engine_version="1.2"` + `patchesList` JSON from `android/src/rpcsx-android.cpp:2715` `_rpcsx_patchEngineVersion` / `2717` `_rpcsx_patchesList`). See corrected line refs per R4/R7.
- Before gameplay, iterate manifest and ensure each entry has `jit_compiler::check(cache_path+obj_name)==true`; enqueue missing ones via existing `workload` path (reuse `ppu_initialize` check_only=false) **before** `Emu.Boot`.
- On manifest cache-hit, skip recursive title directory scan entirely (fast path).
- Invalidation: any key mismatch → rescan/regenerate manifest.
- Low–medium risk: must handle encrypted SELF/MSELF (reuse `unself` path), overlays loaded via `sys_overlay`, and PRXs referenced but not on disk until runtime (log and compile on demand — next phase tiering handles residual).
- **Verify:** Manual: title with known multi-executable (MGS4/GT6/GTA5/collection ISO) — cold boot without manifest completes precompile then enters game without mid-gameplay "Compiling PPU modules" overlay; warm boot with manifest skips compilation and loads from cache. Automated: unit test `IsoInstallManifest` extension for manifest serialization.

### Phase 2 — Benchmarked experiments (P2) — measure, then decide

**Step 2.1 — Benchmark cheaper LLVM codegen for latency-sensitive runtime modules**
- In `JITLLVM.cpp:645-710` `jit_compiler` ctor, replace hardcoded `.setOptLevel(Aggressive)` with configurable:
  ```cpp
  enum class ppu_codegen_mode { fast, normal, aggressive }; // Less, Default, Aggressive
  ```
  Default remains `aggressive` for now.
- Add `g_cfg.core.ppu_llvm_opt_level` (or `llvm_ppu_codegen_mode`) mapping to `CodeGenOptLevel::Less/Default/Aggressive`. Separate policy: `pre_runtime` (Default/Aggressive) vs `runtime_missing_prx` (Less/Default) — initially both `aggressive`, experiment via config toggle.
- Benchmark matrix (same device, same title, 3 runs each):
  - Compile latency: `analysis / translation / verify / codegen / compress / write / load-link / TOTAL`
  - Runtime: PPU host CPU time, FPS, 1% low, frame-time spikes, power (battery historian)
- Decision gate: if `Less` saves >15% total compile latency for <5% FPS loss on latency-sensitive PRXs, adopt split policy; otherwise keep `Aggressive` for pre-runtime and document findings.

**Step 2.2 — Benchmark skipping LLVM `verifyModule` in release (REVISED per review R3)**
- `PPUThread.cpp:5928-5949` currently `#ifndef ANDROID` at **5928** skips `verifyModule(*_module, &out)` at 5939 on Android already — confirm via `grep -n "#ifndef ANDROID" PPUThread.cpp:5928`. On Android verification is already skipped; experiment is desktop-only/benchmark documentation. If a non-Android path still runs verify, gate additional:
  ```cpp
  #if defined(PPU_LLVM_VERIFY) // Debug/Developer = ON, Release = benchmark OFF
    if (verifyModule(...)) ...
  #endif
  ```
- Measure `verification` slice across hundreds of modules (non-Android bench or Android `#undef` test bench); if 1-2% total, keep ON; if meaningful, disable in release and keep in debug/developer.

**Step 2.3 — Reduce cache compression / small-file overhead**
- Measure `emit object vs compress vs write/rename` per-module (Step 0 timers).
- Experiment A: use faster compression level for new modules (e.g., `zip` level 1 or `ZSTD` fast) vs background recompress to aggressive.
- Experiment B: pack per-executable cache into single archive/index (e.g., `ppu_cache/<exe_hash>.pack` + index json) to reduce `open/stat/lookup/close` over hundreds of `.gz` on warm boot. Compare warm-boot load time and install size.
- Choose policy: fast-compress + optional idle recompress is lowest-risk first step; pack is medium risk (needs migration).

**Step 2.4 — Benchmark the SPU LLVM pass pipeline on ARM64 (REVISED per review R8)**
- Keep the five-pass SPU pipeline enabled by default. Do not port the proposed ARM64 compile-out from Phase 1.3 without evidence.
- Add profiling around SPU `PassBuilder` setup and `fpm.run`, then run an A/B matrix on the same ARM64 device/title with passes ON vs an experimental OFF build: SPU compile wall-clock, PPU/SPU host CPU time, gameplay FPS, 1% lows, frame-time spikes, and memory use.
- The OFF variant may ship only if it meets the defined runtime regression budget (no meaningful gameplay CPU/FPS/1% low regression) while producing a measured compile-time benefit. Otherwise retain ON and document the result.
- This experiment is separate from PPU EarlyCSE evidence; no PPU pass change is implied.

### Phase 3 — Architectural (P3 / R&D) — separate projects, design first

**Step 3.1 — Tiered Interpreter → LLVM asynchronous PPU (huge runtime win, high risk)**
- Design doc before code: new `PPUFunction` state machine:
  ```
  new PPU function → interpreter immediately (g_fxo->get<ppu_interpreter_rt>().decode)
                   → enqueue LLVM compile in background
                   → on LLVM ready: atomic jump-table replacement (ppu_ptr(target))
  ```
  Must guarantee: PPU register/state consistency, atomic `vm::g_exec_addr` update, no function replaced while PPU thread entering it, module unload/overlay `ppu_unload_prx` handling, cache invalidation, patch/reloc/func-link/exception correctness.
- Reuse existing interpreter infrastructure; prototype on one title (e.g., GTA SA intro) where missing PRX currently freezes 7s → interpreter→JIT should show only brief interpreter dip.
- Guarded by `g_cfg.core.ppu_tiered_compilation` experimental flag, default OFF until validated.

**Step 3.2 — Persistent compiler infrastructure per worker (optional)**
- Instead of `jit_compiler jit2()` per module, keep per-worker persistent `target information / target machine / LLVMContext` and reset light state per module. Measure memory pressure vs creation/destruction cost. Low priority — only if profiling shows significant MCJIT ctor overhead.

**Step 3.3 — Deduplicate identical PPU functions (experimental R&D, do not ship first)**
- Based on RPCS3 draft #15308: hash function bytes → reuse compiled native code for byte-identical duplicates (>40% duplication observed). Requires semantic equivalence proof (relative branches, external refs change meaning). Research-only gate with correctness fuzzer.

**Step 3.4 — Do NOT upgrade LLVM 20.1.3 → 22.1 blindly in this series**
- Stay on 20.1.3 for phases 0-2. Separately benchmark 20 vs 22 on Android (AArch64) for correctness (macOS #19103 segfault caution) and performance; upgrade only if clean on target Adreno/Mali.

## File-Level Change Map

| File | Change | Rationale |
|---|---|---|
| `app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUThread.cpp` | **P0** Add `PPU_PROFILE_*` scoped timers (analysis/IR/PM setup/FPM run/verify/codegen) + counters; **P1** `jit_module_manager` 30→256 buckets + `jit_core_allocator` #18774 locking fix + caller-thread recycle + `thread_op` policy (foreground/background priority, adaptive `thread_count` at 3818+5517 via `effective_thread_count(policy)`); **P1** verify PPU PassBuilder already `#ifdef ARCH_X64` at 5840-5895 (no PPU edit, verification-only); **P2** gate `verifyModule` at 5928-5949 via `PPU_LLVM_VERIFY` (Android already skips); **P3** tiering hook (`ppu_fallback` + atomic `ppu_ptr` swap) | Core PPU pipeline |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/SPULLVMRecompiler.cpp:2705-2733` | **P2 benchmark only** SPU ARM64 five-pass A/B experiment; keep the current pipeline as the default until compile/runtime evidence supports a change | Candidate optimization requires gameplay regression data; PPU evidence is not sufficient |
| `app/src/main/cpp/rpcsx/rpcs3/util/JITLLVM.cpp:645-710` + `JIT.h` | Configurable `CodeGenOptLevel` enum + ctor param forwarding; document `notifyObjectCompiled` fast-compress level & pack alternative | Codegen latency experiment |
| `app/src/main/cpp/rpcsx/rpcs3/util/JITLLVM.cpp:400-512` | Optional fast compression level + background recompress; measure zip time | Cache write overhead |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUModule.cpp/.h` | Helper to enumerate SELF/SPRX/PRX/ELF/MSELF/overlay executables for manifest | Pre-discovery |
| `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp:2715-2773` | Build/persist `ppu_executable_manifest.json` under `rpcs3::utils::get_cache_dir()+"ppu_manifest/<id>.json"` (`system_utils.cpp:125-130`), precompile missing entries before `Emu.Boot`, key invalidation via `v7-kusa` (5457)/`llvm_cpu` (514)/`ppu_settings` (5410-5426)/`bin_patch.h:31` + `patchesList` (2717) hash, fast-path skip | Eliminate runtime stalls; canonical path has no leading slash |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/system_config.h/.cpp` + `system_config_types.h` | Add `llvm_ppu_codegen_mode`, `ppu_worker_policy`, `thermal_compile_limit`, `ppu_tiered_compilation` (experimental) — optional, defaults preserve behavior | Tunables for A/B |
| `app/src/main/java/com/zenithblue/sambas3/**` (e.g., `SettingsScreen.kt`, `ProgressRepository.kt`) | Optional UI for worker/codegen toggles + logcat profiling overlay | Manual QA ergonomics |
| `patches/rpcsx-submodule-changes.patch` | **Regenerate after every submodule edit** (reverse-check/apply flow in `build_rpcsx.sh`) | Reproducibility |
| `build_rpcsx.sh` | No logic change — ensure patch still applies cleanly after bucket/worker edits | Build gate |
| `docs/BUNDLED_TURNIP_DRIVERS.md` | No change | Unrelated |

## Testing Strategy

**Build gates:**
- `./gradlew assembleStandardDebug assemblePlaystoreDebug` and `./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest` pass.
- `./build_rpcsx.sh` clean build from scratch (patch reverse/forward check).

**Unit / deterministic tests:**
- `jit_module_manager` bucket distribution: insert N synthetic module names, assert uniform spread and no lost entries (30 vs 256).
- `IsoInstallManifest` + new `PpuExecutableManifest` serialization round-trip: determinism, path canonicalization, duplicate detection, key invalidation (firmware hash change → regenerate).
- `verifyModule` gate: debug build still verifies, release build skips when flag OFF (build-type matrix).
- Worker policy: mock `get_thread_count()=8` → foreground workers=4-6, background workers ≤ core count, `llvm_threads` override respected.

**Benchmark harness (manual, Snapdragon Adreno device required):**
- Run cold cache (delete `cache/ppu/` or new manifest key) 3× per config, report median wall-clock via timers added in Step 0:
  ```
  analysis / translation / pass_setup / fpm_run / verify / codegen / compress / write / load-link / TOTAL
  mod count, guest_code_size
  ```
- Matrix:
  - Baseline (30 buckets, low-prio all-cores, Aggressive, verify as-is, gz level default)
  - + #18774 (256 buckets, new locking)
  - + foreground policy (normal prio, 4 workers vs 8)
  - + SPU ARM64 passes ON vs experimental OFF (compile time and gameplay CPU/FPS/1% lows)
  - + manifest pre-discovery (cold vs warm, runtime stall count = 0)
  - + codegen Aggressive/Default/Less (runtime 300 KiB PRX latency + FPS)
- Titles: one small (e.g., `BLUS31642` GTASA intro), one large multi-exe (MGS4/GT6/GTA5 if available), plus standard regression suite in `docs/games/`.

**Integration / device:**
- Cold boot after `adb shell pm clear` + install: manifest built, PPU compiles on big cores, progress bar not frozen, notification/log shows foreground policy.
- Background policy: start game, background app mid-precompile (if still compiling) — verify throttle, no ANR.
- Runtime stall elimination: launch world/intro that previously triggered `sys_prx`/`sys_overlay` load — verify no second "Compiling PPU modules" overlay after manifest path; log shows cache hit.
- Thermal/memory stress: 8 workers vs 4 on hot device — ensure 8 not faster due to throttling/memory bandwidth.

**Long-term (phase 3):**
- Tiered fallback: kill switch — `ppu_fallback` + `ppu_interpreter` path always correct, LLVM job atomic swap validated under ThreadSanitizer with concurrent PPU threads; overlay unload test.

## Acceptance Criteria (objective, verifiable)

- [ ] `grep -n "std::array<bucket_t, 256>" app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/PPUThread.cpp` succeeds and `grep -n "buckets\[.*%.*size"` reflects new size; `jit_core_allocator` diff matches upstream #18774 3-commit chain pinned: `53fefc82c` (core_lock), `503131c8f` (30→256 + hash_array), `356a3a481` (caller recycle) via `git -C /tmp/opencode/rpcs3 show <sha> -- rpcs3/Emu/Cell/PPUThread.cpp` (Research Sources §External already lists SHAs).
- [ ] `grep -n "LoopAnalysisManager\|PassBuilder\|EarlyCSE" PPUThread.cpp` shows the existing block inside `#ifdef ARCH_X64` at 5840-5865; no PPU ARM64 pass setup is added. SPULLVM retains its current five-pass pipeline in Phase 1; the Phase 2 A/B report covers any experimental OFF build and must include SPU compile time plus gameplay CPU/FPS/1% lows.
- [ ] `grep -n "setOptLevel" rpcsx/rpcs3/util/JITLLVM.cpp` is parameterized (`CodeGenOptLevel::Less/Default/Aggressive`) via new enum/config, default still `Aggressive`; bench report exists for `Less vs Default vs Aggressive` on same device/title with TOTAL and FPS deltas.
- [ ] `grep -n "#ifndef ANDROID" rpcsx/rpcs3/Emu/Cell/PPUThread.cpp` shows guard at 5928 and `grep -n "verifyModule" rpcsx/rpcs3/Emu/Cell/PPUThread.cpp:5939` is inside it (Android already skips); if additional `#ifdef PPU_LLVM_VERIFY` gated, both greps pass; measurement log shows `verification` slice timing for ≥2 titles (non-Android bench if Android skips).
- [ ] `grep -n "ObjectCache::notifyObjectCompiled" rpcsx/rpcs3/util/JITLLVM.cpp` shows compression timing log; bench report compares `zip level`/pack overhead and warm-boot `open/stat` count.
- [ ] PPU worker policy: `grep -n "ppu_compile_policy\|FOREGROUND\|BACKGROUND\|scoped_priority"` in `PPUThread.cpp` and logcat `PPU_PROFILE` shows the foreground policy-selected effective worker count, derived from topology/config/benchmark policy and never greater than `get_max_threads()`; background uses low priority; `adb logcat | grep PPU.*worker` proves it.
- [ ] Executable manifest: `ls $(rpcs3::utils::get_cache_dir)/ppu_manifest/*.json` (`Emu/system_utils.cpp:125-130` returns the Android platform cache root with RPCSX's `cache/` suffix) exists after import; manifest JSON contains `EBOOT`, `SELF/SPRX/PRX` list, key fields (`exe_hash`, `fw_version`, `cache_abi=v7-kusa` at PPUThread.cpp:5457, `llvm_cpu` at JITLLVM.cpp:514, `settings` at PPUThread.cpp:5410-5426, `patches` hash from `rpcs3/util/bin_patch.h:31` `patch_engine_version` + `android/src/rpcsx-android.cpp:2715` `_rpcsx_patchEngineVersion` / `2717` `_rpcsx_patchesList`); cold boot with valid manifest does not rescan title dir (log `manifest hit, skipping scan`); warm boot loads from cache, no runtime PPU overlay for known multi-exe title.
- [ ] Wall-clock median improvement documented for at least one title (profile timers) vs baseline pinned `e8ae1481` — e.g., cold cache TOTAL −10% or runtime stalls eliminated (0 mid-gameplay compiles) even if TOTAL only modestly faster.
- [ ] No LLVM 22 upgrade in this series: `grep USE_LLVM_VERSION 3rdparty/llvm/CMakeLists.txt` remains `20.1.3`; separate bench branch noted.
- [ ] `patches/rpcsx-submodule-changes.patch` regenerated and `build_rpcsx.sh` applies cleanly both directions (`git -C app/src/main/cpp/rpcsx apply --reverse --check` or forward).
- [ ] `./gradlew assembleStandardDebug` succeeds and manual smoke on arm64 device: boot `BLUS31642` (or available title) to menu without crash, ISR/touch still works, Vulkan not software.

## Risks & Mitigations (NEW_RISKS)

| Risk | Impact | Mitigation |
|---|---|---|
| #18774 port diverges from RPCSX `e8ae1481` assumptions (RPCSX forked long ago, may have local `jit_core_allocator` mods) | High — build break / deadlock / wrong bucket hash | Clone `RPCS3/rpcs3` to `/tmp/opencode/rpcs3`, extract exact commit diff for #18774, apply minimally, keep Samba-specific `android` guards; record base commit hash in plan review. |
| ARM64 PPU PassBuilder guard already correct, while the SPU five-pass pipeline remains an unproven candidate | Medium — an incorrect removal can hurt gameplay | Keep PPU verification-only; benchmark SPU ON vs OFF with compile time and gameplay CPU/FPS/1% lows before any policy change. |
| Foreground big-core affinity conflicts with Android scheduler / thermal daemon, may worsen throttling or violate Play policy | Medium — slower or thermal shutdown | Start with priority only (no affinity), benchmark affinity as experiment behind flag; monitor `dumpsys thermalservice` and battery. |
| Memory bandwidth saturation with many LLVM workers (8 on phone may be slower than 4) | Medium — perf regression | Adaptive worker benchmark matrix 2..all cores; choose `min(big+mid, thermal_limit, llvm_threads)` as default, document. |
| Executable manifest incomplete (encrypted SELF, dynamic `sys_prx` load, `MSELF` inside PKG) → still hits runtime compile | Medium — incomplete stall elimination | Manifest best-effort + fallback to on-demand compile; tiered interpreter (phase 3) closes residual gap. Log manifest-miss for iteration. |
| Faster `CodeGenOptLevel::Less` regresses FPS more than latency saved | Medium — user-visible stutter after compile | Keep `Aggressive` default for pre-runtime, only experiment runtime `Less` behind flag with FPS/power gates; decision documented, not shipped until proven. |
| `verifyModule` already skipped on Android (`#ifndef ANDROID`) — disproves prompt's assumption | Low — no action | Confirm via read at 5939; if Android skips, mark step as desktop-only or close. |
| Cache compression change increases disk I/O or install size unexpectedly | Medium | Measure both latency and `.gz` size; keep per-module fast level first, pack as opt-in. |
| Tiered Interpreter→LLVM is high-risk (atomic jump-table, unload, relocs) — incorrect swap crashes PPU thread | High | Isolate as separate design doc + experimental flag OFF by default; exhaustive concurrent tests before merge. |
| Function deduplication (#15308) semantic unsound (relative branches) | High | R&D only, behind flag, with correctness fuzzer; not in P1/P2. |
| Patch regeneration forgotten → CI builds stale core | Medium | Checklist: `git -C app/src/main/cpp/rpcsx diff > patches/...` after each submodule edit + `build_rpcsx.sh` verification. |
| Inconclusive research: ctx7 LLVM docs not fetched (quota) | Low | Note fallback; rely on local `llvm/IR/Verifier.h`, `PassBuilder.h`, `CodeGen.h` headers as ground truth. |

## Handoff to Plan Reviewer

Validate:

1. Bucket count and #18774 diff fidelity — reviewer must `git clone RPCS3/rpcs3` to `/tmp/opencode/rpcs3`, `git log --grep=18774 --oneline`, `git show <merge_sha> -- rpcs3/Emu/Cell/PPUThread.cpp` and compare `jit_module_manager` 30→256 + `jit_core_allocator` `core_lock`/`sem` + caller-thread recycle against this plan's 1.1; SHA must be pinned in Research Sources post-clone; confirm 256 is upstream value and not arbitrary.
2. Android scheduling claim — verify `thread_ctrl::scoped_priority(-1)` at `PPUThread.cpp:5558` indeed hurts foreground PPU on big.LITTLE (check `utils::get_thread_count`/scheduler doc) and that adaptive worker selection edits only `PPUThread.cpp:3818/5517` via `effective_thread_count(policy)`, not double-capping `system_utils.cpp:22-28` `get_max_threads()`, and is measurable not speculative.
3. ARM64 PassBuilder overhead — confirm PPU already `#ifdef ARCH_X64` at 5840-5865 is verification-only (no PPU file churn) and SPULLVM `2705-2733` is an unconditional five-pass benchmark candidate; ensure the plan does **not** remove it without compile/runtime evidence or re-enable any pass based on unrelated PPU results.
4. Pre-discovery manifest scope vs RPCS3 #16883 — verify manifest path uses `rpcs3::utils::get_cache_dir()+"ppu_manifest/"` (`system_utils.cpp:125-130` is the Android platform cache root plus RPCSX's `cache/` suffix) and keys cover all invalidation dimensions (`v7-kusa` at PPUThread.cpp:5457, `llvm_cpu` at JITLLVM.cpp:514, `ppu_settings` at 5410-5426, `bin_patch.h:31` + `android/src/rpcsx-android.cpp:2715`/`2717` hash) and that on-demand PRX gap is acknowledged with tiered fallback planned.
5. Codegen/verify/compression are benchmark-first, not shipped blindly — verify acceptance greps check `5928` guard line + `5939` call and `SPULLVM` 5-pass grep, and require reports before policy changes.
6. Tiered and dedup are correctly scoped as R&D/high-risk, not P1.
7. LLVM version stays 20.1.3 (`3rdparty/llvm/CMakeLists.txt:3`) for this series — reviewer to flag any premature 22.1 bump.
8. Plan is smallest correct change per phase (profiling → concurrency → scheduling → manifest → benchmarks → R&D) and file map is complete (including patch regeneration at `patches/rpcsx-submodule-changes.patch` + `build_rpcsx.sh` verification).
