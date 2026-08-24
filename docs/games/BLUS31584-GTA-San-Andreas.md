# GTA San Andreas (BLUS31584) — In-Game Validation and Performance Log

**Goal:** validate cold launch → EULA → New Game → correctly rendered and controllable gameplay on `Y5WWBMJVOZSK4HU8`, then characterize the remaining performance limits. The tested SoC is **MediaTek Dimensity 8300 Ultra (`MT6897Z_A/ZA`)**, not Dimensity 7200; its **Mali-G615 MC6 is Arm Valhall generation 4**. Skills: `sambas3-game-launch`, `sambas3-controller`, and `sambas3-logs` (`./scripts/get-samba-logs.sh`).

| Field | Value |
|---|---|
| Path | `/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584` (`games.json` `path`; fixed nested bug `FileUtil.kt:42`) |
| Update | `PS3_UPDATE/` present; temporary local null-skip patch is now **disabled** |
| Tested device | `Y5WWBMJVOZSK4HU8`, 2311DRK48I/duchamp, `mt6897`, Dimensity 8300 Ultra, Mali-G615 MC6, Valhall v4; `1920×1080` override with controller Cross at `1632,873` |
| Current result | **In-game pass**: Grove Street renders correctly; HUD, radar, world geometry, collision, CJ and bike are present and controllable |
| Renderer | Vulkan, driver `44.1.0`, `1280×720`, 100% scale, Async Shader Recompiler, on-disk shader cache enabled |
| Device tuning | Thermal limits are intentionally removed and the Mali GPU clock is locked at 1.400 GHz; frequency/OPP/temperature fields must not be interpreted as stock governor or throttling evidence |
| Controller | `PadOverlay.kt:172 faceX=1430 faceY=576 btnSize=135 → Cross 1632,873`; agent `input tap 1632 873` ×3 (EULA→Start→New Game) or `adb broadcast DEBUG_PAD_CROSS` (new debug feature) |
| Logs | `LogMonitor.kt:298 logcat -v threadtime -b main,crash,system → classifyTag → rpcsx_backend.log` (see `rpcsx-android.cpp:107 LogListener`); triage `grep -E 'modelinfo\|Access violation\|Emulation.*frozen\|pamf\|vdec'` |

## Snapdragon 8 Gen 3 (SM8650 / Adreno 750) — 2026-08-24 Staged-Import Pass

**Device:** `7d6afed8` — `OPD2403`, Qualcomm Snapdragon 8 Gen 3 (`SM8650`), Adreno (TM) 750, Vulkan driver `512.762.41` (Android 16). Originating ISO `GTA-San-Andreas-BLUS31584.iso` 2,489,647,104 bytes SHA256 `7c85be6c8652e9ec2e0915a0e5aa5b72464b75660ef383a2bdc310c6ce7084bd` validated on device before import.

**Importer:** new fail-safe path — strict raw-identifier multi-extent chain (`351720:1,073,739,776 0x80` + `876007:406,073,437 0x00` = 1,479,813,213), bounded directory parser, `^[A-Z]{4}[0-9]{5}$` title check, per-component path validation, duplicate detection, `PS3_UPDATE` skip only at root, whole-title staging outside `config/games` on the same filesystem, manifest `stat`/`open`/`open_dir` size coherence, `PARAM.SFO`/`EBOOT.BIN` readable-non-empty pre-commit gate, backup/rename/rollback + transaction marker, and publish/PPU-queue only after commit.

**Installed result (on-device `stat` + `sha256sum`):** `PS3DataMain.obb` 1,479,813,213 `8cdcca8047ce083b4cf27316f266affebc7c565ccee38642029ff327397f680f`, `PS3Data.obb` 708,640,703 `484b4fd07331d00ca6aeea6758772d848e166ee45bb885ba17a2dc115e58d905`, `EBOOT.BIN` 8,113,656, `PARAM.SFO` 1,040; no staging `.staging/` residue, no `.backup.` left, no `.transaction` marker, no pending `＄*.tmp`, and `games.json` contains exactly the committed final path.

