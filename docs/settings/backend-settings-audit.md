# Backend settings audit

Audited against the pinned RPCSX submodule at
`657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc` and its Android build of
`rpcs3/Emu/system_config.h`.

The Android settings tree is generated from the same `cfg_root` used by the
emulator. Paths below are the exact `@@` paths consumed by the Android bridge.
Leaves not listed in the curated Configure Game screen remain available in the
Advanced Settings tree when their native type is supported by the generic
editor. String/map leaves are intentionally read-only/not rendered by the
generic Android editor until a text editor is added.

| UI label | Full setting path | Scope | UI/backend type | Persistent file | Apply phase | Android support | Validation |
|---|---|---|---|---|---|---|---|
| PPU Decoder | `Core@@PPU Decoder` | Global/game | enum | `config.yml` / title custom | Next boot | Supported | set + read-back + PPU boot log |
| PPU Threads | `Core@@PPU Threads` | Global/game | int 1..8 | same | Next boot | Supported | range/read-back |
| PPU LLVM Codegen Mode | `Core@@PPU LLVM Codegen Mode` | Global/game | enum | same | Next boot | Supported | read-back + PPU log |
| Max LLVM Compile Threads | `Core@@Max LLVM Compile Threads` | Global/game | int 0..1024 | same | Next boot | Supported (`0` auto) | range/read-back |
| PPU Foreground Compile Threads | `Core@@PPU Foreground Compile Threads` | Global/game | int 0..32 | same | Next boot | Supported (`0` auto) | range/read-back |
| PPU LLVM Greedy Mode | `Core@@PPU LLVM Greedy Mode` | Global/game | bool | same | Next boot | Supported | true/false read-back |
| LLVM Precompilation | `Core@@LLVM Precompilation` | Global/game | bool | same | Next boot | Supported | true/false read-back |
| SPU Decoder | `Core@@SPU Decoder` | Global/game | enum | same | Next boot | Supported | set + read-back |
| SPU Block Size | `Core@@SPU Block Size` | Global/game | enum | same | Next boot | Supported | set + read-back |
| Enable /host_root | `VFS@@Enable /host_root/` | Global/game | bool | same | Next boot | Supported | read-back |
| Initialize Directories | `VFS@@Initialize Directories` | Global/game | bool | same | Next boot | Supported | read-back |
| Limit disk cache size | `VFS@@Limit disk cache size` | Global/game | bool | same | Next boot | Supported | read-back |
| Disk cache maximum size | `VFS@@Disk cache maximum size (MB)` | Global/game | int 0..10240 | same | Next boot | Supported | range/read-back |
| Renderer | `Video@@Renderer` | Global/game | enum | same | Next boot | Vulkan supported on Pad 2 | read-back + Vulkan log |
| Resolution | `Video@@Resolution` | Global/game | enum | same | Next boot | Supported | read-back + renderer log |
| Aspect ratio | `Video@@Aspect ratio` | Global/game | enum | same | Next boot | Supported | read-back + screenshot |
| Frame limit | `Video@@Frame limit` | Global/game | enum | same | Next boot | Supported | read-back + performance monitor |
| MSAA | `Video@@MSAA` | Global/game | enum | same | Next boot | Supported options only | read-back + Vulkan boot |
| Shader Mode | `Video@@Shader Mode` | Global/game | enum | same | Next boot | Supported options only | read-back + shader log |
| Write Color Buffers | `Video@@Write Color Buffers` | Global/game | bool | same | Next boot | Supported | both bool directions/read-back |
| Read Color Buffers | `Video@@Read Color Buffers` | Global/game | bool | same | Next boot | Supported | both bool directions/read-back |
| VSync | `Video@@VSync` | Global/game | bool | same | Next boot | Supported | read-back + frame timing |
| Resolution Scale | `Video@@Resolution Scale` | Global/game | int 25..800 | same | Next boot | Supported | range/read-back |
| Anisotropic Filter Override | `Video@@Anisotropic Filter Override` | Global/game | uint 0..16 | same | Next boot | Supported (`0` auto) | range/read-back |
| Output Scaling Mode | `Video@@Output Scaling Mode` | Global/game | enum | same | Next boot | Supported | read-back |
| Audio Renderer | `Audio@@Renderer` | Global/game | enum | same | Next boot | Native Android backend | read-back + audio init |
| Audio Provider | `Audio@@Audio Provider` | Global/game | enum | same | Next boot | Native Android backend | read-back + audio init |
| RSXAudio Avport | `Audio@@RSXAudio Avport` | Global/game | enum | same | Next boot | Native Android backend | read-back |
| Audio Format | `Audio@@Audio Format` | Global/game | enum | same | Next boot | Supported options only | read-back + audio init |
| Master Volume | `Audio@@Master Volume` | Global/game | int 0..200 | same | Live/next boot | Supported | range/read-back |
| Enable Buffering | `Audio@@Enable Buffering` | Global/game | bool | same | Next boot | Supported | bool/read-back |
| Desired Audio Buffer Duration | `Audio@@Desired Audio Buffer Duration` | Global/game | int 4..250 | same | Next boot | Supported | range/read-back |
| Keyboard | `Input/Output@@Keyboard` | Global/game | enum | same | Next boot | Android bridge | read-back |
| Mouse | `Input/Output@@Mouse` | Global/game | enum | same | Next boot | Android bridge | read-back |
| Pad handler mode | `Input/Output@@Pad handler mode` | Global/game | enum | same | Next boot | Android bridge | read-back |
| Keep pads connected | `Input/Output@@Keep pads connected` | Global/game | bool | same | Live/next boot | Android bridge | bool/read-back |
| Background input enabled | `Input/Output@@Background input enabled` | Global/game | bool | same | Live/next boot | Android bridge | bool/read-back |
| Language | `System@@Language` | Global/game | enum | same | App/core restart | Supported | read-back + relaunch |
| Keyboard Type | `System@@Keyboard Type` | Global/game | enum | same | Next boot | Supported | read-back |
| Enter button assignment | `System@@Enter button assignment` | Global/game | enum | same | Next boot | Supported | read-back |
| Internet enabled | `Net@@Internet enabled` | Global/game | enum | same | Next boot | Supported | read-back + network log |
| UPNP Enabled | `Net@@UPNP Enabled` | Global/game | bool | same | Next boot | Supported | bool/read-back |
| Start Paused | `Savestate@@Start Paused` | Global/game | bool | same | Next boot | Supported | bool/read-back |
| Suspend Emulation Savestate Mode | `Savestate@@Suspend Emulation Savestate Mode` | Global/game | bool | same | Next boot | Supported | bool/read-back |
| Compatible Savestate Mode | `Savestate@@Compatible Savestate Mode` | Global/game | bool | same | Next boot | Supported | bool/read-back |
| Automatically start games after boot | `Miscellaneous@@Automatically start games after boot` | Global/game | bool | same | Next boot | Supported | bool/read-back |
| Prevent display sleep while running games | `Miscellaneous@@Prevent display sleep while running games` | Global/game | bool | same | Live/next boot | Android integration | bool/read-back |
| Show trophy popups | `Miscellaneous@@Show trophy popups` | Global/game | bool | same | Live/next boot | Supported | bool/read-back |
| Pause Emulation During Home Menu | `Miscellaneous@@Pause Emulation During Home Menu` | Global/game | bool | same | Live | Android integration | bool/read-back |

## Type and path rules

- Native `cfg::_bool` leaves are emitted as JSON booleans and are written as
  bare YAML booleans.
- Native enum/string leaves are emitted as JSON strings and are written as YAML
  strings; the JNI contract receives JSON literals, not enum indexes.
- Integer/unsigned/float leaves retain their numeric literal and native range
  validation remains authoritative.
- `0` is displayed as the backend's auto value where the native field documents
  that meaning; it is not silently rewritten by the app.
- A node missing from the runtime tree is not synthesized by the app. Generic
  UI rendering only acts on the node returned by the backend.

## Audit command

The runtime source of truth for a full build-specific audit is:

```text
RPCSX.settingsGetGlobal("")
```

The result includes each leaf's `type`, `value`, `default`, and where applicable
`min`, `max`, and `variants`. The scoped APIs use the same path resolver, so a
path cannot be accepted by the scoped writer unless it resolves against the
same `cfg_root` schema.
