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
resolution, aspect ratio, frame limit, audio/system categories, per-game
write/readback/reset, effective merge, process restart, fresh boot, restart,
manual load, and cold savestate recovery. The final candidate was packaged as
`standardDebug` with APK SHA-256
`46878fd38efbbb56a3fdfc4ab780ffeb9cec58ca8017d1004228f76d35bb8629`, arm64
core SHA-256
`afc8d4a706135c1c00e1b0988abe9888846cd06ef6474c544e9b1cc16579eec9`, and
x86_64 core SHA-256
`1ae2bcd04854f34af4b99dbae7b08c0c892f8eb19d0a6da1128295b8fe25011d`.

Runtime core ID: `rpcsx=657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`, Samba source
`5f599909946dafdecab2a084cb49f672f86bcb37`, patch SHA
`2444bd02c690b4a7901a3d50b7f6ad40249952fe9a1d6eb86d9e8d14fc9bc91e`, build
type `RelWithDebInfo`. Full provenance is in
[`native-core-provenance.txt`](artifacts/2026-09-01-settings-wiring/native-core-provenance.txt).

## Source provenance

The build was installed on and tested only against the OnePlus Pad 2. The
runtime core ID, APK hash, arm64/x86_64 core hashes, and patch hash are listed
above and repeated in the linked provenance artifact.

## Pre-fix architecture

Before the repair, the app had multiple settings paths: native RPCSX state,
an app-side SharedPreferences cache, title values replayed through the global
setter, and boot paths that did not consistently use RPCSX custom config mode.
The visible Compose value was therefore not sufficient evidence of persistence
or runtime application.

## Exact root causes repaired

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

## Native backend semantics

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

## Global canonical config

The only production global source is RPCSX's canonical `config/config.yml`.
Advanced Settings reads and writes that file through the scoped JNI exports;
the app-side legacy SharedPreferences resolver is retained only for JVM
regression tests.

## Per-game sparse config

Each title file stores only changed keys. `Use Global` means the title key is
absent. Setting a title value equal to the current global value removes the
title key, and Reset All removes the title file without touching global
configuration.

## Boolean ordering fix

Boolean callbacks now send the new literal before updating the display object.
The Pad 2 harness and focused tests both verified `false -> true` and
`true -> false` readback.

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
| Runtime FPS effect at 30 | stable native samples near 30 FPS | PASS; mean 29.843 FPS |
| Runtime FPS effect at 60 | stable native samples near 60 FPS | PASS; mean 58.899 FPS |
| Runtime FPS effect Off | limiter disabled, subject to tablet display | PASS; mean 57.993 FPS, display-bound caveat |

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

## Video validation

Fresh GTA San Andreas boots logged `boot phase=fresh result=0` with Frame limit
`30`, `60`, and `Off`. Stable native samples from the final APK/core were:

| Setting | Samples | Min FPS | Max FPS | Mean FPS | Mean frame time |
|---|---:|---:|---:|---:|---:|
| `30` | 70 | 28.452 | 32.173 | 29.843 | 33.335 ms |
| `60` | 36 | 56.974 | 60.778 | 58.899 | 17.027 ms |
| `Off` | 18 | 55.617 | 61.508 | 57.993 | 17.036 ms |

`Off` disables the emulator limiter, but the Pad 2 surface/display remains
approximately 60 Hz, so this is not claimed as uncapped. CSV captures are in
[`frame-limit-30.csv`](artifacts/2026-09-01-settings-wiring/frame-limit-30.csv),
[`frame-limit-60.csv`](artifacts/2026-09-01-settings-wiring/frame-limit-60.csv),
and [`frame-limit-off.csv`](artifacts/2026-09-01-settings-wiring/frame-limit-off.csv).

The resolution test used exact RPCSX value `1280x720` and was restored to
`720x576`; aspect used `4:3` and was restored to `16:9`. These are guest/video
output settings, not Android surface dimensions. The Pad 2 render surface
remained 3000x2120, so the screenshot is not being presented as a 1280x720
Android surface result.

## Unsupported Android settings

The patched core returned 258 leaf settings:

```text
missing=0  typeMismatch=0  duplicate=0  unsupported=27  valid=true
bool=121  enum=55  float=2  int=22  uint=31  string=24  map=1  set=1  log_map=1
```

231 bool/enum/integer/float leaves have generic Android editors. The 27
string/map/set/log-map leaves remain native-backend-capable but are hidden
until an appropriate text/list editor exists. The exhaustive table is
[`settings-path-audit.csv`](../settings/settings-path-audit.csv), with raw
tree and audit at
[`settings-tree-pad2.json`](artifacts/2026-09-01-settings-wiring/settings-tree-pad2.json)
and [`settings-schema-audit.txt`](artifacts/2026-09-01-settings-wiring/settings-schema-audit.txt).

## Effective config merge

Global reads and writes use canonical `config/config.yml`, even while a title
is active. Title files contain only values different from global; an equal
value is removed. Boot, restart, and savestate boot use RPCSX custom mode so
the effective tree is global plus the title sparse delta. The app does not
replay title values through the global setter.

| Operation | Apply phase |
|---|---|
| Global edit while stopped | live cache plus next emulation boot |
| Global edit while running | persistent; next emulation boot |
| Configure Game edit | persistent; next emulation boot |
| In-game transaction | runtime transaction; sparse persistence on Save; restart-only values on next boot |
| Clear title override | persistent immediately; restart-only values on next boot |

### Settings refresh/read-back

After commits, launcher, Configure Game, and in-game rows refresh from native
readback. Rejected writes leave the backend value authoritative. Row-local
Compose state is keyed by the current JSON node, so refresh does not retain an
old boolean or slider value.

