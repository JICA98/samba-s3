# Plan: FGS Persistent Notifications for PPU + RSX Shader Compilation with Time Remaining + In-UI Mirror

> User verbs: *"also use fgs as well notification persistent when compilation both of them happens and time remaining"* + *"can u make RSX Shaders — runtime, in-game RSX overlay (not Android Compose): in ui as well??"*  
> Evidence: screenshot `Y5WWBMJVOZSK4HU8` 2026-08-24 16:30:43 shows `Compiling PPU Modules... Progress: file 21 of 78, module 24 of 33 (10m remaining)` **only** as RSX overlay on `ANativeWindow` (behind `PadOverlay`), no persistent Android notification, and `RSX / Compiling shaders` 5 s hint (`overlay_compile_notification.cpp:12`) never leaves the surface. `PrecompilerService.kt` exists but never calls `startForeground` (`AndroidManifest.xml:29` no `foregroundServiceType`), and `ProgressRepository.kt:93` posts via `NotificationManagerCompat.notify` only. Runtime PPU path (`progress_dialog_server.cpp:40` / `system_progress.cpp:195`) has accurate `10m remaining` math already, but `rpcsx-android.cpp:1111 UiMessageDialog` is a stub, so boot-time PPU falls back to overlay too.

> **Review verdict (2026-08-24):** Plan is directionally correct but not implementation-ready as written (~6.5/10). Bridge, persistent status, keeping RSX overlay, and mirroring into Android UI are approved. The following revision fixes the blocking issues before handing to workers: single-anchor FGS lifecycle, correct `foregroundServiceType`s (`dataSync` vs `specialUse`), notification-permission semantics, cold-start channel creation, PPU snapshot contract, shader hook location, no invented ETA, and a dedicated Compose overlay. All items below marked `[REVISED]` or `[NEW]` address the review.

## Task Summary

Make **both** compilation domains persistently visible outside the emulator surface:

1. **PPU Modules** — install-time via `CompilationQueue` (`rpcsx-android.cpp:1264`) **and** boot/runtime via `progress_dialog_server` (`rpcsx/rpcs3/Emu/system_progress.cpp:195` where `of_1000`, `remaining`, `max_remaining` and the `progr` string `"Progress: file %d of %d, module %d of %d (Xm remaining)"` are already computed at `system_progress.cpp:260-341`).
2. **RSX Shaders** — per-cache-miss via `VKGSRender.cpp:1990` / `GLGSRender.cpp:825` → `show_shader_compile_notification()` (`overlay_compile_notification.cpp:12`, 5 s).

Both must:
- run as **FGS** (`ServiceCompat.startForeground`) with ongoing `rpcsx-progress` notification showing `value/max`, percentage and the **time-remaining text** already produced natively,
- survive app background (library `Activity` stopped, `MainActivity` in background) until `value==max` or `value<0`,
- mirror in **Android Compose UI** in `RPCSXActivity` (top chip/badge + optional progress bar) so the user sees it without pulling the shade, while the RSX overlay stays for captures,
- expose distinct titles/icons so PPU vs shader are distinguishable (`"Compiling PPU Modules"` vs `"Compiling shaders"` from `rpcsx-android.cpp:325`).

Non-goals: no new engine compilation logic, no change to `async_with_interpreter` semantics, no emoji.

### Approved Architecture (post-review)

```
RPCSX native
   │
   ├── PPU progress snapshot  (build_system_progress_snapshot())
   │     percentValue + native message + real ETA  ────────────┐
   │                                                           │
   └── RSX compile BEGIN/END/cache-miss events (VK/GL) ───────┤
             │                                                 │
             ▼                                                 ▼
      CompileProgressBridge (Kotlin singleton, StateFlow<CompileState>)
             │
             ├──────────────► dedicated RPCSXActivity ComposeView (compileStatusOverlay)
             │
             ▼
 CompilationMonitorService
      foregroundServiceType=specialUse  ◄── ONE anchor FGS notification (NOTIF_FGS=2000)
      START_NOT_STICKY, onTimeout() handled
             │
             ├── optional PPU secondary notification (2001, ordinary ongoing)
             └── optional Shader secondary notification (2002, ordinary ongoing)
                  — never call stopForeground() per-domain; only when activeDomainCount==0
                  — single merged InboxStyle on 2000 is also acceptable

PrecompilerService (install / firmware)
      foregroundServiceType=dataSync
      ContextCompat.startForegroundService() → startForeground() within ~5s
      6h/24h dataSync timeout → onTimeout() must stop promptly
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
   bridge registered at RPCSX initialization (MainActivity + RPCSXActivity cold path)
   starts/promotes to FGS only on first genuine active compilation event
   one anchor FGS notification (2000); secondaries are ordinary ongoing notifications
   stop only when activeDomainCount == 0  (PPU done + shader idle)
```

## Research Sources

**Internal:**