**Firmware:** 4.92 `PS3UPDAT.PUP` (206,177,436 bytes) installed via app UI; `Firmware installation complete` with automatic VSH PPU compilation intentionally disabled (`sendVshBootable -1`).

**Vulkan:** global `config.yml` `Renderer: Vulkan`; backend `Found Vulkan-compatible GPU: 'Adreno (TM) 750' running on driver 512.762.41` and `Vulkan: Renderer initialized on device 'Adreno (TM) 750'`; `RSX: Renderer: Vulkan` present before RSX resume. No `VK_ERROR_DEVICE_LOST` or `rsx::thread is too sleepy` fatal.

**Patches:** `Skip null modelinfo crash` remains disabled (leave disabled with complete data). First-run PPU compilation for `BLUS31584` (71 modules, `LLVM Recompiler Legacy` `cortex-a34`) ran to `Finalization` without error.

**Intro-to-Ballas checkpoint (DebugPad broadcast, no coordinate tap):** `BROADCAST DEBUG_PAD_CROSS` / `DEBUG_PAD_START` loop progressed through EULA, `Start Game`, `New Game`, and the intro cutscene (`After five years...`, `Welcome home, Carl...`, `This is a weapon, Officer Pulaski...`) without `Access violation`, `Emulation has been frozen`, or `modelinfo` signatures. Screenshots show airport (`JUANK AIR`), police stop, train yard, and the post-cutscene Ballas alley with CJ, bike, HUD and radar — world geometry and collision intact and controller input accepted after the transition. Overhead `PWR: +0.86 – 3.16 W` is for reference only.

**Evidence bundle:** `/tmp/gta-sm8650-pass-20260824-152523/` (APK identity, ISO/PUP/OBB sizes/hashes, `games.json`, Vulkan log lines, `rpcsx_backend.log` PPU window, and screenshots at cutscene / police / train / Ballas checkpoint). The same regression also passes `IsoImportValidationTest` (28 tests, `AtomicFileCopier`, `StagedGameInstaller`, and strict ISO parser cases) and both standard/playstore unit-test tasks. `patches/rpcsx-submodule-changes.patch` was regenerated and reverse-apply checked; final commit stays local.

## Current Result — In-Game Pass, Low FPS Under Investigation (MediaTek Baseline)

The 2026-08-24 run on `Y5WWBMJVOZSK4HU8` reaches the first Grove Street mission with correct scene rendering and collision. The active patch configuration has `Skip null modelinfo crash: Enabled: false`; the current backend log contains **no** `Access violation` and no `Emulation has been frozen` event. The formerly truncated archive is now complete:

| Archive | Current bytes | Earlier bad copy |
|---|---:|---:|
| `PS3DataMain.obb` | `1,479,813,213` | `1,073,739,776` (`1 GiB − 2 KiB`) |
| `PS3Data.obb` | `708,640,703` | not implicated |

The on-device overlay reported **CPU 58%, GPU 100%, RAM 83%, swap 46%, CPU temperature 89.2 °C** during one gameplay capture. Those GPU and thermal readings are **not proof of saturation or throttling on this modified device**: thermal limits are removed and the Mali clock is pinned at 1.400 GHz. A later idle/background sample still reported `ACTIVE=96` while the same kernel interface reported `3D/TA/COMPUTE=0/0/0`, demonstrating that this `ACTIVE` field/overlay cannot be used as a trustworthy busy percentage here. Use per-frame GPU timing or validated busy/total counters for the optimization work.

Vulkan and the physical Mali GPU are definitely being used. The backend repeatedly logs `Renderer: Vulkan`, `Found Vulkan-compatible GPU: 'Mali-G615 MC6' running on driver 44.1.0`, and `Vulkan: Renderer initialized on device 'Mali-G615 MC6'`. A `mali-compiler` thread plus successful Vulkan program-compilation messages provide additional confirmation. The low overall CPU percentage therefore does not indicate a software renderer; it can coexist with a frame limit in one emulation thread, RSX synchronization, shader compilation, readbacks, or driver submission.

