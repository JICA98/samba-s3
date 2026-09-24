# Plan: Samba S3 PS3 backend reliability and ARM optimization

Date: 2026-09-24  
PLAN_STATUS: READY_FOR_REVIEW  
PASS: 1  
ITERATION: 1  
Scope: planning only. No implementation, backend fork, push, build, installation, or benchmark authorized by this document's creation.

## 1. Goal and expected outcome

Make Samba S3's current PS3 backend reproducibly buildable and identifiable, then improve measured ARM performance without breaking guest correctness, compatibility, thermal behavior, or memory stability.

The output is not a promise of a particular FPS gain. The first deliverable is a trustworthy baseline. Subsequent changes must demonstrate improvement against that baseline or fix a reproduced correctness defect.

Priorities:

1. Preserve and publish local backend work.
2. Ensure source changes reach the packaged and loaded core.
3. Establish reproducible performance and correctness measurements.
4. Correct CPU-target, affinity, timer, and synchronization problems individually.
5. Optimize startup/JIT behavior only where profiling justifies it.
6. Port relevant missing upstream RPCS3 improvements deliberately.

## 2. Backend identity and scope boundaries

Samba's backend checkout is `app/src/main/cpp/rpcsx`, originating from `RPCSX/rpcsx`. Its Android build uses RPCS3-derived PS3 emulation:

```cmake
set(WITH_PS4 off)
set(WITH_PS3 on)
```

Evidence: `app/src/main/cpp/rpcsx/android/CMakeLists.txt:91–92`; links `rpcs3`, `rpcsx::fw::ps3`, and `rx` at lines 120–123.

Therefore:

- Fork the existing RPCSX backend to preserve the Android integration.
- Treat official `RPCS3/rpcs3` as the upstream optimization/reference source for its PS3-derived components.
- Do not replace the checkout with stock RPCS3 or blindly merge unrelated trees.
- Keep x86_64 building and compatible; ARM-specific changes must not silently alter x86 behavior.
- Do not remove bounded PPU compilation, Android GPU synchronization workarounds, or safety checks merely to resemble desktop code.
- Do not begin an ARM SPU first-tier compiler rewrite in the initial implementation.

### Reviewed source snapshot

| Component | Revision | Meaning |
|---|---|---|
| Local backend | `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc` | Reviewed local commit, plus dirty changes |
| RPCSX master | `e8ae1481ab7ba04d5c6bef89dd852aabba2c88ff` | Public tip checked September 24 |
| RPCSX dev | `b41e09a047549a5506b0501194fd16919c0d615f` | Separate Android/PS3 reference branch |
| Official RPCS3 master | `c82221f292880272d11302ecf1206f81a970a5ab` | Public tip checked September 24 |

Earlier inspection found 14 local commits beyond local `origin/master`, 40 modified files, +2408/-430 lines. Recheck these values at implementation start; preserve any intervening user changes. Being ahead of RPCSX master does not establish parity with current RPCS3.

No source ZIP was available in the workspace. ZIP equivalence, exact imported RPCS3 revision, installed core identity, and comparative FPS remain unverified.

## 3. Evidence and research sources

### Repository evidence

- `docs/reviews/rpcsx-backend-review.md`: targeted backend review and previously reproduced patch-preflight failures.
- `app/build.gradle.kts:162–209`: core build/verification gating and packaging dependencies.
- `build_rpcsx.sh:55–135`: patch application, generated identity, per-ABI builds and copy.
- `scripts/verify-apk-core.sh:20–119`: existing artifact checks and permissive fallbacks.
- `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt:274–296`: packaged core loading.
- `app/src/main/cpp/native-lib.cpp:952–1009,1573–1577`: dlopen, export resolution, identity retrieval.
- `.github/workflows/build.yml:38–113`: recursive checkout, native build, packaging checks.
- `scripts/tests/test_debug_bridge.py`: host-side script test precedent.
- `docs/testers/2026-09-01-gta-performance-power-regression.md`: historical A/B evidence structure; different device, not a current baseline.

### Official/reference sources

- https://github.com/RPCSX/rpcsx — repository holding the current backend integration.
- https://github.com/RPCS3/rpcs3 — reference implementation for selective PS3 updates.
- https://api.github.com/repos/RPCSX/rpcsx/commits/master — checked public reference revision.
- https://api.github.com/repos/RPCS3/rpcs3/commits/master — checked official PS3 reference revision.
- https://cli.github.com/manual/gh_repo_fork — fork options verified in the preceding review using Context7.
- https://docs.gradle.org/current/userguide/incremental_build.html — implementation reference for declared inputs/outputs; exact integration must match the installed Gradle/AGP versions.
- https://gcc.gnu.org/onlinedocs/gcc/Other-Builtins.html — reference for instruction-cache clearing semantics; verify applicable NDK/compiler implementation before selecting a primitive.
- https://docs.vulkan.org/spec/latest/chapters/synchronization.html — reference for event/fence completion and host memory visibility; validate changes against the project's actual Vulkan usage.

