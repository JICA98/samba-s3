# Samba S3: sustained GOW III performance and crash-elimination work plan

Date: 2026-09-24. Status: implementation plan, not a claim of completed optimization.

## 1. Mission and evidence boundaries

Target God of War III on the OnePlus 13R / Snapdragon 8 Gen 3 with **60 genuinely rendered game frames per second at correct game speed**, without recurrent gameplay crashes, while minimizing CPU time and energy per useful frame. Preserve stock thermal protection and supported Android operation. Settings and game patches are permitted, but every visual or emulation trade-off must be labeled. Repeated presentation of an old frame, frame generation, faster menus, frame skipping, and slower guest time do not satisfy the target.

Reviewed public snapshot:

| Layer | Repository | Revision |
|---|---|---|
| Frontend | JICA98/samba-s3 | `270d72a56a926375f16f6c9a22474acdda99cd8c` |
| Backend | abhay-byte/samba-s3-core, branch `samba-android` | `ed8ba6c12c218249524a441b79f48d6bae842394` |
| Core named by the revised benchmark | Embedded S3CORE identifier | `05ed8aed420f7cf4975835f4baebda91cec14748` |

The frontend gitlink now matches the backend branch above. Earlier observations of an older frontend pin are superseded. The benchmark identifies a different core, so do not reuse its results to certify the current binary without a revision comparison and a new run. Source identifiers in this plan are immutable review anchors, not instructions to discard later work. [S01–S04]

This review inspected selected frontend/backend source paths, the published plan/handoff section, the revised benchmark, its thread snapshot and exit history. It did not execute a native build, the tests, or a device benchmark. See REVIEW.md for confirmed observations and unresolved hypotheses. References [Sxx] are in SOURCE_REFERENCES.json.

### Definition of progress

Use 10, 15, 20, 30 and 60 FPS as measured milestones, not forecasts. Against the report's 7.44 FPS figure, 60 requires approximately 8.06 times the throughput; against 6.78, approximately 8.85 times. These ratios are arithmetic, not performance predictions. A final 60 FPS claim must pass the qualification in Section 15. A development session that does not reach it must publish the best stable result, remaining measured bottleneck, and exact next experiment instead of marking the goal complete.

## 2. Non-negotiable engineering rules

Preserve existing Android JNI interfaces, PPU preparation/stop/retry, direct ISO access, patch management, save-state loading, driver loading and crash evidence. Do not disable memory ordering, instruction-cache maintenance, DMA completion, GPU readbacks, address validation, or guest work merely to raise FPS. A faster run with broken geometry, stale textures, skipped jobs or altered simulation is rejected.

Keep all guest SPU contexts. A host worker-concurrency experiment is not permission to delete or stop guest SPUs. Do not apply x86-specific AVX assumptions to ARM; do not enable SVE/SVE2 or other instructions merely from a marketing CPU name. Preserve the validated capability and affinity intersection.

All performance changes need an isolated toggle or reversible commit, a hypothesis, an expected counter change, one principal outcome metric, correctness tests and paired on-device evidence. No broad rewrite, global fast-math, blanket barrier removal, universal 'Mega' SPU setting, or driver flag collection without attribution.

Publish backend commits to the writable core fork first, then update the frontend gitlink. Never force-reset the user's active checkout. Preserve a known-good APK, matching unstripped libraries, manifests, configuration, patch receipts, logs and saves before changing the baseline. Do not commit game/firmware files, signing material, private account data or enormous raw traces to a public repository without review.

## 3. Work order and ownership

| Order | Ticket | Main ownership | Dependency / gate |
|---|---|---|---|
| 1 | B00: reproducible baseline | Build + reviewer | Current APK must match pinned source |
| 2 | M01: truthful, inexpensive metrics | Frontend + tools | Must precede GPU-utilization conclusions |
| 3 | C02: crash classification | Native + tester | Run in parallel with M01 |
| 4 | P03: critical-path profile | Performance/tools | Needs B00 and trustworthy clocks |
| 5 | F04: auxiliary CPU work | Frontend + logging | Attribute hot DefaultDispatch first |
| 6 | S05: scheduling and waits | Native CPU | Profile must show queueing/spinning |
| 7 | J06: SPU execution/code generation | Native CPU/JIT | Hot guest blocks identified |
| 8 | J07: runtime compilation/cache | Native JIT | Compilation measured during slow windows |
| 9 | V08: RSX/Vulkan work and readbacks | Native graphics | CPU and GPU timeline available |
| 10 | R09: memory/lifetime | Native + tester | Run throughout all phases |
| 11 | G10: verified GOW profile | Frontend + game tester | Exact executable/hash and patch receipts |
| 12 | D11: Samba Turnip candidate | Graphics/driver | Driver-attributed cost or reproducible fault |
| 13 | U12: upstream synchronization | Native + reviewer | Commit-by-commit dependency analysis |
| 14 | Q13: sustained acceptance | Independent reviewer/tester | All retained candidates rebuilt cleanly |

