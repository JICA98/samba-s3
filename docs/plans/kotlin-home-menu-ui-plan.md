# Plan: Kotlin Touch-Driven Home Menu UI (`kotlin-home-menu-ui-plan.md`)

PASS 2 · ITERATION 1 · Author: @planner · Date: 2026-08-23
PASS 2 revision applied per docs/reviews/kotlin-home-menu-ui-plan-pass1-review.md
(2 MAJOR: depth-aware close sequence + touch-consumption spec; 4 MINOR: FQN XML tag,
injector scheduler seam, stale-list risk extension, activity-recreation limitation).

---

## Task Summary

The engine's Home Menu renders correctly over the dimmed PadOverlay (v1), but it is
pad-only: the engine core has **no touch/mouse input path** (`NullMouseHandler`,
`app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp:1454-1458`) and the menu is driven
exclusively by pad-button edges. The user cannot touch the menu items.

Goal: build a **complete Kotlin custom-View Home Menu UI** (`HomeMenuView`) that visually
replaces the engine's list on screen, accepts direct touch input, and drives the engine's
real menu through **synthetic pad input** (`RPCSX.instance.overlayPadData(...)`) using a
new `PadInputInjector` with strict press→release sequencing. Includes a collapsed-mode
floating button to bring the list back after engine submenus open, a Kotlin-side Exit-Game
confirm dialog, and a clamp-to-top selection-sync strategy that removes dependence on
previously tracked engine state.

UI-framework decision (documented): **classic custom `View` + Canvas**, NOT Compose.
Evidence: `RPCSXActivity : Activity()` (RPCSXActivity.kt:24) — plain `Activity`, not
`ComponentActivity`, so ComposeView would require manual
lifecycle/savedStateRegistry owners; and the codebase precedent for in-emulator overlays
is Canvas drawing (`PadOverlay : SurfaceView`, PadOverlay.kt:69;
`GlassButtonRenderer`, GlassButtonRenderer.kt:30). A Canvas View matches the established
glassmorphic renderer style and adds no new dependencies.

---

## Research Sources

1. **Engine: main-menu construction, item order, per-item effects**
   `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_main_menu.cpp`
   - Item order: Resume (:30-38) → **Settings page** (:40) → **[conditional] Friends**
     only if `rpcn_configured()` (:42-69) → **[conditional] Trophies** only if a trophy
     name exists (:71-96) → Screenshot (:98-107) → Recording (:109-118) → **SaveState
     page** (:120) → Restart (:122-137) → Exit Game (:139-151).
   - Effects on CROSS: Resume → `page_navigation::exit` (:37); Settings → submenu
     `next` via add_page (:40); Trophies → opens separate `trophy_list_dialog` overlay,
     returns `stay`, home menu remains (:87-94); Screenshot/Recording → set global flag +
     `exit` (:105, :116); Restart → `Emu.Restart` + `exit` (:130-136);
     **Exit Game → `Emu.GracefulShutdown(false, true)` immediately — NO confirmation
     page in this fork** (:146-149).
   - On the tested device (8 items visible): Friends absent (RPCN not configured),
     Trophies present → order is exactly: Resume, Settings, Trophies, Take Screenshot,
     Start/Stop Recording, SaveState, Restart Game, Exit Game.

2. **Engine: navigation, initial selection, clamp-vs-wrap, enter/back mapping**
   - `overlay_home_menu_page.cpp:127-247` — message box takes priority (:129-138);
     dpad_up/dpad_down → select_previous/select_next (:212-223); cross invokes callback
     of selected index (:151-166); **circle on root page → `page_navigation::exit`
     (closes menu)** (:167-176); L1/R1 jump ±10 (:224-233).
   - Initial selection = **index 0 ("Resume")**: `m_selected_entry = -1`
     (`overlay_list_view.hpp:20`) then forced to 0 on first `add_entry`
     (`overlay_list_view.cpp:185-186`).
   - **Clamp, not wrap**: `select_entry` uses
     `m_selected_entry = std::max(0, std::min(entry, max_entry))`
     (`overlay_list_view.cpp:150`). Therefore sending **UP × itemCount guarantees
     selection lands at index 0** — basis of the sync strategy.
   - Main menu created with `use_separators=false` (`overlay_home_menu.cpp:17`), so
     element count == visible item count (8).
   - Enter/Back: default `enter_button_assignment = cross`
     (`system_config.h:343`); engine swaps cross/circle semantics only if user sets
     circle-assign (`overlays.cpp:198-199`). Plan assumes default; residual risk R4.
   - `pause_during_home_menu` default = **false** (`system_config.h:400`) — game keeps
     running behind the menu; home-menu dialog allows input while paused anyway
     (`m_allow_input_on_pause = true`, `overlay_home_menu.cpp:19`).
   - Settings page = 7 sub-pages (Audio/Video/Input/Advanced/Overlays/PerfOverlay/Debug),
     all pad-navigable lists (`overlay_home_menu_settings.cpp:10-18`); save/discard
     confirmations use the engine message box (`overlay_home_menu_page.cpp:117-125`),
     NOT used by Exit Game.

