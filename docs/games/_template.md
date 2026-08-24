# <TITLE> (<TITLEID>) — Fix Log

| Field | Value |
|---|---|
| Title |  |
| TitleID |  |
| Path | `/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/<ID>` |
| Update | `PS3_UPDATE` present? / patch `patch.yml` hash? |
| Device | serial, model, SoC, exact GPU and driver (`Y5WWBMJVOZSK4HU8`: Dimensity 8300 Ultra / Mali-G615 MC6) |
| Renderer | Vulkan plus the hardware physical-device line from `rpcsx_backend.log` (`Cubeb` is audio, not a graphics renderer) |
| Baseline config | `config.yml` snippet (Video + Core) |
| Loop script | `scripts/get-samba-logs.sh` + `input tap 1632,873` ×3 |
| In-game target | First controllable frame after cutscene (no `Access violation`) |
| Game-data validation | Record sizes/hashes for large archives before settings triage |

## Iterations

### Iter 01 — baseline (Video defaults)
```yaml
Video:
  Renderer: Vulkan
  Write Color Buffers: false
  ...
Core:
  PPU Decoder: LLVM Recompiler (Legacy)
  SPU Decoder: Recompiler (LLVM)
```
- Launch: warm/cold, `mCurrentFocus` RPCSXActivity
- Steps: EULA ✓ / Start Game ✓ / New Game → crash at `mm:ss`
- Log: `grep -c modelinfo` = , `Access violation 0x0` at `timestamp`, 20-line window:
  ```
  ```
- Verdict: FAIL — reason

### Iter 02 — WCB true
…

## Final Stable Config (per-game delta)

```json
// GameSettingsOverrides replayed via GameSettingsOverrides.kt
{
  "Video@@Write Color Buffers": true,
  ...
}
```

Or direct `config.yml` diff for testing.

## Debug Notes

- Controller: `1632,873` (1920×1080), or `adb broadcast DEBUG_PAD`.
- Logs: `./scripts/get-samba-logs.sh Y5WWBMJVOZSK4HU8 /tmp/out` → attach `rpcsx_backend.log` window.
- Upstream: `rpcsx-android.cpp:107 LogListener` → `logcat -b main` → `LogMonitor.kt:298` → `rpcsx_backend.log`.

## References

- `skills/sambas3-game-launch`, `sambas3-logs`
