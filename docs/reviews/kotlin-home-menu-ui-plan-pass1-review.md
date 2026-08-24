# Plan Review: kotlin-home-menu-ui-plan — PASS 1 ITER 1

## Verdict: REVISE

## Counts: CRITICAL 0 · MAJOR 2 · MINOR 4 · SUGGESTIONS 1

## Verification Summary (all citations re-checked against source)

| Plan claim | Source check | Result |
|---|---|---|
| Item order Resume→Settings→[Friends]→[Trophies]→Screenshot→Recording→SaveState→Restart→Exit | `overlay_home_menu_main_menu.cpp:30-151`; `add_page` adds real list entry (`overlay_home_menu_page.cpp:69-82`) | CONFIRMED |
| Initial selection −1 → forced 0 on first add_entry | `overlay_list_view.hpp:19` (`m_selected_entry = -1`), `.cpp:185-186` | CONFIRMED |
| Clamp-not-wrap | `overlay_list_view.cpp:150` `std::max(0, std::min(entry, max_entry))`; `select_previous(count)` :164-167 | CONFIRMED — UP×itemCount lands on 0 |
| use_separators=false ⇒ element count == item count | `overlay_home_menu.cpp:16` (5th arg `false`); `get_selected_entry()` hpp math | CONFIRMED |
| Circle on root exits; parent→back | `overlay_home_menu_page.cpp:167-176` | CONFIRMED |
| Exit Game = direct `GracefulShutdown(false,true)`, NO confirm | `overlay_home_menu_main_menu.cpp:146-150` | CONFIRMED |
| 1 ms poll; auto-repeat ≥500 ms; edge-detect; init all-pressed | `overlays.cpp` `thread_ctrl::wait_for(1000)`, `ms_threshold = 500` (:57), `last_state` flip (:92-99), state.fill(true) (:64-69); dpad_up/down in auto-repeat set (`overlays.h:66-78`) | CONFIRMED — 120 ms hold fires exactly once |
| Enter-button swap | `system_config.h:343` default cross; swap `overlays.cpp:198-199` region | CONFIRMED (R4 documented) |
| pause_during_home_menu=false | `system_config.h:400` | CONFIRMED |
| NullMouseHandler (no touch path) | `rpcsx-android.cpp:1454-1458` | CONFIRMED |
| `_rpcsx_overlayPadData` absolute semantics + PS special-case | `rpcsx-android.cpp:1676-1713`, PS branch calls `open_home_menu()` | CONFIRMED |
| JNI bindings | `RPCSX.kt:84,92`; `native-lib.cpp` dlsym + guarded bindings | CONFIRMED |
| PS-edge & double-open guard | `pad_thread.cpp:472-477`, `:660` exchange guard | CONFIRMED |
| `Activity` base (Compose decision) | `RPCSXActivity.kt:24` `class RPCSXActivity : Activity()` | CONFIRMED — custom View is smallest correct change |
| Layout stack / Z-order insertion point | `activity_rpcs3.xml:11-45` (GraphicsFrame→PadOverlay→toggles) | CONFIRMED feasible; insert between PadOverlay and toggles keeps menu_toggle tappable |
| PadOverlay single push site | Only one `overlayPadData` call in PadOverlay (`PadOverlay.kt:388-392`) | CONFIRMED — gate covers all PadOverlay sites |
| v1 menu mode / policy / dim / fade suppression | `PadOverlay.kt:367-369,379,394,498-508,511-542`; `OverlayTouchPolicy.kt:4-9` | CONFIRMED |
| Hardware-pad writer documented (not silent) | `RPCSXActivity.kt:216-225` `sendGamepadData` | CONFIRMED — covered by R7 |
| Threading precedent | overlayPadData already called from UI thread (`PadOverlay.kt:388` touch listener, `RPCSXActivity.kt:217` key/motion handlers) | CONFIRMED — main-looper Handler chain safe; no sleeping on UI thread |
| Palette constants | `GlassButtonRenderer.kt:33-36` match plan values | CONFIRMED |
| Test infrastructure JVM/JUnit4 | `app/src/test/java/com/zenithblue/sambas3/overlay/OverlayTouchPolicyTest.kt` exists | CONFIRMED |
| Smallest-change discipline | Change map = 3 new Kotlin + 2 modified Kotlin + layout + tests; no C++/Gradle changes | CONFIRMED |

## Findings

