# Recovery Phases 1, 2, and 3: Re-Review and Updated Plan (2026-09-25)

## 1. Scope, Verdict, and Executive Architecture

This document presents the consolidated re-review and updated plan for **Phase 1 (Baseline & Provenance — CR00)**, **Phase 2 (Metrics Qualification — CR03)**, and **Phase 3 (Runner & Crash Classification — CR01)** of the God of War III Gaia Crash Recovery project on the OnePlus 13R (Snapdragon 8 Gen 3, serial `d30a1726`).

### Executive Verdict: PARTIAL (Not Overall Pass)

**2026-09-25 follow-up:** A fresh, read-only preflight now has a host-archived command/output/manifest at `docs/benchmarks/evidence-cr01-dry-20260925/`. This is a dry-run receipt, not a gameplay qualification or a runner-generated manifest. Earlier missing artifact `evidence-gow3-bench-20260925-094902` remains missing.

- **CR01 (Runner False-Pass Hardening)**: **PASS for synthetic gate; Live-run qualification OPEN.** CR01’s synthetic crash-plus-cleanup gate passes (`WORKER.md:215` via `test_crash_then_cleanup_fixture`). **69 focused unit tests passed** across the test harness. Live Gaia-route qualification remains open.
- **CR00 (Baseline & Provenance)**: **PARTIAL.** Current installed binary hashes (`base.apk`, `librpcsx-android.so`, `libsambas3-android.so`, `vulkan.adreno.so`) and **10 archived config/save hashes match**. However, **runtime library mapping and native effective settings remain unverified**; furthermore, **the claimed dry-run artifact was not found** on disk.
- **CR03 (Frame & Thermal Qualification)**: **PARTIAL.** Phase 10 throughput reconciles arithmetically to **12.728 FPS** ($718\text{ increments} / 56.411\text{ s}$), but its **46 samples cannot prove full-frame latency tails**. The analyzer flags are **`--start-us` and `--end-us`**; the review uses those exact names. Live scene qualification remains open.
- **CR02 (Route Progression)**: **OPEN / NOT_STARTED.** Live Gaia-route qualification (G0–G7 checkpoint progression past the first fight into the shoulder fire and tree lifting route) is explicitly a **separate next ticket**, not a reason to fail CR01’s unit-level acceptance.

---

## 2. Summary Status and Verification Matrix

