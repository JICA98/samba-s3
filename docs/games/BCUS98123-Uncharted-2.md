# Uncharted 2: Among Thieves (BCUS98123) — OnePlus 13R

> **Latest status (September 12): not fixed/playable.** Corrected strict query
> scopes still reproduce guest/presentation stalls. Several actual 120-second
> frame timeouts recovered correctly. Continuous counter evidence is still
> needed for the other apparent freezes. The compilation-related watchdog bypass
> is removed and the updated release recovered from a real timeout. A separate
> saved-log-manifest finalization bug is now being fixed.

### Corrected strict-query run — freeze reproduced; timeout recovery verified

Release APK `7e4db0c1e64fe1ab4a4850385df6394957f13ee9234c42313d6e7dec1a68dd7d`
was installed with `adb install -r` on OP13R only, preserving app data. Both the
packaged arm64 core and the recovered process reported patch `585b9ddf73eeb541`.
The six focused unit-test classes passed: 55 tests, zero failures/errors.

Canonical launch at 10:05:42 accepted `direct_iso/BCUS98123`, PID `28633`.
Effective configuration and current core log confirmed Accurate XFloat,
Strict Rendering Mode=true, Relaxed ZCULL=false, Turnip 26.2.99 / Adreno 750.
Frames advanced around 30 FPS before slowing. CROSS at 10:06:46 was acknowledged.
The surface counter reached `1080` at 10:06:49 and stopped advancing. The current
run's retained core log reproduced RSX kick timeouts at emulated 1:08.229,
followed by `[SPU-PM] Error: Got too many flags!`.

At 10:08:50.087 the watchdog emitted:

```text
frame-timeout no-produced-frame-for=120247ms presented=1080 state=Running surface_gen=1
```

Failure evidence persisted before clean-process recovery terminated the stalled
process. Android exit-info records PID `28633`, SIGNALED/status 9 at 10:08:50.554;
this was the deliberate recovery, not evidence of an independent native crash.
New PID `30070` returned to MainActivity and reported `CONFIRMED_CRASH`, cause
`FRAME TIMEOUT`, at 10:08:50.885. No Vulkan device loss or native fatal was found
in this run's retained evidence. The pre-test global configuration was restored
byte-for-byte, SHA-256 `5577258bbc9f73d195ec3ede7f8112e0428f776c3b94641164d4422f81caa4d8`.

This verifies the requested timeout classification/recovery on a real frame
stall, not gameplay or visual correctness. The screenshot CLI was unavailable;
no screenshot was inspected. Stop/reopen and save/load correctness across games
remain unverified. Next useful evidence is a native thread backtrace during the
stall, before recovery, rather than another untraced graphics-setting change.

- Launch acknowledgment: `/tmp/samba-bridge-by3qkl/logcat.txt`
- CROSS acknowledgment: `/tmp/samba-bridge-3XuCBz/logcat.txt`
- One-shot evidence: `/tmp/u2-strict-query-fixed-attempt1-20260912`
- Pre-recovery core log: `cache-RPCSX.old.log` inside that bundle (recovery rotated it).

### Second strict-query run — same failure without pad input

The second bounded run launched at 10:12:15 in PID `30070` with the same
installed release and test configuration. No pad input was sent. The surface
counter reached `1182`, then the watchdog fired at 10:15:30.682 after
`120263ms` without a produced frame. Home recovery again classified
`CONFIRMED_CRASH` / `FRAME TIMEOUT`. This reproduction does not require CROSS.

`debuggerd -b 30070` was attempted while the process was live; Android refused
with `root is required`. No native backtrace was obtained and no privilege
escalation was attempted. Shell thread wait-channel output was zero/redacted,
so it cannot locate the stalled native function.

After the user authorized/installed agent-device 0.21.1, a screenshot was
captured and inspected. It shows the recovered launcher with `CRASHED` and
`Likely cause: FRAME TIMEOUT`, not the guest frame during the stall. The original
global configuration was restored after collection. Both strict-query attempts
are exhausted; no further identical setting test is warranted.

- Launch: `/tmp/samba-bridge-CUuvep/logcat.txt`
- One-shot evidence: `/tmp/u2-strict-query-fixed-attempt2-20260912`
- Recovery screenshot (filename predates observed state): `/tmp/u2-strict-query-attempt2-stall.png`

The next diagnostic adds one warning after one million unsuccessful query-result
polls in `query_pool_manager::get_query_result`. It leaves waiting and returned
query values unchanged. A warning would positively identify this wait path;
its absence would not rule out a blocked driver call or an earlier wait.

### Query-poll diagnostic — autosave freeze; timeout behavior unresolved

