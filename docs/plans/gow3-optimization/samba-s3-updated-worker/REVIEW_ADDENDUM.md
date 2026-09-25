# Review addendum — What changes the next worker's priorities

## Decision

Continue from the existing work, but reopen qualification and specific correctness gaps. Do not accept “all phases done” as proof that the route past the first fight is stable. The next release gate is the user's Gaia shoulder/fire/tree sequence, not a timed early-combat loop.

**The exact new crash cause remains unproven.** No new phone run or build was performed for this review.

## Confirmed defects and inconsistencies

### 1. A detected crash can still produce runner success

The runner breaks after detecting process death but later returns zero. It subsequently attempts cleanup and may write DEBUG_STOP_GAME as a clean stop. Its navigation also treats elapsed sleeps and a live PID as proof of the target scene. These are concrete control-flow defects in qualification. [S04]

Repair this before accepting another pass/fail report. Distinguish game result, first failure and cleanup.

### 2. Cleanup can override a native-crash diagnosis

The offline classifier checks deliberate-stop markers before native and Java failure. A manifest stop reason can therefore override a matching crash. Generic words such as `clean` are accepted as stop evidence. Matching also needs stricter process/window identity. [S05]

The required negative test is: native crash during the route, then successful cleanup. The final gameplay verdict must remain failed.

### 3. Phase 10 does not have a complete latency distribution

The raw artifact contains 46 samples, a cumulative-frame delta of 718, and 56.411 seconds. The endpoint throughput is arithmetically 12.728014 FPS. The 46 sampled latest-frame intervals, however, cannot establish all 718 frames' percentiles or total stall count. [S09]

The report's 45-second narrative and the analysis's 56.411-second interval also need explicit boundaries. The runner currently analyzes its collected log without scene-bound arguments. [S04, S06]

The raw-event analyzer separately drops long gaps as pauses without explicit pause evidence. That would hide stalls in that branch. [S08]

### 4. The 99% “fix” can manufacture completion

Current progress logic can accept one remaining item, force done counters to totals, and terminate after repeated empty text. This affects the shared progress/PPU event path as well as the SPU cache experience. [S11]

This is not proof that the last compilation job is safe to execute or that it caused this crash. Replace it with producer-owned terminal status and test delayed/failing/canceled final work.

### 5. Runtime patch effects are still not proven by the receipt

The structured receipt improves UI honesty relative to a blind boolean, but current “applied” names come from enabled configuration and profile settings are copied into the result. They do not include a runtime-loaded executable/module application receipt. The getter also reconstructs ALL targets rather than the particular granular experiment. [S14]

Mega versus Normal must be tested with native effective configuration, because Fast Mode can override a manually edited setting. The separately mentioned SPU MLAA patch needs ownership/dependency tracking too.

### 6. Some historical optimization claims no longer describe the code

The descriptor-binding guard and premature pipeline-return optimization introduced in V08 were removed in the later correction. Do not credit those reverted operations to the current build. [S15]

The ARM emergency-spill rollback removed both tbl2 lowering and a broader target-attribute path. A successful earlier boot does not prove that every new streamed block is safe. [S13]

### 7. Thermal and screenshot evidence need correction

The Phase 10 report records Thermal Status 3 despite the handoff describing unthrottled operation. Battery temperature alone does not resolve that inconsistency. [S06]

Phase 7's screenshot and the older revised combat screenshot share the exact Git blob. It is reused evidence, not independent visual confirmation of Phase 7. Pixel rendering failed in this review; no visual correctness claim is made. [S10]

## High-priority investigations, not established crash causes

- SPU Mega block shape, new runtime compilation and compiler resource/register pressure.
- Single-flight failure/cancel/generation behavior and executable lifetime.
- Cache identity collapsing different effective feature sets.
- Patch mismatch/dependencies at a new scene/module transition.
- Scratch-buffer reentrancy, readback visibility and resource retirement.
- Pipeline-key semantic/equality correctness.
- Guest job/wait synchronization, direct-ISO streaming, or a matched GPU fault.

Use the CR04 matrix and the actual failure signature to choose among them. Do not implement speculative changes to all of them together.

## Scope of the model issue

The worker model's label is not the diagnosis. The source and evidence contain identifiable false-success paths independent of which model wrote them. The updated worker contract requires smaller changes, negative tests, real scene milestones, immutable evidence, and separation of implementation from verification.

## Immediate execution order

Preserve the current failing configuration; repair false-pass reporting; capture the exact route failure; compare current Mega/patches against controlled Normal/no-optional-patch conditions; repair the implicated backend path and the progress/state correctness gaps; qualify the route and save/load; then resume measured backend, Turnip and upstream optimization.

See WORKER.md for exact tasks and acceptance gates. See SOURCES.md for pinned source links.