Research limitation: additional documentation/reference-checkout commands during planning did not return usable results. No new reference clone inspection or exact latest API signature is claimed. Resolve exact Gradle, Android/LLVM, and Vulkan API details with current documentation before implementing the relevant phase. Static findings below are grounded in repository inspection, not inferred upstream fixes.

## 4. Architecture relevant to the work

### Build and load

1. `build_rpcsx.sh` configures the backend Android CMake project per ABI.
2. Backend output is copied to `app/src/main/jniLibs/<abi>/librpcsx-android.so`.
3. Gradle packages native libraries; legacy packaging is enabled (`app/build.gradle.kts:122–125`).
4. App entry points obtain `ApplicationInfo.nativeLibraryDir`.
5. `RPCSX.openLibrary()` loads that directory's `librpcsx-android.so` through the JNI wrapper.
6. Native code resolves the backend API and optional build identity export.

Current inspected app paths use the packaged core. No independent downloaded-core replacement path was found. Runtime dlopen must not be confused with runtime download; repository prose describing a downloaded core needs reconciliation during implementation.

Entry points include the launcher, `RPCSXActivity`, and PPU worker processes. Loaded-core evidence must cover all of them, ideally through the existing shared load function rather than duplicated logging.

### Compilation and settings

- PPU and SPU LLVM compilation consume backend configuration.
- Android initialization currently forces `cortex-a34` and saves settings.
- x86 SPU LLVM mode has a quick first tier followed by background ordinary LLVM compilation. ARM uses ordinary LLVM synchronously for newly encountered blocks.
- Bounded Android install/prelaunch PPU compilation limits concurrent work to avoid memory failures.
- CPU affinity and busy waits affect shared CPU/RSX hot paths; changes require cross-architecture guards and device measurements.

## 5. Included implementation review

Backend paths in this section are relative to `app/src/main/cpp/rpcsx/` unless marked parent.

| ID | Priority | Finding and evidence | Confidence / interpretation |
|---|---|---|---|
| B01 | P1 | Parent `app/build.gradle.kts:162–198` trusts existing ABI libraries; checks existence rather than source freshness. Task-name substring gates miss direct core and install task requests. | Confirmed control flow. A backend edit can leave old binaries packaged. |
| B02 | P1 | Parent `build_rpcsx.sh:55–100` patches and then overwrites tracked `android/src/samba-build-id.cpp`. Earlier forward/reverse `git apply --check` both returned 1. | Preflight failure reproduced in reviewed checkout; full build not run. |
| B03 | P1 | Build ID lacks dirty-source/toolchain/ABI identity. Parent `scripts/verify-apk-core.sh:20–119` has fallback outputs, skipped missing ABIs, identity-based waiver of byte mismatch. | Artifact identity insufficient to prove provenance. Account explicitly for legitimate stripping. |
| A01 | P1 | `android/src/rpcsx-android.cpp:2319–2327` writes `g_cfg.core.llvm_cpu = "cortex-a34"`, saves settings. | Confirmed initialization overwrite, not only a failed-detection fallback. Effective final target still requires tracing later settings. |
| A02 | P1 | `rpcs3/Emu/CPU/CPUThread.cpp:638–645`, RSX equivalents override OS mode on Android; `rpcs3/util/Thread.cpp:2902–2948` assumes CPU indices and falls back to `0xFC`. | Confirmed policy/configuration problem; performance impact unmeasured. |
| A03 | P1 | `rx/include/rx/asm.hpp:282–298` uses `/182`; counter frequency already available at `rpcs3/util/sysinfo.cpp:801–808`. | Confirmed nonportable host-delay calibration; not a guest-clock or 182× slowdown claim. |
| C01 | P1 | `rpcs3/Emu/RSX/VK/VKTextureCache.h:298–325` ignores `wait_for_event()` result; timeout exists in `vkutils/sync.cpp:612–661`. | Confirmed unchecked failure; actual visible corruption not reproduced. |
| C02 | P2 investigation | `rpcs3/Emu/Cell/SPUCommonRecompiler.cpp:2031–2058,2093–2124` writes instructions then barriers without explicit cache maintenance at those sites. | Must audit surrounding publication/alias/lifetime guarantees before deciding exact fix. |
| C03 | P2 investigation | `rpcs3/Emu/RSX/VK/vkutils/sync.cpp:407–443` replaces/unmaps label backing while objects hold pointers. | Conditional lifetime concern if a label survives rollover; overlap not demonstrated. |
| P01 | P2 | `SPUThread.cpp:2110–2122,2176–2188`; `SPUCommonRecompiler.cpp:7378–7429,7675–8022`: x86 first tier promotes to ordinary LLVM; ARM dispatch compiles synchronously. | Potential cold-block stall disadvantage, not proof of slower final ARM LLVM code. |
| P02 | P2 | `PPUThread.cpp:247–280,329–333,4529–4535`: source inventory precedes manifest-hit handling and can hash complete MSELF files. | Startup work remains; frequency and cost require tracing. |
| P03 | P2 investigation | `rpcs3/util/JITLLVM.cpp:186–232,292–310`: large reserved arenas, incremental commitment, decommit without release on teardown. | Virtual-range retention; not equivalent RSS leak. Ownership must be proved before releasing. |
| S01 | P2 | Parent `PpuReadinessStore.kt:95–102` queries `"Core llvm_cpu"`; backend config declares `"Use LLVM CPU"` in `rpcs3/Emu/system_config.h:30`. | Readiness may not reflect effective target; verify helper encoding and return handling before correction. |

