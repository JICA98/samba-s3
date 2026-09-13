# The Last of Us (BCUS98174) — Next Steps & Execution Goal

## 1. Primary Goal

The overarching objective is to achieve a stable **30.0 FPS playable in-game experience** for *The Last of Us* (`BCUS98174`) on mobile ARM64 platforms (specifically OnePlus 13R, Snapdragon 8 Gen 3 / Adreno 750), eliminating graphical regressions (black screens, corrupted deferred lighting), resolving synchronization deadlocks (SPURS starvation, SPU RSX kicks timeouts), and maintaining thermal stability without device emergency throttling.

---

## 2. Current Status & Root Causes Discovered

| Issue | Root Cause | Implemented Solution | Result |
|---|---|---|---|
| **Black 3D Screen** (Title menu & Bedroom gameplay) | Game deferred renderer relies on CPU reading back color buffers to compute lighting passes and mirror reflections | Enabled `"Video@@Read Color Buffers": true` and `"Video@@Handle RSX Memory Tiling": true` | **FIXED:** 3D Title screen window (vines, foliage, sunlight) and Sarah's bedroom (mirrors, lamp, furniture) render with full textures and dynamic lighting |
| **SPU RSX Kick Timeouts** (`RsxKick: *** Timeout while waiting ... on RSX SPU kicks ***`) | `Max SPURS Threads` was restricted to 4, starving SPU 4 (RSX command kicks) and SPU 5 (animation processing) | Set `"Core@@Max SPURS Threads": 6` and `"Video@@Driver Wake-Up Delay": 1` | **FIXED:** Zero SPU kick timeouts; animation jobs complete on time; draw calls flushed continuously |
| **PPU Trap Abort** (`SIGTRAP` at `PPUThread.cpp:3507`) | Naughty Dog assertion trap during cutscene/gameplay handover | Set `"Core@@Stub PPU Traps": 1` | **FIXED:** Handover assertion stubbed safely; execution continues |
| **Vulkan Compute Pipeline Crash** (`res=-13 VK_ERROR_UNKNOWN`) | Qualcomm proprietary driver bug compiling compute shaders | Auto-selected bundled Turnip 26.3 Mesa driver on Adreno 750 via `GpuDriverSelection.kt` | **FIXED:** Compute passes compile cleanly |
| **Low Framerate during Bedroom Gameplay (~2.5 FPS)** | Heavy continuous CPU workload (6 SPURS threads + 1280x720 CPU buffer readbacks) pushed device temperatures to 47.0°C battery / 95°C CPU, triggering Android OS Thermal Status 5 (emergency downclock of Cortex-X4/A720 cores to 400–800 MHz) | Hardware thermal saturation | **IDENTIFIED:** Target for next optimization phase |

---

## 3. Highly Detailed Next Steps

### Step 1: Integrate RPCS3 Performance Game Patches
*The Last of Us* includes widely adopted community patches that drastically reduce CPU/SPU load and eliminate SPU lighting bottlenecks:
1. **Enable GPU Lighting (`tlou100_db`)**:
   - Offloads lighting effects from SPU threads to the RSX / Vulkan GPU pipeline.
   - PPU patch offset for v01.00 (`PPU-9df60dc1aa5005a0c80e9066e4951dc0471553e6`):
     ```yaml
     - [ be16, 0x00a7a7b8, 0x90e3 ]
     - [ be16, 0x00a516ec, 0x4800 ]
     ```
   - **Benefit:** Reduces SPU 5 CPU load by ~40%, lowering device thermal generation and directly increasing framerate.
2. **Disable in-built MLAA (`tlou100_mlaa`)**:
   - Bypasses the compute-intensive morphological anti-aliasing SPU pass.
   - **Benefit:** Frees up massive SPU compute cycles during active camera movement.
3. **Automated Patch Deployment**:
   - Bundle `imported_patch.yml` into SambaS3 or expose an automated debug intent (`DEBUG_PATCH_IMPORT` / `DEBUG_PATCH_SET`) to enable these patches on boot for `BCUS98174`.

### Step 2: Thermal Management & Thread Throttling
1. **PPU/SPU Thread Affinity & Priority**:
   - Pin PPU `main_thread` and RSX worker threads to high-performance cores (Cortex-X4 and Cortex-A720 prime cores) using `pthread_setaffinity_np` or Android task profiles.
   - Pin background compiler threads to efficiency cores (Cortex-A520).
2. **Device Cooling & Benchmark Environment**:
   - Validate performance from a cold boot (<35°C) with an external cooling fan or peltier attachment to prevent Thermal Status 5 downclocking during long gameplay segments.

### Step 3: Buffer Readback Optimization on Unified Memory
1. **Turnip Vulkan Zero-Copy Readback**:
   - On Snapdragon unified memory (UMA), the CPU and GPU share physical DRAM.
   - Investigate whether `VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT` can be leveraged directly for `Read Color Buffers` without explicit staging buffer copies and full pipeline stalls.

### Step 4: Progression Testing Through Prologue Climax
1. **Hallway and Downstairs Transition**:
   - Guide Sarah out of the bedroom into the hallway, down the stairs, and into the living room TV news scene.
2. **Joel & Tommy Car Escape**:
   - Validate that driving scenes (high object density, particle explosions, fire/smoke shaders) maintain target frametimes.
3. **Savestate Integrity**:
   - Validate savestate creation and restoration at each checkpoint.