The CPU/JIT, frontend and renderer workers may investigate separate branches concurrently. A single integrator owns the combined baseline, core gitlink and experiment registry. Do not benchmark overlapping workers on the same phone.

## 4. B00 — Bind the tested artifact to its actual source

### Relevant source

Frontend `app/build.gradle.kts`, `build_rpcsx.sh`, `scripts/lib/core_provenance.py`, `scripts/verify-apk-core.sh`, `.gitmodules`; backend `android/CMakeLists.txt` and generated S3CORE identity. The current Gradle code already calls provenance verification and packages arm64 outputs; do not reintroduce the obsolete 'skip if both .so files exist' review finding. [S05]

### Work

Record frontend HEAD, backend gitlink, checked-out backend HEAD, recursive dependency revisions, dirty state, actual compiler/linker commands, NDK/CMake/LLVM identities, build configuration, package ABI and native binary SHA-256. Verify installed package and loaded S3CORE identity rather than trusting an APK filename or UI version.

Compare benchmark core `05ed8aed...` against the pinned `ed8ba6c...`. Record which source changes are performance-affecting, correctness-only, patch-manager-only, or build-only. Do not assume ancestry from hash prefixes. Resolve both commits through Git, preserve their trees and compare them before reusing any historical benchmark.

Use the existing provenance tooling; audit its tests and error handling. Semantic patch normalization must not ignore code changes inside preprocessor lines, string literals, whitespace-sensitive generated content, or a malformed patch. A raw content digest and a complete source-tree identity must remain available even when a second cache-oriented semantic digest exists.

Verify that a direct Gradle package task, assemble task, install task and bundle task cannot consume an unverified old core. Fail only for ABIs actually required by the selected variant. Validate before installation, not merely after an unrelated task finishes. Keep profiling and production variants distinguishable but with equivalent optimization flags.

### Acceptance

A fresh recursive clone builds. An unchanged second build succeeds. A one-line real backend change changes the source identity and causes a native rebuild. A tampered .so, mismatched manifest, missing required ABI, changed nested dependency, wrong core gitlink or dirty release state fails appropriately. Kotlin-only changes must not silently impersonate a new native build. Archive matching symbols outside the stripped APK.

## 5. M01 — Fix metrics before using them as optimization evidence

### Confirmed paths

`MonitoringRepository.kt` runs the sampler on `Dispatchers.Default`, loops at 250–1000 ms and publishes history/state snapshots. `AndroidSystemMetricsCollector.kt` caches several sources, but polls thermal headroom with the one-second power sample, obtains PSS every three seconds, and parses the first integer of multiple incompatible GPU node formats. [S06–S07]

### GPU parser correction

Make GPU source discovery return a typed source, not merely a File. Store `path`, `format`, `units`, `scope`, `sampling_semantics` and `last_success_monotonic_ns`.

Support separately:

- Explicit percentage nodes: parse a scalar percentage and validate the documented range.
- KGSL busy/total pairs: parse both unsigned values. For a windowed source, compute `100 * busy / total`; do not subtract successive windowed pairs. For a genuinely cumulative source, use validated deltas instead. Determine semantics from the device/kernel source or repeated raw observations, not the filename alone.
- Unsupported textual nodes: return unavailable, not the first numeric token. Do not treat generic `gpuinfo` as a utilization API without a documented parser.

Reject zero denominator, negative/overflow values, impossible busy > total, malformed output, stale values, counter reset and permission errors. Log the selected path and a rate-limited raw sample. Preserve integer precision by parsing counters as Long or an appropriate unsigned type before floating-point division. Frequency units should be declared per source, not inferred solely from numerical magnitude.

Tests: `50 100` gives 50%; `43 128` gives approximately 33.594%, not 43%; `0 0` is unavailable; explicit `50%` is 50%; unsupported text is unavailable; reset/permission cases do not spin or fabricate zero load.

### CPU definitions

Report system utilization on a clearly labeled 0–100% scale, process CPU in logical-core equivalents on a 0–800% scale for this phone, and per-thread CPU separately. Use elapsed monotonic time and kernel clock tick frequency. Do not add guest time twice when interpreting /proc/stat; explicitly document treatment of iowait, steal, irq and softirq. A global `top` header is not a PID's CPU percentage.

Record per-core frequency alongside utilization. An idle little core does not imply interchangeable capacity with a busy prime core. Do not claim CPU waste from a percentage alone.

### Thermal and memory observer cost

Move headroom to its own schedule with at least the documented interval, using cached values between requests. The reviewed Android documentation recommends avoiding requests more frequently than once per ten seconds; the immediate retry with another forecast duration is not a remedy for rate limiting. Use thermal-status callbacks or supported current-status sampling for prompt transitions. [S19]

Default gameplay telemetry should avoid PSS collection every three seconds unless that cost is measured and justified. Propose diagnostic PSS sampling every 15–30 seconds or on demand, while keeping cheap RSS/available-memory sampling at a measured cadence. Profile Debug.getMemoryInfo, proc parsing, Binder calls, sysfs I/O, list construction and log formatting. Do not replace the existing local /proc memory path with a blocking system-service call during crash recovery without reviewing the earlier reason it was removed.