Diagnostic APK `1a2b4a73836412ec42224e29e138dafea66960094a9af7232f16e244b3a6da7b`,
core patch `974dd375`, built successfully and was installed on OP13R without
deleting data. PID `6215` launched at 10:22:58. Accurate XFloat and strict
rendering were confirmed. Presentation stopped at `1097` around 10:24:08;
RSX kick/SPU flags errors followed. No `S3VKQUERY` warning appeared in the
current run's retained core log. This does not prove the driver call itself
cannot block, but supplies no positive evidence for the million-poll loop.

The inspected screenshot shows the autosave notice and CROSS Continue prompt,
with unavailable FPS/frame metrics. CROSS was acknowledged at 10:25:15 but did
not restore presentation. The live evidence bundle sampled `rsx::thread` at
96.2% CPU and main PPU at 100%; other PPU/SPU threads remained active.

Unlike the two prior runs, this process stayed at RPCSXActivity past 10:27:57
without automatic timeout recovery. A continuous native-counter trace was not
captured across that interval, so this alone does not prove a watchdog miss:
occasional frames could reset its deadline despite an apparently frozen guest.
Canonical stop at 10:28:51 returned
`stop completed ok=true`, after evidence had been collected. No shader-cache
deletion, save deletion, or native crash was needed to stop this run.

- Launch: `/tmp/samba-bridge-c2G1ym/logcat.txt`
- Early live-black screenshot: `/tmp/u2-query-diagnostic-launch.png`
- Frozen autosave screenshot: `/tmp/u2-query-diagnostic-frozen.png`
- CROSS acknowledgment: `/tmp/samba-bridge-Prq06q/logcat.txt`
- Live one-shot evidence: `/tmp/u2-query-diagnostic-attempt1-20260912`
- Clean stop: `/tmp/samba-bridge-KeD92p/logcat.txt`

Android's documented `<profileable android:shell="true" />` supports local
`simpleperf` stack profiling in release builds without making the app debuggable.
It is now enabled for the next diagnostic; packaged manifest verification passed.
APK `340863b33f3bddc9baa7352bc322e08142f290b60e1ba0ed97b76dcc81a3a55b` was installed
on OP13R, retaining core patch `974dd375`.

### Native profiler result and watchdog eligibility correction

The profileable release launched at 10:31:19, PID `9991`. Direct `simpleperf -p`
sampling failed with `Permission denied`; its app-aware route failed setting
the SELinux context for UID 10440 / targetSdkVersion 37. The device already had
`security.perf_harden=0` and `perf_event_paranoid=-1`; no security settings were
changed and no root/bypass was attempted. No usable native profile was obtained.
The ineffective manifest flag and temporary query-poll warning were removed.

This run later timed out at 10:35:38.917 after `120261ms`, `presented=1733`, and
returned to Home as `FRAME TIMEOUT`. Earlier telemetry had only reached `1084`;
the later higher count demonstrates why missing telemetry must not be treated
as proof of a continuously stopped counter. The one-shot bundle predates this
timeout; the terminal event was read from live logcat afterward.

Source review also found a real eligibility loophole: `CompileProgressBridge`
being active disabled the watchdog even when RPCSX reported `Running`. That
compilation exemption is removed: foreground Running sessions remain subject
to the user's 120-second no-frame limit. Paused/non-running and background
sessions remain excluded. This is not yet proven to explain the prior apparent
miss, and runtime validation of the correction remains pending.

- Launch: `/tmp/samba-bridge-sod9Ys/logcat.txt`
- Pre-timeout one-shot evidence: `/tmp/u2-native-profile-attempt1-20260912`
- Post-recovery clean-stop acknowledgment: `/tmp/samba-bridge-Kgd3rT/logcat.txt`

The corrected watchdog release built and installed successfully on OP13R:
APK `0080a549839fdc7fcc1de0fe984acad782e8f5368eda3bf0a778356cfcb68cb9`,
core patch `585b9ddf`. All 55 focused tests passed again. Packaged-core and
manifest checks confirmed removal of the temporary query marker and profiling
flag. No app data was deleted. Its first validation run launched at 10:41:42,
PID `14451`, acknowledgment `/tmp/samba-bridge-IfbcI7/logcat.txt`.

That validation reached the autosave notice, then stopped advancing on CROSS
(acknowledged at 10:44:02). Last visible telemetry showed `presented=1081` at
10:42:52.358. New PID `15912` reported `CONFIRMED_CRASH` / `FRAME TIMEOUT` at
10:44:52.725, and the recovered launcher screenshot confirms that classification.
The pre-test global configuration was restored with SHA-256 `5577258b…`.

