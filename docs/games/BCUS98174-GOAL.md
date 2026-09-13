# The Last of Us (BCUS98174) — Project Goal & Performance Objectives

## September 13 continuation: frozen core recovered, source fix pending device validation

- The resumed launch was rejected with `nativeState=Frozen` in PID 7058 at
  17:53:18 IST. This was an existing failed session, not a new gameplay sample.
- Evidence collected before stopping: `/tmp/tlou-resume-20260913-existing-session`.
  `cache-RPCSX.log` records main-thread read at unmapped `0x42` and Save/Load
  Game Thread read at unmapped `0x3b906052`. These faults do not establish that
  save data is corrupt; save creation and restore must be retested.
- `debug-stop-game.sh` acknowledged `stop completed ok=true`; bridge evidence:
  `/tmp/samba-bridge-VNX7Ir/logcat.txt`. The bounded wrapper ended after 110.5 s.
  No new traversal FPS measurement was obtained.
- Source change: Android `on_pause` emits frontend event 10 for frozen state;
  `System.cpp` suppresses the permanent frozen overlay. Kotlin routes that event
  and an older-core state-poll fallback to persisted crash evidence and clean
  process recovery, bypassing unsafe native teardown.
- Validation: incremental ARM64 `rpcsx-android` native target built successfully
  (existing compiler warnings); focused standard unit tests passed, 5 tests,
  including frozen-core and frame-timeout manifest finalization and Patch Fast
  Mode. Logs: `/tmp/tlou-freeze-native-build.log`, `/tmp/tlou-freeze-tests.log`.
  This is build/unit evidence, not proof of on-device recovery or 30 FPS.
- Regenerated the persistent native patch, excluding the generated
  `samba-build-id.cpp`; its stale stamp previously failed the reverse check.
- No SPU job limiter was introduced. Per-thread CPU utilization cannot identify
  cloth work or prove that dropping jobs preserves guest barriers and collision.
  Identify guest job entry points and measure time in useful work versus waits
  before attempting selective frequency changes. Keep six SPURS workers and
  Atomic FIFO, given the documented failures with fewer workers and Fast FIFO.
- Only OnePlus USB `d30a1726` was connected. Two-device release deployment and
  on-device freeze, save/load, visual and traversal validation remain outstanding.


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

## 3. Bottleneck Hypotheses for Active Traversal

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

## 4. Proposed Experiments (not measured performance gains)

1. **SPU Physics/Cloth Throttling & Decoupling:**
   - Implement an SPU job frequency limiter for non-critical cloth and secondary collision simulation jobs during traversal, freeing 2 SPU worker cores for critical animation and RSX kick threads.
2. **UMA Zero-Copy Color Buffer Mapping:**
   - Replace staged buffer copy readbacks with host-visible coherent memory mapping in `VKRenderTargets.cpp` with explicit GPU completion and host visibility synchronization; unified memory does not make concurrent GPU writes and CPU reads safe.
3. **Automatic Game Termination on Core Freeze:**
   - In `rpcsx-android.cpp`, hook `EMULATION_FROZEN` to trigger immediate, clean activity finish and home exit rather than displaying in-emu banner.
