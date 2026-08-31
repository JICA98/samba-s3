# Runtime Feature Repair Validation

Validation date: 2026-08-31 (Asia/Kolkata)

This report records the runtime repair work for Home/Launch Center savestate loading, RPCSX performance monitoring, FPS/frame-time graphs, achievements, touch input, and controller lifecycle. Device runtime evidence is stored in [the dated artifact folder](./artifacts/2026-08-31-runtime-repair/).

## Build provenance

- Root commit: `6392c3522cae3634ab4833c6e7d33b167bdb6e22`
- RPCSX submodule: `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`
- Patch SHA-256: `4c5edbe84bc8aaa9bc14b984d2be584dc359caf012dc6e404c9f2ecaff1e742a`
- Standard debug APK SHA-256: `82dd2588f7c630ad72e9acec92f257531b2a7787a94d96b031ad8abc2e7dcabf`
- arm64 core SHA-256: `03a3dbe78a0803648a46c3023b33ac9301b02c0d6d0a9e1e4779e1631111e22e`
- x86_64 core SHA-256: `3db863c3826ad7b01f13f5043a68c5a82cd0a77f9c7ce571c16b322678573c80`
- Build command: `./build_rpcsx.sh debug` followed by both standard and playstore debug assemblies.

## Native capability audit

PASS. The installed APK reports `S3CAP perf_export=1 trophy_export=1`; `verify-apk-core.sh` passed for both ABIs and the APK/core S3CORE IDs match. The arm64 symbol audit confirms `_rpcsx_setPerfMetricsEnabled`, `_rpcsx_getPerfMetricsJson`, `_rpcsx_getCurrentTrophies`, and `_rpcsx_getTrophiesForTitle`; see [native-symbols.txt](./artifacts/2026-08-31-runtime-repair/native-symbols.txt) and [pass4-apk-core-verify.log](./artifacts/2026-08-31-runtime-repair/pass4-apk-core-verify.log).

## Home load

PASS. The selected slot produces a single direct sequence: stopped preflight, surface-ready barrier, `direct-boot-begin`, direct savestate boot, `direct-boot-return result=NoErrors`, running, and first-frame confirmation. No normal boot followed by a UI-thread `loadSaveState` was observed.

Evidence: [home-selected-savestate.log](./artifacts/2026-08-31-runtime-repair/home-selected-savestate.log) and [home-selected-savestate-vulkan-monitor.png](./artifacts/2026-08-31-runtime-repair/home-selected-savestate-vulkan-monitor.png).

A clean normal-flow rerun also passed after clearing the prior-session recovery screen: [home-normal-pass.log](./artifacts/2026-08-31-runtime-repair/home-normal-pass.log) and [home-normal-pass.png](./artifacts/2026-08-31-runtime-repair/home-normal-pass.png). A separate forced-stop harness incident is retained as [home-load-forced-stop-recovery.log](./artifacts/2026-08-31-runtime-repair/home-load-forced-stop-recovery.log): force-stopping a live session correctly surfaced the previous-session recovery UI, so its subsequent coordinate taps were not a valid Home-load attempt.

The validated APK used for the mandatory stress matrix completed 30/30 normal Home launches, 10/10 force-stop/reopen recovery cycles, and 10/10 Continue Latest Save cycles. Every cycle reached a clean exit with no fatal/recovery marker in the intended post-recovery flow. The force-stop runs intentionally showed the expected previous-session recovery screen before EXIT; see [home-normal-30-summary.txt](./artifacts/2026-08-31-runtime-repair/home-normal-30-summary.txt), [home-force-stop-10-summary.txt](./artifacts/2026-08-31-runtime-repair/home-force-stop-10-summary.txt), and [home-continue-10-summary.txt](./artifacts/2026-08-31-runtime-repair/home-continue-10-summary.txt). The later pass3 diagnostic candidate is separately identified by its build ID and has direct-load/cross-check evidence, but has not been rerun through the complete two-pass stress matrix.

