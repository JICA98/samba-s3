# Trophies in-game regression fix — 2026-09-17

## Symptom
- Launcher Trophies overlay: correct (inFamous 2 BCUS98125, 0/52, real icons).
- In-game Trophies page: `0/0 unlocked · 0%`, `No installed trophy set for this title`,
  header fell back to `ACHIEVEMENTS` (empty gameName).

## Root cause
- Two different native queries with different failure modes:
  - Launcher (`GamesScreen`): `AchievementRepository.title(titleId)` (+ Direct ISO
    fallback) using the explicit titleId parsed from the game library entry.
  - In-game (`RpcsxInGameMenuCoreGateway.trophies()` / `InGameTrophiesPage`):
    `AchievementRepository.current()` only, which depends on native
    `Emu.GetTitleID()` + `current_trophy_name`. When the live context is empty
    (menu before trophy context registration, savestate boot, Direct ISO), native
    returns `no_trophy_set` even though the HDD set is installed.
- Kotlin code was identical to checkpoint `97a1909`; the failure is the missing
  in-game fallback, not a new native bug.

## Fix (no native rebuild)
- `InGameMenuCoreGateway.kt`: `RpcsxBridge` gains `getTitleId()` /
  `getTrophiesForTitle()`; `RpcsxInGameMenuCoreGateway.trophies()` tries live
  `current()` first, then explicit `title(getTitleId())` when live is unavailable.
- `InGameTrophiesPage.kt`: same live → titleId fallback as defense-in-depth.
- Rename: user-facing name is now **Trophies** everywhere (`ingame_achievements`
  string → `Trophies`, `AchievementsContent` fallback `TROPHIES`).
- Realtime alert: native RSX `OverlayTrophyNotification` popup was already wired
  (`rpcsx-android.cpp`); Kotlin `FRONTEND_EVENT_TROPHY_UNLOCKED` handler now also
  shows an on-screen Toast and invalidates the trophy cache so open Trophies
  pages refresh.
- Regression barriers added in code (`TROPHY REGRESSION BARRIER`): never collapse
  the in-game path to `current()` alone.

## Files
- `app/src/main/java/com/zenithblue/sambas3/ui/ingame/InGameMenuCoreGateway.kt`
- `app/src/main/java/com/zenithblue/sambas3/ui/ingame/InGameTrophiesPage.kt`
- `app/src/main/java/com/zenithblue/sambas3/ui/achievements/AchievementRepository.kt`
- `app/src/main/java/com/zenithblue/sambas3/ui/achievements/AchievementsContent.kt`
- `app/src/main/java/com/zenithblue/sambas3/ui/ingame/InGameMenuCoordinator.kt` (comment)
- `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` (unlock toast)
- `app/src/main/res/values/strings.xml` (`ingame_achievements` → `Trophies`)
- `docs/play-store/CHANGELOG.md`

## Validation
- Unit tests: `:app:testStandardDebugUnitTest` (TrophySnapshotProvider,
  InGameMenuCoordinator trophies row).
- On-device: open launcher Trophies (expect set) then in-game Trophies for the
  same title (must match counts, no 0/0); unlock event shows toast + list refresh.
