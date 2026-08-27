# Samba S3 — Complete Kotlin In-Game Emulator Menu Conversion Plan

**Target:** Samba S3 Android in-game menu — replace RPCSX/RPCS3 native Home Menu with complete Kotlin/Compose implementation
**Primary repository:** `JICA98/samba-s3` (also `anomalyco/samba-s3` fork)
**Samba baseline reviewed:** `07c7929` (2026-08-27) — current HEAD `07c7929 fix: first-time PPU 9/9 PREPARING hang`
**RPCSX submodule baseline:** `e8ae1481ab7ba04d5c6bef89dd852aabba2c88ff`
**Upstream RPCS3 comparison:** `6567a5a2f8ab47a89db395d6b47a7b59b23d6960` (2026-08-26)
**Plan slug:** `complete-kotlin-ingame-menu-conversion`
**Author:** @planner (Muse Spark)
**Date:** 2026-08-27
**Non-negotiable product requirement:** the RPCSX/RPCS3 native/backend `home_menu_dialog` must **never** be shown on Android. All visible in-game emulator UI must be Kotlin/Compose. The C++ backend remains responsible only for emulator state, settings persistence, screenshots, recording, savestates, trophies/friends data, restart/shutdown, and other headless operations.

---

## 0. Executive Decision

Do **not** skin `home_menu_dialog`. Do **not** render the core Home Menu and overlay Compose. Do **not** keep a `Core Home Menu` fallback row.

1. Make Android declare **frontend ownership** of the Home Menu.
2. Route **every** Home/PS/menu request to Kotlin.
3. Prevent **every** Android path that can create `rsx::overlays::home_menu_dialog`.
4. Expose backend Home Menu operations as small headless C APIs.
5. Wrap those APIs through Samba's existing `dlopen`/`dlsym` JNI bridge (`app/src/main/cpp/native-lib.cpp`).
6. Build the complete menu and all sub-pages in Compose.
7. Give Kotlin **exclusive** controller/touch input ownership while the menu is open.
8. Preserve backend pause, Save/Discard, screenshot/recording, savestate, trophy, restart, and graceful shutdown semantics.
9. Make optional features **capability-driven** so the Kotlin menu survives future RPCSX/RPCS3 backend updates.

Desired visual/feature split (from user screenshots):
- **Screenshot A (keep):** Samba/Kotlin visual language — centered dark card (`RPCSXColors.surfaceElevated`), game title header, icon rows, dimmed game/touch controls.
- **Screenshot B (absorb):** Backend feature contract — Resume, Settings, Trophies, Screenshot, Recording, Save State, Restart, Exit, plus conditional Friends and backend Save/Discard/controller behavior.
- The row currently labelled **CORE HOME MENU** must disappear permanently.

---

## 1. Research Findings That Drive the Implementation

### 1.1 Samba already has a Kotlin menu, but it is only a launcher shell

**File:** `app/src/main/java/com/zenithblue/sambas3/ui/ingame/EmulationMenu.kt:50-87`

```kotlin
enum class InGamePage { Closed, Menu, GlobalSettings, ConfigureGame }
private enum class EmulationMenuAction { Resume, ConfigureGame, GlobalSettings, CoreHomeMenu, Exit }
EmulationMenuAction.CoreHomeMenu -> { onDismiss(); onOpenCoreHomeMenu() }
```

**Caller:** `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt:204-207`

```kotlin
private fun openCoreHomeMenu() {
    if (RPCSX.getState() != EmulatorState.Running) return
    RPCSX.instance.openHomeMenu()
}
```

The current Compose menu deliberately transfers UI ownership back to C++.

**Required result:** Delete `CoreHomeMenu` from the Android frontend. No user-facing action and no fallback path may call `RPCSX.openHomeMenu()`.

### 1.2 The backend can open the native Home Menu even if the Kotlin row is removed

This is the most important hidden requirement.

**Pinned RPCSX Android:** `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp:2031-2035`

```cpp
extern "C" void _rpcsx_openHomeMenu() {
  if (auto padThread = pad::get_pad_thread(true)) {
    padThread->open_home_menu();
  }
}
```

### Trigger A — virtual PS button

`app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp:1849-1853`:

```cpp
if (btn.m_outKeyCode == CELL_PAD_CTRL_PS && btn.m_pressed) {
  if (auto padThread = pad::get_pad_thread(true)) {
    padThread->open_home_menu();
  }
}
```

Even if this block is removed, `app/src/main/cpp/rpcsx/rpcs3/Input/pad_thread.cpp:471-475` separately watches PS state:

```cpp
if ((ps_button_pressed && !m_ps_button_pressed) ||
    pad::g_home_menu_requested.exchange(false))
{
    open_home_menu();
}
```

Therefore deleting `_rpcsx_openHomeMenu` alone is insufficient.

### Trigger B — Surface detach

`app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp:2039-2081` — current `event == 2` path and `event == 0` resume-any-paused path. No explicit `open_home_menu()` on surface detach today in this pin (verified), but spec warns some forks added it — verify and ensure it never calls `open_home_menu()`.

**Required result:** Install a **frontend Home-Menu ownership hook** in `pad_thread` so a Home/PS request is consumed and forwarded to Android instead of creating `home_menu_dialog`. Remove any Android-specific native menu creation on surface detach.

### 1.3 The native Home Menu owns pause/resume behavior

`app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu.cpp:164-170` (open) and `99-113` (close):

```cpp
if (g_cfg.misc.pause_during_home_menu) {
    Emu.BlockingCallFromMainThread([](){ Emu.Pause(false,false); });
}
// ... on exit:
if (g_cfg.misc.pause_during_home_menu) {
    Emu.BlockingCallFromMainThread([](){ Emu.Resume(); });
}
```

It also attaches its own input thread `overlayman.attach_thread_input(...)` at `overlay_home_menu.cpp:155-162`.

**Required result:** Kotlin menu must replace both responsibilities: frontend-menu session owns optional pause/resume; Kotlin owns input while visible. Do not only replace drawing.

### 1.4 Current Kotlin menu does not own physical gamepad input

`app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt:319-417` — `onKeyDown`, `onKeyUp`, `onGenericMotionEvent` always update `gamePadState` and call `RPCSX.instance.overlayPadData(...)` even while a Compose menu page is visible.

`app/src/main/java/com/zenithblue/sambas3/overlay/PadOverlay.kt:309-417` has a useful **touch** menu-mode gate (`OverlayTouchPolicy.shouldAcceptOverlayTouch`), but the Activity's **physical controller** path remains live.

**Consequences while Kotlin menu is open:**
- Controller input reaches the running game.
- Backend sees PS/Home.
- Held buttons/sticks leak through.
- Opening/closing menu leaves stale pressed state.

**Required result:**

```
GAMEPLAY
  physical controller -> emulator
  touch overlay        -> emulator
KOTLIN_MENU
  physical controller -> Kotlin menu
  touch overlay        -> blocked/dimmed
  emulator             -> receives neutral pad frame only
```

### 1.5 Backend Settings has Save/Discard semantics; current Kotlin screen does not

Pinned `app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_page.cpp:177-211`:

- **Square / Save:** `Emu.GetCallbacks().save_emu_settings(); *m_config_changed = false;`
- **Triangle / Discard:** `g_cfg.from_string(g_backup_cfg.to_string()); Emu.GetCallbacks().update_emu_settings(); *m_config_changed = false;`

Contrast `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp:3141-3167` `_rpcsx_settingsSet`:

```cpp
if (!root->from_json(value, !Emu.IsStopped())) return false;
Emulator::SaveSettings(g_cfg.to_string(), "");
return true; // every successful edit is persisted immediately
```

Current `app/src/main/java/com/zenithblue/sambas3/ui/ingame/InGameSettingsPage.kt:41-95` loads tree via `settingsGet("")` and reuses `AdvancedSettingsScreen` which calls `settingsSet` directly — immediate persistence. It also records via `GameSettingsOverrides.recordGlobal` (app-side per-title ladder), but no transactional save/discard.

