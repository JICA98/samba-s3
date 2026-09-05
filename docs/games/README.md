# Per-Game Fix Registry — SambaS3

This directory tracks **per-title** compatibility, configs and loop results on the MediaTek reference device `Y5WWBMJVOZSK4HU8` (2311DRK48I/duchamp, Dimensity 8300 Ultra, Mali-G615 MC6, Valhall generation 4). It is the **single source of truth** for agents iterating toward correctly rendered, controllable gameplay.

## Structure

| File | Purpose |
|---|---|
| `_template.md` | Copy for new titles. |
| `BLUS31584-GTA-San-Andreas.md` | GTA SA — **in-game pass** after replacing the truncated `PS3DataMain.obb`; shader/steady-state FPS optimization remains open. |
| `BLUS30443-Demons-Souls.md` | Demon's Souls — **in-game pass** after Write Color Buffers fix, JNI fallback bridge, and pre-boot curated defaults. |
| `BLUS30758-Red-Dead-Redemption.md` | Red Dead Redemption — intro rendering only; post-intro black screen still reproduced on Adreno 750 / Turnip 26.2.99 with gpu_label active. See [current validation](../findings/2026-09-04-rdr-adreno750-gpulabel-validation.md). |
| `GPU-COMPATIBILITY.md` | Global SambaS3 GPU-family forecast and verification matrix for Adreno, Mali/Immortalis, Xclipse/AMD and PowerVR. |
| `<TITLEID>-<slug>.md` | Other titles as they are triaged. |

## Workflow (use with skills `sambas3-game-launch` + `sambas3-logs`)

1. **Find path:** `adb shell cat .../files/games.json` → `BLUS31584`.
2. **Preflight Vulkan:** `.../config/config.yml` `Renderer: Vulkan` (skill § Preflight).
3. **Launch** via warm path (`MainActivity` → `RPCSXActivity -e path`, now also cold-safe after `RPCSXActivity.kt:52` + `native-lib.cpp:198` fix). Use `1632,873` Cross for `1920×1080`.
4. **Play sequence:** EULA → Start Game → New Game (3× X). Snapshot after each.
5. **Crash triage:** `./scripts/get-samba-logs.sh Y5WWBMJVOZSK4HU8 /tmp/out` → `grep -i "Access violation\|modelinfo\|Emulation.*frozen\|pamf\|vdec"` (skill `sambas3-logs`).
6. **Record** iteration in the per-game file: config yaml snippet, `grep -c` counts, 20-line window, screenshot state, verdict.
7. **Iterate** config via direct `config.yml` edits (or per-game `GameSettingsOverrides` once stable). Never use `agent-device` launcher for games.
8. **Verify data before tuning:** the importer now rejects reported-size mismatches and publishes files atomically, but providers may legally report an unknown size. For GTA, independently confirm `PS3DataMain.obb` is `1,479,813,213` bytes; a partial import previously looked like a PPU/GPU crash.

## In-App Debug Bridge (controller for agents)

See `app/src/main/java/com/zenithblue/sambas3/debug/DebugPadReceiver.kt`. Agents can inject without coordinate taps:

```bash
adb shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_CROSS
adb shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD --ei d1 0 --ei d2 64 --ei lx 127 --ei ly 127 --ei rx 127 --ei ry 127
```

In-app: Settings → **Debug → Controller Test** shows live `digital[2]` + stick bytes and `adb` cheat-sheet (copyable). This is the canonical controller path for loop scripts after `PadOverlay.kt:69` coordinate calc.

## Official Backend Debug (RPCSX core logs)

The core uses `LOG_CHANNEL(channel,"NAME")` (`rpcs3/util/logs.hpp:195`). On Android, `rpcsx-android.cpp:107 LogListener` forwards every `logs::message` to `__android_log_print` (level→ `ANDROID_LOG_ERROR/WARN/INFO/DEBUG`). `LogMonitor.kt:298 logcat -v threadtime -b main,crash,system` captures that and routes by `classifyTag` to `rpcsx_backend.log` / `rpcsx_vulkan.log` / `rpcsx_app.log` (25/15/10 MB, rotated `.1/.2`).

To get **complete logs**:

- **Verbose per channel:** `adb shell "echo 'RSX:trace\nPPU:trace' > .../config/log.cfg"` or `settingsSet("log","{'RSX':'Trace'}")` via `RPCSX.kt:87` (engine `logs::set_level`). Default is `notice` (`logs.hpp:113 enabled = trace? actually notice`). In-app Debug → Log Levels exposes this.
- **TTY:** `.../logs/rpcsx_backend.log` already includes `sys_tty_write` (`cellGame` spam) because it is the same stream; also `.../cache/TTY.log` (`LogMonitor.kt:203` legacy).
- **Full dump:** `./scripts/get-samba-logs.sh` pulls all three + `logcat -b crash` tombstone. Share the bundle, not just tail, for `F/RPCS3: Access violation` issues.

See `docs/debug/Backend-Logging.md` (or `patches/rpcsx-submodule-changes.patch:107` + `native-lib.cpp` guards) for null-guard details on cold `getState`.

## Acceptance: “in-game after cutscene”

A title is **PASS** only when the agent can, in a continuous loop, go from cold launch → EULA → menu → New Game → cutscene → **first controllable gameplay frame** (character movable, correct geometry/collision, not frozen, no `F/RPCS3: Access violation` in the `modelinfo` window). Record the config that achieved it and the `per_game` delta via `GameSettingsOverrides.kt`.

## References

- `RPCSXActivity.kt:52` cold `GeneralSettings`+`openLibrary` fallback, `native-lib.cpp:198` null guard, `PadOverlay.kt:172` Cross calc, `LogMonitor.kt:203`, `FileUtil.kt:42` nested-path fix, `skills/sambas3-*.md`.
- Device `Y5WWBMJVOZSK4HU8` calibration `1920×1080 → 1632,873`.
