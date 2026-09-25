# WORKER — God of War III Gaia Crash Recovery and Verified Optimization

**Revision:** 2026-09-25, independent follow-up to the Phase 1–10 handoff  
**Primary target:** OnePlus 13R, Snapdragon 8 Gen 3, device `d30a1726`  
**Worker:** the user's existing Gemini 3.8 Flash worker; the name is a user-supplied execution label, not a capability guarantee.  
**Priority:** correct progression through the first fight → Gaia shoulder/fire transition → tree obstacle, then sustained performance.  
**Long-term performance target:** 60 genuinely new game frames/second at correct game speed. Not yet achieved or certified.

## 0. Read this before changing code

The user reports a reproducible crash after the first fight, before Gaia extinguishes the shoulder fire and Kratos lifts the tree. The earlier handoff's “zero crashes” and “Phases 1–10 completed” do not qualify that route. Do not dismiss the user's report because the benchmark runner returned zero.

This is an UPDATED plan, not an instruction to discard all previous improvements. Retain verified telemetry rate limiting, buffered logging, safe synchronization, and useful ARM lowering changes. Reopen the specific correctness and qualification gaps listed here.

Do not start with a custom Turnip performance build. First establish a trustworthy outcome recorder and reproduce the exact failure with the currently installed driver. An early controlled driver A/B is allowed if a matched GPU fault makes it diagnostically useful; that is different from starting a speculative driver optimization project.

**No root cause for the new Gaia crash has been proven by this review.** Confirmed source defects below explain why earlier qualification is unreliable. They are investigation priorities, not permission to assert that one of them caused this particular crash.

Read `REVIEW_ADDENDUM.md`, `PHASE_EVIDENCE_AUDIT.md`, and `SOURCES.md` alongside this file. Source IDs in square brackets refer to that registry. All new script names below are proposed deliverables unless explicitly identified as existing.

### Reviewed source identity

| Item | Actual identity retrieved from GitHub |
|---|---|
| Frontend repository | `JICA98/samba-s3` |
| Frontend `master` | `1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd` |
| Backend repository | `abhay-byte/samba-s3-core` |
| Backend `samba-android` | `141af96fa006f56c25ec335137e92c78b29b17e3` |
| Frontend submodule path | `app/src/main/cpp/rpcsx` |
| Reviewed frontend gitlink | `141af96fa006f56c25ec335137e92c78b29b17e3` |

Some expanded commit hashes in the pasted handoff do not match GitHub's actual commits. Resolve short IDs against the correct repository; never construct full hashes or use the upstream RPCSX URL for fork-only commits. Remote branch agreement does not prove the worker's local checkout is clean or that the user's installed APK matches it. [S01–S03]

## 1. Worker execution contract

### 1.1 Separate implementation from acceptance

Every ticket has separate fields:

- `implementation`: NOT_STARTED / IN_PROGRESS / IMPLEMENTED.
- `verification`: NOT_RUN / FAILED / INCONCLUSIVE / VERIFIED_FOR_DECLARED_SCOPE.
- `scene_qualification`: NOT_REACHED / FAILED / PASSED.
- `review`: NOT_REVIEWED / REVISE / ACCEPTED.

A compilation, unit-test pass, screenshot, or successful cleanup cannot set all four fields to complete. A test fixture passing is not “100% accurate classification” for arbitrary real crashes. A thread showing 0.0% in one `top` sample is not proof of zero overhead.

Operate one hypothesis and one independently reviewable change at a time. Use a dedicated branch/worktree; do not combine a compiler change, driver change, profile change, and harness rewrite into one experiment.

A single worker may implement and test, but must not claim independent subagents or independent review unless those agents actually ran. Record the actual executor and evidence. A fresh review context can inspect the diff without inheriting the implementation narrative; identify that accurately.

### 1.2 Forbidden shortcuts

Do not:

- Force progress counters to their totals, accept “one item remaining” as completion, or infer completion from empty UI text.
- Turn a crash or hang into a passing result because `DEBUG_STOP_GAME` subsequently succeeds.
- Count a warning screen, menu, cutscene, or duplicated presentation as qualified 60 FPS combat.
- Exclude a long stall because it exceeds an arbitrary pause threshold.
- Reuse an old screenshot as a new run's evidence.
- Treat enabled patch configuration as proof that the correct executable was patched.
- Silence FIFO errors, skip guest jobs, remove synchronization, unprotect failed readbacks, or catch fatal execution errors merely to continue drawing.
- Disable Android thermal protection, overclock, alter governors globally, or force unsafe CPU features.
- Remove all caches or uninstall the app without preserving the user's data. Keep game saves, save states, configuration, firmware, and imported drivers.
- Stop the investigation with “hardware limitation” without a measured remaining bottleneck. Conversely, do not promise 60 FPS from unmeasured changes.

### 1.3 Durable work record

Maintain `docs/performance/gaia-recovery-status.md` and an append-only experiment ledger. After each completed experiment, record:

`ticket, hypothesis, source revisions, binary hashes, driver identity, config/patch/cache identities, launch identity, milestone reached, first failure, cleanup result, test commands, evidence paths, verdict, next smallest experiment`.

When a ticket is blocked, state the actual unavailable capability or artifact. Do not replace a missing test with a fabricated pass. Continue independent source/unit work where useful.

## 2. Findings that this plan must address

| ID | Confirmed observation | Required treatment |
|---|---|---|
| V01 | Runner detects process death, breaks out of the loop, then eventually returns `0`. | Preserve an immutable failure outcome and return nonzero. |
| V02 | Runner's later successful stop writes `clean_stop=true` and `DEBUG_STOP_GAME`; classifier considers deliberate stop before native/Java crash. | Separate failure from cleanup; cleanup never overwrites an earlier fault. |
| V03 | Fixed sleeps, button presses, and process existence are treated as gameplay progression. | Confirm actual scene milestones and exact process/session identity. |
| V04 | Phase 10 has 46 sampled interval values but reports 718 counter-derived frames and full-looking stall/percentile statistics. | Label old tails as sampled; capture complete frame events for new qualification. |
| V05 | Analyzer's non-telemetry path discards gaps ≥1 second as pauses. | Retain stalls unless explicit pause events prove user suspension. |
| V06 | Phase 7 screenshot has the same Git blob as the older revised combat screenshot. | Mark as reused; obtain fresh phase/run-bound images. |
| C01 | Progress server can force `pdone=ptotal`, accept `ptotal-1`, and complete after repeated empty text. | Replace UI-derived completion with producer-owned terminal state. |
| C02 | Single-flight compilation waits without an explicit local cancel/generation check; failure, publication, and executable ownership need a full contract review. | Add adversarial native tests and repair only demonstrated state/lifetime defects. |
| C03 | SPU cache suffix reduces features to `sve2/sve/neon/base`, using substring matching. | Canonical signed effective-feature identity; audit outer cache keys too. |
| F01 | Fast Mode receipt derives “applied” from enabled names/configuration and fills effective settings from the profile map. | Distinguish configured state from native runtime application. |
| R01 | Phase 8 descriptor/pipeline elision was later reverted. | Do not credit reverted changes in the current build's performance claim. |
| E01 | Phase 10 report itself records Thermal Status 3; the handoff says unthrottled. | Regenerate thermal conclusions from matched raw evidence. |