Maintain one sampler per emulation session. Cancellation/restart tests must prove no orphaned loops or duplicate battery receivers. Add source ages; cached samples must not receive a misleading new acquisition timestamp on each UI refresh. Separate display update frequency from source acquisition frequency.

### Frame metrics

Keep raw monotonically timestamped guest-flip IDs, successful Vulkan presentation submissions, and actual presentation timing where supported as distinct streams. A queue-present callback is not automatically a completed display event. Associate all streams with run_id, PID, core identity and scene marker. Use asynchronous bounded storage, not synchronous disk writes per frame.

For a closed interval, principal throughput is the count of qualified unique frames divided by elapsed time. Compute frame-time percentiles from consecutive timestamps within the same interval and report boundary conventions. UI rolling-FPS averages remain secondary. Exclude startup, warning screens and intentional pauses from gameplay measurements and report them separately.

### Acceptance

Unit fixtures cover each format, clock reset and cancellation. Same-scene monitor-off versus minimal-monitor-on runs show negligible observer impact: use a predeclared target such as <=2% relative throughput effect, and disclose inconclusive results when noise exceeds the threshold. A full diagnostic capture is labeled as instrumented and not silently compared against an uninstrumented release.

## 6. C02 — Diagnose the actual 1–2-minute failure

Do not translate 'high CPU and then exit' into a native crash diagnosis. Published exit-info contains user actions, package updates, signal-9 exits and background low-memory entries; it does not identify a new failure for PID 5454. [S04]

### Collection and correlation

Create a unique run directory before launch. Record package version, APK/core/driver hashes, serial, boot ID, process start time, PID/TIDs, monotonic clock origin and wall-clock offset. Keep the last relevant bounded native/app/Vulkan logs, rotated files, game settings and patch receipts. Include memory, thermal and per-thread timeline around failure.

On next launch, fetch ApplicationExitInfo for this package and match by PID/start/run markers, not 'latest record' alone. Record reason, signal/exit status, process importance, timestamp and available trace. API 31+ may expose a native tombstone through getTraceInputStream; permissions, retention and null results must be handled explicitly. SIGKILL is not proof of LMKD, although Android notes some devices report memory kills that way. [S20]

Classify: native signal/abort; Java/CheckJNI exception; memory-pressure kill; ANR/deadlock; Vulkan device loss/GPU reset; deliberate stop or PPU-worker recycling; driver/system-server failure. Report UNKNOWN when available evidence cannot distinguish them. Keep gameplay and `:ppu_compile` process exits separate.

### Backend fault work

Symbolicate native PCs using the exact core and driver symbols. Reproduce the same guest workload without optimization changes. For FIFO desync, find the first broken readback/memory-ordering event, not only the last invalid command. Track ownership and completion of buffers visible to guest CPU/SPU.

For JIT faults, retain generated-code mapping IDs, guest block identity, compiler settings and publication/patch generation. Validate aligned atomic instruction replacement, nearby veneer reachability and lifetime, instruction-cache maintenance, publication order and readers already executing old code. A source-string assertion is not a concurrency stress test.

For timeouts, verify every caller propagates failure without exposing stale host memory, unprotecting guest pages, reusing a still-in-flight resource or looping at full speed indefinitely. The current `VKTextureCache.h::imp_flush` returns false on non-success and retains resources; preserve that improvement while auditing callers and retry/teardown behavior. [S12]

### Acceptance

Reproduce and label the reported failure, or explicitly record 'not reproduced' after the defined test. Retain a minimized reproducer or trace. Run the corrected build through at least three extended GOW sessions plus two other reproducible failing titles. A deliberate debug stop is not a crash. No declaration that every game's crashes are fixed from one 81-second combat interval.

## 7. P03 — Find the frame's actual critical path

Build a release-equivalent **profileable** variant rather than measuring an unoptimized/debuggable configuration. Preserve the production signing workflow; no hardcoded signing secrets. Profileable shell access is supported by Android for local tracing. [S17–S18]

Use Simpleperf for native/managed call stacks and Perfetto for scheduling, wakeups, CPU frequency, Binder work, memory and available GPU events. Record on-CPU and off-CPU time where the platform permits. Capture 15–30-second focused diagnostic windows and longer low-overhead qualification separately.

JIT code requires special handling. Verify that generated-code ranges can be attributed in the actual profiler. If not, add a benchmark-only guest-block-to-host-range map or supported JIT metadata path and correlate sampled addresses offline. Do not label all anonymous executable samples as 'LLVM compilation': they may be executing emulated guest code.

Add low-overhead scoped events/counters at:

