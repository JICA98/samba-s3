# Findings: Demon’s Souls BLUS30443 INSTALL PPU JIT memory / worker cap

**Date:** 2026-09-02  
**Branch:** `recovery/ingame-menu-fix`  
**Baseline root:** `ce19aaf52b87cb8ca04ec2f151390c633b039991`  
**Baseline RPCSX gitlink:** `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`  
**Device:** `7d6afed8` (OPD2403 / LineageOS)  
**Title:** Demon’s Souls `BLUS30443` (233 modules)  
**Artifacts:** `docs/testers/artifacts/2026-09-02-demons-souls-ppu-jit-memory/`

---

## 1. Verdict (read this first)

| Gate | Status |
|---|---|
| Typed INSTALL terminal (COMPLETED-only → PreRuntime READY) | **Shipped (Kotlin)** |
| A0 `Max LLVM Compile Threads=0` (all host) | **CRASH** ~module 97, `PPUW.1.4`, Scudo + `std::bad_alloc` in LLVM RuntimeDyld |
| A1 `Max LLVM Compile Threads=1` | **CRASH** ~module 99, Scudo + `std::bad_alloc` (tid Thread-6; no PPUW) |
| A2 `Max LLVM Compile Threads=2` | Skipped — A1 already proves worker cap insufficient |
| Chosen product gate | **Gate C (multistep batch)** — implemented; **does not yet prevent OOM** |
| Multistep (Gate C) | **Shipped** — `install_step_max_new=16`, `ppu_finalize` between steps, INSTALL worker cap=2 |
| Progress UI | **Shipped** — overall `module X of 233` (not batch-of-N) |
| Gate C device accept | **FAILED** — Scudo OOM at ~module **99/233** after `install_step=5` (pid 490, tombstone_35) |

**Scope of this change set:** overall PPU progress display + batch/multistep install architecture. **Not solved:** INSTALL Scudo OOM on BLUS30443. Needs reviewer handoff for a stronger memory architecture.

---

## 2. Baseline

```
HEAD=ce19aaf52b87cb8ca04ec2f151390c633b039991
RPCSX=657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc
Prior findings: docs/findings/2026-09-02-ppu-lifecycle-kotlin-fix-and-multistep-handoff.md
```

Device left at llvm=1 from a prior OOM retry; restored to **0** via debug settings broadcast for A0 crash repro.

---

## 3. Upstream / local parity table