- `<source: app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp:322-326>` `MAKE_STRING` for `RSX_OVERLAYS_COMPILING_SHADERS/PPU_MODULES` + `PROGRESS_DIALOG_*` strings; `ProgressMessageDialog:997` (routes to `ProgressRepository` when `progressId!=-1`), `UiMessageDialog:1111` stub, `MessageDialog:1125` router, `CompilationQueue:1264` (`push/nextWorkTag/impl:1320` waits `Stopped/Ready`, sets `Running`, calls `ppu_precompile`, finishes with `Progress(...).success(0):1428`), `installIso:2402` → `g_compilationQueue.push`.
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/system_progress.cpp:40-425>` `progress_dialog_server` loop: native dialog vs `show_overlay_message` branch (`call_from_main_thread` not wired on Android, so `MessageDialog` path), PPU cue `show_ppu_compile_notification():236` with 10 ms refresh, `progr` construction (`get_localized_string(PROGRESS_DIALOG_PROGRESS)` + `FILE/MODULE OF` + `REMAINING` with `time_left_queue` smoothing `290-309`).
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/overlay_compile_notification.cpp:12-48>` 5 s shader cue, 20 s PPU cue, both `queue_message(bottom_left, loading_icon24)`.
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKGSRender.cpp:1987-1996>` cache-miss → `show_shader_compile_notification()` gated by `g_cfg.misc.show_shader_compilation_hint`, reached after `get_graphics_pipeline(...)` + `vk::leave_uninterruptible()` + `check_cache_missed()` — i.e. cache-miss indicator, not a clean "compilation started" hook.
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

- FGS types and when to use `specialUse` vs `dataSync` (local file processing qualifies for `dataSync`; runtime Vulkan/OpenGL pipeline compilation is a weaker match — `specialUse` is the accurate category for valid FGS work not matching another type, requires `FOREGROUND_SERVICE_SPECIAL_USE` + manifest `<property>` justification). TargetSdk 35 `dataSync` has a **6-hour total per 24h** background limit then `onTimeout()` is called and service must stop promptly. ([Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types) / [timeout](https://developer.android.com/develop/background-work/services/fgs/timeout) / [Play Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646))
- `POST_NOTIFICATIONS` denied: FGS can start without the permission, but the notification **does not appear in the drawer** — visible only via Task Manager. ([Android Developers](https://developer.android.com/develop/ui/compose/notifications/notification-permission))
- After `ContextCompat.startForegroundService()` the service must call `startForeground()` within about **5 seconds** or the system throws `ForegroundServiceDidNotStartInTimeException`. ([Android Developers](https://developer.android.com/develop/background-work/services))

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
| `app/src/main/AndroidManifest.xml` | ADD `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_SPECIAL_USE`; `PrecompilerService` → `foregroundServiceType="dataSync"`; new `CompilationMonitorService` → `foregroundServiceType="specialUse"` + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="emulatorCompilation"/>`; add `<property>` explanation for Play review |
| `app/src/main/java/com/zenithblue/sambas3/NotificationChannels.kt` **[NEW]** | Extract `ensureCreated(context)` from `MainActivity:34` (channel `rpcsx-progress`, `IMPORTANCE_LOW` minimum for FGS, `setShowBadge(false)`); called by **both** activities **and** by each FGS `onCreate` before `startForeground` (cold-start safety) |
| `app/src/main/res/values/strings.xml` | ADD `compiling_ppu_title`, `compiling_shaders_title`, `compiling_notification_desc`, `time_remaining_format` |
| `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp` | ADD FGS bridge: `g_compileFgCallback`, `setCompileFgListener` JNI, helpers to post PPU snapshot; wire `handle_taskbar_progress` if not noop |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/system_progress.cpp` | MODIFY — extract `build_system_progress_snapshot()` helper, forward snapshot to Android **in addition to** `show_ppu_compile_notification()` (so overlay stays + FGS mirrors) |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKGSRender.cpp` + `GLGSRender.cpp` + `overlay_compile_notification.cpp` | MODIFY — instrument **VK/GL pipeline/cache-miss path** with native BEGIN/END events for FGS; keep `show_shader_compile_notification()` untouched for RSX HUD only (do not put primary FGS callback inside it) |
| `app/src/main/cpp/native-lib.cpp` | ADD dlsym + JNI exports for `setCompileProgressListener` / `notifyCompileProgress` |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt` | ADD `external fun setCompileProgressListener(cb)` + domain constants `COMPILE_DOMAIN_PPU=0, SHADER=1` |
| `app/src/main/java/com/zenithblue/sambas3/ProgressRepository.kt` | EXTEND with `createForeground(Service, notificationId, ...)` + `ServiceCompat.startForeground` helper; keep existing API for non-FGS callers |
| `app/src/main/java/com/zenithblue/sambas3/CompilationMonitorService.kt` | **NEW** — single-anchor FGS (`NOTIF_FGS=2000`), secondary ordinary notifications (`PPU=2001`, `SHADER=2002`) or merged `InboxStyle` on anchor; `START_NOT_STICKY`, `onTimeout()` handling, `activeDomainCount` lifecycle |
| `app/src/main/java/com/zenithblue/sambas3/CompileProgressBridge.kt` **[NEW]** | Kotlin singleton `StateFlow<CompileState>` fed by native callbacks; `CompilationMonitorService` and `RPCSXActivity` both observe it |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` | ADD **dedicated** `compileStatusOverlay : ComposeView` above `PadOverlay` (not inside the `gone` `ingameOverlay`); `activity_rpcs3.xml` new view; chip observes `CompileProgressBridge.state` |
| `app/src/main/java/com/zenithblue/sambas3/MainActivity.kt` + `RPCSXActivity.kt` cold path | Register native progress bridge at `RPCSX.initialized` time; do **not** start idle FGS with placeholder — FGS promotes only on first genuine active compilation event |
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
               android:value="emulatorCompilation"/>
   </service>
   ```
   Document Play justification: runtime PPU/shader compilation is user-initiated (game install / game launch / area traversal) and must remain visible while the user backgrounds the app; `specialUse` is the accurate category because runtime Vulkan pipeline compilation does not match `dataSync` (file processing) or any other enumerated type. `PrecompilerService` (firmware/package install + file preprocessing) correctly uses `dataSync`.
   TargetSdk 35 `dataSync` has a **6-hour per-24h** total limit then `onTimeout()` fires — `PrecompilerService` must implement `onTimeout(startId, fgsType)` to stop promptly. `specialUse` has no such quota but must still stop as soon as compilation is done (only as long as necessary per Play policy).

2. Extract channel creation into `NotificationChannels.kt`:
   ```kotlin
   object NotificationChannels {
     const val RPCSX_PROGRESS = "rpcsx-progress"
     fun ensureCreated(ctx: Context) { /* IMPORTANCE_LOW or DEFAULT, setShowBadge(false), VISIBILITY_PUBLIC */ }
   }
   ```
   Call `ensureCreated(applicationContext)` in: `MainActivity.onCreate`, `RPCSXActivity.onCreate` (cold path), **`CompilationMonitorService.onCreate` before `startForeground`**, and `PrecompilerService.onCreate` before `startForeground`. The previous plan's channel-only-in-`MainActivity` would crash / throw `ForegroundServiceDidNotStartInTimeException` / show a bad notification on cold `RPCSXActivity` entry (adb launch after force-stop).

3. `POST_NOTIFICATIONS` semantics: FGS can start without the grant, but **the notification will not appear in the shade** — it is only visible via Task Manager. Do not gate `startForeground` on `Permission.PostNotifications.checkPermission()`. The FGS helper must ignore that gate (only the non-FGS `ProgressRepository.create` respects it).

### P2 — `ProgressRepository` FGS-capable helper  [REVISED — single anchor]

1. Add `fun createForeground(service: Service, title: String, notificationId: Int, ...): Long` that builds the same `NotificationCompat.Builder` (`rpcsx-progress`, `setCategory(CATEGORY_PROGRESS)`, `setOngoing(true)`) but calls:
   ```kotlin
   ServiceCompat.startForeground(service, notificationId, builder.build(),
       if (service is CompilationMonitorService) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
       else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
   ```
   Store `notificationId` alongside `ProgressWithHandler` so `onProgressEvent` updates via `NotificationManagerCompat.notify` while the service stays foreground. For `CompilationMonitorService`, `notificationId` is **always** `NOTIF_FGS = 2000` (the anchor). Secondaries (`2001`/`2002`) are ordinary ongoing notifications posted via `notify()` only — they do not call `startForeground`.

2. `onProgressEvent` logic: allow `silent=false` for FGS path to always post text (`progr` contains `10m remaining`). For `max>0` determinate, `setProgress(max,value,false)` + `setContentText(text)` (the verbatim `progr`); for indeterminate (`max==0`) `setProgress(0,0,true)`. **Do not** call `ServiceCompat.stopForeground()` per-domain. Only when `activeDomainCount == 0` (both PPU done and shader idle) should the service call `ServiceCompat.stopForeground(service, STOP_FOREGROUND_REMOVE)` + `cancel(anchor)` and `stopSelf()`. This fixes the bug where PPU finishing while shaders are active would demote the whole service.

3. No breaking change to existing `ProgressRepository.create(Context ...)` callers; new FGS callers use the overload. `onTimeout(startId, fgsType)` (Android 14+) should cancel notifications and `stopSelf(startId)` if still foreground.

### P3 — Native → Kotlin bridge for both domains  [REVISED]

1. **Define callback contract in `rpcsx-android.cpp:90` area:**
   ```cpp
   enum class CompileDomain : int { PPU = 0, SHADER = 1 };
   struct PpuProgressSnapshot {
     int percentValue;   // 0..100 — the same value driving the RSX progress bar (from of_1000 / file/module math)
     int percentMax = 100;
     std::string message; // verbatim progr — e.g. "Progress: file 21 of 78, module 24 of 33 (10m remaining)"
     int fileDone, fileTotal;
     int moduleDone, moduleTotal;
   };
   // Kotlin callback: onCompileProgress(domain, percentValue, percentMax, message, fileDone, fileTotal, moduleDone, moduleTotal)
   // For SHADER: value is BEGIN/END sentinel or active count, max==0 (indeterminate), message fixed.
   std::function<void(PpuProgressSnapshot)> g_ppuFgCb;
   std::function<void(bool isBegin)> g_shaderFgCb;
   ```

2. **PPU path — fix the percentage source + ETA extraction:**
   - In `system_progress.cpp`, the displayed percentage is **not** `pdone/ptotal`. It is derived from `of_1000` / weighted file+module math and smoothed `time_left_queue`. The `show_overlay_message` branch can `continue` before the block that builds `progr` and remaining-time, so simply appending a callback after `show_ppu_compile_notification()` would miss or duplicate work.
   - **Refactor:** extract a helper `PpuProgressSnapshot build_system_progress_snapshot(...)` that computes `percentValue`, `progr`, and ETA exactly as the RSX overlay does. Both the RSX UI (`progress_dialog` / `show_ppu_compile_notification`) **and** the Android bridge consume the same snapshot. Then in the `show_overlay_message` branch **and** the normal dialog branch, after the snapshot is built, invoke:
     ```cpp
     if (g_ppuFgCb) g_ppuFgCb(snapshot); // snapshot.message is verbatim progr with "(Xm remaining)"
     ```
     Forward `snapshot.message` verbatim to Kotlin — do not reformat in Kotlin (avoids locale drift). Expose `fileDone/fileTotal/moduleDone/moduleTotal` as well so Android `setProgress` exactly mirrors the native bar.

3. **Shader path — do not use `overlay_compile_notification.cpp` as primary hook:**
   - `overlay_compile_notification.cpp:12 show_shader_compile_notification()` is a 5 s UI cue reached **after** `VKGSRender::get_graphics_pipeline(...)` + `vk::leave_uninterruptible()` + `check_cache_missed()`. It fires only when `show_shader_compilation_hint` is enabled and an overlay manager exists, and its 5 s is notification lifetime, not compilation duration. Do **not** put the Android FGS callback only there.
   - **Instrument the VK/GL pipeline path independently** with native BEGIN/END events:
     - In `VKGSRender.cpp` (around the `get_graphics_pipeline` / `check_cache_missed` block at `:1987-1996`) and `GLGSRender.cpp:825` equivalent, emit `g_shaderFgCb(true/*BEGIN*/)` when a cache miss will trigger compilation, and `g_shaderFgCb(false/*END*/)` when the pipeline is ready / after `check_cache_missed` resolves. Keep `show_shader_compile_notification()` untouched for the RSX HUD. The FGS bridge should coalesce bursts (many BEGINs before first END) into a single active period.
   - For phase 1, shader has **no trustworthy ETA**. The 5 s is not compilation time and there is no async queue with known queued/finished counts in this cue. Do **not** estimate `pending * ~120ms`. Show shaders as **indeterminate**: `setProgress(0,0,true)` + `"Compiling shaders…"` (or `"Compiling shaders (Vulkan pipelines)"`). If a genuine queue is later discovered, compute ETA from measured durations/EWMA.

4. **JVM side `rpcsx-android.cpp:1828`:** implement `_rpcsx_setCompileProgressListener(JNIEnv* env, jobject thiz, jobject callback)` storing a `GlobalRef` to a Kotlin `fun onCompileProgress(domain:Int, value:Long, max:Long, message:String)` (or the richer snapshot signature), invoked via `g_mainThreadProcessor` / main looper. Use `MainThreadProcessor` already present (`g_mainThreadProcessor:940`). Wire `Emu.GetCallbacks().handle_taskbar_progress` if feasible — currently noop at `rpcsx-android.cpp:1449` — otherwise use the direct `g_ppuFgCb`/`g_shaderFgCb` globals.

### P4 — `native-lib.cpp` + `RPCSX.kt` plumbing  [REVISED]

1. `native-lib.cpp:18` add `RPCSXApi` entries:
   ```cpp
   bool (*setCompileProgressListener)(JNIEnv*, jobject);
   // or two setters: setPpuProgressListener / setShaderProgressListener
   ```
   Add `dlsym` lines at `:88-90` and JNI exports `Java_com_zenithblue_sambas3_RPCSX_setCompileProgressListener`. Follow `RPCSXLibrary::Open` null-handle guard pattern (`native-lib.cpp:74`).

2. `RPCSX.kt:72` add:
   ```kotlin
   fun interface CompileProgressCallback { fun onProgress(domain:Int, value:Long, max:Long, message:String?) }
   external fun setCompileProgressListener(callback: CompileProgressCallback)
   companion object { const val COMPILE_DOMAIN_PPU=0; const val COMPILE_DOMAIN_SHADER=1 }
   ```
   Keep `@Keep`. Alternative minimal: two externs `onPpuCompileProgress`/`onShaderCompileProgress` if `fun interface` marshal is cumbersome.

3. `CompileProgressBridge.kt` (new singleton):
   ```kotlin
   object CompileProgressBridge {
     data class CompileState(
       val ppuPercent:Int=0, val ppuMax:Int=100, val ppuMsg:String? = null,
       val ppuActive:Boolean=false, val shaderActive:Boolean=false, val shaderMsg:String? = null,
       val fileDone:Int=0, val fileTotal:Int=0, val moduleDone:Int=0, val moduleTotal:Int=0
     )
     val state = MutableStateFlow(CompileState())
     fun onNativePpu(snapshot: PpuSnapshot) { /* update state, debounce to ≤2/s */ }
     fun onNativeShader(begin:Boolean) { /* ref-count bursts, debounce hide 5s */ }
   }
   ```

### P5 — `CompilationMonitorService` (the FGS)  [REVISED — one anchor]

1. Create `app/src/main/java/com/zenithblue/sambas3/CompilationMonitorService.kt : Service()`:
   - `companion object { const val NOTIF_FGS=2000; const val NOTIF_PPU=2001; const val NOTIF_SHADER=2002; fun ensureStarted(ctx:Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, CompilationMonitorService::class.java)) }`
   - Holds `activeDomainCount` (PPU active + shader active). **Only `NOTIF_FGS` is the FGS notification** (`startForeground(NOTIF_FGS, ..., SPECIAL_USE)`). `NOTIF_PPU` and `NOTIF_SHADER` are secondary ordinary ongoing notifications posted via `NotificationManagerCompat.notify` when their domain is active — or, simpler, merge both lines into the single anchor via `InboxStyle` (`"PPU: 21/78 (10m remaining)"` + `"Shaders: compiling"`). The plan keeps two secondaries for independent cancel semantics, but the service **never** calls `stopForeground()` until `activeDomainCount == 0`.

   - Lifecycle — **no idle placeholder**:
     - `onCreate`: `NotificationChannels.ensureCreated(this)`; register `RPCSX.instance.setCompileProgressListener { domain,value,max,msg -> handle(...) }` via `CompileProgressBridge`. **Do not** call `startForeground` yet.
     - `handle()` posts to Main handler, updates `CompileProgressBridge.state`, and on **first genuine active event** (`ppuActive` becomes true or `shaderActive` becomes true) calls `ServiceCompat.startForeground(this, NOTIF_FGS, buildAnchorNotification(), FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` **within ~5s** of the `startForegroundService()` that the native bridge triggered. If the service was not yet running, `handle()` first calls `ensureStarted(applicationContext)` (which is safe because the user is launching/installing/playing — a user-initiated foreground context).
     - For PPU: every snapshot → update anchor (or `NOTIF_PPU` secondary) via `notify()` with `setProgress(100, percentValue, false)` + `setContentText(snapshot.message)` + `InboxStyle` if merged. On `percentValue==100` or `value<0` → clear PPU active, `cancel(NOTIF_PPU)`, update anchor, decrement `activeDomainCount`. Only if `activeDomainCount==0` → `ServiceCompat.stopForeground(this, STOP_FOREGROUND_REMOVE)` + `stopSelf()`.
     - For shader: `BEGIN` → shaderActive=true, `notify(NOTIF_SHADER)` with `setProgress(0,0,true)` + `"Compiling shaders"` + pulsing indicator. Debounce hide: `Handler.postDelayed(5000)` reset on each new BEGIN so rapid bursts keep the notification without flicker. On `END` (ref-count drops to 0, debounce expires) → clear shaderActive, `cancel(NOTIF_SHADER)`, update anchor, decrement count, stop only if zero.
     - `onStartCommand` → `START_NOT_STICKY`. If the process dies, native emulator/compiler dies too — restarting only the notification is useless and would show a stale placeholder.
     - `onTimeout(startId, fgsType)` (API 34+): `cancelAll()` + `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf(startId)`. Required because targetSdk 35 `dataSync` (used by `PrecompilerService`) enforces the 6h quota, and `specialUse` still needs prompt stop.

2. `PrecompilerService` adjustment:
   - Change `PrecompilerService.start()` from `context.startService(intent)` to `ContextCompat.startForegroundService(context, intent)`.
   - `onCreate`: `NotificationChannels.ensureCreated(this)`.
   - `onStartCommand`: immediately `ServiceCompat.startForeground(this, notifId, builder.build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)` (within ~5s) via `ProgressRepository.createForeground(this, title, requestId.toInt())` instead of `create`. Currently it imports `ServiceCompat`/`ServiceInfo` but never uses them — fix that.
   - `onTimeout` + `stopForeground` on finish (`value==max` or `value<0`). Change `START_STICKY` → `START_NOT_STICKY` (install intents are not meant to be redelivered after process death without the fd).

3. **Do not** start `CompilationMonitorService` at `MainActivity` init with a `"Preparing compilation..."` placeholder merely to avoid a later start restriction. That would show a spurious notification when no compilation exists and weakens the Play justification that FGS runs only as long as necessary. Register the bridge early, but promote to FGS only on real work.

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
   The existing `ingameOverlay` (`:21-25`) is `visibility="gone"` and only shown when `InGameUi.page != Closed` — a chip inside it would be invisible during gameplay. Place `compileStatusOverlay` as a sibling above `padOverlay` with `translationZ = 64f` (or higher than `ingameOverlay`'s `64f` if needed) and `elevation` ordering validated by screenshot. Alternatively, refactor `ingameOverlay` so its `ComposeView` stays visible while only menu content is conditionally rendered — but the separate view is lower-risk.

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
   Respect `WindowInsets` (`applyInsetsToPadOverlay` pattern). Dismisses automatically when `StateFlow` idle. Keep RSX overlay untouched for captures; Compose chip is additive.

### P7 — Strings & icons

1. `strings.xml` add: `<string name="compiling_ppu_title">Compiling PPU Modules</string>`, `<string name="compiling_shaders_title">Compiling shaders</string>`, `<string name="compiling_shaders_desc">Building Vulkan pipelines</string>`, keep `installation_progress` channel name. No new drawables needed — reuse `ic_video`/`memory` vectors if small icon variant wanted, else `ic_sambas3_foreground` as `setSmallIcon`.

## File-Level Change Map

| File | Change | Rationale |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | MODIFY — `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` + `FOREGROUND_SERVICE_SPECIAL_USE`, `PrecompilerService` `dataSync`, `CompilationMonitorService` `specialUse` + `<property>` | P1 FGS declar., correct types |
| `app/src/main/java/com/zenithblue/sambas3/NotificationChannels.kt` | **NEW** — `ensureCreated()` extracted from `MainActivity` | P1 cold-start crash fix |
| `app/src/main/res/values/strings.xml` | MODIFY — 3 new title strings | P7 i18n |
| `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp` | MODIFY — `g_ppuFgCb`/`g_shaderFgCb`, snapshot struct, `_rpcsx_setCompileProgressListener`, wire `handle_taskbar_progress` + forward | P3 PPU/shader bridge |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/system_progress.cpp` | MODIFY — extract `build_system_progress_snapshot()` helper, forward snapshot to Android in both branches | P3 correct % + ETA source |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/overlay_compile_notification.cpp` | KEEP overlay, **do not** add primary FGS callback here | P3 shader HUD stays |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKGSRender.cpp` + `GLGSRender.cpp` | MODIFY — emit BEGIN/END events around `get_graphics_pipeline`/`check_cache_missed` | P3 true shader hook |
| `app/src/main/cpp/native-lib.cpp` | MODIFY — new `RPCSXApi` members, `dlsym`, `JNIEXPORT setCompileProgressListener` | P4 plumbing |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt` | MODIFY — `external fun setCompileProgressListener`, domain constants | P4 |
| `app/src/main/java/com/zenithblue/sambas3/CompileProgressBridge.kt` | **NEW** — `StateFlow<CompileState>` singleton | P4/P5/P6 shared state |
| `app/src/main/java/com/zenithblue/sambas3/ProgressRepository.kt` | MODIFY — `createForeground(Service, notificationId)` overload, anchor vs secondary semantics, `onTimeout` handling | P2 single-anchor |
| `app/src/main/java/com/zenithblue/sambas3/CompilationMonitorService.kt` | **NEW** — `NOTIF_FGS=2000` anchor FGS + `2001`/`2002` secondaries (ordinary), `START_NOT_STICKY`, `onTimeout`, `activeDomainCount` | P5 |
| `app/src/main/java/com/zenithblue/sambas3/PrecompilerService.kt` | MODIFY — `ContextCompat.startForegroundService`, `createForeground`, `START_NOT_STICKY`, `onTimeout` | P5 install FGS |
| `app/src/main/res/layout/activity_rpcs3.xml` | MODIFY — add `compileStatusOverlay` ComposeView above `PadOverlay` | P6 visibility fix |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` | MODIFY — dedicated `compileStatusOverlay` observing `CompileProgressBridge.state` | P6 |
| `app/src/main/java/com/zenithblue/sambas3/MainActivity.kt` + `RPCSXActivity.kt` cold path | MODIFY — register bridge early; promote FGS only on first real event (no idle placeholder) | P5 lifecycle |

## Testing Strategy

Build gates: `./gradlew assembleStandardDebug assemblePlaystoreDebug` and `./gradlew :app:testStandardDebugUnitTest` (existing `ProgressRepository` unit-path stubbed; no NDK units needed).

Manual on `Y5WWBMJVOZSK4HU8` (already has `samba-s3-standard-debug.apk` `141M` + 118M builds; `2a580689`/`7d6afed8` as controls):

- M1 Install flow (ISO `BLUS30441` or `BLUS31584`) → pull shade during `StagedGameInstaller` `Scanning/Building manifest/Committing` → FGS appears as `Installing — 0% → ... → Preparing PPU compilation` with determinate `value/max`; after commit `Compiling PPU Modules — Progress: file 21/78, module 24/33 (10m remaining)` takes over the anchor notification, survives pressing Home (app background) and returns to `GamesScreen`; `adb shell dumpsys notification` shows `ongoing=true, category=progress`, `adb shell dumpsys activity services` shows `isForeground=true, foregroundServiceType=dataSync` for `PrecompilerService`.
- M2 Boot PPU (cold boot game with no cache, including **cold `RPCSXActivity` entry after `adb shell am force-stop`**) → `RPCSXActivity` launches, `Compiling PPU Modules...` chip appears ≤500 ms via `compileStatusOverlay` (same verbatim `progr` as overlay, `percentValue` matches RSX bar), shade anchor shows same `file X of Y` line updating every ~500 ms; `Logcat RPCS3` `LLVM: Compiled module` aligns with increments. Verify `NotificationChannels.ensureCreated` was called before `startForeground` (no crash). Kill game → anchor + PPU secondary clear within 2 s, chip fades; `isForeground=false` 5 s after idle.
- M3 RSX shader trip — launch `GTASAsf*` intro traversal (intro→Grove Street) → every new pipeline shows `Compiling shaders` overlay 5 s **plus** shade `Compiling shaders` indeterminate entry (or merged line on anchor) and `compileStatusOverlay` chip pulse (indeterminate, no ETA); rapid burst keeps notification without flicker (debounce ≤2/s updates, hide debounced 5 s). After burst stops, shader secondary auto-cancels after 5 s; anchor remains only if PPU still active, otherwise service stops.
- M4 Both concurrent — start PPU cold boot then immediately trigger shader burst (enter world) → shade shows anchor `InboxStyle` with both lines (`PPU 24/33 (10m)` + `Shaders: compiling`) or anchor + two secondaries (`2000` + `2001` + `2002`); chip shows `PPU 24/33 (10m)` + shader dot. Verify PPU finishing does **not** demote FGS while shader still active (`dumpsys activity services` still `isForeground=true, specialUse`).
- M5 Permission denied — `adb shell appops set com.zenithblue.sambas3 POST_NOTIFICATION deny` → install FGS and runtime PPU FGS still start (`isForeground=true`) but **do not appear in the shade** — verify they are visible only via **Task Manager / `adb shell dumpsys notification`** and not via drawer; re-allow restores shade visibility. This replaces the previous incorrect "FGS still visible in shade" criterion.
- M6 `POST_NOTIFICATIONS` granted/denied, rotation, `singleTask` bring-to-front, and `6.1.170-GKID` OEM log spam regression: verify no `ForegroundServiceDidNotStartInTimeException` (service promotes within ~5 s of `startForegroundService`), no `ForegroundServiceStartNotAllowedException` on targetSdk 35 (FGS started only while app is foreground / user-initiated), and `onTimeout` is implemented.

## Acceptance Criteria

- [ ] `grep -rn foregroundServiceType AndroidManifest.xml` shows `dataSync` on `PrecompilerService` and `specialUse` on `CompilationMonitorService`; `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_SPECIAL_USE` permissions present; `CompilationMonitorService` has `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">`.
- [ ] `grep -rn NotificationChannels app/src/main/java` shows `ensureCreated` called from `MainActivity`, `RPCSXActivity` cold path, `CompilationMonitorService.onCreate`, and `PrecompilerService.onCreate`.
- [ ] `./gradlew assembleStandardDebug` passes both flavors with `compileSdk 36 / targetSdk 35`.
- [ ] PPU install or cold boot (including cold `RPCSXActivity` after force-stop) shows **persistent** anchor notification `Compiling PPU Modules` with verbatim `Progress: file X of Y, module Z of W (Xm remaining)` from `build_system_progress_snapshot()` (verify `adb shell dumpsys notification | grep -A2 "Compiling PPU"` contains `(.*remaining)` and `percentValue` matches RSX bar), survives `Home` → app background, and `compileStatusOverlay` chip in `RPCSXActivity` mirrors the same `percent/max/msg` (determinate bar moves) — chip is visible **outside** the menu (not inside the `gone` `ingameOverlay`).
- [ ] RSX traversal shows `Compiling shaders` as **indeterminate** (`setProgress(0,0,true)`) with no ETA, plus Compose chip pulsing while RSX 5 s hint is on surface; both clear ≤6 s after shader burst ends. Shader FGS trigger comes from **VK/GL pipeline cache-miss BEGIN/END**, not solely `show_shader_compile_notification()`.
- [ ] When both domains active, anchor `InboxStyle` (or anchor + two ordinary secondaries) is visible and cancelled correctly: `ppu==max` clears PPU line but **does not** call `stopForeground` while shader still active; shader debounce clear does not clear PPU; `stopForeground` only when `activeDomainCount==0`.
- [ ] `adb shell dumpsys activity services | grep CompilationMonitorService` → `isForeground=true, foregroundServiceType=specialUse` during any compilation, `false` 5 s after idle; `PrecompilerService` → `dataSync`; no `ForegroundServiceStartNotAllowedException` / `ForegroundServiceDidNotStartInTimeException` on targetSdk 35.
- [ ] `CompilationMonitorService` uses `START_NOT_STICKY` and implements `onTimeout(startId, fgsType)` to stop promptly; `PrecompilerService.start()` uses `ContextCompat.startForegroundService()` and promotes within ~5 s (not 10 s).
- [ ] Existing RSX overlay `Compiling PPU Modules... / Compiling shaders` still renders on the surface for captures (not removed).
- [ ] Zero emoji, icons are `painterResource` XML vectors or `mipmap/ic_sambas3_foreground`.

## Risks & Mitigations

- **R1 FGS start timeout (~5 s on 34+, not 10 s)** — service must call `startForeground` within ~5 s of `startForegroundService()`. Mitigation: register bridge early, but call `startForeground` only on first genuine event and do it immediately in `handle()` (build anchor notification synchronously). Do not start an idle placeholder at `MainActivity` init.
- **R2 `targetSdk 35` `dataSync` 6-hour quota** — `dataSync` gets a total 6 h per 24 h then `onTimeout()` fires and service must stop. Mitigation: `PrecompilerService` implements `onTimeout`; `CompilationMonitorService` uses `specialUse` (no quota) for runtime compilation; keep `dataSync` only for the install/file-processing flow. Document Play exemption (Play Asset Delivery) does not apply generally.
- **R3 Duplicate `progr` formatting drift** — forward native `progr` verbatim plus `percentValue` from `build_system_progress_snapshot()` instead of re-formatting in Kotlin. Mitigation: single helper consumed by both RSX UI and Android bridge.
- **R4 Shader storm spam** — each pipeline miss could post 60+ notifications/s. Mitigation: debounce shader FGS updates to ≤2/s and debounce hide to 5 s (`Handler.postDelayed`); coalesce BEGIN bursts via ref-count.
- **R5 `ProgressRepository` thundering `onProgressEvent` races** — existing `ConcurrentHashMap` + `Handler.createAsync` serializes to main looper; new FGS path reuses it. Mitigation: per-domain secondary ids prevent `value/max` cross-talk; `synchronized` around `builder.setProgress/setContentText` if `dumpsys` shows tearing; anchor updates are single-threaded via Main handler.
- **R6 Permission `POST_NOTIFICATIONS` denied** — normal `ProgressRepository.create` gates on `hasPermission`; FGS path must not gate `startForeground` but must expect shade invisibility (Task Manager only). Mitigation: FGS `createForeground` ignores the gate; tests verify Task Manager visibility, not shade.
- **R7 Lifetime leaks** — service stays foreground forever if `value==max` missed (emulator killed). Mitigation: observe `Emu.GetStatus() == Stopped` via `RPCSX.getState()` polling every 2 s in service; auto-cancel after 10 s idle even without native finish callback; `onTimeout` is the last resort.
- **R8 Surface vs Compose z-order** — `compileStatusOverlay` must sit above `PadOverlay` but below `HomeMenu` overlay host. Mitigation: new `compileStatusOverlay` sibling above `padOverlay` with `translationZ` ordering validated by screenshot `Y5WWBMJVOZSK4HU8`; separate from the `gone` `ingameOverlay`.

## Handoff to Plan Reviewer

Previously needed validation; now **resolved** per review:

- (a) `foregroundServiceType` — `PrecompilerService=dataSync`, `CompilationMonitorService=specialUse` with `<property>` justification, `onTimeout` handling, 6h quota noted.
- (b) Single vs two notificationIds — **one anchor FGS (`2000`)** + two ordinary ongoing secondaries (`2001`/`2002`) or merged `InboxStyle` on anchor; never independently `stopForeground` per-domain (`activeDomainCount` gate).
- (c) Shader ETA — **indeterminate only** for phase 1; no `pending*120ms`; future ETA only if genuine queue with measured EWMA.
- (d) `handle_taskbar_progress` vs direct `g_compileFgCb` — check wiring at `rpcsx-android.cpp:1449`; prefer shared `build_system_progress_snapshot()` reachable from `system_progress.cpp` without duplicating math; direct global is fallback.
- (e) `MainActivity` vs `RPCSXActivity` ownership — bridge registered in both, FGS promoted only on first real event (no idle placeholder), `NotificationChannels.ensureCreated` in each service before `startForeground`, double `startForeground` avoided via anchor singleton.

## Review Checklist (for next reviewer)

- [ ] `specialUse` justification in manifest `<property>` is user-perceptible (game launch/traversal) and runs only as long as necessary — acceptable for Play review.
- [ ] Cold `RPCSXActivity` (force-stop → `adb shell am start -n .../.RPCSXActivity -e path ...`) does not crash on FGS start (channel created, `startForeground` within 5 s).
- [ ] `POST_NOTIFICATIONS` denied shade-invisibility is documented and tested via `dumpsys` / Task Manager, not drawer assertion.
- [ ] PPU `percentValue` comes from `build_system_progress_snapshot()` (same as RSX bar), not `pdone/ptotal`.
- [ ] Shader FGS is driven by VK/GL cache-miss BEGIN/END, `show_shader_compile_notification()` remains HUD-only.
- [ ] `compileStatusOverlay` is a separate `ComposeView` above `PadOverlay`, not inside the `gone` `ingameOverlay`.
