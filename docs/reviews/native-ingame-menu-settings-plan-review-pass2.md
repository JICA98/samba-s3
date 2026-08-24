# Plan Review: native-ingame-menu-settings — PASS 2 ITER 1

## Verdict: REVISE
## Counts: CRITICAL 0 MAJOR 1 MINOR 2 SUGGESTIONS 0

> Single MAJOR is a NEW contradiction introduced by the F3 fix itself; all 7 pass-1 fixes are
> otherwise present and correct. No previously resolved finding reopened.

## Fix Verification Matrix (all 9 items checked against sources)

| # | Fix | Status | Evidence |
|---|---|---|---|
| F1 | org.json-free codec | ✅ PRESENT | Plan :147 normative dependency-free `quoteCfgString` + `encodeOverrideMap/decodeOverrideMap`, explicit "NO testImplementation(org.json), build.gradle untouched"; AC plan :251 zero-org.json grep over gameconfig sources+tests; Testing item 1 plan :226. Repo confirms only `testImplementation(libs.junit)` at app/build.gradle.kts:152 and `unitTests.isReturnDefaultValues = true` at :129. Codec API takes primitives → no conflict with F2. |
| F2 | hook at all 8 mutation sites | ✅ PRESENT | Exactly 8 `settingsSet` call sites exist in SettingsScreen.kt: 536/558 (bool edit/reset), 596/618 (enum), 659/687 (uint/int), 731/757 (float). Reset branches verified to use `def.toString()` / `"\"" + def + "\""` (encoded default) exactly as plan :155 claims. Hook in change map plan :211 ("8 sites, reset records encoded default") and AC plan :249. |
| F3 | hostsSuppressed flag + leftover contract | ⚠️ PRESENT BUT SELF-CONTRADICTORY | Flag + onCreate/onDestroy pairing + leftover contract all written (plan :141, R9 plan :268); queue structure claims accurate (AlertDialogQueue.kt:32-59 singleton + shared `mutableStateListOf`; sole launcher host AppNavHost.kt:125; boot-failure dialog→finish() real at RPCSXActivity.kt:120-127; onDestroy :217). **But see MAJOR-1 below.** |
| F4 | retained 1-arg menu-mode gate | ✅ PRESENT | Plan :174/:186 keeps `shouldAcceptOverlayTouch(isMenuMode) = !isMenuMode` at the PadOverlay.kt:356-358 early-return (current 2-arg call confirmed at :356), :394 collapses to unconditional push (current injector-gated push confirmed at :394); full rewritten policy block P5 step 5; test expectations specified P5 step 6 + Testing item 4 (`(false)==true`, `(true)==false`, sticks, dim). |
| F5 | title-tier-only post-boot replay | ✅ PRESENT | `applyTitleTier` defined plan :153; post-boot step plan :166 replays ONLY per-title tier, restart-required nodes documented next-launch; AC plan :250; unit test asserts applyTitleTier emits only title-tier paths (Testing item 2). Pre-boot full ladder while Stopped (plan :165) preserves precedence: global pre-boot + title last = correct final state. |
| F6 | cross/circle PNG citation | ✅ PRESENT | Plan :55 correction matches repo: `circle.png`, `cross.png` exist; only `ic_circle.xml` is a vector. M8 audit scoped to newly added/selected drawables (plan :241). |
| F7 | launcher gate | ✅ PRESENT | Plan :168 gate = `RPCSX.activeLibrary != null && RPCSX.getState() == EmulatorState.Stopped`; symbols verified (RPCSX.kt:114 `activeLibrary`, :127 `getState(): EmulatorState`). Change map plan :212. |
| N8 | friendly header name | ✅ PRESENT | Plan :125 resolve chain GameRepository.find(path)?.info.name.value → TITLE_ID-shaped segment → raw string; `find(path)` verified (GameRepository.kt:227), `GameInfo.name` is `MutableState<String?>` (GameRepository.kt:34) so `.value` is correct. |
| N9 | bootThread marshaling + R8 | ✅ PRESENT | Plan :167 marshal replay onto bootThread, interactive sets stay on main; R8 documents g_cfg single-writer assumption + fallback executor (plan :267). |

## Findings