3. **Engine: input edge detection & timing envelope**
   - Overlay input thread polls pads every **1 ms** (`thread_ctrl::wait_for(1000)`,
     `overlays.cpp:143`); a button press fires `on_button_pressed` only on a
     **press edge** (`last_state` flip, `overlays.cpp:92-99`).
   - Auto-repeat only after holding ≥ **500 ms** (`ms_threshold`, `overlays.cpp:57`,
     applied at :102) for auto-repeat buttons incl. dpad_up/down (`overlays.h:66-78`).
   - ⇒ Injection envelope: press **~120 ms** (< 500 ms ⇒ exactly one event) +
     gap **~150 ms** (> poll period by 150×) between steps is safe.
   - `last_button_state` initialised as ALL-PRESSED when a dialog opens
     (`overlays.cpp:64-69`): the first thing an injector does (release phase) is also
     what arms edge detection. Our sequence starts with a release — compatible.

4. **Android bridge: overlayPadData semantics & thread-safety precedent**
   - `_rpcsx_overlayPadData` sets ABSOLUTE button/stick state from the passed bitmasks
     (`rpcsx-android.cpp:1676-1713`); PS-bit special-case calls `open_home_menu()`
     (:1695-1699). Guarded JNI binding: `native-lib.cpp:158-162, 210-218`;
     declaration `RPCSX.kt:84`.
   - Called today from the **main thread** (touch listener `PadOverlay.kt:388-392`;
     key handler `RPCSXActivity.kt:216-225`). Writes happen outside the
     `g_virtual_pad_mutex` lock but this is the established, working mechanism;
     injector will use the same call site/thread. `openHomeMenu()` binding:
     `native-lib.cpp:97, 211-218`; engine guard prevents double-open
     (`pad_thread.cpp:660` `if (m_home_menu_open.exchange(true)) return;`).
   - PS-edge auto-open: `pad_thread.cpp:472-477`
     (`(ps_button_pressed && !m_ps_button_pressed) || g_home_menu_requested` →
     `open_home_menu()`).