### Claims deliberately not used as premises

- Both Vulkan host event backends poll (`vkutils/sync.cpp:383–390,612–661`). Mapped-label fallback does not replace a blocking x86 event wait.
- An 8 MiB stack reservation is not 8 MiB resident memory. No demonstrated causal link from stack size to the PPU compiler cap.
- `std::atomic::fetch_or` does not prove a library call; inspect the shipped machine code.
- Release script selects `RelWithDebInfo`, not Debug (`build_rpcsx.sh:10–13`).
- Phone-versus-desktop FPS cannot isolate architecture, source age, compiler, driver, thermal, or configuration differences.

## 6. Ordered implementation phases

Each phase is a separate reviewable change. Performance experiments require the provenance and baseline gates first. Correctness discoveries can advance ahead of optimization, but must produce a new baseline if behavior changes.

### Phase 0 — Preserve and publish the backend

**Purpose:** make the exact source retrievable before changing build or performance behavior.

1. Reinspect parent/submodule status, remotes, detached HEAD, nested submodule revisions, staged and untracked files.
2. Back up worktree and Git metadata, including the parent's `.git/modules`; preserve dirty and untracked content separately from committed history.
3. Create a writable fork of RPCSX, suggested name `<owner>/samba-s3-core`, retaining needed non-default branches.
4. Create a new branch at the existing local HEAD; keep the upstream remote and add a publishing remote. Do not reset to upstream or force-recreate an existing branch.
5. Review and commit intended backend source changes. Exclude generated stamps, binaries, credentials, and unrelated changes. Preserve excluded source work rather than discarding it.
6. Push backend history first. Update the parent's submodule URL and exact gitlink only after publication succeeds.
7. Test recursive checkout in a separate directory. Record nested dependency SHAs.

**Gate G0:** fresh recursive checkout retrieves the published backend and all required dependency commits. Any expected applied patch is explicitly recorded; no undocumented dirty source is required.

**Rollback:** retain original checkout/backup and prior frontend pin. Never use a destructive reset to “clean up” the only copy of backend work.

### Phase 1 — Reproducible build and artifact identity

**Purpose:** ensure an optimization is actually in the tested binary.

#### 1A. Remove generated-file/patch conflict

- Generate the build identity translation unit under the per-ABI build directory and compile it through Android CMake.
- Generate before configure/build as required by CMake dependencies; update only when identity content changes.
- Keep generated output out of tracked backend source and the persistent patch.
- Migrate reviewed backend changes into fork commits. Reconcile the remaining patch against that exact revision; do not simply regenerate or empty it blindly.
- A repeated unchanged build must pass. An unexpectedly incompatible patch must fail with an actionable message before packaging.

#### 1B. Define a small per-ABI provenance manifest

Use existing scripts and standard-library hashing/JSON facilities; no new build framework. Record:

- Manifest schema version; backend revision; exact nested dependency revisions.
- Relevant backend tracked/untracked source content digest for local experimental builds, with deliberate exclusions for build outputs and generated identity.
- Relevant integration script/CMake/patch content digest.
- ABI, build type, NDK/compiler identity, CMake identity, effective compile/link options.
- Actual linked LLVM/dependency identity. Do not infer the linked LLVM version from a single version variable: reconcile CMake declarations, vendored submodule, downloaded assets, and link inputs.
- Input dependency archive checksums where archives feed the build.
- Hash of produced library, recorded after linking outside the embedded string.

