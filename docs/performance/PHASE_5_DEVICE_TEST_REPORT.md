# Phase 5 On-Device Validation Report: Prime-Core Rendering Affinity

**Run ID:** `phase5-session-20260925-172923`  
**Date:** `2026-09-25T17:29:28.656999+00:00`  
**Device:** `OnePlus 13R` (`d30a1726`) — Snapdragon 8 Gen 3 (SM8650)  
**Workload:** `God of War® III` (`BCUS98111`)  
**Duration:** `398.8 seconds`  
**Stop Reason:** `USER_REQUESTED_STOP`  
**Active Emulator PID:** `9962`  

---

## 1. Executive Summary

This test validates the **Phase 5 Alternative Scheduler policy** on real Snapdragon 8 Gen 3 silicon under active emulation load. The objective is to verify that:
1. The classified frame rendering backend thread (`thread_class::rsx` / `rsx::thread`) successfully queries live kernel cpusets and applies an affinity preference to the Cortex-X4 Prime Core (`0x80`, Core 7) and Big performance cluster (`0x7C`, Cores 2–6).
2. Heavy SPU worker threads and PPU execution threads are confined to the performance cluster (`0xFC`), preventing them from spilling over into efficiency cores.
3. Actual kernel thread affinity readback confirms placement via `sched_getaffinity` per thread ID.

---

## 2. Performance Metrics & Frame Analysis

| Metric | Measured Value | Unit |
|---|---|---|
| **Total Qualified Frame Events** | `125` (Sampled Crosschecks) | events |
| **Total In-Engine Presented Frames** | `3,676` | frames |
| **Rolling FPS Mean** | `24.37` | FPS |
| **Rolling FPS Median (P50)** | `15.71` | FPS |
| **Rolling FPS 95th Percentile (P95)** | `60.00` | FPS |
| **Rolling FPS Min / Max** | `5.66 / 70.25` | FPS |
| **Mean Frame Time** | `58.43` | ms |
| **Median Frame Time (P50)** | `52.95` | ms |
| **90th Percentile Frame Time (P90)** | `97.31` | ms |
| **95th Percentile Frame Time (P95)** | `120.27` | ms |
| **99th Percentile Frame Time (P99)** | `160.98` | ms |
| **Active Duration** | `398.8` | s |
| **Screenshots Captured** | `26` | files |

---

## 3. Live Kernel Thread Affinity Readback

The following live kernel placement readbacks were recorded from `Thread.cpp::set_thread_affinity_mask` via `sched_getaffinity` during this session:

