# Grand Theft Auto V (BLJM61019) — Next Session Handoff

## Current status

- **Latest commit:** `4489f534f624cbc3c2574c033b1a33a124fa2048`
- **Game/title ID:** `BLJM61019` (Japan disc, PARAM.SFO VERSION 01.00). Path: `direct_iso/BLJM61019`
- **GPU:** Qualcomm Adreno 750 / OnePlus 13R `d30a1726`
- **Driver:** Turnip 26.3 package
- **Passes completed:** **3/4** (Pass 4 is the next session)
- **Best Normal Mode FPS:** 26.2 FPS Ludendorff title card (58.8 ms spike); 26.6 FPS boot fade
- **Best Fast Mode FPS:** 28.9 FPS Loading Story Mode (Michael); 33.2 ms
- **30 FPS target status:** FAIL

---

## What has already been tried

1. Baseline without LLVM compile-thread cap: SIGSEGV during SPU compile at 3.2 GB RSS — FAIL.
2. `Max LLVM Compile Threads: 2`: SPU cache compile completes — KEEP.
3. Wiki `Write Depth Buffer: true` + `Accurate RSX reservation: true`: SIGSEGV at `Cache miss 0xCD5B2000` during boot fade — REVERT.
4. Depth-write revert: Ludendorff card 26.2 FPS, then SIGSEGV 2.5 GB — PARTIAL.
5. Per-game Fast Mode gating: unit tests + on-device GTA V ON / Demon's Souls NOT SUPPORTED — PASS.
6. Fast Mode `Driver Wake-Up Delay: 1`: Loading Story Mode 27.7 FPS, then SIGSEGV 1.3 GB — KEEP overlay; crash open.
7. Explicit `Accurate RSX reservation access: false`: leftover cleared; crash remains — KEEP false.
8. `SPU Block Size: Mega` in Fast Mode: **not runtime-tested**, removed from overlay — do not re-add until stable.
9. Skip-logo patches imported for BLJM61019 01.00 (`PPU-7b0b0796…`) and 02.24 (`PPU-e2d02081…`).
10. Pass 3 native Turnip fixes in `VKDMA.cpp`, `VKTextureCache.h/cpp`, `sync.cpp`: `0xCD5B2000` RSX texture-cache miss SIGSEGV completely ELIMINATED (15+ min continuous runtime, 28.9 FPS Loading Story Mode) — KEEP.

---

## Changes retained

- Native core fixes: `VKDMA.cpp` local_mem_base clamping, safe `map_range`, `end`, and `get`; `VKTextureCache.h/cpp` guarded `dma_fence` in `imp_flush`, swizzled readback bounds check, `internal_bpp` division-by-zero guard; `sync.cpp` `wait_for_event` null check.
- Per-game Fast Mode capability (`PatchFastMode`, `GameLaunchCenter` disable + stale UI clear).
- GTA V Fast Mode overlay: `Video@@Driver Wake-Up Delay: 1` only.
- GTA V Normal curated defaults: WCB/RCB true, tiling false, wake-up 200, SPURS 4, SPU loop, Relaxed ZCULL, async tex false, Max LLVM Compile Threads 2, Accurate RSX reservation **false**.
- On-device `imported_patch.yml` GTA V skip-logo entries (do not overwrite TLOU patches).

---

## Changes reverted

- `Video@@Write Depth Buffer: true` (0xCD5B2000 SIGSEGV trigger).
- `Core@@Accurate RSX reservation access: true` (wiki; now explicit false).
- Fast Mode `SPU Block Size: Mega` (never runtime-tested; deferred).

---

## Current dominant bottleneck

1. **Stability:** RSX `0xCD5B2000` SIGSEGV is **RESOLVED**.
2. **Transition to 3D Gameplay (blocker for Pass 4):** Game loops in `cellNetCtlGetInfo` network check during initial Loading Story Mode before 3D cutscene triggers.
3. **Performance (measured):** dual-bound RSX ~87–93% and PPU ~46–63% at 28.2–28.9 FPS on Loading Story Mode. Gameplay 3D scene (North Yankton) remains to be measured.

---

## Remaining problem scenes

- Transition past Loading Story Mode into North Yankton bank 3D prologue.
- Pause menu, on-foot, driving, heavy traffic.
- Same-scene Fast vs Normal once 3D is stable.

---

## Highest-priority next investigations

1. Resolve `cellNetCtlGetInfo` polling loop or supply a savegame past the prologue cutscene to enter 3D driving/on-foot directly.
2. Once North Yankton 3D renders: measure baseline FPS in 3D.
3. Re-evaluate `SPU Block Size: Mega`, RCB off, or SPURS tuning once 3D gameplay is reached.
4. Do not enable the GTA V 60 FPS patch.

---

## Do NOT repeat

- Do not re-enable `Write Depth Buffer` on this Turnip path.
- Do not use a generic Fast Mode keyword fallback.
- Do not apply the GTA V `60 FPS` patch.
- Do not uninstall the APK (`adb install -r` only — uninstall wipes `Android/data`).
- Do not `am force-stop` then cold-start `RPCSXActivity`.
- Do not claim untested regional IDs are verified.
- Do not add SPU Mega to Fast Mode until the SIGSEGV is gone (rebuilds 10k modules).

---

## Recommended next-session starting point

- Crash site: `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKTextureCache.h` `copy_texture` (miss=true) and `VKTextureCache.cpp` `dma_transfer`.
- Config: `GameSettingsOverrides.curatedDefaultsForTitle` GTA V branch; Fast Mode overlay in `PatchFastMode.gtaVFastModeSettings()`.
- Launch: `./scripts/debug-launch-game.sh d30a1726 direct_iso/BLJM61019`. If “STOPPED UNEXPECTEDLY”, tap **RETRY** or Dismiss X (Circle/BACK are unreliable on that dialog).
- Overlay: `./scripts/debug-monitor.sh d30a1726 --ez enabled true --es preset Performance`
- Evidence: `./scripts/get-samba-logs.sh d30a1726 /tmp/gta5-out` then grep `0xCD5B2000` in `logcat-sambas3.txt`.

---

## Reproduction/test route

1. Device `d30a1726`. Game `direct_iso/BLJM61019`.
2. Normal: Fast Mode OFF. Fast: launcher **FAST MODE: ON** (green).
3. Wait for SPU cache if it rebuilds. Keep `Max LLVM Compile Threads: 2`.
4. Measure overlay at Loading Story Mode and Ludendorff card.
5. Expect SIGSEGV unless the DMA miss path is fixed.
6. Gating check: GTA V Fast Mode selectable; Demon's Souls `BLUS30443` **NOT SUPPORTED**.
