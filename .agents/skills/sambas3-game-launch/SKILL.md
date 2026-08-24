---
name: sambas3-game-launch
description: Launch SambaS3 PS3 games on device via RPCSXActivity, verify Vulkan (not software), and drive controllers for agent gameplay. Use when user asks to "launch game", "test GTA", "run on Vulkan", or needs agent controller automation. Pairs with sambas3-logs for crash triage.
---

# SambaS3 Game Launch + Controller Agent

## Preflight: Vulkan vs Software

Game **must** run on Vulkan. Check before launch:

```bash
adb shell cat /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/config.yml | grep -A1 "Renderer:"
# expect: Renderer: Vulkan  (not "OpenGL" or "Software")
# fix if wrong: Settings → Advanced Settings → Video → Renderer → Vulkan, OR per-game Configure Game → Video
```

Verify after boot (log should show Vulkan, not `rsx: Null`):
```bash
adb -s SERIAL shell "grep -i 'renderer.*vulkan\|vulkan.*initialized' .../logs/rpcsx_backend.log | tail -5"
```

## 1. Find game path

```bash
adb shell cat /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/games.json | python3 -m json.tool
# → [{"path":".../config/games/BLUS31584","name":"GTA San Andreas",...}]
# GTA SA: BLUS31584 at /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584
```

If you see a nested path `.../config/games/storage/emulated/0/...` , run the startup fix (already patched in `FileUtil.kt:42` `fixNestedGameDirs`), or manually:
```bash
adb shell "mv '.../config/games/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584' '.../config/games/BLUS31584' && rm -rf '.../config/games/storage'"
```

## 2. Launch

> **Cold-start warning (2026-08-24):** `RPCSXActivity` direct after `am force-stop` crashes with `SIGSEGV pc 0x0 at Java_com_zenithblue_sambas3_RPCSX_getState+32` (`native-lib.cpp:198` `rpcsxLib.getState()` null when `librpcsx-android.so` not yet `dlopen`). Fixed in code `RPCSXActivity.kt:50` `GeneralSettings.init` + `openLibrary`/`initialize` fallback **and** `native-lib.cpp:198` null-guard (rebuild required). Until APK with fix is installed, **never** `am force-stop` then direct `RPCSXActivity`; warm through `MainActivity`.

```bash
# SAFE warm launch (required on current APK before cold-fix, MediaTek mt6897):
adb -s Y5WWBMJVOZSK4HU8 shell am force-stop com.zenithblue.sambas3
adb -s Y5WWBMJVOZSK4HU8 shell am start -n com.zenithblue.sambas3/.MainActivity
sleep 5  # wait for LogMonitor + RPCSX.openLibrary() + initialize + threads (MainActivity.kt:28-86)
adb -s Y5WWBMJVOZSK4HU8 shell am start -n com.zenithblue.sambas3/.RPCSXActivity -e path "/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584"

# After fix is installed (check `adb logcat | grep "RPCSX cold init"`), direct launch is safe:
adb -s Y5WWBMJVOZSK4HU8 shell am start -n com.zenithblue.sambas3/.RPCSXActivity -e path "/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584"

# single device (omit -s)
adb shell am start -n com.zenithblue.sambas3/.RPCSXActivity -e path "/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584"

# wait for focus
adb -s Y5WWBMJVOZSK4HU8 shell dumpsys window | grep mCurrentFocus  # expect RPCSXActivity
adb -s Y5WWBMJVOZSK4HU8 shell "tail -F /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/logs/rpcsx_backend.log" | grep -m1 "Mount\|PPU.*Modules" &
```

App activity launcher (`android-mcp`) is **not** used for games — always use `am start` with `path` extra. The helper `scripts/get-samba-logs.sh` also pulls logs after.

**If you see `Fatal signal 11 pc 0x0 ... Java_com_zenithblue_sambas3_RPCSX_getState`** — you hit the cold bug. Retry via warm path above and flag for rebuild (`native-lib.cpp:198` guard + `RPCSXActivity.kt` cold `openLibrary`). See `sambas3-logs` skill § Boot triage.

## 3. In-game menu (new native UI, no pad injection)