The backend log also shows `206` successful Vulkan program compilations across the captured multi-launch log and thousands of game-level attempts to read and then create `/dev_bdvd/PS3_GAME/USRDIR/Shaders/*.vert|*.pix`. Reads return `CELL_ENOENT`; writes return `CELL_EPERM` because `/dev_bdvd` is read-only. That prevents GTA's own shader files from persisting at that path, so the game can repeat work on later boots even though RPCSX's separate on-disk Vulkan shader cache is enabled.

## Baseline (Iter 01 — Video defaults, LLVM)

```yaml
Video: Renderer Vulkan, 1280x720, Frame limit Auto, MSAA Auto, Shader Mode Async Shader Recompiler, Write Color false, Read Color false, Multithreaded RSX false, Accurate RSX false, Relaxed ZCULL false
Core: PPU LLVM Recompiler (Legacy), SPU Recompiler (LLVM), Accurate SPU DMA false, Accurate RSX reservation false
```

- **Launch:** warm `MainActivity→RPCSXActivity` (cold before fix `SIGSEGV pc0 at getState+32 native-lib.cpp:198` when `am force-stop` then direct, fixed `RPCSXActivity.kt:52` cold `openLibrary` + null guard).
- **Steps:** EULA `1632,873` → Rockstar North → Start Game → New Game → loading bar ~50% → toast `The PS3 application has likely crashed, you can close it.` (frozen frame).
- **Log 08-24 08:58:48.611** (`/tmp/gta-wcb-085912`, also `08-24 08:46:49.476` baseline): `F/RPCS3: Access violation reading location 0x0` + `W/RPCS3: Emulation has been frozen!` preceded by 22× `D/RPCS3: sys_tty_write(): Too many objects without modelinfo structures` at `08:58:48.481`. `grep -c modelinfo` ≈ 40+, `AV 1`. No Vulkan error. `Ppu syscall` healthy before.

## Iter 02 — WCB true (Iter `02-wcb`, `WCB=true RCB/WDB/RDB true`)

```diff
+ Write Color Buffers: true
+ Read Color Buffers: true
+ Write Depth Buffer: true
+ Read Depth Buffer: true
```

- Result: **FAIL** — identical `AV 0x0` at same point (`/tmp/gta-wcb-085912`, `02-wcb`). Not a Video write-color fix on Mali.

## Iter 03 — WCB + Multithreaded RSX (`03-wcb+mtrsx`)

```diff
+ Multithreaded RSX: true
```

- Result: **FAIL** — `04-WCB-MTR-Accurate` log (with Accurate still false at first) already showed `AV 0x0` at `09:02:01.236` with 4737 `modelinfo` lines total (`/tmp/gta-loop-20260824-090241/04-WCB-MTR-Accurate`). Triple-press sequence `1632,873` succeeds to menu but crash unchanged.

## Iter 04 — WCB+MTR+Accurate RSX reservation (`04-WCB-MTR-Accurate`)

```diff
+ Accurate RSX reservation access: true
```

- Result: **FAIL** — same `AV 0x0` at `09:02:01.236` (see above). No `rsx::thread sleepy`, no Vulkan `VK_ERROR`.

## Iter 05 — +Relaxed ZCULL (`05-WCB-MTR-Accurate-Relaxed`)

```diff
+ Relaxed ZCULL Sync: true
```

- Result: **FAIL** — loop `05` stalled but subsequent manual `06/07` checks and `09:07:46.190` with `PPU/SPU Interpreter (precise)` still hit `Too many objects without modelinfo` → `AV 0x0` (`/tmp/gta-interp-090544` with interpreter actually showed new path: `sys_memory_allocate` + `Failed to lock sudo memory` then later `09:07:46.055 modelinfo×8` → `09:07:46.190 AV 0x0`). So Video toggles alone do not bypass.

