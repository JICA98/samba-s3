# Unified Settings Sub-Pages — Blur, Controller Hints + Mapping, Safe-Area, Top-Bar Unification

Scope: 8 pages reached from Settings grid (screenshots 1–7 + Patch Manager):

| # | Page | File |
|---|------|------|
| 1 | Users | `ui/user/UsersScreen.kt` |
| 2 | Advanced Settings | `ui/settings/SettingsScreen.kt` → `AdvancedSettingsScreen` |
| 3 | Custom GPU Driver | `standard/.../ui/drivers/GpuDriversScreen.kt` + `playstore/.../ui/drivers/GpuDriversScreen.kt` |
| 4 | Controls | `ui/controller/ControllerSettingsScreen.kt` |
| 5 | Performance Monitor | `ui/monitoring/MonitoringSettingsScreen.kt` |
| 6 | Log Monitor | `ui/settings/LogMonitorScreen.kt` |
| 7 | Crash Logs & History | `ui/crash/CrashLogsHistoryScreen.kt` |
| 8 | Patch Manager | `ui/settings/PatchManagerScreen.kt` |

Reference screenshots: Users (plain M3 TopAppBar, renders under left nav/gesture bar), Advanced Settings (good: custom 54dp top bar + `× SELECT | ○ BACK` hint bar), Custom GPU Driver (M3 TopAppBar + `+`), Controls (custom CONTROLS + BACK pill, no hint bar), Performance Monitor (in-content header + M3 TopAppBar, no hint bar), Log Monitor (custom 56dp top bar + bottom action bar + `○ BACK`), Crash Logs (custom 56dp header, no hint bar, renders over bottom nav).

## 1. Problems observed

1. **Top bars differ per page** — M3 `TopAppBar`/`LargeTopAppBar` (Users, GPU standard/playstore, Monitoring, Patch) vs custom 52–56dp rows (Advanced, Log, Crash, Controls, Settings root). Back affordance differs: plain `<` icon vs circled `<` in `Surface(CircleShape, 0x20C9A84C)` vs BACK pill.
2. **No shared blur/ambience** — only Settings root has `AmbientSettingsBackground()` (vertical gradient `#080B14→#0B101E→#060810` + two `.blur(70/80dp)` radial glows). Sub-pages use flat `RPCSXColors.background` / `surfaceElevated`.
3. **Controller hints inconsistent** — Advanced has `cross→Select + circle→Back`; Patch has `cross→Toggle + circle→Back`; Log bottom bar has `circle→Back` only; Users/GPU/Controls/Monitoring/Crash have none.
4. **Controller mapping missing/partial** — Settings root has full D-pad/stick + L1/R1 + A/B/Y handling with focus indices; Advanced sub-pages rely on default Compose focus (no explicit `OnKeyListener`/`onPreviewKeyEvent` for `BUTTON_B→back`); Users/GPU/Monitoring/Crash/Patch/Controls have no gamepad `View.OnKeyListener`.
5. **Safe-area violations** — Users `Scaffold(windowInsetsPadding(navigationBars))` ignores status/cutout; Advanced `AdvancedTopBar` uses `safeDrawing.only(Horizontal)` (draws under status bar/notch); Crash uses `windowInsetsPadding(safeDrawing)` on outer Surface but header is 56dp `surfaceElevated` without status inset separation; screenshots show content running edge-to-edge over left gesture nav + bottom system bar.

## 2. Target design (unified)

### 2.1 `SambaTopBar` (new shared composable, `ui/common/SambaTopBar.kt`)

```
SambaTopBar(
  title: String,            // uppercase, monospace 17sp, gold primary
  iconRes: Int?,            // e.g. tune/gamepad/memory/ic_terminal/ic_restore
  onBack: () -> Unit,
  actions: @Composable RowScope.() -> Unit = {},
  compact: Boolean = false  // 48dp in split-pane, 52dp standalone
)
```

