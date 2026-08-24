# Plan: FGS Persistent Notifications for PPU + RSX Shader Compilation with Time Remaining + In-UI Mirror

> User verbs: *"also use fgs as well notification persistent when compilation both of them happens and time remaining"* + *"can u make RSX Shaders — runtime, in-game RSX overlay (not Android Compose): in ui as well??"*  
> Evidence: screenshot `Y5WWBMJVOZSK4HU8` 2026-08-24 16:30:43 shows `Compiling PPU Modules... Progress: file 21 of 78, module 24 of 33 (10m remaining)` **only** as RSX overlay on `ANativeWindow` (behind `PadOverlay`), no persistent Android notification, and `RSX / Compiling shaders` 5 s hint (`overlay_compile_notification.cpp:12`) never leaves the surface. `PrecompilerService.kt` exists but never calls `startForeground` (`AndroidManifest.xml:29` no `foregroundServiceType`), and `ProgressRepository.kt:93` posts via `NotificationManagerCompat.notify` only. Runtime PPU path (`progress_dialog_server.cpp:40` / `system_progress.cpp:195`) has accurate `10m remaining` math already, but `rpcsx-android.cpp:1111 UiMessageDialog` is a stub, so boot-time PPU falls back to overlay too.

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

## Research Sources

**Internal:**

- `<source: app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp:322-326>` `MAKE_STRING` for `RSX_OVERLAYS_COMPILING_SHADERS/PPU_MODULES` + `PROGRESS_DIALOG_*` strings; `ProgressMessageDialog:997` (routes to `ProgressRepository` when `progressId!=-1`), `UiMessageDialog:1111` stub, `MessageDialog:1125` router, `CompilationQueue:1264` (`push/nextWorkTag/impl:1320` waits `Stopped/Ready`, sets `Running`, calls `ppu_precompile`, finishes with `Progress(...).success(0):1428`), `installIso:2402` → `g_compilationQueue.push`.
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/system_progress.cpp:40-425>` `progress_dialog_server` loop: native dialog vs `show_overlay_message` branch (`call_from_main_thread` not wired on Android, so `MessageDialog` path), PPU cue `show_ppu_compile_notification():236` with 10 ms refresh, `progr` construction (`get_localized_string(PROGRESS_DIALOG_PROGRESS)` + `FILE/MODULE OF` + `REMAINING` with `time_left_queue` smoothing `290-309`).
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/overlay_compile_notification.cpp:12-48>` 5 s shader cue, 20 s PPU cue, both `queue_message(bottom_left, loading_icon24)`.
- `<source: app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKGSRender.cpp:1987-1996>` cache-miss → `show_shader_compile_notification()` gated by `g_cfg.misc.show_shader_compilation_hint`.
- `<source: app/src/main/cpp/native-lib.cpp:22-110>` `RPCSXApi` dlsym table has `processCompilationQueue/startMainThreadProcessor` (:22-23) but no shader callback; `RPCSXLibrary::Open` pattern (:74-119).
- `<source: app/src/main/java/com/zenithblue/sambas3/ProgressRepository.kt>` `onProgressEvent:53` (updates `value/max/message`, calls per-id `Handler`), `create:76` (builds `NotificationCompat.Builder` `rpcsx-progress`, `setProgress(0,0,true)`, `setOngoing(true)`, `notify` via `NotificationManagerCompat` — **never `startForeground`**), per-id `asyncHandler:109` renders `text` as `setContentText` and `value/max` as `setProgress`; failed → `AlertDialogQueue`.
- `<source: app/src/main/java/com/zenithblue/sambas3/PrecompilerService.kt>` current: `onStartCommand:100` creates `ProgressRepository.create` with title `firmware_installation/package_installation`, launches `thread { install(...) }` (`thread:135`), `START_STICKY`, **no `startForeground`, no `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC/…`, no notificationId owned by Service**.
- `<source: app/src/main/java/com/zenithblue/sambas3/RPCSX.kt:82-85>` `processCompilationQueue/startMainThreadProcessor/collectGameInfo` externs.
- `<source: app/src/main/java/com/zenithblue/sambas3/MainActivity.kt:82-88>` starts both native looper threads (`thread { startMainThreadProcessor/processCompilationQueue }`) after `RPCSX.initialize`.
- `<source: app/src/main/AndroidManifest.xml:5,29-43>` permissions `POST_NOTIFICATIONS` only; `<service .PrecompilerService>` lacks `android:foregroundServiceType` and `android:permission FOREGROUND_SERVICE`; `RPCSXActivity` already `configChanges` handled.
- `<source: app/src/main/res/values/strings.xml:57>` `installation_progress` used for `NotificationChannel("rpcsx-progress")` (`MainActivity.kt:35`).
- Device evidence: `rpcsx_backend.log` 16:24:53 firmware install (no FGS), 16:25:34 `librudp.sprx` LLVM compile during `Running` with `Thread [Emulation Join Thread] is too sleepy`, 16:30:43 screenshot proves overlay-only PPU with `10m remaining`.