Pass 1 and Pass 2 each also completed a 22-minute continuous gameplay endurance run on the unchanged validated candidate, with image and log evidence: [pass1-long-play-end.png](./artifacts/2026-08-31-runtime-repair/pass1-long-play-end.png), [pass1-long-play.log](./artifacts/2026-08-31-runtime-repair/pass1-long-play.log), [pass2-long-play-end.png](./artifacts/2026-08-31-runtime-repair/pass2-long-play-end.png), and [pass2-long-play.log](./artifacts/2026-08-31-runtime-repair/pass2-long-play.log). The current pass4 candidate independently completed the same direct-load sequence; see [pass4-direct-load.png](./artifacts/2026-08-31-runtime-repair/pass4-direct-load.png) and [pass4-direct-load.log](./artifacts/2026-08-31-runtime-repair/pass4-direct-load.log).

## Performance benchmark

PARTIAL. Runtime performance export is real and populated, including FPS, frame time, CPU buckets, RSX load, and sample arrays. On the current APK/core, each profile ran 60 samples at 5-second cadence: 24 warmup samples followed by 36 measurement samples (~3 minutes). Measurement results were: monitor disabled CPU 4.00% / 36.92°C; minimal 3.94% / 37.25°C; developer graphs off 3.50% / 37.51°C; developer graphs on 4.22% / 37.69°C. All reported thermal status remained 3. These are single current-build runs using host `top` and battery telemetry; shell power-current readings were unavailable, and no controlled pre-monitor APK baseline or three-run repetition was available, so no regression percentage is claimed. Evidence: [benchmark-A-disabled.csv](./artifacts/2026-08-31-runtime-repair/benchmark-A-disabled.csv), [benchmark-B-minimal.csv](./artifacts/2026-08-31-runtime-repair/benchmark-B-minimal.csv), [benchmark-C-developer.csv](./artifacts/2026-08-31-runtime-repair/benchmark-C-developer.csv), and [benchmark-D-developer.csv](./artifacts/2026-08-31-runtime-repair/benchmark-D-developer.csv).

## Performance overlay

PASS. The overlay displays native FPS/frame time and live CPU/PPU/SPU/RSX/threads values. Enabling/disabling it calls the native lifecycle gate; disabled mode produces `S3PERF native_export=1 enabled=0` and no overlay.

Evidence: [monitor-disabled-gameplay.png](./artifacts/2026-08-31-runtime-repair/monitor-disabled-gameplay.png), [monitor-enabled-graphs.png](./artifacts/2026-08-31-runtime-repair/monitor-enabled-graphs.png), [monitor-disabled.log](./artifacts/2026-08-31-runtime-repair/monitor-disabled.log), and [monitor-enabled.log](./artifacts/2026-08-31-runtime-repair/monitor-enabled.log).

Minimal and developer preset captures are also preserved as [monitor-minimal.png](./artifacts/2026-08-31-runtime-repair/monitor-minimal.png) and [monitor-developer.png](./artifacts/2026-08-31-runtime-repair/monitor-developer.png).

Current pass4 captures show populated Minimal and Developer/graphs overlays: [pass4-monitor-minimal.png](./artifacts/2026-08-31-runtime-repair/pass4-monitor-minimal.png) and [pass4-monitor-developer.png](./artifacts/2026-08-31-runtime-repair/pass4-monitor-developer.png). Disabled-monitor behavior is recorded in [pass4-monitor-disabled.log](./artifacts/2026-08-31-runtime-repair/pass4-monitor-disabled.log).

The pass4 candidate completed a clean 32-second gameplay cross-check: 29 once-per-second samples at each of native, JSON, and UI layers, with matching FPS and frame-time values. See [pass4-crosscheck-lines.txt](./artifacts/2026-08-31-runtime-repair/pass4-crosscheck-lines.txt) and [pass4-crosscheck-30s.png](./artifacts/2026-08-31-runtime-repair/pass4-crosscheck-30s.png).

WebP copies for review are included alongside the PNG evidence, including [monitor-enabled-graphs.webp](./artifacts/2026-08-31-runtime-repair/monitor-enabled-graphs.webp), [monitor-minimal.webp](./artifacts/2026-08-31-runtime-repair/monitor-minimal.webp), and [monitor-developer.webp](./artifacts/2026-08-31-runtime-repair/monitor-developer.webp).

## FPS graph