- One-shot evidence: `/tmp/u2-watchdog-ungated-attempt1-20260912`
- Autosave screenshot: `/tmp/u2-watchdog-ungated-launch.png`
- Recovery screenshot: `/tmp/u2-watchdog-ungated-recovery.png`
- CROSS acknowledgment: `/tmp/samba-bridge-fBPSzq/logcat.txt`

USB transport `d30a1726` replaced offline Wi-Fi ADB during evidence collection.
The run's external log manifest, session `1789189902538-b8263198`, still contained
`terminalState=RUNNING`, `endedAtMs=0`, `stopReason=""`, `captureState=RECORDING`
after recovery. This is a separate, directly observed persistence bug: the
clean-process timeout path bypassed `EmulatorStopCoordinator`'s log finalization.

The shared failed-stop/frame-timeout restart path now finalizes the existing log
session as `FAILED` with its actual failure detail before scheduling process
termination. It uses `RECOVERED_PARTIAL`, since live native writers cannot be
safely drained. A regression test checks the persisted state, timeout cause,
end timestamp, and partial-capture label before the scheduled kill executes.
Persistence correction validated on OP13R USB `d30a1726` with release APK
`f084e77b5d04b1742c7600bdf24f356abd65759025d0fd52f8ade7e8f8599308`.
All 26 selected regression tests passed (zero failures/errors), including native
snapshot preservation before forced restart. No app data was deleted.

Session `1789191590103-a168e837` launched at 11:09:50 IST on September 12,
PID `27831`, with Accurate XFloat and Strict Rendering Mode enabled. No pad input
was sent. Recovery started at 11:13:17 IST; the persisted reason is exactly
`frame-timeout no-produced-frame-for=120282ms presented=1252 state=Running surface_gen=1`.
The manifest now correctly contains `terminalState=FAILED`,
`endedAtMs=1789191797574`, and `captureState=RECOVERED_PARTIAL`.
It retains a sealed 6,529,813-byte native `RPCSX.log` snapshot, SHA-256
`c98fba9a38405593044a33b42001c748ddadc72317f35e324cae9fb5757fb28f`.
Recovered PID `29566` displays **CRASHED / FRAME TIMEOUT** in Home.

- One-shot evidence plus authoritative session manifest/native snapshot:
  `/tmp/u2-persist-timeout-attempt1-20260912`
- Inspected recovery screenshot: `/tmp/u2-persist-timeout-recovery.png`
- Launch acknowledgment: `/tmp/samba-bridge-ePRLN0/logcat.txt`

After recovery and evidence collection, the baseline global configuration was
restored byte-for-byte, SHA-256
`5577258bbc9f73d195ec3ede7f8112e0428f776c3b94641164d4422f81caa4d8`.
This verifies timeout detection, crash classification, and failure-log persistence;
it does **not** fix the Uncharted 2 autosave freeze or establish save/load correctness.

### Home-only PPU preparation gate — September 12, 11:37 IST

The cross-game restart check exposed a PPU regression in Demon's Souls on OP13R:
the direct/debug launch bypassed Home's readiness decision, creating the emulator
loading surface and compiling PPU modules there. Save-load launch callbacks also
entered the activity without the fresh-play preparation check.

`RPCSXActivity.onCreate` now applies the existing shared readiness decision before
creating its loading surface or invoking native boot. Unprepared launches return
to Home and request the existing Kotlin-owned PPU preparation worker. This applies
to fresh, selected-save, and durable-recovery requests. Deferred activity destruction
does not run emulator teardown or cancel the independent Home worker.

Release APK SHA-256
`590978c8ee471051ed644c10202a99dcd48fc04cf35cf4794419f991fb20ea71`
was installed on OP13R only, retaining app data. All 21 selected tests passed,
including a Robolectric test exercising the actual activity entry/destruction for
every boot mode. Runtime verification shows `reason=NeedsPreparation`, followed by
Demon's Souls compiling on its **Home card**, module 93/233, with Stop PPU visible.
Main PID `11861` and independent `:ppu_compile` PID `13116` were confirmed live.
Preparation is still running; gameplay and post-preparation restart are not yet
validated. Uncharted 2's autosave freeze remains unresolved.

- Original regression evidence: `/tmp/demons-ppu-loading-regression-20260912`
- Original loading-screen screenshot: `/tmp/demons-op13r-cold-20260912.png`
- Gate acknowledgment: `/tmp/samba-bridge-P1z6LO/logcat.txt`
- Inspected Home screenshot: `/tmp/demons-ppu-home-gate-20260912.png`

The Home job subsequently completed at 11:48:38 IST: 233/233 cached, runtime audit
successful, zero new runtime PPU objects, worker exited, both readiness labels Ready.
Evidence: `/tmp/demons-home-ppu-completed-20260912` and
`/tmp/demons-ppu-home-complete-20260912.png`.

