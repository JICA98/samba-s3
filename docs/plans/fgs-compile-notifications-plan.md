# Plan: FGS Persistent Notifications for PPU + RSX Shader Compilation with Time Remaining + In-UI Mirror

> User verbs: *"also use fgs as well notification persistent when compilation both of them happens and time remaining"* + *"can u make RSX Shaders — runtime, in-game RSX overlay (not Android Compose): in ui as well??"*  
> Evidence: screenshot `Y5WWBMJVOZSK4HU8` 2026-08-24 16:30:43 shows `Compiling PPU Modules... Progress: file 21 of 78, module 24 of 33 (10m remaining)` **only** as RSX overlay on `ANativeWindow` (behind `PadOverlay`), no persistent Android notification, and `RSX / Compiling shaders` 5 s hint (`overlay_compile_notification.cpp:12`) never leaves the surface. `PrecompilerService.kt` exists but never calls `startForeground` (`AndroidManifest.xml:29` no `foregroundServiceType`), and `ProgressRepository.kt:93` posts via `NotificationManagerCompat.notify` only. Runtime PPU path (`progress_dialog_server.cpp:40` / `system_progress.cpp:195`) has accurate `10m remaining` math already, but `rpcsx-android.cpp:1111 UiMessageDialog` is a stub, so boot-time PPU falls back to overlay too.

> **Review verdict (2026-08-24, revision 2):** Implementation-ready after two review passes. This revision retains the approved single-anchor lifecycle, FGS types, notification-permission semantics, cold-start channels, native PPU snapshot, indeterminate shader status, and dedicated Compose overlay. It additionally removes the service-listener bootstrap cycle, moves shader lifecycle events to the real pipeline enqueue/completion path, adds explicit terminal events and install/runtime ownership, handles background-start denial, defines old-core ABI fallback, and adds the required submodule patch update.

## Task Summary

Make **both** compilation domains persistently visible outside the emulator surface:

1. **PPU Modules** — install-time via `CompilationQueue` (`rpcsx-android.cpp:1264`) **and** boot/runtime via `progress_dialog_server` (`rpcsx/rpcs3/Emu/system_progress.cpp:195` where `of_1000`, `remaining`, `max_remaining` and the `progr` string `"Progress: file %d of %d, module %d of %d (Xm remaining)"` are already computed at `system_progress.cpp:260-341`).
2. **RSX Shaders** — per-cache-miss via `VKGSRender.cpp:1990` / `GLGSRender.cpp:825` → `show_shader_compile_notification()` (`overlay_compile_notification.cpp:12`, 5 s).

Both must:
- run as **FGS** (`ServiceCompat.startForeground`) when platform start rules permit, with ongoing `rpcsx-progress` notification showing `value/max`, percentage and the **time-remaining text** already produced natively,
- survive app background after a legal foreground start until an explicit native terminal event (`COMPLETED`, `FAILED`, or `CANCELED`),
- mirror in **Android Compose UI** in `RPCSXActivity` (top chip/badge + optional progress bar) so the user sees it without pulling the shade, while the RSX overlay stays for captures,
- expose distinct titles/icons so PPU vs shader are distinguishable (`"Compiling PPU Modules"` vs `"Compiling shaders"` from `rpcsx-android.cpp:325`).

Non-goals: no new engine compilation logic, no change to `async_with_interpreter` semantics, no emoji.

### Approved Architecture (post-review)

```
RPCSX native (event includes domain + phase + origin/job id)
   │
   ├── PPU BEGIN/PROGRESS/COMPLETED/FAILED/CANCELED
   │     snapshot from build_system_progress_snapshot()
   │     percentValue + native message + real ETA  ────────────┐
   │                                                           │
   └── RSX BEGIN at ProgramStateCache enqueue + terminal event
         from the actual pipeline completion callback ────────┤
             │                                                 │
             ▼                                                 ▼
      CompileProgressBridge (process singleton; owns the one JNI listener)
             │  StateFlow<CompileState> + latest triggering event
             │
             ├──────────────► dedicated RPCSXActivity ComposeView (compileStatusOverlay)
             │
             ├── legal start: startForegroundService(intent carrying event snapshot)
             └── denied background start: keep UI/native status, log + no crash
             ▼
 CompilationMonitorService
      foregroundServiceType=specialUse  ◄── ONE anchor FGS notification (NOTIF_FGS=2000)
      onStartCommand synchronously promotes from intent/latest snapshot; START_NOT_STICKY
             │
             ├── optional PPU secondary notification (2001, ordinary ongoing)
             └── optional Shader secondary notification (2002, ordinary ongoing)
                  — never call stopForeground() per-domain; only when activeDomainCount==0
                  — single merged InboxStyle on 2000 is also acceptable

PrecompilerService (install / firmware)
      foregroundServiceType=dataSync
      ContextCompat.startForegroundService() → startForeground() within ~5s
      owns install-origin PPU; monitor service must ignore that origin
      Android 15 background dataSync quota → onTimeout() must stop promptly
```

For install/precompilation:

```
PrecompilerService  (dataSync)
   user starts install
   ContextCompat.startForegroundService()
   startForeground(NOTIF=3000, type=dataSync) immediately (~5s requirement)
   real install/PPU progress via ProgressRepository
   stopForeground + stopSelf at completion or onTimeout()
```

For runtime PPU/shader work:

```
CompilationMonitorService  (specialUse — preferred; dataSync is a weaker match for runtime Vulkan pipeline compilation)
   process bridge registered idempotently after RPCSX initialization
   bridge—not the stopped service—receives the first native event
   starts/promotes from the event payload only while a foreground start is allowed
   one anchor FGS notification (2000); secondaries are ordinary ongoing notifications
   stop immediately when activeDomainCount == 0; optional UI fade may outlive the FGS
```

## Research Sources

**Internal:**

