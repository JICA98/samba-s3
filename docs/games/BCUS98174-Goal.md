# The Last of Us (BCUS98174) — Project Goal & Performance Target

## 1. Primary Objective
Achieve sustained, stable **30.0 FPS** (33.3 ms frametime cadence) playable in-game performance for *The Last of Us* (`BCUS98174`) on mobile ARM64 hardware (Snapdragon 8 Gen 3 / Adreno 750), with:
- Zero rendering regressions (no black viewports, no missing geometry, no corrupt lighting tiles or green rain artifacts).
- Uninterrupted audio delivery (48 kHz stereo streaming via AAudio with zero buffer underruns).
- Crash-free runtime progression through the entire prologue (bedroom, second-floor hallway/foyer, downstairs living room, Tommy's car escape) into Boston quarantine zone.
- Thermal equilibrium below OS thermal throttling thresholds (battery temperature <38.0°C, CPU temperature <75.0°C).

---

## 2. Target Hardware & Platform Specification

| Component | Target Baseline Specification |
|---|---|
| **Device** | OnePlus 13R (`CPH2691` / `OP5D3BL1`) |
| **SoC** | Qualcomm Snapdragon 8 Gen 3 (`SM8650-AB`) |
| **CPU Architecture** | 1x 3.3 GHz Cortex-X4 (Prime) + 5x 3.2 GHz Cortex-A720 (Performance) + 2x 2.3 GHz Cortex-A520 (Efficiency) = 8 cores total |
| **GPU** | Qualcomm Adreno 750 |
| **Vulkan Driver** | Mesa Turnip 26.3.0 (`tu` driver, Vulkan 1.3) |
| **Operating System** | Android 16 (API 37) |
| **RAM / VRAM** | 12 GB LPDDR5X (Unified Memory Architecture - UMA) |
| **Title Version** | *The Last of Us* Disc v01.00 (`BCUS98174`, EBOOT hash `PPU-9df60dc1aa5005a0c80e9066e4951dc0471553e6`) |

---

## 3. Playable Target Criteria

A session is considered meeting the 30.0 FPS target when all of the following conditions are verified simultaneously:
1. **Interactive Framerate:** Controllable character traversal (Sarah walking, Joel navigating) sustains **30.0 ± 1.5 FPS** over a continuous 120-second gameplay window without stuttering below 24 FPS.
2. **Cutscene Pacing:** Real-time in-game cinematics and narrative sequences (e.g. Joel phone conversation downstairs) maintain rock-solid **30.0–30.1 FPS** (33.3–33.4 ms frametimes) with synchronized audio.
3. **Visual Integrity:**
   - Full deferred lighting, point lights, and flashlights render accurately without blocky grid patterns or blacked-out areas.
   - Mirror reflections in bedroom and bathroom render correctly.
   - Water, foliage, and transparent overlays (curtains, smoke) render without alpha cutoff or clipping regressions.
4. **Input Responsiveness:** Gamepad button presses (Cross, Triangle, Square, Circle) and analog stick movements are acknowledged and sampled within 1–2 guest frames (<66 ms latency).
5. **Clean Session Management:** Game boots reliably from MainActivity/GameLaunchCenter without manual cache clearing, and shuts down cleanly within 1000 ms via `DEBUG_STOP_GAME`.