**External / platform:**

- Android 14+ (targetSdk 35, compileSdk 36) requires `FOREGROUND_SERVICE` permission, `foregroundServiceType` on `<service>`, `ServiceCompat.startForeground(id, notification, type)`, and `POST_NOTIFICATIONS` grant; `NotificationCompat.Builder.setProgress(max, progress, false)` + `setOngoing(true)` + `setCategory(CATEGORY_PROGRESS)` is the canonical persistent pattern (`ctx7` for `ServiceCompat` / `NotificationCompat` if needed).

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
| `app/src/main/AndroidManifest.xml` | ADD `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`, service `foregroundServiceType="dataSync"` (+ `shortService` if needed) |
| `app/src/main/res/values/strings.xml` | ADD `compiling_ppu_title`, `compiling_shaders_title`, `compiling_notification_desc`, `time_remaining_format` |
| `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp` | ADD FGS bridge: `g_compileFgCallback`, `setCompileFgListener` JNI, helpers to post PPU `value/max/progr`; hook `system_progress.cpp` via `Emu.GetCallbacks().handle_taskbar_progress` already wired? else add `call_from_main_thread` shim |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/system_progress.cpp` | MODIFY to forward `progr/value` to Android when `show_overlay_message` branch active (so overlay stays + FGS mirrors) |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKGSRender.cpp` + `GLGSRender.cpp` | MODIFY to forward shader-miss events to Android (counter + last-hit timestamp for remaining estimate) |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/overlay_compile_notification.cpp` | KEEP overlay, also trigger FGS callback (single chokepoint) |
| `app/src/main/cpp/native-lib.cpp` | ADD dlsym + JNI exports for `setCompileProgressListener` / `notifyShaderCompile` |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt` | ADD `external fun setCompileProgressListener(cb)` or two externs `onPpuProgress`/`onShaderCompile` |
| `app/src/main/java/com/zenithblue/sambas3/ProgressRepository.kt` | EXTEND `create` with `asForegroundService: Boolean` + `startForeground` helper; keep existing API for install flow |
| `app/src/main/java/com/zenithblue/sambas3/CompilationMonitorService.kt` | **NEW** single FGS owning two notificationIds (PPU=2001, shader=2002) or one merged id; `startForeground` on first `onPpuProgress/onShaderCompile`, update via `NotificationManagerCompat.notify`, `stopForeground/STOP_FOREGROUND_REMOVE` on completion |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` | ADD Compose mirror chip (`isPpuCompiling/isShaderCompiling + remaining`) observing `CompilationMonitorService.state` via `StateFlow` |
| `app/src/main/java/com/zenithblue/sambas3/MainActivity.kt` | Ensure `CompilationMonitorService` is started at `RPCSX.initialized` and survives `singleTask` brings-to-front |
| `gradle/libs.versions.toml` | no new deps (uses `androidx.core:core 1.12+` already) |

## Implementation Steps

### P1 — Manifest + channel for FGS

1. `AndroidManifest.xml:4` add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>` and `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>` (or `specialUse` if policy demands). Add `android:foregroundServiceType="dataSync"` to `<service android:name=".PrecompilerService">` **and** to new `<service android:name=".CompilationMonitorService" android:foregroundServiceType="dataSync" android:exported="false">`. Keep `tools:targetApi="31"` handling. No new activity entry.
2. Keep channel `rpcsx-progress` (`MainActivity.kt:34` `IMPORTANCE_DEFAULT`, `setShowBadge(false)`) — for FGS it must be at least `LOW`. Ensure `NotificationManagerCompat` channel is created before any FGS start (already in `MainActivity.onCreate`). Document that `POST_NOTIFICATIONS` denied still shows FGS (FGS notifications bypass the gate on 14+, but `ProgressRepository.hasPermission` must not suppress `startForeground`).

