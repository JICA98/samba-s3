# SambaS3 — Savestate Multi-Slot Recovery & Implementation Plan

Status: **MULTI-SLOT WORKING; tablet restore/restart deadlock fixed; extended matrix pending**
Session log (2026-08-27): PPU context serialization root cause found and fixed
(see §1.3); multi-slot UI/backend shipped and device-verified; restore now
completes VFS+PRX+LLVM+context restore but threads terminate right after
"SPU Runtime: Built the interpreter" (see §1.4).
Companion doc: `docs/NEXT_STEPS.md`.

---

## 1. Root-Cause Chain (what was actually wrong)

### 1.1 Restore crash — `Verification failed (object: 0x0)` in `id_map<lv2_obj>`
**File:** `kernel/cellos/src/sys_prx.cpp`, `lv2_prx::load()` (crash at the
`ensure(g_cfg.savestate.state_inspection_mode.get())` line, `sys_prx.cpp:349`).

**Chain of causes, verified with live instrumentation:**

1. Save path (`_rpcsx_saveState`):
   `Kill(false, true)` writes the savestate, then `after_kill_callback = Restart()`.
2. `Emulator::Restart()` re-enters emulation via **`Load(m_title_id)`** —
   **`Load()` never calls `Emulator::Init()`**.
3. `Emulator::Kill()` ends with **`g_fxo->reset()`** (System.cpp:4241), which
   destroys `vfs_manager` — **the entire VFS mount table is gone**.
4. During the state-load thread, `vfs::get("/dev_flash/sys/external/<prx>")`
   returns `""` ("Not mounted" branch in `VFS.cpp` → `result_base.empty()`).
   Evidence: "PS3 firmware is not installed" logged in the same window
   (`PPUModule.cpp:2600` — `fs::is_file(vfs::get("/dev_flash/sys/external/") + "liblv2.sprx")` = false).
5. `lv2_prx::load()` → `fs::file{""}` → ENOENT → `ensure(state_inspection_mode=false)` → thread killed → black screen.
   (The "empty path" in the error was `vfs::get()`'s output, NOT corrupt
   savestate data — serial stream id/type/path all matched the file bytes.)

**Fixes applied (submodule `app/src/main/cpp/rpcsx`, branch `samba-frontend-menu`):**

