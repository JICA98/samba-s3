# Plan Review: native-ingame-menu-settings — PASS 1 ITER 1

## Verdict: REVISE
## Counts: CRITICAL 0 MAJOR 2 MINOR 5 SUGGESTIONS 2

## Verified Plan Claims (evidence re-checked, not trusted)

- P1: `RPCSXActivity : Activity()` confirmed (RPCSXActivity.kt:31); `OverlayEditActivity : ComponentActivity()` + `setContent` under the same fullscreen theme confirmed (overlay/OverlayEditActivity.kt:75-84) — in-repo precedent holds. `activity-compose 1.13.0` (libs.versions.toml:23), compose BOM/material3 (app/build.gradle.kts:143-145). Manifest has no `configChanges` on RPCSXActivity (:38-48); MainActivity precedent :60.
- P2: `kill` RPCSX.kt:90, `openHomeMenu` :92, `settingsGet/Set` :87-88, `getTitleId` :95 (zero callers repo-wide, verified). activity_rpcs3.xml layout ids/z-order match plan (GraphicsFrame :11-14, PadOverlay :16-19, HomeMenuView :21-25, toggles :27-51).
- P3: `AdvancedSettingsScreen(navigateBack, navigateTo, settings: JSONObject, path, isInSplitPane)` at SettingsScreen.kt:451-458 is JSON-parameterized and reusable; all four live-commit sites exist (bool else :548-551, enum :608-611, int/uint :671-676, float :743-746); `SingleSelectionDialog` used at :586; `"settings$itemPath"` navigation :519; "*" marker :531. `AlertDialogQueue.AlertDialog()` hosted only at AppNavHost.kt:125.
- P4: GameInfo has no title-id field (GameRepository.kt:24-30); FileUtil PARAM.SFO→getDirInstallPath :52-59. EmuCoreC references verified at real paths (`core/Ps3CoreSettingOverrides.kt`, `core/Ps3Runtime.kt`, `ui/settings/Ps3CoreSettingsSection.kt`, `core/ps3/Emulator.kt`): replay order defaults→baseline→global→title (:197-212), baseline first-seen semantics (:72-89), boot-before-apply (Ps3Runtime.kt:226-231), pause() no-op (:248-252). `_rpcsx_settingsSet` `from_json(value, !Emu.IsStopped())` + save-only-on-accept confirmed (rpcsx-android.cpp:2594-2620).
- P5: deletion sweep complete — injector/menu symbols appear only in: the 3 files deleted, their 2 tests, activity_rpcs3.xml, RPCSXActivity.kt, PadOverlay.kt (:98, :356, :373-375, :394, :519), OverlayTouchPolicy.kt. All covered by plan's change map; no orphaned R.string in deleted files. `menu_toggle` retained with `ic_home_menu`.
- P6: icon inventory verified; note exception below (cross/circle are PNGs).

## Findings

### [MAJOR] JVM tests cannot exercise org.json — codec/override tests will fail as specified
- Location: docs/plans/native-ingame-menu-settings-plan.md §Testing items 1-2; SettingsValueCodec uses `JSONObject.quote`
- Problem: android.jar stubs org.json in local unit tests; `unitTests.isReturnDefaultValues=true` makes `JSONObject.quote/put/get*` return null/defaults instead of real behavior. No `testImplementation("org.json:json")` exists and no current test touches JSONObject (verified: only `testImplementation(libs.junit)` app/build.gradle.kts:152; rg over app/src/test shows zero org.json usage).
- Evidence: app/build.gradle.kts:126-130,152; EmuCoreC codec parity target uses JSONObject.quote (Ps3CoreSettingOverrides.kt:269-274).
- Impact: `SettingsValueCodecTest` / `GameSettingsOverridesTest` fail or silently assert nothing; worker must deviate from plan unguided.
- Required change: add `testImplementation("org.json:json:<version>")` to app/build.gradle.kts in P4 scope (or make codec escaping dependency-free and say so explicitly).

### [MAJOR] `onValueCommitted` hook enumerates only the 4 edit branches; the 4 long-click reset branches also mutate the engine and are missed
- Location: plan P4 step 3 vs SettingsScreen.kt reset branches :558-572 (bool), :618-633 (enum), :687-702 (int/uint), :757-772 (float)
- Problem: long-click reset performs a successful `settingsSet(itemPath, def)` without invoking any commit hook. In-game Global Settings reset → global tier still stores old value → next-boot replay resurrects it. ConfigureGame long-click reset bypasses `clearGameSetting` row removal.
- Evidence: SettingsScreen.kt:553-574, 613-634, 679-703, 749-773.
- Impact: breaks AC "per-row reset restoring the global tier live" via a reachable UI path; silent data resurrection at boot.
- Required change: extend hook to reset branches too (record encoded default to the same tier) OR disable long-click reset inside in-game wrappers; state which.