PASS. FPS uses a separate finite sample series with stable zero-based scaling, target/current labels, gridlines, and bounded history. The on-device image shows a populated graph around 59.7 FPS. The unit-tested fixture `[30, 40, 50, 60]` is preserved as [graph-known-fps-30-40-50-60.webp](./artifacts/2026-08-31-runtime-repair/graph-known-fps-30-40-50-60.webp), and current-build real-game crops are [pass1-fps-graph-real.webp](./artifacts/2026-08-31-runtime-repair/pass1-fps-graph-real.webp) and [pass2-fps-graph-real.webp](./artifacts/2026-08-31-runtime-repair/pass2-fps-graph-real.webp). The real sample sequence is recorded in [benchmark-D-developer.csv](./artifacts/2026-08-31-runtime-repair/benchmark-D-developer.csv).

## Frametime graph

PASS. Frame time has its own series and scale rather than sharing the FPS scale. The on-device image shows a populated graph around 16.7 ms with a 30 ms upper label. The unit-tested fixture `[33.3, 25, 20, 16.7]` is preserved as [graph-known-frame-33-25-20-16.webp](./artifacts/2026-08-31-runtime-repair/graph-known-frame-33-25-20-16.webp), and current-build real-game crops are [pass1-frametime-graph-real.webp](./artifacts/2026-08-31-runtime-repair/pass1-frametime-graph-real.webp) and [pass2-frametime-graph-real.webp](./artifacts/2026-08-31-runtime-repair/pass2-frametime-graph-real.webp). The real sample sequence is recorded in [benchmark-D-developer.csv](./artifacts/2026-08-31-runtime-repair/benchmark-D-developer.csv).

## Achievements

PASS for stopped and current data presentation. Launch Center opens a stopped-title list without the previous `vfs_manager` crash and shows the real GTA title, 33 trophy entries, and actual trophy icons. In-game data shows `1/33 3%`, real names, grades, descriptions, icon art, and an unlocked entry. The native log records `S3TROPHY ... state=ready total=33 unlocked=1`.

Evidence: [stopped-achievements-real-icons.png](./artifacts/2026-08-31-runtime-repair/stopped-achievements-real-icons.png), [in-game-achievements-real-icons.png](./artifacts/2026-08-31-runtime-repair/in-game-achievements-real-icons.png), [final-current-achievements.png](./artifacts/2026-08-31-runtime-repair/final-current-achievements.png), and the current-candidate captures [pass4-achievements-ingame.png](./artifacts/2026-08-31-runtime-repair/pass4-achievements-ingame.png), [pass4-achievements-ingame.log](./artifacts/2026-08-31-runtime-repair/pass4-achievements-ingame.log), [pass4-achievements-launch-center.png](./artifacts/2026-08-31-runtime-repair/pass4-achievements-launch-center.png), and [pass4-achievements-launch-center.log](./artifacts/2026-08-31-runtime-repair/pass4-achievements-launch-center.log).

The real unlock-event path is implemented and enriched with native trophy data, but a new trophy was not intentionally unlocked during this validation; event delivery is therefore NOT RUN.

## PadOverlay touch

PASS for the repaired layering and visible touch surface. The monitor is above the emulator surface but non-clickable, while the PadOverlay remains visible and actionable. The saved gameplay images show the complete touch layout and the toolbar menu was opened by touch during validation.

The final APK also completed the full touch surface matrix with 19/19 root, monitor, and PadOverlay traces while monitoring was enabled, and 19/19 root and PadOverlay traces with monitoring disabled and no monitor traces. This covers the complete touch-button set plus both-stick swipes on the current 3000x2120 layout; see [touch-full-correct-monitor-on.log](./artifacts/2026-08-31-runtime-repair/touch-full-correct-monitor-on.log), [touch-full-correct-monitor-off.log](./artifacts/2026-08-31-runtime-repair/touch-full-correct-monitor-off.log), [touch-full-correct-monitor-on.png](./artifacts/2026-08-31-runtime-repair/touch-full-correct-monitor-on.png), and [touch-full-correct-monitor-off.png](./artifacts/2026-08-31-runtime-repair/touch-full-correct-monitor-off.png). External physical-controller permutations remain unavailable.

Touch-through captures and logs are preserved for both states: [touch-monitor-on.webp](./artifacts/2026-08-31-runtime-repair/touch-monitor-on.webp) and [touch-monitor-off.webp](./artifacts/2026-08-31-runtime-repair/touch-monitor-off.webp).

