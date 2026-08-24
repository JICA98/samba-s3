# Plan Review: ingame-menu-overlay-touch — PASS 2 ITER 1

## Verdict: REVISE
## Counts: CRITICAL 0 / MAJOR 4 / MINOR 2 / SUGGESTIONS 0

## Meta-finding: the revision was never applied

`docs/plans/ingame-menu-overlay-touch-plan.md` is byte-for-byte the same PASS 1 text:

- Header (plan:4) still reads `PASS 1 · ITERATION 1`.
- Step 3a (plan:105) still contains the literal phrase *"worker: confirm the cleanest reset against PadOverlayStick.kt"* that PASS 1 prohibited verbatim.
- `git log -- docs/plans/ingame-menu-overlay-touch-plan.md` → no commits; file untracked. No alternate revision exists (`ls docs/plans/` → only the original + unrelated `fix_gameplay_menu_controller_coinciding.md`).

None of the three MAJOR required changes and neither MINOR note from
`docs/reviews/ingame-menu-overlay-touch-plan-pass1-review.md` appears in the text.

## Findings

### [MAJOR][PLAN_GAP] PASS 1 revision not applied — plan resubmitted unchanged
- Location: whole file; header plan:4; steps plan:104-107
- Problem: All 5 PASS 1 findings persist (details below). Worker must not start.
- Evidence: plan:4, plan:105, plan:106, plan:154 vs pass1-review.md findings 1-5.
- Impact: Every previously identified correctness gap ships to @worker as-is.
- Required planner change: Apply the PASS 1 required changes, bump header to `PASS 2`, resubmit.

### [MAJOR][CODE_DEFECT] Fixed L3/R3 sticks still gated by menu mode despite design saying live
- Location: plan:106 (step 3b gates ":356-371") vs plan:29 ("fixed L3/R3 sticks stay live"); PadOverlay.kt:356-362
- Problem: Gate set still spans the fixed-stick loop (:356-362). Both policy functions (plan:97-100) remain byte-identical bodies (`!isMenuMode || isEditing`), so gating fixed sticks kills menu navigation via left stick. No acceptance criterion for fixed L3/R3 during menu mode exists (plan:162-172).
- Evidence: PadOverlay.kt:356-362 vs plan:106; plan:29; plan:97-100.
- Impact: Dead L-stick navigation + frozen mid-drag fixed stick in menu mode; contradicts plan's own design section.
- Required planner change: Gate ONLY floating handling (:364-371) + spawn (:379-397); leave :356-362 ungated; add objective criterion "fixed L3/R3 respond during menu mode"; differentiate the two policy functions (spawn gate needs `!sticks[i].isActive()` context or distinct logic).

### [MAJOR][PLAN_GAP] Floating-stick release semantics still delegated to worker discretion
- Location: plan:105 ("floatingSticks[i] = null … worker: confirm the cleanest reset"); PadOverlayStick.kt:39,49,68-78,136-151; PadOverlay.kt:391
- Problem: Naive null assignment leaves `locked != -1` (:69,:90,:138) → `isActive()` true forever → respawn check PadOverlay.kt:391 permanently fails for that side; ring stays displaced by onAdd (:76); nub off-center; L3/R3 press bit (:108,:145) and analog State values (:124-125,:147-148) stale.
- Evidence: PadOverlayStick.kt:136-151 is the full release path (unlock, restore ring offset :141-142, centreNub :143, clear press bit :145, recenter 127 :147-148).
- Impact: Permanent regression of the exact feature being preserved.
- Required planner change: Specify exact mechanism — synthetic ACTION_CANCEL driven through `stick.onTouch(event, pointerIndex, state)` returning -1 (which also clears `floatingSticks[i]` per PadOverlay.kt:368 pattern), or explicit release replicating :136-151 field-by-field. No discretion.

### [MAJOR][PLAN_GAP] Fade-system arbitration under menu mode still incomplete
- Location: plan:105 ("cancel pending fade runnable" once at entry); PadOverlay.kt:305-309, :482-486, :488-492
- Problem: Every non-editing touch still re-arms `resetFadeTimer()` (:305-309). After `fadeTimeout` idle, `fadeOutOverlay()` animates alpha **from explicit start 1f** to 0f (:485) — the dimmed 0.35 overlay jumps bright then hides mid-menu-mode. Next touch runs `fadeInOverlay()` 0f→1f (:491), restoring full brightness while menu mode ON. No `isOverlayVisible` reconciliation on entry/exit specified.
- Evidence: PadOverlay.kt:305-309, :485, :491; plan:105 has no per-event guard.
- Impact: Violates the ~35% dim acceptance criterion after 19 s idle or any auto-fade cycle; manual steps 2-3 fail.
- Required planner change: Skip resetFadeTimer/fadeOutOverlay/fadeInOverlay while `isMenuMode && !isEditing`; set `isOverlayVisible=true` (or equivalent) when dimming on entry; restore consistent state on exit.

### [MINOR][PLAN_GAP] Hardware-gamepad PS limitation still undocumented
- Location: plan:32, plan:176-182 (risks), manual step 7 (plan:159)
- Problem: No acknowledgment that hardware gamepad PS press won't enter Kotlin menu mode (hook lives only in PadOverlay touch path); PS glass button already opens the engine menu today (rpcsx-android.cpp:1695-1699).
- Required planner change: one-sentence scope note in Risks.

### [MINOR][PLAN_GAP] Logcat expectation still stated as guaranteed
- Location: plan:154 ("Expect engine log `input: opening home menu...`")
- Problem: Not marked best-effort; emission depends on log backend (pad_thread.cpp:671 `input_log.notice`). Visual criteria should be primary.
- Required planner change: mark "(best-effort)".

## Citation spot-check (all accurate, no drift)

PadOverlay.kt:301 setupTouchListener ✓ · :305-309 fade re-arm ✓ · :346-350 editables ✓ · :356-362 fixed sticks ✓ · :364-371 floating handling ✓ · :373-377 unconditional overlayPadData ✓ · :379-397 spawn ✓ · :474-492 fade system (:485 start=1f, :491 0→1) ✓. PadOverlayStick.kt:39 locked ✓ · :49 isActive ✓ · :68-78 onAdd displacement ✓ · :136-151 release path ✓. native-lib.cpp:31/:97/:211-214 binding, no null-guard ✓. RPCSXActivity.kt:41-44 oscToggle ✓. RPCSX.kt:92 external openHomeMenu, zero Kotlin callers ✓.

Change map scope unchanged (activity_rpcs3.xml, RPCSXActivity.kt, PadOverlay.kt, OverlayTouchPolicy+test, native-lib.cpp) — scope itself remains sound; only the listed gaps block.

## Next Agent: Planner
## Next Action: Actually apply the three MAJOR required changes from PASS 1 (unchanged wording above), fold in the two MINOR notes, bump header to PASS 2, and resubmit. Do not hand to @worker before then.