## Iter 06/07 — Interpreter variants

- `PPU Interpreter (precise) + SPU Interpreter (precise)` (set via `config.yml: Core PPU/SPU Decoder`): still `AV 0x0` after New Game loading (`09:07:46.190`), but initial `modelinfo` count lower (0 immediately, then spike after). Indicates PPU LLVM vs Interpreter not root cause; the game’s `modelinfo` allocation path hits null deref regardless.

## Iter 08 — SPU Cache false (keep WCB+MTR+Accurate+Relaxed, LLVM)

```diff
 Core: SPU Cache: false (was true)
```

- **Launch:** cold via `am start -e path` + `input tap 1632,873` ×3 (EULA→Start→New Game). Previous attempt timing off (taps before EULA) gave `0 AV`; retry at `09:24-09:27` correctly timed.
- **Log 09:27:10.358** (`/tmp/gta-08-092451` re-run 09:27): `F/RPCS3: Access violation reading location 0x0` at `09:27:10.358` preceded by 13× `Too many objects without modelinfo structures` at `09:27:10.220-221`. `grep -c modelinfo` >13, `AV 1`, no `VK_ERROR`, no `rsx sleepy`. Same deterministic crash as baseline — `SPU Cache false` not a fix on Mali-G615.
- **Screen:** frozen at `Big Smoke` loading bar ~50% + toast `The PS3 application has likely crashed`.

## Iter 09 — Accurate SPU DMA true (revert SPU Cache true, keep WCB+MTR+Accurate+Relaxed)

```diff
 Core: SPU Cache: true (revert)
+ Accurate SPU DMA: true (was false)
   SPU Block Size: Safe
```

- **Launch:** clean via `MainActivity` warm + `rm rpcsx_backend.log*` + `RPCSXActivity -e path` + `1632,873` ×3. Log file freshness fixed (previous `rm` left stale writer; now `rm *.log*` + `MainActivity` restart).
- **Log 09:28:04** (`/tmp/gta-09-092804`): `3025:[08-24 09:27:10.358] F/RPCS3: Access violation 0x0` but `grep` before pull still showed old `09:27:10` — re-pull after `MainActivity` restart gave fresh `09:28:??` but triage still `AV 1 model 1346` (same `modelinfo` flood). Not a fix.

## Iter 10 — SPU Block Size Mega (keep Accurate SPU DMA true)

```diff
+ SPU Block Size: Mega (was Safe)
```

- **Launch:** `am force-stop; rm logs; MainActivity; RPCSXActivity` + `1632,873` ×3. Initial `09:30:38` pull showed `0 AV 0 model` because EULA timing shifted (Mega slower, 25 s wait insufficient — snapshot still at EULA). Retry correctly timed at `09:31:30` after reaching Start Game → New Game.
- **Log 09:31:30.941** (`09:31` run): `D/RPCS3: modelinfo ×8 at 09:31:30.811` → `F/RPCS3: AV 0x0 at 09:31:30.941` + `Emulation has been frozen!`, `grep -c` `1 AV 14632?` actually `1 AV 8 model` in window but `grep -c modelinfo` on full file `>1000`. Same crash — `Mega` not a fix, just slower.

## Iter 11 — XFloat Precise (attempt, file-edit reverted)

```diff
+ XFloat Accuracy: Precise (attempt via sed, reverted to Approximate on MainActivity boot)
  SPU Block Size: Safe (reverted from Mega)
```

- **Attempt 09:33** (`/tmp/gta-11-093311`): set `XFloat Accuracy: Precise` via `sed` before `am force-stop`, but `grep` after `MainActivity` restart showed `Approximate` again — `config.yml` is rewritten by `MainActivity.kt:62 openLibrary/initialize` + `GameSettingsOverrides` on boot, so direct file edits for some `Core` keys are lost. Need `GameSettingsOverrides.kt` / `settingsSet("Core@@XFloat Accuracy","\"Precise\"")` per-title path instead. `0 AV 0 model` in that pull was pre-crash (still at `Start Game` snapshot, 25 s wait insufficient after `Mega` timing shift). Reverted to `Approximate` + `Safe` for clean baseline.

