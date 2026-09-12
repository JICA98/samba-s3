# inFamous 2 (BCUS98125) — Android Validation

| Field | Value |
|---|---|
| Title / update | inFamous 2 (USA), `BCUS98125`, game reports v02.00 |
| Image | `inFamous 2 (USA) (En,Fr,Es,Pt) (v02.00).iso`, 15,335,620,608 bytes |
| Registered path | `direct_iso/BCUS98125` |
| Core | RPCSX `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`, RelWithDebInfo |
| OnePlus 13R | CPH2691, Snapdragon 8 Gen 3 / Adreno 750, Turnip |
| Poco X6 Pro | 2311DRK48I, Dimensity 8300 Ultra / Mali-G615 MC6, vendor Vulkan 44.1.0 |
| Test date | 2026-09-09 |

## Result

- OnePlus 13R reaches the first controllable scene with correct geometry and effects. The intro video is about 28–30 FPS; the CPU-heavy opening gameplay is about 7–12 FPS. A 30, 60, Display, or uncapped emulator limit cannot raise a workload that is already below the limit.
- Poco X6 Pro completes PPU preparation (71/71 cached objects) and starts the game, but the current Mali 44.1.0 driver repeatedly faults at `libGLES_mali.so+0x1c0e9a8`, reading `0x40`. Android records `SIGNALED`, status 11, not a low-memory kill. Reproductions ranged from 660 MB to 3.5 GB RSS, so RAM pressure is not the sole cause.
- A same-queue Vulkan fix now checks whether `vk::AsyncTaskScheduler` actually exists before using it. This prevents the separate OnePlus `shared_mutex` underflow caused by accessing unconstructed FXO storage.
- Multi-slot savestates are validated on the OnePlus 13R. Slot 0 loaded, slot 1 then committed as a separate 152,747,917-byte file, slot 0 remained intact at 9,721,277 bytes, and slot 1 restored to a confirmed rendered frame.
- Android now completes final emulator teardown on its serialized lifecycle queue. Manual load uses asynchronous graceful shutdown and chains the new boot from the post-kill callback, avoiding both a join-thread self-wait and a lifecycle-queue wait cycle. A completed load also cancels its watchdog so a late false **Load Failed** alert cannot replace success.
- Manual savestates receive a terminal frontend failure event when RPCSX rejects the asynchronous SPU/HLE preparation stage. The inFamous 2 compatibility profile also enables SPU-compatible savestates.

## Compatibility Profile

Product-owned defaults for all known inFamous 2 title IDs:

```json
{
  "Video@@Vulkan@@Asynchronous Texture Streaming 2": true,
  "Savestate@@Compatible Savestate Mode": true
}
```

Known aliases: `BCUS98125`, `BCES01143`, `BCES01144`, `BCES01229`, `NPEA00318`, `NPUA80638`.

The tested OnePlus session additionally used Atomic RSX FIFO, Mega SPU block size, six preferred SPU threads, multithreaded RSX, relaxed ZCULL, disabled ZCULL occlusion queries, and color/depth buffer reads and writes. Those experimental values are not shipped as title defaults because the stable evidence does not show that all are required.

## Device Iterations

### OnePlus 13R / Adreno 750

1. Asynchronous texture streaming originally accessed an absent scheduler when graphics and transfer used the same Vulkan queue. Result: mutex verification failure.
2. Guarding the optional scheduler removed that crash. The title reached the rendered intro and controllable gameplay.
3. Saving from SambaS3's paused menu initially failed because RPCSX could not move already-paused SPUs to its savestate-safe boundary. The save path now resumes briefly behind the frozen transition frame, then lets RPCSX acquire its own SPU boundary.
4. The first slot-0 save committed, but loading it left the old emulation join thread alive because Android's general main-thread callback runs inline. A later slot save could consequently hang or fault in RSX teardown. Final cleanup now uses the dedicated lifecycle queue, while manual load no longer synchronously occupies that queue waiting for cleanup.
5. Final validation: load slot 0 completed with `Objects cleared` for the old generation and no stale join/watchdog threads; save slot 1 committed in about 2.3 seconds; both final `.zst` files coexisted; automatic slot-1 recovery reached its first confirmed frame.
6. The 60-second manual-load watcher previously survived native success and emitted a false timeout afterward. Native completion now interrupts the watcher, the timeout revalidates the pending request, and the heavy-title budget matches the existing 120-second first-frame budget.

### Poco X6 Pro / Mali-G615 MC6

1. Async Shader Recompiler: `RSX.W1` SIGSEGV in the vendor Mali library. Android exit RSS: 3.5 GB, then 674 MB on repeat.
2. Shader Recompiler: identical vendor-driver fault on `rsx::thread`; exit RSS about 660 MB.
3. Shader Interpreter only: identical driver instruction; exit RSS 2.2 GB. This rules out RPCSX shader-worker concurrency as the cause.
4. Minimal video overrides (no forced read/write color/depth buffers, no multithreaded RSX or relaxed ZCULL): identical `RSX.W1` fault; exit RSS 2.4 GB.
5. Minimal profile with asynchronous texture streaming disabled: identical fault in both `RSX.W1` and `RSX.W2`; exit RSS 1.7 GB. This eliminates the remaining title-specific streaming toggle as the cause.

Thermal status reached 3 and CPU sensors approximately 78–82 °C in the first run. That can reduce performance, but the repeat at low RSS and the stable Mali instruction identify a driver fault rather than an Android LMK event.

## Frame Limit and Patch Notes

