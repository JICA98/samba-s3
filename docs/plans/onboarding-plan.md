# Plan: Onboarding Experience and Settings Replay

PASS 1 · ITERATION 3 · Author: @planner · Date: 2026-08-24

Revision goal: add a **Run onboarding again** action in Settings and correct the
first-run/navigation design. This is a plan-only change; implementation stops here.

---

## Outcome

Add a six-page, controller-friendly onboarding flow:

`Welcome → Device Check → Firmware → Graphics Driver → Game Library → Setup Complete`

The flow opens automatically until it is completed once. After completion, users can
open it again from Settings without changing their first-run completion state. A replay
returns to Settings; first-run completion enters the Games screen.

The visual baseline remains the proposed deep-navy/gold CRT treatment, but the
implementation must reuse `RPCSXColors`, `AppTypography`, existing PlayStation glyph
drawables, repository state, and existing setup operations.

---

## Review Findings and Corrections

1. **Do not use a conditional `NavHost.startDestination` for both launch modes.**
   `AppNavHost` currently returns early while `RPCSX.activeLibrary` is null. First-run
   onboarding must therefore be rendered as a top-level gate before that early return.
   Replays can use a normal `onboarding` destination because Settings is only reachable
   after the main graph is available.

2. **First run and replay have different completion/back behavior.**
   First-run FINISH persists completion and reveals a new Games graph. Replay FINISH
   pops back to Settings. Back on page 0 is consumed during required first-run setup but
   exits to Settings during replay.

3. **The Settings action must not reset `has_completed_onboarding`.**
   It only navigates to the replay destination. If a user abandons a replay, the next app
   launch must still skip automatic onboarding.

4. **No fake setup controls.** Any displayed setup CTA must perform its stated action.
   Firmware installation and game-folder selection should call the same
   `PrecompilerService`/`FileUtil` paths already used by `GamesDestination`. Read-only
   device/driver status cards must not be focusable or styled as selectable controls.

5. **Do not broaden this feature into global window or input refactors.**
   `MainActivity` already enables edge-to-edge with
   `WindowCompat.setDecorFitsSystemWindows(window, false)`. The prior proposals to
   change `RPCSXTheme`, `MainActivity`, the manifest predictive-back flag, and
   `PadOverlay` are unrelated to adding onboarding and could alter the entire launcher.
   Keep the current immersive policy and apply `WindowInsets.safeDrawing` within the
   onboarding root.

6. **Keep haptics feature-local.** Follow the existing `PadOverlay` behavior—respect
   `GeneralSettings["haptic_feedback"]`, use `VibratorManager` on API 31+, and guard
   `hasVibrator()`—without refactoring the overlay in this change. Since minSdk is 29,
   pre-26 compatibility branches are unnecessary.

7. **Avoid unnecessary file fragmentation and speculative APIs.** Start with a host,
   page-content file, preference wrapper, and destination/action host. Split further only
   if the resulting files become difficult to maintain.

---

## Verified Integration Points

| Concern | Current code | Planned use |
|---|---|---|
| Startup | `MainActivity` initializes `GeneralSettings` before `AppNavHost` | Read the completion flag at the top of `AppNavHost` |
| Missing core | `AppNavHost` returns `GamesDestination` when `RPCSX.activeLibrary == null` | Place required onboarding before this guard; disable core-dependent setup actions if the library is unavailable |
| Navigation | `AppNavHost` owns `games`, `settings`, `drivers`, and other routes | Add one `onboarding` replay route; do not create a nested/duplicate `NavHost` |
| Settings UI | `SettingsScreen` already receives `navigateTo` and uses `HomePreference` | Add a `HomePreference` that calls `navigateTo(ONBOARDING_ROUTE)` |
| Persistence | `GeneralSettings` wraps `app_prefs` | Store one Boolean completion key via `OnboardingPrefs` |
| Firmware | `GamesDestination` uses `GetContent` and `PrecompilerServiceAction.InstallFirmware` | Reuse the same behavior from the onboarding destination |
| Games | `GamesDestination` uses `OpenDocumentTree`, persistable read permission, and `FileUtil.installPackages` | Reuse the same behavior from the onboarding destination |
| Driver state | Flavor-specific screens and `GpuDriverHelper` already own driver policy | Show the current/recommended status without duplicating flavor-specific install UI |
| Theme/assets | `RPCSXColors`, `AppTypography`, `cross`, `circle`, `ic_folder`, `ic_refresh` exist | Reuse them; do not add a second palette or placeholder Material glyphs |

No native, JNI, CMake, dependency, flavor-policy, manifest, activity, or global theme
changes are required.

---

## Navigation and State Contract

Use a single persisted flag and two explicit entry modes.