Current final-build matrix evidence is [touch-full-correct-monitor-on.webp](./artifacts/2026-08-31-runtime-repair/touch-full-correct-monitor-on.webp) and [touch-full-correct-monitor-off.webp](./artifacts/2026-08-31-runtime-repair/touch-full-correct-monitor-off.webp). The Pass 2 enabled run recorded 19 root/monitor/PadOverlay traces; see [pass2-touch-summary.txt](./artifacts/2026-08-31-runtime-repair/pass2-touch-summary.txt).

## Controller Settings

PARTIAL. The controller device repository, shared mapper, reserved PS/Guide handling, and tap/long-press model are present in source and compile-tested. Final-code runtime evidence shows the readable schematic, Mapping/Test/Profiles tabs, live input diagnostics, and the physical-button remap prompt in [controller-settings-landscape.webp](./artifacts/2026-08-31-runtime-repair/controller-settings-landscape.webp), [controller-test.webp](./artifacts/2026-08-31-runtime-repair/controller-test.webp), [controller-profiles.webp](./artifacts/2026-08-31-runtime-repair/controller-profiles.webp), and [controller-capture.webp](./artifacts/2026-08-31-runtime-repair/controller-capture.webp). A complete external-controller hardware matrix was not available in this run; the device reported the virtual touch controller.

## In-game SAVE/LOAD

PASS for the validated restore path, with the repeated-count gate still PARTIAL. The final build reached confirmed first frames after SAVE/restore activity, and the Home stress matrix exercised 10 Continue Latest Save restores successfully. A current-build SAVE request has complete evidence across [pass2-save-confirmed-current-begin.log](./artifacts/2026-08-31-runtime-repair/pass2-save-confirmed-current-begin.log) and [pass2-save-confirmed-current-end.log](./artifacts/2026-08-31-runtime-repair/pass2-save-confirmed-current-end.log): accepted, file committed, completion event, recovery boot result 0, and first-frame confirmation. A current-build manual LOAD is recorded in [pass2-load-current.log](./artifacts/2026-08-31-runtime-repair/pass2-load-current.log), with [pass2-load-transition.png](./artifacts/2026-08-31-runtime-repair/pass2-load-transition.png) and [pass2-load-restored-current.png](./artifacts/2026-08-31-runtime-repair/pass2-load-restored-current.png). The plan’s 10 SAVE plus 10 LOAD repetitions per pass were not fully proven by the UI harness; the failed-counted batch is retained in [pass2-save-10-summary.txt](./artifacts/2026-08-31-runtime-repair/pass2-save-10-summary.txt) and is not counted.

## Automated tests

PASS. The current candidate logs record `:app:testStandardDebugUnitTest`, `:app:testPlaystoreDebugUnitTest`, `assembleStandardDebug`, and `assemblePlaystoreDebug` completing successfully. The added coverage includes graph math, defensive native telemetry parsing, surface readiness, boot-mode resolution, controller mapping, overlay input policy, and monitoring lifecycle shutdown/restart behavior. Native exports and packaged ABI S3CORE IDs were independently checked on the current APK; see [pass4-build.log](./artifacts/2026-08-31-runtime-repair/pass4-build.log) and [pass4-apk-core-verify.log](./artifacts/2026-08-31-runtime-repair/pass4-apk-core-verify.log).

## Pass 1

PARTIAL. Source/native/app gates, the 30-cycle normal Home matrix, touch-through evidence, achievements, SAVE/restore coverage, and the 22-minute Pass 1 endurance session passed. Unit tests and both debug flavor assemblies completed successfully. The required 10 SAVE plus 10 LOAD repetitions were not fully proven by the UI harness, and physical-controller/event checks were unavailable.

Representative evidence: [pass1-home-load-restored.png](./artifacts/2026-08-31-runtime-repair/pass1-home-load-restored.png), [pass1-perf-minimal-landscape.webp](./artifacts/2026-08-31-runtime-repair/pass1-perf-minimal-landscape.webp), [pass1-perf-developer-landscape.webp](./artifacts/2026-08-31-runtime-repair/pass1-perf-developer-landscape.webp), [pass1-fps-graph-real.webp](./artifacts/2026-08-31-runtime-repair/pass1-fps-graph-real.webp), [pass1-frametime-graph-real.webp](./artifacts/2026-08-31-runtime-repair/pass1-frametime-graph-real.webp), [pass1-trophy-ingame-list.webp](./artifacts/2026-08-31-runtime-repair/pass1-trophy-ingame-list.webp), [pass1-trophy-launch-center-list.webp](./artifacts/2026-08-31-runtime-repair/pass1-trophy-launch-center-list.webp), and [pass1-fatal-grep.txt](./artifacts/2026-08-31-runtime-repair/pass1-fatal-grep.txt).