**Required result:** Add a real in-game settings transaction: snapshot `g_cfg` when Settings page starts; apply edits transiently; Square/Save commits via `save_emu_settings` callback; Triangle/Discard restores snapshot and calls `update_emu_settings`; back navigation prompts if dirty.

### 1.6 Backend action semantics that must be preserved

`app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_main_menu.cpp:98-151`:

- **Screenshot:** `g_user_asked_for_screenshot = true; return page_navigation::exit;` (`RSXThread.cpp:41-42`, `VKPresent.cpp:762`, `GLPresent.cpp:280` consume it).
- **Recording:** `g_user_asked_for_recording = true; return page_navigation::exit;`
- **Restart:** `Emu.CallFromMainThread([]{ Emu.SetContinuousMode(true); Emu.Restart(false); }); return exit;`
- **Exit Game:** `Emu.CallFromMainThread([]{ Emu.GracefulShutdown(false,true); }); return stay;` (no confirm page in this fork).

Samba currently exits with `RPCSX.instance.kill()` at `RPCSXActivity.kt:213`. Not parity.

**Required result:** Expose exact operations as headless Android C APIs and call them from Compose. Exit must become `GracefulShutdown`.

### 1.7 Backend Savestate behavior

`app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_savestate.cpp:12-62`:

- Save State or Save State And Exit when `g_cfg.savestate.suspend_emu` true.
- Load last savestate when available (`boot_last_savestate(true)` test, `false` boot).
- Suspend-false path sets `after_kill_callback = Restart` + `SetContinuousMode(true)` + `Kill(false,true)`.

Current upstream RPCS3 (2026-08-26) has evolved to up to four reloadable slots — API must already support multiple slots even if current pin reports one.

### 1.8 Trophies are real backend data, not a static UI

`app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/Trophies/overlay_trophy_list_dialog.cpp:241-431`:

- Loads `TROPUSR.DAT`, `TROPCONF.SFM`, `TROP%03d.PNG`, parses `title-name` + per-`trophy` nodes, respects `hidden` + `unlocked` filtering, grade mapping, progress `%`.
- Main menu only adds Trophies when `current_trophy_name` non-empty (`overlay_home_menu_main_menu.cpp:72-96`).

**Required result:** Reuse that loading logic as a headless service returning JSON; build Compose trophy screen. Do not open `trophy_list_dialog`.

### 1.9 Friends is conditional and backed by RPCN

`app/src/main/cpp/rpcsx/rpcs3/Emu/RSX/Overlays/FriendsList/overlay_friends_list_dialog.cpp:691-696`:

```cpp
bool friends_list_dialog::rpcn_configured() {
    cfg_rpcn cfg; cfg.load();
    return !cfg.get_npid().empty() && !cfg.get_password().empty();
}
```

Supports friends online/offline + presence, received/sent requests, blocked, remove/accept/reject/cancel; `unblock` is TODO (`// TODO` at line 301).

**Required result:** Friends row capability-gated; expose friend data/actions headlessly; render in Kotlin; do not create `friends_list_dialog`. Do not claim unblock works until backend implements it.

---

## 2. Source Baselines and References

Worker must read these before editing. All paths are relative to repo root unless noted.

### Samba S3

- Current Kotlin in-game menu — `app/src/main/java/com/zenithblue/sambas3/ui/ingame/EmulationMenu.kt:1-259`
- Current in-game settings bridge — `app/src/main/java/com/zenithblue/sambas3/ui/ingame/InGameSettingsPage.kt:1-95`
- Activity / physical controller / existing core-menu invocation — `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt:1-457` (openCoreHomeMenu at 204-207, openInGamePage 189-201, menuToggle 120-130, pad handlers 304-417)
- Kotlin native declarations — `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt:1-203` (`openHomeMenu` at 93, `overlayPadData` at 85, `settingsGet/Set` at 88-89)
- Touch pad menu-mode — `app/src/main/java/com/zenithblue/sambas3/overlay/PadOverlay.kt:1-589` (setMenuMode 510-541, touch listener 308-417, fade 489-501)
- Touch policy — `app/src/main/java/com/zenithblue/sambas3/overlay/OverlayTouchPolicy.kt:1-13`
- Dynamic JNI loader — `app/src/main/cpp/native-lib.cpp:1-452` (RPCSXApi 18-59, dlsym 82-134, wrappers 174-452)
- Submodule declaration — `.gitmodules:1-6`
- Strings — `app/src/main/res/values/strings.xml:91-115` (ingame_* at 98-103)
- Layout — `app/src/main/res/layout/activity_rpcs3.xml` (PadOverlay, osc_toggle, menu_toggle, ingameOverlay ComposeView)
- Advanced settings UI — `app/src/main/java/com/zenithblue/sambas3/ui/settings/SettingsScreen.kt` (AdvancedSettingsScreen) + `AdvancedSettingsNav.kt`

### Pinned RPCSX backend (submodule `app/src/main/cpp/rpcsx`)

- Android exports — `android/src/rpcsx-android.cpp:1-3343` (overlayPadData 1830-1867, surfaceEvent 2039-2081, settingsGet/Set 3131-3167, callbacks 1583-1758)
- Pad Home Menu trigger — `rpcs3/Input/pad_thread.cpp:438-478` and `open_home_menu` 649-684; header `rpcs3/Input/pad_thread.h:1-117` (`m_home_menu_open` 77, `g_home_menu_requested` 88)
- Home Menu lifetime/pause/input — `rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu.cpp:1-180`
- Home Menu actions — `rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_main_menu.cpp:1-156`
- Home Menu settings — `rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_settings.cpp:1-143`
- Home Menu Save/Discard/controller navigation — `rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_page.cpp:1-287`
- Savestate page — `rpcs3/Emu/RSX/Overlays/HomeMenu/overlay_home_menu_savestate.cpp:1-62`
- Trophy data source — `rpcs3/Emu/RSX/Overlays/Trophies/overlay_trophy_list_dialog.cpp:1-432`
- Friends/RPCN behavior — `rpcs3/Emu/RSX/Overlays/FriendsList/overlay_friends_list_dialog.cpp:1-698`

### Current upstream RPCS3 comparison

Use pinned RPCSX semantics as compatibility baseline. Use current RPCS3 only to shape forward-compatible APIs; do not copy newer behavior unless core helpers are also ported and tested.

---

## 3. Target Feature Matrix

| Feature | Current Samba Kotlin | Pinned backend | Required Kotlin result |
|---|---:|---:|---:|
| Resume | yes | yes | yes |
| Configure Game | yes | no | keep as Samba extension |
| Backend-compatible Settings | partial/global generic | yes (transactional) | full + transactional |
| Friends | no | conditional (RPCN) | conditional Kotlin page |
| Trophies | no | conditional | conditional Kotlin page |
| Take Screenshot | no | yes | yes |
| Start/Stop Recording | no | yes | yes |
| Save State | no | yes | yes |
| Load State | no | last state | capability-driven slots |
| Restart Game | no | yes | yes |
| Exit Game | yes (kill) | graceful shutdown | graceful backend action |
| Pause while menu open | incomplete | config-driven | exact parity |
| Physical controller menu input | no | yes | yes |
| Touch blocking | mostly | n/a | preserve/fix neutralization |
| Square Save | no | yes | yes |
| Triangle Discard | no | yes | yes |
| L1/R1 page jump | no | yes | yes |
| Native Home Menu visible | yes/fallback | yes | **never on Android** |
| Toggle Fullscreen | no | not in pin | capability false on Android; API supports future |
| Upstream 4 state slots | no | not in pin | API shape supports it |

---

## 4. Architecture

Four layers:

```
Compose UI
  |
  v
InGameMenuController / state + navigation + input (Kotlin)
  |
  v
RPCSX.kt JNI facade  (app/src/main/java/com/zenithblue/sambas3/RPCSX.kt)
  |
  v
Samba native-lib.cpp dynamic bridge  (app/src/main/cpp/native-lib.cpp, dlopen+dlsym)
  |
  v
librpcsx-android.so headless frontend-menu API  (android/src/rpcsx-android.cpp)
```

