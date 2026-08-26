# Findings — GTA San Andreas Import & Headless PPU (2026-08-27)

## Device
- OnePlus OPD2403 (QTI SM8650, Adreno750v2, 11.3GB, Android 16 API36)
- APK 166M `samba-s3-standard-debug.apk` HEAD `60bb267` patch `766b058` core `64b58e1`

## Steps Executed via ADB MCP
1. `adb uninstall` + `install -r` — Success
2. Onboarding 1/7 Welcome → 2/7 Permissions Allow → 3/7 Device Check Continue → 4/7 Firmware Select `PS3UPDAT.PUP 206 MB` in `/PS3` → `Installed · 4.92` → 5/7 Graphics Driver System Vulkan → 6/7 Game Library `Scan game folder` → `USE THIS FOLDER /PS3` → ALLOW → `1 game(s) BLUS31584` → 7/7 Finish
3. Home launcher: VSH + `GTA-SAN-ANDREAS-BLUS31584.IS ISO BLUS31584 Not installed`
4. Swipe to GTA card → `IMPORT` (1482,1169)
5. Install PPU: `Compiling PPU Modules 0→71` (≈250s, 71 modules, install_ppu_active session S3-1787767943066-a34f, progress 0→100)
6. Terminal: `state_change READY/COMPILING`, `PpuCoordinator Starting headless prelaunch for BLUS31584`, `waitForIdle Running→Stopped`, `Applying normal pre-boot settings`, `headless_preflight_begin`, `headless_boot_preflight_begin session=1787768533717`, `S3 preflight plan: title=BLUS31584 cat=DG effective=/.../BLUS31584 eboot=/.../BLUS31584/USRDIR/EBOOT.BIN dirs=3`

## Current State
- Home UI: `Preparing PPU 0.0 / PREPARING` (bottom `PREPARING` disabled, card spinner) — correctly gated, no RPCSXActivity, no Surface, MainActivity remains resumed
- `ppu_state.json` → `{"BLUS31584":{"preRuntime":"READY","runtime":"COMPILING","fingerprint":{...}}}`
- Logcat: install completed, headless BEGIN emitted, but **no `headless_boot_preflight_terminal`** after >7 min at 20% CPU. No `LLVM: Compiled module` for PRELAUNCH, no `S3 preflight main binary` second log.
- CPU `Performance Sensor` + `SPU LLVM` threads alive, 20% total

## Classification
**CASE B → C hybrid**: `headless_boot_preflight_begin` exists, no terminal, genuine native preflight running but stuck before detailed progress. `prelaunchState.ppuActive=true` correctly, but `percent=0.0` never advances.

## Root Cause Hypothesis (to fix)
- `ppu_preflight_plan.eboot_path` currently `effective_path + "/USRDIR/EBOOT.BIN"` only. For ISO-extracted game at `/config/games/BLUS31584`, real EBOOT is at `/.../BLUS31584/PS3_GAME/USRDIR/EBOOT.BIN` (seen in PPU manifest scan: `Scanning directory: /.../BLUS31584/PS3_GAME/`). `fs::is_file(plan.eboot_path)` therefore fails for the single-candidate path, but our code does not yet fallback to `locateEbootPath` candidates (`/PS3_GAME/USRDIR/EBOOT.BIN`, `/EBOOT.BIN`, `/USRDIR/ISO.BIN.EDAT`). In current code we return `invalid_path` only after checking single file; however no terminal was emitted, suggesting `decrypt_self` or `ppu_load_exec` may be hanging on wrong path rather than returning.
- Additionally `S3 preflight main binary` log not emitted suggests `is_file` check may have succeeded (if file exists at USRDIR) but `decrypt_self` then hangs. On this device `BLUS31584` EBOOT at `USRDIR/EBOOT.BIN` may be encrypted and `decrypt_self` without VFS mount may block.
- VFS not explicitly mounted in `PreparePpuOnly` (unlike `Emu.Init()` which mounts `/dev_hdd0` etc). For DG+hdd0 case, `vfs::get("/dev_hdd0/game/")` assumes VFS mounted. Missing mount could cause `GetGameDirs()` or `fs::is_file` for hdd0 to mis-resolve.
- `progress_dialog_server` was correctly started, but since `run_ppu_precompile_stage` never reached `ppu_precompile` (stuck earlier), no `PROGRESS` events are emitted, hence UI stays `0.0`.