Avoid self-reference: embedded source/config identity must not depend on the library hash or its generated source. Frontend documentation-only changes should not force a backend rebuild. Exclude signing secrets, workstation paths, and timestamps from stable input identity.

Release preparation must use a declared reproducible source state: committed source plus an explicit expected patch, or fully committed backend changes. Intentional experimental dirty builds may use content digests, but a bare `dirty=true` is insufficient.

#### 1C. Replace existence-only Gradle gating

- Express real inputs/outputs and task dependencies; remove task-name string heuristics.
- Direct core build and install/assemble/bundle requests must invoke or correctly reuse the same provenance-validated build.
- Preserve unit-test execution without requiring NDK/native rebuilds when no package task needs native outputs.
- Prefer always invoking the lightweight incremental configure/build path initially if a correct complete input model would otherwise be fragile. Let the native build system skip unchanged compilation; do not optimize task skipping before correctness.
- Explicitly derive required ABIs for each packaging task. Standard release APK/AAB currently requires arm64-v8a and x86_64.
- An explicit single-ABI developer build must not satisfy a later two-ABI packaging request.
- Use one shared policy for `build_and_install.sh`, Gradle, and CI; no independent existence checks.

#### 1D. Verify packaged bytes and loaded identity

- Extend the existing APK verifier; remove arbitrary `.cxx` fallback selection and silent ABI skipping for required ABIs.
- Verify APK and standard release AAB contents against the exact packaging-stage library. If stripping changes bytes, identify and hash the actual transformed output rather than waiving differences because text IDs match.
- Bind provenance to the packaging artifact and its per-ABI library hashes. Reject stale identity, wrong ABI, missing required library, or unexplained byte mismatch.
- Normalize missing/empty backend IDs as unknown, never successful evidence.
- Log successful core load centrally in `RPCSX.openLibrary()`: actual path, process, ABI, source/config identity. Reuse it for launcher, direct gameplay, and worker processes.
- Hash the loaded file once per process only if needed to bind runtime evidence to packaging; never hash libraries per frame.
- Preserve graceful diagnosis for older cores missing the identity export; such runs cannot satisfy the optimization baseline gate.
- Record immutable artifacts, per-ABI manifests, and IDs in CI. Reconcile the existing release-signing environment mismatch before using CI as a release gate; do not broaden this into an unrelated signing redesign.

**Gate G1:** clean, incremental, changed-source, changed-config, changed-patch, ABI-missing, and malformed-manifest cases behave correctly. APK/AAB bytes and all tested process load IDs match the intended core. Two unchanged builds succeed without native recompilation.

### Phase 2 — Establish controlled baseline and measurement

**Purpose:** locate actual bottlenecks before selecting optimization work.

Required targets: Poco X6 Pro and OnePlus 13R, identified by serial. Follow repository policy: install **release APKs only**, update **both devices after every release build**. If either device is unavailable, record the validation as blocked; do not call a one-device result complete.

Preserve user app data. Do not uninstall routinely. If signatures prevent an in-place update, stop and arrange backup/approved recovery before uninstalling.

For each run, record:

| Category | Required evidence |
|---|---|
| Artifact | Frontend/backend revisions, per-ABI source/config ID, APK/library hashes, loaded ID |
| Device | Serial/model, OS/kernel, available/allowed CPUs, CPU features, governor/thermal state if readable |
| Graphics | Exact driver identity, renderer, resolution scale, relevant compatibility settings |
| JIT | Configured and effective LLVM CPU/features, PPU/SPU modes, compiler worker count, cache state |
| Workload | Game title/version/content ID, scenario/save, exact scene and input sequence |
| Timing | Preparation, launch-to-interactive, frame-time median/p95/p99, guest progress, runtime compile time/count |
| Resources | CPU execution/waits, GPU time where valid counters exist, RSS/peak RSS, virtual mappings, temperature/throttling |
| Correctness | Visual reference, sound/progression, save/load, background/resume, crashes/timeouts |

Separate three workloads:

1. **Cold preparation:** isolated test cache, compilation and peak memory.
2. **Warm launch:** previously prepared cache, time to interactive and remaining compilation.
3. **Sustained gameplay:** fixed scene, steady frame times and thermal behavior.

Use at least two legally available representative titles/scenes: one CPU/SPU-heavy and one rendering/synchronization-heavy. Select actual available content at execution; do not fabricate scenarios. Add a large-preparation workload when evaluating compiler memory changes.