| File | Change |
|---|---|
| `rpcs3/Emu/System.cpp` (`Load()`) | If `!vfs::manager_initialized()` → call `Init()` (restores mounts for the Kill→Load entry path). |
| `rpcs3/Emu/VFS.{h,cpp}` | New `vfs::manager_initialized()` helper (the type is only defined in VFS.cpp, so `g_fxo->is_init<vfs_manager>()` can't be used from System.cpp). |
| `kernel/cellos/src/sys_prx.cpp` | `lv2_prx::load()`: on re-open failure, log detailed diagnostics and continue with partial `hle_load()` recovery instead of aborting the restore (`ensure` removed). `lv2_prx::save()`: warn when a PRX saves with an empty/unmappable VFS path. |
| `rpcs3/Emu/IdManager.h` | Temporary `idm_log` restore-entry tracing (map/id/type per entry). **Remove before final commit.** |

**Verified result:** restore now re-opens every PRX (`raw_path="/dev_flash/sys/external/liblv2.sprx"` etc.), LLVM modules link, SPU interpreter builds.

### 1.3 Second root cause — PPU guest context was never serialized (FIXED 2026-08-27)Commit `7115851c8` ("Initial new PPU interpreter implementation") commented out
the context line in `ppu_thread::serialize_common` (`PPUThread.cpp:2972`):
`ar(gpr, fpr, cr, fpscr.bits, lr, ctr, vrsave, cia, xer, sat, nj, prio...)` —
the new PPUContext split `xer` into `xer_so/xer_ov/xer_ca/xer_cnt`, so the old
line no longer compiled and was disabled instead of adapted.
**Consequence:** every saved PPU thread restored with a ZEROED context at
`cia=0x0` → either instant `Access violation executing location 0x0` for all
threads, or a silent thread-exit cascade after "SPU Runtime: Built the
interpreter" (the old "Linking PPU Modules 3 of 4" hang was this same bug).

**Fix applied:**
- `PPUThread.cpp serialize_common`: restored serialization with split XER
  fields: `ar(gpr, fpr, cr, fpscr.bits, lr, ctr, vrsave, cia, xer_so, xer_ov,
  xer_ca, xer_cnt, sat, nj, prio.raw().all)`.
- `savestate_utils.cpp`: `SERIALIZATION_VER(ppu, 1, 4)` — version 4 is now the
  only compatible version; old context-less savestates are rejected cleanly.
- Verified on device: `Loading PPU Thread [... cia=0xf809a4/0x1a720 ...]` with
  real addresses; savestate header reports `ppu=4`.

### 1.4 Remaining gap — thread-exit cascade right after SPU runtime init
After the 1.3 fix, restore proceeds: VFS mounted → PRX re-opened → LLVM obj
cache fully reloaded → SPU interpreter built → then within ~1 ms every PPU
thread terminates ("PPU: Threads (9)…(1)" cascade, no access violations, no
error logged) and the emulator shuts down. Suspects:
1. Threads blocked inside HLE syscalls are saved as RUNNABLE with `cia` at the
   `sc` return site; resume executes guest code at that site assuming the lv2
   kernel-side state (event queues, sleepers, event flags) was restored and
   will re-wake them. Verify lv2 object restore (`lv2_config`, event queue
   member lists, `g_priority_order_tag`).
2. `FinalizeRunRequest()` ordering vs `PostponeInitCode` awake list
   (`awake_ppus` order validation `ensure(prev + 1 == ppu.first)` — check for
   silent ensure failure in release).
3. `main_thread` (status=RUN, cia=0x1a720) may return from guest code if its
   wait/wake pairing wasn't re-established → sys_process_exit path.
**Next debug step:** log `lv2_obj::is_scheduler_ready()` + awake-queue size at
FinalizeRunRequest; add a temporary notice in `sys_process_exit` to identify
who triggers shutdown.

### 1.2 Restart Game — same root cause as 1.1
Menu "Restart Game" → `Emu.Restart()` → `Load()` without `Init()` → same empty-VFS
failure. The `Load()` fix covers it; needs on-device re-validation.

---

## Device Validation (2026-08-27)

Full matrix + evidence: `docs/testers/2026-08-27-ingame-menu-savestate-device-validation.md`.
Summary: save-to-slot works (slots 1/3 written); auto-boot restore hangs at
"Linking PPU Modules" (§1.4 cascade confirmed on device twice); **NEW Issue B**:
LOAD from menu SIGABRTs with `Scudo invalid chunk state` (allocator corruption
in the shutdown path) killing the process. Restart Game reboots cleanly but
skips its confirm dialog. Fix B before resuming A.

**UPDATE — root cause found & patched, awaiting device retest**: the Android
`call_from_main_thread` callback stub executed tasks inline on the calling
thread, so destructive transitions tore down an emulator generation on a
gateway/JNI thread while live threads still used it. The LOAD tombstone shows
the load lambda unwinding `BootGame → Init → typemap reset →
MCJIT/Module/DataLayout dtor` on the gateway thread with a double-free; the
SAVE auto-restart hang is the same overlapping-generations fault. Fixed in
submodule `1ae66db06` (serialized CoreDispatchWorker + transition gate +
S3SSTATE tracing) and parent `b937fb0` (LOAD no longer resumes pre-kill,
duplicate-action rejection, restart confirm dialog now reachable). Filter
device logs with `grep S3SSTATE` for phase evidence.

---

## 2. Multi-Slot Savestate — Design

### 2.1 File layout (core scheme, unchanged)
```
<config>/savestates/<TITLE>/<TITLE>_1_<slot>.SAVESTAT[.zst|.gz]
```
- The core always writes the newest state to **slot 0**
  (`System.cpp`: `get_savestate_file(m_title_id, m_path, 0, 0)` — hardcoded id 0).
- Slots 1..4 are produced by **renaming** the fresh slot-0 file after the save
  completes (zero core changes, no serialization risk).

### 2.2 Backend (`rpcsx-android.cpp`) — ALREADY EDITED (this session)
- `kSaveStateSlotCount = 5`; helpers `slotSavestatePath(slot)` /
  `slotSavestatePathCompressed(slot)` mirror `get_savestate_file` naming.
- `_rpcsx_getSaveStateInfo()` → slots 0..4 with `exists` + label
  (`Slot N — <dd Mon HH:MM>` from file mtime).
- `_rpcsx_saveState(slot)`:
  `Kill(false, true)`; in `after_kill_callback`: rename slot-0 file → slot N
  (remove old target first), then `SetContinuousMode(true); SetForceBoot(true);
  BootGame(slot_path, "", true)` — full `BootGame → Init → Load` path (mounts
  guaranteed), replacing the old `Restart()` route.
  Suspend mode (`savestate.suspend_emu`): save + rename, no reboot.
- `_rpcsx_loadSaveState(slot)`: resolve slot file (must exist), then
  `GracefulShutdown(false,false,false,true)` + `BootGame(path, "", true)`.
- Capabilities JSON (`j["savestate"]`) now uses the same `saveStateSlotsJson()`
  0..4 loop (suspend mode keeps its single entry). ✅

### 2.3 Kotlin ✅ DONE (2026-08-27)
- `SaveStateCapabilities.fromJson` already parses `slots[]`
  (`InGameMenuModels.kt`), gateway passes `slot` through
  (`InGameMenuCoreGateway.kt:118-119`), coordinator dispatches
  `InGameMenuIntent.SaveState(slot)` / `LoadState(slot)` → bridge.
- `InGameSaveStatePage.kt`: 5-row slot grid implemented (SAVE per `canSave`,
  LOAD per `exists`, "Slot N — <date>" labels, suspend-mode button kept).

---

## 3. Remaining Work (ordered)

### P1 — multi-slot wiring ✅ DONE (2026-08-27)
1. Capabilities JSON uses shared `saveStateSlotsJson()` (slots 0..4).
2. `InGameSaveStatePage.kt`: 5-row slot grid (SAVE per `canSave`, LOAD per
   `exists`); suspend-mode keeps its single button.
3. Device-verified: slot grid renders, Slot 0 shows "27 Aug 16:34" date label,
   slots 1–4 show "Empty" with LOAD disabled; saving to slot 2 produced
   `BLUS31584_1_2.SAVESTAT.zst` (41–52MB) and auto-rebooted from that file.

### P2 — fix the last restore step: thread-exit cascade (§1.4) — FIXED ON TABLET
1. Add temporary notices: `sys_process_exit` caller, `FinalizeRunRequest`
   awake-count, `ensure(prev + 1 == ppu.first)` result in `PostponeInitCode`.
2. Verify lv2 kernel-object restore (event queues/flags, sleepers,
   `g_priority_order_tag`) re-arms waiters so RUNNABLE threads blocked on
   syscalls don't fall through and exit.
3. Once fixed: full save→restore round trip must show live gameplay
   (clock advancing, input responsive).

The remaining `Starting` stall was reproduced after PPU link/apply had already
completed. `lv2_obj::sleep_unlocked()` called `FinalizeRunRequest()` through
Android's intentionally-inline `CallFromMainThread` while `lv2_obj::g_mutex`
was held. Commit `74b0da9a8` routes this one callback through the explicit
`post_core_lifecycle` queue, so it runs after the scheduler critical section.
Tablet evidence shows SAVE auto-restart and direct LOAD return to live GTA
gameplay; PPU link reports `failed=0`.

### P3 — validation matrix (tablet device `7d6afed8`, wifi serial
`adb-7d6afed8-mU47CV._adb-tls-connect._tcp`)
1. ✅ Save slot 2 → rename to slot file verified; auto-boot from slot file
   reaches PPU linking + LLVM cache reuse (blocks on P2).
