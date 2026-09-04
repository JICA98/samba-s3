---
name: sambas3-game-launch
description: Deterministic SambaS3 game launch via MainActivity warm start. Use when asked to launch/test a game on device. Wraps scripts/debug-launch-game.sh; max 2 attempts, 20s focus cap. Pairs with sambas3-device-test (orchestrator), sambas3-controller (input), sambas3-logs (evidence).
---

# SambaS3 Game Launch (deterministic)

## Why this path

- `RPCSXActivity` is `android:exported="false"` (`AndroidManifest.xml:64-69`); `MainActivity` is `android:exported="true"` (`:84-94`).
- `MainActivity.onResume` registers `DebugPadReceiver` (`MainActivity.kt:134-137`) and `onCreate` runs `RPCSX.openLibrary()` + `initialize` + main-thread/compile-queue threads (`MainActivity.kt:45-108`).
- `RPCSXActivity` has a cold-init fallback (`RPCSXActivity.kt:162-177`), but warm start through `MainActivity` first remains the safe ordering (avoids the historical `getState` null `SIGSEGV` after `force-stop`).
- There is **no `DEBUG_BOOT_GAME` handler** in `debug/DebugPadReceiver.kt` (actions are `DEBUG_PAD*`, `DEBUG_FATAL`, `DEBUG_LOG_MONITOR_*`, `DEBUG_BENCH_*`, `DEBUG_SETTINGS_*`, `DEBUG_REMOVE_GAME`, `DEBUG_INSTALL_FILE`). `debug-launch-game.sh` probes for it but launches via the warm start below.

## Canonical

```bash
SERIAL='7d6afed8'
GAME='/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584'

./scripts/debug-launch-game.sh "$SERIAL" "$GAME"
```

What the script does: starts `MainActivity` → brief wait for `DebugPad registered` → warm `am start -n com.zenithblue.sambas3/.RPCSXActivity --es path "$GAME" --es originalGamePath "$GAME" --es bootMode FreshGame` → waits at most 20s for `mCurrentFocus ... RPCSXActivity`. Max 2 attempts, then stops.

## Resolve GAME (counts toward the ~10-command budget)

```bash
adb -s "$SERIAL" shell cat /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/games.json | python3 -m json.tool
```

Use the exact on-device absolute path. If you see a nested `.../config/games/storage/emulated/0/...` path, run the startup fix (`FileUtil.fixNestedGameDirs`) or repair manually — do not boot the nested path.

## Preflight: Vulkan (one command)

```bash
adb -s "$SERIAL" shell cat /storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/config.yml | grep -A1 "Renderer:"
# expect Renderer: Vulkan
```

After boot, Vulkan is confirmed only by backend lines like `Found Vulkan-compatible GPU` + `Vulkan: Renderer initialized on device`. `Renderer: Cubeb` is audio.

## Do NOT

- `am force-stop` then direct `RPCSXActivity` (cold crash risk).
- Direct `am start .../.RPCSXActivity` outside `debug-launch-game.sh` (bypasses ordering + retry/focus caps).
- `input tap` coordinates for menus — use `sambas3-controller`.
- More than 2 launch attempts per hypothesis. On failure: `get-samba-logs.sh` once, then report.

## In-game menu (native UI)

`menu_toggle` / PS button → `RESUME` / `CONFIGURE GAME` / `GLOBAL SETTINGS` / `CORE HOME MENU` / `EXIT GAME` (`EmulationMenu.kt`). Drive it with pad broadcasts (`START`, dpad) or one `agent-device` snapshot + tap — not coordinate math.

## References

- `AndroidManifest.xml:63-94`, `MainActivity.kt:45-108,134-143`, `RPCSXActivity.kt:162-177,900-930`, `EmulatorBootRequest.kt` (`path` fallback), `scripts/debug-launch-game.sh`.
