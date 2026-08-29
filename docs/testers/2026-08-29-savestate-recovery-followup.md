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
- Direct LOAD of slot 4 returned to rendered GTA gameplay. The final capture
  is `/tmp/samba-s3-tablet-load10-final.png`.
- The extended tablet gate completed 10 LOAD requests (`S3LIFE` ids 14–23),
  10 `boot_returned` events, PPU link stages with `failed=0`, and zero
  `FATAL`, `SIGABRT`, `SIGSEGV`, Scudo invalid-chunk, access-violation, or
  `VK_ERROR_DEVICE_LOST` signatures in the 14:40–14:59 test window.
- The 10x SAVE gate also completed earlier on slot 4; every cycle returned to
  the in-game menu with the process alive and the saved slot updated.
- The old “Applying PPU Code…” label can remain over rendered gameplay after
  restore; backend PPU link/JIT completion and responsive gameplay show this
  is stale UI progress text, not an active PPU-link deadlock.
- Settings opened and returned successfully after the repeated LOAD gate.

## Build and publication

- Submodule commit: `74b0da9a8`.
- Root pin commit used for the tested native artifact: `95ddc70`.
- Root branch push: `origin/recovery/ingame-menu-fix` succeeded.
- RPCSX submodule push remains blocked: configured upstream
  `RPCSX/rpcsx.git` returns GitHub HTTP 403 for this account. The root pin is
  therefore pushed, but a clean clone cannot fetch the private/unpublished
  submodule commit until a writable fork or alternate remote is provided.

## Remaining validation

The required cold launch, explicit restart, repeated SAVE/LOAD, and settings
gates pass on the tablet. Native debuggerd stack capture remains unavailable
on this non-rooted device (`debuggerd: root is required`); the conclusion is
based on lifecycle/PPU logs and screenshots. A writable RPCSX fork is still
needed before a clean external clone can fetch submodule commit `74b0da9a8`.