These are source/evidence observations, not newly executed phone tests. [S04–S15]

## 3. Ticket CR00 — Preserve the current failure and establish provenance

**Goal:** preserve a reproducible baseline without changing the game's execution first.

### Actions

1. Read repository `AGENTS.md` and the existing skills:
   - `.agents/skills/sambas3-device-test/SKILL.md`
   - `.agents/skills/sambas3-game-launch/SKILL.md`
   - `.agents/skills/sambas3-controller/SKILL.md`
   - `.agents/skills/sambas3-logs/SKILL.md`
   - `.agents/skills/agent-device/SKILL.md`, where applicable.
2. Resolve stale instructions against actual build/loader code. For this task the target is OnePlus 13R; do not silently broaden acceptance to another device. Do not follow an uninstall example that destroys user data.
3. Record, without modifying the working tree:

```bash
git status --short
git rev-parse HEAD
git remote -v
git ls-tree HEAD app/src/main/cpp/rpcsx
git submodule status --recursive
git -C app/src/main/cpp/rpcsx status --short
git -C app/src/main/cpp/rpcsx rev-parse HEAD
git -C app/src/main/cpp/rpcsx remote -v
adb -s d30a1726 get-state
adb -s d30a1726 shell getprop ro.build.fingerprint
adb -s d30a1726 shell dumpsys package com.zenithblue.sambas3
```

4. Preserve the currently installed APK/core identity before installing a new candidate. Record actual runtime-loaded library path/build ID, not only a packaged file that the loader might bypass.
5. Archive effective per-title and global configuration, Fast Mode preferences, all enabled patches including separately imported `Disable SPU MLAA`, game title/update identity, and cache namespaces. Do not redistribute game binaries.
6. Record actual driver library SHA-256, driver package name/version, Mesa commit if known, GPU/device/driver IDs, pipeline cache UUID, enabled extensions, and Android kernel build. An unknown field stays unknown.
7. Preserve the current save-state and game-save inventory, with hashes and compatibility metadata. Never use a new save to overwrite the only original.
8. Read the existing build and provenance scripts before invoking them; preserve the current release-equivalent configuration. Performance measurements must not silently switch to a debuggable build.

### Current historical reference only

Phase 10's report/manifest lists APK SHA-256 `5a35d5dcd69404eeb945817c1094bc6196c0310532954b5bd68017e6ba3054d9` and core library SHA-256 `e93f1faa806914f24f5c22e346dd4174e66b63c07ed0242a9d18c3dcd7fcc855`. These identify that archived run, not necessarily today's installation. [S06, S07]

**Acceptance:** a source/binary/config/driver inventory exists; missing evidence is explicit; original user data and current failure conditions remain recoverable.

## 4. Ticket CR01 — Fix the test system's false-pass behavior first

**Existing files:** `scripts/perf/run-gow3-benchmark.py`, `scripts/perf/classify-crash.py`, `scripts/perf/collect-run-evidence.sh`; inspect the Kotlin `ExitClassification.kt` and `ProcessExitMatcher.kt` implementations discovered in the repository for equivalent behavior. [S04, S05]

### 4.1 Runner state and outcome

Introduce separate run state, gameplay result, and cleanup result. Suggested run states:

`PREFLIGHT → LAUNCHING → NAVIGATING → QUALIFYING → COLLECTING → CLEANUP → FINISHED`.

Suggested outcomes:

`PASS`, `FAIL_CRASH`, `FAIL_HANG`, `FAIL_GUEST_PROGRESS`, `FAIL_PROVENANCE`, `FAIL_REGRESSION`, `INCONCLUSIVE_EVIDENCE`, `INCONCLUSIVE_SCENE`, `ABORTED_SAFETY`.

Maintain a first-failure latch plus a list of later events. A later deliberate stop can describe cleanup but cannot clear the first failure. Process death, PID replacement, emulator-core stop while the app survives, lost progress, or corrupt rendering each need their own evidence.

Use monotonic time for deadlines. Identify a process by package/process name, PID, start-time identity and device boot identity; also identify the emulator launch/session and surface generation. A nonempty `pidof` result from another invocation must not pass.

Suggested control-flow contract, not an existing implementation:

```text
launch_identity = capture_identity_at_launch()
outcome = NOT_FINISHED
first_failure = null
try:
    verify_binary_and_runtime_identity_or_fail()
    navigate_and_verify_required_scene_milestones()
    record_qualification_interval()
    verify_postconditions()
except classified_failure as failure:
    first_failure = first_failure or failure
finally:
    preserve_evidence_before_cleanup()
    cleanup_result = attempt_cleanup_without_changing_first_failure()
    preserve_post_cleanup_evidence()
    verdict = derive_verdict(first_failure, milestones, evidence, postconditions)
    write_manifest_atomically(verdict, cleanup_result)
return 0 only when verdict == PASS
```

Define nonzero exit codes for failures and inconclusive runs. CI/automation must treat both as unqualified. Do not use a successful JSON write or analyzer invocation as gameplay success.

### 4.2 Strict evidence and provenance

Reject absent expected APK/core hashes, not only mismatches. Parse structured identity and compare exact fields rather than searching a string. Replace source-edited “expected latest hash” constants with a build manifest supplied to the runner.

Preserve pre-existing crash buffers before clearing them. Stream logcat to the host from before launch instead of relying exclusively on a final ring-buffer dump. Collect a second diagnostic snapshot after termination; retain both. All shell commands need checked return codes, bounded timeouts, and explicit unavailable results.

Remove the hardcoded `/home/abhaybyte/.gemini/...` screenshot destination. Write the run-local original first; publish a phase alias only after verification. Never overwrite a previous experiment's evidence directory.

### 4.3 Classification precedence and identity

Do not run unscoped regular expressions over arbitrary historical text and call the first matching category the root cause.

First correlate events to the run. Then record the sequence: e.g. GPU fault → native abort → recovery stop. Keep primary observed failure, contributing signals, and cleanup separately. A matched native crash cannot become DELIBERATE_STOP because the manifest contains `DEBUG_STOP_GAME`.

Remove generic substring `clean` as proof of a deliberate exit. `EXIT_SELF` needs its status and context. A successful emulator stop while Android keeps the process alive is not an Android process exit with a manufactured status 0.

Require exact package/process-name matching; eliminate the “any process name without ':'” fallback. Enforce both start and end time bounds for known PIDs. Preserve timezone/clock-domain information. A missing native tombstone is missing evidence, not proof of no crash.

Android native tombstone streams returned through ApplicationExitInfo can be protobuf rather than text; preserve the raw bytes, use the appropriate schema, and tolerate absent/overwritten traces. [X01, X02]

### Required regression tests

Create real executable tests, not source-text assertions, covering:

| Fixture | Required result |
|---|---|
| SIGSEGV during qualification, successful stop afterward | FAIL_CRASH; cleanup may be successful |
| Java fatal followed by activity finish | Failure retained |
| Process dies and a new PID appears | Original run fails |
| Same PID reused outside run window | Historical exit rejected |
| Unrelated package crash / PPU compiler recycling | Does not classify main game as crashed |
| App survives but guest stops progressing | FAIL_HANG or FAIL_GUEST_PROGRESS |
| No tombstone and unexplained disappearance | Unqualified/unknown cause, not PASS |
| Nonzero EXIT_SELF | Not automatically clean |
| GPU fault then abort then cleanup | All correlated events preserved |
| Missing expected hash | FAIL_PROVENANCE |
| Screenshot/trace collection failure | Evidence incomplete, not “100% verified” |
| Clean stop, complete milestones, no failure | PASS for the declared scope only |

**Acceptance:** the synthetic crash-plus-cleanup case demonstrably returns a nonzero runner status and cannot produce a clean gameplay verdict. Capture the command, output, and manifest.

## 5. Ticket CR02 — Replace inferred combat with actual checkpoint progression

### 5.1 Route contract

Use the user's description as the initial route definition; verify the exact in-game ordering from the live game rather than guessing an internal level ID.

| Marker | Required evidence |
|---|---|
| G0_BOOT | Correct game/update, original launch identity, first valid game image |
| G1_FIGHT_START | Kratos controllable in the first fight; enemies and input response visible |
| G2_FIGHT_COMPLETE | Fight genuinely completed, not repeated idle attacks |
| G3_TRANSITION_ENTER | Transition toward the shoulder/fire/tree path begins |
| G4_FIRE_SEQUENCE | The described Gaia/fire event is reached and completes |
| G5_TREE_INTERACTION | Tree obstacle and actual lifting interaction reached |
| G6_TREE_PASSED | Kratos passes the obstacle and remains controllable |
| G7_BEYOND_TRANSITION | Next identifiable progression point plus continued active play |

Markers need observed images/video and timestamp association. Some may be combined or reordered after live verification; document that change. Do not emit a marker merely because a fixed sleep elapsed.

Use existing controller scripts. The inspected controller skill exposes `debug-pad.sh`, `gamepad.sh`, button presses, holds, sequences, and analog stick movement. Confirm delivery through `DebugPad` acknowledgement, not just broadcast return status. Discover commands with `--help`; do not invent a save-state or checkpoint CLI. [S18]

A loop that taps SQUARE every eight seconds does not establish that enemies died or that Kratos walked to the tree. Navigate using observed input response, holds, and analog movement. Release held buttons/sticks in cleanup.

### 5.2 Reproduction protocol

1. Run the preserved current configuration through the full route. Capture the last successful marker and first failure, without automatic “clean pass” labeling.
2. Aim for three independent repetitions, unless device safety or a fully captured deterministic fault makes more runs redundant. Record every attempt, including failures and inability to reach the scene.
3. Start from normal boot or an in-game save when available. A same-build pre-failure save state may accelerate diagnosis only after at least one normal traversal proves the same failure.
4. Do not compare backend versions using an unsupported save-state format or mismatched JIT cache. Reproduce from a compatible normal game save/cold boot to confirm a regression.
5. Use a bounded navigation watchdog. A time limit without the required marker is INCONCLUSIVE_SCENE or failure to progress, never “gameplay passed.”
6. At a suspected hang, check guest progress as well as presentation. A spinner or unchanged image can continue presenting while emulation is stuck.
7. Save a short continuous clip around G2–G6 where the platform allows it; also capture direct framebuffer stills at milestones. Record capture overhead and keep performance scoring runs separate when video capture materially changes load.

### 5.3 Failure packet

Capture session/boot/PID/start identity; wall and device-monotonic timestamps; title/update/executable identity; effective configuration and patch receipt; driver identity; last marker; last known guest SPU/PPU PC or job identity when available; FIFO state; resource generation; compiler activity; native/Java logs; process exit record; tombstone raw/decoded/symbolized form; memory and thermal samples.

If the process disappears, host-streamed logs must survive. If it remains alive, capture stacks and wait states before stopping. A low-cost bounded native diagnostic ring is preferable to synchronous per-instruction logging.

Do not log arbitrary guest memory or distribute game executables as diagnostics. Preserve only the minimum reproducer needed, privately where appropriate.

**Acceptance:** one evidence packet demonstrably belongs to the user's post-fight transition, not merely a boot/menu/early combat run. A failure signature is either identified or explicitly UNKNOWN with the next missing observation stated.

## 6. Ticket CR03 — Correct frame and thermal qualification

**Existing files:** `scripts/perf/analyze-frame-events.py`, `app/src/main/cpp/native-lib.cpp`, and monitoring sources. [S08, S09]

### 6.1 Distinct event types

Separate:

- Guest update/flip progress, when the core exposes a reliable event.
- Renderer submission.
- One chosen authoritative surface presentation/queue event.
- Periodic cumulative-counter snapshots.
- Latest sampled interval and rolling FPS.
- Explicit pause/resume and scene-boundary events.

Do not mix them into one generic FrameEvent list and derive full-frame tails. Add schema/source/clock-domain tags. Prefer one primary presentation source; validate any dual-source deduplication with frame identity rather than assuming callbacks less than 800 microseconds apart are duplicates.

Implement a bounded per-frame event buffer with sequence numbers and overflow/loss counts. Include session ID, process identity, surface generation, frame sequence, and device monotonic timestamp. Avoid expensive formatting/file I/O in the frame callback. Flush through a low-overhead consumer, and measure instrumentation overhead.

### 6.2 Scoring rules

For explicit window `[t0,t1)`, report qualified new frame count divided by the entire window duration. For first/last frame endpoint calculations, consistently use the count of intervals between those endpoints; do not count N events over an N−1 interval span without documenting the convention.

Retain time spent stalled, compiling, or waiting inside a gameplay window. Subtract only explicitly recorded intentional pauses for a separately labeled active-time metric; also report wall-time throughput. A crash/hang window extends to the recorded failure/timeout, not only to its last good frame.

Full-frame percentiles require a complete interval stream, or declared loss with an unqualified tail estimate. Counter snapshots can support endpoint throughput but cannot reconstruct every frame's latency. With only sampled intervals, output `sampled_interval_p95`, sample count, and `full_frame_p95 = unavailable`. “17 sampled intervals over 100ms” is not “17 total stalls.”

Do not sort multiple sessions or clock resets into one artificial timeline. Handle date rollover, monotonic reset, surface recreation and frame-counter reset explicitly.

Pass exact marker boundaries to the analyzer. The existing runner currently analyzes an entire collected log without those boundaries. [S04]

### 6.3 Required analyzer fixtures

Test a known 60 FPS stream; a two-second stall with no explicit pause; a real pause/resume; process/frame-counter reset; duplicate callbacks; valid closely spaced frames; missing events; midnight crossing; a fatal event after the final frame; and periodic counter samples whose unseen intervals contain stalls.

The two-second stall must lower throughput and remain in tail reporting. Forty-six crosschecks must never become 718 independently measured intervals.

### 6.4 Thermals and CPU