### [MAJOR] Toggle-OFF injects a single CIRCLE — does not close engine menu when trackedDepth ≥ 1
- Location: plan Step 5 (lines ~226-229) vs plan's own trackedDepth model (Step 6, 6b)
- Problem: Plan instructs toggle-OFF to "inject single CIRCLE step (closes engine menu)". Per engine, CIRCLE on a sub-page navigates to the parent (`overlay_home_menu_page.cpp:170-174`); only CIRCLE at root exits (`:175`). After tapping Settings/SaveState (trackedDepth=1) and collapsing to ☰, toggle-OFF would send ONE circle → engine lands on root page, menu still open, while the plan simultaneously hides the Kotlin UI and clears the dim → broken visual/input state (engine menu over undimmed game).
- Evidence: `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_page.cpp:167-176`
- Impact: Reachable core flow leaves engine menu orphaned; contradicts the plan's own depth tracking; not covered by any manual scenario (S1-S8 never toggle-off from a submenu).
- Required planner change: Step 5 toggle-OFF must inject `(trackedDepth + 1)` sequential CIRCLE steps (same 120/150 ms envelope) when tracked-engine-open, before hiding UI/setMenuMode(false). Add manual scenario: open Settings → ☰ → toggle OFF → engine menu fully closed, game interactive.

### [MAJOR] Touch consumption of HomeMenuView not specified — scrim taps can fall through to PadOverlay
- Location: plan Step 4 (~lines 206-219); AC8 (~line 344)
- Problem: HomeMenuView will be a sibling View above PadOverlay in ConstraintLayout. Android dispatches to next-lower sibling when `onTouchEvent` returns false, so a tap on the scrim (non-row area) would reach PadOverlay if the view doesn't return true. In menu mode the policy suppresses floating sticks, but FIXED face buttons remain live (AC8), so a scrim tap landing on the on-screen CROSS would fire the engine-selected row (index 0 = Resume → closes everything unexpectedly; worse, arbitrary row after drift).
- Evidence: `activity_rpcs3.xml:16-19` (full-screen siblings); `PadOverlay.kt:357-361` (editables/sticks still process in menu mode); AC8 keeps fixed buttons live
- Impact: Modal UI leaks touches; unpredictable engine actions from taps intended as dismissal/no-op.
- Required planner change: Specify in Step 4: expanded mode consumes ALL touches within view bounds (return true for every ACTION_DOWN/MOVE/UP/CANCEL, including scrim); AC8's "fixed buttons remain live" clause applies to collapsed ☰ mode only (where the small ☰ intentionally lets pad controls stay reachable). Tighten AC8 wording accordingly.

### [MINOR] XML tag must be fully-qualified custom view
- Location: plan Affected Components (~line 161) `<HomeMenuView>`
- Evidence: `activity_rpcs3.xml:16` uses FQN `com.zenithblue.sambas3.overlay.PadOverlay`
- Required change: write `<com.zenithblue.sambas3.overlay.HomeMenuView>` (or note FQN requirement).

### [MINOR] Injector JVM-testability seam ambiguous
- Location: plan Step 2 vs Testing Strategy item 3
- Problem: `Handler(Looper.getMainLooper())` is unavailable in JVM unit tests; plan promises "fake clock/callback recorder" tests but gives no seam (e.g., injectable scheduler/clock interface or pure sequencer separated from executor).
- Required change: one sentence specifying the abstraction (constructor-injected `postDelayed(delay, block)` lambda or similar) so @worker doesn't improvise Robolectric.

### [MINOR] Native engine-menu closure while Kotlin list visible leaves stale UI (extends R5)
- Location: Risks R5/R6
- Problem: Engine menu can close natively (on-screen ○ in menu mode per AC8, or hardware gamepad) with no callback to Kotlin. Row taps then inject Release+UP×8+CROSS into the live game. R5 covers only the toggle-off CIRCLE case.
- Evidence: no engine→Kotlin notification path exists (grep native-lib.cpp: only overlayPadData/openHomeMenu inbound)
- Required change: extend R5/R6 text to name this case + recovery (toggle cycle / PS-glass re-open).

### [MINOR] Activity recreation while menu open drops menu mode/UI state
- Location: plan Step 5 lifecycle handling
- Evidence: `AndroidManifest.xml:39-43` (no configChanges); `RPCSXActivity.kt:46` menuModeOn local var (pre-existing v1 pattern)
- Required change: note as accepted limitation or save menu-mode flag in onSaveInstanceState; injector.onDestroy-cancel already planned.

### [SUGGESTION] Future JNI `isHomeMenuOpen()` query would eliminate the R5/R6/stale-open class entirely; worth one line in "future work".

## Next Agent: Planner
## Next Action: Apply the two MAJOR required changes (depth-aware toggle-OFF circles; explicit touch-consumption spec + AC8 wording) plus the four minor edits to docs/plans/kotlin-home-menu-ui-plan.md, then resubmit for PASS 2 review. Do NOT start @worker yet.
