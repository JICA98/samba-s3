# Plan: In-Game Home Menu vs Controller Overlay — Reachability, Occlusion & Touch Conflict

Task slug: `ingame-menu-overlay-touch-plan`
PASS 2 · ITERATION 1 (revised per pass-1 review)

## Task Summary

USER-ISSUE (verbatim): *"in game app menu is behind the controller overlay. when touching ui controller is moving"*

Inside `RPCSXActivity`, the emulator renders its **Home Menu** (`rsx::overlays::home_menu_dialog`) onto the game surface (`GraphicsFrame`), but the semi-transparent `PadOverlay` sits above it as a full-screen Android View. Two symptoms:

1. **Visual occlusion** — the Home Menu draws *behind* the PadOverlay; users cannot see/reach it.
2. **Touch conflict** — every tap anywhere hits PadOverlay's full-screen `OnTouchListener`, and taps in the central area **spawn/move a floating analog stick** whose data is pushed to the emulator (`overlayPadData`) instead of interacting with the menu ("controller is moving").

### Root-cause findings (evidence-backed, verified this pass)

- The Home Menu is **already reachable today**: pressing the on-screen **PS glass button** sends `CELL_PAD_CTRL_PS` (Digital1 bit 0x100, `RPCSX.kt:17`) through `overlayPadData` → virtual pad (`rpcsx-android.cpp:1649-1650` registers it) → `pad_thread` detects the press edge and calls `open_home_menu()` (`pad_thread.cpp:461-475`). Users just have no discoverable affordance and no feedback.
- The Home Menu is an RSX overlay drawn on the **same game surface**, navigable **only by pad buttons**: dpad/cross select items (`overlay_home_menu_page.cpp:147-166`), **Circle exits/closes** (`overlay_home_menu_page.cpp:167-175` → `close(true,true)` at `overlay_home_menu.cpp:105`). It accepts **no mouse/touch input** (zero `mouse` matches in `Emu/RSX/Overlays/*`; `init_mouse_handler` installs `NullMouseHandler`, `rpcsx-android.cpp:1454-1458`; no touch/mouse JNI exists in `native-lib.cpp`).
- Therefore **direct touch-to-menu is impossible without engine `.so` changes** (the core is downloaded from GitHub releases at runtime per root `AGENTS.md`). The correct smallest change is **Kotlin-only**: make the menu discoverable, stop PadOverlay from hijacking touches while the menu is up, and dim the overlay so the menu is visible — while keeping pad-button navigation to drive the menu.

### Chosen design: explicit "menu mode"

A user-controlled **menu mode** (not inferred state — see Risks):

