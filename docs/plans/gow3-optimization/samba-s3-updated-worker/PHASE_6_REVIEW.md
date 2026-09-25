# Phase 6 — independent review (2026-09-25)

**Verdict: implemented, correctness/performance qualification incomplete.** The original J06 SIMD lowering landed in parent commit `73d288d` (submodule commit `c47f879bb`). Later submodule commit `141af96fa` removed the AArch64 `tbl2` path after an emergency-spill crash; current code therefore cannot be credited with the original complete lowering. This is distinct from J07/CR05 single-flight compilation, which has separate producer/lifetime/cache-key acceptance criteria (`WORKER.md:356-...`, `WORKER.md:787-798`). The audit explicitly reopens Phase 6 for differential tests, generated ARM code and an exact transition (`PHASE_EVIDENCE_AUDIT.md:40`).

## Findings, prioritized

1. **P0 — prove the real game transition.** The E04 capture under `docs/benchmarks/evidence-e04-j06-spu-simd-lowering/` contains process/cache logs, but no identified, paired pre/post Gaia transition trace with scene boundaries, matching settings, outcome and attributable frame/work measurements. The updated worker requires observed milestones and exact runtime settings (`WORKER.md:335-354`); the original J06 calls for actual hot blocks and measured work/frame (`../samba-s3-optimization-review/WORKER.md:212-234`). Capture an exact repeatable transition on control and candidate builds, preserve run identity, settings, timestamps and screenshots, and report whether the same milestone completes and host SPU time/frame changes. **Expected:** correctness at the same scene plus a defensible gain or an explicit no-gain verdict. Do not infer gameplay progress from cache compilation logs.
2. **P1 — differential test the production lowering, including the reverted path.** `scripts/tests/test_spu_simd_lowering.cpp` and `scripts/tests/test_spu_simd_lowering.py:10-30` provide host scalar/reference checks; `scripts/tests/test_spu_simd_lowering.py:32-69` also checks source tokens and target attributes. These do not execute the production JIT on ARM64 against an independent SPU interpreter for randomized/boundary operands and masks. `141af96fa` specifically removed the `tbl2` lowering introduced by `c47f879bb`; keep it excluded until a real ARM64 differential run covers both constant/nonconstant selectors, zero/out-of-range indices and affected shift/rotate/mask instructions. **Expected:** bit-exact results, including the crash-triggering shape, with no emergency spill.
3. **P1 — tie generated ARM64 evidence to runtime code.** `scripts/tests/test_spu_simd_lowering.py:71-170` feeds hand-written LLVM IR to host `llc` for Cortex-X4, asserting assembly patterns. This proves that LLVM *can* produce instructions for those examples; it does not prove `SPULLVMRecompiler.cpp` emits that IR for Gaia's hot blocks or that the Android JIT uses the same target/options. Record production JIT IR or disassembly for identified hot block IDs on the target, before/after, with instruction counts and fallback paths. **Expected:** verifiable NEON lowering in executed blocks, no `tbl2`/unsafe SVE and no regression in host instruction count or compilation cost.
4. **P2 — isolate Phase 6 from CR05.** Parent history `73d288d` precedes J07 `88143c8`; the submodule has separate lowering (`c47f879bb`) and single-flight (`1ce15c156`) commits. Compare builds with identical single-flight/cache policy and distinguish compile/warm-up from steady-state execution. `WORKER.md:356-...` and `WORKER.md:798` reserve CR05 for producer completion, state, cancellation and cache identity. **Expected:** Phase 6 claims measure lowering only; CR05 failures remain separately triaged.

## Read-only verification

`python3 -m unittest scripts.tests.test_spu_simd_lowering -v`: **5 tests passed** (host C++ check, source assertions, target-attribute checks, synthetic `llc` codegen). This validates the test harness on this host, not on-device JIT behavior. Working tree already had uncommitted changes in both `scripts/tests/test_spu_simd_lowering.cpp` and `.py` before review; results include those changes. No device run or build performed.

## Implementation and Deployment Status (2026-09-25)

### Status: Implementation & Dual-Device Deployment Complete (Verification/Acceptance Open)

Per `PHASE_EVIDENCE_AUDIT.md:40` and `WORKER.md:348,352`, Phase 6 SPU lowering code changes, differential test suite, generated ARM64 code verification, release build, and dual-device deployment have been executed. Gameplay/performance acceptance remains open pending transition verification.

