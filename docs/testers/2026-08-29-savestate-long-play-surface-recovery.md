# Savestate long-play and surface recovery — 2026-08-29

## Scope

Validation was performed only on Android tablet `OPD2403`, serial
`adb-7d6afed8-mU47CV._adb-tls-connect._tcp`; the phone was not used. Game:
GTA San Andreas (`BLUS31584`).

## Implemented recovery behavior

- Save requests persist a durable `REQUESTED` record, then transition it to
  `COMMITTED` only after the exact selected slot file is present.
- The original game path is stored separately from the savestate path.
- Save completion tears down the old emulator/surface generation before
  creating a new generation and booting the exact saved slot.
- A process interruption after commit is recovered on the next `MainActivity`
  launch. Recovery re-registers the original game and boots the committed
  savestate path, with bounded retry handling.
- Manual LOAD now arms the exact selected existing slot as `COMMITTED` before
  confirmation. If the process terminates during restore, the next launch
  uses that same slot instead of a generic game boot.
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

The final install window was also exercised interactively on the tablet after
installing the rebuilt APK. The native acceptance signal was observed before
the save completed, then Slot 4 committed and recovered as follows:

```text
S3SAVE native-accepted ... slot=4 state=REQUESTED
S3SAVE id=1 phase=file-committed slot=4 ... BLUS31584_1_4.SAVESTAT.zst
S3SURFACE destroyed generation=1 native-release-return=true
S3SURFACE created generation=2 accepted=true
S3RENDER boot-savestate-begin ... BLUS31584_1_4.SAVESTAT.zst
S3PPU stage=link-end ... failed=0
S3RENDER first-frame-confirmed generation=2
S3SAVE pending recovery cleared
```

The same Slot 4 was then loaded from the in-game save menu; after the PPU
link/apply sequence completed, the screenshot showed live gameplay again:
`/tmp/samba-tablet-load-final.png`. A duplicate completion event was ignored
with a warning for a missing record, confirming stale-event protection.

The backend and app logs for the run contain no fresh `VK_ERROR_DEVICE_LOST`,
Scudo invalid-chunk, `SIGABRT`, `SIGSEGV`, access-violation, or PPU link failure.
The tablet did emit harmless 4x4 `AHardwareBuffer` allocation warnings during
surface recreation; they did not prevent first-frame confirmation. Historical
Vulkan `dequeueBuffer -19` entries predate this validation window.

### Final exact Slot 3 manual LOAD

The rebuilt Standard Debug APK was installed on the tablet and launched GTA
normally. `/tmp/samba-tablet-final-installed-gameplay2.png` shows rendered
gameplay after installation with no stale restore overlay.

The existing exact Slot 3 file was present:

```text
/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config//savestates/BLUS31584/BLUS31584_1_3.SAVESTAT.zst
size=67358041 bytes, mtime=2026-08-29 21:24
```

The tablet selected `Slot 3 — 29 Aug 21:24` and tapped LOAD. The final log
recorded:

```text
S3SAVE manual-load marker armed ... slot=3 ... BLUS31584_1_3.SAVESTAT.zst
S3LIFE ... action=load phase=shutdown_end ... is_stopped=1
S3LIFE ... action=load phase=boot_begin new_gen=1 ... BLUS31584_1_3.SAVESTAT.zst
S3PPU stage=link-end ... failed=0
S3PPU savestate-progress-cleared
S3SAVE pending recovery cleared
S3SAVE manual-load first-frame-confirmed slot=3 generation=1
```

`/tmp/samba-tablet-manual-load-after-final.png` captured the transitional PPU
linking screen at module 3/71. The completion capture
`/tmp/samba-tablet-manual-load-confirmed-final.png` shows live restored GTA
gameplay with HUD and no PPU overlay. The complete filtered log is
`/tmp/samba-tablet-manual-load-final.log`.

### Fresh controlled interruption

The final tablet-only interruption test saved Slot 4, observed the savestate
mtime/size change seven seconds after confirmation, and immediately stopped the
tablet process before the in-app recovery could finish. On the next
`MainActivity` launch, the fallback selected the exact committed file:

```text
S3SAVE recovery-register-game .../games/BLUS31584 result=0
S3SAVE recovery-boot .../BLUS31584_1_4.SAVESTAT.zst result=0
S3PPU stage=link-end ... failed=0
S3PPU savestate-progress-cleared
S3RENDER first-frame-confirmed generation=1
S3SAVE pending recovery cleared
```