Renderer/backend must not push UI pixels for Home Menu functionality.

### 4.1 Kotlin classes to create

```
app/src/main/java/com/zenithblue/sambas3/ui/ingame/
  InGameMenuModels.kt          // capability / savestate / trophy / friends data classes
  InGameMenuController.kt      // stack + selectedIndex + capabilities + open/resume/push/back
  InGameMenuInputRouter.kt     // MenuInput sealed + pad mapping + analog repeat
  EmulationMenu.kt             // rewritten host (LazyColumn, conditional rows, no CoreHomeMenu)
  InGameSettingsPage.kt        // rewritten transactional Settings
  InGameTrophiesPage.kt        // NEW
  InGameFriendsPage.kt         // NEW
  InGameSaveStatePage.kt       // NEW
  InGameMenuComponents.kt      // row + header + footer helpers
```

Do not put native command logic directly inside composables.

### 4.2 Backend data/action boundary (v1)

Return one capability JSON document so Kotlin does not guess availability.

Example:

```json
{
  "apiVersion": 1,
  "frontendOwnsHomeMenu": true,
  "pauseDuringMenu": true,
  "screenshot": true,
  "recording": { "supported": true, "active": false },
  "trophies": { "available": true },
  "friends": { "available": false },
  "savestate": { "supported": true, "suspendMode": false, "loadSlots": [0] },
  "fullscreen": false
}
```

Do not make Kotlin infer features from RPCSX version strings.

---

## 5. Phase 1 — Make Kotlin the Only Home Menu Owner

Land before adding feature pages. At this point native Home Menu must already be unreachable on Android.

### 5.1 Add a frontend Home request hook in pad handling

**Files:** `rpcs3/Input/pad_thread.h`, `rpcs3/Input/pad_thread.cpp`

Generic hook (no `#ifdef ANDROID` inside the core loop):

```cpp
// pad_thread.h — inside namespace pad
using home_menu_request_handler = std::function<bool()>;
void set_home_menu_request_handler(home_menu_request_handler handler);
bool dispatch_home_menu_request();
```

Replace at `pad_thread.cpp:471-475`:

```cpp
// BEFORE:
if ((ps_button_pressed && !m_ps_button_pressed) ||
    pad::g_home_menu_requested.exchange(false))
{
    open_home_menu();
}
// AFTER:
if ((ps_button_pressed && !m_ps_button_pressed) ||
    pad::g_home_menu_requested.exchange(false))
{
    if (!pad::dispatch_home_menu_request()) // true = frontend consumed
    {
        open_home_menu();
    }
}
```

Desktop/non-Android: no handler registered → fallback unchanged. Do not set `m_home_menu_open = true` for Kotlin menu; that variable means RSX native menu owns input/rendering. Add separate `g_frontend_menu_active` if backend needs it (see Phase 2).

### 5.2 Add a frontend event callback to RPCSX Android

Parallel to `setCompileProgressListener` infrastructure (`rpcsx-android.cpp:3188-3234`, `native-lib.cpp` pattern, `RPCSX.kt:115-134`).

Kotlin:

```kotlin
@Keep
fun interface FrontendEventCallback {
    fun onEvent(type: Int, payload: String?)
}
const val FRONTEND_EVENT_HOME_REQUESTED = 1
const val FRONTEND_EVENT_RECORDING_CHANGED = 2
const val FRONTEND_EVENT_SCREENSHOT_RESULT = 3
const val FRONTEND_EVENT_EMULATOR_ACTION_ERROR = 4
// RPCSX.kt:
external fun setFrontendEventListener(callback: FrontendEventCallback?): Boolean
```

Native: global ref + `JavaVM*` + `jmethodID`, thread-attach, post via `g_mainThreadProcessor` (same as `dispatchCompileEventToJava` at `rpcsx-android.cpp:1067-1105`). When `dispatch_home_menu_request()` runs and Android owns the menu: emit `HOME_REQUESTED`, return true, never invoke `home_menu_dialog`. Kotlin posts to main thread.

### 5.3 Delete the immediate Home Menu call from `_rpcsx_overlayPadData`

Delete at `rpcsx-android.cpp:1849-1853`:

```cpp
if (btn.m_outKeyCode == CELL_PAD_CTRL_PS && btn.m_pressed) {
  if (auto padThread = pad::get_pad_thread(true)) {
    padThread->open_home_menu();
  }
}
```

Keep PS state on virtual pad; the normal pad_thread rising-edge path now routes through the handler — single source of truth for on-screen PS, Android gamepad, and native handlers.

### 5.4 Remove Home Menu creation from `_rpcsx_surfaceEvent`

At `rpcsx-android.cpp:2039-2081`, ensure surface lifecycle manages only surface/pause state. Do not call `padThread->open_home_menu()` from surface detach (verify none exists today; guard against future re-add). Fix pause ownership — current `event == 0` resumes any paused emulator, which can accidentally resume a Kotlin-menu-owned pause.

```cpp
static atomic_t<bool> g_surface_pause_owned = false;
static atomic_t<bool> g_frontend_menu_active = false; // see Phase 2
// event == 2: if (Emu.IsRunning()) { Emu.Pause(); g_surface_pause_owned = true; }
// event == 0: if (g_surface_pause_owned.exchange(false) && Emu.IsPaused() && !g_frontend_menu_active) Emu.Resume();
```

### 5.5 Retire `_rpcsx_openHomeMenu` from Android

Remove `extern "C" void _rpcsx_openHomeMenu()` once frontend event path works, then remove from Samba:

- `RPCSX.kt:93` `external fun openHomeMenu()`
- `native-lib.cpp:33` pointer, `:105` dlsym, `:233-241` JNI
- `RPCSXActivity.kt:204-207` `openCoreHomeMenu()`, `onOpenCoreHomeMenu` param in `EmulationOverlayHost`, `EmulationMenuAction.CoreHomeMenu` at `EmulationMenu.kt:87`, `R.string.ingame_core_home_menu` at `strings.xml:101` if unused, `ic_home_menu` drawable if genuinely unused.

Acceptance grep (must return **0** hits in app sources):

```bash
rg -n "openHomeMenu|_rpcsx_openHomeMenu|CoreHomeMenu|ingame_core_home_menu" app/src
```

Generic RPCSX/RPCS3 desktop `home_menu_dialog` source files may remain in submodule; Android frontend-owned path must not reach them.

---

## 6. Phase 2 — Backend Frontend-Menu Session: Pause/Resume Ownership

Add to `android/src/rpcsx-android.cpp`:

```cpp
static atomic_t<bool> g_frontend_menu_active = false;
static atomic_t<bool> g_frontend_menu_paused_emu = false;

extern "C" bool _rpcsx_beginFrontendMenu();
extern "C" void _rpcsx_endFrontendMenu(bool resume_if_owned);
extern "C" bool _rpcsx_isFrontendMenuOpen();

extern "C" bool _rpcsx_beginFrontendMenu() {
    if (!Emu.IsRunning()) return false;
    if (g_frontend_menu_active.exchange(true)) return true; // idempotent
    g_frontend_menu_paused_emu = false;
    if (g_cfg.misc.pause_during_home_menu) {
        Emu.BlockingCallFromMainThread([](){
            if (Emu.IsRunning()) Emu.Pause(false,false);
        });
        g_frontend_menu_paused_emu = Emu.IsPaused();
    }
    return true;
}
extern "C" void _rpcsx_endFrontendMenu(bool resume_if_owned) {
    if (!g_frontend_menu_active.exchange(false)) return;
    const bool owns = g_frontend_menu_paused_emu.exchange(false);
    if (resume_if_owned && owns && Emu.IsPaused()) {
        Emu.BlockingCallFromMainThread([](){
            if (Emu.IsPaused()) Emu.Resume();
        });
    }
}
extern "C" bool _rpcsx_isFrontendMenuOpen(){ return g_frontend_menu_active.load(); }
```

Adapt `atomic_t` API to the tree's type if needed.