Keep the existing reduced polling rates unless new profiling disproves them. Record CPU time per new frame, compiler CPU, PPU/SPU/RSX work, auxiliary thread work, GPU timings and frequencies. Zero in a coarse sample means below that sample's resolution.

Use actual thermal-status transitions and frequency traces; do not infer “unthrottled” from a 35°C battery. Preserve charging state, cooling, ambient conditions when known, and initial thermal state. Compare like conditions and report mixed conditions as such.

**Acceptance:** raw events, explicit windows, output statistics, and regenerated report reconcile. The historical 12.73 FPS remains a counter-based observation from its old window, not a certified new gameplay baseline.


## 7. Ticket CR04 — Isolate the progression failure with a small, controlled matrix

**Goal:** find the smallest change that changes the failure, without attributing a bundled improvement to the wrong feature.

Keep the current driver, game/update, resolution, renderer synchronization settings, performance-core scheduling policy, input route, and starting conditions constant. Preserve `0xFC` as the current device's observed control, not a universal hardcoded mask for every phone.

### 7.1 Initial four conditions

| Condition | SPU block mode | Optional GOW performance patches | Purpose |
|---|---|---|---|
| A | Current Mega | Current complete enabled set | Reproduce user's current failure |
| B | Normal | Same set as A | Isolate Mega/block-size interaction |
| C | Normal | All optional GOW performance patches disabled | Conservative diagnostic control |
| D | Mega | Same disabled set as C | Separate block mode from patch effects |

“Disabled” includes individually imported or previously enabled SPU MLAA patches, not just the three names in Fast Mode's curated map. Preserve required compatibility settings. Never disable a patch dependency halfway without understanding its contract.

For each condition, capture native effective settings after all launch overrides. Simply editing YAML to Normal while Fast Mode reapplies Mega does not create condition B. Add a narrowly scoped diagnostic launch configuration if needed, with explicit precedence and a runtime receipt. Do not mutate global configuration during active emulation.

Use compatible cache namespaces. Preserve cold and warm conditions separately. A new block mode/lowering/patch identity must not reuse incompatible generated code. Copy or rename experiment-specific caches, not the user's entire cache directory.

The “Max LLVM Compile Threads = 2” setting stays constant initially. Trace its actual consumers before saying it caps runtime SPU compilation; a limit on startup/PPU workers does not automatically limit synchronous compilations on six distinct SPU threads.

### 7.2 Interpret outcomes carefully

- A fails and B passes: Mega/block shape is implicated, not yet the root cause. Check LLVM block complexity, register pressure, code generation, control flow and memory lifetime.
- B fails and C passes: isolate patch families. Test MLAA dependency group, motion blur, and their combination. Keep intro behavior fixed for scored gameplay.
- A–D all fail at the same point: focus on common native/renderer/streaming paths and the first matched failure signature.
- Only warm or restored-state runs fail: prioritize cache identity, stale code/resource generations, or save-state restoration.
- Failure timing changes but route remains broken: not a fix.
- A condition cannot reach G2 because input/navigation differs: inconclusive comparison; repair route matching.

After one pass, rerun the most informative failing/passing pair. If a conservative configuration crosses G6, retain it as a temporary diagnostic baseline, not an automatic permanent quality/performance decision. The final production profile must be separately requalified.

### 7.3 Regression bisection

Only label an old revision “good” after it crosses the same G2–G7 route. Booting an old build is insufficient. If the old source cannot build with current dependencies, record a build-skipped revision rather than a gameplay failure.

Use independent worktrees and preserve matching frontend/core revisions. Bisect or selectively disable these groups separately when evidence implicates them:

1. GOW Mega/profile and optional patches.
2. J07 single-flight and cache identity.
3. J06 integer/SIMD lowering.
4. V08 scratch reuse.
5. V08 semantic pipeline-key changes.

Keep the later descriptor-binding and unstable SHUFB-path reversions unless intentionally running a separately labeled regression reproducer. Do not reintroduce a known black screen just to reduce CPU usage.

**Acceptance:** a compact matrix with observed milestones, failure signatures and exact runtime settings; the next code investigation is justified by evidence rather than a list of guesses.

## 8. Ticket CR05 — Repair compilation completion and single-flight correctness

This is a correctness ticket. Do not mark it complete because the dialog disappears.

### 8.1 Producer-owned progress completion

**Files:** backend `rpcs3/Emu/system_progress.cpp`, `rpcs3/Emu/Cell/SPUCommonRecompiler.cpp`; inspect associated progress structures, JNI events and Kotlin readiness consumers. [S11]

Remove acceptance based on `pdone >= ptotal - 1`, empty text, or a timer that forces totals. Also remove the UI/progress consumer's authority to fabricate producer completion.

Implement an explicit compile-job/session record, using existing compatible abstractions where possible:

`job_id, generation, queued, running, completed, failed, canceled, producer_closed, workers_joined, terminal_reason`.

Each accepted work ticket reaches one terminal outcome exactly once. A complete job requires producer closure, no running/queued work, worker lifetime completion, and no unhandled failure. A counter reaching a number is not the only proof. Duplicate cache hits count as completed work through an explicit ticket path, not through arbitrary total adjustment.

Keep display percentage separate from readiness. Empty descriptive text is a presentation state, not completion. Canceled or failed preparation must not publish a ready cache manifest or a successful Kotlin readiness state.

Progress aggregates shared by PPU and SPU must not let one session consume another's counters. Dynamic discovery can increase totals, but the final job must explain its work inventory. A no-work cache hit can explicitly complete with a zero-work reason.

Test the originally reported 6047/6048 case by delaying the last worker, not by deleting its accounting. Trace enqueue/dequeue/finish to find the original missed contribution or join/progress ordering issue.

### Required native/integration tests

Delayed final task; duplicate function; failed final task; cancellation while one worker waits; empty text while work is active; zero-work cache hit; a new progress generation while the old one terminates; worker exception; totals growing during discovery; and simultaneous PPU/SPU progress.

Assert that no READY/COMPLETED event is emitted until producer truth allows it. Test Kotlin's handling of cancellation and retry through the real bridge, not only a mock percentage.

### 8.2 Single-flight contract and lifecycle

**Files:** backend `SPURecompiler.h`, `SPULLVMRecompiler.cpp`, `SPUASMJITRecompiler.cpp`, `SPUCommonRecompiler.cpp`, and the actual atomic/wait primitive implementation. [S12, S16]

First trace all entry points: startup cache build, runtime miss, interpreter fallback, x86 fast-tier promotion, stop, and save-state restore. Determine whether the wait primitive itself supports abort; do not infer that solely from the local loop.

Write an ownership/state diagram. Distinguish a block's code identity and executable generation from “someone is compiling.” Define the result of failure, cancellation, cache invalidation and retry. A waiter must not block forever on a dead producer or observe a freed `spu_item`.

Required properties:

