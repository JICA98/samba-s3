# Start here — Updated Gaia crash worker

Read WORKER.md completely, then REVIEW_ADDENDUM.md, PHASE_EVIDENCE_AUDIT.md and SOURCES.md.

Continue the existing SambaS3 work; do not restart the entire old optimization plan. The current blocker is God of War III crashing after the first fight, before Gaia's shoulder/fire sequence and the tree-lifting path. Target OnePlus 13R, device d30a1726.

Reviewed frontend: 1edbebd7ce5c0be9b6949fb64f9d9039e1e179cd.
Reviewed backend: 141af96fa006f56c25ec335137e92c78b29b17e3.
Re-resolve current remotes and preserve all local/user data before changing anything.

Your first deliverable is CR00/CR01: preserve the failing configuration and fix the runner/classifier so that a crash followed by successful cleanup CANNOT pass. Do not start a Turnip performance fork yet. Produce the failing negative test, the fix, its passing test, and an honest run manifest.

Then reproduce the actual first-fight → fire → tree route using observed scene milestones. Fixed sleeps, process liveness and periodic SQUARE presses are not route verification. Capture the first actual fault and perform the small Mega/Normal × patches/no-optional-patches matrix with verified native effective settings.

Reopen the 99% completion shortcut and SPU single-flight/cache correctness issues as directed. Do not force counters complete, remove synchronization, hide errors, delete user saves, or label a 45-second run “all crashes fixed.”

Keep the useful previous fixes. Work in small, reversible commits with actual tests. Record implementation, test execution, scene qualification and review separately. Do not invent independent subagents or test results.

After crossing the tree reliably, qualify normal boot, Samba save/load, continued play and memory behavior. Only then proceed to measured backend optimization, controlled Turnip work, upstream ports and the final 60 FPS/30-minute acceptance.

The long-term target remains real 60 FPS at correct game speed. Keep reporting measured progress without declaring the target reached until the acceptance evidence supports it.
