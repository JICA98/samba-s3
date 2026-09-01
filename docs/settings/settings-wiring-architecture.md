# SambaS3 settings wiring architecture

## Source of truth

SambaS3 uses RPCS3's own configuration model. The app does not use
SharedPreferences as an active settings database.

```text
Advanced Settings
        |
        v
RPCSX JNI: settingsGetGlobal / settingsSetGlobal
        |
        v
<RPCS3 config directory>/config.yml  (canonical Global)
        |
        +-------------------------------+
                                        |
Configure Game / in-game Settings      |
        |                               |
        v                               |
custom_configs/config_<TITLE_ID>.yml   |
        |                               |
        +---------- sparse merge <------+
                    |
                    v
        Effective title configuration at boot
                    |
                    v
                 RPCSX core
```

## Scope rules

- Global reads and writes use `config.yml`, even while a title-specific
  runtime configuration is active.
- A game file contains only values different from the current global file.
  An absent key means `Use Global`.
- Setting a game value equal to the current global value removes the title key.
- Game A writes never call the global setter and cannot change Game B's file.
- The in-game Settings page is explicitly `THIS GAME`; its transaction is
  committed as a sparse title file.

The old `GameSettingsOverrides` SharedPreferences implementation remains only
as an internal JVM-test seam for the legacy resolver tests. Production reads
and writes go through the native methods listed below.

## JNI contract

| Kotlin API | Native export | Storage/operation |
|---|---|---|
| `settingsGetGlobal(path)` | `_rpcsx_settingsGetGlobal` | Read `config.yml` or a node |
| `settingsSetGlobal(path, value)` | `_rpcsx_settingsSetGlobal` | Validate, write atomically to `config.yml` |
| `gameSettingsOverridesGet(title)` | `_rpcsx_gameSettingsOverridesGet` | Read sparse title deltas |
| `gameSettingsOverrideSet(title, path, value)` | `_rpcsx_gameSettingsOverrideSet` | Validate and write one sparse delta |
| `gameSettingsOverrideClear(title, path)` | `_rpcsx_gameSettingsOverrideClear` | Remove one sparse delta |
| `gameSettingsOverridesClear(title)` | `_rpcsx_gameSettingsOverridesClear` | Remove one title file |
| `settingsGetEffective(title, path)` | `_rpcsx_settingsGetEffective` | Read global plus title overlay |

All native writes use the emulator lifecycle mutex. Global writes are validated
against a fresh `cfg_root`; title writes are validated against the same global
schema. Writes use `fs::pending_file`, so a failed write does not leave a
partially serialized config.

## Read-back and apply phase

Global UI writes call `settingsSetGlobalAndVerify`: the setter must succeed and
the canonical file is read again before the UI reports success. Game writes and
clears perform the equivalent sparse-map read-back. A failed read-back leaves
the row unchanged and displays the existing error dialog.

The core boot and savestate boot paths use `cfg_mode::custom`, which makes the
normal RPCS3 loader apply the global file and then the title custom file.
Restart preserves that custom mode. PPU preflight creates a temporary effective
tree and restores the stopped live tree afterward.

| Operation | Apply phase |
|---|---|
| Global edit while stopped | Live cache + next boot |
| Global edit while running | Persistent; next emulation boot |
| Configure Game edit | Persistent; next emulation boot |
| In-game transaction | Runtime transaction; sparse title persistence on Save; next boot for restart-only nodes |
| Clear title override | Persistent immediately; next boot for restart-only nodes |

The app deliberately does not replay title values through the global setter at
launch. This prevents title leakage after exit and keeps Global Settings on the
canonical baseline.

## Boolean ordering

The Advanced Settings switch sends the new boolean literal (`true` or `false`)
to the verified setter before updating the display object. This avoids the old
mutable-object ordering bug where the callback could observe the previous
value. The codec and regression tests cover both directions.

## Refresh behavior

- The launcher settings state is re-read from `settingsGetGlobal("")` on
  refresh and after a global commit.
- Configure Game reads the canonical global tree and native sparse title map
  when opened.
- The in-game transaction refreshes its tree after each native transient write;
  rejected writes are surfaced as a settings error and the backend tree wins.
- Row-local Compose state is keyed by the current JSON node, so a refresh cannot
  leave an old switch or slider value on screen.

## Compatibility

The JNI calls are null-safe when an old downloaded core is present, but those
old cores do not provide the new scoped exports. The arm64 test APK must be
built with the patched core; an old core is reported as a failed scoped write
instead of silently falling back to global mutation.
