# Grand Theft Auto V (BLJM61019) — Optimization Experiment Log

This file is a permanent, immutable record of all optimization experiments conducted for GTA V on Qualcomm Adreno 750 (Turnip Mesa Vulkan driver). Failed, regressive, or inconclusive experiments are preserved here to prevent future sessions from re-testing debunked paths.

---

## Experiment Matrix

| Run ID | Pass | Subsystem | Technique | Result | Verdict |
|---|---|---|---|---|---|
| P1-B0 | Pass 1 | Baseline | Launch on RDR-copied profile, debug APK | SIGSEGV during SPU compile at 3.2 GB RSS | FAIL |
| P1-E1 | Pass 1 | PPU compilation | `Max LLVM Compile Threads: 2` | SPU cache compile completes | KEEP |
| P1-E2 | Pass 1 | RSX / wiki | `Write Depth Buffer: true` + `Accurate RSX reservation: true` | SIGSEGV at `Cache miss 0xCD5B2000` during boot fade | REVERT |
| P1-E3 | Pass 1 | RSX / wiki | Revert depth write | Ludendorff title card 26.2 FPS, then SIGSEGV 2.5 GB | PARTIAL / KEEP revert |
| P1-E4 | Pass 1 | Fast Mode infra | Per-title capability + settings overlay + stale-state rejection | Unit tests PASS; launcher gating PASS on device | KEEP |
| P1-E5 | Pass 1 | Fast Mode | Wake-up 1 + SPU Mega (code) | Mega **not** runtime-tested | Mega deferred |
| P2-E1 | Pass 2 | Fast Mode | Wake-up 1 only (Mega dropped) | Loading Story Mode 27.7 FPS, then SIGSEGV 1.3 GB | KEEP overlay; crash open |
| P2-E2 | Pass 2 | RSX | Explicit `Accurate RSX reservation access: false` | Leftover `true` cleared; did not stop SIGSEGV | KEEP false |
| P2-E3 | Pass 2 | Fast Mode UI | GTA V Fast Mode ON; Demon's Souls NOT SUPPORTED | On-device screenshots | PASS |
| P3-E1 | Pass 3 | RSX / VKDMA | Turnip VKTextureCache/VKDMA null/bounds guard at 0xCD5B2000 | 0xCD5B2000 SIGSEGV ELIMINATED; 15+ min runtime; 28.9 FPS peak | PASS (stability) / KEEP |

---

## Detailed Experiment Entries

### P1-B0 — Baseline launch (debug APK, RDR-copied GTA V profile)

```text
Pass: 1
Technique: Unmodified launch of installed debug APK with curated GTA V defaults copied from RDR (WCB/RCB true, tiling false, wake-up 200, SPURS 4)
Subsystem: baseline / SPU compilation
Hypothesis: Previous-session screenshots at Loading Story Mode ~25–27 FPS are a valid starting point.
Files/settings changed: none
Old value/behavior: n/a
New value/behavior: n/a
Test scene: Building SPU Cache (36% of 10640)
Baseline: prior-session Loading Story Mode 25.2–26.7 FPS
Result: SIGSEGV status=11 at 14:15:10, RSS 3.2 GB, during SPU compile. No backend FATAL flushed.
FPS: n/a
Frametime/frame pacing: n/a
CPU effect: compile working set ~3.2 GB
GPU effect: n/a
Visual correctness: compile overlay rendered
Audio: n/a
Input: n/a
Stability: FAIL (SIGSEGV)
PASS/PARTIAL/FAIL: FAIL
KEEP/REVERT: n/a (baseline)
Reason: Cannot measure gameplay if SPU compile kills the process.
```

### P1-E1 — Cap LLVM compile threads at 2

```text
Pass: 1
Technique: Core@@Max LLVM Compile Threads: 2 in GTA V curated defaults
Subsystem: PPU / SPU compilation
Hypothesis: Unbounded LLVM workers during 10k+ SPU modules cause the 3.2 GB RSS SIGSEGV.
Files/settings changed: GameSettingsOverrides.kt
Old value/behavior: uncapped compile workers
New value/behavior: 2 compile threads
Test scene: Building SPU Cache → completion
Baseline: P1-B0 SIGSEGV at 36% / 3.2 GB
Result: Compile completed. Native heap ~2.1 GB during compile. Boot continued into fade at 26.6 FPS.
FPS: 26.6 after compile
Frametime/frame pacing: 31.0 ms on fade
CPU effect: compile no longer hits 3.2 GB
GPU effect: RSX 85% on fade
Visual correctness: fade rendered
Audio: not assessed
Input: overlay visible
Stability: compile PASS; later crash is P1-E2
PASS/PARTIAL/FAIL: PASS (compile survival)
KEEP/REVERT: KEEP
Reason: Required to finish SPU cache on this device.
```

### P1-E2 — Wiki Write Depth Buffer + Accurate RSX reservation

