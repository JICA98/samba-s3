# The Last of Us (BCUS98174) — Next Steps & Execution Plan

## 1. Executive Summary & Current State

| Metric / Phase | Observed Value | Status |
|---|---|---|
| **Title Screen (3D Window & Menu)** | 15.3–16.5 FPS (Foliage, sunbeams, curtains rendered) | **PASS** |
| **Narrative In-Game Sequences** | **30.1 FPS (33.4 ms frametime, APP CPU 332%, RSX 87%, PPU 78%)** | **TARGET ACHIEVED** |
| **Active Controllable Gameplay (Bedroom)** | 3.5–4.2 FPS (Sarah model, mirror reflections, input control) | **FUNCTIONAL / BOUND BY SPU** |
| **Hallway & Joel's Bedroom Door** | 3.5–4.2 FPS (Door unlocked via Triangle, traversed hallway to Joel's door & stairs) | **PROGRESSION VERIFIED** |
| **Telemetry & Overlay Routing** | Native overlay disabled; in-app Compose UI overlay active | **PASS** |
| **Audio Pipeline** | 48 kHz stereo continuous streaming via AAudio | **PASS** |
| **Crash / Assertion Handling** | Zero PPU trap aborts, zero RSX SPU kick deadlocks | **RESOLVED** |

---

## 2. Root Cause Diagnostics from Recent Testing

### A. Narrative In-Game vs. Active Gameplay Divergence
- **Narrative In-Game (Image 1 / Joel Living Room Scene):**
  - Reaches **30.1 FPS** (33.4 ms frametimes).
  - CPU usage is only **332%** (out of 800% max).
  - SPU physics, collision detection, and inverse-kinematics routines are idle or running minimal keyframe updates.
- **Active Gameplay (Sarah Traversal in Bedroom & Hallway):**
  - Framerate drops to **2.5–3.2 FPS** (330–430 ms frametimes).
  - CPU usage surges to **698%**.
  - `top -H` diagnostic confirms **8 heavy threads** competing simultaneously:
    - 6x `SPU[0xX000100]` worker threads (70–80% CPU each)
    - 1x `rsx::thread` (78.5% CPU)
    - 1x `PPU[0x1000000]` main thread (50–90% CPU)
  - Snapdragon 8 Gen 3 has only **6 performance cores** (1x Cortex-X4 + 5x Cortex-A720) and **2 weak Cortex-A520 efficiency cores**.
  - SPU contention forces heavy emulator worker threads onto the Cortex-A520 cores, dragging down the entire pipeline.

### B. Patch Analysis (`imported_patch.yml`)
- **`Enable GPU Lighting` (`tlou100_db`)**:
  - Offloads lighting effects from SPU to GPU, but on game version 01.00 it introduces blocky square lighting artifacts across indoor walls due to missing volumetric passes.
  - Recommendation: Disable `Enable GPU Lighting` on v01.00; keep native SPU lighting or calibrate custom Turnip shader replacements.
- **`Disable in-built MLAA` (`tlou100_mlaa`)**:
  - Successfully eliminates SPU morphological anti-aliasing passes without visual corruption.
- **Input Sampling at Low Framerates**:
  - At 2.5–3.0 FPS, a single frame takes 330–400 ms.
  - The default `debug-pad.sh` button pulse was 120 ms. Because 120 ms is shorter than a single guest frame, the guest engine was polling between pulse intervals and missing input.
  - Holding input for >=600 ms (or raw broadcast holds) guarantees reliable guest sampling.

### C. Thermal & Charging Impact
- During fast VOOC charging (+25W to +43W), device battery temperature quickly climbs to 41.5–42.5°C, triggering aggressive SoC throttling.
- When on standard USB trickle power (+5W to +8W) or on battery, temperature remains at 37–38°C, sustaining peak Cortex-X4 clock speeds (3.3 GHz).

---

## 3. Execution Record & Next Steps

### Step 1: Thread Affinity & Core Pinning Optimization (COMPLETED)
1. **Core Affinity Exclusion:**
   - Modified `thread_ctrl::get_affinity_mask()` in `rpcs3/util/Thread.cpp` on ARM64 Android to exclude CPU cores 0–1 (Cortex-A520 little cores) for `thread_class::spu`, `thread_class::ppu`, and `thread_class::rsx`.
   - Updated `CPUThread.cpp`, `RSXThread.cpp`, and `RSXOffload.cpp` to enforce affinity mask application under Android regardless of scheduler mode.
   - Verified via `ps -o TID,PSR,%CPU,COMM`: SPU workers, PPU main, and RSX threads are pinned strictly to Cortex-A720 and Cortex-X4 performance cores (Cores 2–7). Active traversal frametime improved from 348 ms down to 228–271 ms (framerate improved to 3.5–4.2 FPS).
2. **Telemetry Routing:**
   - Disabled native emulator HUD overlay (`Performance Overlay@@Enabled: false`).
   - Enabled in-app Compose UI monitoring overlay (`DEBUG_MONITOR_SET --ez enabled true --es preset Performance`), routing live FPS, frame times, CPU, and battery metrics.
3. **PPU Compiler Thread Throttling:**
   - Restricted `Core@@Max LLVM Compile Threads` to 1 in `GameSettingsOverrides.kt` to prevent runtime memory exhaustion during background compilation.

### Step 2: Traverse from Second-Floor to Downstairs Living Room (IN PROGRESS)
1. **Hallway Navigation:**
   - Sarah navigated past bedroom doorway, down the upstairs hallway to Joel's bedroom door and the staircase landing (`docs/games/BCUS98174/tlou-ingame-joel-bedroom-door.png`).
2. **Staircase & Ground-Floor Trigger:**
   - Descend the staircase to trigger the living room streaming zone where Joel is on the phone.
   - Confirm real-time narrative gameplay handoff sustaining 30.1 FPS as verified in reference benchmarks.

### Step 3: Turnip Vulkan Zero-Copy Readback & SPU Offload (NEXT ARCHITECTURAL STEP)
1. **Evaluate Color Buffer Readback Overhead:**
   - On Snapdragon unified memory (UMA), physical memory is shared between CPU and GPU.
   - Implement host-visible coherent buffer mapping for `Read Color Buffers` in `VKRenderTargets.cpp` to avoid synchronous GPU stall fences during color buffer copyback.
2. **SPU Job Batching / Throttling:**
   - Profile the 6 SPURS worker threads during traversal to determine whether non-critical collision / cloth simulation jobs can be yielded or decoupled without animation desync.
