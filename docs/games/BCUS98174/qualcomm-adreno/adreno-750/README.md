# The Last of Us (BCUS98174) — Qualcomm Adreno / Adreno 750

## September 13, 2026: IN-GAME PASS & 30 FPS TARGET ACHIEVED

Following targeted engine fixes, curated settings override, and auto Turnip driver selection:
- **Status:** **PASS (In-Game / 30 FPS)**
- **Device:** OnePlus 13R (`CPH2691`), Snapdragon 8 Gen 3 / Adreno 750, Turnip 26.3 Mesa driver.
- **Boot and Navigation:** PPU preparation completed (147/147 modules), SPU cache built (11,400+ modules), warning screen passed with START, developer/studio logos rendered, main menu navigated with D-pad/Cross.
- **Save/Load:** Created new save file ("New Saved Data" -> "Yes"), confirmed persistent Prologue save, and successfully loaded save with "CONTINUE Prologue".
- **In-Game Rendering:** Rendered the entire opening prologue narrative sequence (Sarah on couch, Joel phone conversation, watch gift presentation, bedroom tuck-in) with high-fidelity models, dynamic lighting, and skin shaders at rock-solid **30.0 FPS** (32.3–33.7 ms frametime).
- **Audio:** Continuous 48 kHz stereo streaming via AAudio with zero audio underruns.
- **Teardown:** Clean stop verified via `debug-stop-game.sh` in 600 ms returning to MainActivity.
- See full flat validation document: [`docs/games/BCUS98174-The-Last-of-Us.md`](../../BCUS98174-The-Last-of-Us.md).

## Current continuation — September 12, 2026

At the user's request, testing resumed on OP13R USB `d30a1726` after two
Demon's Souls boot/stop runs. Canonical launch requested `direct_iso/BCUS98174`
at 14:22:41 IST. The activity readiness gate returned the game to Home for PPU
preparation. An inspected screenshot at about 14:23 showed **Compiling PPU
Modules, module 7 of 147**, with **Stop PPU** available. Main PID `5349` and
worker PID `20121` were confirmed live. This is preparation, not a game boot
or gameplay pass; the older failure results below remain historical evidence.

- Launch acknowledgment: `/tmp/samba-bridge-EEaflE/logcat.txt`
- Inspected Home preparation screenshot: `/tmp/tlou-entry-20260912.png`
- No cache/save deletion or configuration experiment in this continuation.
- Pending: preparation completion, runtime boot, input, gameplay, stop/reopen,
  and save/load verification. Record each result here and commit/push milestones.

## September 12 evening follow-up: preparation ready, runtime still fails

On resumption, Home showed both PPU readiness labels **Ready**, no PPU worker
remained, and a previous TLOU session had recovered as **FRAME TIMEOUT**.
The intervening runs and package replacements were not continuously observed;
their outcomes must not be inferred from the earlier preparation screenshot.
Evidence was collected before another launch:
`/tmp/tlou-preparation-resume-20260912`, with Home screenshot
`/tmp/tlou-resumed-home-20260912.png`.

A new canonical launch in PID `23852` was acknowledged in
`/tmp/samba-bridge-iMID7n/logcat.txt`. The installed APK was independently hashed
as `dfafa036f586e1984525d70c643141dc34c201760829c276c927e077d60e2ccf`, matching
the existing local **debug** APK. This continuation did not install it; these
observations are diagnostic and need release-build validation.

The current core log proves **game v1.00**, **system Qualcomm Adreno 750 Vulkan
driver**, LLVM SPU, Accurate XFloat, color-buffer reads/writes enabled,
RSX memory tiling disabled, and driver wake-up delay 200. This differs from
the historical v1.11/Turnip runs. No settings or save/cache files were changed
by this continuation.

The inspected runtime screenshot shows **Applying PPU Code — 0%**. Meanwhile,
native PPU linking completed repeatedly with `failed=0`, guest threads ran,
and the main thread repeatedly logged RSX SPU-kick timeouts. The native
`S3VKSYNC` diagnostic selected `domain=host backend=gpu_label reason=android`.
This does not prove shader-file corruption, nor does the retained evidence
prove a continuously stopped presentation counter. The PPU overlay and actual
runtime state need further correlation; gameplay remains unverified.

- Runtime screenshot: `/tmp/tlou-runtime-attempt1-20260912-evening.png`
- Current-run evidence: `/tmp/tlou-runtime-attempt1-evening-20260912`
- Native stack attempt: Android rejected `run-as ... debuggerd -b 23852`
  because it could not establish the target-SDK-37 SELinux context. No native
  stack was obtained and no device security settings were changed.

