# RDR Post-Intro Crash — Reproduction, Driver/Settings A/B, and Scope Fix

**Date:** 2026-09-03
**Branch:** `recovery/ingame-menu-fix`
**Commits:** `bdf3a75` (perm cleanup) → `5a60569` (settings isolation) → `0219dd1` (RDR)
**Reference device for RDR:** OnePlus Pad 2 / OPD2403, Snapdragon 8 Gen 3, Adreno 750, Android 16
**Local smoke device:** POCO 2311DRK48I, MediaTek MT6897, Mali-G615, Android 16 (no RDR/DS titles on hand)

## 1. Baseline

- Root SHA at start: `7382968d8cb043fcd3fde0513e94e9ed5511c13f`
- Branch: `recovery/ingame-menu-fix`
- Local device: POCO 2311DRK48I (Mali-G615, Android 16, SDK 36)
- Core: packaged `librpcsx-android.so`; `app/src/main/cpp/rpcsx` at `657b26a0d`
- RDR dump identity (reference run): `/storage/emulated/0/PS3/Red Dead Redemption - Game of the Year Edition (USA) (En,Fr,De,Es,It).iso`, BLUS30758
- Local `/storage/emulated/0/PS3/` holds only GoW III + GTA SA ISOs — no BLUS30758/BLUS30443 on the smoke device.

## 2. Review of previous partial PASS

The `7382968` evidence proves: PPU/SPU compile, revolver intro, Rockstar Games
logo, Rockstar San Diego logo. It does not prove Press Start, main menu,
new/load game, controllable 3D gameplay, or any timed stability run.
Status renamed to **PARTIAL PASS — boot/intro 3D rendering verified**
(`docs/games/BLUS30758-Red-Dead-Redemption.md` §3 lists OPEN stages).

## 3. Current reproduced failure

On the reference Adreno 750 path the known failures are:

- **A. Qualcomm Vulkan SIGSEGV** — `vulkan.adreno.so` (512.762.41) NULL-deref in
  the compiler thread (`libllvm-qgl.so`) during descriptor/pipeline creation
  after PPU compile. Class: proprietary-driver crash.
- **C. RSX wait/deadlock** — `Handle RSX Memory Tiling=true` reproduces
  `[vulkan] vk::wait_for_event has timed out!` (DMA-fence wait in
  `VKTextureCache.h imp_flush`). Class: RSX wait/stall.
- **E. CPU starvation** — 6 SPURS threads busy-wait (`GETLLAR`), ~700% CPU on
  the 8-core SoC, 3.5s+ input lag, `THERMAL: SEVERE`. Class: thermal/CPU.

On the local Mali smoke device RDR cannot be reproduced (title absent, wrong
GPU); boot-path changes there are validated by unit tests + clean Home launch.

## 4. Driver A/B

| Run | Driver | Result |
|---|---|---|
| D0 system (`vulkan.adreno.so`) | Qualcomm proprietary | SIGSEGV in descriptor/pipeline path (prior evidence) |
| D1 bundled Turnip 26.3 (`libvulkan_freedreno.so`, Vulkan 1.3.279) | Mesa | Intro rendering succeeds (prior evidence) |

Product consequence: `7382968` shipped no driver-selection logic, so D1 was
test-device state. Commit `0219dd1` adds `resolveCompatBootDriverForBoot`:
RDR-family + Adreno + system/default + validated installed Turnip ⇒ boot-only
Turnip, no global preference mutation, restore on exit. Explicit user driver
always wins; Mali/non-Adreno never overridden; missing Turnip logs
`reason=turnip-missing` and falls back without claiming fixed.
Unit proof: `GpuDriverSelectionTest` 7/7 (RDR+Adreno+system+Turnip,
explicit-custom, Mali, non-RDR, missing-Turnip, ranking, case-insensitivity).

## 5. Settings A/B

Candidate profile evaluated one axis at a time from prior runs:

- S1 WCB only → menus still broken; S2 +RCB → lighting passes return;
  S3 +Tiling=false → DMA-fence stall disappears; S4 +Wake-Up=200 → SPU/RSX
  race gone; S5 +SPU loop → spin loops optimized; S6 +SPURS=4 → CPU
  700%→200–480%, input sane; S7 +Relaxed ZCULL → ZCULL stalls gone.
- Minimal stable set shipped = all seven (see evidence table in the game doc
  §4 with correctness vs performance classification).

## 6. Settings ownership fix

`gameOverrides()` returned `curated + local + native`, which broke Reset
(clear saw curated leftovers as remaining overrides) and let the local mirror
mask native-apply failure. Fixed in `5a60569`:

- `compatibilityDefaultsForTitle()` — product-owned profile, never user state.
- `explicitUserOverrides()` — local + native only; this is what
  `gameOverrides()`, the Settings screen, Reset/Clear, and "has custom" use.
- `resolvedBootOverrides()` — compat → explicit (user wins); the exact map
  the lease applies.
- `recordGame()` reports app-tier persistence honestly; native best-effort is
  logged separately, never used as proof.
- Scoped lease: snapshot affected globals → verify lease on disk → apply →
  verify read-back; exact restore on clean exit; stale-lease recovery before
  the next boot. `S3GAMECFG` boot/restore logging.

## 7. Final minimal RDR profile

`Video@@Write Color Buffers=true`, `Video@@Read Color Buffers=true`,
`Video@@Handle RSX Memory Tiling=false`, `Video@@Driver Wake-Up Delay=200`,
`Core@@SPU loop detection=true`, `Video@@Relaxed ZCULL Sync=true`,
`Core@@Max SPURS Threads=4` (+ boot-only Turnip on affected Adreno).
Only `BLUS30758` is device-validated; sibling IDs are family mapping.

## 8. Gameplay validation

Intro stages PASS (prior screenshots preserved). Press Start → 20-min
gameplay, repeat boot, background/resume, clean exit remain OPEN pending the
Adreno reference device with the title installed. No PASS is claimed for them.

## 9. Cross-title regression

Lease + unit proof (`cross_title_sequence_does_not_leak`):
RDR → exact global restore → DS sees only `WCB=true` → restore → generic
title sees untouched globals. Driver: RDR Turnip never persists; DS/generic
boot the stored selection. DS 3D gameplay re-validation on device is pending
(title absent locally).

## 10. PPU regression

PPU files frozen after `bdf3a75` (empty diff on `ppu/**`, AIDL,
`CompileProgressBridge`, `PrecompilerService`, native patch — see
`docs/testers/artifacts/2026-09-03-rdr-crash-fix/ppu-freeze-diff.txt`).
Overall `module X/TOTAL` UI untouched; no batch counter; no headless Runtime
PPU re-enabled; `:ppu_compile` stays CPU-only (compat driver never applied
to the worker). No-permission smoke: fresh build launches to Home with only
baseline permissions (screenshot `00-home-smoke.png`, `apk-permissions.txt`).

## 11. Remaining risks

1. Post-intro RDR stages unverified — need reference Adreno device + title.
2. `Max SPURS Threads=4` is a performance recommendation; re-measure default
   vs 4 with `top -H` during the 20-min run.
3. Core still lacks Global/per-title settings symbols, so isolation is
   app-side (lease); revisit if the core gains real `settingsGetGlobal` etc.
4. Turnip-absent fallback boots the known-crashing system driver by necessity;
   UX should surface that state before claiming fixed.
