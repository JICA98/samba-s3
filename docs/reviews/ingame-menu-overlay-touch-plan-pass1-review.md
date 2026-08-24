# Plan Review: ingame-menu-overlay-touch — PASS 1 ITER 1

## Verdict: REVISE
## Counts: CRITICAL 0 / MAJOR 3 / MINOR 2 / SUGGESTIONS 0

## Verified claims (all re-checked against repo; citations accurate)

- RPCSX.kt:92 openHomeMenu declared, never invoked from Kotlin (grep: single hit).
- RPCSXActivity.kt:41-44 oscToggle flips isInvisible; :197-206 sendGamepadData; hw input :108-195.
- activity_rpcs3.xml:11-14 GraphicsFrame bottom, :16-19 PadOverlay full-screen, :21-32 osc_toggle.
- PadOverlay.kt setupTouchListener :301-402; editables :346-350; fixed sticks :356-362; floating handling :364-371; unconditional overlayPadData :373-377; central spawn :379-397. Fade system :474-492 (resetFadeTimer/fadeOutOverlay/fadeInOverlay via ObjectAnimator alpha). PS button CELL_PAD_CTRL_PS :267-271.
- native-lib.cpp API struct :18-51 exhaustive, no mouse/touch entry point; openHomeMenu bound :31/:97/:211-214 with NO null guard; guard precedents :335/:341/:350.
- Submodule rpcsx/android/src/rpcsx-android.cpp: NullMouseHandler :1454-1458; virtual pad init :1606-1674 with PS registered :1649-1650; _rpcsx_overlayPadData ALSO calls padThread-open_home_menu on PS press (:1695-1699); _rpcsx_openHomeMenu :1865-1869.
- pad_thread.cpp:461-475 PS-edge detection opens home menu; open_home_menu :649-684 incl. OSK-busy early return :651-656; pad_thread.h:77 m_home_menu_open not exported to Kotlin.
- Home menu is pad-only: dpad/ls/cross navigate overlay_home_menu_page.cpp:147-166; circle exit :167-175; close(true,true) overlay_home_menu.cpp:105; pause_during_home_menu default false system_config.h:400. Only mouse match in Overlays is a settings hint string.
- patches/rpcsx-submodule-changes.patch:34-43 confirms auto-open was deliberately removed from surfaceEvent.
- Test infra real: testImplementation(libs.junit) app/build.gradle.kts:146; flavorDimensions distribution :60; existing JVM suites under app/src/test/**; both-flavor test tasks documented in AGENTS.md.
- GraphicsFrame.kt:8-45 plain SurfaceView, zero touch handling. OverlayEditActivity.kt:142 sets isEditing.

Scope decision confirmed sound: engine exposes no touch/mouse forwarding and the core .so is runtime-downloaded, so Kotlin-only menu-mode mitigation is the smallest correct change.

## Findings

### [MAJOR][CODE_DEFECT] Internal contradiction: fixed L3/R3 sticks gated despite design saying they stay live
- Location: plan line 29 vs step 3b (line 106) which gates lines 356-371; PadOverlay.kt:356-362
- Problem: step 3b wraps BOTH the fixed-stick block (:356-362) and floating-stick block (:364-371) with the same policy predicate, but design section (line 29) and reviewer question 4 state fixed L3/R3 stay live for menu navigation. Both policy functions have identical bodies, so applying either at :356-362 kills fixed sticks during menu mode. Worker must guess intent.
- Evidence: PadOverlay.kt:356-362; plan line 29 vs line 106; ls navigation supported by menu (overlay_home_menu_page.cpp:149-150).
- Impact: dead L3/R3 in menu mode contradicts stated UX and freezes any mid-drag fixed stick.
- Required planner change: step 3b must gate ONLY floating-stick handling (:364-371) and spawn block (:379-397). Fixed-stick block stays ungated. Add acceptance criterion: fixed L3/R3 respond during menu mode.

### [MAJOR][PLAN_GAP] Floating-stick release semantics under-specified because floating sticks share instances with fixed sticks
- Location: plan step 3a (floatingSticks[i] = null + recentre, worker to confirm); PadOverlay.kt:389-392; PadOverlayStick.kt:39,49,68-78,136-151
- Problem: floatingSticks[stickIndex] holds the SAME leftStick/rightStick instance as sticks[]. Naive null assignment leaves locked != -1 (isActive true), ringBounds displaced by onAdd (:76), nub off-center, stale State values. After exiting menu mode the spawn check !sticks[stickIndex].isActive() (PadOverlay.kt:391) fails forever for that side, permanently blocking respawn; draw() renders a misplaced ring.
- Impact: permanent regression of exactly the feature being preserved; impl-reviewer would classify as CODE_DEFECT post-hoc.
- Required planner change: specify release semantics explicitly: drive synthetic MotionEvent.ACTION_CANCEL through stick.onTouch (full reset path PadOverlayStick.kt:136-151 unlocks, restores ring offset, centres nub, clears press bit, recentres State) OR add explicit release(padState) replicating that path. Do not leave this to worker discretion.

### [MAJOR][PLAN_GAP] Menu-mode dim conflicts with the always-rearming auto-fade system
- Location: plan step 3a (cancel pending fade runnable once); PadOverlay.kt:305-309, :475-480, :482-486, :488-492
- Problem: every non-editing touch re-arms resetFadeTimer (:305-309), including menu-mode dpad taps. After fadeTimeout (19 s) idle, fadeOutOverlay animates alpha from explicit start value 1f down to 0f (:485): the dimmed 0.35 overlay jumps to full brightness then hides while menu mode is still ON. The next touch triggers fadeInOverlay 0-to-1 (:491), restoring full brightness mid-menu-mode. Also entering menu mode while isOverlayVisible == false leaves the flag inconsistent on exit.
- Evidence: PadOverlay.kt:305-309, :485, :491.
- Impact: directly violates acceptance criterion of ~35 percent dim while menu is up; manual steps 2-3 fail after 19 s idle or after an auto-fade cycle.
- Required planner change: define fade-system behavior under isMenuMode, e.g. skip resetFadeTimer/fadeOutOverlay/fadeInOverlay while menu mode active, reconcile isOverlayVisible on entry/exit (or make fade target MENU_DIM_ALPHA instead of 0f in menu mode).

### [MINOR][PLAN_GAP] Hardware gamepad PS press will not enter menu mode
- Location: plan step 3c hook lives only in PadOverlay touch listener; hardware PS path is RPCSXActivity.onKeyDown/onKeyUp (:108-136)
- Problem: acceptable asymmetry but undocumented; also worth noting the PS glass button already opens the menu today via the direct call in _rpcsx_overlayPadData (rpcsx-android.cpp:1695-1699), so the hook only adds mode-entry feedback.
- Required planner change: one sentence acknowledging scope.

### [MINOR][PLAN_GAP] Manual test log expectation may not match logcat format
- Location: plan Testing Strategy step 2 expects logcat text: input: opening home menu...
- Problem: engine emits input_log.notice(opening home menu...) at pad_thread.cpp:671; whether that reaches logcat verbatim depends on the log backend / LogMonitor capture path.
- Required planner change: mark the log line as best-effort; keep visual criteria primary.

## Next Agent: Planner
## Next Action: revise docs/plans/ingame-menu-overlay-touch-plan.md per the three MAJOR required changes (gate set for step 3b, explicit floating-stick release semantics, fade-vs-dim arbitration), fold in the two MINOR notes; then resubmit for PASS 2 review.