| Area | Counters/events |
|---|---|
| SPU dispatch | guest block hash/PC; executions; instruction count estimate; channel/DMA/reservation wait |
| SPU/PPU compiler | miss/hit; queue wait; LLVM pass time; machine-code generation; relocation; cache finalization; publish wait |
| Guest synchronization | sleeping/spinning time; wake cause; wake latency; CAS retries; lost-progress watchdog |
| RSX CPU | FIFO decode; draw preparation; texture work; descriptors; pipeline lookup/compile; readback |
| Vulkan | submit count; CPU submission time; queue idle gaps; GPU timestamp spans; fence/event wait |
| Memory | live JIT bytes; arena capacity/committed bytes; Vulkan allocations; in-flight bytes; RSS/PSS/map count |
| Frontend | monitoring/logging/patch scans; UI snapshot rate; file/Binder work; lifecycle duplicate counts |

Report exclusive and inclusive time to avoid double-counting. Multithreaded CPU work is a resource total, not the duration of a single frame. Reconstruct the dependency path from guest work through RSX submission and presentation. Rank optimization candidates by contribution to the bottleneck, not just by global CPU share.

The report's `DefaultDispatch` TID 5498 at 85.7% CPU is the first explicit attribution target. Identify its full managed/native stack; it may run frontend work, blocking JNI/core work or a diagnostic task. A scheduler thread name does not establish Kotlin as the cause. [S03]

Deliver `profile-summary.md`, symbolized flamegraphs, trace files, source IDs and a top-ten cost table with uncertainty and sample coverage. Profiling permissions or unavailable GPU events are recorded, not silently approximated.

## 8. F04 — Remove avoidable frontend and logging CPU work

### Reviewed paths

`MonitoringRepository.kt`, `AndroidSystemMetricsCollector.kt`, `LogMonitor.kt`, `PatchFastMode.kt`. Audit active call sites in `RPCSXActivity.kt`, the current log broker and lifecycle owners before changing an older compatibility path. [S06–S09]

The reviewed LogMonitor consumer flushes writers and copies four UI buffers at each batch or when its channel empties. Its reader restarts logcat after EOF with no normal-EOF delay; the explicit retry delay is in the exception branch. These are concrete candidate costs/edge cases, not proof that this class caused the hot benchmark thread.

### Implementation

Measure logging volume, active subscribers and coroutine stacks. Keep one session log producer. Preserve fatal/crash collection when overlays are hidden; do not 'optimize' by losing diagnostic evidence. Coalesce UI lists to a fixed measured display cadence, separate file flush cadence from per-entry arrival, and use bounded queues with counters for dropped low-priority messages. Flush critical records and session boundaries appropriately.

Add bounded restart backoff for both exception and normal EOF, with cancellation checks. Filter by relevant process/session where permitted while retaining essential external crash/system diagnostics in a separate path. Rate-limit repeated diagnostics and publish their aggregate count. Avoid repeated patch/YAML scans, filesystem discovery and JNI queries in render/composition loops; cache immutable launch data.

Disable UI-only histogram/history work when nobody observes it. Keep heavy filesystem and blocking native work off the shared Default worker pool when it would monopolize it, but do not pretend moving it to IO reduces its CPU cost. Use named scopes/trace spans and lifecycle ownership, not many unnamed global coroutines.

### Acceptance

Attribute the hot worker before and after; prove reduction in exclusive CPU time and unchanged game speed/correctness. Logs remain complete for terminal failures. Stress repeated activity recreation, overlay toggle, pause/resume, logcat EOF and game stop. No busy-loop restart and no duplicate consumers. Retain only measured wins.

## 9. S05 — Scheduling and synchronization without deleting useful work

### Current implementation

Backend `rpcs3/util/Thread.cpp::calculate_affinity_mask` now supports a real OS mode and intersects custom/performance masks with allowed CPUs. The benchmark selected RPCS3 Scheduler with PPU/SPU/RSX on `0xfc`; six guest SPU workers plus PPU and RSX contend for that six-CPU set. This is a testable contention hypothesis, not proof that performance-core affinity is wrong. [S02, S10]

### Experiments

Compare OS mode, current performance-core mode, and a capacity-aware policy using identical caches, patches, resolution, thermal starting state and guest scene. Observe actual per-TID masks, run-queue delay and migrations. Distinguish prime/performance/efficiency capacity; a simple big-versus-little split may be insufficient, but do not hardcode logical CPU IDs in generic code.

Keep spare host work off the critical guest/render path where measured beneficial. Do not permanently reserve the fastest core for a guessed bottleneck. Try bounded compiler/background-job concurrency separately from guest execution scheduling. Count PPU/RSX deadline misses, not only aggregate CPU.

Audit priority changes and failures. Ensure a low-priority thread holding a lock cannot indefinitely block a high-priority guest/render thread. Record actual effective nice/scheduling state. Do not request realtime/root clock policies as the production solution.

The old timer conversion has been revised; inspect `rx/include/rx/asm.hpp::busy_wait` and counter helpers rather than reinstating the obsolete fixed /182 critique. Calibrated time does not eliminate the energy cost of spinning. Measure host wait sites individually. Where waits dominate, implement bounded adaptive spin followed by a proper wait/notification primitive with a recheck protocol preventing lost wakeups. Keep guest timing and memory-ordering contracts intact.

