# Plan Review: kotlin-home-menu-ui-plan — PASS 2 ITER 1

## Verdict: APPROVE
## Counts: CRITICAL 0 · MAJOR 0 · MINOR 0 · SUGGESTIONS 3

## PASS 2 Fix Verification (all six changes confirmed present & correct)

| # | Pass-1 finding | Plan evidence | Source spot-check | Result |
|---|---|---|---|---|
| MAJOR-1 | Depth-aware close | Step 5 (:245-253): inject `(trackedDepth+1)` sequential CIRCLEs (120/150 ms envelope) BEFORE hide UI / `setMenuMode(false)`, then reset `trackedDepth=0`; ✕ row in Step 6 table (:272); S9 (:356-358); AC6 (:374-376); unit test #4 `buildClosePlan(trackedDepth)` yields exactly `(trackedDepth+1)` press/release pairs (:327-328) | `overlay_home_menu_page.cpp:167-176`: circle w/ parent → `set_current_page(parent)`+back (:170-174); root → exit (:175). Depth 1 ⇒ 2 CIRCLEs (parent→root→exit) = S9 wording exact | FIXED |
| MAJOR-2 | Touch consumption | Step 4 (:224-231): `onTouchEvent` returns true unconditionally for DOWN/MOVE/UP/CANCEL incl. scrim, rationale cites sibling fall-through + live fixed CROSS hazard; collapsed fall-through intent explicit (:232-235); AC8 (:380-384) "fixed dpad/face/L3/R3 live" scoped to collapsed ☰ mode only; S6 (:348-352) reworded | `activity_rpcs3.xml:16-19` full-screen siblings; `PadOverlay.kt:357-361` editables process in menu mode — hazard real, fix addresses it | FIXED |
| MINOR | FQN tag | Affected Components (:164) + change map (:308) both `<com.zenithblue.sambas3.overlay.HomeMenuView>` | mirrors FQN precedent `activity_rpcs3.xml:16` | FIXED |
| MINOR | Scheduler seam | Step 2 (:193-197): constructor-injected `postDelayed: (Runnable, Long) -> Unit`, default = main-looper Handler; test #3 (:323-326) fake queue scheduler, no Looper | JVM-safe pattern consistent with existing JUnit4 tests | FIXED |
| MINOR | R5/R6 extension | R5 (:408-419): native closure via on-screen ○/hardware gamepad, NO engine→Kotlin path, recovery = resync/toggle cycle/PS re-open (guarded `pad_thread.cpp:660`); R6 (:420-430): activity recreation drops menu state, PRE-EXISTING accepted, onDestroy-cancel prevents stuck buttons, future onSaveInstanceState | `AndroidManifest.xml:38-43`: RPCSXActivity has NO `android:configChanges` — claim verified | FIXED |
| MINOR | R9 future JNI | (:439-440) `isHomeMenuOpen()` noted as future work, no engine changes | — | FIXED |
| Handoff | Lists PASS 2 changes | (:446-458) maps every pass-1 finding to its fix | matches review file | FIXED |

## Regression / New-Defect Scan

1. **Depth-close vs exit-confirm flows**: No contradiction. Confirm dialog gates ONLY the Exit Game row, before any injection (Step 6 :271); ✕/toggle-OFF never trigger confirm; AC5 "No" sends zero frames.
2. **✕ vs toggle-OFF divergence**: None — identical depth-aware sequence grouped in Step 5 (:245), one table row (:272), one AC (AC6).
3. **S9 race feasibility**: Safe by ordering. During close-injection the Kotlin UI remains visible AND menu mode stays ON (hide + `setMenuMode(false)` deferred to sequence completion, :247-251). In collapsed mode a touch on fixed ○ falls through to PadOverlay but hits the new policy gate: `shouldAcceptOverlayTouch(true, true)=false` consumes-and-ignores, `shouldSuppressOverlayPadPush(true)` skips the push. Verified the cited gate region `PadOverlay.kt:312-417` is the single touch listener covering fixed buttons (:357-361) AND the sole `overlayPadData` push site (:388-392). Injection suppression IS active for the whole close sequence.
4. **AC objectivity**: AC1-AC10 observable; AC6 now exact-count objective.
5. **Scope creep**: Change map identical to pass 1 scope (3 NEW Kotlin + 2 modified + policy predicates + layout + tests; no C++/Gradle). R5/R6/R9 edits are documentation-only. None found.

## Suggestions (informational, non-blocking)

1. [SUGGESTION] `buildClosePlan` is specified in Testing Strategy (:327) but not named in Step 1's pure-function list or the HomeMenuModel change-map cell (:302). Worker will infer placement; one word would remove ambiguity.
2. [SUGGESTION] PS-glass edge landing inside the ~540 ms close-injection window could natively reopen the engine menu just as the injector finishes exiting it (orphaned open menu, undimmed). Sub-second corner of the R5/R6 desync class; recovery already documented (toggle cycle; native reopen guarded `pad_thread.cpp:660`). Acceptable residual risk.
3. [SUGGESTION] Tracked-open sentinel naming ("trackedDepth ≥ 0" open vs "flag cleared" closed, :245/:254) implies -1 but never names it; worker can pick any sentinel matching the stated semantics.

## Next Agent: Worker
## Next Action: Implement docs/plans/kotlin-home-menu-ui-plan.md as approved (PASS 2). Suggestions above are optional refinements, not requirements.