- At most one owner per block/generation/tier; distinct blocks can compile concurrently within the measured resource budget.
- No global compilation lock held while waiting for another block.
- Waiters can exit on stop/generation invalidation through a defined mechanism.
- All owner exit paths notify consistently; a sticky FAILED state has an explicit retry/fallback policy.
- A published function pointer has completed relocation, executable-memory/cache finalization and required dispatch registration.
- Readers of `compiled` and readers of `compile_state` agree on what “callable” means.
- A failed trampoline rebuild cannot leave an ambiguously successful state.
- Compiled code and metadata survive until all executing readers are quiescent.
- Save/load and new game sessions do not inherit an old owner's in-flight ticket.
- Existing x86 fast-tier code must still be eligible for optimized-tier promotion; an early “pointer exists” return must not accidentally suppress that path.

Do not assume an additional barrier alone repairs a lifetime/state bug. Conversely, do not remove instruction-cache synchronization for performance.

The new `clearAllGlobalMappings()` and pointer clearing require an ownership audit. Nulling `m_module` is not proof that LLVM allocations were released, and deleting an owning engine/module to reduce PSS can invalidate generated code or relocations. Establish who owns every object and executable allocation before cleanup changes.

### Native stress fixtures

Use a test harness exercising the actual C++ state/wait/publication code:

- Six or more concurrent requests for one block: one successful owner, identical valid results.
- Concurrent requests for different blocks: no accidental global serialization.
- Inject an exception at each compile stage and ensure waiters unblock correctly.
- Stop during waiting and during finalization; restart into a new generation.
- Force a trampoline/publication failure and verify state is not falsely ready.
- Repeated compile/execute/reclaim with reader overlap under appropriate sanitizer or stress instrumentation.
- x86 fast-tier promotion test where that build is available; otherwise explicitly untested, not silently certified.

Host tests do not substitute for ARM64 instruction-cache/publication testing on the target.

### 8.3 Cache identity and actual target features

Current cache suffixes select a broad `sve2/sve/neon/base` tag by substring search. `-sve2` still contains `sve2`, and different feature sets can collapse to the same tag. Audit any outer cache key before concluding that an actual incompatible object was reused. [S12]

Create a canonical key covering the dimensions that affect generated code:

`guest code digest + entry/region identity + applicable patch digest + decoder/tier + block mode + lowering/codegen ABI version + LLVM library identity + target triple/data layout + effective CPU/ISA feature map + relevant semantic settings`.

Parse feature enable/disable semantics, normalize order, and use actual effective target attributes rather than merely requested flags. Equivalent settings may canonicalize together; materially different effective code generation must not.

The latest rollback removed an entire `setMAttrs` path as well as explicit SVE and SHUFB lowering. Log the actual EngineBuilder/TargetMachine and function attributes. Absence of a `+sve` string is not by itself proof of a NEON-only generated program when CPU defaults imply features. Keep the known-compatible path until tested. [S13]

Version the generated-object namespace when lowering semantics change. Do not unnecessarily invalidate raw guest-code inventories that remain valid. Reject old/incompatible executable objects without deleting user game saves.

**Acceptance:** producer-terminal tests, native single-flight stress tests, and cache-key tests pass with actual commands recorded; ARM64 target execution and stop/retry are tested. No 99%-completion shortcut remains.

## 9. Ticket CR06 — Fix the actual native failure, selected by its signature

Do not implement every branch below speculatively. Follow the first correlated fault and use reversible isolation controls.

### 9.1 LLVM abort / register-scavenging / new SPU compilation

Capture the full LLVM error, guest block digest, entry point, block mode, target CPU/features, module/function attributes, calling convention, and compiler library build identity. Record NDK Clang and the embedded LLVM library separately; they are not necessarily the same compiler version.

Preserve a minimal IR/MIR reproducer when practical and legally distributable; otherwise retain the minimum private failing block evidence. Reproduce compilation independently from live game execution before claiming a compiler fix.

Audit fixed-register/calling-convention constraints, reserved registers, vector register pressure and consecutive-register requirements. Mega can change block shape and pressure; that is a hypothesis to test, not proof.

Do not restore the reverted `aarch64_neon_tbl2` path or broaden SVE features as an untested performance fix. Do not switch calling conventions globally or apply fast-math to silence a failure. A narrowly scoped fallback must preserve guest semantics and report its use.

Verify that the selected runtime compiler budget actually limits the relevant work without blocking the guest thread needed to release a resource. Measure outstanding modules, code size, compile latency and memory high-water marks.

### 9.2 SIGSEGV/SIGBUS/SIGILL in generated SPU/PPU execution

Map the host PC to the exact generated object and guest instruction region. Log register/guest-PC context safely with bounded diagnostics. A host JIT code address needs a JIT symbol map; a stripped ELF alone may not explain it.

Check code lifetime and publication first when the PC lies in stale/reclaimed code. For instruction semantics, differential-test the changed lowering against the interpreter/reference behavior.

For FSM/FSMBI/mask and shift/rotate work, test exhaustive relevant masks/immediates, lane/byte order, out-of-range shifts, sign extension, wrap behavior and constant/nonconstant paths. For SHUFB, include all control-byte classes and special fill values. Integer microbenchmarks do not establish floating-point correctness of unrelated game code.

Run the exact transition with the changed lowerings individually disabled to identify necessity. Do not compare visual plausibility alone; wrong vector lanes can corrupt data that fails several seconds later.

For SIGILL, verify process-allowed CPU capabilities and actual execution affinity for every relevant thread, including restored/new threads. Do not simply target the fastest named CPU globally.

### 9.3 FIFO desync / GPU device loss / readback or resource corruption

Preserve the first FIFO error and preceding guest writes. Do not skip the failing command and call the run stable.

Audit `VKTextureCache.h::imp_flush`, `VKTextureCache.cpp` DMA region building, relevant `texture_cache.h` ownership/protection, and renderer resource retirement. Preserve failure-state retention on readback timeouts; failed data must not become guest-visible success.

For thread-local scratch reuse, establish non-reentrancy or add a lease/stack/pool design that survives nested use. Check:

- Resize invalidation of any outstanding CPU pointer/reference.
- Clearing logical DMA-region lists each operation.
- Checked multiplication/ranges for `pitch * height`, tiled surfaces and image dimensions.
- Copying only initialized bytes and retaining read-modify-write semantics.
- Exception/stop/resize paths and capacity high-water behavior.
- GPU resource lifetime until fence completion; distinguish that from CPU arrays whose Vulkan API consumes their contents during the call.

Capacity reuse removes repeated allocation after warm-up; it does not prove zero allocations for every possible larger texture.

For pipeline keys, test `equal(a,b) => hash(a)==hash(b)` and one-field semantic differences under each supported dynamic-state mode. The inspected `minSampleShading` equality uses floating comparison while its hash uses raw bits; +0 and −0 need an explicit consistent policy. Determine whether problematic values are reachable before attributing a game fault.

Inventory static, dynamic and extension state from actual pipeline creation. Ignoring a pointer address is correct; ignoring the semantic contents of a relevant `pNext` structure is not automatically correct. Do not demand different keys for fields that are genuinely dynamic and correctly emitted. [S15, S20]

Preserve unconditional descriptor binding/program loading from the black-screen fix. Any future elision needs command-buffer generation, layout compatibility, descriptor content/version, and dynamic-state validity tracking—not only `reload_state || update_descriptors`.