Follow-up release
`18657201d4e1611ab0b6ed63f22230ebc7933f02cce0b43931ffe21a779deea1`
adds idle-engine fingerprint invalidation at the same activity gate. The native
key now includes Accurate Cache Line Stores and vector-NaN fixups, previously
omitted code-generation settings. Native patch SHA-256:
`c4a520ad5ebd3d621f191e3b64778d55ce8721803ac456306f4b267d70e96fd5`.
Both ABIs built; all 148 selected tests passed. OP13R installation waited until
the Home worker exited. A changed-fingerprint launch correctly returned to Home,
re-audited its cache, and returned to Ready; see the Demon's Souls report for logs.
This is PPU preparation correctness, not a fix for Uncharted 2's RSX/SPU stall.

### Accurate SPU DMA isolation — September 12, 11:19 IST

The first attempted offline configuration change was **not applied**: the already
initialized launcher rewrote `config.yml` from cached settings during boot. Native
configuration still reported Accurate SPU DMA=false and Strict Rendering Mode=true.
That run reproduced RSX kick timeouts and `[SPU-PM] Error: Got too many flags!`;
it is not DMA A/B evidence. Logs were collected once before the canonical stop,
which acknowledged `ok=true` without process teardown failure.

- Invalid configuration attempt: `/tmp/u2-dma-config-not-applied-20260912`
- Launch: `/tmp/samba-bridge-DtCgN3/logcat.txt`
- Stop: `/tmp/samba-bridge-DsWU0O/logcat.txt`

For attempt 2, the stopped app process was explicitly closed before pushing the
single-key DMA test configuration and launching through MainActivity again.
PID `395` now confirms Accurate SPU DMA=true, Accurate XFloat, and Strict Rendering
Mode=false. This changes only DMA from the saved baseline, not from the earlier
strict-rendering experiment. Existing caches and saves were retained. Startup
rendered the Naughty Dog splash at 60 FPS, then stalled at the autosave prompt
without any pad input. At 11:20:42.208 and 11:20:42.240 IST, guest RSX kick
timeouts returned; `[SPU-PM] Error: Got too many flags!` followed at 11:20:42.361.
Last sampled telemetry was `presented=1091` at 11:20:42.796. The later screenshot
shows the autosave prompt with unavailable FPS and RSX CPU usage of 98%.
Accurate SPU DMA alone is therefore not a verified workaround. This does not
exclude other synchronization faults or establish a Vulkan driver root cause.

After one evidence bundle, the canonical stop again acknowledged `ok=true`.
The stopped process was closed before restoring the saved baseline configuration,
preventing cached settings from overwriting it. Byte equality and SHA-256
`5577258bbc9f73d195ec3ede7f8112e0428f776c3b94641164d4422f81caa4d8` were verified.
The app is left stopped; no test setting was promoted into the game profile.

- Test configuration: `/tmp/u2-accurate-dma-test.yml`
- Launch: `/tmp/samba-bridge-xCqtJw/logcat.txt`
- Splash screenshot (filename predates inspection):
  `/tmp/u2-accurate-dma-attempt2-autosave.png`
- Stalled autosave screenshot: `/tmp/u2-accurate-dma-attempt2-prompt.png`
- One-shot evidence: `/tmp/u2-accurate-dma-attempt2-20260912`
- Stop acknowledgment: `/tmp/samba-bridge-6ccFKi/logcat.txt`

### Relaxed ZCULL A/B — evidence attribution incomplete

A reversible device-side A/B enabled only `Video@@Relaxed ZCULL Sync=true` on
top of the genuine Accurate-XFloat test profile. Effective `Accurate` XFloat
and relaxed ZCULL were verified before launch. The run reached 90% SPU-cache
progress at 25 seconds and a black but still-presenting 60 FPS surface at 40
seconds, according to the earlier run notes. The user reported an audio-only
freeze, but its precise run attribution and 120-second duration are not
independently established by the retained evidence.

Earlier notes recorded the following signature; do not treat stale app-managed
logs in the bundle (dated September 10) as proof for this September 12 run:

- repeated `RsxKick: *** Timeout while waiting on RSX SPU kicks. Manual kick
  ***` began at emulated time 0:01:34;
- `[SPU-PM] Error: Got too many flags!` followed at 0:01:37;
- no later RSX presentation recovery was present;
- the one-shot bundle contained no backend fatal/access violation, Vulkan
  error or device loss, frontend/JNI error, crash-buffer entry, or Android
  ANR.