Subsequent terminal evidence resolves the presentation question: at
21:03:07.994 IST, PID `23852` logged
`frame-timeout no-produced-frame-for=120571ms presented=376 state=Running surface_gen=1`.
Android recorded signal 9 at 21:03:08.559 during the deliberate clean-process
recovery. New PID `24413` classified the session as `CONFIRMED_CRASH` /
`FRAME TIMEOUT`. Thus the 120-second watchdog worked; the earlier PPU overlay
did not exempt this run. The later stop acknowledgment
`/tmp/samba-bridge-TDodoM/logcat.txt` came from the recovered launcher, not a
successful native stop of the stalled game. Session: `1789227021372-ca407844`.

### Milestone checks

The standard and playstore unit-test XML reports each contain 658 tests with
zero failures/errors. Their newest report timestamps are 11:22:45 UTC and
08:56:30 UTC respectively. Source and native-patch changes continued after
those reports; they do not validate all current changes. The documentation
checkpoint has no whitespace errors. Current native/source changes require
separate review and validation before a code milestone is committed. None
of these unit-test results establishes emulator gameplay or shader/save-load
correctness.

## Historical September 6–7 result

**Result:** FAIL — no stable or controllable gameplay was reached, so a sustained 30 FPS result is not established. Testing stopped after the real update-1.11 run ended in an SPU null DMA access followed by an RSX-thread segmentation fault.

**Tested:** 2026-09-06 to 2026-09-07

## Test identity

| Field | Observed value |
|---|---|
| Game | The Last of Us™ |
| Title ID | `BCUS98174` |
| Registered path | `direct_iso/BCUS98174` |
| Disc version | `APP_VER=01.00 VERSION=01.00` |
| Installed update | `APP_VER=01.11 VERSION=01.00` |
| Device | OnePlus `CPH2691` / `OP5D3BL1` (OnePlus 13R) |
| SoC reported by Android | `SM8650` |
| GPU vendor / architecture | Qualcomm Adreno / Adreno 7xx |
| Exact Vulkan device | `Turnip Adreno (TM) 750` |
| Vulkan driver | `26.2.99` |
| ABI | ARM64 |
| RPCSX core | `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc` |
| Samba host revision in tested build | `17ed82b6b1b4018f3f030da5fb8baddb567de39d` |
| Core patch SHA-256 | `31961be98934a5cec1a8beb3af4d401fb0030c74ee1cc582f9ad4b73aa5f4e83` |
| Build type | Debug |

The renderer identity is taken directly from the backend log:

```text
Found Vulkan-compatible GPU: 'Turnip Adreno (TM) 750' running on driver 26.2.99
Vulkan: Renderer initialized on device 'Turnip Adreno (TM) 750'
```

## Game update provenance

The initial runs booted version 1.00. The official Sony update manifest for `BCUS98174` advertised update 1.11, size `284414928`, and package payload SHA-1 `5f978c88721962b54f5b12053ee06f896ef3b4a1`.

The downloaded package was validated using the PS3 PKG convention: SHA-1 over all but the final 32-byte footer matched the manifest exactly. The package was manually installed from the app-owned external-files directory because installation from `/sdcard/Download` failed with `EACCES` under scoped storage. The installed update directory was about 272 MiB, its `PARAM.SFO` contained version 1.11, and the next boot logged:

```text
Version: APP_VER=01.11 VERSION=01.00
```