## Iter 12 — real PPU Interpreter (enum names were wrong in 06/07)

Valid `Core@@PPU Decoder` strings are `Interpreter`, `Interpreter (Legacy)`, `LLVM Recompiler (Legacy)` (`system_config_types.cpp:523-525`). Iter 06/07 used `'Interpreter (precise)'` which `cfg::try_to_enum_value` rejected — LLVM stayed on. Applied via SharedPreferences `game.BLUS31584` (`run-as` → `applyForGame` at boot). **Log confirmed `PPU Decoder: Interpreter`.** Too slow for gameplay (Rockstar logo ~60s, Home Menu 120ms pad pulse missed). Aborted; LLVM restored. **Do not use Interpreter on device.**

Also: `XFloat Accuracy` values are `Accurate|Approximate|Relaxed|Inaccurate` — not `Precise` (Iter 11 was a no-op).

## Iter 13 — LLVM + skip-crash PPU patch + Accurate XFloat (reached 80%)

```
PPU Decoder: LLVM Recompiler (Legacy)
XFloat Accuracy: Accurate
Disable SPU GETLLAR Spin Optimization: true
Accurate PPU 128-byte Reservation Op Max Length: -1
Handle RSX Memory Tiling: true
Disable Asynchronous Memory Manager: true
Patch: PPU-5c0eb0c4eb31a039bdec24f04f9a030a2cc4e0be "Skip null modelinfo crash"
  be32 0x003349c8 -> 0x4e800020 (blr)  [50% AV CIA]
```

- Official `rpcs3.net` patch.yml has **no** BLUS31584 / this PPU hash entries.
- Patch applied: `I/RPCS3: Applied patch ... Skip null modelinfo crash`.
- First boot after XFloat/128-byte change recompiled ~170 PPU modules (one-time, LLVM cache).
- **Result: loading bar ~80% (bandana CJ) vs old 50% Big Smoke.** `grep -c 'Access violation'` = **0**. Still toast `EMULATION_FROZEN` at `10:26:41`.
- MainThread CIA at freeze `0x2b2ecc` after `lwzx`/`lwz r4,0(r3)` vcall (`0x2b2eb4`) — same modelinfo-null family, later in load.

## Iter 14 — Complete OBB, Patch Disabled, In-Game Pass

The original ISO contains a complete `PS3DataMain.obb` of `1,479,813,213` bytes. Replacing the partial installed copy removed the loader's reads beyond EOF. The temporary `blr` patch at PPU CIA `0x003349c8` was then disabled and its patched EBOOT LLVM cache removed; leaving that patch enabled with complete data skipped required model setup and produced a grey/blue void with CJ falling through missing collision.

With the complete archive and patch disabled, GTA reaches Grove Street, renders the world correctly, and accepts gameplay input. This isolates the earlier 50–80% load freeze from the GPU: it was caused by incomplete game data plus a diagnostic patch that became harmful after the data was repaired.

## Root Cause of the Former Load Freeze (Dump, Not Mali/Video)

The first installed `PS3DataMain.obb` on the device was **truncated**:

| File | Bytes | Hex |
|---|---|---|
| on disk | `1073739776` | `0x3ffff800` (1 GiB − 2 KiB) |
| game `sys_fs_close` pos | `1479556240` (`1.377 GB`) | `0x5820bc90` |

Log (`10:26:33.810` / `10:26:36.307`): `sys_fs_close(...PS3DataMain.obb) Pos/Size: 1.377GB/0.999998GB`. The loader sought roughly 400 MB past EOF; reads returned empty; IDE/IMG objects spawned without `CBaseModelInfo*` → TTY `Too many objects without modelinfo` → freeze. **Video/Core toggles cannot supply missing archive bytes.** The volume had 183 GB free, so this was a bad copy rather than disk-full.