By evidence collection the process was absent and launcher focused. Android
exit-info contained no new crash record attributable to this run, so the final
process-exit mechanism is unclassified. This bundle does not independently
prove either a 120-second no-frame timeout or a native/device-loss crash.

Relaxed ZCULL is not a verified workaround. This evidence does not rule out
query-scope handling or query-pool waits.

After collection the stopped device was restored semantically to LLVM, Safe
SPU block mode, loop detection off, Approximate XFloat, zero Stub PPU Traps,
and relaxed ZCULL off. The rewritten core configuration hashes `5577258b…`;
it no longer matches the pre-run byte checksum because RPCSX persisted
unrelated configuration state during the run.

- 25-second capture: `/tmp/u2-zcull-relaxed-attempt1-25s.png`
- 40-second live-black capture: `/tmp/u2-zcull-relaxed-attempt1-40s.png`
- 60-second capture: `/tmp/u2-zcull-relaxed-attempt1-60s.png`
- One-shot evidence: `/tmp/u2-zcull-relaxed-attempt1-timeout-20260912`

### Strict-rendering A/B — strict query scopes were not exercised

The next reversible run enabled `Video@@Strict Rendering Mode=true` with
Accurate XFloat and Relaxed ZCULL off. Configuration SHA-256 was `1f5a8247…`.
Source inspection subsequently found `g_drv_strict_query_scopes` permanently
false: renderer initialization never assigned it from strict rendering.
Therefore this run did not test the upstream strict-query recommendation.

Presentation remained live long enough to reach `presented=1128` at 7.41 FPS /
141 ms frame time. `[SPU-PM] Error: Got too many flags!` appeared at emulated
time 0:01:08, followed by repeated `RsxKick` manual-kick timeouts at 0:01:12.
No new frame telemetry appeared for more than three minutes. A verified CROSS
pulse at 09:24:44 produced no observed guest progress. Missing telemetry alone
is not proof that the presentation counter stopped advancing.

No `S3FRAMEWATCH` event was found. Without continuous counter evidence this
does not independently prove a watchdog miss. Source contains the 120-second
timeout correction; deployment verification remains pending. The user has
explicitly limited current deployment/testing to OP13R; Poco is not a blocker.

The one-shot bundle again contains no backend fatal/access violation, Vulkan
error or device loss, frontend/JNI error, crash-buffer entry, or Android ANR.
Canonical stop completed successfully, then the exact semantic baseline was
restored and verified as SHA-256 `5577258b…`.

Relaxed ZCULL did not exercise RPCSX's conditional-render emulation: current
`VKHelpers.cpp` enables that path only when Relaxed ZCULL is on *and* the driver
does not advertise conditional-render support. Turnip advertises the extension.
The earlier attribution of forced conditional-render emulation to the upstream
maintainer was incorrect: upstream issue #18828 refers to strict query scopes,
not the unrelated setting that occupied the same line number locally.

- One-shot evidence: `/tmp/u2-strict-rendering-attempt1-timeout-20260912`
- CROSS bridge: `/tmp/samba-bridge-STmkM6/logcat.txt`
- Clean-stop bridge: `/tmp/samba-bridge-ccxtsa/logcat.txt`

### Strict-query source correction

`VKHelpers.cpp` now initializes `g_drv_strict_query_scopes` from
`g_cfg.video.strict_rendering_mode`, matching upstream behavior. Both query
begin/end consumers already exist. The experimental forced conditional-render
emulation was removed; it did not disable the native conditional-render path.
The corrected one-line hunk is persisted in `patches/rpcsx-submodule-changes.patch`.

The superseded experiment built a core with patch prefix `863e4627`, but was
not deployed. The corrected release and strict-rendering=true run are documented
above. No successful gameplay, stop/reopen, or save/load claim follows from this fix.

| Field | Value |
|---|---|
| Title ID | `BCUS98123` |
| Device | OnePlus 13R (`CPH2691`), Snapdragon 8 Gen 3 / Adreno 750 |
| Test date | 2026-09-12 |
| Release APK | SHA-256 `18657201d4e1611ab0b6ed63f22230ebc7933f02cce0b43931ffe21a779deea1` |
| Core | `rpcsx=657b26a0`, patch `585b9ddf`, `RelWithDebInfo` |
| Renderer | Turnip 26.2.99, `Turnip Adreno (TM) 750` |
| Current result | Not fixed: the best prior run reached the opening train scene with white/overbright corruption; the current release/profile reaches the autosave prompt and then stops presenting frames |

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

## 2026-09-11 Shader-cache hardening and runtime refinements