## Required Fixes
1. **Robust EBOOT resolution**: replace single `effective_path + "/USRDIR/EBOOT.BIN"` with `locateEbootPath`-style search trying `/USRDIR/EBOOT.BIN`, `/PS3_GAME/USRDIR/EBOOT.BIN`, `/EBOOT.BIN`, `/USRDIR/ISO.BIN.EDAT` relative to `effective_path`. Log which candidate was chosen.
2. **Ensure VFS mount** before plan/GetGameDirs (call `Emu.Init()` lightweight or at least `vfs::mount("/dev_hdd0", ...)` as normal boot does) OR rely on host paths only and document.
3. **Add step logs** in `PreparePpuOnly` after each stage: `after vm::init`, `after init_fxo`, `after progress_dialog_server`, `after plan + GetGameDirs`, `before/after decrypt_self`, `before/after ppu_load_exec`, `entering run_ppu_precompile_stage` to narrow hang.
4. **Guarantee PRELAUNCH progress**: keep explicit `BEGIN` + `PROGRESS` via `progress_dialog_server` as already implemented, but ensure `g_progr_text` is set even if `ppu_precompile` not reached (emit `Analyzing PPU Executable` progress).
5. **Process-death recovery**: already implemented `recoverInterruptedRuntimePreparations` → `FAILED` on next launch if still COMPILING with Stopped engine and no prelaunch active. Not yet tested on this stuck case — after fix, force-stop during COMPILING should become retryable.

## Evidence Paths
- `adb logcat -v threadtime | grep -E "S3PPU|PpuCoordinator|RPCS3|headless"`
- `adb shell cat /.../config/prefs/ppu_state.json`
- `adb shell ls -R /.../config/games/BLUS31584`
- APK `app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk`

## Next Test Plan
- Patch EBOOT resolution + added logs, rebuild core (`./build_rpcsx.sh release`), reinstall, re-import GTA, wait for `headless_boot_preflight_terminal result=0 stopped=1` + `PRELAUNCH COMPLETED` + `ppu_state IDLE_AFTER_COMPILE` + focused card `PLAY` enables. Then test PLAY → no extra `RUNTIME PPU BEGIN` before first frame, force-stop recovery, and driver concurrency.

## Update 2026-08-27 00:22 — Fix Verified
- Patched `build_ppu_preflight_plan` to try `/PS3_GAME/USRDIR/EBOOT.BIN` fallback. Rebuilt core (`bb8d16d`), reinstalled APK.
- Force-stop during stuck COMPILING → on next launch `PpuReadinessStore` correctly recovered: `[BLUS31584] COMPILING → FAILED` (`W/PpuReadinessStore Recovered interrupted runtime PPU preparations` + `W/PpuCoordinator Recovered stale headless PPU state`).
- Tapped GTA card (center 1500,600) → `W/GameRun Failed state for GTA San Andreas, retrying` → `Manual headless request for BLUS31584`
- **Second headless with fix succeeded in 1s**:
  ```
  S3 preflight plan: title=BLUS31584 cat=DG effective=.../BLUS31584 eboot=.../BLUS31584/PS3_GAME/USRDIR/EBOOT.BIN dirs=3
  S3 preflight main binary: .../PS3_GAME/USRDIR/EBOOT.BIN
  headless_boot_preflight_terminal session=1787769995345 result=0 stopped=1
  Headless prelaunch success BLUS31584 -> IDLE_AFTER_COMPILE
  ```
  `ppu_state.json` now `{"BLUS31584":{"preRuntime":"READY","runtime":"IDLE_AFTER_COMPILE"}}`
- After `am force-stop` + relaunch, Home correctly shows **`X PLAY`** (2527,2067) for GTA SAN ANDREAS (gold border, `BLUS31584`), no `RPCSXActivity`, no Surface, MainActivity remains resumed — focused tap now routes to `onPlay()` as designed.

### Remaining for full acceptance
- 5 fresh headless runs not yet done (only 1 full install→headless + 1 retry with fix)
- Normal-boot no-extra `RUNTIME PPU BEGIN` before first frame not yet proven (needs `PLAY` → `RPCSXActivity` + log check)
- Process-death recovery proven `COMPILING→FAILED` (done) but 20-cycle driver stress and DG+hdd0 update test not yet done