- `RPCSXActivity` is now `ComponentActivity` (`MainActivity.kt` precedent) with `FrameLayout{ GraphicsFrame, PadOverlay, ComposeView#ingameOverlay }` (`activity_rpcs3.xml`).
- Menu opened by `menu_toggle` (`ic_home_menu`, `app:tint="#40FFFFFF"` at `bottom_toTopOf osc_toggle`) or PS button (`onMenuRequestedFromPad`).
- Items: `RESUME` (`ic_play`), `CONFIGURE GAME` (tune/sliders), `GLOBAL SETTINGS` (gear), `CORE HOME MENU` (home → `RPCSX.instance.openHomeMenu()`), `EXIT GAME` (stop → `kill()` + `finish()`). All real vectors, no emoji.
- Overlay: `EmulationMenu.kt` + `InGameSettingsPage.kt` (reuses `AdvancedSettingsScreen`), scrim consumes touches when expanded.

Agent: tap `menu_toggle` via `android-mcp_Click` `(1860,940)` or `ClickBySelector text="RESUME"` etc. Verify with `Snapshot`.

## 4. Controller logic for agents

### A. PadOverlay (on-screen, default)

- Full-screen `SurfaceView` (`PadOverlay.kt:69`) above `GraphicsFrame`. Handles `MotionEvent` → `State(digital[2], left/right sticks 0-255)` → `RPCSX.instance.overlayPadData(d1,d2,lx,ly,rx,ry)` (`RPCSX.kt:84`).
- Buttons: `PadOverlayButton` (Cross/Circle/Square/Triangle, L1/R1/L2/R2, Select/Start/PS), `PadOverlayDpad` (4 dirs), `PadOverlayStick` (floating sticks spawned in center area). Bits: `Digital1Flags.CELL_PAD_CTRL_*` (`RPCSX.kt:9`), `Digital2Flags` (`:23`).
- Layout is proportional to screen (`PadOverlay.kt:113` `btnSize = min(display)`). Coordinates shift per device — **never hardcode**, use `Snapshot` to get current `padOverlay` child positions, or tap via `android-mcp` vision.

Agent patterns:
```kotlin
// via UI (preferred, no JNI)
Click(x,y) // single button
Drag(x1,y1,x2,y2) // stick drag (stick spawns on first tap in center, then drag)
LongClick(x,y) // L3/R3
```

- `osc_toggle` (`ic_show_osc`) hides overlay (`padOverlay.isInvisible`); controller still works via physical pad.

### B. Physical gamepad (if attached)

- `RPCSXActivity.kt:108` `onKeyDown/onKeyUp/onGenericMotionEvent` → `InputBindingPrefs` → `gamePadState` → same `overlayPadData`. No agent action needed; just ensure `InputDevice.SOURCE_GAMEPAD` events are not intercepted.

### C. Headless / direct JNI (for tests, not for gameplay)

```kotlin
RPCSX.instance.overlayPadData(
  d1 = Digital1Flags.CELL_PAD_CTRL_CROSS.bit, d2 = 0,
  lx = 127, ly = 127, rx = 127, ry = 127
)
```
Press = set bit, hold 120ms, release = clear bit + call again. Used by `PadInputInjector.kt` (now deleted) and `GameSettingsOverrides` not for gameplay.

**Agent must:** use **method A** for GTA SA gameplay (visible overlay). Method C is for JVM tests only.

### Example: Press X to pass `PRESS X TO CONTINUE` (GTA SA) — MediaTek Y5WWBMJVOZSK4HU8

`PadOverlay.kt:172 faceX/faceY` is proportional. **Never hardcode without verifying `wm size`**. On MediaTek override `1920×1080` `/tmp/gta-loop-20260824-090241` calibrates to:

```bash
# 1920x1080 landscape → btnSize=135, faceX=1430, faceY=576 → Cross center 1632,873 (verify via python calc in log)
adb -s Y5WWBMJVOZSK4HU8 shell "input tap 1632 873"  # correct for 1920x1080; 840,830 was portrait miscalc
# portrait 1080x2400 would be 823,2021 (do not use for landscape game)
# generic discovery:
python3 -c "
totalW=1920; totalH=1080; sizeHint=min(totalW,totalH); btnSize=min(sizeHint//8,int(totalH*0.40/3)); faceX=totalW-int(totalW*0.038)- (btnSize*2+int(btnSize*0.70))-int(btnSize*0.4); faceY=totalH-int(totalH*0.13)-(btnSize*2+int(btnSize*0.70)); print(faceX+btnSize+btnSize//2, faceY+(btnSize*2+int(btnSize*0.70))-btnSize//2)"
# or via snapshot: find blue X circle bottom-right, tap its center
android-mcp_Click x=1632 y=873
```