| Ticket / Phase | Check ID | Target / Scope | Expected Evidence | Evaluated Outcome | Notes & Constraints |
|---|---|---|---|---|---|
| **CR00** (Phase 1) | **CR00-1** | Git repo & device fingerprint | Commits `1edbebd7ce` (frontend), `141af96fa0` (core); fingerprint matches OnePlus 13R (`d30a1726`) | **PASS** | Matches `manifest.json:2-10` and `device_provenance_commands.txt:1-120`. |
| **CR00** (Phase 1) | **CR00-2** | Installed vs. runtime library hashes | SHA-256 for `base.apk`, `librpcsx`, `libsambas3`, and `vulkan.adreno.so`; runtime dynamic memory mapping | **PARTIAL** | Installed file hashes match 100%. Live `/proc/<pid>/maps` mapping **unverified** (sandbox blocked unprivileged read). |
| **CR00** (Phase 1) | **CR00-3** | Effective config & patch receipts | Native effective settings matching Fast Mode (Mega SPU, 2 compile threads, patches enabled) | **PARTIAL / UNVERIFIED** | Static YAML has Safe/0 threads; Fast Mode overrides at runtime without native engine receipt. Driver UUID/extensions remain `unknown`. |
| **CR00** (Phase 1) | **CR00-4** | Save data integrity & recoverability | 10/10 SHA-256 match across configs and saves; non-destructive copy verified; 0 savestates | **PASS** | 10/10 local and device hashes match; non-destructive pull verified; 0 savestates on device. |
| **CR00** (Phase 1) | **CR00-5** | Historical crash baseline | `dumpsys activity exit-info` showing SIGSEGV for `pid=28772` | **PASS (Baseline) / SCOPED (Unrouted)** | Real native crash confirmed on device; unlinked to G2–G7 transition. |
| **CR00** (Phase 1) | **CR00-6** | Pre-flight dry-run verification | Persistent artifact directory, run manifest, and logged preflight confirmation | **PASS (new host-archived preflight only)** | `evidence-cr01-dry-20260925/{command-output.txt,run-manifest.json}`; exit 0. Original `094902` claim remains unsubstantiated; runner does not create dry-run artifacts. |
| **CR01** (Phase 3) | **CR01-1** | Synthetic crash-plus-cleanup gate | Nonzero exit (exit 4), `first_failure = FAIL_CRASH`, cleanup succeeds without clearing crash, atomic manifest | **PASS (Unit Acceptance Gate)** | `test_crash_then_cleanup_fixture` demonstrably passes; satisfies `WORKER.md:215`. |
| **CR01** (Phase 3) | **CR01-2** | Focused regression test suite | Executable validation of runner fail-closed logic, classifier rules, and frame parser | **PASS (69/69 Tests Pass)** | 26 runner tests + 21 classifier tests + 22 frame analyzer tests = 69 focused tests OK. |
| **CR01** (Phase 3) | **CR01-3** | Live runner qualification | Verified live session on OnePlus 13R with run-bound manifest and streamed logcat | **OPEN / DEFERRED** | Live run not executed; decoupled from unit gate. |
| **CR02** (Transition) | **CR02-1** | Progression marker contract | G0–G7 route markers and `debug-pad.sh` input protocol | **PASS (Contract Defined)** | Contract specified in `WORKER.md:217-258`. |
| **CR02** (Transition) | **CR02-2** | Live G0–G7 route reproduction | First-transition failure packet captured between first fight and tree lift | **OPEN / NOT_STARTED** | Designated separate next ticket; not a reason to fail CR01 unit acceptance. |
| **CR03** (Phase 2) | **CR03-1** | Telemetry vs. throughput reconciliation | Reconcile 46 sampled intervals vs. 718 increments = 12.728 FPS; classify sparse streams | **PARTIAL (Reconciled; Tails Unproven)** | Arithmetic reconciles to 12.728 FPS; sparse streams set `full_frame_percentiles_available = False`; 46 samples cannot prove full-frame latency tails. |
| **CR03** (Phase 2) | **CR03-2** | Scene window boundaries (`--start-us` / `--end-us`) | Strict filtering via `--start-us` and `--end-us`; empty bounded window exits code 2 | **PASS (Harness) / OPEN (Live)** | CLI flags `--start-us` and `--end-us` verified in harness; live marker feeding awaits CR02. |
| **CR03** (Phase 2) | **CR03-3** | Deduplication, loss & stalls | Deduplication within 800µs; sequence loss detected; stalls $\ge 1.0\text{ s}$ retained | **PASS** | Unit tests verify loss tracking and stall retention without false pause dropping. |
| **CR03** (Phase 2) | **CR03-4** | Thermal reading reconciliation | Parse static dumpsys snapshots vs. continuous transition events; resolve Thermal Status 3 | **PASS (Parser) / OPEN (Live Trace)** | Parser distinguishes static dump vs. transition events; live continuous trace awaits device run. |

---

## 3. Detailed Audit: CR00 — Baseline Preservation, Binaries & Recoverability

### Current Verification Status: PARTIAL

#### 1. Installed Binary Hashes Match (PASS)
Direct ADB inspection on the connected OnePlus 13R (`d30a1726`) confirms the installed package and system driver match the archived baseline:
- `base.apk`: `5a35d5dcd69404eeb945817c1094bc6196c0310532954b5bd68017e6ba3054d9`
- `lib/arm64/librpcsx-android.so`: `e93f1faa806914f24f5c22e346dd4174e66b63c07ed0242a9d18c3dcd7fcc855` (Build ID: `802f78937de934c82a6b16891a6934fe52d96ea7`)
- `lib/arm64/libsambas3-android.so`: `149251f8576683b86cf2f7a1ffea05e5805c885af79bff6ebb84a4f382c46ad2` (Build ID: `266450d34dbe0b3db637d54dc58f636e445f7807`)
- `/vendor/lib64/hw/vulkan.adreno.so`: `979ac22c0d46c26804d559a93ab6c93c66272f224d3a5d0908f94a51247154b0` (Driver version `0762.41`)

