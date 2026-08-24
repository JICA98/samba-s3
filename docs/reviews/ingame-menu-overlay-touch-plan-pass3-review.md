# Plan Review: ingame-menu-overlay-touch — PASS 3 ITER 1

## Verdict: APPROVE
## Counts: CRITICAL 0 / MAJOR 0 / MINOR 2 / SUGGESTIONS 0

## PASS-2 required changes — all applied and verified

| # | Prior requirement | Status | Evidence |
|---|---|---|---|
| 1 | Header PASS 2 marker + revision actually applied | ✅ | plan:4 `PASS 2 · ITERATION 1`; file substantively rewritten (prior pass found byte-identical resubmission) |
| 2a | Gate ONLY :364-371 + :379-397; leave fixed-stick loop :356-362 ungated | ✅ | plan:114 ("MUST REMAIN UNGATED"), change map plan:140; line ranges match PadOverlay.kt exactly |
| 2b | Differentiate policy functions | ✅ | plan:98-99 `shouldHandleFloatingSticks` / `shouldSpawnFloatingStick`, distinct symbols per plan:102 rationale |
| 2c | Fixed L3/R3 acceptance criterion | ✅ | plan:176 |
| 3 | Normative floating-stick release, zero discretion | ✅ | plan:105-108 synthetic ACTION_CANCEL → `onTouch(cancel, 0, state)` + `recycle()`; "confirm the cleanest reset" phrase gone; "ONE approach, no implementer discretion" explicit |
| 4 | Four-step fade arbitration | ✅ | plan:109-113 (skip :305-309 re-arm; guard :482-491 bodies; entry record+dim 0.35; exit restore+resetFadeTimer) |
| 5 | Hardware-gamepad PS limitation (MINOR) | ✅ | plan:192 Risk 8 incl. rpcsx-android.cpp direct-call note |
| 6 | Logcat demoted to best-effort (MINOR) | ✅ | plan:162 snapshot-primary |

## Verification performed this pass

**Synthetic CANCEL correctness (read PadOverlayStick.kt in full):**
- `MotionEvent.obtain(downTime, uptimeMillis, ACTION_CANCEL, 0f, 0f, 0)` — valid 6-arg overload, pointerCount=1.
- Cancel branch `PadOverlayStick.kt:136-137`: first block (:83-84) skipped for CANCEL; at :137 `locked != -1 && (action == ACTION_CANCEL || …)` short-circuits **before** `getPointerId`, so index-0 delivery is safe and bypasses pointer identity correctly.
- Reset coverage confirmed field-by-field: `locked=-1` (:138), ring offset restored + offsets zeroed (:141-142), `centreNub()` (:143), press bit cleared `and pressBit.inv()` (:145 — no-op-safe since floating sticks have pressBit=0), analog axes →127 per side (:147-148). `alpha` never mutated anywhere in the touch path (set only at init, PadOverlay.kt:237/239/244/249) — plan's invariant claim holds. Stale `pressX/pressY` are overwritten before next use (:91-92). Return -1 ignored by caller, which then nulls `floatingSticks[i]` itself — consistent with PadOverlay.kt:368 pattern.

**Citation spot-check (all accurate):**
PadOverlay.kt :301/:305-309/:318-341/:346-350/:356-362/:364-371/:373-377/:379-397/:474-492 ✓ · native-lib.cpp openHomeMenu wrapper :211-214 unguarded ✓, guard precedent :335/:341/:350 ✓ · RPCSX.kt:84/:92 declarations, grep confirms zero Kotlin callers today ✓ · activity_rpcs3.xml GraphicsFrame/PadOverlay/osc_toggle z-order ✓ · RPCSXActivity.kt oscToggle :41-44, hardware input :108+, sendGamepadData :197-206 ✓ · OverlayEditActivity.kt:142 `isEditing = true` ✓ · submodule pad_thread.cpp PS-edge→`open_home_menu()` ✓ · virtual pad PS registration + `_rpcsx_overlayPadData` direct call (rpcsx-android.cpp ~:1649/:1693-1699) ✓ · NullMouseHandler ✓ · Emu/system_config.h:400 `pause_during_home_menu{…false}` ✓ · test-suite convention dir exists (`app/src/test/java/com/zenithblue/sambas3/utils/`) ✓.

**Gating completeness (reviewer question #3):** during menu mode the only touch-driven controller-state mutators left ungated are editables (intended: dpad/face drive the menu) and the fixed-stick loop (intended: L3/R3 live). Floating blocks fully gated; spawn check :391 unreachable when gated. No other mutation site exists.

**Threading/lifecycle (question #5):** `setMenuMode` only from click listeners / overlay listener — all main-thread; fade Handler already uses `Looper.getMainLooper()`; obtain/recycle paired within one stack frame (plan:106-107); ordering between float-release and record-visible steps is conflict-free (disjoint state domains, synchronous).

## Findings

### [MINOR][CODE_DEFECT] plan:108 overstates what :141-142 restores for onAdd-spawned floats
- Location: plan step 3a vs PadOverlay.kt:68-78,93-94,141-142
- Problem: Floating sticks spawn via `onAdd` (:393), which moves the ring **without** setting `bgOffsetX/Y` (those are only set by the onTouch DOWN lock path :93-94, which floating sticks never traverse). So on synthetic CANCEL, `ringBounds.offset(0,0)` leaves the ring at its dragged position — not "returned to base position" as plan claims.
- Impact: None functionally — `floatingSticks[i]` is nulled immediately (not drawn, :412) and the next spawn repositions via `onAdd`. Worker should not "fix" the discrepancy; implement exactly as planned.
- Required change: none blocking; optionally reword to "offsets re-applied (no-op for onAdd-spawned floats)".

### [MINOR][PLAN_GAP] Acceptance-criterion wording vs unconditional overlayPadData
- Location: plan:173 ("no stick/analog state is emitted") vs PadOverlay.kt:373-377 and plan:190 (Risk 6)
- Problem: `overlayPadData` still fires on every menu-mode tap (with unchanged values). Literal reading of "not emitted" contradicts Risk 6.
- Impact: None — parenthetical "(state stays centered)" plus Risk 6 define the objective test; tester will compare values, not call occurrence.

No CRITICAL or MAJOR defects. No regressions introduced by the revision.

## Next Agent: Worker
## Next Action: Implement plan as written (PASS 2 text approved). Follow step order 1→7; keep fixed-stick loop ungated; do not add a parallel stick-release API; treat the two MINOR notes as informational only.