Avoid a generic sleep inserted into every guest wait; it may ruin wake latency. A wait already dominated by wake overhead may need the opposite policy. Evaluate under both lightly loaded and oversubscribed conditions, across warm/cold compilation and thermal throttling.

### Acceptance

Contention/wakeup tests cover cancellation, stop, save/load, worker failure, wraparound and simultaneous publication. Compare throughput, P95/P99 latency, joules/frame where measurable, and CPU-seconds/frame. Select policy by repeatable end-to-end gain. Lower CPU with slower gameplay is not a pass.

## 10. J06 — Optimize actual ARM SPU and PPU execution

### Architecture difference verified in source

In `SPUThread.cpp`, the LLVM decoder selects `make_fast_llvm_recompiler()` on x86-64 and `make_llvm_recompiler()` on ARM64, including save-state construction. The ARM route lacks the same fast first-tier path. This can affect compile misses; it does not establish that already compiled ARM blocks execute slowly for the same reason. [S11]

### Execution-first work

Select the hottest 10–20 guest blocks/functions from GOW traces, including hashes and representative inputs. Dump the corresponding LLVM IR and generated AArch64 code in a controlled diagnostic build. Inspect instruction count, helper calls, spills/reloads, stack traffic, branch density, load/store packing, endian transforms and register pressure. Compare against current upstream implementations and a same-workload reference, not a desktop marketing benchmark.

Prioritize SIMD lowering for byte shuffles, mask generation, packed shifts, multiply/compare operations, gather/scatter-like scalarization and frequent conversions only when hot. Preserve SPU 128-bit architectural semantics. Examine relevant upstream ARM changes such as idiomatic FSM, SVE2 FMS, ARM masked shifts and comparison/checksum transformations; confirm presence/absence in this fork before porting. [S16]

Separate base NEON lowering from optional SVE/SVE2 paths. Check OS-visible HWCAP/HWCAP2, execution-core compatibility and the code generator's vector-length assumptions. A CPU table entry alone must not enable unsupported state. Keep a portable fallback and compiler target/feature identities in cache keys.

Validate SPU floating-point edge behavior, denormals, NaNs, saturation, sign and integer wrapping with differential tests against an established interpreter/reference contract. Do not apply global fast-math or disable accuracy settings to manufacture benchmark wins. Reduced-accuracy experiments require separately labeled profiles and are not correctness-preserving optimizations.

For PPU, profile the actual mixture of translated instructions, firmware/HLE, syscalls, synchronization and RSX submission. Optimize allocations/string formatting in hot native helpers, redundant checks only with a valid lifetime contract, and expensive boundary crossings only after samples identify them. A high SPU total does not rule out a single serial PPU bottleneck.

### Acceptance

Provide before/after IR or assembly examples, guest-state differential results and microbenchmark distributions. Then demonstrate the same change improves matched gameplay without visual or guest-time regression. Microbenchmark speedup alone does not satisfy the ticket. Keep x86 and baseline ARM compile/test coverage.

## 11. J07 — Reduce runtime JIT latency and repeated compilation

### Reviewed paths

`SPULLVMRecompiler.cpp` performs LLVM passes, verification, `m_jit.add`, finalization, function lookup, publication and trampoline rebuilding. Publication maintenance currently precedes storing the compiled pointer; preserve that ordering and validate reader/executable lifetime. [S13]

### Stage A: instrument and remove duplication

Record compile time separately from execution time. Quantify cold/warm compile misses and whether they coincide with the FPS collapses. Add a per-block single-flight state machine so one owner compiles a given identity while others wait or use a valid fallback. Do not hold a global cache lock over the complete LLVM pipeline unless required; first document lock ordering and ownership.

Investigate reuse of immutable compiler target state and safe per-worker contexts, temporary IR memory release, duplicate analysis and disk I/O. Cache keys include guest bytes, applied guest patches, core codegen version, CPU target/features, relevant emulation options and ABI. Incomplete or corrupt entries cannot be marked complete. Reset/rebuild state must be recoverable after cancellation.

Measure each optimization pass before changing its schedule. A lighter pass pipeline may compile sooner but produce slower code; compare total playthrough cost and steady-state speed. Keep verification and diagnostic checks unless an independently reviewed release policy proves equivalent safety.

### Stage B: persistent cache / prewarming

Verify what the current SPU cache actually stores: guest blocks for reconstruction, host object code, or both. Do not describe a guest-block cache as a ready native-code cache. Prewarm only observed safe blocks and bound compiler concurrency/memory. Initial PPU preparation completion is not evidence that no dynamic SPU or shader work remains.

Host-object persistence is a separate design if absent. Specify relocations, W^X where applicable, target identity, library dependencies, versioning, atomic file commit and stale-entry cleanup. Test a failed cache write, same-size modified guest input, process restart and loading after a codegen change.