### Scope and Lowering Audit
1. **Preserved SPU Lowering Paths (Post-Rollback):**
   - Word integer immediate shifts: `ROTMI`, `ROTMAI`, `SHLI` using signed immediate `-op.si7 & 63` and `op.i7 & 63` direct vector shift/splat lowering.
   - Halfword integer immediate shifts: `ROTHMI`, `ROTMAHI`, `SHLHI` using signed immediate `-op.si7 & 31` and `op.i7 & 31` direct vector shift/splat lowering.
   - Quadword byte rotates/shifts: `ROTQBYI`, `ROTQMBYI`, `SHLQBYI` using constant `zshuffle` lowering.
   - Immediate mask generation: `FSMBI` using `make_const_vector` constant building.
   - Vector mask bit testing: `FSM`, `FSMH`, `FSMB` using vector splat + bitmask + comparison (`cmtst`), avoiding 34 scalar instructions.
   - `pshufb` intrinsic emulation: in `CPUTranslator.cpp`, lowered on ARM64 to NEON `tbl1` with masked index (`index & 0x8F`).
2. **Reverted / Unsafe Paths Excluded:**
   - AArch64 `tbl2` path in `SPULLVMRecompiler.cpp` remains excluded to prevent the register scavenging emergency spill crash on ARM64 (`Error while trying to spill X8 from class GPR64`).
   - SVE/SVE2 features remain excluded from `AArch64Common.cpp` (`get_cpu_features_string()`).
   - Unsafe `builder.setMAttrs(mattrs)` remains excluded from `JITLLVM.cpp`.
3. **Preservation of Unrelated Backend Work:**
   - Phase 5 scheduler affinity preference, dynamic cpuset readback, and error fallback (`285402486`).
   - RSX unconditional descriptor binding and program loading (`aae290b7a`).
   - RSX Vulkan scratch buffer reuse, semantic pipeline hashing, and descriptor batching (`9f3eb74df`).
   - SPU JIT single-flight deduplication, fine-grained locking, and cache key formatting (`1ce15c156`, `af4523712`).
4. **Differential and Code Generation Tests:**
   - `scripts/tests/test_spu_simd_lowering.cpp`:
     - Bit-exact testing across all shift ranges for `ROTMI`, `ROTMAI`, `SHLI`, `ROTHMI`, `ROTMAHI`, `SHLHI`.
     - Bit-exact testing across all 128 immediate values for constant vs non-constant shift/rotate equivalence.
     - Differential testing of ARM64 NEON `pshufb` emulation against SSSE3 reference across 1,000 randomized iterations and exhaustive 256-code sweep.
     - Attribute string parsing and comma separation test.
   - `scripts/tests/test_spu_simd_lowering.py`:
     - 5/5 unit tests passed.
     - Synthetic `llc` test targeting Cortex-X4 confirms vector instructions (`cmtst`, `tbl`, `ext`, `shl`, `sshr`, `ushr`) generated without register spills (`str q`) or stack allocation (`sub sp, sp`).
   - Full regression suite `scripts/tests/`: 177/177 unit tests passed.

### Build and Deployment Evidence

| Property | Value / Evidence |
|---|---|
| **Backend Submodule Revision** | `285402486c314a722243c3b1011c70a34c5993a9` (branch `samba-android`) |
| **Backend Build Target** | `arm64-v8a` RelWithDebInfo (`librpcsx-android.so`) |
| **Backend Build Output** | Built cleanly via `./build_rpcsx.sh release` |
| **Packaged Library SHA-256** | `af06e41c226acf92aa4961378f398940668faeb0b209857764a87c9c18304c0e` |
| **S3CORE Build ID** | `rpcsx=285402486c314a722243c3b1011c70a34c5993a9 samba=e93784d8c4e4aac796b72a63a8f5a127affa29791b58b8a453aa74eae5c1460c patch_sha256=none build_type=RelWithDebInfo abi=arm64-v8a ndk=30.0.14904198-beta1 cmake=3.22.1 compiler=Clang_21.0.0 flags=bd8a4ec75966-OFF` |
| **Release APK Artifact** | `app/build/outputs/apk/standard/release/samba-s3-standard-release.apk` |
| **Release APK SHA-256** | `11aac28bee44d2d610a29009881e02cbc1bdb3aae4e9d651176776aba140a481` |
| **Poco X6 Pro (Y5WWBMJVOZSK4HU8)** | Installed: `Success`<br>On-device base.apk SHA-256: `11aac28bee44d2d610a29009881e02cbc1bdb3aae4e9d651176776aba140a481`<br>Extracted librpcsx.so SHA-256: `af06e41c226acf92aa4961378f398940668faeb0b209857764a87c9c18304c0e` |
| **OnePlus 13R (d30a1726)** | Installed: `Success`<br>On-device base.apk SHA-256: `11aac28bee44d2d610a29009881e02cbc1bdb3aae4e9d651176776aba140a481`<br>Extracted librpcsx.so SHA-256: `af06e41c226acf92aa4961378f398940668faeb0b209857764a87c9c18304c0e` |

### Open Qualification Gate
- Real-gameplay transition and performance qualification (e.g. Gaia scene transition and sustained frametime profiling) remains explicitly open and unverified per instructions, avoiding premature claims of 100% completion without gameplay testing.