- `<source: app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp:322-326>` `MAKE_STRING` for `RSX_OVERLAYS_COMPILING_SHADERS/PPU_MODULES` + `PROGRESS_DIALOG_*` strings; `ProgressMessageDialog:997` (routes to `ProgressRepository` when `progressId!=-1`), `UiMessageDialog:1111` stub, `MessageDialog:1125` router, `CompilationQueue:1264` (`push/nextWorkTag/impl:1320` waits `Stopped/Ready`, sets `Running`, calls `ppu_precompile`, finishes with `Progress(...).success(0):1428`), `installIso:2402` → `g_compilationQueue.push`.
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/system_progress.cpp:40-425>` `progress_dialog_server` loop: native dialog vs `show_overlay_message` branch (`call_from_main_thread` not wired on Android, so `MessageDialog` path), PPU cue `show_ppu_compile_notification():236` with 10 ms refresh, `progr` construction (`get_localized_string(PROGRESS_DIALOG_PROGRESS)` + `FILE/MODULE OF` + `REMAINING` with `time_left_queue` smoothing `290-309`).
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/overlay_compile_notification.cpp:12-48>` 5 s shader cue, 20 s PPU cue, both `queue_message(bottom_left, loading_icon24)`.
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKGSRender.cpp:1987-1996>` cache-miss → `show_shader_compile_notification()` gated by `g_cfg.misc.show_shader_compilation_hint`, reached after `get_graphics_pipeline(...)` + `vk::leave_uninterruptible()` + `check_cache_missed()` — i.e. cache-miss indicator, not a clean "compilation started" hook.
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Program/ProgramStateCache.h:349-442>` first real miss inserts `__null_pipeline_handle`, dispatches `backend_traits::build_pipeline(... compile_async ...)`, and stores the completed pipeline through the callback; this is the correct shared VK/GL enqueue/completion lifecycle hook. Deferred compilation invokes the wrapper once with an empty result before the worker later invokes it with the real pipeline, so only the non-null completion is terminal.
- `<source: app/src/main/cpp/native-lib.cpp:22-110>` `RPCSXApi` dlsym table has `processCompilationQueue/startMainThreadProcessor` (:22-23) but no shader callback; `RPCSXLibrary::Open` pattern (:74-119).
- `<source: app/src/main/java/com/zenithblue/sambas3/ProgressRepository.kt>` `onProgressEvent:53` (updates `value/max/message`, calls per-id `Handler`), `create:76` (builds `NotificationCompat.Builder` `rpcsx-progress`, `setProgress(0,0,true)`, `setOngoing(true)`, `notify` via `NotificationManagerCompat` — **never `startForeground`**), per-id `asyncHandler:109` renders `text` as `setContentText` and `value/max` as `setProgress`; failed → `AlertDialogQueue`.
- `<source: app/src/main/java/com/zenithblue/sambas3/PrecompilerService.kt>` current: `onStartCommand:100` creates `ProgressRepository.create` with title `firmware_installation/package_installation`, launches `thread { install(...) }` (`thread:135`), `START_STICKY`, **no `startForeground`, no `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC/…`, no notificationId owned by Service**, and `start()` uses `context.startService` not `ContextCompat.startForegroundService`.
- `<source: app/src/main/java/com/zenithblue/sambas3/RPCSX.kt:82-85>` `processCompilationQueue/startMainThreadProcessor/collectGameInfo` externs.
- `<source: app/src/main/java/com/zenithblue/sambas3/MainActivity.kt:82-88>` starts both native looper threads (`thread { startMainThreadProcessor/processCompilationQueue }`) after `RPCSX.initialize`; creates `rpcsx-progress` channel only in `onCreate:34` — cold `RPCSXActivity` path bypasses it.
- `<source: app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt>` inflates `ActivityRpcs3Binding`, `ingameOverlay` is `visibility="gone"` and only shown with menu pages; cold-start init at `:59-75` starts both native loops without `MainActivity`; layout `activity_rpcs3.xml:21-25` confirms overlay host is gone by default.
- `<source: app/src/main/AndroidManifest.xml:5,29-43>` permissions `POST_NOTIFICATIONS` only; `<service .PrecompilerService>` lacks `android:foregroundServiceType` and `FOREGROUND_SERVICE` permissions; `RPCSXActivity` already `configChanges` handled.
- `<source: app/src/main/res/values/strings.xml:57>` `installation_progress` used for `NotificationChannel("rpcsx-progress")` (`MainActivity.kt:35`).
- Device evidence: `rpcsx_backend.log` 16:24:53 firmware install (no FGS), 16:25:34 `librudp.sprx` LLVM compile during `Running` with `Thread [Emulation Join Thread] is too sleepy`, 16:30:43 screenshot proves overlay-only PPU with `10m remaining`.

**External / platform:**

