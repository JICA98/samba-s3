# The Last of Us (BCUS98174) — Qualcomm Adreno / Adreno 750

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