- Layout mirrors Settings-root/Advanced top bar (canonical):
  - `Row fillMaxWidth height(52dp|48dp), background #EE090C16`, bottom 1dp line `#20C9A84C` via `drawBehind`.
  - Insets: `.windowInsetsPadding(WindowInsets.statusBars)` + `.windowInsetsPadding(safeDrawing.only(Horizontal))` + `padding(horizontal 16dp|8dp)` — so bar sits **below notch**, content never under cutout.
  - Left: circled back `Surface(CircleShape, 0x20C9A84C, border 0x35C9A84C, 34dp|30dp)` + `IconButton(ic_keyboard_arrow_left, primary)` + `○` glyph badge (`Surface(3dp, textSecondary 15%)` like Settings root) → communicates `○ = Back` without text.
  - Center-left: icon (22dp, primary) + title (`Monospace Bold 17sp|14sp, letterSpacing 2sp, primary, maxLines 1, weight 1f`).
  - Right: `actions` slot (search, CRASH HISTORY, RESET/DONE, `+`, tabs) — keeps per-page actions, same slot order.
- Replaces: `TopAppBar` in Users/GPU×2/Monitoring/Patch, `TopBar()` in Log, header `Row` in Crash, in-content headers in Controls/Monitoring.

### 2.2 `SambaScreenScaffold` (new shared composable, same file)

```
SambaScreenScaffold(
  title, iconRes, onBack, actions,
  hints: List<Pair<Int,String>>,   // default [cross→Select, circle→Back]
  compact: Boolean = isInSplitPane,
  ambient: Boolean = true,
  content: @Composable (PaddingValues) -> Unit
)
```

- Structure:
  ```
  Box(fillMaxSize) {
    if (ambient) AmbientSettingsBackground()   // hoisted shared version
    Column(fillMaxSize.windowInsetsPadding(safeDrawing)) {
      SambaTopBar(...)
      Box(Modifier.weight(1f)) { content(...) }
      ControllerHintStrip(hints)               // existing, add navigationBars bottom inset
    }
  }
  ```
- `ControllerHintStrip` change: append `.windowInsetsPadding(safeDrawing.only(Bottom))` (keep existing horizontal) so hints sit **above** gesture nav, never under it.
- `AmbientSettingsBackground` hoist: move copy from `SettingsScreen.kt` (private) to `ui/common/SambaAmbient.kt` as public `SambaAmbientBackground()` — identical gradients/blurs. Keep private wrapper in SettingsScreen delegating to it (no visual change).
- Split-pane (`isInSplitPane=true`): `compact=true`, still shows SambaTopBar in `compact` mode? Current pages hide their bar in split pane (Patch/Users/Advanced-compact variants). Decision: **keep content-embedded compact bar** (48dp, no `○` badge? keep badge) so right pane stays labelled; Settings detail pane already labels selection — compact bar is 48dp title-only, acceptable duplication, matches Advanced `compact=true` precedent.

### 2.3 Controller mapping (per page, same scheme)

Global keys handled at screen root via `View.OnKeyListener` + `onPreviewKeyEvent` (mirrors Settings root `navigateGrid` pattern, but lean — rely on Compose focus for D-pad/stick movement, only intercept actions):

| Input | Action |
|-------|--------|
| `BUTTON_A / DPAD_CENTER / ENTER` | click focused (default — no interception needed) |
| `BUTTON_B / BACK` | `navigateBack()` (intercept, return true) |
| `DPAD_UP/DOWN/LEFT/RIGHT`, left-stick/hat (via focus system) | default focus traversal — ensure every row/card/chip is `focusable`/`clickable` and initial `FocusRequester.requestFocus()` on first item |
| Page extras | GPU: `L1/R1` switch Installed/Browse tabs; Controls: `L1/R1` cycle Mapping/Profiles/Advanced; Log: `Y` toggle auto-scroll; Crash: `L1/R1` prev/next session, `Y` share; Patch: `Y` download official; Advanced: `Y` handled? currently none — add `X`→toggle search? keep `Y`→YAML manager parity with Settings root |

- Each screen gets: `val rootFocus = remember { FocusRequester() }`, `Box(...focusRequester(rootFocus).focusable().onPreviewKeyEvent{...})` + `DisposableEffect(LocalView.current)` with `setOnKeyListener` for gamepad `BUTTON_B/BACK` (View listener catches controller buttons that Compose preview may miss — same dual-path as Settings root lines 1861–2022). `LaunchedEffect(Unit) { rootFocus.requestFocus() }`.
- Focus visuals already exist (`SettingsNavCard` selected ring, `FilterChip`, `SwitchPreference`); add `onFocusChanged` → update detail/selection where needed (Crash session list, Advanced categories).

### 2.4 Hints per page (bottom `ControllerHintStrip`)

