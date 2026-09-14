# Grand Theft Auto V (BLJM61019) — Qualcomm Adreno / Adreno 750

## Overview & Device Specifications

| Attribute | Value |
|---|---|
| **Game Title** | Grand Theft Auto V (GTA V) |
| **Title ID** | `BLJM61019` (Japan disc; PARAM.SFO `TITLE_ID=BLJM61019`, `VERSION=01.00`, `CATEGORY=GD`) |
| **Alternate Regional IDs** | Wiki-listed: `BLUS31156` (USA disc), `BLES01807` (Europe disc), `NPUB31154` (USA digital), `NPEB01283` (Europe digital), `NPJB00516` (Japan digital). Also mapped, **untested**: `NPUB31156`, `NPEB01807`, `NPJB00517`. |
| **Tested Game Version** | Direct ISO `Grand Theft Auto V (Japan) (En,Ja) (v02.00).iso`; on-device PARAM.SFO reports `VERSION=01.00`. Skip-logo patches imported for `01.00` and `02.24` PPU hashes. |
| **Tested IDs** | **Only `BLJM61019` has been launched.** Other IDs are profile-mapped, not verified. |
| **Device Model** | OnePlus 13R (`CPH2691` / `OP5D3BL1`), serial `d30a1726` |
| **SoC** | Qualcomm Snapdragon 8 Gen 3 (`SM8650`) |
| **GPU** | Qualcomm Adreno 750 |
| **Android Version** | Android 16 (API 36) |
| **GPU Driver** | Turnip Mesa Vulkan (`turnip-26.3` package; runtime identity `Turnip Adreno (TM) 750`) |
| **Samba-S3 Host Commit** | `4489f534f624cbc3c2574c033b1a33a124fa2048` |
| **RPCSX Core Revision** | `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc` |
| **Build Variant** | `standard` **release** (`RelWithDebInfo` core), installed on OnePlus 13R and Poco X6 Pro |

---

## Performance Summary

Overlay FPS is the in-app Performance monitor (`emu_flip`), not display interpolation.

| Metric / Phase | Normal Mode | Fast Mode (wake-up 1) | Target | Status |
|---|---|---|---|---|
| **SPU cache compile** | Completes with `Max LLVM Compile Threads: 2` | Completes (same cap) | n/a | KEEP |
| **Boot fade / legal** | 26.6 FPS, 31.0 ms, PPU 85%, RSX 85% | not separately snapshotted | 30 FPS | PARTIAL |
| **Loading Story Mode** | Prior-session 25.2–26.7 FPS (Michael) | **27.7 FPS**, 51.2 ms, PPU 67%, RSX 82% (Trevor) | 30 FPS | PARTIAL |
| **Prologue title card** (`Ludendorff, North Yankton, nine years ago.`) | **26.2 FPS**, 58.8 ms spike, PPU 77%, RSX 88% | crashed before / during next 3D load | 30 FPS | PARTIAL |
| **Main menu / pause** | Not reached | Not reached | 30 FPS | FAIL |
| **On-foot / driving / heavy** | Not reached | Not reached | 30 FPS | FAIL |

### Target Status
```text
30 FPS TARGET: FAIL
```

27.7 FPS on a 2D loading card is **not** a locked 30 FPS result. No on-foot or driving gameplay was measured. Both Normal and Fast Mode still SIGSEGV (`status=11`) while transitioning into the prologue 3D scene.

---

## Configuration

### Baseline Configuration (pre-Pass 1)
- Renderer: Vulkan
- PPU / SPU: LLVM Recompiler
- SPU Block Size: Safe
- WCB / RCB: true
- Handle RSX Memory Tiling: false
- Driver Wake-Up Delay: 200
- Max SPURS Threads: 4
- Relaxed ZCULL Sync: true
- SPU loop detection: true
- Asynchronous Texture Streaming 2: false

### Current Best Configuration — Normal Mode
Same as baseline, plus:
- `Core@@Max LLVM Compile Threads: 2`
- `Core@@Accurate RSX reservation access: false` (explicit; wiki `true` was not kept)

Explicitly **off** after Pass 1:
- `Video@@Write Depth Buffer` — SIGSEGV at `Cache miss at address 0xCD5B2000`

### Current Best Configuration — Fast Mode
Normal, plus overlay:
- `Video@@Driver Wake-Up Delay: 1` (validated Pass 2: Loading Story Mode 27.7 FPS vs ~26 FPS Normal-class samples; still SIGSEGV)
- Patch `Skip Rockstar Boot Logo` if PPU hash matches (imported; hash match not proven)

**Not** in Fast Mode (deferred): `SPU Block Size: Mega` (TLOU rejected; would rebuild 10k SPU modules before GTA V is stable).

---

## Frametime & Frame-Pacing Observations

- Fade/legal (Normal): ~31 ms, 26.6 FPS — already under the 30 FPS cap on a near-empty scene.
- Ludendorff card (Normal): 26.2 FPS, 58.8 ms spike. Dual-bound **RSX 88% / PPU 77%**.
- Loading Story Mode (Fast Mode): 27.7 FPS, 51.2 ms, RSX 82% / PPU 67%. Slightly less host CPU than the Ludendorff Normal sample; still not locked 30.
- Thermal: 31.8–37.2 °C during short boots; not an endurance comparison.

---

## Tested Locations & Scenes
1. SPU cache compile (~10640–10677 modules).
2. Boot fade.
3. Loading Story Mode (Michael prior session; Trevor Fast Mode Pass 2).
4. Prologue title card “Ludendorff, North Yankton, nine years ago.”
5. Launcher Fast Mode gating: GTA V selectable; Demon's Souls `BLUS30443` **FAST MODE: NOT SUPPORTED**.

Not reached: pause menu, Franklin on-foot, driving, heavy traffic.

---

## Known Issues
- Recurring SIGSEGV during RSX texture-cache miss `0xCD5B2000` on Turnip. Tiling is already false; depth write made it fire earlier. Fast Mode wake-up 1 did not prevent it (crash RSS 1.3 GB vs 2.4–2.5 GB Normal).
- Backend logs often do not flush across SIGSEGV; grep `0xCD5B2000` in `logcat-sambas3.txt`.
- No story save (`BLJM61019PROFILE` only).

---

## Progress Tracking
- **Latest Completed Pass:** PASS 2 of 4 (session stopped here on request)
- **Next Pass:** survive `0xCD5B2000` into North Yankton 3D, then re-measure Fast vs Normal on the same scene