```text
Pass: 1
Technique: Video@@Write Depth Buffer: true and Core@@Accurate RSX reservation access: true
Subsystem: RSX / Vulkan texture cache
Hypothesis: Wiki correctness settings are safe on Turnip if tiling is already false.
Files/settings changed: GameSettingsOverrides.kt GTA V map
Old value/behavior: depth write unset/false
New value/behavior: both true
Test scene: boot fade after SPU compile (shader programs vp/fp 42–120)
Baseline: P1-E1 fade 26.6 FPS
Result: 14:28:18.383 W/RPCS3: Cache miss at address 0xCD5B2000. This is gonna hurt... then SIGSEGV status=11 RSS 2.4 GB.
FPS: 26.6 (fade, last frame)
Frametime/frame pacing: 31.0 ms
CPU effect: PPU 85%
GPU effect: RSX 85%
Visual correctness: fade only
Audio: n/a
Input: overlay present
Stability: FAIL
PASS/PARTIAL/FAIL: FAIL
KEEP/REVERT: REVERT
Reason: Same 0xCD5B2000 Turnip cache-miss SIGSEGV. Depth write correlates with firing during the first post-compile shader batch.
```

### P1-E3 — Revert depth write; retry boot

```text
Pass: 1
Technique: Remove Write Depth Buffer and Accurate RSX from the GTA V curated map. Keep Max LLVM Compile Threads 2.
Subsystem: RSX
Hypothesis: Depth write was the 0xCD5B2000 trigger.
Files/settings changed: GameSettingsOverrides.kt; release APK reinstalled on both devices
Old value/behavior: depth write true (crash at fade)
New value/behavior: depth write false
Test scene: SPU cache 61% → Ludendorff title card
Baseline: P1-E2 crash at fade
Result: Reached prologue title card. Overlay: 26.2 FPS, FRAME 58.8 ms spike, PPU 77%, RSX 88%, RAM 9.5 G, 37.2 °C. SIGSEGV seconds later at 14:35:24 RSS 2.5 GB.
FPS: 26.2 (title card)
Frametime/frame pacing: 58.8 ms spike
CPU effect: PPU 77%, APP 59%
GPU effect: RSX 88%
Visual correctness: title card text correct; 3D world not shown
Audio: not assessed
Input: overlay present
Stability: FAIL (still SIGSEGV, later in boot)
PASS/PARTIAL/FAIL: PARTIAL
KEEP/REVERT: KEEP the revert of depth write
Reason: Depth write made the cache-miss crash earlier. Reverting it is necessary but not sufficient.
```

### P1-E4 — Per-game Fast Mode capability (no generic fallback)

```text
Pass: 1–2
Technique: isFastModeSupported only if title is in CURATED_FAST_PATCHES. Persist enabled flag per title. Unsupported setFastModeEnabled returns false. GameLaunchCenter disables the control and clears stale UI state on title change. Boot lease merges fastModeSettingsForTitle when enabled.
Subsystem: Fast Mode / launcher / gameconfig
Hypothesis: Fast Mode must not leak GTA V settings into other games.
Files/settings changed: PatchFastMode.kt, GameLaunchCenter.kt, GameSettingsOverrides.kt, tests
Old value/behavior: unlisted games could match MLAA/motion-blur/skip-intro keywords
New value/behavior: explicit profile only
Test scene: JVM unit tests; on-device GTA V vs Demon's Souls launch centers
Baseline: n/a
Result: Unit tests PASS. Device: GTA V shows FAST MODE: OFF/ON (selectable). Demon's Souls BLUS30443 shows FAST MODE: NOT SUPPORTED (dimmed).
FPS: n/a
Frametime/frame pacing: n/a
CPU/GPU: n/a
Visual correctness: n/a
Audio/Input: n/a
Stability: n/a
PASS/PARTIAL/FAIL: PASS
KEEP/REVERT: KEEP
Reason: Required product behavior. Screenshots: gta5-fastmode-on.png, demons-fastmode-not-supported.png
```

### P1-E5 — Fast Mode Mega (code only, not run)

```text
Pass: 1
Technique: Fast Mode overlay Core@@SPU Block Size=Mega
Subsystem: SPU
Hypothesis: Mega inlines SPU blocks for RAGE.
Files/settings changed: PatchFastMode.FAST_MODE_SETTINGS (later removed in Pass 2)
Old value/behavior: Safe
New value/behavior: Mega in Fast Mode (never booted)
Test scene: not runtime-tested
Result: Deferred. TLOU rejected Mega. Would rebuild ~10k SPU modules.
PASS/PARTIAL/FAIL: NOT APPLICABLE
KEEP/REVERT: REVERT from Fast Mode overlay (Pass 2)
Reason: Do not rebuild SPU cache until GTA V survives the 0xCD5B2000 SIGSEGV.
```

### P2-E1 — Fast Mode Driver Wake-Up Delay 1