Use existing launch/controller/log scripts through their device-test skills. Validate that automation works with release builds before relying on debug broadcasts. `scripts/perf/capture-sambas3.sh` currently does not implement its apparent duration; do not count its snapshot as sustained measurement. `tools/benchmark-gta.sh` requires scene and startup-log preservation checks.

Collect startup identity before any log clearing. Collect failure evidence once before relaunch. Avoid high-volume logs in hot loops; use aggregated counters and existing profiling facilities. Missing permissions/counters are recorded as unavailable, not zero.

**Protocol:** at least five paired A/B runs per performance candidate per device/scenario, alternated or randomized. Use equal cache conditions, a fixed warm-up, a fixed measured interval of at least five minutes for sustained runs, and an agreed start-temperature band (target within 2°C when sensors permit). Extend duration for suspected thermal regressions. Keep settings, driver, input progression, charging mode, and ambient conditions fixed.

**Gate G2:** baseline variability measured; source/load provenance verified; cold and warm effects separated; both devices complete. If telemetry cannot measure frame-time distributions reliably, resolve measurement first rather than substituting overlay FPS.

### Phase 3 — Correct effective LLVM target and cache identity

**Addresses:** A01, S01.

1. Trace all initialization and later global/per-game settings writes. Establish whether the A34 assignment was a compatibility workaround and whether it affects x86 initialization.
2. Stop unconditional persistent overwriting of user configuration. Preserve explicit valid user choices.
3. Resolve automatic target conservatively against the LLVM version actually linked and the instruction features guaranteed on allowed execution CPUs.
4. Separate tuning/scheduling preference from instruction-set availability where supported. Do not choose a “big core” target whose enabled instructions become unsafe after migration to another allowed core.
5. Unknown CPU names and unsupported target names must use a documented compatible fallback or an explicit diagnostic, not silently claim optimized targeting.
6. Log configured target, effective target, and effective feature set once per compiler context or session.
7. Correct readiness key lookup using the actual configuration path encoding; reject unresolved `{}` as a meaningful target.
8. Audit PPU/SPU cache keys. Effective target/features and backend cache ABI changes must invalidate affected code caches. Preserve game data; avoid unrelated cache wipes.
9. Compare existing A34 behavior with the candidate using separate caches; validate both cold compilation and warm code quality.

**Gate G3:** explicit settings survive initialization/relaunch, effective features are safe, stale caches are rejected, x86 remains valid, both phones complete correctness and A/B checks. Retain automatic targeting only with measured benefit or a demonstrated configuration-correctness improvement.

### Phase 4 — Restore OS scheduling mode; test affinity separately

**Addresses:** A02.

1. Make OS-managed mode genuinely avoid backend affinity restriction, or restore a captured baseline allowed mask when transitioning from an explicitly pinned mode. Do not “restore” by assuming every online CPU is allowed.
2. Preserve existing user-selectable modes where feasible. Remove unconditional Android bypasses consistently for CPU, RSX, and offload threads.
3. Eliminate `0xFC` fallback. Build explicit policies only from discovered, available, process-allowed CPUs. If discovery is unavailable, fall back to OS management.
4. Keep topology discovery out of hot loops. Validate heterogeneous, offline, restricted-mask, and more-than-eight-CPU cases.
5. Log requested/effective affinity and errno on failure; failure must not silently imply successful pinning.
6. Start by benchmarking current policy versus genuine OS mode. Add a topology-aware policy only if the comparison shows useful remaining scope.

**Gate G4:** no empty/out-of-allowance requests; OS mode behaves as selected; synthetic mask checks pass; no lifecycle regressions; measure contention, frame-time tails, and thermals independently from CPU-target changes.

### Phase 5 — Define host busy-wait units and remove fixed calibration

**Addresses:** A03.

1. Inventory every `busy_wait` caller, including non-Android ARM and x86. Classify arguments as raw counter ticks, historical cycle-like budgets, or desired duration.
2. Document the intended contract. Preserve callers that genuinely use raw counter units; do not apply a universal second conversion.
3. Prefer an existing duration/counter utility. If necessary, introduce one minimal shared conversion helper rather than separate ad hoc ratios.
4. Use actual counter frequency for duration-based waits; handle zero/subtick values, rounding, overflow, and unsupported-frequency fallback explicitly.
5. Read invariant frequency outside hot loops. Preserve polling condition checks and yielding behavior.
6. Do not change guest timer emulation as part of this host-backoff fix.

**Gate G5:** deterministic conversion checks cover 19.2/24/100 MHz and boundary inputs; microbenchmarks record actual delay/overshoot; emulator A/B checks show no correctness regression or increased spin/thermal cost. Audit x86 behavior explicitly.

### Phase 6 — Synchronization and executable-code correctness