### Shader corruption on reopen / save management
Across games, relaunching after gameplay or saving/loading savestates suffered
from random native crashes and pipeline compilation aborts. The current patch
hardens four concrete lifetime/concurrency hazards, but none is yet claimed as
the proven all-games root cause:

1. **`ProgramStateCache.h` publication race:** the old path inserted a default
   shader entry and released the upgrade lock before compilation. A concurrent
   lookup could therefore observe the placeholder. The patch retains the
   writer lock through compilation and double-checks after lock upgrade.
2. **`graphics_pipeline_state.hpp` pointer rebasing:** copied attachment state
   now rebases `pAttachments` whenever attachments are present; raw cache loads
   also explicitly point at the destination's inline attachment array.
3. **`rsx_cache.h` concurrent collection:** this was not a fixed 100-shader
   capacity overflow—the old `lf_fifo` storage grows. The actual risk was
   multi-worker, out-of-order indexed insertion while its backing blocks were
   growing. A reserved `std::vector` with a mutex now serializes collection,
   and fragment-program bytes are owned directly by `local_storage`.
4. **`VKGSRender.cpp` active-writer drain:** queue-empty alone did not prove the
   storage worker had finished its current file write. `wait_stores()` now
   waits for both an empty queue and no active writer before shader-cache
   destruction and `m_prog_buffer` clearing.

### Uncharted 2 rendering and watchdog refinements
1. **SPU XFloat Accuracy (`Core@@XFloat Accuracy=Accurate`):** Added as a
   title-scoped compatibility hypothesis for `BCUS98123`; it still needs a
   valid on-device A/B before it can be credited with fixing the overbright
   artifacts.
2. **Watchdog compile awareness:** `startNoFrameWatchdog` pauses while compilation is active (`CompileProgressBridge.state.value.isActive`), then enforces the product requirement of exactly 120 seconds without a presented frame. An experimental CPU-busy extension was removed after it masked a real black-screen stall.
3. **PPU UI bridge handover:** Updated `CompileProgressBridge` to retain progress metrics on completion, and `RPCSXActivity` now transitions the overlay cleanly to "Starting game… Waiting for game output" (100%) rather than freezing at 99%.

### 23:29 release validation

The updated APK (SHA-256
`18e13a1e72804df721e0c02308b4770d2460f76d5e7ba8161b8154fde8499b48`)
and native core patch `2dc094ae` were installed on the OnePlus 13R. The core
confirmed `BCUS98123`, linked all 111 cached PPU modules successfully, and
initialized Turnip, but the run regressed to black output and stopped
presenting at frame 3,244. It remained in `Running` with CPU activity for more
than 13 minutes. The installed experimental watchdog incorrectly allowed
CPU-busy no-frame stalls up to ten minutes (and had already missed the required
120-second recovery window); that extension has been removed from source and
the invariant is restored to an unconditional 120 seconds after compilation is
inactive.

Preflight after this run found that the previous dynamic-interpreter test had
leaked `SPU Decoder: Interpreter (dynamic)` into the global configuration. The
23:29 launch therefore does not validate the intended LLVM plus Accurate
XFloat combination. It remains valid evidence for the black stall, watchdog
regression, settings-lease leak, and stop-path failure. The global baseline was
repaired to LLVM, automatic compile threads, Safe block mode, loop detection
off, Approximate XFloat, and zero Stub PPU Traps before further testing.

The canonical stop request at 23:44:20 entered `STOPPING` and invoked native
`Kill()`. About 240 ms later the process exited with signal 11 at roughly 1.6
GB RSS, and the launcher recovered it as an interrupted session. This release
therefore does not yet prove playable Uncharted 2 or clean native teardown.

- Long black-stall screenshot: `/tmp/current-session-audit-20260911-2343.png`
- Pre-stop stall bundle:
  `/tmp/uncharted2-busy-watchdog-regression-20260911-2343`
- Stop bridge: `/tmp/samba-bridge-5Fpis1/logcat.txt`
- Stop-failure bundle: `/tmp/uncharted2-current-stop-hang-20260911-2347`

### 00:10 September 12 profile attempt 1

A settings A/B used LLVM/Safe plus Accurate RSX reservations, Atomic RSX FIFO,
Write Color Buffers, Read Depth Buffer, and asynchronous texture streaming.
It presented at 30 FPS at 00:10:46 and reached a visually clean autosave notice
by 00:11:23, without the earlier white/green full-screen corruption. By that
autosave capture, however, both FPS and frame-time metrics already reported no
sample while audio and roughly 300% app CPU continued. The frame remained
unchanged through 00:15:53. Two acknowledged 120 ms CROSS pulses and one 500 ms
raw CROSS hold could not advance a guest that was no longer presenting.