Why `resume_if_owned`:
- Resume/Back: `endFrontendMenu(true)`
- Restart/Exit/SaveStateAndExit/Load: `endFrontendMenu(false)` — do not resume for one frame before stopping/restarting.

---

## 7. Phase 3 — Extend Samba's Dynamic Native Bridge

Every new backend function requires **all three** (keep optional so old `.so` does not crash):

1. function pointer in `RPCSXApi` (`native-lib.cpp:18-59`)
2. `dlsym` in `RPCSXLibrary::Open` (`native-lib.cpp:82-134`)
3. JNI wrapper (null-guarded) + `external fun` in `RPCSX.kt`

Example:

```cpp
struct RPCSXApi {
  // ...
  bool (*beginFrontendMenu)();
  void (*endFrontendMenu)(bool);
  bool (*isFrontendMenuOpen)();
  std::string (*inGameMenuCapabilities)();
  bool (*requestScreenshot)();
  bool (*toggleRecording)();
  bool (*restartGame)();
  bool (*gracefulShutdown)();
};
// dlsym:
result.beginFrontendMenu = reinterpret_cast<decltype(beginFrontendMenu)>(dlsym(handle,"_rpcsx_beginFrontendMenu"));
// JNI:
extern "C" JNIEXPORT jboolean JNICALL Java_com_zenithblue_sambas3_RPCSX_beginFrontendMenu(JNIEnv*,jobject){
  return rpcsxLib.beginFrontendMenu ? rpcsxLib.beginFrontendMenu() : false;
}
```

---

## 8. Phase 4 — Define the Headless In-Game Action API

In `android/src/rpcsx-android.cpp`:

```cpp
extern "C" std::string _rpcsx_inGameMenuCapabilities();
extern "C" bool _rpcsx_requestScreenshot();
extern "C" bool _rpcsx_toggleRecording();
extern "C" bool _rpcsx_restartGame();
extern "C" bool _rpcsx_gracefulShutdown();
extern "C" std::string _rpcsx_getSaveStateInfo();
extern "C" bool _rpcsx_saveState(int slot);
extern "C" bool _rpcsx_loadSaveState(int slot);
extern "C" std::string _rpcsx_getCurrentTrophies();
extern "C" std::string _rpcsx_getFriends();
extern "C" bool _rpcsx_friendAction(std::string_view action, std::string_view username);
extern "C" bool _rpcsx_beginInGameSettingsSession();
extern "C" bool _rpcsx_settingsSetTransient(std::string_view path, std::string_view value);
extern "C" bool _rpcsx_commitInGameSettingsSession();
extern "C" bool _rpcsx_discardInGameSettingsSession();
extern "C" bool _rpcsx_hasDirtyInGameSettings();
```

JSON for structured data (already used for settings).

---

## 9. Phase 5 — Implement Backend Action Wrappers Exactly

### 9.1 Screenshot

```cpp
extern atomic_t<bool> g_user_asked_for_screenshot;
extern "C" bool _rpcsx_requestScreenshot(){
  if (!Emu.IsRunning() && !Emu.IsPaused()) return false;
  g_user_asked_for_screenshot = true; return true;
}
```

Kotlin: close menu without leaking input, call request, optional toast. Do not use Android `View` screenshot — backend captures emulator frame (`VKPresent.cpp:762/796`, `GLPresent.cpp:280/292`).

### 9.2 Recording

```cpp
extern atomic_t<bool> g_user_asked_for_recording;
extern "C" bool _rpcsx_toggleRecording(){
  if (!Emu.IsRunning() && !Emu.IsPaused()) return false;
  g_user_asked_for_recording = true; return true;
}
```

Expose `recording.active` in capabilities if reliable. Compose label: `START RECORDING` / `STOP RECORDING` when known; otherwise use localized toggle label without pretending state is known.

### 9.3 Restart

```cpp
extern "C" bool _rpcsx_restartGame(){
  if (Emu.IsStopped()) return false;
  Emu.CallFromMainThread([](){ Emu.SetContinuousMode(true); Emu.Restart(false); });
  return true;
}
```

Before invoke: `endFrontendMenu(false)`, hide overlay, neutralize input, do **not** finish Activity.

### 9.4 Exit Game

Replace `RPCSX.instance.kill()` (currently at `RPCSXActivity.kt:213`).

```cpp
extern "C" bool _rpcsx_gracefulShutdown(){
  if (Emu.IsStopped()) return false;
  Emu.CallFromMainThread([](){ Emu.GracefulShutdown(false,true); });
  return true;
}
```

Kotlin: confirm dialog → `endFrontendMenu(false)` → graceful shutdown → observe native `Stopped` → clear `RPCSX.activeGame` → `finish()`. Keep `kill()` only as watchdog fallback.

---

## 10. Phase 6 — Savestate Service and Compose Page

### 10.1 Backend model

```json
{
  "supported": true,
  "suspendMode": false,
  "canSave": true,
  "slots": [{ "slot": 0, "exists": true, "label": "Last save state" }]
}
```

`slot` is a backend identifier; Kotlin must not assume 0..3.

### 10.2 Pinned implementation

Preserve `overlay_home_menu_savestate.cpp:20-57` semantics:

- `suspend_mode == false` save: `Emu.after_kill_callback = Restart; SetContinuousMode(true); Kill(false,true);`
- `suspend_mode == true`: kill with savestate, no restart.
- Load: `boot_last_savestate(false)` helper (`Emu/savestate_utils.cpp:346`).

Do not replace with Kotlin file copying.

### 10.3 Forward compatibility

Upstream RPCS3 now supports up to 4 slots — if forward-ported, do as a **separate core commit**; preserve pinned behavior when helper unavailable; `_rpcsx_getSaveStateInfo()` reports actual slots.

### 10.4 Compose page — `InGameSaveStatePage.kt`

- Save State / Save State And Exit toggle by `suspendMode`.
- List each `loadSlots` entry.
- Destructive confirm only where backend is destructive.
- Controller selection, Circle/Back returns to main menu, loading closes menu without resuming old execution.

---

## 11. Phase 7 — Real Transactional In-Game Settings

### 11.1 Do not reuse `_rpcsx_settingsSet` for menu edits

It persists immediately via `Emulator::SaveSettings(g_cfg.to_string(), "")` at `rpcsx-android.cpp:3165`.

Add transient setter:

```cpp
extern "C" bool _rpcsx_settingsSetTransient(std::string_view path, std::string_view valueString){
  nlohmann::json value;
  try { value = nlohmann::json::parse(valueString); } catch(...) { return false; }
  auto* root = find_cfg_node(&g_cfg, path);
  if (!root) return false;
  return root->from_json(value, !Emu.IsStopped()); // live apply, but DO NOT persist
}
```

### 11.2 Session snapshot

```cpp
static std::mutex g_ingame_settings_mutex;
static std::optional<std::string> g_ingame_settings_backup;
static bool g_ingame_settings_dirty = false;
extern "C" bool _rpcsx_beginInGameSettingsSession(){
  std::lock_guard lock(g_ingame_settings_mutex);
  if (g_ingame_settings_backup) return true;
  g_ingame_settings_backup = g_cfg.to_string();
  g_ingame_settings_dirty = false; return true;
}
// after every transient set:
g_ingame_settings_dirty = g_ingame_settings_backup && *g_ingame_settings_backup != g_cfg.to_string();
```

### 11.3 Save

```cpp
extern "C" bool _rpcsx_commitInGameSettingsSession(){
  std::lock_guard lock(g_ingame_settings_mutex);
  if (!g_ingame_settings_backup) return false;
  Emu.GetCallbacks().save_emu_settings(); // uses title ID (rpcsx-android.cpp:1619-1620)
  g_ingame_settings_backup = g_cfg.to_string();
  g_ingame_settings_dirty = false; return true;
}
```

Do not silently substitute `Emulator::SaveSettings(..., "")` — that uses empty title ID vs the Home Menu callback's `Emu.GetTitleID()`.

### 11.4 Discard

