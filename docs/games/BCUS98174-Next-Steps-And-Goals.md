# The Last of Us (BCUS98174) — Next Steps & Execution Plan

## 1. Executive Summary & Current State

| Metric / Phase | Observed Value | Status |
|---|---|---|
| **Title Screen (3D Window & Menu)** | 15.3–16.5 FPS (Foliage, sunbeams, curtains rendered) | **PASS** |
| **Narrative In-Game Sequences** | **30.1 FPS (33.4 ms frametime, APP CPU 332%, RSX 87%, PPU 78%)** | **TARGET ACHIEVED** |
| **Active Controllable Gameplay (Bedroom)** | 2.5–3.2 FPS (Sarah model, mirror reflections, input control) | **FUNCTIONAL / BOUND BY SPU** |
| **Hallway & Foyer Exploration** | 2.5–3.2 FPS (Door unlocked via Triangle hold, 2nd floor hallway traversed) | **PROGRESSION VERIFIED** |
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

## 3. Highly Detailed Next Steps

### Step 1: Thread Affinity & Core Pinning Optimization
1. **PPU & RSX Core Affinity:**
   - Modify thread initialization in `PPUThread.cpp` and `RSXThread.cpp` to explicitly bind `PPU[0x1000000]` and `rsx::thread` to CPU core 7 (Cortex-X4) and core 6 (Cortex-A720).
2. **SPURS Worker Affinity:**
   - Bind SPU threads 0–4 to CPU cores 2–5 (Cortex-A720).
   - Prevent the Linux kernel scheduler from scheduling guest worker threads onto the weak Cortex-A520 cores (CPU 0–1).
3. **PPU Compiler Thread Throttling:**
   - Restrict `Core@@Max LLVM Compile Threads` to 2 in `GameSettingsOverrides.kt` to prevent background shader/module recompilations from consuming memory and triggering Scudo out-of-memory aborts during boot.

### Step 2: Traverse from Second-Floor Foyer to Downstairs Living Room
1. **Hallway Navigation:**
   - Guide Sarah past the banister to the staircase landing.
   - Walk down the stairs to trigger the ground-floor streaming zone.
2. **Living Room Cutscene Trigger:**
   - Enter the living room where Joel is on the phone.
   - Measure framerate during the cinematic handover to verify that 30.0 FPS is sustained as observed in reference benchmarks.
3. **Autosave Checkpoint:**
   - Ensure the autosave checkpoint triggers at the base of the stairs, confirming save slot write integrity.

### Step 3: Turnip Vulkan Zero-Copy Readback
1. **Evaluate Color Buffer Readback Overhead:**
   - On Snapdragon unified memory (UMA), physical memory is shared between CPU and GPU.
   - Implement host-visible coherent buffer mapping for `Read Color Buffers` in `VKRenderTargets.cpp` to avoid synchronous GPU stall fences during color buffer copyback.