```text
1790357367.430  9962 10085 I RPCSX-UI: Thread affinity applied: tid=10085, name='rsx::thread', req=0xfc, eff=0xfc, readback=0xfc
1790357367.441  9962 10087 I RPCSX-UI: Thread affinity applied: tid=10087, name='PPU[0x1000000] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.881  9962 10357 I RPCSX-UI: Thread affinity applied: tid=10357, name='SPU[0x0000100] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.882  9962 10358 I RPCSX-UI: Thread affinity applied: tid=10358, name='SPU[0x1000100] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.882  9962 10359 I RPCSX-UI: Thread affinity applied: tid=10359, name='SPU[0x2000100] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.883  9962 10360 I RPCSX-UI: Thread affinity applied: tid=10360, name='SPU[0x3000100] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.883  9962 10361 I RPCSX-UI: Thread affinity applied: tid=10361, name='SPU[0x4000100] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.883  9962 10362 I RPCSX-UI: Thread affinity applied: tid=10362, name='PPU[0x1000001] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.883  9962 10363 I RPCSX-UI: Thread affinity applied: tid=10363, name='PPU[0x1000002] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.884  9962 10364 I RPCSX-UI: Thread affinity applied: tid=10364, name='SPU[0x0000200] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.884  9962 10365 I RPCSX-UI: Thread affinity applied: tid=10365, name='PPU[0x1000003] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.885  9962 10366 I RPCSX-UI: Thread affinity applied: tid=10366, name='PPU[0x1000004] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.890  9962 10367 I RPCSX-UI: Thread affinity applied: tid=10367, name='PPU[0x1000005] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.890  9962 10368 I RPCSX-UI: Thread affinity applied: tid=10368, name='PPU[0x1000006] ', req=0xfc, eff=0xfc, readback=0xfc
1790357415.890  9962 10369 I RPCSX-UI: Thread affinity applied: tid=10369, name='PPU[0x1000007] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.030  9962 10370 I RPCSX-UI: Thread affinity applied: tid=10370, name='PPU[0x1000008] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.030  9962 10371 I RPCSX-UI: Thread affinity applied: tid=10371, name='PPU[0x1000009] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.031  9962 10372 I RPCSX-UI: Thread affinity applied: tid=10372, name='PPU[0x100000a] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.031  9962 10373 I RPCSX-UI: Thread affinity applied: tid=10373, name='PPU[0x100000b] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.070  9962 10374 I RPCSX-UI: Thread affinity applied: tid=10374, name='PPU[0x100000c] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.071  9962 10375 I RPCSX-UI: Thread affinity applied: tid=10375, name='PPU[0x100000d] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.073  9962 10085 I RPCSX-UI: Thread affinity applied: tid=10085, name='rsx::thread', req=0xfc, eff=0xfc, readback=0xfc
1790357416.073  9962 10377 I RPCSX-UI: Thread affinity applied: tid=10377, name='PPU[0x100000e] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.088  9962 10378 I RPCSX-UI: Thread affinity applied: tid=10378, name='PPU[0x100000f] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.088  9962 10379 I RPCSX-UI: Thread affinity applied: tid=10379, name='PPU[0x1000010] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.139  9962 10380 I RPCSX-UI: Thread affinity applied: tid=10380, name='PPU[0x1000011] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.140  9962 10381 I RPCSX-UI: Thread affinity applied: tid=10381, name='PPU[0x1000012] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.152  9962 10382 I RPCSX-UI: Thread affinity applied: tid=10382, name='PPU[0x1000013] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.152  9962 10383 I RPCSX-UI: Thread affinity applied: tid=10383, name='PPU[0x1000014] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.152  9962 10384 I RPCSX-UI: Thread affinity applied: tid=10384, name='PPU[0x1000015] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.153  9962 10385 I RPCSX-UI: Thread affinity applied: tid=10385, name='PPU[0x1000016] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.153  9962 10387 I RPCSX-UI: Thread affinity applied: tid=10387, name='PPU[0x1000017] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.154  9962 10388 I RPCSX-UI: Thread affinity applied: tid=10388, name='PPU[0x1000018] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.154  9962 10389 I RPCSX-UI: Thread affinity applied: tid=10389, name='PPU[0x1000019] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.155  9962 10390 I RPCSX-UI: Thread affinity applied: tid=10390, name='PPU[0x100001a] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.155  9962 10391 I RPCSX-UI: Thread affinity applied: tid=10391, name='PPU[0x100001b] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.156  9962 10392 I RPCSX-UI: Thread affinity applied: tid=10392, name='PPU[0x100001c] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.156  9962 10393 I RPCSX-UI: Thread affinity applied: tid=10393, name='PPU[0x100001d] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.157  9962 10394 I RPCSX-UI: Thread affinity applied: tid=10394, name='PPU[0x100001e] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.158  9962 10395 I RPCSX-UI: Thread affinity applied: tid=10395, name='PPU[0x100001f] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.158  9962 10396 I RPCSX-UI: Thread affinity applied: tid=10396, name='PPU[0x1000020] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.159  9962 10397 I RPCSX-UI: Thread affinity applied: tid=10397, name='PPU[0x1000021] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.160  9962 10398 I RPCSX-UI: Thread affinity applied: tid=10398, name='PPU[0x1000022] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.160  9962 10399 I RPCSX-UI: Thread affinity applied: tid=10399, name='PPU[0x1000023] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.161  9962 10400 I RPCSX-UI: Thread affinity applied: tid=10400, name='PPU[0x1000024] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.161  9962 10401 I RPCSX-UI: Thread affinity applied: tid=10401, name='PPU[0x1000025] ', req=0xfc, eff=0xfc, readback=0xfc
1790357416.162  9962 10402 I RPCSX-UI: Thread affinity applied: tid=10402, name='PPU[0x1000026] ', req=0xfc, eff=0xfc, readback=0xfc
1790357418.056  9962 10420 I RPCSX-UI: Thread affinity applied: tid=10420, name='PPU[0x1000027] ', req=0xfc, eff=0xfc, readback=0xfc
1790357429.256  9962 10466 I RPCSX-UI: Thread affinity applied: tid=10466, name='PPU[0x1000028] ', req=0xfc, eff=0xfc, readback=0xfc
1790357430.931  9962 10473 I RPCSX-UI: Thread affinity applied: tid=10473, name='PPU[0x1000029] ', req=0xfc, eff=0xfc, readback=0xfc
1790357437.914  9962 10505 I RPCSX-UI: Thread affinity applied: tid=10505, name='PPU[0x100002a] ', req=0xfc, eff=0xfc, readback=0xfc
```