### Apply phases

The table above is the apply-phase contract. Every supported row is classified
in the exhaustive CSV; restart-only changes show an explicit apply hint in the
UI.

## Category navigation

The Pad 2 opened the Advanced Settings root and all runtime categories:
Core, VFS, Video, Audio, Input/Output, System, Net, Savestate, and
Miscellaneous. Current captures include
`10-advanced-core.png`, `11-advanced-video.png`, `12-advanced-audio.png`, and
`13-advanced-system.png`.

### Core/PPU validation

PPU logs show successful link/apply/JIT-finalize stages with no failures. The
PPU decoder and compiler-related rows are classified as supported next-boot
settings in the audit table.

### Audio validation

Audio
logs reach `cellAudioOutConfigure`, `cellAudioInit`, `cellAudioPortOpen`, and
`cellAudioPortStart`; effective boot logs report `audioRenderer="Cubeb"`.
No unsupported native Android audio behavior is inferred from these logs.

### Other categories

VFS, Input/Output, System, Net, Savestate, and Miscellaneous were included in
the runtime tree audit and category navigation pass. The complete per-setting
result table is [`settings-validation-results.csv`](artifacts/2026-09-01-settings-wiring/settings-validation-results.csv):
231 `PASS-SCHEMA-READBACK` rows and 27 `UNSUPPORTED-HIDDEN` rows.

## Global/per-game isolation

The exact Pad 2 Frame limit harness matrix was:

| Scenario | Global | BLUS31584 override | S3TESTB override | BLUS31584 effective | S3TESTB effective | Global read-back |
|---|---:|---:|---:|---:|---:|---:|
| Initial | 30 | none | none | 30 | 30 | 30 |
| Game A override | 30 | 60 | none | 60 | 30 | 30 |
| Global changed | Off | 60 | none | 60 | Off | Off |
| Game A reset | Off | none | none | Off | Off | Off |

All native writes returned `ok=true`; both title files were cleared and global
Frame limit was restored to `60` afterward. `S3TESTB` was a backend-only
synthetic title because the Pad 2 had no second installed game; it was not
claimed as a second-game UI boot.

## Restart/load/recovery

The final build was exercised through the in-game PS menu and after a cold
process restart:

- Restart: `boot phase=restart result=0`, generation `1`.
- Manual Load slot 0: `boot phase=load result=0`, generation `2`, followed by
  first-frame confirmation.
- Cold Continue Save: `boot phase=savestate result=0`, Vulkan/PPU/audio
  initialization, and `first-frame-confirmed mode=UserSelectedSavestate`.

Filtered evidence is in
[`restart-load-recovery.log`](artifacts/2026-09-01-settings-wiring/restart-load-recovery.log);
the screenshot is `21-cold-savestate-recovery.png`. Complete app/backend logs
are retained in the artifact directory.

## Pass 1

Pass 1 ran the global readback, boolean round trip, Frame limit 30/60/Off,
resolution/aspect, BLUS31584 sparse override/reset, category navigation, PPU,
audio, restart, manual load, cold recovery, and cleanup checks. Its raw
settings harness capture is [`s3cfg-pass1.log`](artifacts/2026-09-01-settings-wiring/s3cfg-pass1.log).

## Pass 2

Pass 2 repeated the critical matrix on the unchanged APK/core: boolean
readback, global Frame limit, title override, global isolation, resolution,
aspect, PPU boot path, audio, process persistence, restart, and load. Its raw
capture is [`s3cfg-pass2.log`](artifacts/2026-09-01-settings-wiring/s3cfg-pass2.log).

## Screenshots

The artifact directory contains the final settings screen, Advanced Settings
root, every category capture, global Frame limit captures, resolution/aspect
captures, boolean captures, per-game override/use-global captures, runtime FPS
captures, and cold savestate recovery capture. PNG is used for the Pad 2
captures so the screenshots remain lossless.

## Logs

Filtered `S3CFG`, lifecycle, PPU, audio, frame-sample, schema, process-restart,
restart/load, and cold-recovery logs are retained under
[`artifacts/2026-09-01-settings-wiring`](artifacts/2026-09-01-settings-wiring/).

## Automated checks

Focused settings tests cover sparse title isolation, global inheritance,
per-title reset, exact resolution/aspect values, and both boolean directions.
Result: focused `gameconfig.*` tests passed. The full unit suite ran 337 tests;
the final aggregate run passed, including the previously flaky
`EmulatorStopCoordinatorTest.tenConcurrentStopsIssueOneKillAndShareOutcome`.
No settings test failed.

## Remaining risks

No second installed game was available on the Pad 2. Therefore two-title UI
boot isolation is covered by the synthetic backend-only `S3TESTB` fixture, JVM
tests, native path review, and final cleanup—not claimed as a second-game UI
run. `Frame limit=Off` was display-bound near 60 Hz on this tablet, so it is
not evidence of an uncapped physical output. Unsupported string/map/set/log-map
settings remain hidden until dedicated Android editors exist.

## Deliverables

- Complete audit: [`backend-settings-audit.md`](../settings/backend-settings-audit.md)
- Complete path table: [`settings-path-audit.csv`](../settings/settings-path-audit.csv)
- Pad 2 evidence bundle: [`artifacts/2026-09-01-settings-wiring`](artifacts/2026-09-01-settings-wiring/)
- Installable APK: [`samba-s3-standard-debug-final.apk`](artifacts/2026-09-01-settings-wiring/samba-s3-standard-debug-final.apk)
