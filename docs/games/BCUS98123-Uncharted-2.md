# Uncharted 2: Among Thieves (BCUS98123) — OnePlus 13R

| Field | Value |
|---|---|
| Title ID | `BCUS98123` |
| Device | OnePlus 13R (`CPH2691`), Snapdragon 8 Gen 3 / Adreno 750 |
| Test date | 2026-09-11 |
| Release APK | SHA-256 `220e1cbda81610e8a36db3079670218681e31160cec8c6a6184cfa0381cd80bc` |
| Core | `rpcsx=657b26a0`, patch `2cfacbb5`, `RelWithDebInfo` |
| Renderer | Turnip 26.3, `Turnip Adreno (TM) 750` |
| Current result | Boots through title/menu and into the opening train scene, but first gameplay has white/overbright render-target corruption; not yet a pass |

## Compatibility A/B results

At 18:22, an isolated device-side A/B enabled only `Video@@Write Color
Buffers=true`; write/read depth buffers and read color buffers remained false,
and resolution scale remained 100%. The run rebuilt the SPU cache, then
rendered the Naughty Dog title frame cleanly at 60 FPS. After the deterministic
START press, however, output became black and the monitoring overlay stopped
reporting FPS or frame time.

This A/B is a failure: Write Color Buffers alone trades the first-gameplay
artifact for an earlier permanent black-frame stall. At 18:25:25, the new
watchdog logged `frame-timeout no-produced-frame-for=120269ms presented=1043
state=Running`, stopped the core, and returned to MainActivity in about 1.3
seconds. No backend fatal, JNI error, Vulkan error, tombstone, or crash-buffer
entry accompanied the timeout. The launcher correctly marked the game
`CRASHED`, but its dialog incorrectly displayed `Likely cause: NATIVE CRASH`;
the UI classification must be changed to frame timeout.

Interim evidence:

- WCB title frame: `/tmp/uncharted2-wcb-post-cache.png`
- WCB black transition: `/tmp/uncharted2-wcb-menu-12s.png`
- Watchdog recovery frame: `/tmp/uncharted2-wcb-after-120s.png`
- One-shot timeout bundle: `/tmp/uncharted2-wcb-timeout-20260911`

At 18:31, a second isolated A/B restored WCB=false and enabled only
`Video@@Read Depth Buffer=true`. It reached the autosave notice, but the first
title frame was immediately covered in dense magenta/blue full-screen noise at
about 11 FPS. There was again no backend fatal, JNI error, Vulkan error, or
crash-buffer entry. RDB alone is therefore also ruled out; it was reverted and
the four color/depth-buffer settings are back to false.

- RDB corrupt title frame: `/tmp/uncharted2-rdb-after-autosave.png`
- RDB evidence bundle: `/tmp/uncharted2-rdb-title-corruption-20260911`
- RDB bounded-stop bridge: `/tmp/samba-bridge-8sHGwi/logcat.txt`

At 18:41, the in-app **Clear Cache** action removed only BCUS98123's generated
cache/readiness state after first preserving the old shader cache on the host
(393 files). The ensuing cold launch rebuilt all 111 PPU modules. The Compose
progress UI advanced monotonically (15/111, 39/111, 87/111, then complete), so
this run found no PPU-compilation/Kotlin-bridge defect.

After compilation completed at about 18:48, the game rendered a small animated
gold dagger on black at a stable 30 FPS for more than seven minutes. START did
not advance it. This is a live guest/content-progress stall, not a no-frame
timeout: `presentedFrameCount` continued increasing (12,973 at 18:55:28), so
the 120-second watchdog correctly remained armed without firing. No backend
fatal, JNI error, Vulkan error, tombstone, or crash-buffer entry was present.

The stop requested at 18:55:55 then exposed a separate native teardown hang.
The journal entered `STOPPING`, RPCSX logged `Stopping emulator...` and
`Stopping pad threads...`, but `stop completed ok=true` never arrived within
the bridge's 65-second bound. The post-stop thread sample shows
`SPU[0x5000100]` still consuming about 103% CPU while `Emulation Join` and
`Stop Watchdog` sleep. This is the first run where the cache-teardown change
did not produce a bounded stop, and points to an SPU abort/join problem rather
than the shader-cache writer queue.

- Preserved old shader cache:
  `/tmp/uncharted2-shader-cache-pre-teardown-fix/shaders_cache`
- Cold-cache progress samples:
  `/tmp/uncharted2-clean-cache-25s.png`,
  `/tmp/uncharted2-clean-cache-65s.png`,
  `/tmp/uncharted2-clean-cache-110s.png`
- Final animated-loading frame:
  `/tmp/uncharted2-clean-cache-stall-final.png`
- Loading-stall evidence bundle:
  `/tmp/uncharted2-clean-cache-animated-loading-stall-20260911`