- New small toggle button (`menu_toggle`) next to the existing `osc_toggle` (bottom-right, same layout pattern, `activity_rpcs3.xml:21-32`).
- Toggle ON → `RPCSX.instance.openHomeMenu()` (binding exists end-to-end: `RPCSX.kt:92` → `native-lib.cpp:211-214` → `_rpcsx_openHomeMenu` at `rpcsx-android.cpp:1865-1869`) **and** `padOverlay.setMenuMode(true)`:
  - **Floating-stick spawn disabled** (gates `PadOverlay.kt:379-397`) → center-screen taps no longer create/move sticks (fixes bug #2).
  - **Floating-stick touch handling disabled** (`PadOverlay.kt:364-371`); any active floating stick is released and its analog values re-centered on entry.
  - **All pad controls stay live** (dpad, face, shoulders, PS, START/SELECT, fixed L3/R3 sticks) → the menu remains navigable via overlay controls.
  - **Overlay dims** to ~35% alpha (reuses the proven `ObjectAnimator` alpha pattern of the existing fade system, `PadOverlay.kt:474-492`) → menu clearly visible (fixes bug #1).
- Toggle OFF → restore alpha 1.0, floating sticks work again. User closes the actual menu with **Circle / in-menu Exit** (engine-side), exactly as on upstream RPCS3.
- Bonus symmetry: pressing the **PS glass button** while not in menu mode also enters menu mode (via a small listener), because the engine deterministically opens the menu on that edge.

## Research Sources

- <source: repo file> `patches/rpcsx-submodule-changes.patch:34-43` — proves an older auto-`open_home_menu()` inside `_rpcsx_surfaceEvent` was deliberately removed; menu opening is intentionally explicit now.
- <source: repo file> `app/src/main/cpp/rpcsx/rpcs3/Input/pad_thread.cpp:461-475, 645-684` — PS-button edge detection → `open_home_menu()`; `m_home_menu_open` lifecycle; OSK-busy failure path (:651-656).
- <source: repo file> `app/src/main/cpp/rpcsx/rpcs3/Input/virtual_pad_handler.cpp:25-42` + `rpcsx-android.cpp:1606-1674,1676-1700` — virtual pad wiring for `overlayPadData`, PS bit registered.
- <source: repo file> `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu{.cpp,_page.cpp}` — menu is pad-only UI; circle=exit; `pause_during_home_menu` default false (`system_config.h:400`).
- <source: GitHub commit> RPCS3-Android `4948a8c` "vpad: add home button / open home menu on pause" — https://github.com/RPCS3-Android/rpcs3-android/commit/4948a8c5def06ea15a4ec6189dba36d89b203713 — sibling frontend solved the same problem: added `openHomeMenu()` JNI + dedicated HOME button on PadOverlay sending `CELL_PAD_CTRL_PS`. Our repo already contains the equivalent JNI/binding; we add the discoverable affordance + touch arbitration it lacked.
- <source: GitHub issue> RetroArch #9043 + PR #14044 "Block pointer input when overlay is pressed" and #18364/#18367 "Pass touches through passive overlay elements" — https://github.com/libretro/RetroArch/issues/9043 — established pattern: overlays must arbitrate touch (block pointer while overlay control engaged / pass through passive areas); supports our "suppress spawn area in menu mode".
- <source: docs> PPSSPP control settings (pause/on-screen button model, auto-hide, background-touch mapping): https://www.ppsspp.org/docs/settings/controls/ — precedent for explicit mode buttons rather than inferred menu state.
- <source: upstream source> RPCS3 `pad_thread.cpp` master — https://github.com/RPCS3/rpcs3/blob/master/rpcs3/Input/pad_thread.cpp — confirms our submodule logic matches upstream PS→home-menu behavior.
- <source: failed> `npx ctx7@latest library "Android" …` returned `✖ fetch failed` (network/quota). Fallback used: in-repo evidence that View-alpha animation works on this SurfaceView (`PadOverlay.kt:485,491` existing fades; minSdk 29 > API 24 where SurfaceView gained alpha support). Logged in Risks.

## Current Architecture

```
Kotlin Compose/UI ──► JNI bridge (native-lib.cpp ──dlsym──► libsambas3-android.so / librpcsx-android.so)
     │                                                        │
     │ activity_rpcs3.xml layering (bottom→top):              ▼
     │  1. GraphicsFrame (SurfaceView, game frames + RSX overlays incl. Home Menu)
     │  2. PadOverlay (full-screen SurfaceView, controller overlay)
     │  3. osc_toggle ImageButton (+ NEW menu_toggle)
     └─ input path: PadOverlay.OnTouchListener ──► State(digital/sticks) ──► RPCSX.overlayPadData ──► g_virtual_pad ──► pad_thread ──► game / home_menu_dialog
```

Files investigated (authoritative):

| File | Role / key lines |
|---|---|
| `app/src/main/res/layout/activity_rpcs3.xml:11-32` | Z-order: `GraphicsFrame` bottom, `PadOverlay` full-screen above, `osc_toggle` bottom-right |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt:41-44` | oscToggle only flips `padOverlay.isInvisible`; no menu awareness, no touch pass-through |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt:197-206` | `sendGamepadData()` → `RPCSX.instance.overlayPadData(...)` |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt:84,92` | `overlayPadData(...)`, `external fun openHomeMenu()` — declared, **never called from Kotlin** |
| `app/src/main/java/com/zenithblue/sambas3/GraphicsFrame.kt:8-45` | Plain SurfaceView; forwards surface lifecycle only, **no touch handling** |
| `app/src/main/java/com/zenithblue/sambas3/overlay/PadOverlay.kt:68` | Full-screen SurfaceView |
| `PadOverlay.kt:300-402` | `setupTouchListener`: consumes ALL touches; :346-350 editables; :356-362 fixed sticks; :364-371 floating-stick touch; :373-377 unconditional `overlayPadData`; **:379-397 ANY central tap spawns floating stick ← the reported bug** |
| `PadOverlay.kt:474-492` | Fade system (`resetFadeTimer/fadeOut/fadeIn`) using `ObjectAnimator` view-alpha — reuse pattern for dimming |
| `PadOverlay.kt:267-271` | PS glass button created with `Digital1Flags.CELL_PAD_CTRL_PS` |
| `app/src/main/java/com/zenithblue/sambas3/overlay/OverlayEditActivity.kt:142` | Sets `padOverlay?.isEditing = true` — edit branch (`PadOverlay.kt:318-341`) must stay isolated from menu mode |
| `app/src/main/cpp/native-lib.cpp:31,97,211-214` | `openHomeMenu` fully bound (`_rpcsx_openHomeMenu`); **no mouse/touch/pointer entry point exists** (struct :18-51 is exhaustive) |
| `app/src/main/cpp/native-lib.cpp:335,341,350` | Precedent null-guards for optional symbols (`patchEngineVersion`, `patchesList`, `patchSetEnabled`) — `openHomeMenu` wrapper lacks one (:211-214) |
| Submodule `rpcsx/android/src/rpcsx-android.cpp:1865-1869` | `_rpcsx_openHomeMenu()` → `padThread->open_home_menu()` |
| Submodule `rpcsx-android.cpp:1606-1674` | Virtual pad init; PS registered (:1649-1650); `g_virtual_pad` shared |
| Submodule `rpcsx-android.cpp:1454-1458` | `NullMouseHandler` — mouse/touch injection impossible today |
| Submodule `rpcs3/Input/pad_thread.{h:77,cpp:439,472}` | `m_home_menu_open`; opener skipped while menu open (PS re-press ignored by opener loop) |
| Submodule `rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_page.cpp:167-175` | Circle → `page_navigation::exit` → dialog closes |

## Affected Components & Dependencies

- **`RPCSXActivity`** — wires new toggle + listener; guards on `RPCSX.getState()`.
- **`PadOverlay`** — new `isMenuMode` state, `setMenuMode(Boolean)`, policy gating in `setupTouchListener`, dim animation, floating-stick release, PS-press notification hook.
- **NEW `overlay/OverlayTouchPolicy`** — pure-Kotlin decision object (no Android imports) so the arbitration matrix is JVM-unit-testable.
- **`activity_rpcs3.xml` + `strings.xml` + one vector drawable** — toggle button UI.
- **`native-lib.cpp`** (APK-side bridge, *not* the downloaded core) — optional 2-line defensive null-guard mirroring `:350`.
- **No submodule / engine `.so` changes.** Explicitly out of scope: anything requiring a new `librpcsx-android.so` release (core ships via GitHub downloads; see `AGENTS.md`).
- Consumers preserved: `OverlayEditActivity` (`isEditing` path untouched), hardware gamepad path (`onKeyDown/onGenericMotionEvent`, `RPCSXActivity.kt:108-195`) untouched.

## Implementation Steps (ordered, smallest correct change)

1. **Add pure-Kotlin touch-arbitration policy** `app/src/main/java/com/zenithblue/sambas3/overlay/OverlayTouchPolicy.kt`:
   ```kotlin
   object OverlayTouchPolicy {
       const val MENU_DIM_ALPHA = 0.35f
       // Fixed sticks are deliberately NOT governed by this policy: they stay live
       // during menu mode (left-stick menu navigation, overlay_home_menu_page.cpp:149-150).
       fun shouldHandleFloatingSticks(isMenuMode: Boolean): Boolean = !isMenuMode
       fun shouldSpawnFloatingStick(isMenuMode: Boolean): Boolean = !isMenuMode
   }
   ```
2. **Unit-test the policy** `app/src/test/java/com/zenithblue/sambas3/overlay/OverlayTouchPolicyTest.kt` (JUnit4, matching existing suite style under `app/src/test/java/com/zenithblue/sambas3/utils/`): matrix `menuMode=false` → both predicates true; `menuMode=true` → both false. The two functions currently share a body but MUST remain distinct symbols — they govern different code sites (touch-handling vs central-spawn) and may diverge independently. No `isEditing` parameter: the edit branch returns early from `setupTouchListener` (`PadOverlay.kt:318-341`) before any gated block runs. Run: `./gradlew :app:testStandardDebugUnitTest`.
3. **PadOverlay changes** (`PadOverlay.kt`):
   a. `var isMenuMode = false; private set` + `fun setMenuMode(on: Boolean)`.
      **Floating-stick release (normative — ONE approach, no implementer discretion: synthetic ACTION_CANCEL through the existing reset path).** When menu mode activates while a floating stick is active for side `i` (`floatingSticks[i] != null`), `setMenuMode(true)` MUST:
      - obtain a synthetic cancel event: `val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)`;
      - deliver it via `floatingSticks[i]!!.onTouch(cancel, /*pointerIndex=*/0, state)`; then call `cancel.recycle()`.
      This drives the existing reset branch at `PadOverlayStick.kt:136-151`, which restores AT MINIMUM: `locked = -1` (:138); ring bounds returned to base position (`bgOffsetX/Y` re-applied, then zeroed, :141-142); nub re-centred via `centreNub()` (:143); pressed L3/R3 bit cleared in `state.digital[pressDigitalIndex]` (:145); analog axes recentred to 127 in `State` (:147-148); stick alpha remains `idleAlpha` (alpha is never mutated by touch — invariant holds). The synthetic event is sufficient because the CANCEL branch short-circuits pointer-identity checks (:137 evaluates `action == ACTION_CANCEL` first). After delivery, set `floatingSticks[i] = null` and `invalidate()`. Do NOT add a parallel `PadOverlayStick.release()` API — reusing the verified :136-151 path avoids logic drift.
      **Fade arbitration (normative, replaces any one-shot cancel).** While `isMenuMode && !isEditing`:
      1. `setupTouchListener` MUST NOT call `resetFadeTimer()` or `fadeInOverlay()` — skip the block at `PadOverlay.kt:305-309` when menu mode is active.
      2. `fadeOutOverlay()` / `fadeInOverlay()` MUST early-return while menu mode is active (guard both bodies, `PadOverlay.kt:482-491`).
      3. On ENTERING menu mode: record the prior `isOverlayVisible` flag and current view `alpha`; force `isOverlayVisible = true`; animate view alpha to `OverlayTouchPolicy.MENU_DIM_ALPHA` (0.35f).
      4. On EXITING menu mode: animate back to 1f if previously visible (to 0f if not), restore the recorded `isOverlayVisible`, then resume the normal fade cycle by calling `resetFadeTimer()`.
   b. In `setupTouchListener` (:301-402): compute `val menuMode = isMenuMode && !isEditing` once per event. Gate EXACTLY TWO blocks behind the policy: the floating-stick touch-handling block `PadOverlay.kt:364-371` with `if (OverlayTouchPolicy.shouldHandleFloatingSticks(menuMode))`, and the central-spawn block `PadOverlay.kt:379-397` with `if (OverlayTouchPolicy.shouldSpawnFloatingStick(menuMode))`. The fixed-stick loop `PadOverlay.kt:356-362` MUST REMAIN UNGATED so fixed L3/R3 sticks stay live during menu mode — the Home Menu supports left-stick navigation (`overlay_home_menu_page.cpp:149-150` maps `ls_left`/`ls_right`). Editables (:346-350) and the unconditional `overlayPadData` push (:373-377) stay exactly as-is.
   c. PS-press notification: track previous PS-bit (`state.digital[0] and Digital1Flags.CELL_PAD_CTRL_PS.bit`) across events; on 0→pressed transition while not editing/menu-mode, invoke `var onMenuRequestedFromPad: (() -> Unit)? = null`.
4. **Layout + resources**: `activity_rpcs3.xml` — add `ImageButton` id `menu_toggle` constrained above `osc_toggle` (`layout_constraintBottom_toTopOf="@id/osc_toggle"`, same end margin), 40% tint like sibling; add vector drawable (simple grid/home glyph; reusing an existing suitable icon is acceptable) + `strings.xml` entry `home_menu_toggle_cd` for `contentDescription`.
5. **RPCSXActivity wiring** (`RPCSXActivity.kt`, near :41-44):
   ```kotlin
   var menuModeOn = false
   binding.menuToggle.setOnClickListener {
       if (RPCSX.getState() != EmulatorState.Running) return@setOnClickListener  // log via Log.w("RPCSX State", …)
       menuModeOn = !menuModeOn
       if (menuModeOn) RPCSX.instance.openHomeMenu()
       binding.padOverlay.setMenuMode(menuModeOn)
       binding.menuToggle.setImageResource(if (menuModeOn) …else…)   // mirrors oscToggle pattern
   }
   binding.padOverlay.onMenuRequestedFromPad = {
       if (!menuModeOn) { menuModeOn = true; RPCSX.instance.openHomeMenu(); binding.padOverlay.setMenuMode(true); /* icon swap */ }
   }
   ```
6. **Defensive native guard** (`native-lib.cpp:211-214`, APK-side only): `if (!rpcsxLib.openHomeMenu) { __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI", "openHomeMenu unavailable"); return; }` — mirrors `:335/:341/:350` precedent, prevents SIGSEGV on old downloaded cores lacking the symbol.
7. **Verify**: `./gradlew assembleStandardDebug assemblePlaystoreDebug` + both unit-test tasks; then manual verification below.

## File-Level Change Map

| File | Change | Rationale |
|---|---|---|
| `app/src/main/java/com/zenithblue/sambas3/overlay/OverlayTouchPolicy.kt` | NEW (≈15 lines) | Pure-Kotlin, unit-testable touch arbitration |
| `app/src/test/java/com/zenithblue/sambas3/overlay/OverlayTouchPolicyTest.kt` | NEW | Policy matrix coverage |
| `app/src/main/java/com/zenithblue/sambas3/overlay/PadOverlay.kt` | Add `isMenuMode`/`setMenuMode`, gate ONLY :364-371 & :379-397 via policy (fixed-stick loop :356-362 ungated), fade-arbitration guards, synthetic-cancel float release, PS hook (~60 lines) | Fixes touch-hijack + occlusion; preserves gameplay path |
| `app/src/main/res/layout/activity_rpcs3.xml` | Add `menu_toggle` ImageButton | Discoverable menu affordance (same pattern as `osc_toggle`) |
| `app/src/main/res/drawable/ic_home_menu.xml` (or reused icon) + `res/values/strings.xml` | Icon + contentDescription | Accessibility |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` | Wire toggle + `onMenuRequestedFromPad`, state guard, icon swap (~20 lines) | Calls previously-dead `openHomeMenu()`; single source of truth for mode |
| `app/src/main/cpp/native-lib.cpp` | Null-guard in `Java_com_zenithblue_sambas3_RPCSX_openHomeMenu` | Crash-safety on stale cores; follows :350 precedent |

**Not touched**: submodule `app/src/main/cpp/rpcsx/**`, `GraphicsFrame.kt`, `InputBindingPrefs`, hardware-input paths, `OverlayEditActivity`.

## Testing Strategy

Unit (JVM):

```bash
./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest
./gradlew assembleStandardDebug assemblePlaystoreDebug
./build_and_install.sh debug   # install on device
adb logcat -c && adb logcat | grep -E "sambas3|RPCSX|Main"
```

Manual (device, via android-mcp tools; boot any PS3 game first):

1. Launch SambaS3 → start a game → confirm overlay visible and floating-stick spawn works (baseline).
2. Tap `menu_toggle` → Snapshot(use_vision=true): Home Menu readable on surface, PadOverlay dimmed (~35%), toggle icon swapped. Primary evidence = UI Snapshots (menu rendered above dimmed overlay + inert center taps in step 3). Logcat `opening home menu...` (`pad_thread.cpp:671`, `input_log.notice`) is BEST-EFFORT supplementary evidence only — log tag/routing may vary on device builds.
3. Menu mode ON: tap 5–10× across screen center (avoiding controls) → no stick appears, no stick moves, no unintended menu navigation (compare before/after Snapshots).
4. Menu mode ON: tap overlay D-pad Up/Down → menu selection moves; tap CROSS → item activates; navigate to Exit or tap Circle → menu disappears from surface.
5. Tap `menu_toggle` again → overlay restores full alpha; center tap spawns floating stick again (criterion 1 replays green).
6. Regression: PS glass button (menu mode off) opens menu AND enters menu mode (dim + spawn suppression).
7. Regression: osc_toggle hide/show still independent; OverlayEditActivity drag/editing unaffected; hardware gamepad input unaffected.
8. `git diff --stat app/src/main/cpp/rpcsx` → empty (no engine changes shipped).

## Acceptance Criteria (objective, verifiable)

- [ ] With menu mode OFF, tapping screen center spawns/moves a floating stick exactly as today (behavior unchanged).
- [ ] After entering menu mode, tapping the central screen area does **NOT** spawn or move any analog stick, and no stick/analog state is emitted from those taps (`state` stays centered; verified visually + no game-character drift).
- [ ] Home Menu is clearly visible on screen while menu mode is ON (PadOverlay dimmed to ~35%; screenshot evidence).
- [ ] While menu mode is ON, overlay D-pad/CROSS/CIRCLE navigate and close the Home Menu (Circle dismisses the dialog from the surface).
- [ ] During menu mode, fixed L3/R3 sticks remain touchable and send data (left-stick menu navigation works).
- [ ] Exiting menu mode restores full-brightness overlay and floating-stick spawning works again.
- [ ] Pressing the PS glass button outside menu mode opens the Home Menu and enters menu mode automatically.
- [ ] `osc_toggle` invisibility toggle and OverlayEditActivity editing behave exactly as before (no interaction with menu mode).
- [ ] `./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest` pass, including new `OverlayTouchPolicyTest`.
- [ ] `git diff` shows zero changes under `app/src/main/cpp/rpcsx/**`.

## Risks & Mitigations (NEW_RISKS)

1. **Native lacks touch/mouse forwarding** (confirmed constraint): `NullMouseHandler` (`rpcsx-android.cpp:1454-1458`), no mouse handling anywhere in `Emu/RSX/Overlays`, no pointer JNI in `native-lib.cpp:18-51`. True touch-on-menu requires engine `.so` changes **plus a coordinated core release** (core is downloaded at runtime, not bundled). Out of scope; recorded as future work (would be: real mouse handler + `_rpcsx_sendMotionEvent` JNI + GraphicsFrame touch forwarding).
2. **Menu-open state not exposed to Kotlin** (`m_home_menu_open` lives in `pad_thread.h:77`, no query/export). Menu mode is therefore UI-level and can desync: e.g., `openHomeMenu()` fails silently on OSK-busy (`pad_thread.cpp:651-656`), or the user closes the menu via in-menu Exit while the toggle still reads "on". Mitigation: mode is explicitly user-toggled, degrades gracefully (dimmed overlay + suppressed spawns are harmless standalone); icon/state resets on next toggle or activity recreation. A future `isHomeMenuOpen()` export would make this exact.
3. **ctx7 docs unavailable** (`npx ctx7 …` → `fetch failed`). Fallback basis for View-alpha-on-SurfaceView: the repo's own working fade animations animate this very SurfaceView's alpha (`PadOverlay.kt:485,491`), and minSdk 29 exceeds API 24 where SurfaceView alpha compositing landed. Low risk.
4. **Old downloaded cores without `_rpcsx_openHomeMenu`** would SIGSEGV at `native-lib.cpp:213` (no null-guard, unlike `:350`). Mitigation: step 6 adds the guard (APK-side, no core release needed).
5. **Floating stick mid-drag when menu mode engages** could leave stale analog values. Mitigation: `setMenuMode(true)` releases floating sticks and re-centers `State` before dimming.
6. **Unconditional `overlayPadData` on every touch** (`PadOverlay.kt:373-377`) continues in menu mode with unchanged values — harmless (idempotent state push) but noted so reviewers don't mistake it for a regression.
7. **Rejected alternatives** (documented to prevent re-litigation): (a) touch-forwarding to native — blocked by risk 1; (b) long-press PS gesture — PadOverlayButton has no long-press infrastructure; (c) hiding the whole overlay during menu (`isInvisible`) — would strip the only pad controls that can drive the pad-only menu; (d) inferring menu-close from Circle presses — fragile, unnecessary given explicit toggle.
8. **Known limitation — hardware gamepad**: pressing a physical controller's PS button opens the engine Home Menu but does NOT enter Kotlin menu mode — the `onMenuRequestedFromPad` hook lives only in the overlay touch path, so no dim/spawn-suppression occurs for hardware-pad users. Note the PS **glass** button already opens the engine menu via a direct native call inside `_rpcsx_overlayPadData` (`rpcsx-android.cpp:1695-1699`, verified this pass) in addition to the pad_thread edge loop (`pad_thread.cpp:472-475`). Mitigation: acceptable for a touch-first frontend; future work could poll engine state or extend the JNI surface (`isHomeMenuOpen()` export).

## Handoff to Plan Reviewer

PASS 1 review findings are incorporated in this revision (PASS 2 · ITERATION 1): deterministic floating-stick release semantics (synthetic ACTION_CANCEL through `PadOverlayStick.kt:136-151`, zero implementer discretion), narrowed gating scope (only :364-371 and :379-397; fixed-stick loop :356-362 explicitly ungated), differentiated policy functions (`shouldHandleFloatingSticks` vs `shouldSpawnFloatingStick`), normative four-step fade arbitration replacing one-shot cancel, added fixed-stick acceptance criterion, hardware-pad limitation recorded (risk 8), and logcat evidence demoted to best-effort.

Please validate:

1. **PASS 2 normative semantics**: is the synthetic-ACTION_CANCEL release fully deterministic given `PadOverlayStick.onTouch` (:80-155)? Does the cancel branch leave any state un-reset?
2. **Engine-behavior claims**: PS-edge → `open_home_menu` (`pad_thread.cpp:461-475`) plus the direct native hook inside `_rpcsx_overlayPadData` (`rpcsx-android.cpp:1695-1699`), Circle-closes-menu (`overlay_home_menu_page.cpp:167-175`), pad-only input (no mouse handling in Overlays; `NullMouseHandler` :1454-1458), `pause_during_home_menu=false` default (`system_config.h:400`).
3. **Gating completeness**: only :364-371 and :379-397 gated — any other code site where menu-mode touches could mutate controller state?
4. **Fade arbitration**: do the four numbered rules fully prevent the idle-fade system from fighting the menu-mode dim on both enter and exit paths?
5. **Threading/lifecycle**: `setMenuMode` runs on UI thread from click listeners; `MotionEvent.obtain`/`recycle()` pairing; activity destroy naturally resets state — any missed lifecycle hole?

Pipeline next: `@plan-reviewer` → APPROVE → `@worker` (implementation) → `@impl-reviewer` → `@manual-tester` → `@final-result`.