### Why the first ISO import silently installed only part of the archive

The ISO was not half-complete: its embedded archive had the correct `1,479,813,213` bytes. The installed folder was missing `406,073,437` bytes. The exact external interruption/provider failure is not preserved in the logs, but the pre-fix importer explains why the partial result was accepted:

- `FileUtil.saveFile()` catches `IOException`, prints it, and returns no failure to its caller.
- `copyDirUriToInternalStorage()` marks each file processed without verifying source and destination lengths.
- `installPackages()` then calls `collectGameInfo()` even after a short copy, so the partial title becomes launchable.
- `saveFile()` also ignores the byte count returned by `read()` and writes the whole 1,024-byte buffer, which can corrupt the final block of files whose size is not a multiple of 1,024.
- The later manual ADB push also stopped early once; size/EOCD verification caught that second partial copy before the final replacement.

### Importer fix implemented

The game-directory importer is fixed in source as of 2026-08-24:

- `AtomicFileCopier` writes only the number of bytes returned by each `read()` into a same-directory `.importing` temporary file.
- It flushes and syncs the temporary file, compares the copied byte count with `DocumentsContract.Document.COLUMN_SIZE` when the provider supplies it, and atomically replaces the target only after validation.
- A short read, provider disconnect, size mismatch, directory-enumeration failure, target-directory failure, or indexing failure now propagates to a visible failed-import result. The title is not registered as successfully imported.
- An existing target file remains intact when a replacement fails, and temporary files are cleaned up on handled failures.
- Import progress reserves its final step for `collectGameInfo()`, preventing the UI from reporting completion before indexing succeeds.

Android document providers may legally report an unknown size. Those streams still use exact byte-count writes and atomic publication, but an independent source hash/size remains the strongest validation for large game archives. An APK built before this fix still has the old behavior and must be rebuilt/reinstalled.

## Why Full FPS Is Not Yet Reached

The current evidence identifies likely costs, but it does **not** yet prove a GPU-saturation or thermal-throttling limit:

1. **Live shader work:** `mali-compiler` is active, and RPCSX reports successful Vulkan program compilation. Async compilation avoids a full stop but consumes host time and produces traversal-dependent stutter.
2. **GTA shader persistence failure:** the title repeatedly probes missing `/dev_bdvd/.../Shaders` files and cannot create them on the read-only disc mount (`CELL_EPERM`). The manually created `/dev_hdd0/game/BLUS31584/USRDIR/Shaders` directory does not help while the title still resolves this path to `/dev_bdvd`.
3. **Accuracy/readback settings left from crash diagnosis:** Write/Read Color Buffers, Write/Read Depth Buffers, Accurate RSX reservation, Accurate SPU DMA, XFloat Accurate, SPU Verification, and Accurate ZCULL stats are all enabled. The complete-OBB result proved these did not fix the crash; several can add synchronization, CPU↔GPU readback, or accuracy overhead.
4. **Serialized emulation work:** system-wide CPU utilization averages across eight cores. One saturated PPU/RSX submission thread can cap FPS while the total still looks low. A thread-level capture during active gameplay is required; the later `top -H` sample was taken with no focused game window and is not performance evidence.
5. **Conservative Android JIT target:** the log reports `Use LLVM CPU: cortex-a34` although the SoC exposes four Cortex-A510 and four Cortex-A715 cores. The Android backend falls back to `cortex-a34` when the app sandbox cannot read MIDR sysfs. `cortex-a510` is the safe common target for this heterogeneous SoC, but its benefit must be measured after rebuilding the PPU/SPU caches.
6. **Memory pressure:** the process used roughly 2.2 GiB resident in the gameplay sample, while the device overlay showed 83% RAM and 46% swap. Reclaim/swap activity can worsen frame pacing even when average CPU utilization looks moderate.
7. **Modified telemetry:** the forced 1.400 GHz clock explains maximum OPP reporting but not utilization. Do not use the current `ACTIVE` field, maximum clock, temperature, or disabled thermal ceiling to classify the bottleneck. Capture real frame time, per-thread CPU time, and a known-valid Mali busy counter instead.

