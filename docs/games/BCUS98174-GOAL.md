# The Last of Us (BCUS98174) — Project Goal & Performance Objectives

## 1. Primary Goal
Achieve **sustained 30.0 FPS playable performance with zero visual glitches** during active, controllable gameplay (character traversal in bedroom, second-floor hallway, and downstairs living room) on OnePlus 13R (Snapdragon 8 Gen 3, Adreno 750, Turnip Mesa driver).

---

## 2. Target vs. Current Status

| Emulation Phase | Target Performance | Current Verified Result | Status |
|---|---|---|---|
| **Title Screen 3D Window** | Sustained 30.0 FPS | 15.3–16.5 FPS (Foliage, dynamic lighting) | In Progress |
| **Menu & Save/Load Screens** | Sustained 30.0+ FPS | **49.4–58.0 FPS (13.2–22.0 ms frametime)** | **TARGET ACHIEVED** |
| **Prologue In-Game Narrative** | Sustained 30.0 FPS | **30.1 FPS (33.4 ms frametime, APP CPU 332%)** | **TARGET ACHIEVED** |
| **Active Controllable Traversal** | **Sustained 30.0 FPS (33.3 ms)** | **3.5–4.2 FPS (228–271 ms frametime, bound by SPU)** | **NOT YET ACHIEVED (PRIMARY FOCUS)** |
| **Visual Rendering Quality** | Zero quads, zero blocks, zero artifacts | **Verified 100% clean** via `Strict Rendering Mode: true` | **ACHIEVED** |
| **Save/Load Progression** | Persistent save/load lifecycle | Save creation and restore validated across sessions | **ACHIEVED** |
| **Launcher Fast Mode** | One-click per-game patch preset | Integrated into Home `GameLaunchCenter.kt` | **ACHIEVED** |
| **Crash Handling** | Exit to Home cleanly on freeze | In-emulator frozen toast currently informs user | **PENDING NATIVE EXIT FIX** |

---

## 3. Bottleneck Analysis for Active Traversal

### Why Active Gameplay is 3.5–4.2 FPS While Narrative is 30.1 FPS:
1. **6 SPURS Worker Threads Contention:**
   - In narrative cutscenes, collision, cloth simulation, and physics are idle (CPU ~332%).
   - In active gameplay, Sarah's clothing simulation, character physics, and dynamic collision detection saturate all 6 SPURS worker threads at 70–85% CPU each.
   - Combined with PPU main thread (100–140%) and RSX thread (70–80%), total workload demands 8 heavy threads.
   - Snapdragon 8 Gen 3 has only **6 performance cores** (1x Cortex-X4 @ 3.3 GHz + 5x Cortex-A720 @ 3.0–3.15 GHz) and 2 weak Cortex-A520 efficiency cores.
2. **SPU Barrier & Kick Delays:**
   - Thread contention delays SPU worker completion, causing RSX draw call submission stalls.
3. **Color Buffer Readbacks:**
   - Unified memory architecture GPU-to-CPU color readbacks incur driver stall penalties when synchronization fences block the render pipeline.

---

## 4. Architectural Roadmap to Reach 30 FPS in Active Traversal

1. **SPU Physics/Cloth Throttling & Decoupling:**
   - Implement an SPU job frequency limiter for non-critical cloth and secondary collision simulation jobs during traversal, freeing 2 SPU worker cores for critical animation and RSX kick threads.
2. **UMA Zero-Copy Color Buffer Mapping:**
   - Replace staged buffer copy readbacks with host-visible coherent memory mapping in `VKRenderTargets.cpp` so SPU reads color buffers directly without synchronization fences.
3. **Automatic Game Termination on Core Freeze:**
   - In `rpcsx-android.cpp`, hook `EMULATION_FROZEN` to trigger immediate, clean activity finish and home exit rather than displaying in-emu banner.