Use validation-enabled runs diagnostically where supported, but never compare their FPS directly with an uninstrumented release. A driver fault may be downstream of invalid application synchronization.

### 9.4 Memory-pressure termination or allocator failure

Distinguish native allocation failure, graphics allocation failure, process kill and ordinary high virtual address reservation. Correlate the matched exit with RSS/PSS/native/graphics memory, MemAvailable, swap activity and allocation high-water marks.

The reported 13.18 MB/cycle PSS slope is positive, not proof of zero monotonic growth. Separate one-time warm-up/cache population from repeatable retention. Five cycles and one scene are insufficient to certify every lifecycle path.

Track ownership by subsystem: LLVM objects/executable arenas, Vulkan images/buffers/descriptors, scratch capacity, JNI references, Kotlin histories, threads, file descriptors, and game/ISO handles. Free only resources whose users are finished. Do not free JIT code while readers can execute it.

### 9.5 Guest freeze or scene-streaming failure while the app survives

Check SPU mailboxes/events, PPU waits, job queue progress, RSX semaphore/readback waits, compiler tickets, and direct-ISO/file I/O. Record wait-for dependencies and the producer that should wake each consumer.

A scheduler change that exposes a hang is evidence of timing sensitivity, not proof that little cores make correct emulation impossible. Keep the current scheduling control while finding the missing synchronization or starvation mechanism.

For direct ISO reads, inspect actual read results, offsets, bounds, decompression and cancellation; do not blame storage merely because a new scene streams data. Detect short/error reads and preserve error propagation.

**Acceptance:** identify a cause supported by a failing reproducer, a minimal fix, and an independently repeatable pass through G6/G7. A workaround is labeled as a workaround and retains the unresolved diagnosis.

## 10. Ticket CR07 — Make GOW profile/patch receipts genuinely runtime-backed

**Files:** frontend `patch/PatchFastMode.kt`, `GameSettingsOverrides.kt`, `PatchRepository.kt`, launch/readiness integration; backend patch application and module-loading paths discovered from the current checkout. [S14]

The current structured receipt is useful, but its “appliedPatches” are derived from configured enabled names and successful configuration writes. Its `effectiveSettings` are copied from the profile map. Preserve the UI improvement while separating these stages:

`REQUESTED → CONFIGURED → RUNTIME_MATCHED → APPLIED/FAILED/NOT_APPLICABLE`.

A runtime application receipt should include run/session ID, title/update, loaded executable/module hash, patch identifier/version, expected and actual target identity, application result, and effective setting values observed by the core. Avoid exposing unrelated guest memory.

Record each imported patch's ownership/dependencies. The separately reported `Disable SPU MLAA` must be accounted for explicitly, including when Fast Mode is disabled. Do not let a leftover manually enabled patch contaminate the no-patch control.

Persist the selected experiment/profile identity. A call applying `MLAA_ONLY` should not later be reconstructed as an ALL-target receipt simply because the getter defaults to ALL. The settings-only experiment must be possible without secretly reapplying unrelated patches.

Define precedence once: defaults → compatibility profile → user configuration → explicit, declared diagnostic override. Production Fast Mode ownership should be clear and reversible. Test title A → title B → A so stale GOW settings cannot leak.

Keep Mega experimental until it passes the transition and longer route. A conservative temporary Normal setting is acceptable when the matrix supports it, with the measured performance difference disclosed.

**Acceptance:** native receipt and UI agree for ALL, MLAA-only, motion-blur-only, combined, disabled, unsupported/mismatched executable, and failed application. Each scored experiment archives its runtime receipt and complete enabled patch set.

## 11. Ticket CR08 — Requalify progression, save/load, and lifecycle

### Functional acceptance before driver performance work

Require three independent normal traversals through G2–G7, including at least one cold process launch and a fresh compatible generated-code cache run. Also test a warm-cache traversal. These are functional gates, not enough by themselves for a universal crash-rate claim.

Continue active gameplay beyond the tree for a declared interval, initially at least ten minutes where safe and practical. Do not stop immediately after the formerly crashing instruction and call the route stable.

### Samba save/load

Discover and use the actual Samba UI/save-state implementation. Distinguish emulator save states from normal in-game saves.

Test saving before the failure transition, restoring in the same compatible build, crossing G6, saving after the tree, restoring again, and continuing. Also test cancel/retry and stopping during a save operation through supported workflows.

Verify artwork/loading UI exits cleanly, controller input remains functional, no stuck progress/paused state, no stale surface/descriptor generation, and no old single-flight compilation ticket. Corrupt/incompatible states must fail clearly without modifying the good state.

Do not use a successfully restored emulator state as the only proof that normal loading/streaming was fixed. Confirm normal traversal separately.

### Lifecycle and memory

Run repeated start/play/stop cycles after an explicit warm-up policy; use at least 20 cycles for the dedicated plateau investigation when device conditions permit, not 20 blind repeats of a known crash. Report slope and confidence/variation with units, pre/post-stop level, and subsystem allocation counts.

Use consistent game position and dwell time. Longer unique gameplay can legitimately populate new caches; distinguish that from unbounded per-cycle leaks. Set memory budgets from observed device headroom and component ownership, not an arbitrary fraction of the 80 GB virtual reservation.

**Acceptance:** exact route and save/load tests pass; no growing unresolved retention trend in the declared cycle test; failures remain in the record.


## 12. Ticket P11 — Resume backend optimization from a valid, stable baseline

This new gate precedes performance acceptance. Do not optimize against the old unqualified Phase 10 scene window.

### 12.1 Build a critical-path profile, not a CPU-percentage target

Collect a release-equivalent, symbolized CPU profile and scheduler trace for G1 combat, G3 transition, and G6 onward separately. Use Android Simpleperf/native profiling where supported; verify permission and unwinding quality. Register/export JIT address-to-guest-block mappings through a supported mechanism or explicit sidecar; ordinary ELF symbols do not automatically name generated code. [X01]

Attribute:

- SPU translated execution versus compilation versus synchronization/waiting.
- PPU translated execution, HLE/kernel work, and guest-side job production.
- RSX command decoding, state preparation, pipeline lookup/creation, conversions and readbacks.
- Driver userspace work, system calls, page faults and CPU scheduling delays.
- Auxiliary logging/monitoring/lifecycle work.

Report CPU milliseconds per correctly completed frame and critical-path wall time. A frame can contain overlapping work, so do not sum all thread durations and call that frame latency.

Test the profiler's perturbation on an otherwise matched run. Missing PMU counters are unavailable, not estimated measurements.

### 12.2 Choose the largest supported opportunity