### P2 — `ProgressRepository` FGS-capable helper

1. Add `fun createForeground(context: Service, title: String, notificationId: Int, handler: ...): Long` reusing `create` builder but calling `ServiceCompat.startForeground(context, notificationId, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)` immediately; store `notificationId` alongside `ProgressWithHandler` so `onProgressEvent` updates via `NotificationManagerCompat.notify` while the service stays foreground.
2. Keep `onProgressEvent` logic but allow `silent=false` for FGS path to always post text (`progr` contains `10m remaining`). For `max>0` determinate path, `setProgress(max,value,false)` + `setContentText(text)` (the `progr`); for indeterminate (`max==0`) `setProgress(0,0,true)`. On `value==max` or `value<0`, cancel FGS notification via `ServiceCompat.stopForeground` and `NotificationManager.cancel`.
3. No breaking change to existing `ProgressRepository.create(Context ...)` callers (used by `PrecompilerService` install flow); new FGS callers use the overload.

### P3 — Native → Kotlin bridge for both domains

1. **Define callback contract in `rpcsx-android.cpp:90` area:** `std::function<void(jlong value, jlong max, std::string msg, int domain)> g_compileFgCb; enum CompileDomain{PPU=0, SHADER=1};` + `extern "C" bool _rpcsx_setCompileFgListener(void* cb)` or two JNI-thunk functions.
2. **PPU path:** in `system_progress.cpp:260-360` where `progr/value` and `remaining` are final, after `if(show_overlay_message){ if(pdone<ptotal && show_ppu_compilation_hint) { ppu_cue_refs = show_ppu_compile_notification(); } }` also invoke `if(g_compileFgCb) g_compileFgCb(pdone, ptotal, progr, PPU)` (or call via `Emu.GetCallbacks().handle_taskbar_progress` if already exposed — check `setupCallbacks` wiring at `rpcsx-android.cpp:1449 handle_taskbar_progress` which is currently `[](auto...){}` noop; wire it to the same FGS). The `progr` string already contains `(10m remaining)` — forward verbatim so Kotlin does not reformat.
3. **Shader path:** in `overlay_compile_notification.cpp:12 show_shader_compile_notification()` after `queue_message` also invoke `if(g_compileFgCb) g_compileFgCb(1,0,"Compiling shaders", SHADER)` (shader has no `max`, so indeterminate). For richer remaining, add a static `atomic_u32 g_shaderPending` incremented on entry to `VKGSRender.cpp:1994` cache-miss and decremented on `m_prog_buffer->get_graphics_pipeline` completion (or use `check_cache_missed` counter); expose pending count as `value` and estimate remaining as `pending * ~120ms` (tunable) if desired — else keep indeterminate with `setProgress(0,0,true)` + message. At minimum, trigger FGS on/off with 5 s debounce.
4. **JVM side `rpcsx-android.cpp:1828`:** implement `_rpcsx_setCompileProgressListener(JNIEnv* env, jobject thiz, jobject callback)` storing a `GlobalRef` to a Kotlin `fun onCompileProgress(domain:Int, value:Long, max:Long, message:String)`, invoked via `invokeAsync` → `g_mainThreadProcessor` or direct `CallVoidMethod` on main looper. Use `MainThreadProcessor` already present (`g_mainThreadProcessor:940`).

### P4 — `native-lib.cpp` + `RPCSX.kt` plumbing

1. `native-lib.cpp:18` add two `RPCSXApi` entries `bool (*setCompileProgressListener)(JNIEnv*, jobject)` + `void (*notifyCompileProgress)(int domain, long value, long max, jstring msg)` if not using the callback-setter pattern; otherwise a single setter. Add `dlsym` lines at `:88-90` and JNI exports `Java_com_zenithblue_sambas3_RPCSX_setCompileProgressListener` etc. Follow `RPCSXLibrary::Open` null-handle guard pattern (`native-lib.cpp:74`).
2. `RPCSX.kt:72` add `external fun setCompileProgressListener(callback: CompileProgressCallback)` where `fun interface CompileProgressCallback { fun onProgress(domain:Int, value:Long, max:Long, message:String?) }`. Keep `@Keep`. Alternative minimal: two externs `onPpuCompileProgress`/`onShaderCompileProgress` if `fun interface` marshal is cumbersome.
3. Centralize domain constants: `COMPILE_DOMAIN_PPU=0, SHADER=1` in `RPCSX.kt` companion, reused by service and overlay chip.