The decisive configuration audit found that this was **not** the intended
Accurate-XFloat test. `Core@@XFloat Accuracy` was supplied as bare `Accurate`,
but the native setter requires a JSON string literal and rejected it as
`invalid json 'Accurate'`. The effective engine config confirms `Approximate`;
Stub PPU Traps was correctly `1` and all other A/B settings were active. Source
now JSON-quotes enum compatibility values and refuses/rolls back a boot when
any required title setting fails, instead of silently accepting a partial
profile. Focused settings, watchdog, and crash-classification tests pass.

The canonical stop completed normally in 696 ms and returned to MainActivity;
there was no signal-11 exit. The exact pre-test global config was restored and
verified byte-for-byte (`c25806aa...`).

- Live 30 FPS startup: `/tmp/u2-profile-attempt1-25s.png`
- Frozen autosave notice: `/tmp/u2-profile-attempt1-50s.png`
- Final no-frame confirmation: `/tmp/u2-profile-attempt1-frozen.png`
- One-shot evidence: `/tmp/u2-profile-attempt1-input-gate-20260912`
- CROSS bridges: `/tmp/samba-bridge-iP7gBP/logcat.txt`,
  `/tmp/samba-bridge-9hyOym/logcat.txt`,
  `/tmp/samba-bridge-5w7dIy/logcat.txt`,
  `/tmp/samba-bridge-t3LmXo/logcat.txt`
- Clean stop bridge: `/tmp/samba-bridge-i0R1YJ/logcat.txt`

### 00:22 September 12 profile attempt 2 — genuine Accurate XFloat

The second and final launch for this profile set `XFloat Accuracy: Accurate`
directly in the offline global config before boot, making the old APK's broken
enum write harmless for this A/B. The engine's own `Used configuration` block
proved the complete effective set: LLVM/Safe, Accurate XFloat, Accurate RSX
reservations, Atomic FIFO, Stub PPU Traps 1, WCB, RDB, and asynchronous texture
streaming. Turnip initialized successfully. Accurate XFloat caused a distinct
7,190-module SPU-cache rebuild; the Kotlin overlay progressed to 82%/5 seconds
remaining at the 25-second sample and completed normally.

At 45 seconds the clean autosave prompt was still actively presenting at about
46.5 FPS. One deterministic CROSS was delivered and visibly brightened the
prompt, proving that the virtual pad path reached the game. Within ten seconds,
FPS and frame-time telemetry became unavailable and the image stopped changing
while CPU remained around 300%. It was still identical after 60 and 120 seconds.
The final core transition was an SPU-side RSX cache miss followed by repeated
`RsxKick: Timeout while waiting on RSX SPU kicks. Manual kick` messages and
`[SPU-PM] Error: Got too many flags`. Guest syscalls and audio activity
continued, but RSX never presented another frame.

The one-shot failure bundle contains no backend fatal, Vulkan/device-lost
error, JNI error, crash-buffer entry, tombstone, or new OS exit. The old APK's
known CPU-busy watchdog extension missed the 120-second product limit; corrected
source has already removed that extension and uses exactly 120 seconds. The
canonical stop completed normally in about 700 ms. The exact baseline config
was again restored and verified with matching SHA-256 `c25806aa...`.

- Accurate profile/checksum: `/tmp/u2-profile-config-accurate-attempt2-20260912.yml`
- SPU-cache overlay: `/tmp/u2-profile-accurate-attempt2-25s.png`
- Live clean autosave: `/tmp/u2-profile-accurate-attempt2-45s.png`
- Post-CROSS freeze: `/tmp/u2-profile-accurate-attempt2-post-cross-10s.png`
- 60-second freeze: `/tmp/u2-profile-accurate-attempt2-post-cross-60s.png`
- 120-second freeze: `/tmp/u2-profile-accurate-attempt2-timeout.png`
- One-shot evidence: `/tmp/u2-profile-accurate-attempt2-timeout-20260912`
- CROSS bridge: `/tmp/samba-bridge-6ozdgA/logcat.txt`
- Clean stop bridge: `/tmp/samba-bridge-tJvYbx/logcat.txt`

Status: **Uncharted 2 is not fixed or playable. Shader-cache hardening and the
PPU UI handover are present, but the current build still freezes at or before
the post-autosave transition. Accurate XFloat improves the prompt-stage frame
progress but does not prevent the CROSS-triggered RSX/SPU stall. Source again
enforces exactly 120 seconds without a presented frame and now fixes the
discovered title-setting enum serializer. Clean stop/reopen shader validation
and a fix for the RSX-kick/SPU transition remain required.**

### 2026-09-12 watchdog-unblock + RSX null-guard validation (APK `7b12382a…`, patch `7f314ae…`)