### Stage C: optional ARM fast tier

Start only if measured compile misses remain materially responsible for gameplay stalls after A/B. Design a real ARM fast tier, or evaluate an existing correct fallback while optimized compilation happens asynchronously. Specify coverage, dispatch transition, exceptions, invalidation, save-state interaction and executable lifetime. An interpreter fallback can reduce startup stalls but worsen throughput; it must be measured.

Do not rename the full LLVM path, lower one setting or enqueue its existing compilation and claim tiered execution is implemented. First-tier and optimized-code publication require explicit tests with readers that never wait on the notification.

### Acceptance

Warm gameplay shows reduced compile-related stall time and stable memory. Concurrent requests for an identical block do not duplicate heavy work. New executable pages cannot be reclaimed while any thread can execute them. Runtime changes survive stop/retry and save/load stress.

## 12. V08 and R09 — Renderer, readbacks and memory

### V08: reduce required work without breaking synchronization

Start with `rpcs3/Emu/RSX/RSXThread.cpp`, `VK/VKGSRender.cpp`, `VK/VKDraw.cpp`, `VK/VKPipelineCompiler.cpp`, `VK/VKTextureCache.h`, common texture-cache invalidation and `VK/vkutils/sync.cpp`. Confirm current symbols and call paths before editing neighboring files.

Measure CPU time for command decode, draw-state preparation, descriptor allocation/update, pipeline lookup/creation, texture conversion, staging copies and queue submission. Measure GPU render/copy stages and CPU waits separately. A 50% busy number does not distinguish starvation from readback serialization or short expensive bursts.

Implement narrowly attributable candidates: batch compatible descriptor updates and small copies; reuse validated pipeline/descriptor state; reduce redundant dirty-state processing; persist pipeline cache with correct device/driver/cache identity; bound asynchronous compilation; and reduce unnecessary render-pass breaks. Hash semantic state, not pointer addresses or uninitialized padding. Cache lifetimes must include relevant dynamic state and resource generations.

Instrument every readback's reason, byte range, initiating guest operation, GPU completion and CPU-visible completion. Eliminate redundant readbacks or coalesce overlapping ranges only where guest visibility is unchanged. Keep cache-invalidation and device-to-host ordering correct. Do not return success on an unresolved wait merely to avoid a desync report.

The reviewed `imp_flush` allocates a temporary vector, copies data and performs CPU swizzle conversion for certain readbacks. If this path is hot, evaluate reusable thread-owned scratch storage or a correctly ordered GPU conversion. Preserve the read-modify-write behavior for bytes outside the texture and preserve source lifetime. Removing this work without a valid replacement is data corruption. [S12]

Validation layers and command captures are diagnostic tools, not the scored release configuration. If a failed pipeline/draw is intentionally deferred, count and explain it. Persistent missing geometry is not an accepted optimization.

### R09: resource accounting and plateau

Track per-owner native heap, LLVM temporary contexts, executable arenas, page maps, shader/pipeline cache, staging/readback pools, textures and in-flight submissions. Reserve-size VIRT, committed memory, RSS and PSS are different. The evidence's 80G VIRT is not 80GB of physical consumption. Low MemFree alone is not a memory-pressure diagnosis; track MemAvailable, swap, reclaim and exit reason. [S03–S04]

Use pressure-aware bounded queues and compiler concurrency. Do not disable process-recycled PPU batches before proving a replacement keeps memory bounded. Defer nonessential preparation during gameplay where safe. Reuse scratch storage and arena capacity only after previous consumers have finished. Freeing in-flight GPU memory or published code is never a pressure-control strategy.

Run repeated scene transitions, shader-cache growth, 30 start/stop cycles and save/load sequences. After defined warmup, live memory should plateau for a fixed scene, allowing bounded cache growth. A leak verdict requires a reproducible ownership trend, not one 3.3GB RSS observation. Preserve pressure and allocation-failure evidence with a bounded logger.

### Acceptance

No timeout path exposes invalid guest data; no use-after-free under stressed teardown; no unexplained monotonic memory growth; no recurrent crash at the old window. Report readback bytes/frame, wait time/frame, allocation count/frame, submit count/frame and actual GPU stage time before/after.

## 13. G10 — Make GOW Fast Mode effective and honest

### Confirmed current behavior

`PatchFastMode.kt` lists GOW patches (`Disable MLAA`, `Disable Motion Blur`, `Skip intro`) but GOW has no entry in `FAST_MODE_SETTINGS`. The method persists the enabled flag first, catches patch errors, and returns true without proving a matching patch was applied. This is a patch-only intent flag, not a verified GOW runtime optimization profile. [S09]

### Implementation

Return a structured result: requested, effective, partial, unavailable or failed; game/executable/version identity; applicable patch count; applied patch IDs; unsupported/conflicting IDs; and effective engine-setting overrides. Preserve the user's requested preference while showing actual runtime status separately. A missing patch must not silently masquerade as active optimization.

