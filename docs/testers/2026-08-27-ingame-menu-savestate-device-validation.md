# Device Validation Report — In-Game Menu + Multi-Slot Savestates

- **Date**: 2026-08-27, 17:40–18:15 IST
- **Device**: OnePlus OPD2403IN (adb-7d6afed8-mU47CV._adb-tls-connect._tcp, Wi-Fi ADB), LineageOS 23.2 / Android 16, kernel 6.1.173
- **Build**: samba-s3-standard-debug.apk v2026.07.22 installed 17:10 (core `librpcsx-android.so` arm64 `0c33fb68`, includes PPU-context serialization fix + multi-slot bridge)
- **Game**: GTA San Andreas (BLUS31584)
- **Input mapping note**: screenshots are 2000x1414; device is 3000x2120 landscape → all taps below are **real px = screenshot × 1.5**. PS overlay button `(1500,146)`. Controller broadcasts (`DEBUG_PAD_CROSS/CIRCLE/START`) reach the emulator only.

---

## 1. Results Matrix

| # | Item | Result | Evidence |
|---|------|--------|----------|
| 1 | Cold launch via launcher card tap | ✅ PASS | EULA → title → New Game → gameplay HUD live (`t2–t4`) |
| 2 | Menu open (PS overlay touch) | ✅ PASS | All 8 rows render; Recording row correctly hidden |
| 3 | Resume | ✅ PASS | Menu hides, clock advanced 07:22→07:45; post-close pad input reaches game (START opened game pause map) |
| 4 | Take Screenshot | ✅ PASS | `config/screenshots/GTA San Andreas_20260827_174504.png` (1.78 MB) written; menu auto-resumes. Toast missed by screencap only |
| 5 | Savestate SAVE (slots) | ✅ PASS | Slot 1 → `BLUS31584_1_1.SAVESTAT.zst` 46 MB @17:47; Slot 3 → `..._1_3.SAVESTAT.zst` 49 MB @18:10. Confirm dialog text correct ("Save current emulation state to slot N? Note: will restart.") |
| 6 | Savestate restore — auto-boot after save | ❌ FAIL (hang) | Issue A below |
| 7 | Savestate LOAD from menu (Running game) | ❌ FAIL (crash) | Issue B below |
| 8 | Restart Game | ✅ PASS * | Clean reboot: shaders preloaded from cache (356/358), back to EULA, no FATAL/SIGSEGV in logcat. * No confirm dialog shown before restart (spec deviation, see C) |
| 9 | Settings transaction (dirty footer / back confirm) | ⏸ NOT COMPLETED | Settings page opened; navigation interrupted to prioritize savestate testing per user request |
| 10 | Exit Game | ⏸ NOT TESTED | Same |

---

## 2. Issue A — Savestate restore hangs forever at "Linking PPU Modules"

**Severity**: High (feature unusable end-to-end)

**Repro** (both paths identical):
1. Launch BLUS31584 → reach gameplay.
2. Menu → SAVE STATE → Slot N → Save (file written OK).
3. Auto-boot from slot file runs: VFS mounts, PRX modules restored, LLVM cache fully reused, PPU contexts deserialize with real values (**no more access violations** — the ppu=4 serialization fix works).
4. Immediately after `SPU Runtime: Built the interpreter`, ALL PPU threads exit in a cascade (~1 ms), emulator never reaches Running.

**Observed UI**: frozen splash `"Linking PPU Modules… Progress: module 1 of 2"` indefinitely (verified >4 min).

**Log evidence** (`files/cache/RPCSX.log` tail):
```
PPU: Threads (4): ... / (3) / (2) / (1)   ← clean cascade exit, no error printed
{PPU[...] BankLoader} PPU: Final Thread
SIG: Thread [Savestate Prepare Thread] is too sleepy. Waiting for it 155280699us already!
PERF: CPU Usage: Total ~2%                 ← fully idle, nothing progressing
```
The watchdog double-waits exponentially on the stuck `Savestate Prepare Thread`. System stays alive but menu cannot be opened (gated on Running state) — only recovery is killing the app.

**Suspects** (documented in `SAVESTATE_PLAN.md` §1.4): lv2 kernel objects restored as RUNNABLE without re-arming syscall waiters; `FinalizeRunRequest()` vs `PostponeInitCode` awake-count ordering; main thread falling through guest code into `sys_process_exit`.

## 3. Issue B — LOAD STATE from menu hard-crashes the process

**Severity**: Critical (process death)

**Repro**:
1. Game Running (any state — tested at EULA screen with emulation Running).
2. Menu → SAVE STATE → Slot 3 → LOAD.
3. Emulation shuts down (GracefulShutdown begins), then process dies; both activities force-removed; app returns to launcher.

**Crash** (dropbox `data_app_native_crash` 2026-08-27 18:13:30, pid 27193):
```
tid: DefaultDispatch (coroutine caller: RpcsxInGameMenuCoreGateway → JNI)
signal 6 (SIGABRT)
Abort message: 'Scudo ERROR: invalid chunk state when deallocating address 0x200006e440fb570'
```
→ **allocator corruption (double-free / UAF)** inside native shutdown triggered by `_rpcsx_loadSaveState()`. Likely cause: `GracefulShutdown(false,false,false,true)` invoked re-entrantly from the gateway coroutine while the emulation thread graph is mid-flight (savestate-save auto-boot hangs instead — same code path taken from a different entry point does not free anything first).

Also noted: a second Scudo abort fired when the hung restore process was later force-stopped (teardown path also corrupts the heap). Heap-corruption fixes should cover both.

## 4. Issue C — Restart Game skips its confirmation dialog

Tap on RESTART GAME immediately rebooted the emulation; expected confirm dialog ("Confirm dialog -> game reboots cleanly" per NEXT_STEPS.md step 2.4). Low severity, spec deviation only. Reboot itself is clean.

## 5. What Works Now vs Before

Previously broken → fixed & verified today:
- PPU context serialization (commit `7115851c8` regression): threads no longer restore zeroed at `cia=0`; real PC values observed, no access violations.
- VFS mount on Kill→Load path: PRX modules now re-open successfully during restart/load boots.
- Multi-slot grid UI + capabilities JSON: dates/mtime labels correct across sessions, empty-slot LOAD disabled.

Remaining open: the final "threads exit after SPU init" step (Issue A) and the shutdown-path heap corruption (Issue B).

## 6. Artifacts

- Screenshots `/tmp/opencode/t1…t31.png` (launcher → gameplay → menu → slots → confirms → hangs/crash).
- Savestates on device: `Android/data/com.zenithblue.sambas3/files/config/savestates/BLUS31584/BLUS31584_1_{1,2,3}.SAVESTAT.zst`.
- Crash: `adb shell dumpsys dropbox --print data_app_native_crash` (2026-08-27 18:13:30 entry); tombstones `tombstone_14`, `tombstone_13`.

## 7. Recommended Next Actions

1. Fix Issue B first (blocks safe iteration on Issue A):
   - Route LOAD/save-shutdown through a dedicated worker thread (not the calling coroutine), add a mutex serializing `Kill()/BootGame()` vs menu actions.
   - Audit `Emulator::Kill()` shutdown ordering (vfs/host data vs `g_fxo` destruction) for UAF; run with HWASan build if available.
2. Then chase Issue A per `SAVESTATE_PLAN.md` §P2 plan: temporary notices in `sys_process_exit` / `FinalizeRunRequest` / PostponeInitCode awake-count ensure.
3. Re-run remaining matrix rows (9, 10) once restores don't kill/hang the app.