```cpp
extern "C" bool _rpcsx_discardInGameSettingsSession(){
  std::lock_guard lock(g_ingame_settings_mutex);
  if (!g_ingame_settings_backup) return false;
  g_cfg.from_string(*g_ingame_settings_backup);
  Emu.GetCallbacks().update_emu_settings();
  g_ingame_settings_dirty = false; return true;
}
```

Clear snapshot when page closes.

### 11.5 UI behavior

Backend native page (`overlay_home_menu_page.cpp:244-246`, dialog at 177-211): Cross activate/edit, Circle back, Square Save, Triangle Discard, D-pad/left stick nav, L1/R1 jump 10.

Replicate actions, show footer while dirty: `□ SAVE    △ DISCARD`. Touch needs visible Save/Discard buttons, not only glyphs. On dirty back:

```
Save changes?
[Save] [Discard] [Cancel]
```

Do not silently discard or persist.

---

## 12. Phase 8 — Backend Settings Feature Parity

Pinned `overlay_home_menu_settings.cpp:23-141` groups:

**Audio:** Master Volume, Audio Backend, Audio Buffering, Buffer Duration, Time Stretching, Threshold

**Video:** Frame Limit, Anisotropic Override, Output Scaling, RCAS Sharpening (when Vulkan+FSR), Stretch To Display

**Input:** Background Input, Keep Pads Connected, Show PS Move Cursor, Camera Flip (when qt), Pad Mode, Pad Sleep, fake Move rotation cone H/V

**Advanced:** Preferred SPU Threads, Max CPU Preemptions, Accurate RSX Reservation, Sleep Timers Accuracy, Max SPURS Threads, Driver Wake-Up Delay, VBlank Frequency, VBlank NTSC

**Overlays:** Trophy popups, RPCN popups, Shader/P P U compilation hint, Autosave/autoload hint, Pressure/Analog/Mouse toggle hints

**Performance Overlay:** enabled, framerate/frametime graphs, detail levels, datapoint counts, update interval, position, center X/Y, margins, font size, opacity

**Debug:** debug overlay, input debug overlay, disable video output, texture LOD bias

**Implementation rule:** Do not hard-code guessed `@@` paths in many composables. Prefer:

- **Preferred:** backend returns Home Menu settings schema/allowlist with exact `@@` paths, type, options, availability.
- **Acceptable:** one centralized Kotlin mapping generated from exact `g_cfg` node names after verifying `settingsGet("")` JSON.

Reuse `AdvancedSettingsScreen` components where useful, but do not expose unrelated launcher-only pages.

**Product naming:** Rename current `Global Settings` row to `SETTINGS` (transactional backend page). Keep `CONFIGURE GAME` as Samba extension (`GameConfigureScreen` / `GameSettingsOverrides`). If a separate true Global Settings is desired, treat it as a Samba extension, not the backend Home Menu Settings action.

---

## 13. Phase 9 — Trophies as Kotlin UI

### 13.1 Extract backend data service

Do not instantiate `rsx::overlays::trophy_list_dialog` from Android. Move/reuse logic from `trophy_list_dialog::load_trophies` (`overlay_trophy_list_dialog.cpp:241-310` + `reload` 312-430) into a reusable helper, e.g. `rpcs3/Emu/NP/trophy_query.h` or RPCSX Android service.

Return JSON:

```json
{
  "available": true,
  "gameName": "Example Game",
  "trophySet": "NPWRxxxxx_00",
  "total": 42, "unlocked": 17, "percent": 40,
  "trophies": [
    {
      "id": 0, "name": "First trophy", "description": "Description",
      "grade": "bronze", "unlocked": true, "hidden": false,
      "platinumRelevant": true, "iconPath": "/.../TROP000.PNG"
    }
  ]
}
```

For locked hidden trophy, do not leak name/description — backend masks with `Hidden trophy` / `This trophy is hidden` (`HOME_MENU_TROPHY_HIDDEN_*` at `rpcsx-android.cpp:593-594`, logic at `trophy_list_dialog.cpp:380-399`).

### 13.2 Compose page — `InGameTrophiesPage.kt`

- Header: game name + unlocked/total + percent.
- `LazyColumn` with icon, name, grade, description, locked/unlocked style, hidden behavior matching backend.
- Square toggles Show/Hide Hidden; L1/R1 jumps 10; Circle/Back returns.
- Icons are local backend files — load off main thread; prefer existing image infra; do not add a large library just for this page unless already used.

---

## 14. Phase 10 — Friends / RPCN Page

Only show if backend says RPCN configured (`friends_list_dialog::rpcn_configured()`).

Return e.g.:

```json
{
  "available": true, "page": "friends",
  "friends": [{ "username": "user", "online": true, "presenceTitle": "Game", "presenceStatus": "Playing"}],
  "requestsReceived": [], "requestsSent": [], "blocked": []
}
```

Expose commands `remove_friend`, `accept_request`, `reject_request`, `cancel_request`, `unblock` — only advertise if backend implements it. Currently `unblock` is TODO (`overlay_friends_list_dialog.cpp:299-302`).

Compose: Friends/Requests/Blocked tabs, online friends first (matching backend), confirmation dialogs, Square changes page/tab, Cross selects primary, Triangle rejects invite where applicable, L1/R1 list jump.

---

## 15. Phase 11 — Rebuild the Compose Menu State Model

Current `EmulationMenu.kt:50` `enum InGamePage { Closed, Menu, GlobalSettings, ConfigureGame }` is too small.

```kotlin
sealed interface InGamePage {
    data object Main : InGamePage
    data object ConfigureGame : InGamePage
    data object Settings : InGamePage
    data object Trophies : InGamePage
    data object Friends : InGamePage
    data object SaveStates : InGamePage
}
class InGameMenuController(private val core: RPCSX = RPCSX.instance) {
    val stack = mutableStateListOf<InGamePage>()
    val capabilities = mutableStateOf(InGameMenuCapabilities.EMPTY)
    val selectedIndex = mutableIntStateOf(0)
    val isOpen get() = stack.isNotEmpty()
    fun openMain() { /* begin session + push Main */ }
    fun push(page: InGamePage) {}
    fun back(): Boolean { /* page-specific dirty handling */ return true }
    fun resume() { /* close + resume-owned pause */ }
}
```

Do not let every composable independently call `RPCSX`.

---

## 16. Phase 12 — Main Kotlin Menu Layout

Keep Screenshot A's visual language, but capable of containing the full feature set.

**Recommended order:**

```
RESUME
CONFIGURE GAME
SETTINGS
FRIENDS                  [conditional]
TROPHIES                 [conditional]
TAKE SCREENSHOT
START/STOP RECORDING     [conditional]
SAVE STATE               [conditional]
RESTART GAME
EXIT GAME
```

Remove `CORE HOME MENU` permanently.

**Layout changes required** — current `EmulationMenu.kt:146-149` is capped near 420 dp wide / 480 dp high and was designed for five rows; complete menu will not fit on landscape phones:

- Fixed/pinned game-title header.
- `LazyColumn` for rows (not `Column` + hardcoded dividers).
- Max width ~420 dp, max height based on available landscape height (not hard 480 dp).
- Auto-scroll selected controller row into view.
- Safe-area/cutout padding.
- 48–56 dp minimum touch targets, controller focus highlight, arrow for sub-pages, action glyph for direct commands.

---

## 17. Phase 13 — Controller Input Ownership and Navigation

**File:** `InGameMenuInputRouter.kt`

### 17.1 Activity routing rule

At the very top of controller handlers in `RPCSXActivity.kt:319-406`:

```kotlin
if (inGameMenuController.isOpen) {
    return inGameMenuInputRouter.onKeyDown(keyCode, event)
}
// ... otherwise sendGamepadData()
```

Only gameplay mode calls `sendGamepadData()` (`RPCSXActivity.kt:408-417`).

### 17.2 Menu mapping