| Behavior | Upstream RPCS3 | Samba local RPCSX (`657b26a0`) | Match? |
|---|---|---|---|
| `llvm_threads == 0` means all host threads | yes (`get_max_threads`) | yes (`system_utils.cpp`) | **yes** |
| PPU workload worker count uses JIT allocator / max threads | yes | yes (`ppu_effective_thread_count` + `jit_core_allocator`) | **yes** |
| PPU worker creates auxiliary `jit_compiler(..., 0x1)` | yes | yes (`PPUThread.cpp` ~6032) | **yes** |
| auxiliary memory manager reserves 3 × 256 MiB | yes | yes (`JITLLVM.cpp` `c_max_size * 3`) | **yes** |
| auxiliary JIT destructor releases/decommits reservation | yes (`memory_decommit`) | yes | **yes** |
| main PPU precompile calls `ppu_finalize(..., true)` | yes | yes (SELF/PRX/OVL paths) | **yes** |
| PRX/OVL paths force-release module JIT state | yes | yes (`ppu_finalize(*prx, true)` / ovlm) | **yes** |
| SELF precompile leak fix ~39910885 present | yes | yes (`_main.name=' '; ppu_finalize(_main, true)`) | **yes** |
| successful object-cache fragments persist before later failure | yes | yes (`jit_compiler::check` skip path) | **yes** |
| restart/retry skips valid cached objects | yes | yes | **yes** |
| Concurrent file load memory limit (`concurent_memory_limit`) | yes (upstream #15377 era) | yes (`get_total_memory()/3`) | **yes** |

**Conclusion:** Gate B (missing upstream release/finalize) is **not** the primary gap. Worker concurrency / aux JIT VA pressure is the leading hypothesis (Gate A). Gate C batching alone is **insufficient** — same Scudo map failure persists across steps.

---

## 4. A0 crash reproduction (llvm=0)

- **Origin:** INSTALL (PrecompilerService FGS **3000**)
- **Progress at death:** module **97 / 233** (~41%)
- **Thread:** `PPUW.1.4`
- **Abort path:** Scudo `Can't populate more pages…` → `std::bad_alloc` → `llvm::report_bad_alloc_error` → `RuntimeDyld` / `MCJIT::generateCodeForModule` → `jit_compiler::add`
- **Workers observed:** `PPUW.1.1` … `PPUW.1.4` (multi-worker)
- **Memory (compile mid):** VmSize ≈ 80 GiB virtual, VmRSS ≈ 1.0 GiB, MemAvailable ≈ 7.5 GiB
- **Artifacts:** `artifacts/.../a0/`

This matches the handoff: map/allocator failure under concurrent aux JIT, not “phone out of physical RAM.”

---

## 5. A/B table (filled as runs complete)

| Config | llvm threads | Result | Module at terminal | Peak VmRSS (approx) | PPUW | Duration |
|---|---|---|---|---|---|---|
| A0 | 0 (all host) | **CRASH** SIGABRT | ~97/233 | ~1.0 GiB | 1.1–1.4 | ~6 min to crash |
| A1 | 1 | **CRASH** SIGABRT | ~99/233 | ~0.4 GiB early | none | ~8 min to crash |
| A2 | 2 | skipped (Gate C triggered) | | | | |
| A4 | 4 | skipped | | | | |
| Gate C step=32 | (user llvm; INSTALL cap=2) | **CRASH** | batch UI “of 32” then OOM | | | one step then die |
| Gate C step=16 | (user llvm; INSTALL cap=2) | **CRASH** | **99/233** after `install_step=5` | | Thread-6 | ~11 min (pid 490) |

---

## 6. Typed INSTALL terminal (Kotlin)

`CompileOutcome` + `InstallPpuTerminalLogic` gate PreRuntime READY:

- Only `INSTALL` + matching title + matching job + `COMPLETED` → READY
- `FAILED` / `CANCELED` / `NONE` / `STEP_MORE` / wrong title / wrong job → never READY
- `CompileProgressBridge` preserves outcome/title/job across active→false
- `PrecompilerService` no longer treats `ppuActive=false` alone as success

Tests: `InstallPpuTerminalLogicTest`, existing bridge tests.

---

## 7. Gate A design (INSTALL-only worker cap)

```text
effectiveInstallWorkers = min(configuredEffectiveWorkers, 2)
runtimeWorkers           = configuredEffectiveWorkers  // unchanged
```

- Kotlin helper: `InstallWorkerBudget` (+ unit tests for 0/1/4/16 × host 8 × cap 2)
- Native: `#ifdef __ANDROID__` in `ppu_effective_thread_count` reads `get_system_progress_context().origin == INSTALL` and caps at 2
- Does **not** persist `Max LLVM Compile Threads` in config.yml
- Preserves ce19aaf lifecycle (no headless, first-frame validation, FGS ownership)

---

## 8. Decision gate

**Gate C selected and implemented.** Evidence: A1 (`llvm_threads=1`) still dies at ~module 99 with Scudo. Worker concurrency is not the sole cause.

**Gate C acceptance failed.** step=16 + `ppu_finalize` between steps still dies at ~99/233 with the same Scudo “Can't populate more pages” → `Scudo ERROR: internal map failure (Out of memory)` on Thread-6. Batching is the right *direction* (progress continues across steps; cache advances; UI shows full total) but **does not free enough allocator/VA pressure** to finish 233 modules in one process life.

Gate A INSTALL worker cap=2 remains as defense-in-depth alongside Gate C.

---

## 9. What shipped (this change set)

1. **Overall PPU progress** — `g_progr_ptotal` = full module count (233); cache hits credited; UI `Progress: module X of 233`.
2. **Batch / multistep INSTALL** — CompilationQueue loops with `install_step_max_new` (16), `more_work` flag, `ppu_finalize(_main, true)` between steps.
3. **Typed terminal gating** — COMPLETED-only → PreRuntime READY.
4. **INSTALL worker budget** — cap=2 for INSTALL origin only.
5. **Debug helpers** — `DEBUG_REMOVE_GAME` / install helpers for harness.

Proof screenshots: `accept-gatec-step16/screen-progress-of-233.png`.

---

## 10. Gate C crash evidence (step=16)

| Field | Value |
|---|---|
| Session | `S3-1788369403263-ce5b` |
| pid | 490 |
| Last UI | `Progress: module 99 of 233` |
| Last step log | `install_step=5 more_work=1` / `pdone=96 ptotal=233 cached=96 uncached_remaining=137` |
| Abort | `Scudo ERROR: internal map failure (error desc=Out of memory)` |
| Signal | SIGABRT tid=569 (`Thread-6`) + `std::bad_alloc` |
| Tombstone | `tombstone_35` (head in artifacts) |
| Artifacts | `accept-gatec-step16/` |

Note: a user observation of “~140” may refer to another partial run or cumulative cache across retries; the captured step=16 live session died at **99/233**. Same failure class either way.

---

## 11. Acceptance checklist

| Item | Status |
|---|---|
| Device acceptance ×3 clean compiles | **FAIL** (Gate C still OOM) |
| Force-stop mid-compile cache reuse | Partially observed (cached=N advances across steps) |
| Runtime PPU still uses user llvm threads | Intended; not re-proven this session |
| GTA `BLUS31584` install regression | **Not run** (blocked on accept failure) |
| Restore user llvm setting after experiment | Pending device restore |
| Native tree via patch | Patch regenerated (no `samba-build-id`) |

---

## 12. Remaining risks / reviewer handoff ask

**Problem:** INSTALL-origin PPU JIT for large titles (233 objs) exhausts Scudo map/VA even when:

- llvm workers = 1 (A1),
- INSTALL workers capped at 2 (Gate A),
- work is batched ≤16 new objs with `ppu_finalize` between steps (Gate C).

**What works:** batch loop + overall progress UI + typed READY gating. Process survives multiple steps and advances object cache.

**What fails:** cumulative allocator pressure is not released enough by `ppu_finalize` alone; crash still ~40% through EBOOT modules.

**Ask for reviewer / next architecture:**

1. Confirm whether `ppu_finalize(..., true)` actually returns aux JIT 3×256 MiB reservations and Scudo primary/secondary pages between steps (instrument VmSize / mapped regions per step).
2. Consider **process-recycling multistep**: kill/recreate emulator process (or unload `librpcsx-android.so`) between batches while keeping on-disk object cache — strongest isolation if finalize is insufficient.
3. Consider shrinking/skipping aux `jit_compiler` reservations on INSTALL origin, or a dedicated INSTALL JIT memory manager with hard caps.
4. Do **not** treat persistent `Max LLVM Compile Threads=1` as the product fix (A1 already fails).
5. Preserve ce19aaf lifecycle (no headless on normal paths; FGS ownership; first-frame validation).

### Operational notes

- Full `librpcsx-android.so` rebuild required for native changes (`./build_rpcsx.sh`).
- SAF ISO re-import needed when debug harness uses `file://` (EACCES).
- Exclude large APK binaries from this findings commit.
