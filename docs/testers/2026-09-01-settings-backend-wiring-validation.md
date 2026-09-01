# SambaS3 settings backend wiring validation

Date: 2026-09-01  
Device under test: OnePlus Pad 2 (`OPD2403`, `OP5DAAL1`) only  
Package: `com.zenithblue.sambas3`  
Scope: canonical global settings, sparse per-game overrides, effective settings,
in-game settings scope, and restart/load wiring

## Result

The settings UI now uses RPCSX's configuration backend as its source of truth.
Global settings are stored in `config.yml`; per-game settings are sparse deltas
in `custom_configs/config_<TITLE_ID>.yml`. A missing per-game key is displayed
as `USE GLOBAL`, and the effective value is resolved as global plus that game's
delta.

The Pad 2 checks below cover global readback, booleans in both directions,
resolution, aspect ratio, frame limit, audio/system categories, and the
per-game write/readback/reset path. The final candidate was packaged as
`standardDebug` with arm64 core
`c9b9db91083d313682e1479e172c4eef8318c6b01a293973a48140b969ab378f` and APK
`70175bab5555523a35a4bf21c5637f48078d3114db2ce174650f5740e91ddb39`.

## Root causes repaired

- The old global setter mutated the live `g_cfg` tree and saved it directly,
  which made title-active state look like global state.
- Game launch replayed title values through the global setter, allowing one
  title's configuration to leak into the global baseline or another title.
- Boot and savestate boot selected global configuration mode instead of custom
  mode, so title files were not consistently part of the normal resolver.
- Per-game persistence serialized a complete title tree rather than a sparse
  override file.
- The generic boolean editor could send the mutable row's previous value.
- The first sparse readback implementation used an empty schema. It could write
  `config_<TITLE_ID>.yml` but could not resolve the saved leaf, which surfaced
  as a false UI error. The schema now calls `from_default()` before walking
  title YAML, and the readback path is rebuilt into the test core. The YAML
  walker also rebinds child nodes after traversal so yaml-cpp does not flatten
  a nested title override into a root-level key.

## Backend contract

| Operation | Backend | Storage/effect |
|---|---|---|
| Read/write global | `settingsGetGlobal` / `settingsSetGlobal` | canonical `config.yml` |
| Read game deltas | `gameSettingsOverridesGet` | sparse title YAML |
| Set/clear game delta | `gameSettingsOverrideSet` / `gameSettingsOverrideClear` | one sparse title key |
| Reset game | `gameSettingsOverridesClear` | removes title YAML |
| Read effective leaf | `settingsGetEffective` | global merged with title YAML |
| Boot/restart/savestate | RPCSX custom config mode | global plus title custom file |
| In-game settings | transient transaction, then sparse commit | explicitly current game scope |

All native writes use the emulator lifecycle mutex and atomic pending-file
serialization. Global writes are read back from the canonical file before the
Compose row reports success. Game writes and clears are read back from the
sparse map. The UI refreshes from the same backend after a commit.

## Pad 2 validation matrix

| Check | Expected | Result |
|---|---|---|
| Global Frame limit 30 | UI and canonical backend read back `30` | PASS |
| Global Frame limit 60 | UI and canonical backend read back `60` | PASS |
| Global Write Color Buffers on/off | both boolean literals persist and screen stays in Video | PASS |
| Global Resolution 1280x720 | exact value read back, then restored to 720x576 | PASS |
| Global Aspect ratio 4:3 | exact value read back, then restored to 16:9 | PASS |
| Audio category | backend-backed rows render with current values | PASS |
| System category | backend-backed rows render with current values | PASS |
| Game A override | title YAML contains only changed key; readback shows `OVERRIDE` | PASS twice |
| Game A reset | title delta clears and row returns to `USE GLOBAL` | PASS twice |
| Game B isolation | no title-B fixture is installed in the library | unit-test/source proof; device fixture unavailable |
| Runtime FPS effect | requires a controlled gameplay benchmark | not claimed in this report |

The only installed title used for the title-scoped device check is GTA San
Andreas, `BLUS31584`. No second installed game or synthetic Game B fixture was
found, so cross-title isolation is covered by the JVM tests and native path
review rather than presented as a two-title UI pass.

## Two-pass protocol

Each final pass ran on the unchanged final APK and did the following:

1. Open Advanced Settings and read the global tree.
2. Change a global enum and a boolean, verify UI readback, and restore the
   original values.
3. Open Configure Game for `BLUS31584`, set Frame limit to a different value,
   verify the title override indicator and YAML, then reset the title override.
4. Reopen the screen and verify the row is `USE GLOBAL` with the global value.
5. Capture filtered `S3CFG` logcat and the final global/title file state.

Pass 1 and Pass 2 both showed `OVERRIDE`/30 after the title write, then
`USE GLOBAL`/60 after Reset All. Both ended with
`config_BLUS31584.yml` absent. The final global file retained 720x576, 16:9,
60 FPS, and Write Color Buffers false.

Screenshots, YAML snapshots, CSV results, and filtered logs are stored under
[`artifacts/2026-09-01-settings-wiring`](artifacts/2026-09-01-settings-wiring/).

## Automated checks

Focused settings tests cover sparse title isolation, global inheritance,
per-title reset, exact resolution/aspect values, and both boolean directions.
Result: focused `gameconfig.*` tests passed. The earlier full unit suite ran
337 tests and exposed one existing concurrency-flaky
`EmulatorStopCoordinatorTest.tenConcurrentStopsIssueOneKillAndShareOutcome`
failure; an isolated rerun of that test passed, and settings tests were not
part of the failure. No runtime FPS benchmark was claimed.