Full auto sequence (EULA → Start Game → New Game, 4s spacing, as used in loop `/tmp/gta-auto-loop.sh`):
```bash
for _ in 1 2 3; do adb -s Y5WWBMJVOZSK4HU8 shell "input tap 1632 873"; sleep 4; done
# check: adb shell "grep -c 'modelinfo\|Access violation' .../logs/rpcsx_backend.log"
```

### Example: Play sequence (EULA → cutscene)

```
1. Snapshot → wait for "Loading PPU Modules" → "PRESS X TO CONTINUE"
2. Click Cross (blue X) → EULA accepted
3. Cutscene plays (pamf/vdec). If crash, pull logs via sambas3-logs skill.
4. For menu test: Click menu_toggle → RESUME/CONFIGURE/GLOBAL → Back
```

## 5. Pair with logs

After any crash/hang, immediately run `sambas3-logs`:

```bash
./scripts/get-samba-logs.sh Y5WWBMJVOZSK4HU8 /tmp/out
grep -i "Access violation\|VK_ERROR\|rsx::thread.*sleepy" /tmp/out/rpcsx_backend.log | tail -20
```

GTA SA on `mt6897` (Dimensity 8300 Ultra, Mali-G615 MC6) is now a verified in-game pass:

- The former New Game `Access violation 0x0` after `Too many objects without modelinfo` was caused by a truncated installed `PS3DataMain.obb`, not a Mali driver failure. The bad copy was `1,073,739,776` bytes; the correct source/device file is `1,479,813,213` bytes.
- WCB/RCB/WDB/RDB, Multithreaded RSX, Accurate RSX reservation and interpreter variants did not fix missing data. Do not carry those settings forward as a Mali workaround.
- Disable the temporary `Skip null modelinfo crash` PPU patch after repairing the data. With the complete archive, the patch skips required setup and can produce a void/missing-collision scene.
- Current checkpoint: Grove Street renders with CJ, bike, HUD, radar, world geometry and collision, and accepts controller input. Confirm no `Access violation` or `Emulation has been frozen` in the new launch window.
- Vulkan is verified when the log reports both `Found Vulkan-compatible GPU: 'Mali-G615 MC6' running on driver 44.1.0` and `Vulkan: Renderer initialized on device 'Mali-G615 MC6'`.
- Low FPS is under optimization. Current leads are live Vulkan shader compilation, GTA shader writes failing on read-only `/dev_bdvd`, expensive accuracy/readback settings left from crash triage, serialized PPU/RSX work, the conservative `cortex-a34` Android LLVM fallback, and memory/swap pressure.
- The reference device has thermal limits removed and GPU frequency locked at 1.400 GHz. Do not call it thermally throttled or GPU-saturated from temperature, maximum OPP or the current `ACTIVE` field; capture valid frame/busy timing and active-game per-thread CPU data.

Keep Vulkan, 720p/100%, MSAA disabled, Async Shader Recompiler and the on-disk cache enabled. For optimization, revert one diagnostic accuracy/readback option at a time and compare first-traversal versus repeated-traversal frame times. See `docs/games/BLUS31584-GTA-San-Andreas.md` and the global `docs/games/GPU-COMPATIBILITY.md` matrix.

## References

- `RPCSXActivity.kt:24` `ComponentActivity`, `activity_rpcs3.xml:11` FrameLayout, `overlay/PadOverlay.kt:69`, `RPCSX.kt:84` `overlayPadData`
- `ui/ingame/EmulationMenu.kt:1`, `gameconfig/GameSettingsOverrides.kt:1` (per-game Vulkan prefs)
- Device: `Y5WWBMJVOZSK4HU8` `2311DRK48I`/duchamp `mt6897` Dimensity 8300 Ultra, Mali-G615 MC6 (Valhall generation 4), Vulkan renderer verified in the backend log