#### 2. Ten Archived Config and Save Hashes Match (PASS)
Recomputation on local disk and live readback from `/sdcard/Android/data/com.zenithblue.sambas3/files/` confirms 10/10 hashes match `manifest.json`:
- `config.yml`: `824cfe3696bd67ae3e873afa05a6c979a138607156cef888e23ae63827e4d855`
- `patch_config.yml`: `a0f0a4512d221e3a2d653dd9d0b9a443280b2383367b35c04d461942bd7ddfd8`
- `games.json`: `3950906045318ff0e983c33d0f18b56207ced963de482db8d9b67e6f4e49ba30`
- `ppu_state.json`: `c2343393595c83fa086401037fbe043f7268af6e0f760633ddcfa1a35ecd1030`
- `savedata/BCUS98111-AUTOSAVE/ICON0.PNG`: `dfea1cdcfd8796e56dbf41c47a9d3677eb20757ed2715feab863d1eb942079aa`
- `savedata/BCUS98111-AUTOSAVE/PARAM.SFO`: `106ec5511c4a70fad1e4378e74141e458b9d884417bf913c037dde61e4e8953e`
- `savedata/BCUS98111-AUTOSAVE/SAVEDATA`: `604695e19c27b9e743b76347ca970f62d3ddc8f769222501933ab3b4fd477a25`
- `savedata/BCUS98111-USERDATA/ICON0.PNG`: `dfea1cdcfd8796e56dbf41c47a9d3677eb20757ed2715feab863d1eb942079aa`
- `savedata/BCUS98111-USERDATA/PARAM.SFO`: `ca6cecdc3e4fc2501df67e06c2bc2de639ae1badb15dd74185ec15aacb712d16`
- `savedata/BCUS98111-USERDATA/SAVEDATA`: `ed4d022a40dee571fb52a86cf844eb271df159ce9eadee89c1204e91e733f2f2`
- Device savestates verified empty (0 savestates). Non-destructive copy pull verified without modifying device originals.

#### 3. Runtime Library Mapping Remains Unverified (UNVERIFIED)
- `adb shell cat /proc/<pid>/maps` is blocked by Android security (`Permission denied`).
- `adb shell run-as com.zenithblue.sambas3 cat /proc/<pid>/maps` failed because the release APK is non-debuggable (`run-as: package not debuggable`).
- While `app/src/main/cpp/native-lib.cpp:openLibrary()` specifies `dlopen("/data/app/.../lib/arm64/librpcsx-android.so")` and `dumpsys meminfo` shows `.so mmap` PSS = 42,559 KB, the actual in-process memory mapping has not been directly captured.

#### 4. Native Effective Settings Remain Unverified (UNVERIFIED)
- The preserved `config.yml` records compile threads `0` and SPU block mode `Safe`.
- Fast Mode dynamically overlays `2` compile threads and `Mega` block size (`PatchFastMode.kt:106-110,124-140`).
- Without an in-process native settings receipt or live memory readback, the effective settings active inside the C++ engine remain unverified.
- Driver pipeline cache UUID, Mesa commit, and enabled extensions remain `unknown` (`manifest.json:19-21`).

#### 5. Claimed Dry-Run Artifact Was Not Found (UNSUBSTANTIATED)
- `scripts/perf/run-gow3-benchmark.py:547-549` exits immediately upon completing environment checks when `--dry-run` is passed.
- No evidence directory (`docs/benchmarks/evidence-gow3-bench-20260925-094902`) or `run-manifest.json` was created or persisted on disk.
- Any status ledger claim asserting a persisted dry-run artifact is inaccurate and is hereby retracted.

#### 6. Read-only follow-up and replacement dry-run receipt (2026-09-25)
- Connected device `d30a1726` had app PID `579` in `MainActivity` history; launcher was foreground, not `RPCSXActivity`. No game was launched. `cat /proc/579/stat` succeeded; `cat /proc/579/maps` and `smaps_rollup` returned `Permission denied`. `run-as com.zenithblue.sambas3 cat /proc/579/maps` returned `package not debuggable`; `su` was absent (`inaccessible or not found`). An idle launcher PID cannot establish emulator-core mapping even if maps were readable. Packaged SHA checks and archived JNI loader path are indirect evidence only; actual runtime-loaded path/build ID remains **UNVERIFIED**.
- Read-only recent logcat search found no `dlopen`/native settings receipt for this process. Archived Phase 10 `evidence-e07-g10-fast-mode-profile/cache-RPCSX.log` contains `SYS: Used configuration` with `Max LLVM Compile Threads: 2`, `Thread Scheduler Mode: RPCS3 Scheduler`, and `SPU Block Size: Mega`: historical core configuration evidence, **not** a current PID-native receipt or proof of patch application. Current native effective settings remain **UNVERIFIED**. A new diagnostic core receipt or permitted in-process inspection requires a separately qualified run; no debug APK installed.
- Fresh command `python3 scripts/perf/run-gow3-benchmark.py --serial d30a1726 --dry-run --run-id cr01-dry-20260925 --outdir docs/benchmarks/evidence-cr01-dry-20260925` exited `0`. Exact observed output and a **host-authored**, explicitly labeled manifest are archived in that directory. Runner `--dry-run` itself writes neither file. No scheduler option, game launch, user-data edit, or cleanup occurred.

---

## 4. Detailed Audit: CR01 — Runner False-Pass & Crash Classification