- FGS types and when to use `specialUse` vs `dataSync` (local file processing qualifies for `dataSync`; runtime Vulkan/OpenGL pipeline compilation is a weaker match — `specialUse` is the candidate category for a valid FGS use case not matching another type, requires `FOREGROUND_SERVICE_SPECIAL_USE` + a detailed free-form manifest `<property>` justification and Play review). TargetSdk 35 `dataSync` has a **6-hour background-use total per 24h**, shared across the app's services of that type; foreground interaction resets the timer. Android 15/API 35 introduced `onTimeout(startId, fgsType)`, after which the service must stop promptly. ([Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types) / [timeout](https://developer.android.com/develop/background-work/services/fgs/timeout) / [Play FGS declaration](https://support.google.com/googleplay/android-developer/answer/13392821))
- `POST_NOTIFICATIONS` denied: FGS can start without the permission, but the notification **does not appear in the drawer** — visible only via Task Manager. ([Android Developers](https://developer.android.com/develop/ui/compose/notifications/notification-permission))
- After `ContextCompat.startForegroundService()` the service must call `startForeground()` within about **5 seconds** or the system throws `ForegroundServiceDidNotStartInTimeException`. ([Android Developers](https://developer.android.com/develop/background-work/services))
- TargetSdk 31+ apps generally cannot start an FGS after the app is already backgrounded; `specialUse` is not an exemption. Catch `ForegroundServiceStartNotAllowedException`, retain in-process UI/native state, and do not crash. ([Android Developers](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start))

## Current Architecture

```
Install UI (PrecompilerService) ──► ProgressRepository.create ──► NotificationManager.notify (not FGS)
                                       ▲                                    │
CompilationQueue (native, thread) ─────┘                                    ▼ shade only, dies if app bkgrnd

Boot/Runtime PPU (progress_dialog_server, native thread)
  ├─ if renderer init && show_overlay_message ──► RSX overlay "Compiling PPU Modules" (5-20s batches, system_progress.cpp:236)
  └─ else ──► MessageDialog stub (no FGS) ──► UiMessageDialog (noop) ──► invisible unless show_overlay_message==false

RSX Shaders (VK/GL cache miss) ──► RSX overlay "Compiling shaders" 5s ──► surface only
                                                          ▲
                                                          no JNI → Kotlin path
```

Result: user sees 10 m remaining only on the game surface (must be in `RPCSXActivity`), no shade persistence, no timer outside the game.

## Affected Components & Dependencies

| Component | Impact |
|---|---|
| `app/src/main/AndroidManifest.xml` | ADD `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_SPECIAL_USE`; `PrecompilerService` → `foregroundServiceType="dataSync"`; new `CompilationMonitorService` → `foregroundServiceType="specialUse"` + a detailed free-form `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">`; treat Play approval as a release gate, not assumed approval |
| `app/src/main/java/com/zenithblue/sambas3/NotificationChannels.kt` **[NEW]** | Extract `ensureCreated(context)` from `MainActivity:34` (channel `rpcsx-progress`, `IMPORTANCE_LOW` minimum for FGS, `setShowBadge(false)`); called by **both** activities **and** by each FGS `onCreate` before `startForeground` (cold-start safety) |
| `app/src/main/res/values/strings.xml` | ADD `compiling_ppu_title`, `compiling_shaders_title`, `compiling_notification_desc`, `time_remaining_format` |
| `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp` | ADD process bridge callback carrying `domain`, explicit `phase`, `origin/jobId`, and PPU snapshot; own/replace/delete JNI `GlobalRef`s safely; deliver through `g_mainThreadProcessor` then Kotlin main handler |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/compile_progress.hpp/.cpp` **[NEW]** | Platform-neutral `CompileEvent`, event sink, job-id allocator, and install/runtime system-progress context; keeps Android/JNI dependencies out of renderer/cache templates |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/CMakeLists.txt` | ADD `compile_progress.cpp` to the emulator-core target |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/system_progress.cpp` | MODIFY — extract `build_system_progress_snapshot()` helper; emit explicit PPU BEGIN/PROGRESS/terminal events; tag install vs runtime origin; update RSX PPU HUD with the same snapshot text |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Program/ProgramStateCache.h` | MODIFY — shared VK/GL BEGIN at first placeholder insertion and terminal event from actual non-null pipeline completion callback; handle failure/abort without false END |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKGSRender.cpp` + `GLGSRender.cpp` + `overlay_compile_notification.cpp/.h` | KEEP cache-miss HUD cue in renderers; do not use it as FGS lifecycle. Extend only the PPU HUD API if needed to render the shared detailed snapshot |
| `app/src/main/cpp/native-lib.cpp` | ADD optional dlsym + JNI export for `setCompileProgressListener`; null-check capability so an older runtime core degrades without crashing |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt` | ADD `external fun setCompileProgressListener(cb)` + domain constants `COMPILE_DOMAIN_PPU=0, SHADER=1` |
| `app/src/main/java/com/zenithblue/sambas3/ProgressRepository.kt` | EXTEND with `createForeground(Service, notificationId, ...)` + `ServiceCompat.startForeground` helper; keep existing API for non-FGS callers |
| `app/src/main/java/com/zenithblue/sambas3/CompilationMonitorService.kt` | **NEW** — single-anchor FGS (`NOTIF_FGS=2000`), secondary ordinary notifications (`PPU=2001`, `SHADER=2002`) or merged `InboxStyle`; synchronously promote in `onStartCommand` from triggering payload/latest state; `START_NOT_STICKY`; ignore install-origin PPU |
| `app/src/main/java/com/zenithblue/sambas3/CompileProgressBridge.kt` **[NEW]** | Process singleton owns the one native listener, event reducer, `StateFlow`, legal FGS start attempt, background-start fallback, and idempotent registration; services/activities consume it but never replace its native listener |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` | ADD **dedicated** `compileStatusOverlay : ComposeView` above `PadOverlay` (not inside the `gone` `ingameOverlay`); `activity_rpcs3.xml` new view; chip observes `CompileProgressBridge.state` |
| `app/src/main/java/com/zenithblue/sambas3/MainActivity.kt` + `RPCSXActivity.kt` cold path | Register native progress bridge at `RPCSX.initialized` time; do **not** start idle FGS with placeholder — FGS promotes only on first genuine active compilation event |
| `patches/rpcsx-submodule-changes.patch` | REGENERATE/UPDATE for every change under `app/src/main/cpp/rpcsx`; required so runtime-core changes survive submodule reset/rebuild |
| `gradle/libs.versions.toml` | no new deps (uses `androidx.core:core 1.12+` already) |

## Implementation Steps

### P1 — Manifest + channel for FGS  [REVISED]

1. `AndroidManifest.xml:4` add:
   ```xml
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
   <service android:name=".PrecompilerService"
       android:foregroundServiceType="dataSync"
       android:exported="false"/>
   <service android:name=".CompilationMonitorService"
       android:foregroundServiceType="specialUse"
       android:exported="false">
     <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
               android:value="Keeps user-initiated PS3 PPU and graphics-pipeline compilation visible and running during game launch or temporary backgrounding; interruption delays gameplay"/>
   </service>
   ```
   Document Play justification: runtime PPU/shader compilation is user-perceptible (game launch / area traversal) and must remain visible if the user temporarily backgrounds the app; `specialUse` is the candidate category because runtime Vulkan/OpenGL pipeline compilation does not match `dataSync` or another enumerated type. This remains a **Play release-review gate**: complete the Play Console FGS declaration, describe interruption/deferral impact, and provide the required demonstration video. `PrecompilerService` (firmware/package install + local file processing) correctly uses `dataSync`.
   TargetSdk 35 `dataSync` has a **6-hour background-use total per 24h**, shared by all app `dataSync` services; returning the app to the foreground resets the timer. Android 15/API 35 `onTimeout(startId, fgsType)` must stop `PrecompilerService` promptly. `specialUse` has no corresponding six-hour quota, but policy still requires stopping it when the actual pending compilation count reaches zero.

2. Extract channel creation into `NotificationChannels.kt`:
   ```kotlin
   object NotificationChannels {
     const val RPCSX_PROGRESS = "rpcsx-progress"
     fun ensureCreated(ctx: Context) { /* IMPORTANCE_LOW or DEFAULT, setShowBadge(false), VISIBILITY_PUBLIC */ }
   }
   ```
   Call `ensureCreated(applicationContext)` in: `MainActivity.onCreate`, `RPCSXActivity.onCreate` (cold path), **`CompilationMonitorService.onCreate` before `startForeground`**, and `PrecompilerService.onCreate` before `startForeground`. The previous plan's channel-only-in-`MainActivity` would crash / throw `ForegroundServiceDidNotStartInTimeException` / show a bad notification on cold `RPCSXActivity` entry (adb launch after force-stop).

3. `POST_NOTIFICATIONS` semantics: FGS can start without the grant, but **the notification will not appear in the shade** — it is only visible via Task Manager. Do not gate `startForeground` on `Permission.PostNotifications.checkPermission()`. The FGS helper must ignore that gate (only the non-FGS `ProgressRepository.create` respects it).

4. FGS start legality: `specialUse` does not exempt the app from Android 12+ background-start restrictions. `CompileProgressBridge` attempts `ContextCompat.startForegroundService()` only while the process has a visible activity / legal user-visible transition. Catch `ForegroundServiceStartNotAllowedException`; if a first event arrives after the app is already backgrounded, keep native/StateFlow bookkeeping and resume in-app presentation when visible, but do not crash or invent an exemption. An FGS already started by real work while the activity was visible may continue across Home.

### P2 — `ProgressRepository` FGS-capable helper  [REVISED — single anchor]

1. Add `fun createForeground(service: Service, title: String, notificationId: Int, ...): Long` that builds the same `NotificationCompat.Builder` (`rpcsx-progress`, `setCategory(CATEGORY_PROGRESS)`, `setOngoing(true)`) but calls:
   ```kotlin
   ServiceCompat.startForeground(service, notificationId, builder.build(),
       if (service is CompilationMonitorService) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
       else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
   ```
   Store `notificationId` alongside `ProgressWithHandler` so `onProgressEvent` updates via `NotificationManagerCompat.notify` while the service stays foreground. For `CompilationMonitorService`, `notificationId` is **always** `NOTIF_FGS = 2000` (the anchor). Secondaries (`2001`/`2002`) are ordinary ongoing notifications posted via `notify()` only — they do not call `startForeground`.

2. `onProgressEvent` logic: allow `silent=false` for FGS path to always post text (`progr` contains `10m remaining`). For `max>0` determinate, `setProgress(max,value,false)` + `setContentText(text)` (the verbatim `progr`); for indeterminate (`max==0`) `setProgress(0,0,true)`. **Do not** call `ServiceCompat.stopForeground()` per-domain. Only when `activeDomainCount == 0` (both PPU done and shader idle) should the service call `ServiceCompat.stopForeground(service, STOP_FOREGROUND_REMOVE)` + `cancel(anchor)` and `stopSelf()`. This fixes the bug where PPU finishing while shaders are active would demote the whole service.

3. No breaking change to existing `ProgressRepository.create(Context ...)` callers; new FGS callers use the overload. Android 15/API 35 `onTimeout(startId, fgsType)` belongs in the owning `dataSync` service, which cancels notifications and calls `stopSelf(startId)` within a few seconds. Do not present it as an API 34 or `specialUse` timeout.

### P3 — Native → Kotlin bridge for both domains  [REVISED]

1. **Define a platform-neutral explicit event contract in new `Emu/compile_progress.hpp/.cpp`:**
   ```cpp
   enum class CompileDomain : int { PPU = 0, SHADER = 1 };
   enum class CompilePhase : int { BEGIN = 0, PROGRESS = 1, COMPLETED = 2, FAILED = 3, CANCELED = 4 };
   enum class CompileOrigin : int { INSTALL = 0, RUNTIME = 1 };
   struct PpuProgressSnapshot {
     int percentValue;   // 0..100 — the same value driving the RSX progress bar (from of_1000 / file/module math)
     int percentMax = 100;
     std::string message; // verbatim progr — e.g. "Progress: file 21 of 78, module 24 of 33 (10m remaining)"
     int fileDone, fileTotal;
     int moduleDone, moduleTotal;
   };
   struct CompileEvent {
     CompileDomain domain;
     CompilePhase phase;
     CompileOrigin origin;
     uint64_t jobId; // stable per PPU session or pipeline job
     std::optional<PpuProgressSnapshot> ppu;
   };
   std::function<void(CompileEvent)> g_compileProgressCb;
   ```
   Provide `set_compile_event_sink(...)`, `emit_compile_event(...)`, and a monotonic job-ID allocator. `rpcsx-android.cpp::setupCallbacks()` installs the Android/JNI sink after initialization; core renderer/cache code includes only this neutral header and never references JNI or an Android global. Terminal state is never inferred from `percentValue==100`, a negative value, a five-second timer, or lack of updates. Each native job/session must send exactly one terminal event after BEGIN, including cancellation and failure paths.
   Make sink access thread-safe: copy the current sink under a mutex, then invoke the copy after releasing the mutex so compiler callbacks cannot deadlock listener replacement/shutdown.

2. **PPU path — fix the percentage source + ETA extraction:**
   - In `system_progress.cpp`, the displayed percentage is **not** `pdone/ptotal`. It is derived from `of_1000` / weighted file+module math and smoothed `time_left_queue`. The `show_overlay_message` branch can `continue` before the block that builds `progr` and remaining-time, so simply appending a callback after `show_ppu_compile_notification()` would miss or duplicate work.
   - **Refactor:** extract a stateful helper/calculator `PpuProgressSnapshot build_system_progress_snapshot(...)` that computes `percentValue`, `progr`, and ETA exactly once while preserving the existing `time_left_queue` smoothing state. Both the RSX UI (`progress_dialog` and the PPU HUD message) and Android bridge consume that snapshot. Build it before the `show_overlay_message` early `continue`. Emit BEGIN once for the session, PROGRESS for snapshots, and an explicit terminal event from every loop exit/stop/cancel/failure path:
     ```cpp
     emit_compile_event({PPU, PROGRESS, origin, sessionId, snapshot});
     ```
     Forward `snapshot.message` verbatim to Kotlin — do not reformat in Kotlin (avoids locale drift). Expose `fileDone/fileTotal/moduleDone/moduleTotal` as well so Android `setProgress` exactly mirrors the native bar.
   - Tag install/precompile sessions using a process-wide synchronized `SystemProgressContext { origin, jobId }`: `CompilationQueue` sets `INSTALL` immediately before `ppu_precompile` and clears it with scope-exit after all terminal paths; the progress-server thread reads that context. Default context is `RUNTIME`. Do not use `thread_local`, because `CompilationQueue` and `progress_dialog_server` run on different threads. Installation compilation is serialized while the emulator is Stopped/Ready; assert/log if a conflicting context is attempted. Install-origin PPU remains owned by `PrecompilerService`; only runtime origin may start the monitor.
   - Current `show_ppu_compile_notification()` accepts no dynamic message. Extend its API (or the queued message update path) to accept/update `snapshot.message` if the requirement is to keep the detailed `file/module/remaining` text in the RSX HUD. The static HUD cue alone does not satisfy the shared-snapshot claim.

3. **Shader path — do not use `overlay_compile_notification.cpp` as primary hook:**
   - `overlay_compile_notification.cpp:12 show_shader_compile_notification()` is a 5 s UI cue reached **after** `VKGSRender::get_graphics_pipeline(...)` + `vk::leave_uninterruptible()` + `check_cache_missed()`. It fires only when `show_shader_compilation_hint` is enabled and an overlay manager exists, and its 5 s is notification lifetime, not compilation duration. Do **not** put the Android FGS callback only there.
   - **Instrument the actual shared pipeline lifecycle, not the renderer check:**
     - In `ProgramStateCache.h::get_graphics_pipeline`, emit shader BEGIN only when this call wins the miss race and inserts the first `__null_pipeline_handle`, immediately before `backend_traits::build_pipeline`. Assign a unique `jobId`.
     - Wrap the pipeline callback and emit COMPLETED only when the callback receives the real non-null pipeline. Deferred builds first return an empty result and later invoke the callback from the worker; that initial empty callback is not END. Emit FAILED/CANCELED from exception, compiler-abort, cache-clear, and renderer shutdown paths so the pending counter cannot leak.
     - Keep `VKGSRender.cpp` / `GLGSRender.cpp` `check_cache_missed()` solely for `show_shader_compile_notification()` HUD behavior. Do not emit BEGIN/END around `get_graphics_pipeline()` because END there occurs before an asynchronous pipeline is ready.
     - Promote shader work to FGS only for genuinely deferred/asynchronous pending jobs. An inline pipeline build that begins and completes within the same renderer call keeps the existing RSX HUD cue but does not churn a foreground service after the work has already finished.
   - For phase 1, shader has **no trustworthy ETA**. The 5 s is not compilation time and there is no async queue with known queued/finished counts in this cue. Do **not** estimate `pending * ~120ms`. Show shaders as **indeterminate**: `setProgress(0,0,true)` + `"Compiling shaders…"` (or `"Compiling shaders (Vulkan pipelines)"`). If a genuine queue is later discovered, compute ETA from measured durations/EWMA.

4. **JVM side `rpcsx-android.cpp:1828`:** implement `_rpcsx_setCompileProgressListener(JNIEnv* env, jobject callback)` with replace/unregister semantics and install the neutral event sink. Delete the previous JNI `GlobalRef` before replacement and on shutdown; store the callback method ID once; check/clear JNI exceptions. Dispatch native worker events through `g_mainThreadProcessor`, whose `JNIEnv*` belongs to the Kotlin-created processor thread. It is **not** Android's UI looper, so the Kotlin bridge must post/reduce events on `Handler(Looper.getMainLooper())`. Use this new rich event sink; existing `handle_taskbar_progress` is too lossy and is not a fallback for this feature.

### P4 — `native-lib.cpp` + `RPCSX.kt` plumbing  [REVISED]

1. `native-lib.cpp:18` add `RPCSXApi` entries:
   ```cpp
   bool (*setCompileProgressListener)(JNIEnv*, jobject) = nullptr;
   ```
   Add `dlsym` and JNI export `Java_com_zenithblue_sambas3_RPCSX_setCompileProgressListener`. Because the core `.so` is downloaded independently, treat the symbol as optional: null-check it, expose `supportsCompileProgressEvents`, log one compatibility warning, and degrade to existing HUD/non-FGS behavior on an older core. Do not call a null function pointer. Update the minimum core/release metadata only if product policy chooses to make this feature mandatory.

2. `RPCSX.kt:72` add:
   ```kotlin
   fun interface CompileProgressCallback {
     fun onEvent(domain:Int, phase:Int, origin:Int, jobId:Long,
                 value:Long, max:Long, message:String?,
                 fileDone:Int, fileTotal:Int, moduleDone:Int, moduleTotal:Int)
   }
   external fun setCompileProgressListener(callback: CompileProgressCallback?): Boolean
   external fun supportsCompileProgressEvents(): Boolean
   ```
   Keep the callback and method `@Keep`. Centralize domain/phase/origin constants; do not encode BEGIN/END by overloading `value` or `max`.

3. `CompileProgressBridge.kt` (new singleton):
   ```kotlin
   object CompileProgressBridge {
     private var registered = false
     data class CompileState(
       val ppuPercent:Int=0, val ppuMax:Int=100, val ppuMsg:String? = null,
       val ppuActive:Boolean=false, val shaderActive:Boolean=false, val shaderMsg:String? = null,
       val fileDone:Int=0, val fileTotal:Int=0, val moduleDone:Int=0, val moduleTotal:Int=0
     )
     val state = MutableStateFlow(CompileState())
     fun registerOnce(context: Context) { /* one process JNI listener after RPCSX.initialize */ }
     fun onNativeEvent(event: CompileEvent) { /* main-handler reducer keyed by domain/jobId */ }
     fun requestMonitorStart(context: Context, event: CompileEvent) { /* intent carries event */ }
   }
   ```
   `registerOnce()` is called from both initialization paths but installs only one process-lifetime callback. It owns JNI registration; neither activity nor service installs/replaces its own listener. The reducer tracks a set of active shader job IDs and an explicit PPU session ID, ignores duplicate/late terminal events, and preserves the latest active event for service cold start.

4. On the first active **runtime-origin** event, the bridge synchronously updates its latest state and, if a visible activity/user-visible transition makes the start legal, calls `ContextCompat.startForegroundService()` with the event serialized in extras. Catch `ForegroundServiceStartNotAllowedException` and record `fgsStartDenied=true`; do not retry in a tight loop. Install-origin PPU never starts `CompilationMonitorService` because `PrecompilerService` already owns its `dataSync` anchor.

### P5 — `CompilationMonitorService` (the FGS)  [REVISED — one anchor]

1. Create `app/src/main/java/com/zenithblue/sambas3/CompilationMonitorService.kt : Service()`:
   - `companion object { const val NOTIF_FGS=2000; const val NOTIF_PPU=2001; const val NOTIF_SHADER=2002; fun startForEvent(ctx:Context,event:CompileEvent) }`; the intent contains the active triggering event/snapshot, not an empty wake-up.
   - Holds `activeDomainCount` (PPU active + shader active). **Only `NOTIF_FGS` is the FGS notification** (`startForeground(NOTIF_FGS, ..., SPECIAL_USE)`). `NOTIF_PPU` and `NOTIF_SHADER` are secondary ordinary ongoing notifications posted via `NotificationManagerCompat.notify` when their domain is active — or, simpler, merge both lines into the single anchor via `InboxStyle` (`"PPU: 21/78 (10m remaining)"` + `"Shaders: compiling"`). The plan keeps two secondaries for independent cancel semantics, but the service **never** calls `stopForeground()` until `activeDomainCount == 0`.

   - Lifecycle — **no bootstrap cycle and no idle placeholder**:
     - `onCreate`: only create channels and begin collecting `CompileProgressBridge.state`; do **not** register or replace the native listener.
     - `onStartCommand`: validate the event extra (or atomically read the bridge's latest active runtime event), synchronously build the real anchor, and call `ServiceCompat.startForeground(... SPECIAL_USE)` before returning. If there is no active event, call `stopSelf(startId)` without accepting an empty FGS start. Subsequent events arrive through the already-running bridge/StateFlow collector.
     - For PPU: PROGRESS updates the anchor/secondary with `setProgress(100, percentValue, false)` and verbatim message. Only explicit COMPLETED/FAILED/CANCELED clears the domain. `percentValue==100` alone is presentation state, not lifecycle state.
     - For shader: BEGIN inserts `jobId` into the pending set and shows an indeterminate status. A terminal event removes that same ID. When the pending set reaches zero, shader is no longer FGS-active immediately. A five-second HUD/Compose fade may remain for visual continuity, but must not artificially keep the foreground service alive.
     - After each terminal event, if no runtime domain remains active, cancel secondaries, call `ServiceCompat.stopForeground(...REMOVE)`, and `stopSelf()`. Emulator stopped/shutdown may synthesize CANCELED for all remaining jobs as a fallback; do not use a generic 10-second no-progress timeout because a valid LLVM compile may be silent for longer.
     - `onStartCommand` → `START_NOT_STICKY`. If the process dies, native emulator/compiler dies too — restarting only the notification is useless and would show a stale placeholder.
     - No six-hour `onTimeout` is expected for `specialUse`; normal explicit lifecycle still stops promptly. A defensive override may cancel and stop, but document that Android 15 invokes the six-hour callback for `dataSync`/`mediaProcessing`, not this service type.

2. `PrecompilerService` adjustment:
   - Change `PrecompilerService.start()` from `context.startService(intent)` to `ContextCompat.startForegroundService(context, intent)`.
   - `onCreate`: `NotificationChannels.ensureCreated(this)`.
   - `onStartCommand`: immediately `ServiceCompat.startForeground(this, NOTIF_INSTALL=3000, builder.build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)` within ~5s. Keep the progress repository request ID separate from the fixed service notification ID; do not require `requestId.toInt()` before `createForeground()` has returned.
   - Own all `CompileOrigin.INSTALL` PPU progress and ignore it in `CompilationMonitorService`, preventing two simultaneous FGS anchors for one install.
   - Android 15/API 35 `onTimeout(startId, fgsType)` cancels the work if supported, cancels notifications, and calls `stopSelf(startId)` within a few seconds. Stop foreground on explicit repository success/failure/cancel. Change `START_STICKY` → `START_NOT_STICKY` (install intents are not meant to be redelivered after process death without the fd).

3. **Do not** start `CompilationMonitorService` at `MainActivity` init with a `"Preparing compilation..."` placeholder merely to avoid a later start restriction. Register the process bridge early, but promote only from a real event payload. Accept that a first event received only after the app is fully backgrounded may not legally start an FGS; log/fallback instead of crashing.

### P6 — In-UI mirror in `RPCSXActivity`  [REVISED — dedicated ComposeView]

1. `activity_rpcs3.xml` — add a **separate** `ComposeView` above `PadOverlay` (lower risk than refactoring the existing host):
   ```xml
   <androidx.compose.ui.platform.ComposeView
       android:id="@+id/compileStatusOverlay"
       android:layout_width="match_parent"
       android:layout_height="wrap_content"
       android:visibility="gone"
       app:layout_constraintTop_toTopOf="parent"
       app:layout_constraintStart_toStartOf="parent"
       app:layout_constraintEnd_toEndOf="parent"/>
   ```
   The existing `ingameOverlay` (`:21-25`) is `visibility="gone"` and only shown when `InGameUi.page != Closed` — a chip inside it would be invisible during gameplay. Place `compileStatusOverlay` after/above `padOverlay` but before/below `ingameOverlay`; use a translation/elevation lower than the home-menu host and validate ordering by screenshot. Alternatively, refactor `ingameOverlay` so its `ComposeView` stays visible while only menu content is conditionally rendered — but the separate view is lower-risk.

2. `RPCSXActivity.onCreate` after `setContent { RPCSXTheme { ... } }`:
   ```kotlin
   binding.compileStatusOverlay.setViewCompositionStrategy(DisposeOnViewTreeLifecycleDestroyed)
   binding.compileStatusOverlay.setContent {
     val s by CompileProgressBridge.state.collectAsState()
     if (s.ppuActive || s.shaderActive) {
       Surface(tint, 56dp) {
         if (s.ppuActive) LinearProgressIndicator(progress={s.ppuPercent/100f})
         else CircularProgressIndicator(indeterminate) // shader
         Text(s.ppuMsg ?: s.shaderMsg ?: "Compiling…")
         if (s.shaderActive) PulsingDot()
       }
     }
   }
   // Observe and toggle visibility:
   lifecycleScope.launch { CompileProgressBridge.state.collect { binding.compileStatusOverlay.isVisible = it.ppuActive || it.shaderActive } }
   ```
   Respect `WindowInsets` (`applyInsetsToPadOverlay` pattern). Dismisses automatically when `StateFlow` idle. A short Compose-only fade after the final shader terminal event is allowed, but it must not keep the FGS active. Keep the RSX overlay for captures; the detailed PPU HUD update and Compose chip are additive.

### P7 — Strings & icons

1. `strings.xml` add: `<string name="compiling_ppu_title">Compiling PPU Modules</string>`, `<string name="compiling_shaders_title">Compiling shaders</string>`, `<string name="compiling_shaders_desc">Building Vulkan pipelines</string>`, keep `installation_progress` channel name. No new drawables needed — reuse `ic_video`/`memory` vectors if small icon variant wanted, else `ic_sambas3_foreground` as `setSmallIcon`.

## File-Level Change Map

| File | Change | Rationale |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | MODIFY — `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` + `FOREGROUND_SERVICE_SPECIAL_USE`, `PrecompilerService` `dataSync`, `CompilationMonitorService` `specialUse` + `<property>` | P1 FGS declar., correct types |
| `app/src/main/java/com/zenithblue/sambas3/NotificationChannels.kt` | **NEW** — `ensureCreated()` extracted from `MainActivity` | P1 cold-start crash fix |
| `app/src/main/res/values/strings.xml` | MODIFY — 3 new title strings | P7 i18n |
| `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp` | MODIFY — rich `CompileEvent`, safe JNI global-ref ownership, `_rpcsx_setCompileProgressListener`, terminal events and origin/job IDs | P3 lifecycle bridge |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/compile_progress.hpp/.cpp` | **NEW** — platform-neutral event contract/sink, job IDs, synchronized PPU origin context | P3 keep core independent from JNI |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/CMakeLists.txt` | MODIFY — compile the new event bus implementation | P3 build wiring |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/system_progress.cpp/.hpp` | MODIFY — stateful `build_system_progress_snapshot()`, explicit PPU BEGIN/PROGRESS/terminal events, install/runtime origin | P3 correct %/ETA/lifecycle |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Program/ProgramStateCache.h` | MODIFY — BEGIN at first placeholder insertion; terminal from real non-null async completion/failure/cancel | P3 true shared VK/GL lifecycle hook |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/overlay_compile_notification.cpp/.h` | MODIFY PPU cue to accept/update shared snapshot text; keep shader cue HUD-only | P3 detailed RSX PPU HUD + shader HUD stays |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKGSRender.cpp` + `GLGSRender.cpp` | KEEP `check_cache_missed()` for HUD only; no FGS lifecycle events here | P3 avoid false async END |
| `app/src/main/cpp/native-lib.cpp` | MODIFY — optional capability-checked dlsym/JNI listener; old core degrades safely | P4 ABI compatibility |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt` | MODIFY — rich optional listener, capability query, domain/phase/origin constants | P4 |
| `app/src/main/java/com/zenithblue/sambas3/CompileProgressBridge.kt` | **NEW** — one process JNI listener, event reducer/job sets, `StateFlow`, event-carrying legal start and denied-start fallback | P4/P5/P6 shared state |
| `app/src/main/java/com/zenithblue/sambas3/ProgressRepository.kt` | MODIFY — `createForeground(Service, fixedNotificationId)` builder/update helper; keep service stop/timeout lifecycle in owning services | P2 fixed anchor IDs |
| `app/src/main/java/com/zenithblue/sambas3/CompilationMonitorService.kt` | **NEW** — event-carrying synchronous promotion, `NOTIF_FGS=2000` anchor + ordinary secondaries, explicit terminal lifecycle, ignores install origin, `START_NOT_STICKY` | P5 |
| `app/src/main/java/com/zenithblue/sambas3/PrecompilerService.kt` | MODIFY — fixed `NOTIF_INSTALL=3000`, `ContextCompat.startForegroundService`, install-origin ownership, `START_NOT_STICKY`, API 35 `onTimeout` | P5 install FGS |
| `app/src/main/res/layout/activity_rpcs3.xml` | MODIFY — add `compileStatusOverlay` ComposeView above `PadOverlay` | P6 visibility fix |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` | MODIFY — dedicated `compileStatusOverlay` observing `CompileProgressBridge.state` | P6 |
| `app/src/main/java/com/zenithblue/sambas3/MainActivity.kt` + `RPCSXActivity.kt` cold path | MODIFY — register bridge early; promote FGS only on first real event (no idle placeholder) | P5 lifecycle |
| `patches/rpcsx-submodule-changes.patch` | MODIFY/REGENERATE — include all RPCSX submodule event/snapshot/HUD changes | Required reproducibility |

## Testing Strategy

Build gates: `./gradlew assembleStandardDebug assemblePlaystoreDebug` and `./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest`.

Add reducer/service unit tests before device testing: duplicate BEGIN, out-of-order/duplicate terminal, two concurrent shader job IDs, PPU 100% without terminal, PPU CANCELED while shaders remain, install-origin suppression, no-active-event service start, old-core capability false, and `ForegroundServiceStartNotAllowedException` fallback. Add a focused native test or debug assertion for deferred compiler behavior: empty immediate callback is not terminal; real worker callback is terminal exactly once.

Manual on `Y5WWBMJVOZSK4HU8` (already has `samba-s3-standard-debug.apk` `141M` + 118M builds; `2a580689`/`7d6afed8` as controls):

- M1 Install flow (ISO `BLUS30441` or `BLUS31584`) → pull shade during `StagedGameInstaller` `Scanning/Building manifest/Committing` → fixed notification `3000` appears as `Installing — 0% → ... → Preparing PPU compilation`; after commit, install-origin PPU progress updates that same `PrecompilerService` anchor. Verify notification/service dumps show **one** owner (`dataSync`) and no `CompilationMonitorService`/notification `2000` for the install job. It survives Home after the legal user-started promotion and stops on explicit completion/failure/cancel.
- M2 Boot PPU (cold boot game with no cache, including **cold `RPCSXActivity` entry after `adb shell am force-stop`**) → process bridge receives BEGIN before the service exists, starts it with the event extra, and `onStartCommand` promotes synchronously. `Compiling PPU Modules...` chip appears ≤500 ms; detailed RSX HUD, Compose chip, and shade show the same `progr`/percentage. A 100% PROGRESS event alone must not stop the service; explicit terminal does. Kill game → native CANCELED clears anchor and chip within 2 s, with no idle-time heuristic.
- M3 RSX shader trip — launch `GTASAsf*` intro traversal (intro→Grove Street) → first real `ProgramStateCache` enqueue sends BEGIN; the actual worker completion sends terminal. Shade/chip are indeterminate with no ETA. Multiple pending job IDs keep one domain active without notification spam. When the pending set reaches zero, the FGS stops immediately if PPU is inactive; the existing RSX cue and optional Compose fade may remain up to 5 s without keeping the service foreground.
- M4 Both concurrent — start PPU cold boot then immediately trigger shader burst (enter world) → shade shows anchor `InboxStyle` with both lines (`PPU 24/33 (10m)` + `Shaders: compiling`) or anchor + two secondaries (`2000` + `2001` + `2002`); chip shows `PPU 24/33 (10m)` + shader dot. Verify PPU finishing does **not** demote FGS while shader still active (`dumpsys activity services` still `isForeground=true, specialUse`).
- M5 Permission denied — `adb shell appops set com.zenithblue.sambas3 POST_NOTIFICATION deny` → install FGS and runtime PPU FGS still start (`isForeground=true`) but **do not appear in the shade** — verify they are visible only via **Task Manager / `adb shell dumpsys notification`** and not via drawer; re-allow restores shade visibility. This replaces the previous incorrect "FGS still visible in shade" criterion.
- M6 `POST_NOTIFICATIONS` granted/denied, rotation, `singleTask` bring-to-front, and `6.1.170-GKID` OEM log spam regression: verify no `ForegroundServiceDidNotStartInTimeException`, one idempotent JNI listener, and no leaked `GlobalRef`/duplicate events.
- M7 Background-first fallback — launch game, press Home **before** the first runtime compile event, then trigger/observe the event if emulation continues. Verify a denied late FGS start is caught/logged without process crash or retry loop; native/StateFlow state remains coherent. Separately verify that an FGS started from a real event while the activity is visible continues after Home.
- M8 Old core — install/run a runtime `.so` without `_rpcsx_setCompileProgressListener`. `supportsCompileProgressEvents()==false`, existing HUD continues, and there is no null-call crash. Run the updated core control and verify the capability becomes true.
- M9 Timeout — use Android 15 `device_config` to shorten `data_sync_fgs_timeout_duration`; verify `PrecompilerService.onTimeout(startId, DATA_SYNC)` stops/cancels within a few seconds. Do not claim this callback for `CompilationMonitorService.specialUse`.

## Acceptance Criteria

- [ ] `grep -rn foregroundServiceType AndroidManifest.xml` shows `dataSync` on `PrecompilerService` and `specialUse` on `CompilationMonitorService`; `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_SPECIAL_USE` permissions present; `CompilationMonitorService` has `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">`.
- [ ] `grep -rn NotificationChannels app/src/main/java` shows `ensureCreated` called from `MainActivity`, `RPCSXActivity` cold path, `CompilationMonitorService.onCreate`, and `PrecompilerService.onCreate`.
- [ ] `./gradlew assembleStandardDebug assemblePlaystoreDebug` passes both flavors with `compileSdk 36 / targetSdk 35`.
- [ ] Install-origin PPU has exactly one foreground owner: fixed notification `3000`/`PrecompilerService=dataSync`; `CompilationMonitorService` ignores it. Runtime-origin PPU uses only monitor anchor `2000`.
- [ ] Runtime PPU (including cold `RPCSXActivity` after force-stop) shows a persistent anchor with verbatim `Progress: file X of Y, module Z of W (Xm remaining)` from `build_system_progress_snapshot()`; detailed RSX HUD and Compose mirror use the same snapshot. Lifecycle is driven by explicit terminal phase, not `percentValue==100` or inactivity.
- [ ] RSX traversal shows `Compiling shaders` as indeterminate with no ETA. Shader lifecycle comes from `ProgramStateCache` first enqueue and actual non-null worker completion/failure/cancel, keyed by job ID; renderer `check_cache_missed()` remains HUD-only.
- [ ] When both runtime domains are active, one anchor is visible and cancelled correctly: explicit PPU terminal does not demote while shader job IDs remain; final shader terminal does not clear active PPU; `stopForeground` runs immediately only when `activeDomainCount==0`. A visual fade may outlive FGS state.
- [ ] The process bridge owns exactly one idempotently registered JNI listener. `CompilationMonitorService` does not register it; the first event is carried into `onStartCommand`, which promotes synchronously. Listener replacement/shutdown deletes JNI global references and handles callback exceptions.
- [ ] `adb shell dumpsys activity services | grep CompilationMonitorService` → `isForeground=true, foregroundServiceType=specialUse` during legally started runtime compilation and false immediately after the last terminal event. A first event received after full backgrounding is caught as a denied start/fallback without crash.
- [ ] `CompilationMonitorService` and `PrecompilerService` use `START_NOT_STICKY`; `PrecompilerService.start()` uses `ContextCompat.startForegroundService()` and promotes fixed notification `3000` within ~5 s. API 35 `PrecompilerService.onTimeout(startId, DATA_SYNC)` stops promptly.
- [ ] An older runtime core lacking the new symbol reports capability false and continues existing HUD behavior without a null function-pointer call; the updated `patches/rpcsx-submodule-changes.patch` contains all submodule edits.
- [ ] Existing RSX overlay `Compiling PPU Modules... / Compiling shaders` still renders on the surface for captures (not removed).
- [ ] Zero emoji, icons are `painterResource` XML vectors or `mipmap/ic_sambas3_foreground`.

## Risks & Mitigations

- **R1 FGS start timeout (~5 s, not 10 s)** — service must call `startForeground` shortly after `startForegroundService()`. Mitigation: process bridge updates latest state first and passes the real event in the start intent; `onStartCommand` builds/promotes synchronously. The service never waits for a listener it owns.
- **R2 Background start denial** — a native event may first arrive after Home, when targetSdk 35 cannot legally start the FGS. Mitigation: visible-activity gate, catch `ForegroundServiceStartNotAllowedException`, coherent in-process fallback, no retry loop, and M7. `specialUse` is not treated as an exemption.
- **R3 `targetSdk 35` `dataSync` quota** — Android 15 counts up to 6 h of background `dataSync` use per 24 h across the app and resets it when the user foregrounds the app. Mitigation: API 35 `PrecompilerService.onTimeout`, fixed install ownership, and shortened-timeout testing. `CompilationMonitorService.specialUse` is not described as receiving this quota callback.
- **R4 Duplicate `progr` formatting drift** — forward native `progr` verbatim plus `percentValue` from the stateful shared snapshot helper instead of re-formatting in Kotlin. The detailed PPU HUD update consumes the same snapshot.
- **R5 Shader async lifecycle/storms** — renderer cache-miss checks happen after dispatch and deferred callbacks initially receive an empty result. Mitigation: BEGIN only at first placeholder insertion, terminal only from real completion/failure/cancel, job-ID set reducer, notification updates capped at ≤2/s, and unit/native assertions. Five-second fade is UI-only.
- **R6 Duplicate install/runtime ownership** — a global PPU callback could start both services. Mitigation: required origin field; install PPU stays with fixed `PrecompilerService` anchor, runtime monitor ignores it.
- **R7 Runtime-core ABI skew** — APK and downloaded `.so` can be different releases. Mitigation: optional/null-checked dlsym capability, graceful HUD-only fallback, compatibility test, and updated submodule patch.
- **R8 JNI lifetime/threading** — repeated activity initialization could replace/leak a callback, and `g_mainThreadProcessor` is not the Android UI looper. Mitigation: process singleton `registerOnce`, replace/unregister/delete `GlobalRef`, callback exception checks, then Kotlin main-handler reduction.
- **R9 `ProgressRepository` races** — existing `ConcurrentHashMap` + `Handler.createAsync` serializes to main looper; new FGS path reuses it. Mitigation: fixed service IDs distinct from request IDs, per-domain secondary IDs, and all anchor mutations on the main handler.
- **R10 Permission `POST_NOTIFICATIONS` denied** — FGS path must not gate `startForeground` but must expect shade invisibility. Mitigation: tests verify Task Manager/dumpsys visibility, not drawer visibility.
- **R11 Missing terminal event** — compiler failure/shutdown could otherwise leak the FGS. Mitigation: exactly-once terminal contract on every native path plus emulator-stop CANCELED fallback; never auto-cancel merely because progress was quiet for 10 s.
- **R12 Surface vs Compose z-order** — `compileStatusOverlay` must sit above `PadOverlay` but below `HomeMenu`. Mitigation: XML order/elevation validated by screenshot `Y5WWBMJVOZSK4HU8`; separate from the `gone` `ingameOverlay`.

## Handoff to Plan Reviewer

Resolved design decisions after two review passes:

- (a) `foregroundServiceType` — `PrecompilerService=dataSync`, `CompilationMonitorService=specialUse` with detailed `<property>` justification; Play approval remains an explicit release gate. API 35 timeout is assigned accurately to `dataSync`.
- (b) Single vs two notificationIds — **one anchor FGS (`2000`)** + two ordinary ongoing secondaries (`2001`/`2002`) or merged `InboxStyle` on anchor; never independently `stopForeground` per-domain (`activeDomainCount` gate).
- (c) Shader ETA — **indeterminate only** for phase 1; no `pending*120ms`; future ETA only if genuine queue with measured EWMA.
- (d) Native bridge — use the direct rich `CompileEvent`; `handle_taskbar_progress` is explicitly rejected as too lossy. Shared PPU snapshot prevents duplicated math.
- (e) Listener/service ownership — `CompileProgressBridge.registerOnce()` owns the process listener; both initialization paths may call it idempotently. The service promotes synchronously from an event-carrying intent and never owns the listener.
- (f) Shader hook — shared `ProgramStateCache` enqueue/real callback, not renderer post-hoc `check_cache_missed()`.
- (g) Completion — explicit phase + origin + job ID; never infer from 100%, negative values, elapsed five seconds, or inactivity.
- (h) Service ownership — install-origin PPU belongs only to `PrecompilerService`; runtime PPU/shader belong to the monitor.
- (i) Compatibility — optional core symbol with safe old-core fallback plus regenerated submodule patch.

## Review Checklist (for next reviewer)

- [ ] `specialUse` detailed manifest justification, Play Console declaration, and demonstration video are ready; approval remains a release gate.
- [ ] Cold `RPCSXActivity` (force-stop → `adb shell am start -n .../.RPCSXActivity -e path ...`) does not crash on FGS start (channel created, `startForeground` within 5 s).
- [ ] `POST_NOTIFICATIONS` denied shade-invisibility is documented and tested via `dumpsys` / Task Manager, not drawer assertion.
- [ ] PPU `percentValue` comes from `build_system_progress_snapshot()` (same as RSX bar), not `pdone/ptotal`.
- [ ] Shader FGS is driven by shared pipeline enqueue/actual completion with job IDs; VK/GL `check_cache_missed()` and `show_shader_compile_notification()` remain HUD-only.
- [ ] `compileStatusOverlay` is a separate `ComposeView` above `PadOverlay`, not inside the `gone` `ingameOverlay`.
- [ ] First-event service bootstrap is non-circular, install/runtime origins cannot create duplicate FGS owners, background-start denial is caught, and old runtime cores cannot null-call the new symbol.