```kotlin
sealed interface MenuInput {
    data object Up: MenuInput; data object Down: MenuInput
    data object Left: MenuInput; data object Right: MenuInput
    data object Confirm: MenuInput   // Cross / A
    data object Back: MenuInput      // Circle / B / Android Back
    data object Square: MenuInput; data object Triangle: MenuInput
    data object PageUp: MenuInput    // L1
    data object PageDown: MenuInput  // R1
    data object Home: MenuInput
}
```

Use Samba's configured bindings (`InputBindingPrefs`) when possible, not assumptions based only on Xbox labels.

### 17.3 Analog repeat

Left stick: deadzone ~0.5, emit one edge event, initial repeat ~300 ms, repeat ~80–120 ms, reset only after returning inside deadzone. Do not feed continuous stick values into both game and menu.

### 17.4 Home/PS behavior

All sources converge: touch PS, Android controller PS/Home, native DS3/DS4/DualSense PS, toolbar `menuToggle` → **Kotlin menu opens/toggles**, never `home_menu_dialog`.

---

## 18. Phase 14 — Neutralize Gameplay Input Correctly

Opening a modal menu must send a clean frame to the core. Current `State` centers sticks at 127 (`PadOverlay.kt:38-42`, `RPCSXActivity.kt:399-402`).

```kotlin
private fun neutralizePhysicalPad() {
    gamePadState = State()
    usesAxisL2 = false; usesAxisR2 = false
    RPCSX.instance.overlayPadData(0,0, 127,127, 127,127)
}
```

Add to `PadOverlay.kt`:

```kotlin
fun cancelActiveInputsAndNeutralize()
```

Must: `ACTION_CANCEL` active buttons/sticks, clear digital state, center analog, clear `floatingSticks`, send one neutral backend frame, then enter menu mode. Do not only dim the overlay (`setMenuMode` today at 510-541 only dims and cancels floating sticks, but does not send a neutral `overlayPadData` frame — add it).

### 18.1 Prevent held-button leaks on close

If user opens/closes menu while holding a button/stick, do not immediately deliver held input to game. Maintain re-arm gate:

```
menu closes → wait until all buttons/triggers released AND sticks in deadzone → gameplay input re-armed
```

Avoids accidental Cross/Start actions when confirming Resume.

---

## 19. Phase 15 — Correct Open/Close Ordering

Current `menuToggle` at `RPCSXActivity.kt:120-130` checks `Running` first — problematic if `pause_during_home_menu` makes state become `Paused`.

```kotlin
if (inGameMenuController.isOpen) { inGameMenuController.resume(); return@setOnClickListener }
if (RPCSX.getState() != EmulatorState.Running) return@setOnClickListener
inGameMenuController.openMain()
```

**Open:** verify Running → neutralize → `beginFrontendMenu()` → load capabilities → enter `PadOverlay` menu mode → show Compose → focus selection.

**Resume / normal close:** hide Compose → clear selection/repeat jobs → exit `PadOverlay` menu mode → `endFrontendMenu(true)` → wait for controller neutral before re-arming.

**Restart / Exit / Load / Suspend-save:** neutralize → hide Compose → `endFrontendMenu(false)` → invoke action → do not re-arm.

---

## 20. Phase 16 — Fix Activity Lifecycle Interactions

Audit `onPause`, `onResume`, `onStop`, `surface callbacks`, `onDestroy` (`RPCSXActivity.kt:49-457` + `GraphicsFrame` + `rpcsx-android.cpp:2039-2081`), `onDestroy` at 268-302.

Rules:
1. Surface loss never opens native menu.
2. Surface pause and frontend-menu pause have independent ownership flags.
3. Surface reattach never resumes a frontend-menu-owned pause.
4. Destroying Activity while menu open must not leave `g_frontend_menu_active` stuck — backend clears on `Stopped`/Kill.
5. Recreated Activity queries `isFrontendMenuOpen()` and either reattaches or explicitly ends stale session without unsafe resume.
6. Graceful shutdown while menu open clears frontend-menu/session state.

Add backend cleanup on `Stopped`/Kill path: `g_frontend_menu_active = false; g_frontend_menu_paused_emu = false;` and clear settings-session snapshots.

---

## 21. Phase 17 — Capability Model in Kotlin

```kotlin
data class InGameMenuCapabilities(
    val apiVersion: Int,
    val frontendOwnsHomeMenu: Boolean,
    val screenshot: Boolean,
    val recordingSupported: Boolean,
    val recordingActive: Boolean?,
    val trophiesAvailable: Boolean,
    val friendsAvailable: Boolean,
    val savestate: SaveStateCapabilities?,
    val fullscreen: Boolean
)
```

Parsing failures degrade safely to: Resume, Configure Game, Settings (if supported), Exit. Missing optional native symbols must not crash. If `frontendOwnsHomeMenu == false`, log compatibility error and use Kotlin subset — do not expose a button that opens the old native menu.

---

## 22. Phase 18 — Preserve Configure Game Without Mixing Scopes

`GameConfigureScreen.kt` / `GameConfigureOverlay` + `GameSettingsOverrides.kt` keep as Samba extension. Do not let backend Home Menu Settings and Configure Game write through two independent paths simultaneously. After either page commits: refresh relevant settings tree, update override bookkeeping, avoid stale cached JSON. If `GameSettingsOverrides.recordGlobal/recordGame` persists for restart, ensure it does not duplicate/undo the backend Settings transaction. Worker must trace the existing per-title/global ladder (`GameSettingsOverrides.kt:198-276`, `InGameSettingsPage.kt:86-89`) before changing persistence rules.

---

## 23. Phase 19 — Strings, Icons, Accessibility

Add strings (instead of hardcoded English):

```
ingame_settings, ingame_friends, ingame_trophies,
ingame_take_screenshot, ingame_start_recording, ingame_stop_recording,
ingame_save_state, ingame_restart_game, ingame_exit_game,
ingame_save, ingame_discard, ingame_show_hidden_trophies, ...
```

Reuse: `ic_play`, `ic_settings`, `ic_stop`, `ic_save`, `ic_restore`, `ic_video`, `ic_star`, `tune`, `memory`. Retire `ic_home_menu` if no longer used. Every menu item: contentDescription, controller focus, touch state, disabled state where capability says unavailable. Do not use only color to communicate disabled/selected.

---

## 24. Phase 20 — Do Not Accidentally Reintroduce Backend Overlays

Core still needs native overlays for PS3 guest dialogs (`cellMsgDialog`, `cellSaveData`, trophy popups etc. at `rpcsx-android.cpp:1222-1354`). Requirement is specifically that **emulator Home Menu and its converted sub-UIs** are Kotlin-owned.

Explicitly prohibit Android from creating:

```cpp
manager->create<rsx::overlays::home_menu_dialog>()
manager->create<rsx::overlays::trophy_list_dialog>() // for converted path
manager->create<rsx::overlays::friends_list_dialog>() // for converted path
```

Other guest/system overlays must be evaluated separately before changing them.

---

## 25. Backend/Submodule Landing Strategy

This work spans **two repositories** logically: Samba Android frontend/bridge + RPCSX backend submodule.

Do not leave `app/src/main/cpp/rpcsx` dirty and commit only parent.

Workflow:
1. Create/use a writable RPCSX fork/branch for Samba-specific Android APIs.
2. Implement and commit backend changes there.
3. Build/test `librpcsx-android.so`.
4. Update Samba's submodule pointer to the new backend commit.
5. If submodule URL must point to a Samba-owned fork, update `.gitmodules` deliberately.
6. Commit parent changes after backend commit exists.
7. Record both SHAs in PR description.

Do not copy RPCSX source files into the app as a workaround.

---

## 26. Suggested Commit Sequence for the Worker

### Backend commit 1 — frontend Home ownership
- pad Home request callback/hook; `set_home_menu_request_handler` / `dispatch_home_menu_request`
- Android frontend event listener (`setFrontendEventListener`)
- Remove `_rpcsx_overlayPadData` direct `open_home_menu`
- Remove surface-detach native menu (verify) + fix pause ownership
- `begin/end/isFrontendMenuOpen` pause ownership
- **No visible feature changes yet** — but native Home Menu must already be unreachable.

