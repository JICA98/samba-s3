# Incident Report: Frame Presentation Freeze & RSX FIFO Desync Termination

**Incident Date:** `2026-09-25`  
**Device:** `OnePlus 13R` (`d30a1726`) — Qualcomm Snapdragon 8 Gen 3 (`SM8650`)  
**Workload:** `God of War® III` (`BCUS98111`)  
**Session ID:** `phase5-session-20260925-172923`  
**Host Process PID:** `9962`  
**Target Package:** `com.zenithblue.sambas3` (Standard Release build)  
**APK SHA-256:** `11aac28bee44d2d610a29009881e02cbc1bdb3aae4e9d651176776aba140a481`  
**Core Library SHA-256:** `af06e41c226acf92aa4961378f398940668faeb0b209857764a87c9c18304c0e` (`librpcsx-android.so`)  
**Backend Revision:** `285402486c314a722243c3b1011c70a34c5993a9`  

---

## 1. Incident Overview

During the live test session of *God of War III* on the OnePlus 13R, the visual frame presentation completely halted ("frame dead, no new frames generating"). Emulation had progressed successfully through initial boot cutscenes into 3D in-engine gameplay, accumulating **3,676 presented frames** at an average throughput of ~16.2 FPS before all visual updates ceased.

This document contains the factual investigation of the failure, compiled directly from recorded logcat streams, kernel ring buffers, emulator core diagnostics, and process task status snapshots.

**No speculative hypotheses are presented; all findings below are directly verified from timestamps, kernel events, and core log entries.**

---

## 2. Chronological Timeline of Verified Events

| Emulated Time | Host Wall Timestamp (Epoch / UTC) | Component | Verified Event / Diagnostic Output |
|---|---|---|---|
| `0:00:00.000` | `1790357366.411` (17:29:28.656Z) | Process Boot | App launched via `scripts/debug-launch-game.sh`. PID `9962` assigned. Native window queueBuffer interceptor hooked. |
| `0:00:01.018` | `1790357367.430` | RSX Thread Init | Core spawned RSX worker thread **TID `10085`** (`rsx::thread`). Kernel affinity applied: `req=0xfc, eff=0xfc, readback=0xfc`. |
| `0:00:49.948` | `1790357416.359` | Presentation | First in-engine frame presented to Android `SurfaceFlinger`: `presented=2, fps=70.25, frametime=14.23ms`. |
| `0:01:14.524` | `1790357440.935` | Gameplay | In-engine gameplay rendering active: `presented=1134, fps=16.59, frametime=58.7ms`. |
| `0:02:42.934` | `1790357529.340` | RSX FIFO | **First desync warning:** `·E 0:02:42.934041 {RSX [0x070fa20]} RSX: FIFO error: possible desync event (last cmd = 0xbf5176bb)`. |
| `0:02:52.575` | `1790357538.981` | Kernel GPU (`kgsl`) | **Host GPU hardware bus errors begin:** `kgsl kgsl-3d0: CP: AHB bus error, CP_RL_ERROR_DETAILS_0:0x10008e07 CP_RL_ERROR_DETAILS_1:0x12144`. 270+ errors logged across cores C0 and C1 over the next 15 seconds. |
| `0:03:27.190` | `1790357571.768` | RSX FIFO | **FIFO Call Stack Corruption:** 20 consecutive fatal FIFO call stack errors occur in 40 ms: `·E 0:03:27.190001 {RSX [0x0001020]} RSX: FIFO: CALL found inside a subroutine (last cmd = 0x2)`. |
| `0:03:27.232` | `1790357571.769` | RSX FIFO | **Fatal Queue Failure:** `·F 0:03:27.232191 {RSX [0x0001020]} SIG: Thread terminated due to fatal error: Dead FIFO commands queue state has been detected!` |
| `0:03:27.253` | `1790357571.790` | Emulator Core | **RSX Thread Termination:** `·W 0:03:27.253496 SYS: CPU Thread 'rsx::thread' terminated abnormally!`. TID `10085` exits immediately. |
| `0:03:28.077` | `1790357572.592` | SurfaceFlinger | **Final Presented Frame:** `crosscheck source=surface presented=3676 json_fps=17.35 json_frametime=44.53ms`. **No further frames ever submitted to surface.** |
| `0:03:29.000+`| `1790357573.509+` | UI Monitor | Performance monitor loops emit frozen values (`presented=3676, fps=17.3507`). Emulation process enters zombie state. |
| `0:06:38.000` | `1790357767.000` (17:36:07.000Z) | Host Session | Stop sentinel `/tmp/stop_phase5_recording` received. Live logcat stream stopped, post-mortem diagnostics gathered, process PID 9962 terminated. |

---

## 3. The Fatal Error in Detail