Source changes validated on the OnePlus 13R in two back-to-back launches:

1. **PPU watchdog unblocks frame recovery:** `clearStuckPpuUiIfComplete`
   now clears `ppuActive` (outcome stays `NONE`, metrics/job preserved,
   late terminals ignored) instead of holding `ppuActive=true` at 99%
   "Verifying". A stuck 100%-without-terminal PPU can no longer pause
   `startNoFrameWatchdog` forever. Focused
   `CompileProgressBridgeTest`/`CompileWatchdogLogicTest` pass.
2. **RSX null-sampler guards:** `get_current_vertex_program` and
   `get_current_fragment_program` skip null sampler descriptors instead of
   dereferencing them (SIGSEGV hardening on Adreno/Turnip).
3. **Shader-cache write/read hardening:** `store()` skips zero-length FP /
   empty VP entries (the `ensure(ucode_length)` abort path that poisoned
   reopen loads); `graphics_pipeline_state` copy/assign rebases
   `pAttachments` on `attachmentCount > 0` (null otherwise) so stale disk
   pointers can never survive; `unpack()` enforces the same invariant
   explicitly.

Device results:

- Run 1 (cold): SPU cache 355/7207 → autosave prompt, presented 1,055
  frames, then deterministic stall. Guest `main_thread` prints
  `RsxKick: *** Timeout while waiting…` via `sys_tty_write` at emu-time
  0:01:03; PPU syscall profile goes idle (`sys_timer_usleep` dominant,
  ~40% CPU, no RSX progress). `S3FRAMEWATCH` fired at exactly
  `no-produced-frame-for=120249ms presented=1055 state=Running` and the
  frame-timeout path did a clean-process recovery with **no SIGSEGV /
  no tombstone** — the six-SPU-thread teardown crash did not reproduce.
- Run 2 (cache-reuse, no data wipe): booted cleanly to SPU build
  248/7209 → 78% (5,672/7,209, 6s ETA) with monotonic Kotlin progress —
  **no reopen crash**, confirming the store/unpack hardening. The stall
  itself is unchanged and remains an SPU/RSX kick-accuracy issue (guest
  waits for SPU kicks that never complete on ARM LLVM), not a shader
  corruption or PPU-bridge leak.

Remaining: upstream ARM SPU/RSX kick progress for Uncharted 2 past the
autosave handoff; save/load 0-FPS hangs now recover via the 120-second
frame timeout instead of wedging forever.

### 2026-09-12 run 3 kick analysis + SPURS 4 A/B (ruled out)

Run 3 (warm cache, SPURS 6) reached the autosave prompt in ~60s at live
FPS, then ignored 4 verified CROSS presses. The session log shows
`cellSaveDataListAutoLoad … funcStat returned result=1` at 4:54:21,
followed from 4:54:33 by an unbroken series of guest
`RsxKick: *** Timeout while waiting on RSX SPU kicks. Manual kick ***`
messages with only `main_thread` `sys_rsx_attribute`/`sys_memory` polling
afterwards — no further SPU block compiles and no RSX presentation. The
game waits for SPU→RSX kicks that never arrive; manual kicks do not
restore presentation. Exit was again clean frame-timeout recovery
(SIGKILL, no SIGSEGV).

A reversible device-side A/B then set only `Core@@Max SPURS Threads=4`
(offline `config.yml`, SHA `0e99896e…`, restored byte-for-byte after).
It reached the same autosave prompt in 75s but at only 4.9 FPS / 379 ms
frame time (RSX 86%) versus ~28 FPS with 6 SPURS threads — strictly
worse on this 8-core SoC, so the cap is ruled out and was reverted.
Hypothesis for the next loop: SPU→RSX kick *data* delivery (SPU DMA /
MFC accuracy) rather than thread count, since kicks are sent but never
observed by RSX.

### 2026-09-12 ARM64 LLVM instruction-cache publication experiment

Ported the architecture-neutral part of
`hamzaq2000/rpcs3@983c69d5ebccf45b239e68ed7433c42d94c2d6d4`:
`MemoryManager1` and `MemoryManager2` now retain emitted code ranges and
call `asmjit::VirtMem::flushInstructionCache` only after RuntimeDyld applies
relocations. Apple-only VM and guest-CPU coordination changes were excluded.

The arm64 `RelWithDebInfo` core built successfully. Both standard and
Play Store debug unit-test tasks passed. The generated submodule patch passes
reverse-apply validation.

Device validation is pending: only the OnePlus 13R (`d30a1726`) is connected.
Project policy requires every new release APK to be installed on both the
OnePlus 13R and Poco X6 Pro, so no release APK was built or installed.