### Samba commit 1 — JNI/frontend ownership
- New optional dlsym symbols + null-safe JNI + `RPCSX.kt` APIs
- Register `FrontendEventCallback` → post `HOME_REQUESTED` to main thread → `InGameMenuController.openMain()`
- Remove `openHomeMenu` / `CoreHomeMenu` row; input neutralization; input router gate
- Verify `rg -n "openHomeMenu"` returns 0 in `app/src`

### Backend commit 2 — headless main actions
- `inGameMenuCapabilities`, `requestScreenshot`, `toggleRecording`, `restartGame`, `gracefulShutdown`, savestate queries/actions

### Samba commit 2 — full main menu
- New rows, `LazyColumn` scrolling, controller focus, confirmations, savestate subpage

### Backend commit 3 — settings transaction/schema
- `begin/transient-set/commit/discard/hasDirty/settings schema` (if schema chosen)

### Samba commit 3 — settings parity
- Transactional page, Save/Discard UI, controller shortcuts, dirty back prompt

### Backend commit 4 — trophy/friends data services
- `getCurrentTrophies`, `getFriends`, `friendAction` — JSON adapters, no dialog creation

### Samba commit 4 — trophy/friends pages
- Compose UI, conditional rows, hidden toggle, RPCN actions

### Final commit — cleanup/tests/docs
- Strings, unused bridge deletion, tests, comments, acceptance grep, diagnostics

---

## 27. Test Plan

### 27.1 Kotlin JVM tests

**Menu state:**
- `closed -> open Main`; push/pop each page; back from sub-page; back from main resumes; dirty Settings back does not discard silently; destructive action closes without resume.

**Capability parser:**
- Full document; missing fields; old backend/no symbols; malformed JSON; trophies/friends conditional visibility; savestate one-slot and multi-slot documents.

**Input router:**
- D-pad navigation; Cross confirm; Circle back; Square Save; Triangle Discard; L1/R1 jump; held-key repeat; analog deadzone; menu-open input never invokes gameplay sender.

**Neutralization:**
- Digital buttons cleared; analog centers restored; triggers cleared; game input not re-armed until controller neutral.

### 27.2 PadOverlay tests