Use exact executable/PPU patch identity and supported game/update version. Names are presentation labels, not sufficient evidence of a machine-code patch match. Log native application receipts and include them in the benchmark run manifest. Keep the patched instruction/code cache key separate from UI state.

Test MLAA removal alone, motion-blur removal alone, then their combination. Do not count skip-intro gains as gameplay gains. Validate AA edges, motion, particles, depth, lighting, cutscenes, menus, boss scenes and input/physics. Turning off an effect is a quality trade-off even if it causes no unintended glitch.

Create an explicit GOW engine profile only from measured settings: OS versus performance scheduler, bounded compiler concurrency, SPU block strategy and renderer options justified by trace. Do not copy GTA's driver wake-up delay or another game's accuracy workaround blindly. Compute effective configuration from clean defaults + compatibility + user overrides + labeled profile; do not leak stale global values between games.

Resolution experiment: compare validated 720p-, 540p- and 360p-equivalent output conditions where supported, recording actual internal dimensions. Flat FPS despite lower GPU time suggests the next bottleneck lies elsewhere; a large FPS response directs more work toward rendering. Account for changed cache state and guest patch effects. Keep quality tiers separate in results.

### Acceptance

Fast Mode ON but zero applicable/applied patches displays 'requested, not applied' or a precise equivalent. Native receipt confirms each active modification. Switching GOW → another title → GOW reproduces the same effective config and valid cache identity. No universal zero-glitch claim is made from a curated-name list.

## 14. D11 and U12 — Driver specialization and upstream updates

### D11: begin with reproducible driver selection, not a rewrite

A Samba-specific Turnip build is a legitimate candidate, but its gate is **driver-attributed cost or a reproducible driver fault**. Review Android app-side Vulkan usage and correct the GPU parser first. A driver can consume CPU as well as GPU time; low GPU busy does not rule out driver CPU overhead.

Record selected versus actually loaded .so path/hash, Mesa revision, downstream patch set, build options/toolchain, deviceID, driverID/info, Vulkan extension/feature set, pipelineCacheUUID and kernel interface. A Mesa development version string is not a complete identity. Standard and Play Store flavor restrictions must remain intact.

A/B the shipped driver, another validated build with compatible device support, and an unmodified reproducible control from the intended Mesa baseline. Warm pipeline caches separately per driver, keep guest/core/config identical, and obtain CPU profiles inside driver symbols plus GPU timings where available.

Use Mesa's current u_trace support for Turnip. `MESA_GPU_TRACES`, `MESA_GPU_TRACEFILE` and `TU_GPU_TRACEPOINT` are relevant diagnostic controls; check that the exact build enables them and that the app can set environment before driver load. Mesa's documented render-stage/counter sources include MSM; do not assume those DRM/MSM facilities or Linux rd-replay tools work unchanged on the phone's KGSL backend. Probe supported producers and use app timestamps or available vendor tools when they do not. [S21–S23]

Candidate driver work after attribution: shader IR3 register pressure/spills, excessive state emission, pipeline creation cost, render-pass GMEM/SYSMEM selection, costly resolves, and narrowly identified device workarounds. Build a baseline with symbols before adding patches. Keep every change individually bisectable and upstreamable where possible.

GMEM/SYSMEM/debug overrides are experiments, not universal speed flags. Preserve synchronization, cache coherency and rendering correctness. Do not turn off robustness/validation of input contracts, force high clocks, disable thermal controls, or pretend a debug driver is a performance build.

A retained driver must pass representative Vulkan correctness tests supported by the environment and the emulator's game corpus. Do not claim formal Vulkan conformance from a few games. Revert on visual faults, GPU resets, memory growth or worse sustained frame times.

### U12: track RPCS3 without replacing the Android port blindly

Add RPCS3 upstream as a read-only reference remote in a separate analysis checkout. Record the fetched upstream SHA at execution time; this review does not certify the fork is fully synchronized. Determine common ancestry where it exists, otherwise map functions/files and document port dependencies. RPCSX integration, Android bridges and directory changes make a blind full-tree merge unsafe.

Create an upstream port ledger: upstream commit/PR, subsystem, present/absent/modified status in the core, prerequisites, conflicts, tests, Android impact, result. Prioritize ARM SPU/PPU lowering, memory management, runtime JIT synchronization and renderer correctness when relevant to measured hotspots. The upstream release history contains concrete ARM work worth auditing, not guaranteed gains for this game. [S16]

Preserve x86 build coverage so differences can be studied on the same x86 hardware with old/new source. A phone-versus-desktop comparison alone cannot isolate source inefficiency from hardware/power/memory/driver differences.

## 15. Q13 — Experiments, acceptance and stopping rules

### Run protocol

Choose repeatable scene checkpoints: Gaia combat, a heavier traversal/particle scene, a boss/large encounter reachable in the legal test copy, plus boot/stop and save-state transitions. Freeze controller input/replay timing where possible. Document unavoidable nondeterminism. Keep game update, firmware, executable/hash, driver, patches, settings, cache condition, internal resolution, display mode, battery/charging state and initial thermal state fixed or explicitly stratified.