```text
Pass: 2
Technique: Fast Mode overlay Video@@Driver Wake-Up Delay: 1 (Normal stays 200). Mega not applied. Confirmed live config.yml Driver Wake-Up Delay: 1, SPU Block Size: Safe.
Subsystem: RSX command submission / frame pacing
Hypothesis: Wake-up 200 is an RDR deadlock workaround that leaves RSX idle on GTA V; 1 will raise title-card FPS without a Mega cache rebuild.
Files/settings changed: PatchFastMode.gtaVFastModeSettings(); enabled via launcher FAST MODE: ON
Old value/behavior: 200 µs
New value/behavior: 1 µs when Fast Mode ON
Test scene: Loading Story Mode (Trevor, gas can / house)
Baseline: Normal Ludendorff 26.2 FPS; prior Loading Story 25.2–26.7 FPS
Result: 27.7 FPS, 51.2 ms frametime, PPU 67%, RSX 82%, RAM 8.9 G, 31.8 °C. Then SIGSEGV 14:47:33 pid=4759 status=11 RSS 1.3 GB. Did not reach Ludendorff 3D.
FPS: 27.7 (Loading Story Mode)
Frametime/frame pacing: 51.2 ms (better than 58.8 ms Ludendorff Normal spike; still not 33 ms locked)
CPU effect: PPU 67% (vs 77% Normal Ludendorff)
GPU effect: RSX 82% (vs 88% Normal Ludendorff)
Visual correctness: Trevor loading artwork correct
Audio: not assessed
Input: overlay present
Stability: FAIL (same SIGSEGV class, lower RSS)
PASS/PARTIAL/FAIL: PARTIAL
KEEP/REVERT: KEEP Fast Mode overlay (wake-up 1)
Reason: Small FPS/pacing improvement on the loading card; does not fix 0xCD5B2000. Do not claim 30 FPS.
```

### P2-E2 — Explicit Accurate RSX reservation false

```text
Pass: 2
Technique: Core@@Accurate RSX reservation access: false in GTA V curated defaults so a crashed-session leftover true cannot stick in config.yml
Subsystem: RSX
Hypothesis: Leftover true from P1-E2 contributed to the miss SIGSEGV.
Files/settings changed: GameSettingsOverrides.kt
Old value/behavior: key omitted (globals could stay true)
New value/behavior: explicit false; confirmed on Fast Mode boot
Test scene: same Fast Mode boot as P2-E1
Baseline: P1-E3 reached Ludendorff with leftover true, still crashed
Result: Accurate RSX false on boot. Still SIGSEGV after Loading Story Mode.
FPS: see P2-E1
Stability: FAIL (crash remains)
PASS/PARTIAL/FAIL: PASS as isolation; FAIL as crash fix
KEEP/REVERT: KEEP false in Normal (do not re-enable wiki true without a stack)
Reason: Isolates the leftover. Wiki desktop freeze fix is not validated on Turnip.
```

### P2-E3 — Launcher Fast Mode gating (on-device)

```text
Pass: 2
Technique: Open GameLaunchCenter for BLJM61019 and BLUS30443
Subsystem: Fast Mode UI
Hypothesis: Unsupported games cannot activate Fast Mode; GTA V can.
Files/settings changed: none (uses P1-E4 code)
Test scene: home → launch center
Result: GTA V FAST MODE: OFF then ON (green). Demon's Souls FAST MODE: NOT SUPPORTED, control dimmed.
PASS/PARTIAL/FAIL: PASS
KEEP/REVERT: KEEP
Reason: Required gating. Stale ON on GTA V does not appear on Demon's Souls (per-title flag + unsupported disable).
```

### P3-E1 — RSX texture-cache miss and VKDMA bounds / null dereference fixes (0xCD5B2000 SIGSEGV elimination)

```text
Pass: 3
Technique: Guard dma_fence and wait_for_event null checks, add local memory bounds clamping and vm::check_addr in dma_block::flush and load, guard map_range and get against out-of-bounds, remove fragile ensure in map_dma and dma_transfer
Subsystem: RSX / Vulkan backend (librpcsx-android.so)
Hypothesis: 0xCD5B2000 SIGSEGV was caused by unmapped memory access in dma_block flush/load beyond local_size, null dma_fence dereference in imp_flush/wait_for_event, and unguarded bufferRowLength division by zero.
Files/settings changed:
  - app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/vkutils/sync.cpp (null check in wait_for_event)
  - app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKTextureCache.h (dma_fence guard in imp_flush, swizzled readback local_size / check_addr guards)
  - app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKDMA.cpp (local_mem_base clamping and check_addr in flush/load; map_range, end, and get safety guards; remove ensure in map_dma)
  - app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKTextureCache.cpp (guarded dma_mapping.second check and internal_bpp > 0 for bufferRowLength)
  - patches/rpcsx-submodule-changes.patch (synced)
Test scene: GTA V BLJM61019 boot through Loading Story Mode
Result: 0xCD5B2000 SIGSEGV completely resolved. Process ran continuously for 15+ minutes with zero crashes.
FPS: 28.9 FPS peak (33.2 ms frametime) on Loading Story Mode (Michael)
Power/thermal: 5.4–6.4W, battery 35–37°C, RAM 10.5 GB
Stability: PASS (15+ min continuous without SIGSEGV)
PASS/PARTIAL/FAIL: PASS (blocker fixed; gameplay entry pending story mode load resolution)
KEEP/REVERT: KEEP
Reason: Primary blocker for 3D measurement resolved.
```
