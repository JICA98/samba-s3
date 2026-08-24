# Plan: Native In-Game Menu + Real Settings & Per-Game Configure Pages (No Pad Simulation)

> **PASS 3 spot-fix** — applies `docs/reviews/native-ingame-menu-settings-plan-review-pass2.md`
> (verdict REVISE): MAJOR-1 scopes the AlertDialogQueue suppression gate per-host via
> `AlertDialog(respectHostSuppression: Boolean = true)` — launcher host keeps default `true`,
> in-game host passes `false` — plus 2 informational minors (benign duplicate title-tier
> re-application noise accepted; learning-map fallback wired into `resolveTitleId`/`applyForGame`).
> All PASS 1/PASS 2 fixes remain in force — full history enumerated in Handoff.

## Task Summary

Replace the rejected pad-injection home-menu stack (`HomeMenuView` + `PadInputInjector` simulating DS3 pad frames) with **real native UI**:

1. **In-game menu** — a Material3/Compose panel (app CRT-gold theme, real vector icons, zero emoji) opened during gameplay via the existing `menu_toggle` button and the back gesture. Entries: Resume, Configure Game (per-title), Global Settings, Core Home Menu (engine's native menu), Exit Game.
2. **Full in-game settings page** — the existing `AdvancedSettingsScreen` preference UI reused inside the in-game overlay, bound live to `settingsGet`/`settingsSet`.
3. **Per-game "Configure Game" page** — curated per-title overrides (resolution/scaling, shader mode, frame limit, GPU readbacks, audio, LLVM threads…) with tri-state rows (Use Global / Override), per-row reset, reset-all, persisted app-side and replayed at boot in the correct tier order.

The user's verbatim intent: *"this is bad u r just simulating it i need fully good ui in game settings menu and game settings configre page both in game and configure page made with good ui … no emoji only real icon"*.

## Research Sources

External (cloned under `/tmp/opencode/`):

- `<source: /tmp/opencode/EmuCoreC/app/src/main/java/com/sbro/emucorec/core/ps3/Emulator.kt>` — Compose-over-surface activity pattern: `class Emulator : AppCompatActivity()` (Emulator.kt:52); `root.addView(surfaceView…); root.addView(createComposeOverlay(), …)` FrameLayout stacking (Emulator.kt:89-93); `createComposeOverlay()` builds a `ComposeView` with `ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed`, elevation/translationZ 64f, `setContent { Theme { EmulationOverlayHost(activity) } }` (Emulator.kt:204-216); post-boot `RPCSX.instance.getTitleId()` → `setCurrentGameId` (Emulator.kt:181-184); `setMenuPaused` (Emulator.kt:276-280); `exitEmulation()` stop+finish (Emulator.kt:282-288); back key routed via `dispatchKeyEvent` handler (Emulator.kt:300-305).
- `<source: /tmp/opencode/EmuCoreC/…/core/Ps3CoreSettingOverrides.kt>` — the authoritative per-game override store: prefs `"emucorec_ps3_core_overrides"` with `KEY_GLOBAL="global"`, `KEY_BASELINE="baseline"`, `GAME_PREFIX="game."` (Ps3CoreSettingOverrides.kt:16-19); `RECOMMENDED_DEFAULTS` map with JSON-encoded values e.g. `"Core@@Max LLVM Compile Threads" to "2"`, `"Video@@Shader Mode" to "\"Async Recompiler (multi-threaded)\""` (:40-57); `recordGame(...)` captures previous value into baseline (:71-89); `clearGameSetting` removes one override and immediately restores the global value live (:149-171); **replay order** in `applyForGame`: recommended defaults → baseline → global → per-title (:197-213, esp. :211-212); title-id normalization regex `[A-Z0-9_.-]{3,64}` (:224-227); `collectDefaults` encodes bool/int/uint/float raw and enums quoted (:256-275).
- `<source: /tmp/opencode/EmuCoreC/…/core/Ps3Runtime.kt>` — `boot()` calls `Ps3CoreSettingOverrides.applyForGame(context, titleId)` **before** `RPCSX.boot(path)` (Ps3Runtime.kt:226-231); `pause()` is an explicit **no-op**: "the official RPCS3 core has no pause, only kill/resume" (Ps3Runtime.kt:249-252) — confirms our menu runs over a live-running game unless we exit.
- `<source: /tmp/opencode/EmuCoreC/…/ui/emulation/EmulationMenu.kt>` — in-game menu structure: `EmulationGameMenu` renders a bordered/elevated `Surface` panel with tab layouts (:184-310); tabs enum Game/Graphics/Audio/Controls/Achievements/Gamepad (:407-414); in-game core settings embed via `Ps3CoreSettingsSection(scope = Game, surface = InGame, titleId)` (:417-429).
- `<source: /tmp/opencode/EmuCoreC/…/ui/settings/Ps3CoreSettingsSection.kt>` — tri-state row logic: flatten `settingsGet("")` tree, layer per-title overrides over resolved globals, mark `overridden = true` (:108-147, esp. :130-137); `write()` applies live via `settingsSet` then records to global-or-game tier (:149-175); `resetGameSetting` restores global (:177-195).
- `<source: /tmp/opencode/EmuCoreC/…/core/Ps3SfoParser.kt>` — 83-line SFO reader extracting `values["TITLE_ID"]` (:66) — reference shape for pre-boot title-id derivation.
- `<source: /tmp/opencode/EmuCoreC/…/core/Ps3GameSettingsRepository.kt>` — per-title profile JSON in SharedPreferences `"emucorec_game_ui_config"`, key `"game_<TITLEID>"` (:70-74).
- `<source: /tmp/opencode/aps3e/app/src/main/java/aenu/aps3e/EmulatorActivity.java>` — their in-game menu is a classic-View `DialogFragment` inflating `R.layout.dialog_running_menu` (EmulatorActivity.java:315) — considered and rejected in favor of Compose (we already ship a Compose preference library).
- `<source: /tmp/opencode/ARMSX3/>` — `android/` contains only native build tooling (`rpcsx-android.cpp`, cmake scripts); no Android app UI exists to borrow. Not applicable.
- `<source: https://github.com/RPCS3-Android/rpcs3-android>` — upstream origin of our `ui/settings` screen and preference component library (same package structure `SwitchPreference/ListPreference/SliderPreference`; our SettingsScreen.kt imports them at SettingsScreen.kt:77-85).

Internal (this repo):

- `<source: docs/reviews/native-ingame-menu-settings-plan-review.md>` — PASS 1 review (verdict REVISE, 2 MAJOR / 5 MINOR / 2 SUGGESTIONS); all findings applied in this PASS 2 revision.
- `<source: app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp>` — `_rpcsx_settingsGet(path)` returns subtree `to_json().dump(4)`, empty path = whole tree (:2584-2592); `_rpcsx_settingsSet(path, value)` parses JSON, finds node, validates `root->from_json(value, !Emu.IsStopped())` — i.e. many nodes apply live while running, others reject (:2594-2616); `_rpcsx_kill/_rpcsx_resume/_rpcsx_openHomeMenu/_rpcsx_getTitleId` (:1862-1871).
- `<source: app/src/main/cpp/native-lib.cpp>` — JNI: kill :201, resume :206, openHomeMenu :211, getTitleId :222, settingsGet :277, settingsSet :281. **No pause symbol exists** (dlsym table lines 29-110 contain none).
- `<source: app/src/main/java/com/zenithblue/sambas3/RPCSX.kt>` — `settingsGet/settingsSet` (:87-88), `kill` (:90), `resume` (:91), `openHomeMenu` (:92), `getTitleId` (:95); `getTitleId()` has **zero callers** today (repo-wide grep).
- `<source: app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt>` — `class RPCSXActivity : Activity()` (:31); pad-injection wiring: imports (:21-25), `padInputInjector` field (:47-49), `menuToggle` → `openHomeMenu()` + `setMenuMode` + `showHomeMenuUi()` (:64-77), `onMenuRequestedFromPad` (:81-87), selection → `runInjection(navigationPlan(item))` (:176-215), injector cancel in onDestroy (:217-224).
- `<source: app/src/main/res/layout/activity_rpcs3.xml>` — `GraphicsFrame` :11-14, `PadOverlay` :16-19, `HomeMenuView` (to delete) :21-25, `osc_toggle` :27-38, `menu_toggle` :40-51.
- `<source: app/src/main/AndroidManifest.xml>` — `RPCSXActivity` theme `@android:style/Theme.NoTitleBar.Fullscreen`, `sensorLandscape`, `launchMode="singleTask"`, **no `configChanges`** (:38-48); contrast: `MainActivity` declares `configChanges="orientation|screenSize"` (:60).
- `<source: app/src/main/java/com/zenithblue/sambas3/ui/settings/SettingsScreen.kt>` — `AdvancedSettingsScreen(navigateBack, navigateTo, settings: JSONObject, path, isInSplitPane)` (:451-458) is parameterized by a plain `JSONObject` and applies every edit via `RPCSX.instance.settingsSet` with failure surfaced through `AlertDialogQueue.showDialog` (bool :536-551, enum :596-611, uint/int slider :659-676, float :731-746); long-click reset-to-default (:553-574 etc.); root fetch `JSONObject(RPCSX.instance.settingsGet(""))` (:1019).
- `<source: app/src/main/java/com/zenithblue/sambas3/ui/navigation/AppNavHost.kt>` — `AlertDialogQueue.AlertDialog()` is hosted **only here** (:125), i.e. only in MainActivity's composition — in-game dialogs would never render today.
- `<source: app/src/main/java/com/zenithblue/sambas3/ui/settings/AdvancedSettingsNav.kt>` — `getNestedSettings(root, path)` walks `@@` segments (:68-77); `isSettingsFolder` (:80-81); route encoding helpers (:8-63).
- `<source: app/src/main/java/com/zenithblue/sambas3/GameRepository.kt>` — `GameInfo(val path, val name?, val iconPath?, val gameFlags)` (:24-30): **no title-id field exists**; identity is `path` (install dir from `getDirInstallPath`, e.g. `dev_hdd0/game/<TITLE_ID>/…`).
- `<source: app/src/main/java/com/zenithblue/sambas3/utils/FileUtil.kt>` — PARAM.SFO is opened only to hand an fd to engine `getDirInstallPath` (:52-59); no Kotlin SFO parser exists.
- `<source: app/src/main/java/com/zenithblue/sambas3/overlay/PadOverlay.kt>` — `onMenuRequestedFromPad` (:98), PS-edge trigger (:373-375), injector couplings (:356 `shouldAcceptOverlayTouch(menuMode, PadInputInjector.isActive())`, :394 `shouldSuppressOverlayPadPush(PadInputInjector.isActive())`), `setMenuMode(on)` (:519); touch listener always returns true (consumes all touches, :356-358, :424).
- `<source: app/src/main/java/com/zenithblue/sambas3/overlay/OverlayTouchPolicy.kt>` — `MENU_DIM_ALPHA = 0.35f` (:4), injector-parameterized predicates (:13-18).
- `<source: app/src/main/java/com/zenithblue/sambas3/dialogs/AlertDialogQueue.kt>` — `showDialog(title, message, onConfirm, onDismiss, confirmText, dismissText)` (:35-43), `AlertDialog()` renderer (:54).
- `<source: app/src/main/java/com/zenithblue/sambas3/RPCSXTheme.kt>` — `RPCSXColors` gold-on-navy palette (:19-37), `darkColorScheme` (:39-55), `RPCSXTheme` composable hiding system bars (:95-123) — reuse for the overlay host.
- `<source: app/build.gradle.kts>` — compose BOM `2026.02.01` (:143), `material3` (:145), **no material-icons dependency** (:140-161), `viewBinding + compose` (:111-115), `unitTests.isReturnDefaultValues = true` (:126-130), appcompat present (:148).
- `<source: gradle/libs.versions.toml>` — `activity-compose 1.13.0` (:23), `androidx-appcompat 1.7.1` (:31).
- `<source: app/src/main/res/drawable/>` — vector inventory already covering nearly all needed glyphs: `ic_play`, `ic_stop`, `ic_close`, `ic_settings`, `ic_home_menu`, `tune`, `memory`, `gamepad`, `ic_video`, `ic_audio`, `ic_restore`, `ic_search`, `ic_keyboard_arrow_*`, `hard_drive`, `ic_save`, `ic_terminal`, `ic_wifi`. **Correction (review F6):** `cross.png` and `circle.png` are PNG bitmaps (only `ic_circle.xml` is a vector); they are used solely by `ControllerHintStrip` hints and are NOT part of the in-game menu icon set. The "XML vector only / no emoji" claim is scoped to drawables selected or newly added by this plan — verified per acceptance grep M8.
- `<source: app/src/main/res/values/strings.xml>` — reusable strings `home_menu_toggle_cd` (:75), `exit_game_confirm_message/yes/no` (:76-78).
- `<source: app/src/test/java/com/zenithblue/sambas3/overlay/>` — `OverlayTouchPolicyTest.kt`, `HomeMenuModelTest.kt`, `PadInputInjectorTest.kt` exist and must be updated/deleted with P5.
- `<source: app/src/main/java/com/zenithblue/sambas3/overlay/OverlayEditActivity.kt>` — `class OverlayEditActivity : ComponentActivity()` (:75): in-repo precedent that a `ComponentActivity` hosts Compose fine with the current fullscreen theme family.

## Current Architecture

```
RPCSXActivity (android.app.Activity, singleTask, sensorLandscape, no configChanges)
 └─ activity_rpcs3.xml (ConstraintLayout)
     ├─ GraphicsFrame        (SurfaceView → engine render surface)
     ├─ PadOverlay           (SurfaceView touch overlay; consumes ALL touches; pushes pad state)
     ├─ HomeMenuView         (custom View list — THE REJECTED pad-simulation menu)
     ├─ osc_toggle           (ImageButton)
     └─ menu_toggle          (ImageButton → openHomeMenu() + Kotlin list + dim)

Kotlin HomeMenuView → HomeMenuModel.buildNavigationPlan → PadInputInjector.inject(padFrames)
   → RPCSX.instance.overlayPadData(...)  ← simulates controller presses to drive the ENGINE's
                                            native overlay menu. USER REJECTED THIS.
```

Engine facts that shape the design:

- `settingsSet` applies live when the node's `from_json` accepts `(value, running=true)` (rpcsx-android.cpp:2612); rejection returns `false` and our UI must surface it.
- There is **no pause()**: the Compose menu necessarily floats above a *running* emulator (same as EmuCoreC, whose `pause()` is a documented no-op stub — Ps3Runtime.kt:249-252). Exit = confirm dialog → `kill()` + `finish()`.
- Per-game persistence cannot live in the engine: `_rpcsx_settingsSet` hardcodes global save (single `g_cfg` tree), so per-title overrides must be recorded **app-side** and replayed around boot (EmuCoreC pattern).

## Affected Components & Dependencies

| Component | Impact |
|---|---|
| `RPCSXActivity` | Major rewrite of UI-host portion; keep input (key/motion), USB, boot-thread logic intact |
| `activity_rpcs3.xml` | Swap `HomeMenuView` → `ComposeView`; keep toggles |
| `AndroidManifest.xml` | Add `configChanges` to `RPCSXActivity` |
| `overlay/PadOverlay.kt` | Remove injector hooks + `onMenuRequestedFromPad`; keep `setMenuMode` dim |
| `overlay/OverlayTouchPolicy.kt` | Drop injector parameters; keep dim constant |
| NEW `ui/ingame/EmulationMenu.kt` | In-game Compose menu + host + exit-confirm |
| NEW `ui/ingame/InGameSettingsPage.kt` | Wrapper embedding `AdvancedSettingsScreen` in-game |
| NEW `ui/games/GameConfigureScreen.kt` | Curated per-game page with tri-state rows |
| NEW `gameconfig/GameSettingsOverrides.kt` (+ encoder) | Override store, replay, title-id resolution |
| `ui/settings/SettingsScreen.kt` | Add optional commit-hook param (default null → zero behavior change) |
| DELETED `overlay/HomeMenuView.kt`, `HomeMenuModel.kt`, `PadInputInjector.kt` | Removal of rejected approach |
| Tests | Delete `HomeMenuModelTest`/`PadInputInjectorTest`; rewrite `OverlayTouchPolicyTest`; add override-store tests |

## Implementation Steps

### P1 — Activity upgrade: ComponentActivity + Compose overlay host
1. Change `class RPCSXActivity : Activity()` → `ComponentActivity` (import `androidx.activity.ComponentActivity`). Rationale: `ComposeView` requires a `ViewTreeLifecycleOwner`/`SavedStateRegistryOwner`; `activity-compose 1.13.0` is already a dependency (libs.versions.toml:23). Avoid `AppCompatActivity` — it would force an AppCompat theme; `ComponentActivity` works under `Theme.NoTitleBar.Fullscreen` (precedent: `OverlayEditActivity` :75).
2. In `activity_rpcs3.xml`, replace the `HomeMenuView` element with:
   ```xml
   <androidx.compose.ui.platform.ComposeView
       android:id="@+id/ingameOverlay"
       android:layout_width="match_parent"
       android:layout_height="match_parent"
       android:visibility="gone" />
   ```
   placed **after** `PadOverlay` so it z-orders above it. Set `elevation`/`translationZ` ≥ PadOverlay in code if needed (EmuCoreC uses 64f, Emulator.kt:206-207).
3. Bind the overlay in `onCreate`: `binding.ingameOverlay.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)` and `setContent { RPCSXTheme { EmulationOverlayHost(...) } }` (mirror EmuCoreC Emulator.kt:204-216). Keep visibility GONE while menu closed (empty composition costs nothing measurable; setting content once avoids recomposition churn).
4. Manifest: add `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|uiMode"` to `.RPCSXActivity` (manifest:38-48) so rotation/locale events never recreate the activity mid-emulation (matches `MainActivity`'s precedent at :60 and prevents loss of the native surface + boot thread; the v1 "dropped on recreation" limitation comment at RPCSXActivity.kt:40-41 disappears with the old stack anyway).
5. Preserve fullscreen/insets exactly as today (`enableFullScreenImmersive` RPCSXActivity.kt:341-353; `applyInsetsToPadOverlay` :355-368). The Compose host draws edge-to-edge; scrim and panels handle their own safe-area padding via `Modifier.windowInsetsPadding` where visible.
6. Keep `osc_toggle` behavior byte-for-byte (RPCSXActivity.kt:59-62).

### P2 — In-game Compose menu (`ui/ingame/EmulationMenu.kt`)
1. Define an in-overlay navigation state (owned by `RPCSXActivity` as `mutableStateOf`):
   ```kotlin
   enum class InGamePage { Closed, Menu, GlobalSettings, ConfigureGame }
   ```
2. `EmulationOverlayHost(page: InGamePage, callbacks...)`:
   - `Closed` → emits nothing (ComposeView stays GONE via activity-side flag).
   - Otherwise → full-screen `Box` with a scrim: `Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.55f)).pointerInput(Unit){ detectTapGestures { onPageRequest(Closed) } }` — the scrim sits above `PadOverlay`, so game touch input is consumed while any page is open (no reliance on PadOverlay gating).
   - Menu panel: Material3 `Surface` (rounded 20.dp, `RPCSXColors.surfaceElevated`, 1.dp `outlineVariant` border, shadowElevation ~16dp — visual grammar of `EmulationGameMenu`, EmulationMenu.kt:209-242, recolored to our palette RPCSXTheme.kt:19-37), width ~min(420dp, 92%), vertically centered, monospace uppercase header showing the running game's friendly name — resolve via `GameRepository.find(path)?.info.name.value`, falling back to the TITLE_ID-shaped last path segment, and only then the raw string (`RPCSX.activeGame.value` is a gamePath, RPCSXActivity.kt:118 — never show a storage path as the title; review note N8).
   - Rows (each: leading `Icon(painterResource(...))` + label + chevron `ic_keyboard_arrow_right`; minimum 56dp height):
     - Resume → `ic_play` → close (page=Closed).
     - Configure Game → `tune` → page=ConfigureGame.
     - Global Settings → `ic_settings` → page=GlobalSettings.
     - Core Home Menu → `ic_home_menu` → close page, then `RPCSX.instance.openHomeMenu()` (engine draws its own menu; guard `state == Running`).
     - Exit Game → `ic_stop` → in-panel confirm dialog (Material3 `AlertDialog`) reusing strings `exit_game_confirm_message/yes/no` (strings.xml:76-78) → `RPCSX.instance.kill()` + `finishAffinity()-safe finish()` (mirror EmuCoreC `exitEmulation`, Emulator.kt:282-288).
3. Open/close triggers:
   - `menu_toggle` click → toggle page Menu↔Closed (replace the old injection-based handler RPCSXActivity.kt:64-77; keep the `Running` state guard).
   - Back gesture: register `OnBackPressedCallback(enabled = page != Closed)` via `onBackPressedDispatcher.addCallback` — closing page instead of finishing activity; disabled when closed so system back behaves as before. (EmuCoreC routes back similarly via a handler, Emulator.kt:300-305.)
   - **PS glass button stays native-engine**: delete `onMenuRequestedFromPad` plumbing entirely (PadOverlay.kt:98, :373-375). The engine already opens its own home menu on PS press (comment RPCSXActivity.kt:79-80); our Compose menu never fakes it.
4. While any page is open, call `binding.padOverlay.setMenuMode(true/false)` purely for the 0.35 dim + floating-stick suppression (OverlayTouchPolicy.kt:4-9) — visual depth only; correctness comes from the Compose scrim.

### P3 — In-game Global Settings page (`ui/ingame/InGameSettingsPage.kt`)
1. Load the tree off the main thread once per open: `LaunchedEffect(Unit) { withContext(Dispatchers.IO) { JSONObject(RPCSX.instance.settingsGet("")) } }` (same call as SettingsScreen.kt:1019; full-tree dump(4) is what the main settings screen already pays — acceptable, cached in `remember`).
2. Render `AdvancedSettingsScreen(navigateBack = { page = Menu }, navigateTo = { /* push onto an internal path stack, see step 4 */ }, settings = tree, path = "", isInSplitPane = false)` — the component already implements switch/slider/list editors with live `settingsSet` and failure dialogs (SettingsScreen.kt:524-775). Do NOT duplicate preference components.
3. **Host dialogs in-game with per-host suppression scoping (review-pass2 MAJOR-1 — NORMATIVE approach (b))**: `AlertDialogQueue` is a singleton object holding ONE shared `mutableStateListOf` queue (AlertDialogQueue.kt:32-59), and `MainActivity`'s AppNavHost host (AppNavHost.kt:125) stays composed-but-stopped beneath `singleTask` RPCSXActivity. Normative design: add a `@Volatile var hostsSuppressed: Boolean` flag to the queue object (set `true` in `RPCSXActivity.onCreate`, `false` in `onDestroy`) **and** scope the gate per call site via a renderer parameter: `@Composable fun AlertDialog(respectHostSuppression: Boolean = true)` which returns early only when `respectHostSuppression && hostsSuppressed`. The launcher call site keeps the default (`true`) so it renders nothing while the emulation UI is frontmost; **InGameSettingsPage's in-overlay host passes `respectHostSuppression = false`**, so engine-rejection error dialogs always render during gameplay regardless of the flag — this is what satisfies P3 step 5's failure path, risk R2's mitigation, manual M2, and the acceptance criterion "failed `settingsSet` produces a visible dialog during gameplay". (PASS-2's unconditional early-return would have suppressed the in-game host too, since the flag is true for the activity's entire lifetime.) **Leftover-queue contract unchanged:** entries enqueued during emulation — e.g. the boot-failure dialog fired right before `finish()` (RPCSXActivity.kt:122-127) — are intentionally NOT cleared on kill()/finish(); once the flag clears in onDestroy, the launcher host renders leftovers after MainActivity resumes.
4. Sub-navigation: maintain `snapshotBackstack = mutableStateListOf("")` inside the wrapper; `navigateTo` pushes normalized advanced-settings subpaths (`normalizeAdvancedSettingsPath` + `getNestedSettings`, AdvancedSettingsNav.kt:48-77) instead of using NavHost; `navigateBack` pops; back gesture pops the stack before closing the page (integrate with P2's `OnBackPressedCallback` priority).
5. Restart-required semantics: nodes whose `from_json` rejects while running simply fail `settingsSet` → existing red error dialog appears (pattern preserved). No silent data loss; the "*" suffix marks modified-vs-default rows already (SettingsScreen.kt:531).
6. Exclude app-level entries that make no sense in-game by starting the in-game tree at the engine categories users tune per-session (Video/Core/Audio/Input-Output/System) — implemented by passing `settings = getNestedSettings(tree, "")` unchanged but intercepting `navigateTo("users"/"drivers"/"logs"/…)`-style non-advanced routes with a toast/snackbar "Available from the launcher", since `AdvancedSettingsScreen` only ever navigates `settings@@…` routes internally (its `navigateTo` receives those strings, SettingsScreen.kt:519).

### P4 — Per-game "Configure Game" page + boot replay
1. **New `gameconfig/SettingsValueCodec.kt`** (pure Kotlin, **org.json-free — NORMATIVE, review F1**): encode/decode per node-type exactly like EmuCoreC's collector — bool → `"true"/"false"`, int/uint/float → raw digits, enum/string → hand-rolled `quoteCfgString(value)` implementing JSON string escaping (`\`, `"`, `\n`, `\r`, `\t`, control chars < 0x20 as `\u00XX`) producing output accepted by nlohmann's parser and byte-compatible with `JSONObject.quote` for all engine values (parity target: Ps3CoreSettingOverrides.kt:256-275). **Chosen approach of the two offered by review F1: dependency-free escaping — NO `testImplementation("org.json:json")` is added and app/build.gradle.kts is untouched**, because android.jar stubs make org.json unusable under `unitTests.isReturnDefaultValues = true` (app/build.gradle.kts:126-130) and no current test touches org.json (:152). Consequence: the codec API takes primitives, not org.json types — `encodedFromNode(spec: SettingNodeSpec(type: String, min: String?, max: String?, default: String), newValue: String): String` and `decodeToDisplay(encoded: String): String`; UI callers extract those fields from the engine tree JSONObject on the Compose side. The codec also hosts `encodeOverrideMap(Map<String,String>): String` / `decodeOverrideMap(String): Map<String,String>` (hand-built flat `{"path":<value>,…}` with escaped keys AND values), so `GameSettingsOverrides` persistence is org.json-free too and every gameconfig JVM test runs without the NDK or any new dependency.
2. **New `gameconfig/GameSettingsOverrides.kt`** (object, modeled on Ps3CoreSettingOverrides):
   - SharedPreferences file `"sambas3_game_overrides"`; keys `global`, `baseline`, `game.<TITLE_ID>` (:16-19 analog). Tier maps persist via `SettingsValueCodec.encodeOverrideMap/decodeOverrideMap` (org.json-free, see step 1); JVM tests inject an in-memory prefs seam + fake setter, so no test ever constructs an org.json type.
   - `recordGlobal(ctx, path, encoded)`, `recordGame(ctx, titleId, path, encoded, previousEncoded)` capturing baseline (:59-89 analog), `clearGame`, `gameOverrides(ctx, titleId)`, `resolvedGlobalValues(ctx)`.
   - `clearGameSetting(ctx, titleId, path, fallbackEncoded): Boolean` — deletes the row and immediately re-applies the global/baseline/default value via `RPCSX.instance.settingsSet` (:149-171 analog).
   - `applyForGame(ctx, titleIdOrNull)` — ordered replay: built-in defaults → baseline → global → per-title, calling `settingsSet` per entry and logging rejections (:197-219 analog). A `setter: (path, value) -> Boolean = { p,v -> RPCSX.instance.settingsSet(p,v) }` **constructor-default parameter** makes the replay order testable on the JVM without the NDK.
   - `applyTitleTier(ctx, titleId)` — replays **only** the per-title tier with the same injected setter; used exclusively by post-boot learning so no restart-required node is written while Running (review F5).
   - Title-id resolution helper `resolveTitleId(gamePath: String): String?` — returns last path segment when it matches `[A-Z0-9_.-]{3,64}` (install dirs are `dev_hdd0/game/<TITLE_ID>`, FileUtil.kt:58-63 + GameRepository path identity); plus a persisted `path→titleId` learning map filled post-boot (below). Normatively (review-pass2 minor), `resolveTitleId` consults the learning map whenever the raw segment is not TITLE_ID-shaped, and `applyForGame(ctx, resolveTitleId(gamePath))` therefore resolves learned titles pre-boot on subsequent launches — giving them the full ladder including the title tier from the first boot after they were learned.
3. **Recording hook in `AdvancedSettingsScreen` (review F2 — NORMATIVE: fire the hook on reset branches too)**: add optional param `onValueCommitted: ((path: String, value: String) -> Unit)? = null`; invoke it after EVERY successful engine mutation — all **8** sites: the 4 edit branches (bool :548-551, enum :608-611, uint/int :671-675, float :743-746) **and the 4 long-click reset branches (bool :558-572, enum :618-633, uint/int :687-702, float :757-772)**, where reset passes the **encoded default** (`def.toString()` / `"\"" + def + "\""`) so the store can never hold a stale value that boot replay would resurrect. The alternative offered by review (disabling long-click inside in-game wrappers only) is rejected: it silently removes a discoverable gesture from the reused screen. Launcher usage passes null → identical behavior today. In-game wrappers pass:
   - GlobalSettings page → `{ p, v -> GameSettingsOverrides.recordGlobal(ctx, p, v) }`
   - ConfigureGame page → its curated editors call `settingsSet` directly then `recordGame(ctx, titleId, p, v, previousEncodedForBaseline)`; this page does NOT embed `AdvancedSettingsScreen`, so no long-click-reset surface exists there — its own reset-row calls `clearGameSetting`, which already keeps the store consistent.
4. **Curated Configure Game page** (`ui/games/GameConfigureScreen.kt`): fixed sections built from the live tree (so variants/min/max come from the engine, not hardcoded lists):
   - Video: Resolution/Scaling (`Video@@Resolution`, `@@Aspect ratio`, `@@Anisotropic Filter`, `@@MSAA`), Shader mode (`Video@@Shader Mode`), Frame limit (`Video@@Frame limit`), Read/Write Color Buffers, VSync.
   - Core: `Core@@PPU Decoder`-adjacent LLVM threads (`Core@@Max LLVM Compile Threads`), SPU settings present in tree.
   - Audio: master volume / buffer nodes present in tree.
   - Each row = tri-state chip row: `USE GLOBAL` (renders current effective value, muted) vs `OVERRIDE` (opens the existing `SingleSelectionDialog`/`SliderPreference` editor bound to that node); overridden rows tint `primary` with a small `ic_restore` trailing action = reset-row (calls `clearGameSetting`, Ps3CoreSettingsSection.kt:177-195 analog); toolbar overflow = Reset All (confirm → `clearGame` + re-apply globals live).
   - Effective-value computation mirrors the layering in Ps3CoreSettingsSection.kt:130-137: game override > global > engine value.
5. **Boot replay timing** (in `RPCSXActivity.bootThread`, RPCSXActivity.kt:95-128):
   - **Pre-boot:** right before `RPCSX.boot(gamePath)` call `GameSettingsOverrides.applyForGame(ctx, resolveTitleId(gamePath))` — full ladder defaults → baseline → global → per-title, executed while `Emu.IsStopped()` so restart-required nodes accept (`from_json(value, running=false)`, rpcsx-android.cpp:2612; EmuCoreC applies before boot, Ps3Runtime.kt:229; adapted because our repository has no title-id column, GameRepository.kt:24-30).
   - **Post-boot learning — title tier ONLY (review F5):** after successful boot, poll `RPCSX.instance.getTitleId()` every 250 ms up to 10 s (valid only once the game is up — RPCSX.kt:95, rpcsx-android.cpp:1871; EmuCoreC reads it at exactly this point, Emulator.kt:181-184). On first non-blank value: persist the `path→titleId` learning entry, then replay **only the per-title tier** via a dedicated `GameSettingsOverrides.applyTitleTier(ctx, titleId)` — never re-run defaults/baseline/global while Running, because restart-required nodes reject with `from_json(value, running=true)` (:2612-2616) and those tiers were already applied pre-boot. Restart-required nodes learned late are recorded to the store now and take effect on the NEXT launch (applied pre-boot while Stopped). When the game path was already TITLE_ID-shaped, the pre-boot ladder applied this tier too, so `applyTitleTier` re-applies identical values while Running — benign rejection log noise for restart-required nodes, expected and ignored (review-pass2 minor).
   - **Threading (review note N9):** all replay `settingsSet` calls marshal onto `bootThread` (single serial context); interactive UI sets keep running on the Android main thread exactly like today's launcher settings. Documented assumption: `g_cfg` is written by one thread at a time; engine-side locking is out of scope for this plan.
6. **Launcher entry point (rewritten, review F7)**: long-press on a game row in `GamesScreen` opens a bottom sheet with a "Configure Game" item. The item is **enabled if and only if the engine is initialized AND idle: `RPCSX.activeLibrary != null && RPCSX.getState() == EmulatorState.Stopped`** — reading or editing the config tree requires a live initialized engine (`settingsGet`/`settingsSet` operate on `g_cfg`, populated by `RPCSX.initialize()` at MainActivity startup, MainActivity.kt:53-61). When the gate fails, the item renders disabled with explanatory description text. When enabled, it hosts `GameConfigureScreen` in an inline `ModalBottomSheet` against `JSONObject(RPCSX.instance.settingsGet(""))`; edits apply to the idle tree immediately and record the per-title tier for next boot (EmuCoreC's out-of-game editing model — Ps3CoreSettingsSection `scope = Game` outside emulation). No new activity; scope stays contained.

### P5 — Remove the pad-simulation stack (recommended: full deletion, user explicitly rejected it)
1. **Delete files**: `overlay/HomeMenuView.kt` (360 L), `overlay/HomeMenuModel.kt` (86 L), `overlay/PadInputInjector.kt` (135 L), plus tests `HomeMenuModelTest.kt`, `PadInputInjectorTest.kt`.
2. **RPCSXActivity**: strip imports (:21-26), `menuModeOn/trackedDepth/padInputInjector` fields (:40-49), entire "Kotlin Home Menu UI wiring" block (:131-215), `padInputInjector.cancel()` in onDestroy (:219). Replaced by P2 state machine.
3. **activity_rpcs3.xml**: `HomeMenuView` removed in P1 step 2. `menu_toggle` icon stays `@drawable/ic_home_menu` (still the best glyph; drawable survives).
4. **PadOverlay.kt**: delete `onMenuRequestedFromPad` (:98), PS-edge block (:373-375, `psWasDown`), and the injector terms at :356 and :394. The :356-358 early-return keeps its exact shape but calls the 1-arg predicate: `if (!OverlayTouchPolicy.shouldAcceptOverlayTouch(menuMode)) return@setOnTouchListener true`; :394 collapses to an unconditional `overlayPadData(...)` push. Keep `setMenuMode` (:519) and the dim draw path (:314-320).
5. **OverlayTouchPolicy.kt** rewrite (review F4 — modal gate retained as a named 1-arg predicate):
   ```kotlin
   object OverlayTouchPolicy {
       const val MENU_DIM_ALPHA = 0.35f

       fun shouldHandleFloatingSticks(isMenuMode: Boolean): Boolean = !isMenuMode
       fun shouldSpawnFloatingStick(isMenuMode: Boolean): Boolean = !isMenuMode

       // Menu-mode modal gate: PadOverlay's touch listener consumes EVERYTHING while
       // a Compose page is open (early-return at PadOverlay.kt:356-358), belt-and-
       // braces beneath the Compose scrim in case a pointer ever misses it.
       fun shouldAcceptOverlayTouch(isMenuMode: Boolean): Boolean = !isMenuMode
   }
   ```
   Injector-parameterized functions (:13-18) are deleted; the Compose scrim is the primary modal blocker, this predicate is the fallback.
6. **OverlayTouchPolicyTest.kt**: update to the new signature set — assert `shouldAcceptOverlayTouch(false) == true` (gameplay proceeds to button handling), `shouldAcceptOverlayTouch(true) == false` (menu-open consume-all), both stick predicates, and the unchanged dim constant (drop injector cases).
7. Acceptance sweep: `grep -rn "PadInputInjector\|HomeMenuView\|HomeMenuModel\|onMenuRequestedFromPad" app/src/main app/src/test` → zero hits.

### P6 — Icons & polish (NO emoji anywhere)
1. Icon mapping (all existing `res/drawable` XML vectors, rendered via `painterResource` — same mechanism SettingsScreen uses, SettingsScreen.kt:169-173): play=`ic_play`, exit=`ic_stop`, settings=`ic_settings`, configure=`tune`, engine-menu=`ic_home_menu`, close/back=`ic_close`/`ic_keyboard_arrow_left`, forward=`ic_keyboard_arrow_right`, reset=`ic_restore`, search=`ic_search`, video=`ic_video`, cpu=`memory`, audio=`ic_audio`, storage=`hard_drive`.
2. Add at most ONE new vector if product wants a distinct "power/quit": `ic_power.xml` (Material `power_settings_new` path data) — hand-written vector drawable, EmuCoreC pattern of bespoke `ic_controller_*.xml` vectors. Otherwise reuse `ic_stop`.
3. Hard rule for reviewers/workers: no emoji/codepoint glyphs in any new Kotlin/XML string or icon slot; icons come exclusively from `painterResource(R.drawable.*)`. Verified by acceptance grep below.
4. Strings: add `ingame_menu_title`, `configure_game`, `use_global`, `override_value`, `reset_row`, `reset_all_game`, `setting_available_in_launcher` to strings.xml (English base; follow existing file conventions).

## File-Level Change Map

| File | Change | Rationale |
|---|---|---|
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` | MODIFY — ComponentActivity, Compose host binding, menu state machine, back dispatcher, pre/post-boot override replay, strip injection wiring | P1/P2/P4/P5 |
| `app/src/main/res/layout/activity_rpcs3.xml` | MODIFY — `HomeMenuView` → `ComposeView ingameOverlay` | P1 |
| `app/src/main/AndroidManifest.xml` | MODIFY — add `configChanges` to `.RPCSXActivity` | P1 (prevent recreation killing emulation) |
| `app/src/main/java/com/zenithblue/sambas3/ui/ingame/EmulationMenu.kt` | NEW — overlay host, menu panel, exit confirm | P2 |
| `app/src/main/java/com/zenithblue/sambas3/ui/ingame/InGameSettingsPage.kt` | NEW — full-tree load + `AdvancedSettingsScreen` embedding + internal backstack + `AlertDialogQueue.AlertDialog(respectHostSuppression = false)` host | P3 |
| `app/src/main/java/com/zenithblue/sambas3/dialogs/AlertDialogQueue.kt` | MODIFY — add `@Volatile hostsSuppressed` flag and per-host renderer param `AlertDialog(respectHostSuppression: Boolean = true)`; launcher call site (AppNavHost.kt:125) keeps default | P3 (review-pass2 MAJOR-1) |
| `app/src/main/java/com/zenithblue/sambas3/ui/games/GameConfigureScreen.kt` | NEW — curated tri-state per-game page | P4 |
| `app/src/main/java/com/zenithblue/sambas3/gameconfig/GameSettingsOverrides.kt` | NEW — override store + replay + title-id resolution | P4 |
| `app/src/main/java/com/zenithblue/sambas3/gameconfig/SettingsValueCodec.kt` | NEW — encode/decode per cfg node type | P4 |
| `app/src/main/java/com/zenithblue/sambas3/ui/settings/SettingsScreen.kt` | MODIFY — optional `onValueCommitted` hook at all **8** mutation sites (4 edit + 4 long-click reset branches, reset records encoded default), default null | P4 recording (review F2) |
| `app/src/main/java/com/zenithblue/sambas3/ui/games/GamesScreen.kt` | MODIFY — long-press "Configure Game" sheet item (gated on engine readiness) | P4 entry |
| `app/src/main/java/com/zenithblue/sambas3/overlay/PadOverlay.kt` | MODIFY — remove injector hooks + PS-edge callback; keep `setMenuMode` dim | P5 |
| `app/src/main/java/com/zenithblue/sambas3/overlay/OverlayTouchPolicy.kt` | MODIFY — drop injector params | P5 |
| `app/src/main/res/values/strings.xml` | MODIFY — new menu/configure strings | P6 |
| `app/src/main/res/drawable/ic_power.xml` | NEW (optional) — vector only | P6 |
| `overlay/HomeMenuView.kt`, `overlay/HomeMenuModel.kt`, `overlay/PadInputInjector.kt` | **DELETE** | P5 — rejected simulation stack |
| `test/.../overlay/HomeMenuModelTest.kt`, `PadInputInjectorTest.kt` | **DELETE** | P5 |
| `test/.../overlay/OverlayTouchPolicyTest.kt` | MODIFY — new signatures | P5 |
| `test/.../gameconfig/GameSettingsOverridesTest.kt`, `SettingsValueCodecTest.kt`, `TitleIdResolverTest.kt` | NEW — JVM tests | Testing |

## Testing Strategy

JVM unit tests (`./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest`; `unitTests.isReturnDefaultValues=true` already set, build.gradle.kts:126-130):

1. `SettingsValueCodecTest` — bool→`true/false`, int→raw, enum→hand-rolled `quoteCfgString` asserted against known-good JSON string literals (nlohmann-compatible escaping incl. `\u00XX` control chars); `encodeOverrideMap/decodeOverrideMap` round-trips with keys containing `"`/`\`; **zero org.json usage anywhere in gameconfig sources or tests** (review F1 resolution — no gradle change).
2. `GameSettingsOverridesTest` — with an injected in-memory prefs seam + injected setter recording calls: assert full-ladder replay order **defaults → baseline → global → title** (order list starts with global-tier paths before title paths); assert the `applyTitleTier` variant emits ONLY title-tier paths (review F5); `recordGame` captures previous value into baseline; `clearGameSetting` removes row and re-applies restored value; `clearGame` wipes only that title.
3. `TitleIdResolverTest` — `resolveTitleId("/…/dev_hdd0/game/BLUS30441/") == "BLUS30441"`; rejects non-matching segments; lowercase normalization.
4. `OverlayTouchPolicyTest` (rewritten) — `shouldAcceptOverlayTouch(false) == true` (gameplay proceeds to button handling), `(true) == false` (menu-open consume-all gate retained per review F4); menu mode suppresses floating sticks; dim constant unchanged.

Build gates: `./gradlew assembleStandardDebug assemblePlaystoreDebug` must pass both flavors (playstore flavor shares all touched code; no flavor-specific branches introduced).

Manual device scenarios (adb install standard debug):
- M1 Launch game → tap `menu_toggle` → Compose menu appears ≤ visually instant, game keeps rendering behind scrim; tap Resume → menu gone, touches reach overlay again.
- M2 Menu → Global Settings → change `Video@@Frame limit` → value persists visually ("*"), engine accepts (no error dialog); back gesture pops page stack first, second back closes menu, third does not quit game.
- M3 Menu → Configure Game → set `Shader Mode` OVERRIDE → row shows OVERRIDE + gold accent; kill app + relaunch SAME game → shader mode applied (verify via performance overlay/logcat `RPCSX-UI` settingsSet logs); launch DIFFERENT game → override NOT applied to it (global tier only).
- M4 Reset-row on an overridden setting → immediate live revert to global value; Reset All clears every row.
- M5 Exit Game → confirm dialog → process ends cleanly back to launcher (no black screen; logcat shows graceful kill).
- M6 PS glass button → engine's own native home menu (unchanged behavior, zero Kotlin pad frames — logcat free of injector tags).
- M7 Rotation/fold event mid-game → activity NOT recreated (surface keeps rendering) thanks to configChanges.
- M8 Emoji/vector audit: `grep -rP "[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{FE0F}]" app/src/main/java/com/zenithblue/sambas3/ui/ingame app/src/main/java/com/zenithblue/sambas3/ui/games/GameConfigureScreen.kt app/src/main/java/com/zenithblue/sambas3/gameconfig` → empty; every icon slot in new UI is `painterResource` of an XML vector drawable (claim scoped to newly added/selected drawables; legacy `cross.png`/`circle.png` used only by ControllerHintStrip are out of scope — review F6).

## Acceptance Criteria

- [ ] `grep -rn "PadInputInjector\|HomeMenuView\|HomeMenuModel\|onMenuRequestedFromPad" app/src/main app/src/test` returns ZERO matches (simulation stack fully gone).
- [ ] In-game menu opens from `menu_toggle` and closes via scrim-tap, Resume row, and back gesture; contains exactly: Resume, Configure Game, Global Settings, Core Home Menu, Exit Game — each with a vector-drawable icon (`painterResource`), no emoji codepoints (M8 grep empty).
- [ ] Global Settings page in-game renders the real engine tree via reused `AdvancedSettingsScreen` and a failed `settingsSet` produces a visible dialog **during gameplay** (dialog hosted in overlay composition).
- [ ] Configure Game page shows tri-state per row (Use Global vs Override), per-row reset restoring the global tier live, and Reset-All; overridden rows visibly distinguished.
- [ ] All **8** `AdvancedSettingsScreen` mutation sites (4 edit + 4 long-click reset) fire `onValueCommitted`; in-game Global Settings long-click reset records the encoded default — stale-store resurrection at boot is impossible (review F2; covered by hook call-site review + codec tests).
- [ ] Post-boot learning replays the per-title tier ONLY (unit-tested via injected setter); restart-required nodes learned late take effect next launch (review F5).
- [ ] gameconfig sources and tests import zero org.json classes (grep `^import org.json` over `gameconfig/` + `app/src/test/.../gameconfig/` → empty) — no new test dependency added (review F1).
- [ ] Replay order global→title proven by unit test asserting recorded setter call order (GameSettingsOverridesTest).
- [ ] Same-title relaunch re-applies per-title overrides (manual M3); different title unaffected.
- [ ] `RPCSXActivity` recreates on no configuration change mid-game (configChanges added; manual M7).
- [ ] Both flavors assemble clean and all four JVM test classes pass.
- [ ] Zero emoji in all newly added files (automated grep in M8).

## Risks & Mitigations (NEW_RISKS)

- **R1 Compose host on plain Activity** — mitigated by choosing `ComponentActivity` (provides lifecycle owners ComposeView needs) and the in-repo `OverlayEditActivity` precedent; risk remains that `Theme.NoTitleBar.Fullscreen` window flags interact with compose insets — mitigation: keep `WindowCompat.setDecorFitsSystemWindows(window,false)` as today and test M1/M7 early (spike within P1).
- **R2 Live-apply limits** — some nodes reject while running (`from_json(value, !Emu.IsStopped())`, rpcsx-android.cpp:2612). Mitigation: failures surface via dialog (existing pattern); docs in-screen footnote string noting restart-required nodes persist for next boot (they DO save globally via SaveSettings even when applied=false? — actually save happens only on accepted set; unaccepted changes are lost, which the dialog communicates).
- **R3 Title-id timing** — `getTitleId()` is blank until boot completes; first-ever launch of a game whose path lacks a title-id-shaped segment may replay the per-title tier late (~seconds after boot). Mitigation: pre-boot global tier always applied + post-boot learning map makes every subsequent launch correct; residual risk documented, acceptable vs adding a full SFO parser (EmuCoreC Ps3SfoParser.kt referenced as future enhancement).
- **R4 SettingsScreen coupling** — `AdvancedSettingsScreen` is self-contained (JSON param + direct `settingsSet`), but the modification adding `onValueCommitted` touches 4 branches; regression risk low (null default), covered by launcher smoke test (main settings still work).
- **R5 Full-tree `settingsGet("")` cost** — same dump(4) the launcher settings already perform (SettingsScreen.kt:1019); loaded once per page-open on IO dispatcher; not per-frame.
- **R6 `configChanges` × `singleTask`** — declaring broad configChanges is standard for game activities (EmuCoreC pattern; MainActivity precedent manifest:60); verify M7 that density changes don't leave stale pixel sizes in PadOverlay (it already re-measures on size change).
- **R7 Menu over running emulator** — no engine pause exists (native-lib.cpp dlsym table; EmuCoreC stub Ps3Runtime.kt:249-252), so the game keeps running under menus. This matches user expectation of an overlay settings UI; Exit is the only hard stop.
- **R8 g_cfg writer-threading assumption (review note N9)** — replay sets marshal onto `bootThread`; interactive sets stay on main. We assume `g_cfg` tolerates single-writer-at-a-time across those contexts without app-side locking; engine-side serialization is out of scope. If device testing shows races (corrupted config dumps), follow-up would funnel ALL `settingsSet` calls through one dedicated executor thread.
- **R9 AlertDialogQueue suppression flag lifecycle** — if a future host forgets to reset `hostsSuppressed` on activity teardown, launcher dialogs would silently stop rendering; mitigated by setting the flag only in RPCSXActivity.onCreate/onDestroy pairs and asserting leftover boot-failure dialogs render after exit (manual M5).

## Handoff to Plan Reviewer

**PASS 2 changes applied (from docs/reviews/native-ingame-menu-settings-plan-review.md):**
1. F1 (MAJOR): normative choice = dependency-free `SettingsValueCodec` (hand-rolled `quoteCfgString` + `encodeOverrideMap/decodeOverrideMap`); NO `testImplementation("org.json:json")`; no build.gradle change; gameconfig + tests are org.json-free by construction.
2. F2 (MAJOR): `onValueCommitted` now covers all 8 mutation sites incl. the 4 long-click reset branches (:558-572, :618-633, :687-702, :757-772) recording the encoded default; alternative "disable long-click in wrappers" rejected; added to change map + acceptance criteria.
3. F3 (MINOR): explicit `hostsSuppressed` gate on AlertDialogQueue set from RPCSXActivity onCreate/onDestroy; leftover-queue contract defined (never cleared on kill/finish; launcher host renders them after resume).
4. F4 (MINOR): 1-arg `shouldAcceptOverlayTouch(isMenuMode) = !isMenuMode` retained at PadOverlay.kt:356-358 early-return; :394 collapses to unconditional push; test expectations specified.
5. F5 (MINOR): post-boot learning replays ONLY the per-title tier via `applyTitleTier`; restart-required nodes land next launch; documented.
6. F6 (MINOR): cross/circle corrected as PNG bitmaps used only by ControllerHintStrip; vector-only claim scoped to newly added/selected drawables (M8 updated).
7. F7 (MINOR): P4 step 6 rewritten — sheet enabled iff `RPCSX.activeLibrary != null && RPCSX.getState() == EmulatorState.Stopped`.
8. N8: menu header resolves friendly name via GameRepository → TITLE_ID-shaped segment → raw path fallback.
9. N9: replay sets marshal onto bootThread; g_cfg single-writer assumption documented (risk R8).
10. **PASS 3** (from review-pass2): MAJOR-1 — suppression gate scoped per host via `AlertDialog(respectHostSuppression: Boolean = true)`; launcher keeps default, InGameSettingsPage host passes `false` so in-game error dialogs render during gameplay (flag pairing + leftover contract unchanged); minor — duplicate title-tier re-application documented as benign noise; minor — learning-map fallback wired into `resolveTitleId`/`applyForGame` for non-path-shaped titles.

**Still needs reviewer validation:** (a) P1 ComponentActivity + ComposeView-in-XML viability under current BOM/theme; (b) P4 replay ordering and `resolveTitleId` heuristic against real install-path shapes in `games.json` on device; (c) P5 deletion completeness incl. rewritten PadOverlay touch-listener and tests; (d) hook signature coverage across all 8 SettingsScreen.kt sites per PASS 2 wording; (e) confirmation that no native C++ change is required.
