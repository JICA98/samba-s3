# Backend settings audit

This audit is tied to the RPCSX Android core used by the 2026-09-01 standard
debug Pad 2 build. The native tree returned by `settingsGetGlobal("")` is the
source of truth; the complete build-specific result is
[`settings-path-audit.csv`](settings-path-audit.csv), with one row per runtime
leaf and the canonical path, type, scope, persistence, apply phase, Android
support, current value, default, variants, and validation method.

## Runtime result

Pad 2 schema probe: `leaves=258 missing=0 typeMismatch=0 duplicate=0
unsupported=27 valid=true`.

| Runtime type | Count | Android editor behavior |
|---|---:|---|
| `bool` | 121 | Supported switch |
| `enum` | 55 | Supported selection |
| `int` | 22 | Supported bounded numeric control |
| `uint` | 31 | Supported bounded numeric control |
| `float` | 2 | Supported bounded numeric control |
| `string` | 24 | Hidden until a safe text editor exists |
| `map` / `set` / `log_map` | 3 | Hidden/read-only |

The generic editor therefore covers 231 leaves. The 27 unsupported leaves are
not silently presented as editable controls. See
[`settings-schema-audit.txt`](../testers/artifacts/2026-09-01-settings-wiring/settings-schema-audit.txt)
and [`unsupported-setting-example.txt`](../testers/artifacts/2026-09-01-settings-wiring/unsupported-setting-example.txt)
for the exact runtime list and the rationale for hiding strings/maps/sets.

## Canonical paths and storage

Paths use the root-relative RPCSX form, for example
`Video@@Frame limit`; JNI callers may include the leading `@@`. The native
resolver normalizes that prefix before looking up a leaf.

| Surface | Canonical backend | Persistent storage | Scope |
|---|---|---|---|
| Global Settings | `settingsGetGlobal` / `settingsSetGlobal` | `config/config.yml` | Global |
| Configure Game | `gameSettingsOverride*` | `config/custom_configs/config_<TITLE_ID>.yml` | One title |
| Effective value | `settingsGetEffective` | Merge of the two files | One title |
| In-game Settings | transient setter + sparse commit | Current title custom file | Explicit `THIS GAME` |

Global reads always load the canonical global file, even when a game is active.
Title files contain only values that differ from global. A value equal to the
global value removes the sparse key; a missing key is displayed as `USE GLOBAL`.
All native writes serialize through the lifecycle mutex, use an atomic pending
file, and perform disk read-back before reporting success.

## Curated UI coverage

The following high-risk settings are explicitly used by the validation matrix;
the exhaustive list is in the CSV linked above.

| UI label | Full setting path | Backend type | Scope | Apply phase | Validation |
|---|---|---|---|---|---|
| PPU Decoder | `Core@@PPU Decoder` | enum | global + title | next boot | read-back + PPU boot |
| PPU Threads | `Core@@PPU Threads` | int | global + title | next boot | range + read-back |
| Renderer | `Video@@Renderer` | enum | global + title | next boot | Vulkan render |
| Resolution | `Video@@Resolution` | enum | global + title | next boot | exact read-back + boot |
| Aspect ratio | `Video@@Aspect ratio` | enum | global + title | next boot | exact read-back |
| Frame limit | `Video@@Frame limit` | enum | global + title | next boot | boot line + emu-flip FPS |
| Write Color Buffers | `Video@@Write Color Buffers` | bool | global + title | next boot | true and false |
| Read Color Buffers | `Video@@Read Color Buffers` | bool | global + title | next boot | true and false |
| VSync | `Video@@VSync` | bool | global + title | next boot | read-back |
| Resolution Scale | `Video@@Resolution Scale` | int | global + title | next boot | range + read-back |
| Audio Renderer | `Audio@@Renderer` | enum | global + title | next boot | read-back + audio init |
| Audio Provider | `Audio@@Audio Provider` | enum | global + title | next boot | read-back + audio init |
| Audio Format | `Audio@@Audio Format` | enum | global + title | next boot | read-back + audio init |
| Master Volume | `Audio@@Master Volume` | int | global + title | live | range + read-back |
| Keyboard | `Input/Output@@Keyboard` | enum | global + title | next boot | read-back |
| Pad handler mode | `Input/Output@@Pad handler mode` | enum | global + title | next boot | read-back |
| Language | `System@@Language` | enum | global + title | app restart | read-back + relaunch |
| UPNP Enabled | `Net@@UPNP Enabled` | bool | global + title | next boot | true and false |
| Start Paused | `Savestate@@Start Paused` | bool | global + title | next boot | read-back |
| Pause Emulation During Home Menu | `Miscellaneous@@Pause Emulation During Home Menu` | bool | global + title | live | read-back |

`Audio@@Renderer` and `Audio@@Audio Provider` were observed as `Cubeb` and
`CellAudio` on the Pad 2; the game log contains `cellAudioInit`, port open, and
port start. No unsupported “Native Android backend” label is inferred.

## Apply phases

Every supported editor row displays an explicit phase. Live values are applied
immediately where the core supports that behavior. Most core/video/audio
choices are persistent and take effect after the next emulation boot. Language
is marked for an app restart. In-game edits are labeled for the current game
and are committed as sparse title state; unsupported runtime types are marked
`UNSUPPORTED` in the CSV and are not rendered as generic controls.

The phase table is generated from `SettingsBackendAudit` plus the runtime type
probe, so a stale path/type assumption is visible in the root schema log.