**Addresses:** C01, with C02/C03 investigation gates.

#### GPU wait failures

- Trace readback callers and existing device-lost/timeout handling.
- On wait failure, do not expose readback data as completed or mark cache state synchronized.
- Choose recovery using existing renderer error propagation. Avoid unbounded retry or substituting an arbitrary larger timeout.
- Differentiate successful completion, legitimate no-work state, timeout, invalid state, and device loss.
- Audit null-event success, skipped pipeline draws, and dropped SPU DMA independently; do not convert every fallback into fatal failure without proving contracts.
- Keep GPU-to-host memory dependencies and driver workarounds unless independently demonstrated unnecessary.

#### ARM instruction publication

- Trace writable/executable aliases, code ownership, cross-thread publication, and all surrounding cache flushes.
- Establish whether cache maintenance is genuinely missing; if so, use the compiler/platform-supported range maintenance path for generated code.
- Correct cache visibility does not by itself make concurrent instruction patching safe. Prove alignment, atomicity, ordering, and executing-thread synchronization.
- Cover initial publication and later patch replacement, including code crossing cache-line boundaries.

#### Label rollover

- Reproduce or disprove an outstanding label surviving pool rollover using a reduced-capacity test facility rather than waiting for a full production pool.
- If reproduced, retain backing generations until both GPU work and CPU label users are finished; reuse an existing retirement mechanism.
- Test teardown and device-loss paths. Avoid introducing a global wait-idle on each allocation.

**Gate G6:** injected failure does not consume incomplete data; successful readback unchanged; ARM publication stress passes on both devices; rollover either demonstrably safe or fixed with lifecycle coverage. Correctness gains are not advertised as FPS gains.

### Phase 7 — Profile-gated startup and compiler-memory optimization

**Addresses:** P02/P03. Keep install/prelaunch concurrency caps initially.

1. Instrument aggregate time spent in source enumeration, MSELF hashing, manifest validation, module initialization/finalization, and LLVM compilation.
2. Quantify transient/resident memory and virtual mappings separately. Record arena allocations and live ownership across bounded batches.
3. If manifest validation dominates warm startup, reduce duplicate inventory within a correctly bounded operation/session. Preserve invalidation on actual game, patch, configuration, cache-schema, and source changes.
4. Do not replace content validation blindly with path/size/mtime; storage-provider timestamp behavior may be unreliable. If a narrower install-generation key is used, prove all mutation paths advance it.
5. If retained virtual arenas matter, prove no generated code or metadata retains references before releasing or reusing memory.
6. Adjust arena sizing or compilation concurrency only after memory measurements and ownership proof. Keep cancellation/resume/batching behavior intact.

**Gate G7:** measurable preparation/warm-launch improvement, unchanged invalidation correctness, no OOM, no growing live-mapping trend over repeated batches, correct cancellation/resume and large-title preparation on both phones.

### Phase 8 — Selective upstream updates and optional SPU tiering research

1. Refresh and pin official RPCS3 reference revision at execution time; record exact fetched SHA.
2. Compare ARM code generation, SPU instruction lowering, PPU scheduling, LLVM transforms, memory handling, and Vulkan synchronization at file/function level.
3. For each candidate, record upstream commit/PR, dependencies, local equivalent, feature prerequisites, cache impact, test coverage, and measured objective.
4. Classify present, modified-equivalent, missing, or incompatible. Do not cherry-pick based solely on a newer date or version marker.
5. Port the smallest useful candidate preserving Android exports/integration and required attribution/licenses.
6. Gate SVE/SVE2 or other ISA-specific paths using supported host capability detection and execution placement, never device brand alone.
7. Compare old versus updated fork on the same x86 host where possible to isolate source-age changes from architecture differences.

Only propose a separate ARM SPU first-tier project if baseline traces show uncached SPU LLVM stalls materially limit the target workload after simpler changes. Such a project requires its own design, correctness tests, invalidation/publication model, and maintenance estimate. It is not an initial optimization deliverable.

**Gate G8:** every imported change has recorded provenance, dependency review, successful required-ABI build, correctness evidence, and objective measurements. No blind whole-tree merge.

## 7. File-level change map

Implementation may narrow this map after current-state inspection; conditional rows are not authorization for speculative edits.