### [MINOR] AlertDialogQueue double-hosting safety is asserted, not designed
- Location: plan P3 step 3 ("One extra composable call, shared queue"); AppNavHost.kt:125; dialogs/AlertDialogQueue.kt:32-59 (singleton object + mutableStateListOf)
- Problem: MainActivity stays composed (stopped) beneath singleTask RPCSXActivity; both hosts render one shared queue. Safety currently depends on the paused-frame-clock behavior of stopped compositions (stale host renders nothing until ON_RESUME). Plan should state this assumption and/or gate the AppNavHost host while emulation UI is frontmost, and define expected behavior for queue leftovers after kill()+finish().
- Evidence: AppNavHost.kt:125; AlertDialogQueue.kt:33,55-59.
- Impact: low-probability duplicate/dismiss races; undocumented reliance could break with Compose updates.
- Required change: add explicit mitigation note (gate param on host, or document lifecycle-pause reliance + post-finish leftover behavior).

### [MINOR] Rewritten OverlayTouchPolicy drops the menu-mode early-consume predicate PadOverlay still needs
- Location: plan P5 steps 4-5; PadOverlay.kt:356 (`shouldAcceptOverlayTouch(menuMode, …)` early-return), :394 (`shouldSuppressOverlayPadPush`)
- Problem: proposed policy object contains only MENU_DIM_ALPHA + two stick predicates. The touch listener still needs its menu-mode modal gate ("consume everything when menuMode && !isEditing") somewhere; plan says gates "collapse to menu-mode-only forms" but doesn't define them.
- Evidence: PadOverlay.kt:356-358; plan P5 step 5 code block.
- Impact: worker guesses where the gate lives (inline return vs 1-arg predicate); test rewrite target ambiguous.
- Required change: specify e.g. `fun shouldAcceptOverlayTouch(isMenuMode: Boolean) = !isMenuMode` retained (early-consume when false→consume) or an explicit inline `if (menuMode) return@setOnTouchListener true`.

### [MINOR] Post-boot learning replays the full ladder while Running; restart-required nodes then reject
- Location: plan P4 step 5 (post-boot `applyForGame(ctx, titleId)` again); rpcsx-android.cpp:2612 `from_json(value, !Emu.IsStopped())`
- Problem: pre-boot replay runs Stopped (dynamic=false → accepted); post-boot re-application runs Running, so non-dynamic nodes (decoders, shader mode class) reject and late-learned per-title overrides miss this session. Re-applying defaults+baseline+global tiers again is redundant since pre-boot already applied them.
- Evidence: Ps3Runtime.kt:226-231 (apply before boot), rpcsx-android.cpp:2612-2616.
- Impact: noisy rejection logs; first launch of non-path-shaped games misses restart-required per-title values until second launch even though learning map already persisted.
- Required change: post-boot step should apply ONLY the per-title tier (or skip tiers whose sets succeeded pre-boot) and document that restart-required nodes take effect next launch.

### [MINOR] Research citation error: `cross`/`circle` drawables are PNGs, not XML vectors
- Location: plan Research Sources line about res/drawable ("All are XML vector drawables"), listing `cross`, `circle`
- Evidence: app/src/main/res/drawable contains circle.png and cross.png (only ic_circle.xml exists as vector).
- Impact: none today (P6 map doesn't use them), but the "zero emoji / all vectors" audit premise is factually wrong if worker greps drawable types.
- Required change: correct the sentence or drop cross/circle from the list.

### [MINOR] P4 step 6 launcher-entry paragraph is garbled
- Location: plan P4 step 6: "**only when emulation is not running is impossible**"
- Impact: worker misreads the gating intent; concrete gate (`RPCSX.getState()==Stopped && RPCSX.activeLibrary!=null`) is recoverable but buried.
- Required change: rewrite the sentence: engine tree requires initialized library; sheet item enabled iff engine initialized and state==Stopped.

### [SUGGESTION] Menu header source is raw install path
- `RPCSX.activeGame.value` receives `gamePath` (RPCSXActivity.kt:118), so header would print e.g. `/data/.../dev_hdd0/game/BLUS30441/...`. Resolve friendly name via GameRepository lookup or display resolved TITLE_ID.

### [SUGGESTION] Pick one thread for settingsSet during replay
- Pre-boot replay happens on bootThread; UI edits happen on main. Native g_cfg mutation from two threads is presumably mutex-guarded upstream, but marshaling replay through a single dispatcher (or documenting thread-safety assumption) avoids a class of heisenbugs.

## Next Agent: Planner
## Next Action: Apply the two MAJOR required changes (org.json test dependency or dependency-free codec; enumerate/reset-branch hook strategy) and fold the MINOR clarifications into the plan text; resubmit for PASS 2 review. No architectural rework needed — P1-P6 structure, evidence base, deletion sweep, and scope are sound.
