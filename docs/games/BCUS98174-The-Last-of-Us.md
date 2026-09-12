# The Last of Us (BCUS98174) — Android Validation

| Field | Value |
|---|---|
| Title / update | The Last of Us™ (USA), `BCUS98174`, game reports v01.00 |
| Image | `Last of Us, The (USA) (En,Fr,Es,Pt).iso`, 26,424,311,808 bytes |
| Registered path | `direct_iso/BCUS98174` |
| Core | RPCSX `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`, RelWithDebInfo |
| OnePlus 13R | CPH2691, Snapdragon 8 Gen 3 / Adreno 750, Turnip 26.3 |
| Test date | 2026-09-13 |

## Result

- **In-Game Validation Achieved:** OnePlus 13R successfully boots The Last of Us, advances through SPU cache compilation (10,800+ modules), presents the warning/EULA screen, transitions through high-detail developer/studio logos (Naughty Dog, Sony), displays the interactive main menu, creates and loads persistent save data, and renders the opening prologue narrative in-game at a solid target 30.0 FPS (32–33 ms frametime).
- High-fidelity 3D character models (Sarah, Joel), dynamic interior lighting (nightstand lamp, hallway), skin/hair shaders, couch leather textures, and real-time animations render accurately with zero vertex explosions or color corruptions.
- Audio playback through AAudio/Cubeb streams continuously at 48000 Hz in full sync without buffer underruns.
- The clean stop lifecycle via `debug-stop-game.sh` is validated: reaches `stop completed ok=true`, unregisters host cleanly, and returns to `MainActivity`.

## Compatibility Profile

Product-owned defaults for all known The Last of Us title IDs in `GameSettingsOverrides.kt`:

```json
{
  "Core@@Stub PPU Traps": 1,
  "Core@@XFloat Accuracy": "Accurate",
  "Core@@SPU loop detection": true,
  "Core@@Max SPURS Threads": 4,
  "Core@@RSX FIFO Accuracy": "Atomic",
  "Video@@Driver Wake-Up Delay": 200,
  "Video@@Write Color Buffers": false,
  "Video@@Read Color Buffers": false,
  "Video@@Write Depth Buffer": false,
  "Video@@Read Depth Buffer": false,
  "Video@@Relaxed ZCULL Sync": true,
  "Video@@Vulkan@@Asynchronous Texture Streaming 2": false
}
```

Known aliases: `BCUS98174`, `NPUA80960`, `BCES01584`, `BCES01585`.

## Errors Fixed & Root Causes

1. **Shader Module Recompilation Assertion in `VKProgramPipeline.cpp` & `ProgramStateCache.h`**:
   - *Problem:* `ensure(m_handle == VK_NULL_HANDLE)` triggered a fatal verification crash when fragment programs were re-analyzed or recompiled across pipeline cache upgrades.
   - *Fix:* Added guard check in `shader::compile()` returning existing handle if already constructed, and safely destroying prior handle in `shader::create()`. In `ProgramStateCache.h`, only recompile when `inserted == true` during `try_emplace`.
2. **SPU Spin-Loop Starvation & SPU Deadlock (`SPU loop detection: false`)**:
   - *Problem:* With SPU loop detection disabled, SPURS worker thread `SPU[0x5000100]` spun in a tight polling loop at 100% CPU on lock synchronization barriers, starving the PPU render thread and causing frame timeouts.
   - *Fix:* Enabled `"Core@@SPU loop detection": true` in title defaults.
3. **PPU Trap Abort During Cutscene/Gameplay Handover (`SIGTRAP` in `PPUThread.cpp:3507`)**:
   - *Problem:* Like Uncharted 2, Naughty Dog's engine issues a debug/assertion PPU trap instruction during state handover between scene loading and gameplay. With `Stub PPU Traps = 0`, RPCS3 aborted `PPU[0x1000000] main_thread` with fatal error `PPU Trap!`.
   - *Fix:* Added `"Core@@Stub PPU Traps": 1` to title overrides.
4. **Scudo Allocator OOM / Virtual Map Exhaustion (`SIGABRT status 6`)**:
   - *Problem:* Enabling CPU readback buffers (`Write/Read Color Buffers` and `Write/Read Depth Buffers`) alongside unconstrained 6-thread concurrent LLVM JIT compilation consumed over 4.3 GB RSS and exhausted Android Scudo map counts.
   - *Fix:* Disabled WCB/RCB/WDB/RDB (unnecessary for Naughty Dog deferred pipeline) and constrained concurrent SPURS workers to `"Core@@Max SPURS Threads": 4`. RSS dropped to a stable ~3.0–3.4 GB.
5. **Vulkan Compute Pipeline Failure on Adreno Proprietary Driver (`res=-13 VK_ERROR_UNKNOWN`)**:
   - *Problem:* Qualcomm's proprietary system Vulkan driver fails `vkCreateComputePipelines` with `VK_ERROR_UNKNOWN (-13)` during post-processing compute passes.
   - *Fix:* Added `BCUS98174` (and `TLOU_COMPAT_FAMILY`) to `GpuDriverSelection.kt` so the bundled open-source Turnip 26.3 Mesa driver is automatically selected on Adreno 750 / Snapdragon 8 Gen 3 devices.

## Device Iterations (OnePlus 13R / Adreno 750)

1. *Baseline attempt:* System Adreno driver crashed with `Verification failed` in shader compiler, then stalled in RSX SPU kicks.
2. *Shader handle fix:* Advanced to warning screen, but stalled on 120s frame watchdog because SPU 5 spun at 100% CPU without loop detection.
3. *SPU loop detection enabled:* Reached title screen and main menu at 33 FPS. Accepted pad input, created persistent save game.
4. *Memory exhaustion diagnosis:* WCB/RCB + 6 SPURS threads triggered Scudo internal map failure (4.3 GB RSS). Reverted readbacks and capped SPURS threads to 4.
5. *Turnip auto-selection:* Resolved Qualcomm compute pipeline `-13` errors.
6. *PPU Trap stubbing:* Added `Stub PPU Traps: 1` preventing the fatal abort at cutscene transitions.
7. *In-game milestone:* Successfully rendered the full opening prologue sequence (Sarah couch scene, watch gift to Joel, bedroom transition) at rock-steady 30.0 FPS.

## Screenshots

- **Sarah opening scene:** `docs/games/BCUS98174/tlou-ingame-prologue-sarah.png`
- **Joel on the phone:** `docs/games/BCUS98174/tlou-ingame-prologue-joel.png`
- **Sarah on couch conversation:** `docs/games/BCUS98174/tlou-ingame-prologue-sarah-couch.png`
- **Joel opening watch gift:** `docs/games/BCUS98174/tlou-ingame-prologue-joel-watch.png`
- **Joel wearing watch with Sarah:** `docs/games/BCUS98174/tlou-ingame-prologue-joel-sarah-watch.png`
- **Joel carrying Sarah to bedroom:** `docs/games/BCUS98174/tlou-ingame-prologue-joel-carries-sarah.png`
- **Sarah in bed with nightstand lamp:** `docs/games/BCUS98174/tlou-ingame-prologue-sarah-bed.png`

## Reproduction

```bash
./scripts/debug-launch-game.sh SERIAL direct_iso/BCUS98174
./scripts/debug-monitor.sh SERIAL --ez enabled true --es preset Performance
./scripts/debug-pad.sh SERIAL START
./scripts/debug-pad.sh SERIAL CROSS
./scripts/debug-stop-game.sh SERIAL
./scripts/get-samba-logs.sh SERIAL /tmp/tlou-evidence
```
