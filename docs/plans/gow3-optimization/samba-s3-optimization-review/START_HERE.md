# Agent starter prompt

Work on `JICA98/samba-s3` and its published backend `abhay-byte/samba-s3-core`, branch `samba-android`. Read repository AGENTS.md instructions, then REVIEW.md, WORKER.md and SOURCE_REFERENCES.json from this bundle. The review anchors are frontend `270d72a56a926375f16f6c9a22474acdda99cd8c` and backend `ed8ba6c12c218249524a441b79f48d6bae842394`; preserve any newer work and document the delta.

Implement WORKER.md in its dependency order. The objective is sustained genuine 60 FPS God of War III on OnePlus 13R at correct game speed, no recurrent crashes and minimal CPU/energy per useful frame. Do not promise or mark this target achieved from menu FPS, sparse telemetry, a short combat run, repeated frames or reduced simulation speed.

Begin by validating the installed APK/core/driver against the source, fixing GPU/thermal metric semantics, collecting the actual crash signature and profiling the hot DefaultDispatch worker plus native SPU/PPU/RSX paths. Preserve already-corrected build, timer, affinity, readback and JIT publication behavior. Do not start with a driver rewrite or generic barrier removal.

Maintain an experiment ledger with raw evidence, exact revisions, commands, test status, paired results, correctness and thermal behavior. Review each change independently, publish the core commit before the frontend gitlink, and retain only defensible improvements. Add tracing, benchmark and parser tests specified in the plan. Test save/load and start/stop regressions.

At each handoff report measured progress, the best stable artifact, largest remaining bottleneck and the next falsifiable experiment. Distinguish PASS, FAIL, NOT_RUN and BLOCKED. Deliver partial measured improvements honestly when the complete target is not yet met; never fabricate device runs or declare all R01–R20/native correctness gates passed from source-text tests alone.