```kotlin
const val ONBOARDING_ROUTE = "onboarding"
const val ONBOARDING_PAGE_COUNT = 6

enum class OnboardingEntry { FirstRun, Replay }
```

`OnboardingPrefs.isCompleted()` defaults to `false`; `markCompleted()` writes `true`.
Do not add a version key until there is an actual migration requirement.

At the top of `AppNavHost`, before the library-null return:

```kotlin
var needsFirstRunOnboarding by remember {
    mutableStateOf(!OnboardingPrefs.isCompleted())
}

if (needsFirstRunOnboarding) {
    OnboardingDestination(
        entry = OnboardingEntry.FirstRun,
        onFinished = {
            OnboardingPrefs.markCompleted()
            needsFirstRunOnboarding = false
        },
        onExitAtFirstPage = null,
    )
    return
}
```

This avoids calling `navController.navigate()` before the main graph exists. When the
state flips to false, the existing graph is composed with `games` as its unchanged start
destination.

Register the replay route in the existing graph:

```kotlin
composable(ONBOARDING_ROUTE) {
    OnboardingDestination(
        entry = OnboardingEntry.Replay,
        onFinished = {
            if (!navController.navigateUp()) {
                navController.navigate("settings") { launchSingleTop = true }
            }
        },
        onExitAtFirstPage = {
            if (!navController.navigateUp()) {
                navController.navigate("settings") { launchSingleTop = true }
            }
        },
    )
}
```

Replay never clears or rewrites the completion flag.

Back behavior inside the screen:

- Page 1–5: move to the previous onboarding page.
- Page 0, first run: consume back and remain in onboarding.
- Page 0, replay: invoke `onExitAtFirstPage` and return to Settings.
- FINISH, first run: persist completion and enter Games.
- FINISH, replay: return to Settings with the completion preference unchanged.

---

## Implementation Plan

### 1. Add the onboarding contract and persistence wrapper

- Add `ui/onboarding/OnboardingPrefs.kt` with the route, page count, entry enum, and
  `KEY_COMPLETED = "has_completed_onboarding"`.
- Implement `isCompleted()` and `markCompleted()` over `GeneralSettings`.
- Keep preference access outside previews. `GeneralSettings.init()` already runs before
  production composition in `MainActivity`.

### 2. Build the reusable onboarding UI

- Add `ui/onboarding/OnboardingScreen.kt` for the pager shell, header/progress dots,
  vignette, controller hints, focus movement, BACK/CONTINUE/FINISH buttons, and page-aware
  `BackHandler`.
- Add `ui/onboarding/OnboardingPages.kt` for the six page bodies.
- Use `HorizontalPager(pageCount = { ONBOARDING_PAGE_COUNT })`; keep dots, bounds, and
  finish-page checks tied to the same constant.
- Preserve the proposed navy/gold CRT styling through `RPCSXColors` and `AppTypography`.
  Use `R.drawable.cross`, `R.drawable.circle`, and existing semantic icons.
- Apply `WindowInsets.safeDrawing` to the onboarding content. Do not change the app-wide
  system-bar policy.
- Persist the current pager index with saveable state so recreation returns to the same
  page. Restore it as the pager's initial page.
- Only actionable elements enter the focus order. Focus CONTINUE/FINISH when a page opens;
  controller Enter/D-pad-center and touch clicks must invoke the same callback once.
- Put onboarding-only haptic code in the feature package, respecting the existing haptic
  preference. Do not modify `PadOverlay`.

### 3. Add a destination host for real setup actions

- Add `ui/onboarding/OnboardingDestination.kt` to observe firmware, driver, and game state
  and pass immutable values/callbacks into `OnboardingScreen`.
- Register a firmware picker using `ActivityResultContracts.GetContent`; on a returned URI,
  start `PrecompilerService` with `InstallFirmware`.
- Register a game folder picker using `OpenDocumentTree`; take persistable read permission
  and call `FileUtil.installPackages`.
- Disable these actions with a clear unavailable message when `RPCSX.activeLibrary` is null.
- Show live firmware version/progress, selected driver label, compatible-driver guidance,
  and game count. Do not duplicate custom-driver import/download behavior from the
  flavor-specific driver screens.
- Ensure every CTA is functional. If a page is intentionally informational, render status
  content rather than a button-shaped no-op.

### 4. Wire first-run and replay navigation

- Modify `ui/navigation/AppNavHost.kt` with the first-run gate shown above, before the
  existing library-null branch.
- Keep the existing `NavHost(startDestination = "games")` unchanged after first-run
  completion.
- Add one `composable(ONBOARDING_ROUTE)` for replay.
- Do not construct a second NavHost and do not use `popUpTo(ONBOARDING_ROUTE)` for first run;
  the first-run screen is a composition gate, not a navigation destination.

### 5. Add the Settings replay action