| Page | Hints |
|------|-------|
| Users | `cross→Select`, `circle→Back` |
| Advanced | keep `cross→Select`, `circle→Back` (+ `triangle→YAML`? Settings root uses `BUTTON_Y→openYamlManager` — add hint `triangle→YAML` to advertise) |
| GPU Driver | `cross→Select`, `L1/R1→Tabs` (use existing L1/R1 glyph drawables if present else text), `circle→Back` |
| Controls | `cross→Select`, `L1/R1→Tabs`, `circle→Back` |
| Performance Monitor | `cross→Toggle`, `circle→Back` |
| Log Monitor | keep `circle→Back`, add `cross→Toggle filter`? minimal: `cross→Select`, `circle→Back` + keep CLEAR/AUTO-SCROLL/SHARE action row above strip |
| Crash Logs | `cross→Open`, `L1/R1→Session`, `circle→Back` |
| Patch Manager | keep `cross→Toggle`, `circle→Back` (+ `triangle→Update` for download-official) |

Glyph drawable ids: `R.drawable.cross/circle/triangle` + `R.drawable.l1/r1`? verify existence; fallback to text badge if missing (check `res/drawable*`).

### 2.5 Safe-area rules

- Never use bare `Scaffold()` or `windowInsetsPadding(navigationBars)` alone.
- Standalone pages: `SambaScreenScaffold` (internally `windowInsetsPadding(safeDrawing)`).
- Content scrolls: `contentPadding` must include `WindowInsets.safeDrawing.asPaddingValues()` bottom where LazyColumn meets hint strip (strip is outside scroll, so just `PaddingValues(bottom=8dp)` suffices).
- Split-pane bodies: wrap content in `.windowInsetsPadding(safeDrawing.only(Horizontal))` (vertical handled by parent detail pane) — matches Advanced `WideAdvancedBody`.
- Verify on tablet (TB336FU, left gesture nav visible in screenshots) + OnePlus/POCO (cutout + bottom nav): no text under cutout, no cards under gesture bar.

## 3. File-by-file changes