The final screenshot `/tmp/samba-tablet-controlled-recovery-33s.png` shows
rendered GTA gameplay with no `Restoring...` overlay. The corresponding pulled
logs are under `/tmp/samba-tablet-interruption-window/`.

### Fresh Slot 0 load/save verification

After installing the targeted progress-dialog fix on the same tablet, the
existing exact Slot 0 (`BLUS31584_1_0.SAVESTAT.zst`) was loaded successfully.
The PPU link completed with `failed=0`, followed by
`manual-load first-frame-confirmed slot=0`; the final screenshot
`/tmp/samba-tablet-slot0-load-current-20260829.png` shows the GTA menu without
the stale `Applying PPU Code...` dialog.

The same Slot 0 was then overwritten through the in-game menu. The save
completed and restarted the exact slot, with `first-frame-confirmed`,
`S3PPU savestate-progress-cleared`, and `transition-overlay-hidden` in the
tablet log. The final screenshot is
`/tmp/samba-tablet-save-slot0-after30-20260829.png`; the filtered log is
`/tmp/samba-tablet-save-slot0-logcat-20260829.txt`.

No fresh Scudo, SIGABRT/SIGSEGV, `VK_ERROR_DEVICE_LOST`, or PPU link failure
was observed in this verification window.

### Fresh 20-minute long-play SAVE and same-slot LOAD

On 29 Aug, the tablet ran GTA San Andreas in live free-roam for 20 minutes
with periodic movement input. The 20-minute frame is captured at
`/tmp/samba-tablet-longplay-60-20260829.png` and shows the player, HUD, and
minimap with no PPU progress overlay.

Slot 0 was then overwritten from the in-game menu. The save file became
`63,618,632` bytes and the transition log recorded the complete sequence:

```text
S3SAVE ... phase=file-committed slot=0 ... size=63618632
S3SAVE ... phase=completion-event slot=0
S3RENDER old surface replacement requested ... slot=0
S3RENDER boot-savestate-begin ... BLUS31584_1_0.SAVESTAT.zst
S3PPU stage=link-end ... failed=0
S3RENDER running generation=2
S3RENDER first-frame-confirmed generation=2
S3PPU savestate-progress-cleared
S3SAVE pending recovery cleared
```

The save-start and post-transition frames are
`/tmp/samba-tablet-longplay-save-start-20260829.png` and
`/tmp/samba-tablet-longplay-save-after30-20260829.png`; the filtered log is
`/tmp/samba-tablet-longplay-save-log-20260829.txt`.

The newly saved Slot 0 was immediately loaded from the same exact path. The
tablet displayed the updated `Slot 0 — 29 Aug 23:24` entry, and LOAD completed
with `failed=0`, `manual-load first-frame-confirmed slot=0 generation=2`, and
`S3SAVE pending recovery cleared`. The final restored gameplay frame is
`/tmp/samba-tablet-longplay-load-after30-20260829.png`; the filtered log is
`/tmp/samba-tablet-longplay-load-log-20260829.txt`.

The load log also exposed one benign late completion from the preceding save
being ignored because its pending request had already been consumed. The
fresh load itself completed normally and no PPU, Vulkan, Scudo, or signal
failure appeared in either filtered window.

## Build

- `./gradlew test :app:assembleStandardDebug --no-daemon` — passed.
- ARM64 RPCSX core rebuilt incrementally and copied into the APK inputs.
- Native recovery commit: `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`.
- Embedded RPCSX build ID source revision: `27441815c12cca1a4dcb3daec82b513b1a35eed7`.
- Patch SHA-256:
  `6468572dbc6dec3ea7d0ad996adeb57dffd96a090d10482ea04d7013abecd5f0`.
- ARM64 core SHA-256:
  `8f1090150729cce876e09e0a7b68086efdf1de89d40224b30994145f919be0ed`
- Installed APK SHA-256:
  `a72761845a0ec5d30d223c8b0b89b5301d90aaffed9a24c169512a4768042799`
- Installed only with
  `adb -s adb-7d6afed8-mU47CV._adb-tls-connect._tcp install -r -d`.

## Limitations

The broader lifecycle stress matrix and second long-save-on-another-slot gate
remain outside this run. The required 20-minute long-play, exact Slot 0 SAVE,
and same-slot LOAD were completed on the final tablet install and are recorded
above.