## Performance Test Order

Change one setting group at a time and record cold/warm FPS plus temperature; do not clear shader caches between warm-cache measurements.

1. Close background apps and repeat the same Grove Street camera path. Record FPS/frame time plus thread-level CPU time; do not treat the modified device's frequency/OPP/thermal overlay as utilization data.
2. Keep LLVM, Async Shader Recompiler, 720p/100%, MSAA disabled, Vulkan on-disk shader cache enabled, and the null-skip patch disabled.
3. Revert crash-diagnostic accuracy settings toward baseline in this order: Read Color Buffers off; Read Depth off; Write Depth off; Write Color off; Accurate RSX reservation off; Accurate SPU DMA off; XFloat Approximate. Stop/revert at the first visual or stability regression.
4. Compare first traversal with the second traversal in the same process, then a warm relaunch. This separates new-pipeline compilation from steady-state rendering.
5. Trace why `BLUS31584` selects `/dev_bdvd/.../Shaders` instead of writable game data; validate any mount/path fix before treating the repeated shader work as solved.
6. Benchmark the safe `cortex-a510` LLVM target separately; it invalidates the corresponding LLVM caches, so do not mix its first-boot compile time with gameplay FPS.
7. Run the same dump/config on the Snapdragon 8 Gen 3 / Adreno 750 tablet. That is the primary driver/architecture control; the Realme X2 Pro (`2a580689`) is Adreno 640 and is a secondary control.

Vulkan verification for every run:

```bash
grep -E "Renderer: Vulkan|Found Vulkan-compatible GPU|Vulkan: Renderer initialized" rpcsx_backend.log
# expected on this device:
# Found Vulkan-compatible GPU: 'Mali-G615 MC6' running on driver 44.1.0
# Vulkan: Renderer initialized on device 'Mali-G615 MC6'
```

The global GPU-family forecast and test matrix is maintained in [`GPU-COMPATIBILITY.md`](GPU-COMPATIBILITY.md). Only Mali-G615 MC6 is currently a verified in-game pass for this title; all other rows are hardware/driver forecasts until tested with the same dump and checkpoint.

## How to Reproduce (agent one-liner)

```bash
SERIAL=Y5WWBMJVOZSK4HU8; BASE=/storage/emulated/0/Android/data/com.zenithblue.sambas3/files; GAME=$BASE/config/games/BLUS31584
adb -s $SERIAL shell "am start -n com.zenithblue.sambas3/.RPCSXActivity -e path $GAME"  # cold-safe after fix
sleep 25; for _ in 1 2 3; do adb -s $SERIAL shell "input tap 1632 873"; sleep 4; done
./scripts/get-samba-logs.sh $SERIAL /tmp/out; grep -E 'modelinfo|Access violation' /tmp/out/rpcsx_backend.log | tail -20
```

Expected current result: Grove Street gameplay with correct geometry/collision on LLVM and **without** the null-skip patch. If the archive size regresses to `1073739776`, stop: the import is incomplete and settings testing is invalid.

## References

- `LogMonitor.kt:298`, `rpcsx-android.cpp:107 LogListener`, `native-lib.cpp:198` cold guard, `RPCSXActivity.kt:52` cold init, `PadOverlay.kt:172` (1632,873), `FileUtil.kt:42`, `scripts/get-samba-logs.sh`, `skills/sambas3-*`, `patches/rpcsx-submodule-changes.patch`.
- Loop evidence: `/tmp/gta-wcb-085912`, `/tmp/gta-loop-20260824-090241/04-WCB-MTR-Accurate`, `/tmp/gta-interp-090544`.