### Current Verification Status: PASS for Synthetic Gate; Live-Run Qualification OPEN

#### 1. Synthetic Crash-Plus-Cleanup Gate Passes (`WORKER.md:215`)
The core acceptance criterion of `WORKER.md:215` requires that an executable crash followed by successful cleanup MUST fail closed:
- Fixture `TestBenchmarkRunnerExecutionFixtures.test_crash_then_cleanup_fixture` simulates a SIGSEGV during the gameplay loop, followed by successful execution of `debug-stop-game.sh`.
- **Observed Behavior**:
  1. Runner returns exit code `4` (`FAIL_CRASH`).
  2. Generated `run-manifest.json` latches `first_failure = "FAIL_CRASH"`, `verdict = "FAIL_CRASH"`, and `stop_reason = "FAIL_CRASH"`.
  3. Cleanup success is recorded separately as `cleanup = {"clean_stop": True, "result": "SUCCESS"}` and does not erase or override `first_failure`.
- The Python syntax warning (`SyntaxWarning: 'return' in a 'finally' block`) in `run-gow3-benchmark.py` was corrected by moving return statements outside the `try/finally` block.

#### 2. Sixty-Nine (69) Focused Tests Passed
All 69 focused tests across the qualification test harness pass cleanly in `3.2s`:
- **`scripts/tests/test_run_gow3_benchmark.py` (26 tests OK)**:
  - Crash-plus-cleanup fail-closed gate (exit code 4).
  - PID replacement detection (`FAIL_REPLACEMENT_PID`, exit code 4).
  - Pre-clear logcat crash buffer preservation to `logcat-prior-crash-buffer.log`.
  - Zero qualified frames failure (`FAIL_GUEST_PROGRESS`, exit code 1).
  - Missing evidence fail-closed (`INCONCLUSIVE_EVIDENCE`, exit code 2).
  - Launch failure fail-closed (`FAIL_LAUNCH`, exit code 3).
  - Strict provenance mismatch rejection (`FAIL_PROVENANCE`, exit code 3).
  - Clean run pass fixture (exit code 0, only when all postconditions hold).
- **`scripts/tests/test_classify_crash.py` (21 tests OK)**:
  - Exact package matching rejecting unrelated processes.
  - Native crash retention over `DEBUG_STOP_GAME` cleanup.
  - Java exception retention over cleanup.
  - Nonzero `EXIT_SELF` classified as abnormal termination.
  - Rejection of recycled PIDs before session launch window.
- **`scripts/tests/test_analyze_frame_events.py` (22 tests OK)**:
  - Deduplication within 800µs and sequence loss tracking.
  - Window filtering using `--start-us` and `--end-us`.
  - Retention of stalls $\ge 1.0\text{ s}$ (stalls are not dropped as pauses).
  - Telemetry sampling streams classified as `full_frame_percentiles_available = False`.
  - Parsing static dumpsys snapshots vs. continuous `THERMAL_STATUS_*` transition events.

#### 3. Live-Run Qualification Remains Open
- No live on-device game run was executed.
- Live qualification remains **OPEN**; per `WORKER.md:215`, it is **not required** to pass CR01's unit-level gate.

---

## 5. Detailed Audit: CR03 — Frame and Thermal Qualification

### Current Verification Status: PARTIAL (Reconciled; Tails Unproven)

#### 1. Telemetry Stream vs. Counter Throughput Reconciliation
- Historical Phase 10 artifacts record:
  - 46 parsed telemetry samples (`crosscheck` logcat lines).
  - 718 cumulative presented frame counter increments over 56.411 seconds.
  - Arithmetic throughput: $718 / 56.411\text{ s} = 12.728014\text{ FPS}$.
- **Core Finding**: While the 12.728 FPS throughput calculation is mathematically reconciled from the counter delta, **the 46 sampled intervals cannot prove full-frame latency tails** (P95, P99, or total stall count across all 718 frames).
- `scripts/perf/analyze-frame-events.py:520-569` enforces this rule: when telemetry crosscheck samples are parsed, it sets:
  ```python
  result.full_frame_percentiles_available = False
  ```
  Full-frame percentile fields remain unpopulated, and sampled statistics are explicitly isolated into `sampled_interval_p50_ms`, `sampled_interval_p90_ms`, `sampled_interval_p95_ms`, and `sampled_interval_p99_ms`.

#### 2. Analyzer Window Flags Are `--start-us` and `--end-us`
- The review previously referenced `--window-start-us` and `--window-end-us`.
- The actual CLI flags in `scripts/perf/analyze-frame-events.py:830-845` are:
  - `--start-us`: Start timestamp in microseconds.
  - `--end-us`: End timestamp in microseconds.