| Measured bottleneck | Scoped next optimization | Correctness/performance gate |
|---|---|---|
| Hot SPU integer/SIMD blocks | Inspect IR and AArch64 instructions for scalarization, redundant lane shuffles, spills and repeated loads; optimize one family. | Differential semantics tests and same-scene frame-time gain |
| Recurrent runtime compilation | Correct cache reuse, reduce duplicate/oversized modules, measure pass costs and bounded distinct-block compilation. | Fewer compile stalls without lower-quality code causing steady-state regression |
| Guest spin/wait overhead | Identify side-effect-free waiting contract, notification source and deadline; use a wakeup-safe bounded wait strategy. | No missed wakeups, lost guest timing or new FIFO deadlocks |
| RSX CPU submission cost | Reduce proven redundant state preparation with correct generation/version tracking. | Validation and visual correctness plus lower RSX CPU/frame |
| Readback/conversion cost | Reduce redundant work or reuse safe storage; consider an alternative path only with complete memory visibility semantics. | Same guest data and synchronization under fault/timeout tests |
| Memory bandwidth/allocations | Batch ownership-safe work, eliminate unnecessary copies and retained compiler objects. | Stable memory, no resource lifetime regression |
| Runnable delay/contention | Measure scheduling or compiler-budget alternatives individually. | Lower critical-path delay, not merely lower reported CPU |

Do not automatically rewrite the renderer or add an ARM “fast tier” because x86 has one. A new tier is a separate project requiring its own decoder coverage, semantics, promotion, cache identity and lifetime tests. It is justified only if compilation remains a dominant measured cost after correctness repair.

Likewise, host `-O3`, LTO or CPU-tuning changes require controlled builds and code-size/compile-time measurement. Do not use `-ffast-math`, unsafe feature flags or unqualified SIMD width changes as generic switches.

### 12.3 Iteration discipline

Use the same preserved game position and cache condition for paired runs. Change one component, collect at least three paired runs for an initial screen, then at least five pairs for a release-worthy performance claim when conditions permit. Alternate/randomize order to reduce warming/thermal bias.

Record the per-run distribution and uncertainty. A single peak or a changing scene is not a measured improvement. Reject a throughput gain that breaks G2–G7, save/load, game speed, or rendering.

After every accepted change, update the baseline and re-profile. Do not add hypothetical speedups together. Keep the 60 FPS target open while reporting the measured remaining gap honestly.

## 13. Original Phase 11 / D11 — Controlled Turnip specialization

**Status at handoff:** not completed. It remains in the roadmap.

### 13.1 Entry gate

Normally begin after CR08 establishes a stable route. If CR06 identifies a matched GPU fault, a diagnostic driver comparison can happen earlier with the exact same core/configuration; record it as diagnosis, not a performance release.

The reported 40–58% busy value does not locate the bottleneck by itself. Inspect GPU frequency, queue idle gaps, CPU submission time, waits and actual GPU stage duration.

### 13.2 Reproducible build identity

Determine the actual shipped/imported Turnip source lineage and Android KGSL requirements. Preserve the working binary as the control. Select a pinned candidate revision that supports the device/loader and archive:

`Mesa commit, downstream patches, toolchain/sysroot, build options, KGSL backend, enabled features, loader ABI/entry points, ELF build ID, library SHA-256, driver UUID, pipeline-cache UUID`.

Use the frontend's existing driver import/selection path. Verify the selected library is actually loaded; a UI label alone is not evidence. Avoid changing core and driver simultaneously. Separate each driver's cache identity and preserve the existing working driver for rollback.

Produce a symbolized diagnostic artifact and the corresponding release library from the same declared source/configuration. Debug instrumentation can change timings and must not be mistaken for a release comparison.

### 13.3 Driver experiments

First establish correctness through G2–G7 and repeated normal launches. Then measure:

- CPU cost of driver calls and pipeline compilation.
- GPU render/compute/blit stage durations and idle gaps.
- GMEM/system-memory rendering behavior where relevant to this exact build.
- Shader register pressure/spills for demonstrably hot shaders.
- Readback/fence/submit behavior and memory allocation lifetime.

Mesa documents Turnip support for `u_trace`, including text/JSON and Perfetto integration. Verify the selected Android/KGSL build actually provides the desired producer/transport. Do not assume desktop DRM/MSM replay tools work unchanged on this phone. Crash-interrupted trace output may be incomplete; preserve that fact. [X03]

Example diagnostic environment settings, only after checking support in the selected build:

```text
MESA_GPU_TRACES=print_json
MESA_GPU_TRACEFILE=<writable app diagnostic path>
```

These are driver environment settings, not commands that have already been executed. Set them before driver initialization using the supported loader mechanism. Do not enable incompatible trace modes together.

Target an identified hotspot or fault with one minimal patch. Prefer an upstreamable fix with a reproducer over an opaque “Samba turbo” preset. Do not advertise unsupported Vulkan features, suppress device-loss errors, skip barriers, or hardcode game-specific correctness violations.

### D11 acceptance

The driver passes the exact transition, save/load/resource-lifetime checks, and an extended route. A performance claim includes paired same-core runs, actual loaded-driver identity, cache policy, thermals, image/correctness review, and uncertainty. A custom driver that merely builds is IMPLEMENTED, not VERIFIED faster.

## 14. Original Phase 12 / U12 — Upstream porting with semantic review

Update the upstream port ledger using current primary repository history at execution time. Identify what is already present, what was locally modified, and what was later reverted. Do not assume a PR title proves your fork lacks a change.

For each candidate, record upstream commit/PR, affected contract, dependency commits, expected measured hotspot, architecture/ABI assumptions, local adaptation and tests. Port one coherent change at a time.

Prioritize ARM SPU/PPU correctness, compiler fixes matching the captured reproducer, and demonstrated hot-path improvements. Do not blindly merge all of upstream into the crash-recovery branch.

Keep x86 unaffected where shared code changes; run available cross-architecture tests and explicitly mark unavailable ones. Preserve game/config/patch cache compatibility or version it deliberately.

**Acceptance:** each retained port has a native test or reproducer and device evidence for the claimed scope. Reverted candidates remain in the ledger with reasons.

## 15. Original Phase 13 / Q13 — Final 60 FPS and reliability acceptance

Do not substitute an “optimization phases complete” certificate for this gate.

### 15.1 Functional requirements

The final source/binary/driver combination must pass:

- Normal traversal through G2–G7 in at least three independent runs.
- Cold and warm compatible cache conditions, reported separately.
- The Samba save/load cases in CR08 and repeated stop/restart.
- At least 30 minutes of active, progressing gameplay in a declared route including the previously failing transition; not 30 minutes of menus or standing still.
- Correct game speed, controller response, audio behavior and rendering in the tested route.
- No matched unexplained native/Java crash, GPU device loss, FIFO desync, unresolved hang or corrupted save in the acceptance run.

Report the number of runs and duration actually tested. “0 failures in N runs” is the valid statement; it is not “100% crash-proof.”

### 15.2 Performance requirements

Before final testing, declare internal/output resolution, quality-removal patches, frame limiter and guest-speed settings, driver, cache policy, route and thermal conditions.

The 60 FPS budget is approximately 16.67 ms per new game frame. A proposed practical tolerance for a nominal 60 FPS acceptance is mean throughput at least 59.4 FPS over the declared scored windows, P95 no more than 20 ms and P99 no more than 33.3 ms, with no sustained slower segment hidden by menus or pauses. These are **proposed acceptance thresholds**, not measured results. Report actual figures and any stricter agreed target.