## Pass 2

PARTIAL. The unchanged-source rebuild gate and repeated clean Home-load cycles passed on the validated candidate. Pass 1 and Pass 2 each completed a 22-minute endurance session with zero fatal/error matches in the captured windows; see [pass1-fatal-grep.txt](./artifacts/2026-08-31-runtime-repair/pass1-fatal-grep.txt) and [pass2-fatal-grep.txt](./artifacts/2026-08-31-runtime-repair/pass2-fatal-grep.txt). The pass4 candidate also rebuilt and passed the key direct-load, cross-check, and trophy checks; see [pass4-build.log](./artifacts/2026-08-31-runtime-repair/pass4-build.log). The repeated SAVE/LOAD count on this exact candidate, controlled baseline/repetition, physical-controller hardware, and intentional unlock-event portions remain incomplete, so this is not a claim that every requested device gate passed.

Representative evidence: [pass2-home-load-restored.png](./artifacts/2026-08-31-runtime-repair/pass2-home-load-restored.png), [pass2-perf-minimal-landscape.webp](./artifacts/2026-08-31-runtime-repair/pass2-perf-minimal-landscape.webp), [pass2-perf-developer-landscape.webp](./artifacts/2026-08-31-runtime-repair/pass2-perf-developer-landscape.webp), [pass2-fps-graph-real.webp](./artifacts/2026-08-31-runtime-repair/pass2-fps-graph-real.webp), [pass2-frametime-graph-real.webp](./artifacts/2026-08-31-runtime-repair/pass2-frametime-graph-real.webp), [pass2-trophy-launch-center-list.webp](./artifacts/2026-08-31-runtime-repair/pass2-trophy-launch-center-list.webp), [pass2-touch-monitor-on.png](./artifacts/2026-08-31-runtime-repair/pass2-touch-monitor-on.png), and [pass2-fatal-grep.txt](./artifacts/2026-08-31-runtime-repair/pass2-fatal-grep.txt).

## Fatal grep

PASS for the final stopped-title and direct-savestate sessions: no `FATAL`, `vfs_manager`, or Android runtime exception was present in the captured feature logs. The earlier stopped-title VFS failure was reproduced during repair and removed by using physical trophy files when the emulator is stopped.

## Remaining risks

- The requested 30/10/10 Home stress counts are complete. One earlier force-stop harness attempt is explicitly excluded from the clean count because it intentionally exercised crash recovery and targeted the recovery screen incorrectly; the corrected 10-cycle force-stop run is included.
- Pass 1 and Pass 2 each completed a 22-minute uninterrupted gameplay run; the second pass is still not complete under the strict definition because repeated SAVE/LOAD, hardware, event, and baseline gates remain open.
- A controlled new trophy unlock event was not generated.
- The physical-controller hardware matrix, controlled old/new A/B baseline, and three independent benchmark repetitions remain follow-up work.
- The current pass4 candidate has an instrumented native/JNI/UI once-per-second cross-check: 29 matching samples at each layer in the clean 32-second gameplay run. This diagnostic was not part of the earlier full stress matrix, so a complete two-pass claim is intentionally withheld; the fresh recursive-clone result is recorded in [pass4-fresh-clone.log](./artifacts/2026-08-31-runtime-repair/pass4-fresh-clone.log).
- Portrait performance/controller captures and the complete physical-controller remap/conflict matrix remain unavailable on the attached device.
- The dated evidence directory retains historical captures from the prior candidate; the pass4 candidate is separately identified by its updated APK hash, build log, and runtime captures, and no complete two-pass claim is made.
- The standard build intentionally contains no bundled Turnip driver payload.

Build details and hashes are also recorded in [BUILD.txt](./artifacts/2026-08-31-runtime-repair/BUILD.txt).