Extend `app/src/test/java/com/zenithblue/sambas3/overlay/OverlayTouchPolicyTest.kt` (and existing menu plans' tests):

- Entering menu cancels active touches.
- Entering menu sends neutral frame.
- No floating stick spawns in menu mode.
- Menu mode consumes every touch.
- Exiting restores previous alpha/visibility.

### 27.3 Native tests / debug assertions

Add helpers where practical:

- Frontend handler returning true prevents `open_home_menu`.
- `begin/end` idempotent.
- Pause ownership only resumes owned pause.
- Surface pause does not resume menu-owned pause.
- Settings snapshot Save/Discard.
- Capabilities reflect trophy/friends availability.
- Savestate JSON matches backend state.

Minimal logging (behind normal debug level):

```
FRONTEND_MENU home-request consumed
FRONTEND_MENU begin pausedByMenu=...
FRONTEND_MENU end resume=...
FRONTEND_MENU action=screenshot
FRONTEND_MENU action=restart
```

Never log every controller frame.

### 27.4 Instrumented/manual Android matrix

**Menu entry paths** (every time: only Compose menu):
- Toolbar `menu_toggle`; on-screen PS; Bluetooth gamepad Home/PS if exposed; native USB DS3/DS4/DualSense; Android Back; touch outside card; rotate/recreate if not locked; app background/foreground.

**Pause setting** — both `pause_during_home_menu = false` and `true`: opening, nested pages, background/foreground, resume, restart, exit, screenshot, savestate.

**Input leak** — hold each while opening/closing: Cross, Circle, Start, D-pad, L2/R2, left/right stick. No stale gameplay action after close.

**Feature actions:** screenshot produces backend screenshot; recording toggles; save/load state; restart returns to game; exit gracefully reaches Stopped; Settings Save persists; Settings Discard restores; trophy hidden toggle; Friends operations if RPCN configured.

**Screen sizes:** phone landscape, tablet landscape, cutout/notch, high DPI, gesture/3-button nav. Complete list must remain scrollable and controller-accessible.

---

## 28. Build/Verification Commands

```bash
./gradlew :app:assembleStandardDebug
./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest
./gradlew :app:lintDebug
# native submodule build (path per build_rpcsx.sh / docs):
./build_rpcsx.sh
readelf -Ws app/src/main/cpp/rpcsx/build/**/librpcsx-android.so 2>/dev/null | \
  grep -E "_rpcsx_(beginFrontendMenu|inGameMenuCapabilities|requestScreenshot|toggleRecording|restartGame|gracefulShutdown)"
```

If lint has unrelated pre-existing failures, report separately; do not hide new failures.

---

## 29. Hard Acceptance Criteria

### Visible UI
- [ ] The only emulator Home Menu visible on Android is Kotlin/Compose.
- [ ] `CORE HOME MENU` no longer exists.
- [ ] Resume works.
- [ ] Configure Game still works.
- [ ] Settings works with Save/Discard.
- [ ] Trophies appears only when available and is Kotlin.
- [ ] Friends appears only when RPCN configured and is Kotlin.
- [ ] Screenshot works.
- [ ] Recording works.
- [ ] Save/load state works according to backend capabilities.
- [ ] Restart works.
- [ ] Exit uses graceful shutdown.

### Native-menu exclusion
- [ ] App code contains no call to `RPCSX.openHomeMenu`.
- [ ] `native-lib.cpp` contains no required `_rpcsx_openHomeMenu` bridge (only null-safe optional if kept for old cores).
- [ ] `_rpcsx_overlayPadData` does not directly call `padThread->open_home_menu()`.
- [ ] `_rpcsx_surfaceEvent` does not call `open_home_menu()`.
- [ ] Pad-thread Home/PS request is consumed by frontend handler on Android.
- [ ] Native `home_menu_dialog` cannot be produced through normal Android menu/PS/surface paths.

### Input
- [ ] Physical controller routed exclusively to Compose while menu open.
- [ ] Touch overlay blocked while menu open.
- [ ] Neutral frame sent on menu entry.
- [ ] No held-button/stick leak on menu exit.
- [ ] PS/Home opens/toggles Kotlin menu.

### Pause/lifecycle
- [ ] `pause_during_home_menu=true` pauses and resumes exactly once.
- [ ] `false` leaves emulation running.
- [ ] Surface recreation does not open native menu.
- [ ] Surface recreation does not resume a menu-owned pause.
- [ ] Restart/Exit/Suspend-save never briefly resume due to menu close.

### Settings
- [ ] Editing does not immediately persist via old `_rpcsx_settingsSet`.
- [ ] Save persists.
- [ ] Discard restores runtime values.
- [ ] Dirty back navigation is explicit.
- [ ] Full backend Home Menu settings groups are represented or deliberately capability-hidden.

### Compatibility
- [ ] Missing new optional symbols on older core fail gracefully (no crash, limited menu).
- [ ] Capability JSON is versioned (`apiVersion`).
- [ ] Savestate UI does not assume fixed slot count.
- [ ] RPCSX submodule pointer references a committed backend change.

---

## 30. Explicit Anti-Patterns — Worker Must Not Do These

1. Keep `CORE HOME MENU` as a hidden/debug fallback in production UI.
2. Call native `home_menu_dialog` and cover it with Compose.
3. Only remove `RPCSX.openHomeMenu()` while leaving PS and surface native triggers.
4. Forward controller input to game and Compose simultaneously.
5. Depend only on `PadOverlay.setMenuMode(true)` — it does not solve `RPCSXActivity` physical controller forwarding.
6. Use `kill()` as normal Exit Game parity.
7. Implement Screenshot using Android Activity screenshot APIs.
8. Implement savestates in Kotlin by copying files.
9. Implement Discard by changing only Compose state.
10. Reuse the current persisting `_rpcsx_settingsSet` for transactional Settings page.
11. Guess trophy data from filenames without using `TROPUSR`/`TROPCONF`.
12. Instantiate native trophy/friends dialogs from Kotlin menu.
13. Hard-code Friends or Trophies rows when backend context unavailable.
14. Hard-code exactly one savestate slot into UI.
15. Make new `dlsym` symbols mandatory and break old backend loading.
16. Leave backend submodule modifications uncommitted.
17. Disable all RSX overlays globally just to hide Home Menu.
18. Rewrite unrelated emulator systems while implementing this feature.

---

## 31. Worker Implementation Checklist by File

### `app/src/main/java/com/zenithblue/sambas3/ui/ingame/EmulationMenu.kt`
- Remove `CoreHomeMenu`; expand actions; move state/controller logic out; use `LazyColumn`; controller highlight; conditional capabilities; route to Kotlin subpages.
- Keep `GameRepository`-based `runningGameLabel` header (`EmulationMenu.kt:250-258`).

### `app/src/main/java/com/zenithblue/sambas3/ui/ingame/InGameSettingsPage.kt`
- Start backend settings session (`beginInGameSettingsSession`); use `settingsSetTransient`; dirty state; Save/Discard; backend Home Menu settings subset/schema; no immediate global persistence.
- Host `AlertDialogQueue.AlertDialog(respectHostSuppression = false)` remains (already at line 92-93).

### New Kotlin pages
- `InGameTrophiesPage.kt`, `InGameFriendsPage.kt`, `InGameSaveStatePage.kt`, `InGameMenuInputRouter.kt`, `InGameMenuController.kt`, `InGameMenuModels.kt`, `InGameMenuComponents.kt`.

### `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt`
- Register `FrontendEventCallback` → `InGameMenuController`; Home request → Kotlin menu; menu input routing before gameplay routing; neutralize on open; re-arm after release; remove `openCoreHomeMenu` (204-207); replace normal exit `kill` with graceful shutdown; preserve Activity state through restart/stop; fix menu-toggle Running/Paused ordering (currently 120-130 guards only Running).

### `app/src/main/java/com/zenithblue/sambas3/overlay/PadOverlay.kt`
- Explicit `cancelActiveInputsAndNeutralize()`; preserve `setMenuMode` dim (510-541); no touch reaches core during menu; restore after close.
- Keep `OverlayTouchPolicy` pure (`OverlayTouchPolicy.kt:1-13`) plus any new `shouldAcceptGamepad` predicate.

### `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt`
- Remove `openHomeMenu` (93); add `FrontendEventCallback`, `setFrontendEventListener`, menu session/capability/action/data/settings-transaction APIs.

### `app/src/main/cpp/native-lib.cpp`
- Remove old Home Menu bridge (33, 105, 233-241) or keep as optional null-safe; add optional dlsym for new APIs; null-safe JNI wrappers; string wrapping for JSON; listener bridge.

### `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp`
- Frontend event listener; Home ownership; menu session/pause ownership; remove direct native menu triggers (1849-1853); capabilities; action exports; settings transaction; data adapters; lifecycle cleanup (on `Stopped`/Kill).

### `app/src/main/cpp/rpcsx/rpcs3/Input/pad_thread.{h,cpp}`
- Generic frontend Home request consumer hook (`set_home_menu_request_handler` / `dispatch_home_menu_request`); preserve desktop fallback.

### RPCSX trophy/friends helpers
- Extract data logic from `overlay_trophy_list_dialog::load_trophies` and `friends_list_dialog` where necessary; no rendering/UI dependency in Android JSON adapters.

---

## 32. Worker-Agent Starter Instruction

> Implement this plan in small reviewable commits. Start with Phase 1 and do not build the feature pages until you can prove the native Home Menu is unreachable from Android PS/Home, toolbar, and surface lifecycle paths (logcat `FRONTEND_MENU home-request consumed`, manual test matrix 27.4). Treat the pinned RPCSX submodule commit `e8ae148` as the behavior source of truth, and use current RPCS3 only for forward-compatible API design. Do not leave the RPCSX submodule dirty: commit backend changes in a writable fork/branch and update Samba's submodule pointer. Preserve all unrelated emulator behavior. After each phase, run `./gradlew :app:assembleStandardDebug` + relevant unit tests and include changed files, exact behavior verified, and remaining risks in your handoff.

---

## 33. Definition of Done

The final experience feels like one Android-native emulator product:

- Game continues underneath or pauses according to `g_cfg.misc.pause_during_home_menu`.
- Screen dims (`MENU_DIM_ALPHA` 0.35f), Samba's Kotlin menu appears.
- Touch and physical controller both operate that same Kotlin menu.
- All backend Home Menu functionality is available from Kotlin.
- Backend actions use the real RPCSX/RPCS3 implementation (`g_user_asked_for_*`, `GracefulShutdown`, `Restart`, `savestate` Kill path).
- No user can fall through into RPCSX/RPCS3 native Home Menu.
- No duplicate controller handling.
- Settings can genuinely Save/Discard.
- Optional features appear only when available.
- Design remains compatible with future RPCS3 savestate/menu evolution through capabilities rather than UI hard-coding.

**Boundary:** Kotlin owns presentation and interaction; RPCSX owns emulation behavior and data.

---

## Appendix A — Verified Line References (spot-check)

| Claim | Evidence |
|---|---|
| `InGamePage` today has 4 values | `EmulationMenu.kt:50` |
| `CoreHomeMenu` row + handler | `EmulationMenu.kt:87` + `169-180` + `RPCSXActivity.kt:204-207` |
| `openHomeMenu` SOT | `RPCSX.kt:93` → `native-lib.cpp:33,105,233-241` → `rpcsx-android.cpp:2031` |
| PS virtual-pad home trigger | `rpcsx-android.cpp:1849-1853` |
| Pad-thread PS edge → open | `pad_thread.cpp:471-475` → `649-684` |
| Pause ownership | `overlay_home_menu.cpp:164-170` + `99-113` |
| Physical controller live while menu open | `RPCSXActivity.kt:319-417` |
| Touch menu-mode gate | `PadOverlay.kt:309-417`, `OverlayTouchPolicy.kt:8-12` |
| Save/Discard backend | `overlay_home_menu_page.cpp:177-211`, `overlay_home_menu.cpp` attach |
| `_rpcsx_settingsSet` immediate persist | `rpcsx-android.cpp:3141-3167` |
| Screenshot/recording flags | `RSXThread.cpp:41-42`, `overlay_home_menu_main_menu.cpp:105,116` |
| Savestate suspendMode vs load | `overlay_home_menu_savestate.cpp:15-57`, `Emu/savestate_utils.cpp:346` |
| Trophies hidden masking | `overlay_trophy_list_dialog.cpp:380-399`, `rpcsx-android.cpp:593-594` |
| Friends RPCN gate | `overlay_friends_list_dialog.cpp:691-696`, `_friends_list_dialog:107-153` friends/pressed handling |
| `setMenuMode` dim + cancel | `PadOverlay.kt:510-541` |
| Frontend event precedent | `rpcsx-android.cpp:3188-3234` compile progress listener, `RPCSX.kt:115-134` |

## Appendix B — Strings Already Present

`strings.xml:98-103` — `ingame_menu_title`, `ingame_resume`, `ingame_global_settings`, `ingame_core_home_menu` (to retire), `ingame_exit_game`, `configure_game`. Add `ingame_settings`, `ingame_friends`, `ingame_trophies`, `ingame_take_screenshot`, etc. per Phase 19.

## Appendix C — Risks Not in Scope

- Hardware PS button coming from `InputDevice.SOURCE_GAMEPAD` vs virtual pad race — verify both paths converge via `dispatch_home_menu_request`.
- `enter_button_assignment = circle` (`system_config.h:343`) swapping Cross/Circle in `home_menu_page.cpp` — plan assumes default cross; document if diverging.
- Activity recreation while menu open (`RPCSXActivity` has no `android:configChanges`, `AndroidManifest.xml`) — Phase 16 requires `isFrontendMenuOpen` reattach.