### P5 — `CompilationMonitorService` (the FGS)

1. Create `app/src/main/java/com/zenithblue/sambas3/CompilationMonitorService.kt : Service()`:
   - `companion object { const val NOTIF_ID_PPU=2001; const val NOTIF_ID_SHADER=2002; fun ensureStarted(ctx:Context)` + `StateFlow<CompileUiState>` } where `data class CompileUiState(val ppuValue:Long, val ppuMax:Long, val ppuMsg:String?, val ppuActive:Boolean, val shaderActive:Boolean, val shaderMsg:String?)`.
   - `onCreate` registers via `RPCSX.instance.setCompileProgressListener { domain,value,max,msg -> handle(domain,value,max,msg) }` (or two listeners). `handle` posts to `Main` handler, updates `StateFlow`, and routes to `ProgressRepository` FGS notifications.
   - For PPU: first `PPU` event → `ProgressRepository.createForeground(this, getString(R.string.compiling_ppu_title), NOTIF_ID_PPU) {...}` then every subsequent `onProgressEvent(id, value,max,msg)` updates that builder (reuse the returned `requestId`). On `isFinished` (`value==max` or `value<0`) → `ServiceCompat.stopForeground(STOP_FOREGROUND_REMOVE)` + `cancel(id)` and clear `ppuActive`. Keep service alive while shader still active; `stopSelf` only when both domains finished and install flows idle.
   - For shader: determinate not needed — use `NOTIF_ID_SHADER` with `setProgress(0,0,true)` + `setContentTitle(compiling_shaders_title)` + `setContentText(msg ?: "Compiling shaders")`. Debounce hide: post delayed `Handler(5000)` reset (matches overlay 5s) so rapid bursts keep notification. On idle → cancel.
   - Merge option: if UX prefers one notification, use `NOTIF_ID=2000` with `InboxStyle` listing both lines (`PPU: 21/78 (10m remaining)` + `Shaders: compiling`). Keep two ids for simplicity unless user feedback says merge.
   - Handle `POST_NOTIFICATIONS` denied: FGS notification still shows (system shows). Do NOT gate `startForeground` on `Permission.PostNotifications`.
2. `PrecompilerService` adjustment: after P1, make it call `ServiceCompat.startForeground` as well (upgrade existing install-notifications to FGS) — either make `PrecompilerService` delegate to `CompilationMonitorService` (share notificationId) or keep separate but both FGS. Simpler: `PrecompilerService.onStartCommand` → `ProgressRepository.createForeground(this, title, requestId.toInt())` instead of `create`. Ensure `stopForeground` on finish.

### P6 — In-UI mirror in `RPCSXActivity`

1. In `RPCSXActivity.onCreate` after `setContent { RPCSXTheme { AppNavHost } }` (or `activity_rpcs3.xml` overlay), add a `ComposeView` top bar observing `CompilationMonitorService.uiState.collectAsState()`. When `ppuActive||shaderActive` show `Surface(tint, 56dp)` with `CircularProgressIndicator` (indeterminate for shader, determinate `ppuValue/ppuMax` for PPU) + `Text(ppuMsg ?: shaderMsg ?: "Compiling...")`. On shader indeterminate, show pulsing dot. On PPU, show `LinearProgressIndicator(progress={ppuValue/ppuMax})` mirroring the overlay's white bar but in Compose.
2. Ensure layer above `PadOverlay` (`elevation 64f` precedent `docs/plans/native-ingame-menu-settings-plan.md:111`) and respects `WindowInsets` (`applyInsetsToPadOverlay` pattern). Dismisses automatically when `StateFlow` idle.
3. Keep RSX overlay untouched for captures; the Compose chip is additive, not replacement.

### P7 — Strings & icons

1. `strings.xml` add: `<string name="compiling_ppu_title">Compiling PPU Modules</string>`, `<string name="compiling_shaders_title">Compiling shaders</string>`, `<string name="compiling_shaders_desc">Building Vulkan pipelines</string>`, keep `installation_progress` channel name. No new drawables needed — reuse `ic_video`/`memory` vectors if small icon variant wanted, else `ic_sambas3_foreground` as `setSmallIcon`.

## File-Level Change Map