- `scripts/perf/run-gow3-benchmark.py:744-745` invokes the analyzer using these exact flags:
  ```python
  analyze_cmd = [
      sys.executable,
      str(PERF_DIR / "analyze-frame-events.py"),
      str(log_for_analysis),
      "--json",
      "--output-json", str(analysis_json_path),
      "--start-us", str(qual_start_us),
      "--end-us", str(qual_end_us),
  ]
  ```
- Filtering by `--start-us` and `--end-us` is verified in unit test `test_explicit_scene_window_bounds`. Empty bounded windows fail closed with exit code 2 (`INCONCLUSIVE_EVIDENCE`).

#### 3. Thermal Readings
- Historical Phase 10 reported "unthrottled" while dumpsys recorded Thermal Status 3 (`THERMAL_STATUS_SEVERE`).
- `analyze-frame-events.py` now parses continuous `THERMAL_STATUS_*` transition events, surfacing severe/critical throttling rather than masking it.
- Live continuous thermal and CPU/GPU frequency tracing remains open for the live device session.

#### 4. Raw Phase 10 reconciliation (historical, not scene-qualified)
- `evidence-e07-g10-fast-mode-profile/logcat-process.log` contains **46** `crosscheck source=surface` samples: `presented=1892` at `09-25 12:36:17.598` to `presented=2610` at `12:37:14.009` (same date; delta **718**, duration **56.411 s**). `frame-analysis.json` reports `total_events_parsed=46`, `qualified_frames=718`, `interval_throughput_fps=12.72801403981493`; the latter is endpoint counter throughput, not 718 independently timed intervals. The old `frametime_p95_ms=203.99075` and `stalls_over_100ms=17` are statistics of 46 sampled intervals only; full-frame P95/P99 and stall totals **UNAVAILABLE**.
- `evidence-e07-g10-fast-mode-profile/thermal.txt` has `Thermal Status: 3` (severe), a static snapshot. No `THERMAL_STATUS_*` transitions appear in that run's `logcat-process.log`; onset, duration, and impact are **UNKNOWN**. Battery temperature 35°C cannot establish unthrottled execution. Phase 10's asserted clean/crash-free Gaia route and gameplay-window qualification remain **UNPROVEN** without CR02 markers and session-correlated exits.

---

## 6. Role and Status of Ticket CR02: Checkpoint Progression

### Current Status: OPEN / NOT_STARTED (Separate Next Ticket)

- **CR02 Scope**: Replace timed sleeps and periodic button presses with observed progression across markers G0 through G7:
  - `G0_BOOT`: Valid game image and launch identity established.
  - `G1_FIGHT_START`: Kratos controllable in first combat arena.
  - `G2_FIGHT_COMPLETE`: All introductory enemies dispatched.
  - `G3_TRANSITION_ENTER`: Movement toward the shoulder/fire path begins.
  - `G4_FIRE_SEQUENCE`: Gaia extinguishes shoulder fire sequence.
  - `G5_TREE_INTERACTION`: Tree obstacle reached; lifting prompt engaged.
  - `G6_TREE_PASSED`: Obstacle cleared; forward gameplay resumes.
  - `G7_BEYOND_TRANSITION`: Continued stable gameplay.
- Input delivery is specified via `scripts/debug-pad.sh` and confirmed with `DebugPad` logcat acknowledgements.
- **Decoupling Rule**: Gaia route progression is a **separate next ticket**; lack of live G2–G7 scene evidence is **not** a reason to fail CR01's synthetic unit-level acceptance.

---

## 7. Re-Review Summary Conclusion

1. **Overall Verdict**: **PARTIAL** (not overall pass).
2. **CR01**: **PASS** for the unit-level synthetic crash-plus-cleanup gate (`WORKER.md:215`). All **69 focused tests pass**. Live-run qualification remains **OPEN**.
3. **CR00**: **PARTIAL**. Current installed binary hashes and **10 archived config/save hashes match**. Runtime library mapping and current native effective settings remain **UNVERIFIED**. Original dry-run artifact was **NOT FOUND**; replacement preflight output and host manifest are archived.
4. **CR03**: **PARTIAL**. Phase 10 throughput reconciles arithmetically to **12.728 FPS**, but its **46 samples cannot prove full-frame latency tails**. Analyzer flags are correctly documented as **`--start-us` and `--end-us`**.
5. **Next Step**: Ticket **CR02** (live on-device checkpoint progression and transition failure capture on the OnePlus 13R).
