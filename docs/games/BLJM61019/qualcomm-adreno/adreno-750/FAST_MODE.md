# Grand Theft Auto V (BLJM61019) — Fast Mode Profile Specification

## Overview
GTA V Fast Mode is an opt-in, game-specific profile. There is **no generic fallback**. Capability is gated by `PatchFastMode.kt`.

- Control interactive only for titles with an explicit curated profile (GTA V IDs below, plus TLOU / Uncharted 2 / inFamous 2 / RDR).
- Unsupported titles (e.g. Demon's Souls `BLUS30443`, God of War III `BCUS98111`) show `FAST MODE: NOT SUPPORTED` and cannot activate it.
- Enabled state is persisted **per title ID** (`sambas3_fast_mode` / `enabled.<TITLE_ID>`).

**Runtime-tested ID:** `BLJM61019` only.

| ID | Region / form | Runtime tested |
|---|---|---|
| BLJM61019 | Japan disc | YES (Normal + Fast Mode boots; both SIGSEGV before 3D gameplay) |
| BLUS31156 | USA disc | NO |
| BLES01807 | Europe disc | NO |
| NPUB31154 | USA digital (wiki) | NO |
| NPEB01283 | Europe digital (wiki) | NO |
| NPJB00516 | Japan digital (wiki) | NO |
| NPUB31156 / NPEB01807 / NPJB00517 | extra map | NO |

---

## Validated Fast Mode Settings Matrix

| Setting / Optimization | Normal | Fast Mode | Why | Result | Risk | Validated Pass |
|---|---|---|---|---|---|---|
| Capability gating | n/a | explicit profile only | No keyword fallback | Unit tests + on-device Demon's Souls NOT SUPPORTED | Low | Pass 1–2 |
| Skip Rockstar Boot Logo | off | ON if PPU hash matches | Boot skip only | Imported; hash match unconfirmed | Low | Pass 1 import |
| Driver Wake-Up Delay | 200 | **1** | 200 is RDR deadlock workaround | Loading Story Mode **27.7 FPS** vs ~26 FPS Normal-class; still SIGSEGV | Medium (RDR needed 200) | **Pass 2 KEEP** |
| SPU Block Size | Safe | Safe (Mega **not** applied) | Mega deferred | Not run | High (TLOU) | Pass 2 defer |
| Max LLVM Compile Threads | 2 | 2 | Survive 10k SPU compile | KEEP | Low | Pass 1 |
| Write Color Buffers | true | true | Wiki outdoor lighting | Loading art rendered | Low | Pass 1–2 |
| Read Color Buffers | true | true | HUD (unverified in 3D) | No HUD scene | Medium | Pass 1 |
| Handle RSX Memory Tiling | false | false | Turnip 0xCD5B2000 class | Insufficient alone | High if re-enabled | Pass 1 |
| Write Depth Buffer | off | off | Wiki raindrops; **SIGSEGV 0xCD5B2000** | REVERT | High | Pass 1 FAIL |
| Accurate RSX reservation | false | false | Wiki freeze fix; leftover true isolated | Explicit false; crash remains | Medium | Pass 2 |
| 60 FPS patch | never | never | Raises cap; TLOU Unlock FPS starved PPU | Not applied | High | Do not use |

Normal Mode remains the compatibility path. Fast Mode is not a global default.

---

## Technical Mechanism
- **Launcher:** `GameLaunchCenter` `enabled = isFastModeSupported(titleId)`. Unsupported titles cannot focus-activate the control; `fastModeActive` is forced false on title change.
- **Persistence:** SharedPreferences + in-process map. Missing skip-logo patches do not block the settings overlay.
- **Boot:** `beginScopedLeaseForBoot` appends `fastModeSettingsForTitle` when the flag is on. Fast Mode knobs win over compatibility defaults.
- **Do not enable** the RPCS3 GTA V `60 FPS` patch.

Runtime Fast vs Normal on **gameplay**: The `0xCD5B2000` SIGSEGV is fixed in Pass 3. Fast Mode achieved **28.9 FPS** on Loading Story Mode (vs 26.2 FPS Normal). Transitioning into active 3D gameplay is pending resolution of the initial story load `cellNetCtlGetInfo` polling loop.