- The game is natively a 30 FPS title. `30` is the sensible mobile cap; `60`, `Display`, and `Infinite` only remove or raise the emulator limiter.
- The official RPCS3 patch database entry for the tested PPU hash (`ad7bfe5eb63563703c893ab10930c35cf80e8d0e`) exposes `Disable Mesh Trimming`. It does not provide an official 60 FPS patch for this build, so SambaS3 does not invent one.
- Official patch API: <https://rpcs3.net/compatibility?patch&api=v1&v=1.2>

## 2026-09-11 OnePlus control rerun

A fresh control run on the prior installed core exercised the Kotlin PPU bridge
before the shader-cache release. The visible progress advanced monotonically
from 12/71 (16%) to 33/71 (46%) and 57/71 (80%), then the native logs completed
link/apply without error. The game produced a clean inFamous 2 logo frame at
about 31.8 FPS. Before deterministic gameplay input or a requested stop,
however, Android recorded process signal 11 at about 0.93 GB RSS. The captured
logs contain no Vulkan error, backend fatal, crash-buffer record, or LMK/OOM
event. This run validates PPU UI progress behavior, but it is not a stop/reopen
shader-cache pass and weakens the earlier blanket playable claim until the
current native patch is retested.

- PPU samples: `/tmp/infamous2-reopen-control-attempt1-30s.png`,
  `/tmp/infamous2-reopen-control-attempt1-75s.png`,
  `/tmp/infamous2-reopen-control-attempt1-130s.png`
- Live post-PPU frame: `/tmp/infamous2-reopen-control-attempt1-180s.png`
- Unexpected-stop launcher frame:
  `/tmp/infamous2-reopen-control-attempt1-210s.png`
- Evidence bundle: `/tmp/infamous2-post-ppu-unexpected-stop-20260911`

## 2026-09-11 patched-core stop/reopen validation

The first launch attempted immediately after the Uncharted 2 stop was rejected
as an invalid control when preflight exposed a leaked global `SPU Decoder:
Interpreter (dynamic)`. Its canonical stop did not acknowledge and Android
recorded signal 11 at about 1.0 GB RSS. Evidence was captured before the app was
force-stopped, and the global baseline was repaired offline to LLVM, automatic
compile threads, Safe block mode, loop detection off, Approximate XFloat, and
zero Stub PPU Traps.

With that repaired baseline, current patch `2dc094ae` linked the cached PPU
objects and produced a clean inFamous 2 logo at about 28.3 FPS within 30
seconds. By 65 seconds the surface had changed to black with no reported FPS.
It remained black beyond the required 120-second no-frame threshold while CPU
and RSX stayed busy. The evidence contains no backend fatal, Vulkan error,
crash buffer, or LMK/OOM event, so this is classified as a frame timeout rather
than a graphics-driver crash.

The canonical stop then timed out in native shutdown. The frontend journaled
`FAILED` with `HomeStop`, restored all leased settings, scheduled clean-process
recovery, and intentionally sent signal 9 to its own stuck process. The signal
9 is therefore controlled containment, not an unhandled emulator crash. This
attempt did not reach gameplay or prove a clean shader-cache reopen; one final
cache-reuse launch remains in the bounded validation.

- Invalid dynamic-leak stop bundle:
  `/tmp/infamous2-dynamic-leak-stop-failure-20260911`
- Valid LLVM 30-second frame:
  `/tmp/infamous2-current-core-valid-attempt1-30s.png`
- Black 65-second frame:
  `/tmp/infamous2-current-core-valid-attempt1-65s.png`
- Timeout frame and pre-stop evidence:
  `/tmp/infamous2-current-core-valid-attempt1-timeout.png`,
  `/tmp/infamous2-current-core-valid-attempt1-timeout-20260911`
- Stop bridge and post-stop evidence:
  `/tmp/samba-bridge-95r4yy/logcat.txt`,
  `/tmp/infamous2-current-core-valid-attempt1-stop-failure-20260911`

The second and final cache-reuse launch validated copied surface samples, but
never showed the clean logo seen on attempt 1. Screenshots at roughly 30, 60,
and 165 seconds were black with no FPS and the same displayed frame count while
CPU and RSX remained busy. Vulkan initialized at emulator time 01:38 and the
game continued running until emulator time 04:49, so the long black interval
was not merely pre-renderer PPU compilation. There was again no backend fatal,
Vulkan error, crash buffer, or LMK/OOM evidence.

Unlike attempt 1, final teardown was clean. A Home stop requested at 00:01:26
reached native `Stopped` in 607 ms, completed shader/RSX cleanup and `Objects
cleared`, restored the setting lease, recorded `CLEAN_STOP`, and returned to
MainActivity without a process restart. This proves one clean stop after a
cache-reuse launch, but the reopen itself still failed to render and never
reached gameplay. The current shader-cache patch is therefore not yet
validated as resolving the all-games stop/reopen defect.

- Reopen launch bridge: `/tmp/samba-bridge-4B1vlc/logcat.txt`
- Reopen black frames:
  `/tmp/infamous2-current-core-valid-attempt2-30s.png`,
  `/tmp/infamous2-current-core-valid-attempt2-60s.png`,
  `/tmp/infamous2-current-core-valid-attempt2-timeout.png`
- Final evidence and clean-stop trace:
  `/tmp/infamous2-current-core-valid-attempt2-timeout-20260912`

Current status: **not validated playable on the patched core. Both bounded
LLVM launches stalled on black output; the first required controlled
clean-process recovery after native stop timed out, while the second stopped
cleanly in 607 ms.**

## Reproduction

```bash
./scripts/debug-launch-game.sh SERIAL direct_iso/BCUS98125
./scripts/debug-pad.sh SERIAL START
./scripts/get-samba-logs.sh SERIAL /tmp/infamous2-evidence
```

Use controller broadcasts only after the launch acknowledgement. On every failure, collect one evidence bundle before a changed retry. Preserve the completed PPU cache while changing renderer settings.