- In `SettingsScreen`, add a stable-key `HomePreference` near the app-level maintenance
  entries:
  - Title: `Run onboarding again`
  - Description: `Review device, firmware, graphics, and game-library setup.`
  - Icon: `R.drawable.ic_refresh`
  - Click: `navigateTo(ONBOARDING_ROUTE)`
  - Focus callback: set `focusedKey = "onboarding"`
- Add an `"onboarding"` branch to `SettingsDetailPane` so wide layouts show matching
  detail text instead of the generic fallback.
- Add all user-facing text to `res/values/strings.xml`.
- Do not show a destructive confirmation dialog and do not modify completion persistence
  when the row is clicked.

### 6. Verify both launch modes and both flavors

Run:

```bash
./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest
./gradlew :app:assembleStandardDebug :app:assemblePlaystoreDebug
```

Perform device checks with touch and a controller:

1. Clear app data: onboarding opens before Games, including when the runtime core is absent.
2. Finish first run: the preference becomes true and Games opens; relaunch skips onboarding.
3. Settings → Run onboarding again: page 0 opens with no preference mutation.
4. Back from replay page 0: Settings is restored; relaunch still skips onboarding.
5. Finish replay: Settings is restored, not Games.
6. Back from pages 1–5 goes to the preceding page in both modes.
7. Firmware and folder CTAs launch the correct pickers and handle cancellation safely.
8. With a missing runtime core, core-dependent CTAs are disabled and FINISH leads to the
   existing missing-library Games state without crashing.
9. Rotate/recreate mid-flow: the current page is retained.
10. Verify focus, click de-duplication, insets, and haptics on gesture and three-button
    navigation.

---

## File-Level Change Map

| File | Change |
|---|---|
| `app/src/main/java/com/zenithblue/sambas3/ui/onboarding/OnboardingPrefs.kt` | New completion/route/entry contract |
| `app/src/main/java/com/zenithblue/sambas3/ui/onboarding/OnboardingScreen.kt` | New reusable pager shell and interaction behavior |
| `app/src/main/java/com/zenithblue/sambas3/ui/onboarding/OnboardingPages.kt` | New six page bodies |
| `app/src/main/java/com/zenithblue/sambas3/ui/onboarding/OnboardingDestination.kt` | New repository state and real setup-action wiring |
| `app/src/main/java/com/zenithblue/sambas3/ui/navigation/AppNavHost.kt` | First-run gate plus replay route |
| `app/src/main/java/com/zenithblue/sambas3/ui/settings/SettingsScreen.kt` | Replay `HomePreference` and wide-pane detail case |
| `app/src/main/res/values/strings.xml` | Onboarding and Settings replay strings |

Explicitly out of scope: `MainActivity.kt`, `RPCSXTheme.kt`, `AndroidManifest.xml`,
`PadOverlay.kt`, native/JNI code, Gradle dependencies, custom-driver policy, and unrelated
Settings cleanup.

---

## Acceptance Criteria

- [ ] A fresh install shows all six onboarding pages before the main graph, even if the
  runtime core is missing.
- [ ] FINISH on first run writes `has_completed_onboarding = true`, opens Games, and the
  next launch skips automatic onboarding.
- [ ] Settings contains a controller-focusable **Run onboarding again** row using the
  existing Settings visual language.
- [ ] Opening replay does not clear/change the completion flag.
- [ ] Replay Back on page 0 and replay FINISH both return to Settings; first-run Back on
  page 0 does not bypass required onboarding.
- [ ] Page progress survives activity recreation.
- [ ] Firmware and game-folder CTAs perform their stated actions; no visible control is a
  no-op.
- [ ] Read-only status cards are not placed in the action focus order.
- [ ] The feature uses `RPCSXColors`, `AppTypography`, existing glyphs, and safe-drawing
  insets without global theme/window changes.
- [ ] Both Standard and Play Store debug builds and their unit-test suites pass.

---

## Main Risks

- **Replay accidentally becomes first run:** prevented by never clearing the persisted flag
  from Settings and by passing an explicit `OnboardingEntry`.
- **Returning to the wrong screen:** first run is a composition gate; replay is a graph
  destination whose normal predecessor is Settings.
- **Missing runtime core:** onboarding remains renderable, while core-dependent actions are
  disabled and the existing Games missing-library state remains authoritative.
- **Flavor drift in driver UI:** onboarding reports status/guidance only and leaves driver
  installation/selection policy to the existing flavor-specific screens.
- **Duplicate setup logic:** the destination calls the same service/helper operations as
  Games; implementation should extract a small shared helper only if duplication is more
  than launcher registration boilerplate.
- **Configuration recreation:** save and restore the page index instead of relying only on
  `rememberPagerState`.

Implementation is intentionally not started by this plan revision.
