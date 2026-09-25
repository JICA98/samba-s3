# Source registry

Reviewed on September 25, 2026. Repository URLs below are pinned to the reviewed revisions unless an API metadata endpoint is explicitly identified. This package is a source/evidence review and implementation plan, not a new phone benchmark.

## Repository and application sources

**S01 — Frontend revision.** Actual master revision retrieved through the GitHub connector: `1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd`.
[Commit](https://github.com/JICA98/samba-s3/commit/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd).

**S02 — Backend revision.** Actual samba-android revision: `141af96fa006f56c25ec335137e92c78b29b17e3`.
[Commit](https://github.com/abhay-byte/samba-s3-core/commit/141af96fa006f56c25ec335137e92c78b29b17e3).

**S03 — Frontend submodule binding.** The gitlink at `app/src/main/cpp/rpcsx` is `141af96fa006f56c25ec335137e92c78b29b17e3` and points to `abhay-byte/samba-s3-core`.
[Pinned contents metadata](https://api.github.com/repos/JICA98/samba-s3/contents/app/src/main/cpp/rpcsx?ref=1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd).

**S04 — Benchmark runner.**
[run-gow3-benchmark.py](https://github.com/JICA98/samba-s3/blob/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd/scripts/perf/run-gow3-benchmark.py).
Reviewed provenance validation, fixed-delay navigation, screenshot capture, liveness loop, cleanup/manifest writing, analyzer invocation and final return. The important navigation/outcome region is approximately lines 420 onward. Use function names if lines change.

**S05 — Offline crash classifier.**
[classify-crash.py](https://github.com/JICA98/samba-s3/blob/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd/scripts/perf/classify-crash.py).
Reviewed `match_exit_record`, `classify_exit`, deliberate-stop keywords and their ordering relative to native/Java failure.

**S06 — Phase 10 report.**
[2026-09-25-oneplus-13r-phase10-gow3.md](https://github.com/JICA98/samba-s3/blob/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd/docs/benchmarks/2026-09-25-oneplus-13r-phase10-gow3.md).
Contains the 45-second benchmark narrative, Fast Mode claims, 12.73 FPS result and Thermal Status 3 in the hardware table. These are archived report claims, not independently repeated results.

**S07 — Phase 10 manifest.**
[run-manifest.json](https://github.com/JICA98/samba-s3/blob/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd/docs/benchmarks/evidence-e07-g10-fast-mode-profile/run-manifest.json).
PID 28772; historical APK/core hashes; collection time; `clean_stop=true`; stop reason DEBUG_STOP_GAME. It lacks the full launch/process-start/window identity required by the updated plan.

**S08 — Frame analyzer.**
[analyze-frame-events.py](https://github.com/JICA98/samba-s3/blob/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd/scripts/perf/analyze-frame-events.py).
Reviewed periodic crosscheck parsing, deduplication, source mixing, `compute_frame_metrics`, sampled tails and long-gap pause handling.

**S09 — Phase 10 numerical artifact.**
[frame-analysis.json](https://github.com/JICA98/samba-s3/blob/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd/docs/benchmarks/evidence-e07-g10-fast-mode-profile/frame-analysis.json).
46 parsed samples, 718 counter-derived frames, 56.411 seconds, 12.728014 FPS; 46 interval values produce the reported percentile and stall statistics.

**S10 — Screenshot identities.**
[Pinned screenshot directory metadata](https://api.github.com/repos/JICA98/samba-s3/contents/docs/benchmarks/screenshots?ref=1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd).
Metadata proves Phase 7 and the older revised combat screenshot share a Git blob. Image pixels could not be rendered in this review; see PHASE_EVIDENCE_AUDIT.md.

**S11 — Progress completion.**
[system_progress.cpp](https://github.com/abhay-byte/samba-s3-core/blob/141af96fa006f56c25ec335137e92c78b29b17e3/rpcs3/Emu/system_progress.cpp) and
[SPUCommonRecompiler.cpp](https://github.com/abhay-byte/samba-s3-core/blob/141af96fa006f56c25ec335137e92c78b29b17e3/rpcs3/Emu/Cell/SPUCommonRecompiler.cpp).
The introduced counter-forcing and `ptotal-1` behavior is also visible in
[commit af45237121787df3a8ca3cd316529c6ae76acc44](https://github.com/abhay-byte/samba-s3-core/commit/af45237121787df3a8ca3cd316529c6ae76acc44).
Current progress-server completion region approximately lines 335–415 was inspected directly.

**S12 — Current SPU compilation and cache-key path.**
[SPULLVMRecompiler.cpp](https://github.com/abhay-byte/samba-s3-core/blob/141af96fa006f56c25ec335137e92c78b29b17e3/rpcs3/Emu/Cell/SPULLVMRecompiler.cpp).
Current compile entry/state/cache-tag region approximately lines 1490–1635 was inspected directly.

**S13 — Latest ARM rollback.**
[commit 141af96fa006f56c25ec335137e92c78b29b17e3](https://github.com/abhay-byte/samba-s3-core/commit/141af96fa006f56c25ec335137e92c78b29b17e3).
Removes the explicit SHUFB tbl2 paths, explicit SVE feature additions, and the EngineBuilder `setMAttrs` block. Compiler/ISA safety beyond the observed rollback requires further verification.

**S14 — Current Fast Mode implementation.**
[PatchFastMode.kt](https://github.com/JICA98/samba-s3/blob/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd/app/src/main/java/com/zenithblue/sambas3/patch/PatchFastMode.kt).
Reviewed curated GOW settings, receipt construction, enabled-name matching, ALL-versus-granular experiment handling, patch enable/disable and preference persistence.

**S15 — Renderer optimization and subsequent correction.**
[V08 commit 9f3eb74df64cf57f59bbf5d67fda6529c8601afc](https://github.com/abhay-byte/samba-s3-core/commit/9f3eb74df64cf57f59bbf5d67fda6529c8601afc) adds scratch reuse, semantic pipeline hashing and descriptor/pipeline elision.
[Correction aae290b7a8db0e9af696cb9ee7093da0ffc99326](https://github.com/abhay-byte/samba-s3-core/commit/aae290b7a8db0e9af696cb9ee7093da0ffc99326) removes the unsafe descriptor guard and early pipeline return.

**S16 — Single-flight introduction.**
[commit 1ce15c156fb529aa9245a3f6fbe94a4d3528a4a4](https://github.com/abhay-byte/samba-s3-core/commit/1ce15c156fb529aa9245a3f6fbe94a4d3528a4a4).
Reviewed state machine, publication, waiters, cache tagging, ASMJIT changes and post-publication mapping cleanup. This is the actual expanded SHA resolved from the short ID.

**S17 — Phase 8 report.**
[2026-09-25-oneplus-13r-phase8-gow3.md](https://github.com/JICA98/samba-s3/blob/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd/docs/benchmarks/2026-09-25-oneplus-13r-phase8-gow3.md).
Claims a 45.7-second run and successful descriptor/pipeline elision, which later needed reversal. Retain historical claims as history; do not transfer them unchanged to the final build.

**S18 — Existing controller tooling.**
[sambas3-controller skill](https://github.com/JICA98/samba-s3/blob/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd/.agents/skills/sambas3-controller/SKILL.md).
Documents `debug-pad.sh`, `gamepad.sh`, button/hold/stick support and DebugPad acknowledgement requirements.

**S19 — Repository instructions.**
[AGENTS.md](https://github.com/JICA98/samba-s3/blob/1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd/AGENTS.md).
Contains useful build/tool locations but also architecture/install guidance that must be reconciled with current build/loader code and preservation of user data. It does not override this task's declared device scope.

**S20 — Current pipeline key equality and hashing.**
[VKPipelineCompiler.h](https://github.com/abhay-byte/samba-s3-core/blob/141af96fa006f56c25ec335137e92c78b29b17e3/rpcs3/Emu/RSX/VK/VKPipelineCompiler.h).
Reviewed equality near lines 1–100 and multisample hashing near lines 270–300. The proposed float-key/reachability tests are an investigation, not a confirmed cause of the Gaia failure.

## Primary external technical references

**X01 — Android NDK debugging and native profiling.**
[Android Developers: Debug your project](https://developer.android.com/ndk/guides/debug).
Supports native symbolization, diagnostic tooling and Simpleperf profiling. Native tombstone streams through ApplicationExitInfo are protobuf on supported API levels.

**X02 — Application exit traces and tombstone format.**
[ApplicationExitInfo API](https://developer.android.com/reference/android/app/ApplicationExitInfo);
[AOSP tombstone.proto](https://android.googlesource.com/platform/system/core/+/refs/heads/master/debuggerd/proto/tombstone.proto).
Trace availability is not guaranteed. Preserve the raw representation and associate it with the correct process/run.

**X03 — Mesa tracing.**
[Mesa u_trace documentation](https://docs.mesa3d.org/u_trace.html).
Documents Turnip tracing and output modes. Actual Android/KGSL build support and transport availability must be checked during implementation.

## Evidence boundaries

The user's description is the source for the new post-first-fight crash location. No matching new crash packet was supplied or captured during this review. Device benchmarks, local repository cleanliness, full test-suite results, image pixels and successful passage through the tree have not been independently executed/verified here.

Descriptions of five lifecycle cycles and a 13.18 MB/cycle PSS slope come from the user's handoff; they are not a newly computed leak analysis. Proposed tests, scripts, schema fields and acceptance thresholds in WORKER.md are requirements for subsequent implementation, not existing capabilities.