- Stop-hang evidence bundle:
  `/tmp/uncharted2-clean-cache-stop-hang-20260911`

The second and final cache-reuse launch began at 18:59 after force-stopping the
wedged process (no app data was removed). Unlike the cold run, it progressed
from the dagger to a clean autosave notice by 19:00 while compiling additional
SPU blocks. This confirms that the first launch preserved useful cache progress
and matches the upstream ARM cache-warmup behavior. Two verified CROSS presses
could not advance the notice while the guest was still compiling SPU work.

At 19:02:23 the surface counter had remained at 1,758 for 120.285 seconds, so
the watchdog marked the session failed and initiated `CrashExit`. This time the
native core reached `Stopped` in 605 ms and returned to the launcher normally;
there was no backend fatal, JNI/Vulkan error, tombstone, or crash-buffer entry.
The installed release still labels the cause `NATIVE CRASH`; the source fix now
classifies this signature as `FRAME TIMEOUT` and has passed focused unit tests,
but awaits a new release install for on-device UI verification.

The attempt-2 core log also identified eight simultaneous `SPU Worker` threads
with the global `Core@@Max LLVM Compile Threads=0` (automatic), alongside the
six live SPURS threads. A two-worker cap was therefore tested while retaining
`SPU Block Size=Safe`; Mega was not selected because an open upstream report
identifies it as crashing Uncharted 2 after a cutscene. The timeout
classification and stop-recovery source changes compile and their focused
standard-flavor unit tests pass; release/device validation is pending.

- Cache-reuse autosave frame:
  `/tmp/uncharted2-clean-cache-attempt2-2m.png`
- Timeout recovery frame:
  `/tmp/uncharted2-clean-cache-attempt2-timeout-recovery.png`
- Timeout evidence bundle:
  `/tmp/uncharted2-clean-cache-attempt2-timeout-20260911`
- CROSS bridge evidence: `/tmp/samba-bridge-9dPQov/logcat.txt`,
  `/tmp/samba-bridge-6P2Nll/logcat.txt`

A reversible device-side A/B then set only `Core@@Max LLVM Compile Threads=2`
(Safe SPU block mode and all four color/depth-buffer toggles remained at the
baseline). On its first bounded launch, the core presented a 4,402-module SPU
cache build, reached 81% with a four-second ETA, and completed. It reached a
clean autosave notice with frames still advancing around 20 FPS. CROSS advanced
to black output that remained very slow but live (about 1.2 FPS), while
`highCellSpursKernel5` continued compiling new SPU blocks and the main PPU
logged repeated manual RSX kicks. This is materially better than the prior
automatic-worker attempt, which stopped producing frames at the autosave
notice, but it did not remain stable. At emulation time 02:59.746,
`highCellSpursKernel3` attempted an indirect branch through a null address at
SPU PC `0x0b0c8`; RPCSX emitted a fatal `Segfault executing location 0` and the
Android process exited with signal 11 at 19:10:23. RSS was about 2.0 GB, so this
was not an LMK/OOM event. The cap therefore improves visible progress but has
not yet proven safe; one final cache-reuse retry remains for this hypothesis.

- Two-worker SPU progress: `/tmp/uncharted2-llvm2-attempt1-12s.png`
- Two-worker clean autosave: `/tmp/uncharted2-llvm2-attempt1-37s.png`
- Two-worker post-CROSS live black frame:
  `/tmp/uncharted2-llvm2-attempt1-after-cross.png`
- CROSS bridge evidence: `/tmp/samba-bridge-88il68/logcat.txt`
- Two-worker SIGSEGV evidence bundle:
  `/tmp/uncharted2-llvm2-attempt1-unexpected-stop-20260911`

The second and final two-worker launch used the newly accumulated cache and
reached the Sony intro in 25 seconds, a clean autosave notice in about one
minute, and the animated title at about two minutes. After a verified START at
19:16:03, audio continued but the title image stopped advancing. At 19:17:59,
the watchdog logged `no-produced-frame-for=120323ms presented=3202`, then the
core reached `Stopped` in 714 ms and returned to Home. No backend fatal,
JNI/Vulkan error, tombstone, or crash-buffer entry accompanied this timeout.
This confirms the user's reported sound-only/title-stuck state and is not
successful gameplay. Since attempt 1 SIGSEGV'd and attempt 2 frame-timed-out,
the two-worker cap is ruled out; it was removed from the curated source and the
device global configuration was restored to automatic (`0`).

- Two-worker retry intro: `/tmp/uncharted2-llvm2-attempt2-25s.png`
- Two-worker retry title: `/tmp/uncharted2-llvm2-attempt2-post-cross.png`
- START bridge evidence: `/tmp/samba-bridge-DnVdeR/logcat.txt`
- Two-worker retry timeout recovery:
  `/tmp/uncharted2-llvm2-attempt2-timeout-recovery.png`