### `/proc/$PID/task/*/status` Snapshot:

```text
PID   TID PSR COMM
9962  9962   2 [ithblue.sambas3]
9962  9963   5 [Signal Catcher]
9962  9964   3 [perfetto_hprof_]
9962  9965   2 [Jit thread pool]
9962  9966   6 [HeapTaskDaemon]
9962  9968   3 [ReferenceQueueD]
9962  9969   5 [FinalizerDaemon]
9962  9970   5 [FinalizerWatchd]
9962  9971   2 [binder:9962_1]
9962  9972   0 [binder:9962_2]
9962  9975   3 [binder:9962_3]
9962  9978   5 [Profile Saver]
9962  9979   2 [RenderThread]
9962  9982   4 [DefaultDispatch]
9962  9983   6 [DefaultDispatch]
9962  9984   6 [DefaultDispatch]
9962  9986   6 [Log Writer]
9962  9987   3 [ithblue.sambas3]
9962  9988   1 [Thread-5]
9962  9989   1 [Thread-6]
9962  9990   5 [DefaultDispatch]
9962  9991   2 [TracingMuxer]
9962  9992   4 [pool-4-thread-1]
9962  9993   4 [hwuiTask0]
9962  9994   3 [hwuiTask1]
9962  9995   6 [DefaultDispatch]
9962  9999   2 [DefaultDispatch]
9962 10000   2 [DefaultDispatch]
9962 10001   1 [GrallocUploadTh]
```

---

## 4. Captured Gameplay Screenshots

A total of **26** screenshots were captured across the session:

- `screen_0016s.png`
- `screen_0032s.png`
- `screen_0048s.png`
- `screen_0064s.png`
- `screen_0081s.png`
- `screen_0097s.png` (Full 3D in-game render)
- `screen_0112s.png`
- `screen_0127s.png`
- `screen_0143s.png`
- `screen_0159s.png`
- `screen_0176s.png`
- `screen_0191s.png`
- `screen_0206s.png`
- `screen_0222s.png`
- `screen_0238s.png`
- `screen_0253s.png`
- `screen_0269s.png`
- `screen_0285s.png`
- `screen_0300s.png`
- `screen_0316s.png`
- `screen_0332s.png`
- `screen_0348s.png`
- `screen_0364s.png`
- `screen_0379s.png`
- `screen_0395s.png`
- `screen_boot_00s.png`

---

## 5. Evidence Artifacts

The complete evidence packet has been preserved in:
`/home/abhaybyte/repos/samba-s3/docs/benchmarks/evidence-phase5-session-20260925-172923`

- `logcat-streamed.log` — Continuous host logcat stream during the live run (54.9 MB).
- `logcat-process.log` — Rotated in-process logs with fatal/affinity tags.
- `threads-affinity.txt` — Live `Cpus_allowed` masks and kernel readback log events.
- `threads-top.txt` — Thread CPU utilization and state snapshot.
- `thermal.txt` — Dumpsys thermalservice snapshot.
- `meminfo.txt` — Process memory allocation.
- `frame-analysis.json` — Structured frame timing and percentile metrics.
- `screenshots/` — All 26 captured milestone screencaps.
- `run-manifest.json` — Device and library provenance manifest.