1. **NEW `ui/common/SambaTopBar.kt`** — `SambaTopBar`, `SambaScreenScaffold`, per-§2.1–2.2. Imports: `WindowInsets,statusBars,safeDrawing`, `CircleShape`, `drawBehind`, `FocusRequester` optional. Reuse `ControllerHintStrip` from SettingsScreen (move or import — currently top-level in `SettingsScreen.kt`, same package? No: `ui.settings` vs `ui.common`; add import).
2. **NEW `ui/common/SambaAmbient.kt`** — hoisted `SambaAmbientBackground()` (copy of `AmbientSettingsBackground`). `SettingsScreen.kt`: replace private body with call-through.
3. **`ControllerHintStrip`** (`SettingsScreen.kt:172`) — add bottom nav inset: `.windowInsetsPadding(WindowInsets.safeDrawing.only(Bottom))`.
4. **`UsersScreen.kt`** — replace M3 `Scaffold+TopAppBar` with `SambaScreenScaffold(title=users, icon=ic_person, hints=Select/Back)`; wrap `UsersContent` rows with focusable cards (keep RadioButton behavior); add B/BACK interception + initial focus; split-pane path uses compact scaffold instead of bare Column.
5. **`SettingsScreen.kt / AdvancedSettingsScreen`** — replace `AdvancedTopBar` internals with `SambaTopBar` (keep search-field expanding behavior + compact flag); add `Ambient` behind Scaffold content (currently none — Scaffold has no bg); add explicit `BUTTON_B→navigateBack`, `BUTTON_Y→openYamlManager` key handling at root + `triangle→YAML` hint; fix insets: Scaffold `modifier.windowInsetsPadding(safeDrawing)`, body keeps horizontal-only.
6. **`GpuDriversScreen.kt` ×2** — replace `TopAppBar` with `SambaTopBar(title=Custom GPU Driver, icon=memory, actions=import/add)`; wrap body in ambient + hint strip (`Select, L1/R1 Tabs, Back`); add tab-switch key handling (`L1/R1`); playstore variant: same minus import/download actions.
7. **`ControllerSettingsScreen.kt`** — replace in-content CONTROLS header + BACK pill with `SambaTopBar(title=Controls, icon=gamepad)`; keep Mapping/Profiles/Advanced chips as sub-tabs below bar; add ambient + hint strip; add `L1/R1` tab cycling + B→back; keep remap-capture `onPreviewKeyEvent` (merge, don't clobber).
8. **`MonitoringSettingsScreen.kt`** — remove duplicate in-content PERFORMANCE MONITOR header; single `SambaTopBar(title, icon=ic_video, actions=RESET/DONE)`; ambient + hint strip (`Toggle/Back`); B→back (DONE surface stays as action).
9. **`LogMonitorScreen.kt`** — replace local `TopBar()` with `SambaTopBar(title=Log Monitor, icon=ic_terminal, actions=CRASH HISTORY)`; keep `LogBottomBar` action row, append `ControllerHintStrip(Select/Back)` with bottom inset (already has Back — extend); add ambient behind `LogContent`; add B→back + Y→auto-scroll toggle.
10. **`CrashLogsHistoryScreen.kt`** — replace header Row with `SambaTopBar(title=Crash Logs & History, icon=ic_restore, actions=filter chip + delete)`; add ambient + hint strip (`Open, L1/R1 Session, Back`); add session-list key handling (L1/R1 prev/next, B→back) + initial focus on selected card; keep master-detail weights.
11. **`PatchManagerScreen.kt`** — replace `TopAppBar` with `SambaTopBar(title=Patch Manager, icon=tune?, actions=download+import)`; add ambient; keep existing hint strip, extend hints with `triangle→Update`; add B→back + Y→downloadOfficial; fix Scaffold insets to safeDrawing.

## 4. Verification

- `./gradlew :app:assembleStandardDebug :app:assemblePlaystoreDebug` (both GPU variants).
- `./gradlew :app:testStandardDebugUnitTest` (regression).
- On-device (TB336FU + OnePlus 13R): open each of 8 pages, screenshot; assert: title row identical height/style/back-circle across pages; ambient glow visible; hint strip visible above nav bar; `○` returns to Settings; `×` activates focused card; no overlap with cutout/left-nav/bottom-bar; split-pane (tablet landscape) compact bars render.
- Controller: D-pad + left stick move focus, A select, B back on every page; extras (L1/R1/Y) per §2.3.

## 5. Risks / notes

- `ControllerHintStrip` is currently `public` top-level in `SettingsScreen.kt` (`ui.settings`) — importing from `ui.common`/`ui.drivers` is fine (already done in Patch/Log/Crash via import). No move strictly needed.
- `R.drawable.cross/circle` exist (used by Patch/Log/Advanced hints); L1/R1 glyphs — check `res/drawable-nodpi/`; if absent, use text hints.
- Advanced search `SearchBar(expanded=false)` inside custom bar must survive refactor — keep exact block, only swap surrounding Row decorations for SambaTopBar container.
- Controls remap capture consumes key events — action interceptor must yield when `captureTarget != null`.
- Keep `DebugControllerScreen` route untouched (orphaned, still ADB-reachable).

## 6. Implementation status (done)

- NEW `ui/common/SambaAmbient.kt` — `SambaAmbientBackground()` (hoisted; `SettingsScreen.AmbientSettingsBackground` now delegates).
- NEW `ui/common/SambaTopBar.kt` — `SambaTopBar` (52dp/48dp compact, `#EE090C16` + gold hairline, circled back + `○` badge, `statusBars` + horizontal `safeDrawing` insets, actions slot), `SambaScreenScaffold` (ambient + top bar + content + `ControllerHintStrip`; B/BACK via View listener + preview-key; initial focus; `onGamepadKey`, `isBackAllowed`, `showHints`), `SambaSplitBody`.
- `ControllerHintStrip` now also pads `safeDrawing.Bottom` (hints above gesture nav).
- Users / Patch / Log / Crash / Monitoring / Controls / GPU standard+playstore migrated to the scaffold (unified bars, ambient, hints, safe-area, key mapping per §2.3–2.4).
- Advanced: `AdvancedTopBar` non-search mode replaced by `SambaTopBar` (search mode keeps exact SearchBar block in matching container + `statusBars` inset); Scaffold wrapped in ambient + `safeDrawing` + focus/key handling (B/BACK exits search or backs).
- Verified: `assembleStandardDebug`, `assemblePlaystoreDebug`, `testStandardDebugUnitTest` green; installed standard APK on TB336FU (pid alive, no FATAL in logcat).
- Deviations from §3: Advanced kept its own key wrapper instead of full scaffold (search scope); no `triangle→YAML` hint (manager lives in Settings root scope); Crash hint uses `l1→Session` single glyph for L1/R1 pair.