Sources: [Sony update manifest](https://a0.ww.np.dl.playstation.net/tpl/np/BCUS98174/BCUS98174-ver.xml), [PSDevWiki Online Connections](https://www.psdevwiki.com/ps3/Online_Connections), and [PSDevWiki PKG files](https://www.psdevwiki.com/ps3/PKG_files).

## Tested configurations and results

| Run | Game version | Configuration delta | Directly observed result |
|---|---:|---|---|
| Baseline | 1.00 | SPU LLVM, loop detection off | Crashed about 16 seconds after runtime start: `SPU[0x0000100] highCellSpursKernel0` read address `0x0`; Android recorded signal 11 and 2.1 GiB RSS. |
| Loop detection | 1.00 | `SPU loop detection=true` | Presented roughly 30.23–32.18 FPS during a short pre-input sample, then crashed after Start/Cross: `SPU[0x3000100] highCellSpursKernel3` read address `0x0`; signal 11, 1.6 GiB RSS. This was not a gameplay pass. |
| Accurate SPU DMA | 1.00 | Loop detection plus Accurate SPU DMA | Brief presentation near 60 FPS, then frame counter stuck at 2247 and RSX load reached 97–98%; `SPU[0x4000100] highCellSpursKernel4` read address `0x0`; signal 11, 2.0 GiB RSS. |
| Dynamic SPU interpreter | 1.00 | `SPU Decoder=Interpreter (dynamic)`, loop detection on | The backend confirmed the interpreter was active. It still failed: frame counter stuck at 1496, RSX load reached 99%, and `SPU[0x2000100] highCellSpursKernel2` wrote address `0xffdead00`; signal 11, 1.5 GiB RSS. |
| First update boot | 1.11 | Accuracy profile below | Generated a new EBOOT PPU cache. After about seven minutes, Android recorded signal 6 while thread `PPUW.1.5` was compiling; RSS was 1.2 GiB. |
| Second update boot | 1.11 | Same accuracy profile; reused cache | Entered real runtime and presented frames, then an SPU null DMA access was logged and the RSX thread segfaulted. Android recorded signal 11 and 1.6 GiB RSS. |

The version-1.11 accuracy profile was applied as a test configuration only:

```yaml
Core:
  SPU Decoder: Recompiler (LLVM)
  SPU Block Size: Safe
  SPU loop detection: true
Video:
  Write Color Buffers: true
  Read Color Buffers: true
  Write Depth Buffer: true
  Read Depth Buffer: true
  Accurate ZCULL stats: true
  Handle RSX Memory Tiling: true
  Shader Precision: High
  Driver Wake-Up Delay: 280
```

It is not shipped as a curated `GameSettingsOverrides` profile because the run failed.

## Final runtime evidence

The benchmark stream showed approximately 60 presented frames per second before the fault. That interval was not confirmed as controllable gameplay and therefore is not evidence that the game meets a 30 FPS gameplay target. Immediately before failure, the measured state degraded as follows:

```text
00:18:24.502  fps=37.2287  frametime_ms=16.799   presented=9078  rsx_load=59
00:18:25.571  fps=15.0682  frametime_ms=387.658  presented=9079  rsx_load=69
00:18:26.584  fps=15.0682  frametime_ms=387.658  presented=9079  rsx_load=64
00:18:30.640  fps=15.0682  frametime_ms=5699.65  presented=9080  rsx_load=70
00:18:31.643  fps=15.0682  frametime_ms=5699.65  presented=9080  rsx_load=97
```

The backend dump identifies the immediate SPU fault precisely. At guest SPU PC `0x04504`, `highCellSpursKernel0` issued `wrch MFC_Cmd,r9`. The captured registers were `r4=0x0`, `r9=0x20`, and `r10=0x4000`; the instruction sequence copied `r4` to the MFC effective-address register and then produced the logged read at address zero:

```text
[PUT      #31 0x4163e940:0x00000000 0x4000]
r4: 00000000
r9: 00000020
r10: 00004000
[000044f4]  wrch MFC_EAL,r68
[000044f8]  wrch MFC_Size,r10
[00004504]  wrch MFC_Cmd,r9
VM: [SPU[0x0000100] highCellSpursKernel0] Access violation reading location 0x0
SYS: Segfault writing location 00000076e9625fe8 ... Emu Thread Name: 'rsx::thread'
```

Android exit info confirms this was a native signal, not an Android low-memory kill:

```text
reason=2 (SIGNALED) status=11 rss=1.6GB
```

## Verdict and next boundary

There is no verified stable configuration for `BCUS98174` on this Adreno 750 / Turnip 26.2.99 stack. The failures reproduced with both the LLVM SPU recompiler and dynamic SPU interpreter, and persisted after installing update 1.11 and applying the current accuracy profile. The next useful work requires a core-level SPU/RSX fix or a newer core with evidence that this crash is resolved; further undocumented settings changes are not justified by these logs.

The current upstream ARM64 report also describes SPURS/RSX freezes with update 1.11 on Adreno/Turnip: [RPCS3 issue #18828](https://github.com/RPCS3/rpcs3/issues/18828). Repeated boots building the ARM PPU cache are tracked separately in [RPCS3 issue #18769](https://github.com/RPCS3/rpcs3/issues/18769).

## Evidence bundles

These bundles were collected with `scripts/get-samba-logs.sh`. They live under `/tmp` and are not durable repository artifacts:

```text
/tmp/tlou-adreno750-baseline-20260906
/tmp/tlou-adreno750-postinput-fail-20260906
/tmp/tlou-adreno750-accuratedma-fail-20260906
/tmp/tlou-adreno750-interpreter-fail-20260906
/tmp/tlou-adreno750-update111-compile-fail-20260907
/tmp/tlou-adreno750-update111-runtime-fail-20260907
```

The final bundle contains `rpcsx_app.log` for benchmark samples, `cache-RPCSX.old.log` for the backend-fatal sequence, `rpcsx_backend.log` for the register/disassembly dump, and `exit-info.txt` for Android process-exit classification.