2. ✅ Slot labels with dates in UI; empty slots disable LOAD.
3. ✅ Direct LOAD from slot 3 → live gameplay after PPU link/apply.
4. ⏳ Overwrite slot 2 with a newer save → label/date updates (partially
   verified: overwrite produced new file + new mtime).
5. ⏳ Restart Game (menu) → clean reboot (covered by the same `Load()` fix).
6. ⏳ Suspend mode toggle (`savestate.suspend_emu`) → save-and-exit flow intact.
7. ✅ Regression: cold launch, menu open, screenshots still work on new core.

### P4 — clean-up before commit
1. Remove diagnostic logging:
   - `IdManager.h`: `idm_log` channel + entry notice (revert include too).
   - `sys_prx.cpp`: drop `PRX restore:`/`PRX save:` notices; keep the
     "failed to re-open" error (now non-fatal) and the save-side empty-path
     warning — both are cheap and actionable.
2. Keep `Load()` `Init()` fix + `vfs::manager_initialized()` (core fix).
3. Decide `savestate.supported`: keep `true` only if P2 lands; otherwise
   revert to `false` before release.
4. Unit tests: `./gradlew :app:testStandardDebugUnitTest` (menu intents
   unaffected; no new Kotlin tests needed for slots — JSON passthrough).
