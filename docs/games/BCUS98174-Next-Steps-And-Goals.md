# The Last of Us (BCUS98174) — Detailed Execution Plan & Next Steps

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


## 1. Executive Summary & Verification State

| Metric / Phase | Target Value | Observed Value | Status |
|---|---|---|---|
| **Title Screen (3D Window & Menu)** | 30.0 FPS | 15.3–16.5 FPS (Foliage, sunbeams, curtains) | Functional |
| **Load / Save Game Menu Overlays** | 30.0+ FPS | **30.0–58.5 FPS (16.2–33.3 ms frametime)** | **TARGET ACHIEVED** |
| **Narrative In-Game Sequences** | 30.0 FPS | **30.1 FPS (33.4 ms frametime, APP CPU 332%)** | **TARGET ACHIEVED** |
| **Active Controllable Gameplay (Hallway)** | **30.0 FPS** | **3.0–4.2 FPS (228–349 ms frametime)** | **NOT YET ACHIEVED (BOUND BY SPU)** |
| **Visual Fidelity (Artifacts/Glitches)** | Zero corruption | **Zero block artifacts / 100% clean 3D** (`Strict Rendering Mode: true`) | **ACHIEVED** |
| **Save/Load Lifecycle** | Durable across boots | Save Game 1, 2, 3 created & verified; AutoLoad verified | **ACHIEVED** |
| **Patch Fast Mode** | One-click Home UI toggle | Integrated into `GameLaunchCenter.kt` | **ACHIEVED** |
| **Core Affinity Pinning** | Cores 2–7 (Perf cores) | Excluded Cortex-A520 little cores 0–1 | **ACHIEVED** |
| **Watchdog & Compile Threads** | Clean boot without false timeout | `Compile Threads: 2`, `Watchdog: 240s` | **ACHIEVED** |

---

## 2. Detailed Technical Next Steps

### Step 1: Terminate Game on In-Emulator Crash / Freeze (Immediate Priority)
- **Problem:** When a frame timeout or deadlocked SPU loop occurs, the emulator displays an in-emulator toast:
  `"The PS3 application has likely crashed, you can close it."` (`EMULATION_FROZEN` in `rpcsx-android.cpp:735`).
- **Required Action:**
  1. In `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp`, intercept `EMULATION_FROZEN` event.
  2. Invoke `finish()` or send `DEBUG_STOP_GAME` broadcast directly to stop the core and return the user immediately to `MainActivity` with error classification, eliminating the in-emulator informing banner.

### Step 2: Overcoming the 3.5–4.2 FPS SPU Bottleneck in Active Traversal
- **Working Hypothesis (job-level profiling required):**
  - In active traversal, 6 SPU worker threads + 1 PPU thread + 1 RSX thread compete for 6 Cortex-A720/X4 performance cores.
  - The SPU threads run collision detection and continuous cloth/hair vertex deformation passes for Sarah.
- **Optimization Strategy:**
  1. **SPU Job Interleaving / Decoupling:**
     - Downsample secondary cloth simulation passes from 60 Hz to 30 Hz in `SPUThread.cpp` or via PPU memory patch, only after profiling identifies those jobs and verifies their dependencies; no percentage improvement is established.
  2. **Fast-path Lockless FIFO with Selective Memory Fences:**
     - Calibrate `Core@@RSX FIFO Accuracy` to maintain atomic ordered kicks exclusively for SPU-to-RSX kick rings while running general draw command processing lock-free.
  3. **Vulkan Zero-Copy Color Readback:**
     - In `VKRenderTargets.cpp`, map the color buffer memory directly as host-visible coherent memory on Adreno 750 UMA architecture, avoiding GPU readback pipeline stalls.

### Step 3: Traversal to Ground-Floor Living Room
- **Progression Plan:**
  1. Load existing save slot `Save Game 3` (placed directly in upstairs hallway outside Joel's bedroom).
  2. Traverse down the staircase to cross the streaming cell trigger.
  3. Enter the ground-floor living room where Joel receives the telephone call.
  4. Measure and document frametime transition handoff into the 30.1 FPS narrative sequence.

---

## 3. Reference Test Scripts

```bash
# Launch game directly into emulation
./scripts/debug-launch-game.sh d30a1726 direct_iso/BCUS98174

# Activate real-time in-app monitoring overlay
adb -s d30a1726 shell am broadcast -a com.zenithblue.sambas3.DEBUG_MONITOR_SET --ez enabled true --es preset Performance

# Navigate controller
./scripts/gamepad.sh d30a1726 press START
./scripts/gamepad.sh d30a1726 hold CROSS 0.5

# Clean termination
./scripts/debug-stop-game.sh d30a1726
```