| File / component | Proposed change | Phase |
|---|---|---|
| `.gitmodules` and backend gitlink | Writable fork URL, published exact revision | 0 |
| `build_rpcsx.sh` | Out-of-tree identity, actual input provenance, per-ABI validation, reliable incremental execution | 1 |
| `patches/rpcsx-submodule-changes.patch` | Reconcile committed changes, remove generated identity | 1 |
| `app/src/main/cpp/rpcsx/android/CMakeLists.txt` | Compile generated identity from build directory | 1 |
| `app/build.gradle.kts` | Real task dependencies/inputs/outputs, required ABI validation | 1 |
| `scripts/verify-apk-core.sh` | Strict artifact/ABI checks; packaging-stage hash handling; AAB support if kept in same helper | 1 |
| `build_and_install.sh` | Reuse provenance gate; preserve release-only, both-device requirement | 1–2 |
| `.github/workflows/build.yml` | Reproducible standard release artifacts, manifests, APK/AAB verification; reconcile signing inputs if blocking | 1 |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt` | Central successful-load identity evidence | 1 |
| Existing entry-point ID logging | Consolidate only where duplicated by central reporting | 1 |
| `scripts/tests/test_core_provenance.py` (new, if needed) | Small standard-library host regression suite for artifact/provenance behavior | 1 |
| Existing benchmark/log helpers | Small release-compatible collection fixes only where needed | 2 |
| Backend `android/src/rpcsx-android.cpp` | Preserve target settings; report configured/effective CPU accurately | 3 |
| Backend `rpcs3/util/JITLLVM.cpp`, AArch64 CPU helper | Validated CPU selection; conditional arena ownership work | 3, 7 |
| Parent `PpuReadinessStore.kt`; relevant PPU/SPU cache identity | Correct effective target and invalidation inputs | 3 |
| Backend CPU/RSX/offload thread setup; `rpcs3/util/Thread.cpp` | OS mode, allowed-mask handling, affinity diagnostics | 4 |
| Backend `rx/include/rx/asm.hpp`; audited callers | Explicit host-wait contract/frequency conversion | 5 |
| Backend `VKTextureCache.h`, `vkutils/sync.cpp/.h` | Wait-error propagation; conditional label-lifetime repair | 6 |
| Backend `SPUCommonRecompiler.cpp` | Conditional instruction-publication correction after contract audit | 6 |
| Backend `PPUThread.cpp` | Conditional elimination of measured duplicate preparation work | 7 |
| Specific upstream-port files | Listed per reviewed candidate, not predetermined bulk changes | 8 |

Keep one runnable focused regression check for each nontrivial behavior change; use existing infrastructure rather than introducing a test framework. Do not add tests that only restate source text when behavior can be tested.

## 8. Verification matrix

### Build/provenance checks

- Fresh recursive checkout, both ABIs, no copied libraries.
- Repeated unchanged build: passes, no unnecessary native recompilation.
- Edit backend source: intended affected ABI builds and produces changed provenance.
- Edit relevant patch/config/toolchain identity: stale artifact rejected/rebuilt.
- Edit unrelated documentation: no core rebuild caused solely by frontend HEAD change.
- Missing one required ABI, absent manifest, unknown build ID, wrong ABI, corrupted bytes: fail clearly.
- Same source ID but different unexplained bytes: fail; no identity-only waiver.
- Direct core request, assemble, install, bundle: consistent native prerequisites.
- Unit tests alone: no unnecessary NDK build.
- Standard release APK and AAB verified. Do not require or ship playstore release variant.

### Existing project commands to preserve

```bash
./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest
./gradlew assembleStandardRelease
./gradlew bundleStandardRelease
```

These are future implementation checks, not commands executed for this plan. Every release build triggers the repository requirement to update both target devices; schedule artifact builds accordingly. Unit tests may use debug test variants; device installs remain release-only.

### Runtime correctness matrix

Both phones: first launch, warm launch, cold compilation, in-game steady state, pause/resume, background/foreground, exit/relaunch, savestate save/load where supported, compilation cancellation/resume, and repeated sessions. Include visual/progression checks, not FPS alone. Known baseline defects must be recorded separately rather than silently accepted as new regressions.

Both ABIs: compile/link and host-side checks. Obtain x86_64 runtime smoke evidence on a compatible emulator/host before promoting shared-core changes to a release baseline; record unavailable runtime coverage explicitly.

### Performance retention rule

Before each candidate, declare one primary metric: for example p95 frame time, runtime compile stall time, warm launch time, or peak RSS. Do not select whichever metric improves after seeing results.

- Minimum five paired runs; publish all valid samples and exclusions with reasons.
- Target at least 5% improvement in the primary metric, exceeding observed run-to-run noise. Smaller improvements require stronger evidence rather than automatic rejection or unsupported claims.
- No repeatable regression greater than 3% in median/p95/p99 frame times or preparation time on the other required workload/device unless explicitly reviewed as a necessary correctness tradeoff.
- No new visual/progression failure, crash, invalid instruction, synchronization error, cache misuse, or OOM.
- No unexplained peak RSS increase above 5% or new throttling pattern. If sensors are unavailable, mark energy/thermal conclusions unverified.
- If confidence intervals overlap materially or noise exceeds the proposed gain, extend measurement or mark inconclusive. Do not average away a bad device or scene.

These thresholds are decision rules, not promised gains. Correctness fixes may be retained without speedups, with any cost explicitly reported.

## 9. Acceptance criteria

- [ ] AC01: Exact backend history and required changes retrievable from the writable fork by fresh recursive checkout.
- [ ] AC02: Generated build identity no longer conflicts with tracked patch application; repeated builds pass.
- [ ] AC03: Every required packaged ABI matches declared source/config/toolchain provenance and packaging-stage bytes.
- [ ] AC04: Launcher, direct gameplay, and worker process loaded-core evidence matches the tested artifact.
- [ ] AC05: Standard release APK/AAB gates enforce ABI completeness; unit-test-only tasks remain independent of native packaging.
- [ ] AC06: Explicit LLVM CPU settings persist; effective CPU/features are safe, logged, and represented in relevant cache identity.
- [ ] AC07: OS scheduler mode is genuine; explicit affinity respects allowed topology and failure reporting.
- [ ] AC08: Host busy-wait units are documented and tested; fixed device-independent ratio removed only after caller audit.
- [ ] AC09: GPU wait failures cannot be treated as completed readbacks; instruction-publication and label-lifetime concerns have an evidence-backed disposition.
- [ ] AC10: Baseline and retained experiments verified on both Poco X6 Pro and OnePlus 13R using release APKs, identical controlled workloads, and preserved evidence.
- [ ] AC11: Startup/memory changes occur only for measured bottlenecks and preserve invalidation, bounded preparation, cancellation, and stability.
- [ ] AC12: Upstream ports have exact provenance/dependencies and capability gating; shared changes retain x86_64 build and required runtime coverage.
- [ ] AC13: Every optimization claim includes raw paired measurements; inconclusive or harmful experiments are not enabled by default.

## 10. Risks, mitigations, and open decisions

| Risk / unknown | Mitigation / decision owner |
|---|---|
| Local source changes after this snapshot | Implementer rechecks status/diff and protects user work before each phase. |
| Generated patch plus commits double-apply behavior | Explicit clean-source contract and repeat-build tests; reconcile before performance changes. |
| Unknown provenance of linked LLVM/dependency archives | Inspect actual configure/link inputs; record immutable versions/checksums before CPU targeting. |
| APK stripping changes bytes | Compare declared packaging-stage outputs; do not weaken verification. |
| CPU target fixes expose unsupported instructions | Allowed-CPU feature intersection, conservative fallback, migration tests, cache invalidation. |
| Cache identity correction reintroduces expensive preparation | Scope invalidation correctly; record one-time cold cost separately from warm benefit. |
| Affinity or timer changes trade latency for power | Separate experiments; monitor waits, thermal state, and long-session behavior. |
| Driver workaround required on only some devices | Keep current safe fallback until per-driver correctness evidence supports narrowing. |
| Missing device/permissions/automation support | Mark affected gate blocked; no debug-install workaround on target phones. |
| CI signing mismatch blocks release reproducibility | Reconcile existing secret/config input contract with minimal scope, without exposing secrets. |
| Reference/API research incomplete | Fetch exact current documentation before implementing each API-sensitive phase; do not invent signatures. |
| Tests unavailable for emulator internals | Use minimal host checks and device fault/stress evidence; state coverage limitations explicitly. |
| Upstream history too divergent for clean cherry-picks | File/function-level ports with recorded provenance and dependencies. |

Open execution inputs: fork owner/name, available representative games/scenes, target-device serials, x86 runtime environment, access to signing configuration, approved backup location. These do not prevent planning; they gate the corresponding execution steps.

## 11. Review and handoff

This document includes the backend findings; no separate review file is required for the current request.

Independent plan review must check:

1. Claims remain supported by current repository code.
2. Fork preservation occurs before source/patch reconciliation.
3. Build identity excludes self-reference and distinguishes dirty development input.
4. Packaged and loaded identities are both checked, including ABI/stripping behavior.
5. No assumed FPS gain, unsafe CPU feature enablement, blind workaround removal, or unbounded compiler concurrency.
6. Baseline/correctness/performance gates precede promotion of changes.
7. Device policy, cache invalidation, cancellation, and x86 compatibility are covered.
8. Conditional investigations do not become unconditional speculative rewrites.

Review disposition: pending. Implementation is not started by approval of this planning document; user requested only this Markdown plan.