| File | Change | Rationale |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | MODIFY — `FOREGROUND_SERVICE` perms, `foregroundServiceType="dataSync"` on both services | P1 FGS declar. |
| `app/src/main/res/values/strings.xml` | MODIFY — 3 new title strings | P7 i18n |
| `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp` | MODIFY — `g_compileFgCb`, `_rpcsx_setCompileProgressListener`, wire `handle_taskbar_progress` + forward in `MessageDialog`/`CompilationQueue` paths | P3 PPU bridge |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/system_progress.cpp` | MODIFY — after `show_ppu_compile_notification()` also invoke `g_compileFgCb` with `pdone/ptotal/progr` | P3 time-remaining source |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/overlay_compile_notification.cpp` | MODIFY — call FGS callback after `queue_message` | P3 shader trigger |
| `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/VK/VKGSRender.cpp` + `GLGSRender.cpp` | MODIFY — optional pending counter forward, keep single chokepoint if preferred | P3 alt |
| `app/src/main/cpp/native-lib.cpp` | MODIFY — new `RPCSXApi` members, `dlsym`, `JNIEXPORT setCompileProgressListener` | P4 plumbing |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt` | MODIFY — `external fun setCompileProgressListener`, domain constants | P4 |
| `app/src/main/java/com/zenithblue/sambas3/ProgressRepository.kt` | MODIFY — `createForeground(Service, notificationId)` overload + `stopForeground` handling | P2 |
| `app/src/main/java/com/zenithblue/sambas3/CompilationMonitorService.kt` | **NEW** — FGS, two notificationIds, StateFlow, debounce for shader | P5 |
| `app/src/main/java/com/zenithblue/sambas3/PrecompilerService.kt` | MODIFY — upgrade to FGS via `createForeground` | P5 |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` | MODIFY — Compose chip observing `CompilationMonitorService.uiState` | P6 |
| `app/src/main/java/com/zenithblue/sambas3/MainActivity.kt` | MODIFY — `CompilationMonitorService.ensureStarted` after `RPCSX.initialized` | P5 lifecycle |

## Testing Strategy

Build gates: `./gradlew assembleStandardDebug assemblePlaystoreDebug` and `./gradlew :app:testStandardDebugUnitTest` (existing `ProgressRepository` unit-path stubbed; no NDK units needed).

Manual on `Y5WWBMJVOZSK4HU8` (already has `samba-s3-standard-debug.apk` `141M` + 118M builds; `2a580689`/`7d6afed8` as controls):

- M1 Install flow (ISO `BLUS30441` or `BLUS31584`) → pull shade during `StagedGameInstaller` `Scanning/Building manifest/Committing` → FGS appears as `Installing — 0% → ... → Preparing PPU compilation` with determinate `value/max`; after commit `Compiling PPU Modules — Progress: file 21/78, module 24/33 (10m remaining)` takes over the same notification, survives pressing Home (app background) and returns to `GamesScreen` with progress chip still visible; `adb shell dumpsys notification` shows `ongoing=true, category=progress`, `adb shell dumpsys activity services` shows `isForeground=true`.
- M2 Boot PPU (cold boot game with no cache) → `RPCSXActivity` launches, `Compiling PPU Modules...` chip appears ≤500 ms (same `progr` as overlay), shade entry shows same `file X of Y` line updating every ~500 ms; `Logcat RPCS3` `LLVM: Compiled module` aligns with progress increments. Kill game → notification clears within 2 s, chip fades.
- M3 RSX shader trip — launch `GTASAsf*` intro traversal (intro→Grove Street) → every new pipeline shows `Compiling shaders` overlay 5 s **plus** shade `Compiling shaders` indeterminate entry and `RPCSXActivity` chip pulse; rapid burst keeps notification without flicker (debounce). After traversal stops, shade entry auto-cancels after 5 s.
- M4 Both concurrent — start PPU cold boot then immediately trigger shader burst (enter world) → shade shows two entries (`2001` + `2002`) or merged `InboxStyle` both lines; chip shows `PPU 24/33 (10m)` + shader dot.
- M5 Permission denied — `adb shell appops set com.zenithblue.sambas3 POST_NOTIFICATION deny` → install FGS still visible (system FGS bypass), PPU runtime FGS still visible; re-allow restores normal priority.
- M6 `POST_NOTIFICATIONS` granted/denied, rotation, `singleTask` bring-to-front, and `6.1.170-GKID` OEM log spam regression check (no `ForegroundServiceDidNotStartInTimeException`; service calls `startForeground` within `onCreate`+`handle` < 5 s).

## Acceptance Criteria

