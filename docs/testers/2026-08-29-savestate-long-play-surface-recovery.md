# Savestate long-play and surface recovery — 2026-08-29

## Scope

Validation was performed only on Android tablet serial `7d6afed8`; the phone
serial was not used. Game: GTA San Andreas (`BLUS31584`).

## Implemented recovery behavior

- Save requests persist a durable `REQUESTED` record, then transition it to
  `COMMITTED` only after the exact selected slot file is present.
- The original game path is stored separately from the savestate path.
- Save completion tears down the old emulator/surface generation before
  creating a new generation and booting the exact saved slot.
- A process interruption after commit is recovered on the next `MainActivity`
  launch. Recovery re-registers the original game and boots the committed
  savestate path, with bounded retry handling.
- PPU compile progress is explicitly cleared after savestate restore so the
  old “Applying PPU Code…” message does not remain over live gameplay.
- Surface events are generation-checked; one bounded `Activity.recreate()` is
  available as a last-resort dead-buffer recovery.
- Android `qt_events_aware_op` now polls the wrapped lifecycle operation rather
  than returning immediately from its former empty stub.

## Tablet evidence

The installed Standard Debug build completed a normal slot-4 save and restore:

```text
S3SAVE id=2 phase=file-committed slot=4 ... BLUS31584_1_4.SAVESTAT.zst
S3SURFACE event=2 generation=2
S3SURFACE event=0 generation=3
S3SAVE recovery-boot ... BLUS31584_1_4.SAVESTAT.zst result=0
S3PPU savestate-progress-cleared
```

The second slot-4 save was interrupted immediately after commit and recovery
boot was requested. After `am force-stop`, relaunching only `MainActivity`
produced:

```text
S3SAVE recovery-register-game .../games/BLUS31584 result=0
S3SAVE recovery-boot .../BLUS31584_1_4.SAVESTAT.zst result=0
S3SURFACE new-generation-created generation=1
S3PPU stage=link-end ... failed=0
S3PPU savestate-progress-cleared
S3RENDER first-frame-confirmed generation=1
S3SAVE pending recovery cleared
```

Final screenshot: `/tmp/samba-tablet-cold-recovery-final.png`. It shows live
GTA gameplay with HUD and no stale PPU progress text. The earlier normal
restore capture is `/tmp/samba-tablet-after-slot4-restore.png`.

The backend and app logs for the run contain no fresh `VK_ERROR_DEVICE_LOST`,
Scudo invalid-chunk, `SIGABRT`, `SIGSEGV`, access-violation, or PPU link failure.
The tablet did emit harmless 4x4 `AHardwareBuffer` allocation warnings during
surface recreation; they did not prevent first-frame confirmation. Historical
Vulkan `dequeueBuffer -19` entries predate this validation window.

## Build

- `./gradlew :app:testStandardDebugUnitTest :app:assembleStandardDebug` —
  passed.
- ARM64 RPCSX core rebuilt incrementally and copied into the APK inputs.
- ARM64 core SHA-256:
  `6a1ed8c8a4db409460945788686ef0b7c5c7362188f734f0d68771878fe5dda7`
- Installed APK SHA-256:
  `945179a31acc3440a4063eb2a295ebd47f61d330f95e7ed95b6ddc62d71d284c`
- Installed only with `adb -s 7d6afed8 install -r -d`.

## Limitations

This report records the completed normal-save and forced-interruption recovery
gates. A fresh 20–30 minute uninterrupted long-play run was not repeated in
this final install window; the prior repeated tablet SAVE/LOAD evidence remains
in `2026-08-29-savestate-recovery-followup.md`.