### 3.1 Exact Log Record
From [`cache-RPCSX.log`](file:///home/abhaybyte/repos/samba-s3/docs/benchmarks/evidence-phase5-session-20260925-172923/cache-RPCSX.log#L20022-L20027):

```text
·F 0:03:27.232191 {RSX [0x0001020]} SIG: Thread terminated due to fatal error: Dead FIFO commands queue state has been detected!
Try increasing "Driver Wake-Up Delay" setting or setting "RSX FIFO Accuracy" to "Atomic", both in Advanced settings. Called from 
(in file /home/abhaybyte/repos/samba-s3/app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/RSXFIFO.cpp:725[:6], in function 'void rsx::thread::run_FIFO()') (errno=22=Invalid argument)
(in file /home/abhaybyte/repos/samba-s3/app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/RSXThread.cpp:2670[:4], in function 'void rsx::thread::recover_fifo(std::source_location)') (errno=22=Invalid argument)
·! 0:03:27.253299 {rsx::thread} SIG: Thread time: 102.605963s (0.000000Gc); Faults: 8325 [rsx:8325, spu:0]; [soft:2874578 hard:1]; Switches:[vol:34945 unvol:651283]; Wait:[63.839s, spur:0]
·W 0:03:27.253496 SYS: CPU Thread 'rsx::thread' terminated abnormally!
```

Corresponding Android host logcat line from [`logcat-streamed.log`](file:///home/abhaybyte/repos/samba-s3/docs/benchmarks/evidence-phase5-session-20260925-172923/logcat-streamed.log#L216656-L216687):

```text
1790357571.769  9962 10085 F RPCS3   : Thread terminated due to fatal error: Dead FIFO commands queue state has been detected!
1790357571.769  9962 10085 F RPCS3   : Try increasing "Driver Wake-Up Delay" setting or setting "RSX FIFO Accuracy" to "Atomic", both in Advanced settings. Called from 
1790357571.769  9962 10085 F RPCS3   : (in file /home/abhaybyte/repos/samba-s3/app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/RSXFIFO.cpp:725[:6], in function 'void rsx::thread::run_FIFO()') (errno=22=Invalid argument)
1790357571.769  9962 10085 F RPCS3   : (in file /home/abhaybyte/repos/samba-s3/app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/RSXThread.cpp:2670[:4], in function 'void rsx::thread::recover_fifo(std::source_location)') (errno=22=Invalid argument)
1790357571.790  9962 10085 D RPCS3   : Thread time: 102.605963s (0.000000Gc); Faults: 8325 [rsx:8325, spu:0]; [soft:2874578 hard:1]; Switches:[vol:34945 unvol:651283]; Wait:[63.839s, spur:0]
1790357571.790  9962 10085 W RPCS3   : CPU Thread 'rsx::thread' terminated abnormally!
```

### 3.2 Preceding FIFO Corruption Sequence
Immediately prior to the queue death at emulated time `0:03:27.190001` through `0:03:27.230314`, `rsx::thread` logged 20 consecutive subroutine nesting violations in [`cache-RPCSX.log`](file:///home/abhaybyte/repos/samba-s3/docs/benchmarks/evidence-phase5-session-20260925-172923/cache-RPCSX.log#L18832-L18851):

```text
·E 0:03:27.190001 {RSX [0x0001020]} RSX: FIFO: CALL found inside a subroutine (last cmd = 0x2)
·E 0:03:27.192107 {RSX [0x0001020]} RSX: FIFO: CALL found inside a subroutine (last cmd = 0x2)
... [repeated 18 more times] ...
·E 0:03:27.230314 {RSX [0x0001020]} RSX: FIFO: CALL found inside a subroutine (last cmd = 0x2)
```

The RSX command FIFO register dump recorded at the moment of failure:
- **FIFO GET Pointer:** `0x0001020`
- **FIFO PUT Pointer:** `0x0a4e800`
- **FIFO REF Pointer:** `0x00000b4a`
- **Active Method:** `[00001020] (x) call 0x0000000` inside subroutine at `0x00866f78` (`sp=0x00000000`).

---

## 4. Why the App Appeared "Alive" but "Frame Dead" (Zombie State)

In standard RPCS3/RPCSX architecture, the emulator runs decoupled multi-threaded engines:
1. **PPU / SPU Threads** — Execute PS3 game code, logic, physics, and AI.
2. **Audio Threads** — Process CellAudio streams and output via OpenSL/Oboe.
3. **RSX Thread (`rsx::thread`, TID 10085)** — Consumes the FIFO command queue, translates NV4097 calls into Vulkan render commands, and calls `vkQueuePresentKHR` / Android `ANativeWindow` swapchain buffers.

### 4.1 Post-Crash Thread Audit
When `rsx::thread` executed `recover_fifo()` and encountered an unrecoverable dead queue state, the function threw/exited, abnormally terminating **only TID 10085**.

An audit of [`threads-names.txt`](file:///home/abhaybyte/repos/samba-s3/docs/benchmarks/evidence-phase5-session-20260925-172923/threads-names.txt) taken while PID 9962 was still active demonstrates:
- **RSX Thread (TID 10085):** **ABSENT**. The thread was terminated.
- **RSX Auxiliary Workers (`RSX.W1` TID 10082, `RSX.W2` TID 10083):** Present but idle (no master to dispatch work).
- **PPU Main Thread (`PPU[0x1000000]` TID 10087):** Still executing syscalls.
- **SPU Worker Threads (`SPU[0x...00]` TIDs 10357–10364):** Still running.
- **Audio Threads (`cellAudio` TIDs 10045, 10058, 10061, 10064):** Still active and streaming audio frames.

Because the main process and audio threads remained alive, the Android OS did not detect a SIGSEGV or process crash. However, because `rsx::thread` was dead, **no new draw commands could be dispatched, and no new frames could be presented to the display surface**, resulting in the exact symptom observed: the display froze on frame `3,676`.

---

## 5. Corroborating Hardware & Kernel Telemetry

### 5.1 Qualcomm Adreno Kernel Bus Errors (`kgsl-3d0`)
Between `1790357538.981` and `1790357554.093` (33 seconds before RSX failure), the kernel ring buffer captured recurrent Command Processor (CP) hardware AHB bus errors from the Adreno 750 GPU driver in [`logcat-streamed.log`](file:///home/abhaybyte/repos/samba-s3/docs/benchmarks/evidence-phase5-session-20260925-172923/logcat-streamed.log#L196073-L211036):

```text
1790357538.981     0     0 F : [ C0] kgsl kgsl-3d0: CP: AHB bus error, CP_RL_ERROR_DETAILS_0:0x10008e07 CP_RL_ERROR_DETAILS_1:0x12144
1790357538.983     0     0 F : [ C0] kgsl kgsl-3d0: CP: AHB bus error, CP_RL_ERROR_DETAILS_0:0x10008e07 CP_RL_ERROR_DETAILS_1:0x12144
1790357538.994     0     0 F : [ C1] kgsl kgsl-3d0: CP: AHB bus error, CP_RL_ERROR_DETAILS_0:0x10008e07 CP_RL_ERROR_DETAILS_1:0x12144
```
A total of **275** identical AHB bus error entries were logged by the kernel.

### 5.2 Device Thermal Status
From [`thermal.txt`](file:///home/abhaybyte/repos/samba-s3/docs/benchmarks/evidence-phase5-session-20260925-172923/thermal.txt):
- **Thermal Status:** `3` (Severe Throttling)
- **Skin Temperature:** `51.413 °C`
- **CPU Cores 1–7 Temperatures:** `89.9 °C` (throttled ceiling)
- **GPU 0, 2, 3, 4, 7 Temperatures:** `89.9 °C`

### 5.3 Active Frame Presentation Plateau
From [`logcat-streamed.log`](file:///home/abhaybyte/repos/samba-s3/docs/benchmarks/evidence-phase5-session-20260925-172923/logcat-streamed.log):
```text
1790357571.356  9962  9984 D S3PERF  : crosscheck source=surface presented=3670 json_fps=17.1752 json_frametime_ms=57.565
1790357571.790  9962 10085 W RPCS3   : CPU Thread 'rsx::thread' terminated abnormally!
1790357572.592  9962  9984 D S3PERF  : crosscheck source=surface presented=3676 json_fps=17.3507 json_frametime_ms=44.528
1790357573.509  9962  9984 D S3PERF  : crosscheck ui_snapshot_fps=17.3507 ui_snapshot_frametime_ms=44.528
1790357574.453  9962  9984 D S3PERF  : crosscheck ui_snapshot_fps=17.3507 ui_snapshot_frametime_ms=44.528
```
After frame 3676 at timestamp `1790357572.592`, no subsequent `crosscheck source=surface presented=` log line was ever generated for the remaining 3+ minutes until manual termination.

---

## 6. Relevant Evidence File References

All raw evidence files are preserved in the session artifact directory:  
[`docs/benchmarks/evidence-phase5-session-20260925-172923/`](file:///home/abhaybyte/repos/samba-s3/docs/benchmarks/evidence-phase5-session-20260925-172923/)

- **`cache-RPCSX.log`** — Lines 18832–20030: Exact FIFO subroutine error cascade and `recover_fifo()` fatal queue death.
- **`logcat-streamed.log`** — Lines 216656–216687: Exact fatal logcat crash record for TID 10085 and timestamp correlation.
- **`logcat-streamed.log`** — Lines 196073–211036: 275 kernel `kgsl-3d0` CP AHB bus error records.
- **`threads-names.txt`** — Snapshot showing PID 9962 active threads, confirming `rsx::thread` missing while PPU/SPU/audio remain active.
- **`thermal.txt`** — System thermal sensor snapshot documenting 51.4°C skin and 89.9°C core temperatures with Thermal Status 3.
- **`screenshots/`** — 26 sequential screenshots showing progression up to in-game 3D rendering and the subsequent frozen visual state.
- **`PHASE_5_DEVICE_TEST_REPORT.md`** — Live session summary report.