- [ ] `grep -rn foregroundServiceType AndroidManifest.xml` shows `dataSync` on `CompilationMonitorService` (+ `PrecompilerService`) and `FOREGROUND_SERVICE` permissions present.
- [ ] `./gradlew assembleStandardDebug` passes both flavors with `compileSdk 36 / targetSdk 35`.
- [ ] PPU install or cold boot shows **persistent** shade entry `Compiling PPU Modules` with `Progress: file X of Y, module Z of W (Xm remaining)` text exactly as `system_progress.cpp:260` computes (verify `adb shell dumpsys notification | grep -A2 "Compiling PPU"` contains `(.*remaining)`), survives `Home` → app background, and chip overlay in `RPCSXActivity` mirrors the same `value/max/msg` (determinate progress bar moves).
- [ ] RSX traversal shows **second** persistent entry `Compiling shaders` (indeterminate, `setProgress(0,0,true)`) plus Compose chip pulsing while overlay 5 s hint is on surface; both clear ≤6 s after shader burst ends.
- [ ] When both domains active, two entries (or merged `InboxStyle`) are visible and correctly cancelled independently (`ppu==max` clears PPU, shader debounced clear).
- [ ] `adb shell dumpsys activity services | grep CompilationMonitorService` → `isForeground=true` during any compilation, `false` 5 s after idle; no `ForegroundServiceStartNotAllowedException` on `targetSdk 35` device.
- [ ] Existing overlay `Compiling PPU Modules... / Compiling shaders` still renders on the surface for captures (not removed).
- [ ] Zero emoji, icons are `painterResource` XML vectors or `mipmap/ic_sambas3_foreground`.

## Risks & Mitigations

- **R1 FGS start timeout (10 s on 34+)** — call `startForeground` in `onCreate` with placeholder notification before native callback arrives. Mitigation: service starts at `MainActivity` init and again at first native event; fallback placeholder `Preparing compilation...` indeterminate.
- **R2 `targetSdk 35` `dataSync` policy** — `dataSync` is exempt but some OEMs restrict. Mitigation: keep `shortService` fallback meta-data and test on `duchamp` (`6.1.170-GKID`) which already shows the PPU overlay; log `adb shell dumpsys notification` if blocked.
- **R3 Duplicate `progr` formatting drift** — forward native `progr` verbatim instead of re-formatting in Kotlin to avoid locale/desync. Mitigation: pass `std::string progr` as `jstring` unchanged.
- **R4 Shader storm spam** — each pipeline miss could post 60+ notifications/s. Mitigation: debounce shader FGS updates to ≤2/s and debounce hide to 5 s (`Handler.postDelayed`).
- **R5 `ProgressRepository` thundering `onProgressEvent` races** — existing `ConcurrentHashMap` + `Handler.createAsync` serializes to main looper; new FGS path reuses it. Mitigation: per-domain notificationIds prevent `value/max` cross-talk; add `synchronized` around `builder.setProgress/setContentText` if `dumpsys` shows tearing.
- **R6 Permission `POST_NOTIFICATIONS` denied** — normal `ProgressRepository.create` gates on `hasPermission`; FGS path must not. Mitigation: FGS `createForeground` ignores the gate; only `setSilent` handling respects it.
- **R7 Lifetime leaks** — service stays foreground forever if `value==max` missed (emulator killed). Mitigation: observe `Emu.GetStatus() == Stopped` via `RPCSX.getState()` polling every 2 s in service; auto-cancel after 10 s idle even without native finish callback.
- **R8 Surface vs Compose z-order** — Compose chip must sit above `PadOverlay` but below `HomeMenu` overlay host. Mitigation: place chip in `RPCSXActivity`'s `ingameOverlay` `ComposeView` with `elevation 64f` sibling ordering validated by screenshot `Y5WWBMJVOZSK4HU8` (current screenshot already shows dimmed overlay path).

## Handoff to Plan Reviewer

Needs reviewer validation: (a) `foregroundServiceType` choice (`dataSync` vs `specialUse` justification for Play policy), (b) single vs two notificationIds UX (current choice: two, for independent cancel), (c) shader remaining estimation scope (indeterminate now, pending-counter later), (d) `handle_taskbar_progress` wiring vs direct `g_compileFgCb` reachability from `system_progress.cpp` without new global, (e) `MainActivity` vs `RPCSXActivity` service ownership to avoid double `startForeground` on rapid `kill→boot`.