### [MAJOR] F3 suppression gate as written also suppresses the in-game dialog host, contradicting the plan's own AC and change map
- Location: plan :141 (P3 step 3 normative text) vs plan :207 (change map: InGameSettingsPage includes "`AlertDialogQueue.AlertDialog()`") vs plan :247 (AC: failed `settingsSet` produces a visible dialog **during gameplay**)
- Problem: `hostsSuppressed` is set `true` in `RPCSXActivity.onCreate` — i.e., for the ENTIRE lifetime of the emulation activity, which is precisely when the in-game settings page needs dialogs. The normative instruction says "`AlertDialogQueue.AlertDialog()` returns early while suppressed" with no call-site distinction. The change map adds a second host call inside `InGameSettingsPage.kt`, but that host calls the same gated composable → every in-game error dialog is silently dropped. This breaks AC plan :247, P3 step 5 (:143 "existing red error dialog appears"), R2 mitigation (:261), and manual M2's failure path.
- Evidence: AlertDialogQueue.kt:54 single shared renderer; AppNavHost.kt:125 launcher-only host today; RPCSXActivity.onCreate spans the whole session so the flag is always true while any in-game page exists.
- Impact: worker implementing literally ships dead error UI in-game or must invent an exemption mechanism on their own (param? second flag? move check to call site?) — a design decision that belongs in the plan.
- Required planner change: scope the gate to the LAUNCHER host only. Pick one and state it normatively:
  (a) keep the check out of `AlertDialogQueue.AlertDialog()` and instead wrap the AppNavHost call site: `if (!AlertDialogQueue.hostsSuppressed) { AlertDialogQueue.AlertDialog() }`; or
  (b) add parameter `AlertDialog(respectHostSuppression: Boolean = true)` and have InGameSettingsPage pass `false`.
  Either way, keep the onCreate=true / onDestroy=false pairing and the leftover-queue contract unchanged.

### [MINOR] applyTitleTier redundantly re-applies an already-applied title tier when the path was title-id-shaped
- Location: plan :165 (pre-boot ladder includes per-title tier via `resolveTitleId(gamePath)`) vs :166 (post-boot `applyTitleTier` unconditional on first non-blank getTitleId)
- Problem: for the common case (`dev_hdd0/game/<TITLE_ID>` per FileUtil getDirInstallPath), the title tier was already applied pre-boot while Stopped; post-boot re-application rewrites identical values while Running, so restart-required per-title nodes reject again — pure log noise, no functional harm (values already set; replay logs rejections, no dialog).
- Impact: cosmetic inconsistency with F5's own rationale ("never re-run tiers already applied pre-boot"); no incorrect behavior.
- Required planner change (informational): one sentence — e.g., skip `applyTitleTier` when the learned titleId equals the pre-boot-resolved id, or explicitly note benign duplicate-set rejections are expected and ignored.

### [MINOR] Pre-boot resolution's use of the persisted learning map is implied, never wired
- Location: plan :154 (resolveTitleId regex + "plus a persisted path→titleId learning map") and :165 (`applyForGame(ctx, resolveTitleId(gamePath))`) vs R3 :262 ("post-boot learning map makes every subsequent launch correct")
- Problem: if `applyForGame` receives only the regex result, non-path-shaped games miss their title tier pre-boot forever despite the learning map existing. R3's promise only holds if resolution consults the map.
- Impact: inferable by a careful worker; risk of a silent gap for exotic install paths only.
- Required planner change (informational): state that `resolveTitleId` falls back to the persisted learning-map lookup after the regex check (or that `applyForGame` does).

## Regression Scan Results (no other new contradictions)

- F2 × F1: no conflict — reset encodes defaults with plain Kotlin string building (`def.toString()`, `"\"" + def + "\""` — verified present in SettingsScreen.kt reset branches); codec API is primitive-string based; zero org.json needed anywhere on this path.
- F5 precedence: pre-boot ladder (Stopped, restart-required accepted) then title-last post-boot yields correct final state; AC :252 order test still valid.
- F3 lifecycle × singleTask/configChanges: pairing onCreate/onDestroy is stable once configChanges prevents recreation (P1 step 4); process-death edge kills both compositions so no stale suppression; R9 covers forget-to-reset. Sound apart from MAJOR-1.
- Deletions intact: change map :217-218 still DELETEs HomeMenuView/HomeMenuModel/PadInputInjector + their tests; AC :245 zero-grep sweep unchanged; no scope creep detected.
- Acceptance criteria remain objective (greps, injected-setter order assertions, manual scenarios).

## Next Agent: Planner
## Next Action: Apply the single MAJOR required change (scope the F3 suppression gate to the launcher/AppNavHost host call site — option (a) or (b) — keeping pairing + leftover contract) and fold the two informational minors into plan text. Resubmit for PASS 3 spot-check of P3 step 3 wording only; no other section needs re-review.