5. Commit message (submodule + parent):
   - submodule: `fix(android): mount VFS on Restart/Load path; graceful PRX restore; multi-slot savestate support`
   - parent: `feat(ingame-menu): multi-slot savestates + fix restore/restart VFS regression`

### P5 — submodule remote (NEXT_STEPS.md Step 3, still open)
Push `samba-frontend-menu` (now includes savestate fixes) to the accessible
fork, update `.gitmodules` if needed, verify clean-clone repro.

---

## 4. Key Files

| File | Role |
|---|---|
| `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp` | JNI bridge; `_rpcsx_getSaveStateInfo/_rpcsx_saveState/_rpcsx_loadSaveState` (multi-slot, this session) |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/System.cpp` | `Load()` `Init()` fix; `Kill()` `g_fxo->reset()`; savestate write path (`get_savestate_file(..., 0, 0)`) |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/VFS.{h,cpp}` | `vfs::manager_initialized()`; `vfs::get` "Not mounted" → `""` behavior |
| `app/src/main/cpp/rpcsx/kernel/cellos/src/sys_prx.cpp` | `lv2_prx::load/save` — restore diagnostics + graceful fallback |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/IdManager.h` | temporary `idm_log` restore tracing (remove) |
| `app/src/main/java/com/zenithblue/sambas3/ui/ingame/InGameSaveStatePage.kt` | slot grid UI (TODO) |
| `app/src/main/java/com/zenithblue/sambas3/ui/ingame/InGameMenuModels.kt` | `SaveStateCapabilities` JSON model (slots) |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/savestate_utils.cpp` | `get_savestate_file`, `boot_last_savestate` naming reference |

## 5. Device / Build Quick Reference
```bash
# Native (incremental, ~6-8 min after touch)
./build_rpcsx.sh release > /tmp/opencode/rpcsx_buildN.log 2>&1

# APK + install
./gradlew :app:assembleStandardDebug -q
adb -s adb-7d6afed8-mU47CV._adb-tls-connect._tcp install -r \
  app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk

# Logs (device file listener is authoritative; logcat rotates fast)
adb -s <serial> pull /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/cache/RPCSX.log
grep -E "PRX restore:|failed to re-open|Mounted path|Saved savestate" RPCSX.log
```
