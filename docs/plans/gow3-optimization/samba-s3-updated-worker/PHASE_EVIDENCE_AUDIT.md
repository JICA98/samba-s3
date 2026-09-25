# Phase and screenshot evidence audit

**Reviewed revisions:** frontend `1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd`; backend `141af96fa006f56c25ec335137e92c78b29b17e3`.

## Scope and limitation

Repository reports, selected source changes, raw Phase 10 numerical/manifest artifacts, and screenshot directory metadata were retrieved.

**The PNG pixels could not be rendered in this review.** Repository image requests failed, and the text-oriented file action could not decode PNG bytes. Therefore this document does not assert that any specific screenshot shows correct graphics, the claimed scene, or passage through the tree.

Metadata does permit an exact duplicate-file finding. A screenshot filename, small file size, or descriptive report caption is not treated as visual proof.

## Screenshot identity audit

All listed paths are under `docs/benchmarks/screenshots/`. The hashes are **Git blob SHA-1 identities**, not raw SHA-256 checksums. [S10]

| Asset | Git blob identity | Size, bytes | What can be concluded |
|---|---|---:|---|
| `gow3-phase7-gameplay.png` | `fd05ce11598bb70501aa7293f0e239bbfc98dbb8` | 3,856,339 | Exact same stored blob as older combat screenshot below |
| `gow3-revised-gameplay-combat.png` | `fd05ce11598bb70501aa7293f0e239bbfc98dbb8` | 3,856,339 | Same bytes; not independent Phase 7 capture |
| `gow3-phase8-gameplay.png` | `9783ab9ea94520bebed538a8bce863d1d1e494e4` | 134,286 | Exists; pixels and scene not verified |
| `gow3-phase10-gameplay.png` | `f84cb7515e8cd290a8a070323b0e8b674247c020` | 4,078,250 | Exists; pixels and scene not verified |
| `gow3-blackscreen-fix.png` | `9c25974bc8136b1aca498dfa05a47db766b9c120` | 2,524,205 | Exists; pixels and scene not verified |

This table does not imply there are no other screenshots inside individual evidence bundles. The worker must enumerate all phase-specific evidence directories before declaring an image missing.

The current runner overwrites a Phase 10 canonical screenshot path and writes a hardcoded user-specific path before its run-local copy. It captures after a process-liveness check rather than a scene assertion. Correct these provenance weaknesses; do not infer motive from them. [S04]

## Phase status for the next worker

These statuses describe this review's evidence, not a re-run of the previous tests.

| Original phase | Review disposition | Required follow-up |
|---|---|---|
| 1 — Baseline/tooling | Implemented tooling exists; qualification reopened | Fix runner outcomes, missing-hash handling, launch identity and actual scene boundaries |
| 2 — Metrics | Handoff reports typed parsers and reduced polling; do not rewrite blindly | Retain useful work, inspect current implementation, validate sources/rates and sampling overhead; zero in top is not zero cost |
| 3 — Crash classification | Reopened: concrete precedence/identity defects | Crash-before-cleanup fixtures and exact process/run correlation |
| 4 — Logging/frontend | Claimed work should be retained pending scoped verification | No new claim that the old hot worker still exists; profile current stacks only if needed |
| 5 — Scheduling | Current performance-affinity policy is the control | OS-mode failure is timing-sensitive evidence, not proof of a hardware-caused FIFO defect |
| 6 — SPU lowering | Implementation and dual-device deployment complete; qualification/acceptance open | Differential test suite passed, Cortex-X4 codegen verified, standard release APK built & deployed to Poco X6 Pro & OnePlus 13R; transition/gameplay testing pending |
| 7 — Single-flight / 99% cache | Reopened for correctness | Producer completion, state/lifetime/cancellation tests and cache identity audit; fresh screenshot |
| 8 — RSX / scratch / hashing | Partial retention and partial reversion | Do not count reverted elision; test scratch ownership and semantic key contracts |
| 9 — Memory/lifecycle | Handoff's five-cycle result is limited evidence | Recompute with warm-up policy, consistent scene, units and longer cyclic/continuous coverage |
| 10 — GOW Fast Mode | Configuration/UI implemented; progression and runtime effects not fully qualified | Native receipts, Mega/Normal matrix, all imported patches accounted for, complete G2–G7 route |
| 11 — Turnip | Pending | Reproducible controlled build and same-core diagnosis/performance A/B |
| 12 — Upstream | Pending qualification | Port ledger with exact commits, dependencies and native tests |
| 13 — 60 FPS / 30-minute run | Not demonstrated | New-frame throughput at correct game speed; full route and frame-event evidence |
| 14 — Release | Blocked on required acceptance | Exact packaged artifact tested; no broad “all fixed” claim |

## Numerical reconciliation required

Phase 10's archived JSON establishes:

- 46 parsed telemetry samples.
- 718 presented-counter increments over 56.411 seconds.
- 12.728014 FPS counter-based interval throughput.
- 46 latest-interval values used for the reported 82.0075 ms median, 203.99075 ms P95, 251.6351 ms P99, and 17 samples over 100 ms.

The throughput arithmetic is internally consistent, but neither the scene label nor full-frame tail coverage follows from that arithmetic. [S09]

Reconcile the user's Phase 1 E00 “12.95 active FPS” with the old 6.78 FPS comparison baseline. Those may represent different scenes/windows; neither a gain nor a regression should be inferred without matching them.

Reconcile “45-second active benchmark” against the actual 56.411-second analysis interval and collection boundaries. Do not silently trim adverse samples to fit the narrative. [S04, S06, S09]

The handoff's 13.18 MB/cycle PSS slope is not a zero slope. It may include warm-up/cache behavior; longer consistent measurements are needed to distinguish it from retention. No independent memory-leak calculation was performed for this package.

## Required new visual audit

Create a row per phase/run, using original images and the corresponding run manifest:

`phase, run_id, capture_time, PID/start/session, source revisions, raw SHA-256, claimed scene, observed scene, artifact reused?, verdict`.

Use verdicts `VERIFIED_VISIBLE_STATE`, `REUSED_REFERENCE`, `WRONG_SCENE`, `INCONCLUSIVE`, or `MISSING_AFTER_COMPLETE_SEARCH`.

For the current bug, obtain direct images of first-fight completion, transition entry, Gaia/fire completion, tree interaction, tree passed, and later controllable gameplay. Prefer a continuous transition clip plus stills. The reviewer must actually open them.

Do not edit away error overlays, crop out relevant failure state, or present generated/reconstructed imagery as device evidence. Keep any annotated explanatory copy separate from its original.

A visual pass is necessary for scene and rendering claims; it is not sufficient for performance or crash-rate claims.
