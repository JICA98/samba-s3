# Savestate recovery follow-up — 2026-08-29

## Scope

Validation used only Android tablet serial `7d6afed8`. The phone serial was not
used. Game: GTA San Andreas (`BLUS31584`).

## Finding and fix

PPU linking was not the final blocker. The tablet log showed:

```text
S3PPU stage=link-end ... failed=0
S3PPU stage=apply-end ...
Final Thread
```

The emulator then remained in `Starting`. `lv2_obj::sleep_unlocked()` invoked
`FinalizeRunRequest()` while holding the LV2 scheduler mutex. Android's normal
`CallFromMainThread` callback is deliberately inline, so this re-entered the
same mutex. The targeted fix is commit `74b0da9a8`: route this callback through
`post_core_lifecycle`, which executes it on the serialized lifecycle worker
after the critical section is released.

## Tablet evidence

- Native ARM64 core rebuilt successfully.
- Standard debug APK installed with `adb -s 7d6afed8 install -r -d`.
- Cold launch reached the GTA EULA/title flow.
- SAVE auto-restart returned to rendered GTA gameplay with HUD and responsive
  scene, instead of staying black in `Starting`.
- Direct LOAD of slot 3 returned to rendered GTA gameplay. The captured
  screenshot is `/tmp/samba-s3-tablet-load3-fix2-95s.png`.
- PPU link/apply/JIT stages completed with `failed=0`; no Scudo invalid-chunk,
  SIGABRT, or fatal exception appeared in the tested log window.

## Build and publication

- Submodule commit: `74b0da9a8`.
- Root pin commit: `660168a`.
- Root branch push: `origin/recovery/ingame-menu-fix` succeeded.
- RPCSX submodule push remains blocked: configured upstream
  `RPCSX/rpcsx.git` returns GitHub HTTP 403 for this account. The root pin is
  therefore pushed, but a clean clone cannot fetch the private/unpublished
  submodule commit until a writable fork or alternate remote is provided.

## Remaining validation

The extended repeated SAVE/LOAD matrix, explicit restart, suspend-mode, and
double-tap stress cases remain follow-up work. The diagnostic build identity
string also predates `74b0da9a8`; it must be regenerated when the core is
rebuilt for a publishable artifact.