5. **Web: upstream RPCS3 direction & comparable frontends**
   - Upstream RPCS3 v0.0.41 changelog: *"native overlay: Home menu rewrite"* (PR #18358),
     sidebar/tabbed settings rework, dropdown/slider widgets —
     https://www.sourceforge.net/projects/rpcs3.mirror/files/v0.0.41 and
     http://www.emucr.com/2026/03/rpcs3-git-20260317.html. Upstream is diverging toward
     richer native widgets (desktop, mouse-capable); our Android fork keeps the list
     menu with `NullMouseHandler` ⇒ **a Kotlin-side touch layer driving pad input is the
     pragmatic approach** rather than porting upstream mouse code into the engine.
   - PCGamingWiki RPCS3 page documents desktop-only *"Mouse input in menus"* support
     (https://www.pcgamingwiki.com/wiki/RPCS3) — not available in the Android fork
     (NullMouseHandler confirmed above).
   - PPSSPP Android ships its own touch-driven menus/on-screen controls implemented in
     the frontend layer (`UI/GamepadEmu.cpp` referenced in
     https://github.com/hrydgard/ppsspp/issues/18801; controls docs
     https://www.ppsspp.org/docs/settings/controls) — precedent for frontend-owned
     touch UIs sitting above an emulator core.

---

## Current Architecture (v1 state — verified)

| Concern | Where |
|---|---|
| Activity base class | `class RPCSXActivity : Activity()` — RPCSXActivity.kt:24 (plain Activity, **no Compose**) |
| Layout stack | GraphicsFrame → PadOverlay → osc_toggle → menu_toggle (`activity_rpcs3.xml:11-45`) |
| Menu toggle button | ON: `openHomeMenu()` + `setMenuMode(true)` + icon swap; OFF: `setMenuMode(false)` only (RPCSXActivity.kt:46-56) |
| PS-glass edge hook | `onMenuRequestedFromPad` enters menu mode WITHOUT calling openHomeMenu (engine opens natively) (RPCSXActivity.kt:57-63; detection PadOverlay.kt:367-369) |
| Menu-mode dim | `setMenuMode(on)` fades overlay alpha to `MENU_DIM_ALPHA = 0.35f`, cancels active floating sticks w/ synthetic ACTION_CANCEL (PadOverlay.kt:511-542; OverlayTouchPolicy.kt:4) |
| Floating-stick suppression in menu mode | `shouldHandleFloatingSticks/shouldSpawnFloatingStick` (OverlayTouchPolicy.kt:8-9; usage PadOverlay.kt:379, 394) |
| Pad push | Every overlay touch pushes absolute state via `overlayPadData` (PadOverlay.kt:388-392); hardware pad pushes via `sendGamepadData` (RPCSXActivity.kt:216-225) |
| Fade arbitration | 19 s fade timeout suppressed in menu mode (PadOverlay.kt:498-508) |
| Existing tests | `app/src/test/java/com/zenithblue/sambas3/overlay/OverlayTouchPolicyTest.kt` (JVM, JUnit4) |

Root constraint re-verified this pass: engine has NO pointer input path —
`init_mouse_handler → NullMouseHandler` (rpcsx-android.cpp:1454-1458); no pointer JNI in
native-lib.cpp (grep: only `overlayPadData` :158 and `openHomeMenu` :211 bindings).

---

## Affected Components & Dependencies

**New (Kotlin, main sourceSet):**
- `overlay/HomeMenuView.kt` — custom `View`: glassmorphic panel + item rows + X close,
  per-row touch hit-testing, selection highlight, collapsed floating ☰ button mode.
- `overlay/HomeMenuModel.kt` — pure-JVM data: `HomeMenuItem(id, label, engineIndex,
  action)` + ordered list constant (8 items) + action enum. Kept Android-free for tests.
- `overlay/PadInputInjector.kt` — sequencer: builds a step plan (pure logic, testable)
  and executes it on `Handler(Looper.getMainLooper())` postDelayed chain calling
  `RPCSX.instance.overlayPadData(...)`; exposes `isInjecting` (volatile) and completion /
  cancel callbacks; guaranteed final "all-release" frame.
- Tests: `PadInputInjectorTest.kt`, `HomeMenuModelTest.kt` (selection math), extended
  `OverlayTouchPolicyTest.kt`.

**Modified:**
- `OverlayTouchPolicy.kt` — add injection-suppression predicates.
- `PadOverlay.kt` — consume-but-ignore touches while `PadInputInjector.isInjecting`
  (skip its own `overlayPadData` push); no other behavior change.
- `RPCSXActivity.kt` — instantiate/show/hide HomeMenuView; wire menu_toggle, PS-edge
  hook, injector callbacks; exit-confirm dialog; lifecycle cleanup in onDestroy.
- `activity_rpcs3.xml` — add `<com.zenithblue.sambas3.overlay.HomeMenuView>` (FQN,
  mirroring the PadOverlay tag precedent) after PadOverlay (above it in Z-order),
  initially `android:visibility="gone"`.

**Dependencies:** none new (android.graphics / android.os.Handler / android.view only —
already exercised by PadOverlay). Unit tests use JUnit4 like existing ones.

---

## Implementation Steps (ordered, smallest correct change)

### Step 1 — `HomeMenuModel.kt` + selection math (pure JVM)
- `enum class HomeMenuAction { RESUME, OPEN_SUBMENU, LAUNCH_OVERLAY_AND_STAY, EXIT_APP }`
  plus per-item `PadAction` mapping.
- Item list constant mirroring engine order for the observed 8-item configuration:
  Resume(idx 0, RESUME), Settings(1, OPEN_SUBMENU), Trophies(2, LAUNCH_OVERLAY_AND_STAY),
  Take Screenshot(3, RESUME), Start/Stop Recording(4, RESUME), SaveState(5, OPEN_SUBMENU),
  Restart Game(6, RESUME), Exit Game(7, EXIT_APP-with-Kotlin-confirm).
- Pure function `buildNavigationPlan(targetIndex: Int, itemCount: Int, confirm: Boolean):
  List<PadStep>` where `PadStep = Press(d1,d2) | Release | Wait(ms)`:
  1. `Release` (arm edge detection, overlays.cpp:64-69),
  2. `UP × itemCount` (clamps selection to 0 — overlay_list_view.cpp:150),
  3. `DOWN × targetIndex`,
  4. terminal `CROSS` (or `CIRCLE` for close actions).

### Step 2 — `PadInputInjector.kt`
- Executes a plan: for each step schedule on main-looper `Handler` via postDelayed;
  PRESS holds `PRESS_MS = 120L`, inter-step gap `GAP_MS = 150L` (envelope justified by
  overlays.cpp:57/143).
- **Testability seam (JVM-safe):** the scheduler is constructor-injected as
  `postDelayed: (Runnable, Long) -> Unit` with a default implementation
  `{ r, delayMs -> Handler(Looper.getMainLooper()).postDelayed(r, delayMs) }`.
  JVM unit tests pass a fake queue-based scheduler (runnable list drained manually /
  virtual clock) so no Android Looper is needed and timing order is deterministic.
- Each frame pushes FULL absolute bitmask pair (d1,d2) + neutral sticks (127) — mirrors
  `_rpcsx_overlayPadData` absolute semantics (rpcsx-android.cpp:1691-1711).
- **Safety invariant:** regardless of cancellation/activity destroy/error, the LAST frame
  ever pushed is an all-released frame (`d1=d2=0`). Implemented via
  `cancel()` → removeCallbacks → push release; `onDestroy` calls `cancel()`.
- `@Volatile var isInjecting`; companion `isActive()` for PadOverlay/policy queries.
- Completion callback runs on main thread.

### Step 3 — Policy extension
- Add to `OverlayTouchPolicy`:
  - `fun shouldSuppressOverlayPadPush(injectInProgress: Boolean): Boolean =
     injectInProgress` (PadOverlay skips its own `overlayPadData` push when true).
  - `fun shouldAcceptOverlayTouch(isMenuMode: Boolean, injectInProgress: Boolean):
     Boolean = isMenuMode && !injectInProgress` (gate for editables/sticks processing;
     return early consuming the event otherwise).
- Keep existing functions untouched (back-compat with OverlayTouchPolicyTest).

### Step 4 — `HomeMenuView.kt` (render + hit-test)
- Full-width bottom sheet-style panel (glass style constants reused conceptually from
  GlassButtonRenderer.kt:33-39 palette: GLASS_BG 0x730F0F14, GLASS_BORDER 0x24FFFFFF,
  GLASS_ACTIVE highlight 0x4DFFFFFF, GLASS_LABEL 0xB3FFFFFF) + scrim covering rest of
  screen; drawn ABOVE PadOverlay (XML order).
- Rows ≥ 48dp tall, label left-aligned, chevron/glyph right; pressed-row visual state;
  selected-row highlight mirrors engine focus (informational only).
- Top-right ✕ close button (mirrors engine hint "PRESS ✕ TO CONTINUE"); footer hints
  "Enter / Back / Save / Discard" text for parity with engine bottom bar.
- Touch (expanded mode): `onTouchEvent` **MUST return `true` unconditionally** for every
  ACTION_DOWN / ACTION_MOVE / ACTION_UP / ACTION_CANCEL within the view's full-screen
  bounds — including scrim/outside-row taps (treated as no-op). Rationale: HomeMenuView
  is a full-screen sibling ABOVE PadOverlay (`activity_rpcs3.xml:16-19`); Android
  dispatches to the next-lower sibling when `onTouchEvent` returns false, and PadOverlay's
  FIXED face buttons remain live in menu mode (PadOverlay.kt:357-361) — an unconsumed
  scrim tap landing on the on-screen CROSS would fire the engine-selected row. Expanded
  mode is therefore strictly modal. No multi-touch complexity (single pointer).
- Touch dispatch: ACTION_DOWN hit-tests rows/✕; row hit → `onItemSelectedListener`,
  ✕ hit → `onCloseListener`, anything else → consumed no-op. In COLLAPSED ☰ mode only
  the small button is touch-active; all other touches fall through to PadOverlay
  intentionally so pad controls stay reachable.
- Collapsed mode: small circular ☰ button pinned top-right below menu_toggle; tapping it
  expands the list (and triggers the resync sequence described in Step 6b).
- All rendering via Canvas + Paint (no Drawables required).

### Step 5 — Activity wiring
- Inflate via XML id `homeMenuView`; initially gone.
- `showHomeMenuUi()` helper: `visibility=VISIBLE`, `menuToggle → ic_close`.
- menu_toggle ON → `RPCSX.instance.openHomeMenu()` (once, guarded by state check
  RPCSXActivity.kt:48-51) + `setMenuMode(true)` + show UI.
- menu_toggle OFF (and the ✕ button) is **DEPTH-AWARE**: if tracked-engine-open
  (`trackedDepth ≥ 0`), inject **`(trackedDepth + 1)` sequential CIRCLE presses** using
  the standard envelope (each press ~120 ms hold / ~150 ms gap) BEFORE hiding the Kotlin
  UI or calling `setMenuMode(false)`. Engine semantics: CIRCLE on a sub-page navigates to
  the parent page (overlay_home_menu_page.cpp:167-174); only CIRCLE at the root exits the
  menu (:175-176). Hence depth ≥ 1 requires one CIRCLE per level plus one to exit root.
  After the sequence completes: hide UI → `setMenuMode(false)` → reset `trackedDepth = 0`.
  Rationale (review MAJOR #1): a single CIRCLE from a sub-page only reaches the parent,
  orphaning the engine menu over an undimmed game.
- Tracked-open flag cleared by every dismissal action (Resume/X/etc.) so a stray CIRCLE
  is never sent into the running game after natural closure (residual risk R5).
- PS-edge hook (`onMenuRequestedFromPad`): additionally `showHomeMenuUi()` (engine opens
  itself via rpcsx-android.cpp:1695-1699).
- HomeMenuView callbacks → injector plans (Step 6 mapping).
- `onDestroy`: `injector.cancel()`.

### Step 6 — Action mapping (engine-verified)
| Kotlin row | Engine behavior (citation) | Injected sequence | Kotlin follow-up |
|---|---|---|---|
| Resume Game | exit menu (main_menu.cpp:31-38) | Release, UP×8, DOWN×0, CROSS | hide UI, `setMenuMode(false)`, clear tracked-open |
| Settings | submenu `next` (main_menu.cpp:40) | Release, UP×8, DOWN×1, CROSS | collapse to ☰, trackedDepth=1 |
| Trophies | opens trophy overlay, menu stays (main_menu.cpp:78-96) | Release, UP×8, DOWN×2, CROSS | collapse to ☰ (depth unchanged) |
| Take Screenshot | flag + exit (main_menu.cpp:98-107) | Release, UP×8, DOWN×3, CROSS | dismiss as Resume |
| Start/Stop Recording | flag + exit (main_menu.cpp:109-118) | Release, UP×8, DOWN×4, CROSS | dismiss as Resume |
| SaveState | submenu `next` (main_menu.cpp:120) | Release, UP×8, DOWN×5, CROSS | collapse to ☰, trackedDepth=1 |
| Restart Game | exit + `Emu.Restart` (main_menu.cpp:122-137) | Release, UP×8, DOWN×6, CROSS | dismiss as Resume |
| Exit Game | **immediate `GracefulShutdown`, no engine confirm** (main_menu.cpp:139-151) | (confirm first) Release, UP×8, DOWN×7, CROSS | Kotlin AlertDialog: **No → nothing sent**; **Yes → run sequence then dismiss** |
| ✕ / close (depth-aware, same as toggle-OFF) | circle on root = exit; sub-page = parent (page.cpp:167-176) | Release, CIRCLE × (trackedDepth + 1), 120/150 ms envelope per press | dismiss as Resume, reset trackedDepth=0 |

- **Exit-confirm honesty note:** verified — this fork shows NO exit confirmation page
  (the engine message box is only used for settings save/discard,
  overlay_home_menu_page.cpp:177-211). The Kotlin confirm dialog is therefore the only
  guard against accidental shutdown, and "Yes" simply performs the normal navigation +
  CROSS; "No" performs NOTHING (menu untouched, selection unchanged).
- **Trophies nuance:** trophy list takes over overlay input; when the user closes it
  (circle via overlay face buttons), home menu regains input with selection parked on
  the Trophies row. Harmless: every subsequent activation resyncs (Step 6b).

### Step 6b — Selection-sync strategy (replaces naive delta tracking)
- **Never trust a remembered engine selection.** Every activation first forces the
  engine selection to a known point: `UP × itemCount` exploits the CLAMP
  (overlay_list_view.cpp:150) to land on index 0, then walks DOWN to the target.
- Worst case cost: 8 UP + 7 DOWN + CROSS = 16 steps ≈ 16×270 ms ≈ 4.3 s on the largest
  menu; typical taps (top rows) ≈ 1–1.5 s. During injection rows are disabled and the
  pressed row shows a progress indication.
- Collapsed ☰ restore: if `trackedDepth == 1`, first send ONE CIRCLE (returns to parent
  page — page.cpp:170-174), then expand list. If `trackedDepth == 0`, just expand.
  Hardware-pad page changes while collapsed are a documented desync hole (R6) with
  recovery path: menu_toggle full off/on cycle (PS-glass re-open works anytime because
  engine re-create is guarded, pad_thread.cpp:660).

---

## File-Level Change Map

| File | Change | Rationale |
|---|---|---|
| `overlay/HomeMenuModel.kt` | NEW — items, actions, `buildNavigationPlan` | pure logic, JVM-testable |
| `overlay/PadInputInjector.kt` | NEW — sequencer + `isInjecting` + guaranteed-release cancel | bridges touch → pad safely |
| `overlay/HomeMenuView.kt` | NEW — glassmorphic touch list + ☰ collapsed mode | the requested UI |
| `overlay/OverlayTouchPolicy.kt` | ADD 2 predicate functions | central, testable policy |
| `overlay/PadOverlay.kt` | GATE touch handling + skip `overlayPadData` push while injecting (PadOverlay.kt:312-417 region) | prevent concurrent conflicting pad pushes |
| `RPCSXActivity.kt` | wire view, toggle, PS-hook, confirm dialog, onDestroy cancel (regions RPCSXActivity.kt:46-63, 104-110) | integration point |
| `res/layout/activity_rpcs3.xml` | ADD fully-qualified `<com.zenithblue.sambas3.overlay.HomeMenuView>` after PadOverlay, `visibility="gone"` (FQN mirrors PadOverlay precedent, activity_rpcs3.xml:16) | Z-order above PadOverlay |
| `app/src/test/.../OverlayTouchPolicyTest.kt` | EXTEND — new predicates | unit coverage |
| `app/src/test/.../PadInputInjectorTest.kt` | NEW — plan shape, timing constants, cancel-releases-last invariant | unit coverage |
| `app/src/test/.../HomeMenuModelTest.kt` | NEW — clamp-to-top math, per-row sequences, exit-confirm gating | unit coverage |

No engine (C++) files are modified. No Gradle/deps changes.

---

## Testing Strategy

**JVM unit tests** — `./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest`:
1. `buildNavigationPlan(3, 8, …)` = [Release, UP×8, DOWN×3, CROSS] with PRESS=120/GAP=150
   constants asserted (< 500 ms auto-repeat threshold).
2. Clamp math: targetIndex 0..7 each yields correct DOWN count; itemCount reflects model.
3. Injector: uses the injected `postDelayed: (Runnable, Long) -> Unit` seam with a fake
   queue-based scheduler (no Android Looper in JVM tests); callback recorder proves
   interleaved press/release frames, no two consecutive presses without a release, and
   `cancel()` mid-plan still emits a final all-zero frame (stuck-button prevention).
4. Depth-aware close plan: `buildClosePlan(trackedDepth)` yields exactly
   `(trackedDepth + 1)` CIRCLE press/release pairs.
5. Policy: `shouldAcceptOverlayTouch(true,false)=true`, `(true,true)=false`,
   `shouldSuppressOverlayPadPush(false)=false/(true)=true`.
6. Exit-app gating: confirm=false produces empty plan; confirm=true produces full plan.

**Manual device verification** — `./build_and_install.sh debug`, then:
```
adb logcat -c && adb logcat | grep -E "sambas3|RPCSX|Main"
```
Scenarios:
S1 Boot game → tap menu_toggle → Kotlin list visible over dimmed pad → tap "Resume Game"
   → both menus close, overlay restores, game interactive.
S2 Open menu → tap "Settings" → engine settings page renders (dim retained), Kotlin list
   collapses to ☰ → tap ☰ → list returns (CIRCLE back + resync observed as brief
   highlight walk on engine list).
S3 Open menu → tap "Take Screenshot" → screenshot taken, menus closed.
S4 Open menu → tap "Exit Game" → Kotlin confirm appears; "No" → nothing changes;
   repeat and "Yes" → game shuts down cleanly.
S5 Open menu → tap ✕ → menu closes, no stray button reaches the game (character/game
   input unaffected).
S6 While Kotlin menu is EXPANDED, tap/drag on scrim and non-row areas: NOTHING happens —
   no floating stick spawn, no PadOverlay face-button activation (HomeMenuView consumes
   all touches; strictly modal). In COLLAPSED ☰ mode: dpad/face overlay controls work;
   DURING an injection, overlay touches neither spawn sticks nor corrupt the running
   sequence (logcat shows clean press/release cadence).
S7 PS-glass tap → menu mode ON **and** Kotlin list shown (native open + UI together).
S8 Rapid double-taps on rows: second tap ignored while injecting (row disable), no
   stuck buttons afterward (subsequent pad control still works).
S9 Open menu → tap "Settings" (depth 1) → collapse via ☰ → toggle OFF → engine menu
   FULLY closed (two CIRCLEs: parent then exit), overlay undimmed, game interactive,
   no orphaned engine menu visible.

---

## Acceptance Criteria (objective)

- [ ] AC1 Touch "Resume Game" closes BOTH the Kotlin list and the engine menu; pad overlay
      returns to pre-menu alpha; game receives no stray pad press (verifiable in logcat
      cadence + game behavior).
- [ ] AC2 Touch "Settings" opens the engine settings page with dim retained; Kotlin list
      collapses to ☰; tapping ☰ restores the list and engine returns to main page.
- [ ] AC3 Touch "SaveState" behaves per AC2 (submenu variant).
- [ ] AC4 Touch "Take Screenshot"/"Start/Stop Recording" triggers the engine action and
      closes both menus.
- [ ] AC5 Touch "Exit Game" ALWAYS shows the Kotlin confirm first; "No" sends zero pad
      frames; "Yes" shuts the game down.
- [ ] AC6 ✕ (and toggle-OFF) close the engine menu from ANY tracked depth via exactly
      `(trackedDepth + 1)` synthetic CIRCLE presses, then dismiss the UI and reset
      trackedDepth to 0 — engine menu never left orphaned over an undimmed game.
- [ ] AC7 Injection never leaves a stuck button: after ANY completed or cancelled
      sequence (including activity destroy mid-injection), the last pushed frame is
      all-released (unit-tested invariant; manual check S8).
- [ ] AC8 While Kotlin menu is EXPANDED or injecting: no floating-stick spawn, no
      floating-stick handling, and HomeMenuView consumes ALL touches (scrim taps are
      no-ops — PadOverlay receives nothing, so fixed dpad/face buttons cannot fire).
      "Fixed dpad/face/L3/R3 remain live" applies ONLY in collapsed ☰ mode and outside
      injection.
- [ ] AC9 PS-glass tap opens menu mode WITH the Kotlin list visible.
- [ ] AC10 `:app:testStandardDebugUnitTest` and `:app:testPlaystoreDebugUnitTest` pass
      with the new test classes.

---

## Risks & Mitigations (NEW_RISKS)

- **R1 Conditional engine items (index drift).** Friends appears iff RPCN configured
  (main_menu.cpp:42-69); Trophies iff trophy data exists (main_menu.cpp:78-96). If the
  runtime list differs from the hardcoded 8-item model, DOWN-count targets the wrong row.
  *Mitigation:* single-source item table; clamp-to-top resync limits damage to wrong-row
  activation only; document in code comment; future work = parse engine state if a JNI
  ever exposes it. Verified current build shows the 8-item layout.
- **R2 Injection duration.** Longest path ≈ 4.3 s (AC worst case). *Mitigation:* row
  disabling + progress indication; optional future optimization using L1/R1 jump-±10
  (page.cpp:224-233) once lists exceed 10 items (currently useless: 8 < 10).
- **R3 Slow-device timing.** Engine polls at 1 ms and fires on press edges; 120/150 ms
  envelope has >100× margin; auto-repeat needs ≥500 ms hold (overlays.cpp:57,143).
  Residual risk accepted; constants centralized for tuning.
- **R4 Enter-button assignment.** If user sets `enter_button_assignment=circle`
  (system_config.h:343 default cross), CROSS/CIRCLE roles swap engine-wide
  (overlays.cpp:198-199). Out of scope: app never surfaces this setting; documented.
- **R5 Stray pad presses after NATIVE engine-menu closure (stale Kotlin list).** The
  engine can close its menu without telling Kotlin — on-screen ○ via the fixed overlay
  face buttons in menu mode (PadOverlay.kt:357-361), or a hardware gamepad. There is NO
  engine→Kotlin notification path (grep of native-lib.cpp: only inbound
  `overlayPadData`/`openHomeMenu` bindings). If that happens while the Kotlin list is
  visible, row taps would inject Release+UP×8+CROSS into the LIVE GAME. Same class of
  risk: toggle-OFF injecting CIRCLEs into a live game if the menu already closed natively.
  *Mitigation:* tracked-open flag cleared on every Kotlin dismissal path; recovery path
  for the stale-list case = collapsed ☰ tap performs full resync (UP×8 clamp-to-top
  before any navigation) and/or user does a toggle off/on cycle or PS-glass re-open
  (engine re-create guarded, pad_thread.cpp:660); every injected navigation starts from
  the resync so a stale list self-corrects on next use; residual window documented.
- **R6 Depth desync while collapsed / activity recreation.** Pad-navigating deeper pages
  while the Kotlin list is collapsed invalidates trackedDepth; ☰ restore might send
  CIRCLE at root (closing the menu). *Mitigation:* restore failure mode = menu closes
  (safe, recoverable via PS glass); recovery documented (toggle cycle). Additionally,
  ACTIVITY RECREATION while the menu is open drops `menuModeOn`/Kotlin UI state entirely:
  RPCSXActivity has NO `android:configChanges` (AndroidManifest.xml:38-43) and v1 kept
  `menuModeOn` as a local var (RPCSXActivity.kt:46) — this is a PRE-EXISTING v1
  limitation, explicitly accepted here; injector.onDestroy-cancel prevents stuck buttons
  across recreation, but the engine menu may remain open over an undimmed overlay until
  the user re-opens/toggles. Future improvement (out of scope): persist menu-mode flag in
   onSaveInstanceState.
- **R7 Concurrent pad writers.** Hardware gamepad events during injection push absolute
  states that fight the injector (both write the same virtual pad). *Mitigation:* accept
  + document as known limitation (menu context: game paused-ish, hardware rarely used);
  PadOverlay writer fully suppressed during injection.
- **R8 Upstream divergence.** Upstream RPCS3 is rewriting the home menu (sidebar/tabs,
  PR #18358); a future engine .so update may change item order/count. *Mitigation:*
  item table is one constant; resync strategy tolerant to count changes passed as
  parameter.
- **R9 (future work).** A native `isHomeMenuOpen()` JNI query would eliminate the entire
  R5/R6 stale-open/desync class; noted as future work, no engine changes in this plan.

---

## Handoff to Plan Reviewer

**PASS 2 changes to validate (per docs/reviews/kotlin-home-menu-ui-plan-pass1-review.md):**
- MAJOR-1 fixed: Step 5 toggle-OFF/✕ now injects `(trackedDepth + 1)` sequential CIRCLE
  presses (120/150 ms envelope) before hiding UI / `setMenuMode(false)`, then resets
  `trackedDepth = 0`; ✕ row in Step 6 table updated; new unit test #4
  (`buildClosePlan`) and manual scenario S9 cover it.
- MAJOR-2 fixed: Step 4 specifies expanded-mode HomeMenuView consumes ALL touches
  (`onTouchEvent` returns true unconditionally, scrim included); AC8 and S6 reworded so
  "fixed overlay buttons live" applies ONLY to collapsed ☰ mode.
- MINOR fixes: FQN `<com.zenithblue.sambas3.overlay.HomeMenuView>` in change map;
  injector `postDelayed: (Runnable, Long) -> Unit` scheduler seam for JVM tests;
  R5 extended (native closure → stale list → taps hit live game, resync/toggle recovery);
  R6 extended (activity recreation drops menu state — no configChanges,
  AndroidManifest.xml:38-43 — accepted v1 limitation); R9 future JNI note added.

Please validate:
1. Engine claims: item order/effects (main_menu.cpp), clamp-not-wrap
   (overlay_list_view.cpp:150), initial index 0 (hpp:20/cpp:185-186), exit-game-no-confirm
   (main_menu.cpp:139-151), 1 ms poll + 500 ms repeat threshold (overlays.cpp:57,143).
2. Depth-aware close math vs engine CIRCLE semantics (page.cpp:167-176) and the S9 flow.
3. Touch-consumption spec completeness (dispatch order, modal expanded mode, collapsed
   fall-through intent).
4. Architecture fit: custom View vs Compose decision given `Activity` base
   (RPCSXActivity.kt:24); XML Z-order placement; PadOverlay gating points.
5. Safety invariant (final all-release frame) implementation feasibility in
   PadInputInjector.cancel()/onDestroy path; JVM testability of the scheduler seam.
6. Sync strategy soundness (UP×itemCount clamp-to-top) and ☰ restore depth heuristic.
7. Acceptance criteria objectivity and manual scenario coverage (S1–S9).
Next pipeline step after APPROVE: invoke @worker with this plan.