Do not count interpolated, repeated, overlay-only or menu frames as new game simulation/rendering progress. Separately report presentation throughput and guest progress when they differ. No guest-speed acceleration that makes physics run too quickly qualifies.

Use complete frame events or explicitly declare the run unqualified for full-frame tail metrics. Include stalls and the entire declared interval. Keep cold compilation metrics separate from warmed steady state without hiding either.

The historical 12.728 FPS observation would require about 4.71× throughput to reach 60, if a matched valid baseline reproduced it. That arithmetic is not a forecast. Re-establish the baseline first.

### 15.3 Efficiency requirements

At comparable gameplay and quality, compare CPU time/frame, energy/frame where measurable, thermals, memory and frame-time tails. Lower CPU utilization by slowing the guest is not a win. A higher GPU busy percentage may simply mean the CPU supplies work more effectively; it is not automatically a regression.

Do not disable thermal management to meet the target. Declare cooling/charging conditions and preserve those conditions across comparisons. An unsupported power measurement stays unavailable.

**Verdict:** Q13 remains NOT_ACHIEVED until its evidence passes. A stable 15/20/30 FPS improvement may be retained and honestly reported without relabeling it 60 FPS.

## 16. Original Phase 14 — Integration and release packaging

Integrate only accepted changes. Freeze frontend commit, backend gitlink, submodule dependency identities, driver artifact, configuration/patch schema and generated-cache version.

Build and test from a clean checkout with the intended release configuration. Verify packaged AND runtime-loaded core identity. Archive matching symbols and provenance manifests. Use the existing project packaging verification scripts rather than inventing a second weak “grep hash” check.

Use in-place installation where signing compatibility allows; do not uninstall and erase the user's data as a routine build step. A signing mismatch needs a deliberate preservation/recovery procedure, not silent data loss.

Run the final route on the exact packaged artifact. Include required code tests, native stress/differential tests, analyzer/classifier negative tests, lifecycle/save-load tests and device run evidence. A source-string assertion suite does not validate C++ concurrency, ARM code generation or GPU synchronization.

Prepare `reviewer_handoff_gaia_recovery.md` with actual commands and results. State what was not run. Do not claim the local worktree is clean or remotes synchronized without executing those checks after the final commit/push.

## 17. Evidence layout and schema

Use an immutable directory per run:

```text
docs/benchmarks/gaia-recovery/<run-id>/
  run-manifest.json
  events.jsonl
  milestones.jsonl
  frame-events.jsonl
  frame-analysis.json
  inputs.jsonl
  config/
    requested.json
    effective-native.json
    patches-runtime.json
    driver-identity.json
  screenshots/
    <marker>-<device-monotonic-time>.png
    screenshot-manifest.json
  logs/
    host-logcat-prelaunch-to-end.log
    backend.log
    crash-buffer-before-cleanup.log
    post-cleanup.log
  crash/
    exit-records.json
    tombstone.pb
    tombstone-decoded.json
    symbolized.txt
    diagnosis.json
  profiles/
  checksums.sha256
```

Only create artifacts that actually exist. Missing/unavailable files are represented in the manifest with a reason, not zero-byte placeholders masquerading as successful collection. Large traces can be retained externally with hashes and reproducible access; do not commit copyrighted game payloads.

Minimum manifest fields:

```json
{
  "schema_version": 2,
  "run_id": "<unique>",
  "experiment_id": "<matrix condition or ticket>",
  "frontend_commit": "<actual>",
  "core_commit": "<actual>",
  "apk_sha256": "<actual>",
  "loaded_core_sha256": "<actual or explicitly unavailable>",
  "device": {"serial": "d30a1726", "boot_id": "<observed>"},
  "process": {
    "name": "com.zenithblue.sambas3",
    "pid": 0,
    "start_identity": "<observed>"
  },
  "emulator_session_id": "<observed or explicitly assigned and propagated>",
  "window": {
    "clock": "device_monotonic",
    "start_marker": "G1_FIGHT_START",
    "end_marker": "G7_BEYOND_TRANSITION",
    "start_us": 0,
    "end_us": 0
  },
  "milestones_observed": [],
  "first_failure": null,
  "cleanup": {"attempted": false, "result": "NOT_ATTEMPTED"},
  "evidence_complete": false,
  "verdict": "INCONCLUSIVE_EVIDENCE"
}
```

This is a schema example with placeholders, not a valid run. Never submit these zero values as collected measurements. Define units and null/unavailable handling in the implementation.

Each screenshot record includes raw SHA-256, path, marker, run/session/PID identity, device timestamp and reviewer observation. Keep Git blob SHA separate from raw file SHA-256. An image can prove a visible state; it does not by itself prove FPS, correct game speed or successful later progression.

## 18. Ticket order and stop conditions

| Order | Ticket | Gate to leave it |
|---|---|---|
| 1 | CR00 | Original failure conditions and provenance preserved |
| 2 | CR01 | Crash-plus-cleanup cannot pass; process/session identity enforced |
| 3 | CR02 + minimum CR03 instrumentation | Actual transition failure captured with trustworthy route identity |
| 4 | CR04 | Small failing/passing matrix or clear common failure signature |
| 5 | CR05 and selected CR06 branch | Known correctness gaps repaired and actual cause addressed |
| 6 | CR07 | Native profile/patch application verifiable and reversible |
| 7 | CR08 | Transition, continued play, save/load and lifecycle requalified |
| 8 | P11 / D11 / U12 | Measured, individually accepted optimizations |
| 9 | Q13 | Declared sustained performance and reliability gates actually pass |
| 10 | Phase 14 | Exact final artifact and handoff verified |

CR05's unit/contract work may proceed while collecting CR02 evidence, but do not merge multiple speculative backend changes before isolating the original failure. D11 may enter early only for the diagnostic exception described above.

Stop a run for device safety, unrecoverable access/tool failure, or a captured fatal fault. Preserve the record and next experiment. Do not stop merely because a checklist has ten headings marked “implemented”; do not run uncontrolled infinite loops that repeatedly crash or overheat the device.

## 19. Required final handoff format

```text
Verdict:
  Gaia transition: PASS / FAIL / INCONCLUSIVE
  Sustained 60 FPS: ACHIEVED / NOT_ACHIEVED / NOT_TESTED

Actual frontend/core/driver identities:
Actual installed/runtime hashes:
Original reproducible failure:
Root cause evidence (or remaining UNKNOWN):
Minimal fix and rejected hypotheses:
Exact G2–G7 route evidence:
Normal boot versus save-state results:
Raw frame-event completeness and scoring boundaries:
Per-run throughput / P50 / P95 / P99 / stalls:
Thermal, CPU/frame, memory and power observations:
Tests executed (commands, exit codes, counts):
Tests not executed and why:
Cleanup result, separately from gameplay result:
Remaining risks:
Next smallest experiment:
```

Attach real source locations, run manifests, checksums and images. Do not write “100% verified,” “zero CPU overhead,” “no leaks,” or “all crashes resolved” beyond what the test scope actually establishes.