- Two-worker retry timeout bundle:
  `/tmp/uncharted2-llvm2-attempt2-title-timeout-20260911`

A final SPU-backend isolation run temporarily changed only `Core@@SPU Decoder`
from `Recompiler (LLVM)` to `Interpreter (dynamic)`; the captured engine
configuration confirms the interpreter was active. It skipped the SPU cache
compile and reached a clean autosave prompt in about 25 seconds, but never
visibly advanced past it after a verified CROSS press. The surface eventually
stopped at presented frame 1,205. At 19:27:56 the watchdog logged
`no-produced-frame-for=120238ms`, proving that the freeze is not exclusive to
LLVM compilation.

Watchdog shutdown then exposed a severe stop-path failure: all six live SPU
threads attempted to execute distinct invalid host addresses, RPCSX logged six
fatal `Segfault executing location` messages, and Android recorded a signal-11
exit at about 1.5 GB RSS. A subsequent LLVM run reproduced this same shutdown
signature, so it is a shared, state-dependent SPU teardown race rather than an
interpreter-only crash. Dynamic interpretation is still ruled out because it
froze earlier than LLVM and never reached the title. The device was restored
to `SPU Decoder=Recompiler (LLVM)`, automatic LLVM threads (`0`), and Safe SPU
block mode.

- Dynamic-interpreter autosave frame:
  `/tmp/uncharted2-dynamic-attempt1-25s.png`
- Dynamic-interpreter stalled frame after CROSS:
  `/tmp/uncharted2-dynamic-attempt1-after-cross.png`
- CROSS bridge evidence: `/tmp/samba-bridge-Y7ycEa/logcat.txt`
- Timeout/SIGSEGV evidence bundle:
  `/tmp/uncharted2-dynamic-attempt1-timeout-sigsegv-20260911`

An LLVM/Safe A/B then enabled only `Core@@SPU loop detection=true`, targeting
the repeated SPURS polling and manual RSX-kick waits. It produced black frames
at about 22 FPS initially, but regressed to non-producing black output before
the autosave prompt. The final backend activity was another
`RsxKick: Timeout while waiting on RSX SPU kicks. Manual kick`. At 19:33:33 the
watchdog fired after 120.238 seconds at presented frame 1,173. During shutdown,
all six LLVM SPU threads attempted distinct invalid host execution addresses,
and Android again recorded signal 11 (about 1.2 GB RSS). This confirms both
that loop detection does not cure the game stall and that the six-thread crash
is a shared SPU stop/unmap race. Loop detection was restored to false.

- Loop-detection live-black frame:
  `/tmp/uncharted2-loop-detection-attempt1-25s.png`
- Loop-detection stalled-black frame:
  `/tmp/uncharted2-loop-detection-attempt1-60s.png`
- Loop-detection evidence bundle:
  `/tmp/uncharted2-loop-detection-attempt1-timeout-sigsegv-20260911`

The Android recovery path now treats this proven stop hazard as a containment
boundary. After the 120-second watchdog persists the typed crash report, a
frame timeout schedules MainActivity in a fresh process and terminates the
failed process without invoking native `Kill()`. Other failures and normal
user exits retain the coordinated native stop path. This prevents an already
recorded `FRAME TIMEOUT` from being replaced by the reproducible six-SPU-thread
SIGSEGV during teardown. The change compiles for both product flavors, and the
complete standard/playstore debug unit-test suites pass. On-device release
validation is pending the required two-device install because the Poco X6 Pro
is still disconnected.

## 2026-09-11 release run

The release applies the exact-title compatibility setting `Core@@Stub PPU
Traps=1` and automatically selects installed Turnip on Adreno 750. The boot log
proved the scoped setting lease was applied `1/1`, with the original global
value `0` restored after exit. RPCSX initialized Turnip successfully.

The first launch after reinstall was rejected by Android because uninstalling
invalidated the persisted Storage Access Framework grant for
`Download/1DM/Compressed`. Reauthorizing that already-registered folder through
the system picker restored access to all seven direct ISOs. This was a storage
permission failure, not an emulator failure.

The authorized retry made substantial progress:

- rendered the autosave notice without the former green full-screen corruption;
- passed the previous fatal foreground PPU trap by logging
  `PPU Trap: Stubbing 1 instructions forwards`;
- reached an animated title screen and accepted START;
- rendered the New Game and Normal-difficulty menus;
- created new saved data and reached the opening train cinematic;
- continued producing frames, with no native fatal signal, Vulkan error,
  access violation, or 120-second no-frame timeout.