Perform at least five paired A/B runs for retained performance claims with five-minute gameplay windows when stability permits; alternate order to reduce thermal/time bias. A crashing baseline is a stability result and cannot supply a complete five-minute performance comparison. Report the valid pre-failure segment separately without advertising it as sustained superiority.

Use a short instrumented profile to diagnose and separate release-equivalent runs to score. Do not clear all caches between every warm performance test. Preserve cold-start and warmed-cache experiments as separate categories. Normal cooling is the primary product claim; any external-cooling run is a separate condition. Never bypass thermal protections.

Store raw frame timestamps and calculate throughput and P50/P95/P99 frame time with confidence intervals across runs. Calculate CPU-seconds/frame as process CPU time delta divided by qualified frame count. Battery power from system estimates is noisy: record charging and instrumentation and label its scope; use external measurement only when actually available. Report thermal-state occupancy, frequency distribution, RSS/PSS and cache growth.

### Required experiment sequence

| ID | Variable | Primary question |
|---|---|---|
| E00 | Corrected current APK, no code experiments | What is the actual present baseline/crash? |
| E01 | Minimal telemetry versus full overlay/diagnostics | Is the observer causing measurable CPU cost? |
| E02 | Logging display/subscriber/flush policy | Is the hot coroutine doing avoidable work? |
| E03 | OS versus current scheduler | Are PPU/RSX/SPU run queues the bottleneck? |
| E04 | Equivalent 720/540/360 render conditions | Does reducing rendering work improve throughput? |
| E05 | Applicable MLAA patch alone | Does guest work actually decrease? |
| E06 | Motion blur alone / validated combined profile | Quality and performance trade-off |
| E07 | Cold versus warm SPU/shader state | Compilation or steady execution? |
| E08 | One SIMD/JIT lowering change | Does measured host work/frame drop? |
| E09 | One readback/batching change | Do CPU waits/submissions drop correctly? |
| E10 | One driver change against reproducible control | Does driver-attributed cost improve? |
| E11 | Combined accepted candidates | Do independent improvements survive integration? |
| E12 | 30-minute gameplay plus save/load and teardown | Is the result sustained and reliable? |

### Final 60 FPS qualification — proposed project acceptance contract

Do not declare completion from warning/menu scenes or peak FPS. Require correct simulation speed and unique game frames over declared gameplay checkpoints. A practical 60 FPS-class gate is >=59 average qualified FPS over each scored long run, >=55 FPS in the low-percentile one-second windows, and P95 qualified interframe time <=20 ms; publish P99 and every >100 ms stall. These tolerances are project choices, not a guarantee or a claim already met.

Complete at least three 30-minute target runs, include heavier scenes, and record thermal behavior throughout. Test normal game saves and the Samba UI save-state path: save, resume, load, stop/restart, driver/core version mismatch handling, direct ISO descriptor restoration, compilation cache interaction, input and visual state after restore. Do not assume save states are compatible across backend revisions; keep old copies and test compatibility explicitly.

Correctness failures veto a performance pass. Unexplained process death, GPU reset, desync, missing geometry, wrong game time, corrupted save or unbounded live memory is a fail even at 60 FPS. Retain the best stable configuration rather than shipping a faster corrupt one.

### Orchestrator loop and status reporting

For each experiment: baseline identity → hypothesis → smallest code/config change → unit/native checks → device A/B → inspect raw evidence → independent review → retain/revert → update bottleneck ranking. Publish actual commands and exit codes. Tests not run are NOT_RUN; inaccessible device/profile permissions are BLOCKED with exact error. Never infer success from a test file's existence or a handoff claim.

After three well-controlled no-gain experiments on one hypothesis, revisit the profile rather than stacking more settings. Do not stop working merely because 60 FPS is ambitious, but do not claim an endless loop will guarantee it. At each handoff provide achieved FPS, correctness state, CPU/energy/frame, thermal behavior, source hashes and the largest remaining measurable dependency.

## 16. Expected deliverables

Maintain `docs/performance/current-baseline.md`, `docs/performance/experiment-ledger.md`, `docs/performance/upstream-port-ledger.md`, source/config/patch/driver receipts and a compact evidence index. Proposed tools: `scripts/perf/run-gow3-benchmark.py`, `analyze-frame-events.py`, `collect-run-evidence.sh`, and native diagnostic counters. These are requested new outputs, not scripts claimed to exist already.

The benchmark runner must reject mismatched run IDs, package/core hashes, scene windows and missing raw event data; preserve partial/crash runs without relabeling them as successes. The analyzer must unit-test event deduplication, boundary handling, dropped samples, zero time, pauses, clock changes, and separation of rolling display metrics from full-interval throughput.

A final change report lists source changes, native tests actually executed, device conditions, measured effect and regressions. Finish with a clean source checkout and reproducible artifact verification, not a local .so copied from a different tree.