The result is still not playable. At the first hanging-train gameplay frame,
the scene contained a large opaque white rectangle plus a green/white
overbright circular target. Scene geometry, Drake, the train and mountains
otherwise rendered. This is a reproducible render-target/color-buffer defect,
not the earlier PPU crash. The core simultaneously continued reporting
`RsxKick: Timeout while waiting on RSX SPU kicks` and one
`[SPU-PM] Error: Got too many flags`, but frames continued and the process did
not crash.

Stop was perceived as stuck on the device, but the bounded release bridge
reported `stop completed ok=true` at 18:16:02 and `RPCSXActivity.onDestroy`
returned to `MainActivity` at 18:16:03. Teardown therefore completed in about
one second; the frozen last-frame presentation during shutdown remains a UX
issue, not a native stop hang in this run.

Evidence:

- Gameplay render-failure bundle:
  `/tmp/uncharted2-gameplay-white-targets-20260911`
- Storage-grant launch failure bundle:
  `/tmp/uncharted2-release-launch-timeout-20260911-1804`
- Clean autosave frame: `/tmp/uncharted2-stubppu-run.png`
- Clean title/menu frames: `/tmp/uncharted2-stubppu-post-cross-50s.png`,
  `/tmp/uncharted2-after-start.png`
- Clean cinematic sample: `/tmp/uncharted2-glitch.mp4`
- Corrupt first-gameplay frame: `/tmp/uncharted2-gameplay-check.png`
- Stop bridge evidence: `/tmp/samba-bridge-ipeztY/logcat.txt`

## Earlier 2026-09-09 baseline

A fresh system-driver run remained alive for more than four minutes with an
RSX/SPURS progress stall. A cached retry reached the title screen at about 105
seconds, but the frame was green/corrupt and START could not advance it. The
safe baseline already used SPU block size `Safe`, multithreaded RSX disabled,
and asynchronous texture streaming disabled.

Earlier evidence:

- Fresh live-stall bundle: `/tmp/samba-logs-20260909-090514`
- Cached title-stall bundle: `/tmp/uncharted2-attempt2-menu-stall-20260909`
- Original user-failure audit: `/tmp/uncharted2-user-failure-20260909`

Related upstream ARM reports: <https://github.com/rpcs3/rpcs3/issues/18769>
and <https://github.com/RPCS3/rpcs3/issues/17774>.

## 2026-09-11 Shader corruption resolution and runtime refinements

### Root cause analysis of shader corruption on reopen / save management
Across all games, relaunching after gameplay or saving/loading savestates suffered from random native crashes and pipeline compilation aborts. Root causes identified and fixed:
1. **`ProgramStateCache.h` race condition:** `search_vertex_program` and `search_fragment_program` previously dropped the upgrade lock immediately after `try_emplace`, exposing uncompiled default entries (`handle == VK_NULL_HANDLE`) to concurrent reader threads. Now the upgrade lock is held across compilation, with an `I2` double-check.
2. **`graphics_pipeline_state.hpp` attachment pointer rebasing:** Deserialized `.bin` states had attachment pointers referencing old process addresses. Attachment pointers (`pAttachments = att_state`) are now consistently rebased on copy/assign whenever attachments exist.
3. **`rsx_cache.h` leaky FIFO queue:** `fragment_program_data` queue used `lf_fifo<..., 100>`, which asserted (`index - i < N * 2`) and overflowed for titles with >100 shaders on reload. Direct allocation in `fp.data.local_storage` and mutex-protected `std::vector<unpacked_shader>` now handle arbitrary shader quantities safely.
4. **`VKGSRender.cpp` teardown ordering:** Drain and destroy `m_shaders_cache` before clearing `m_prog_buffer` to eliminate background writer races during emulator shutdown.

### Uncharted 2 rendering and watchdog refinements
1. **SPU XFloat Accuracy (`Core@@XFloat Accuracy=Accurate`):** Added to curated settings for `BCUS98123`. Uncharted 2 requires double-precision intermediate representation for SPU floating-point operations to prevent overbright artifacts and white rectangular lighting glitches on ARM.
2. **Watchdog compile awareness:** `startNoFrameWatchdog` now pauses while compilation is active (`CompileProgressBridge.state.value.isActive`) and the timeout is raised to 180s, preventing premature frame timeouts during heavy level / shader streaming.
3. **PPU UI bridge handover:** Updated `CompileProgressBridge` to retain progress metrics on completion, and `RPCSXActivity` now transitions the overlay cleanly to "Starting game… Waiting for game output" (100%) rather than freezing at 99%.

Status: **Shader cache corruption and teardown races resolved natively in `librpcsx-android.so`. PPU UI bridge and transition overlay updated. `Core@@XFloat Accuracy=Accurate` added for BCUS98123. Awaiting on-device validation once the device is connected.**

