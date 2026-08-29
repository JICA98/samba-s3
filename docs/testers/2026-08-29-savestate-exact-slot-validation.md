# Savestate exact-slot recovery validation

Date: 2026-08-29
Device: tablet `7d6afed8` only
Build: Standard Debug, ARM64

## Artifact

- APK: `app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk`
- ARM64 RPCSX core SHA-256: `5fa8de22a3a6271dd238141db84d5717a2f29d683c0385888bf5de2a56080a55`
- Installed with `adb -s 7d6afed8 install -r -d` successfully.

## SAVE -> same-slot restore

1. Saved live GTA San Andreas state to slot 4.
2. Native emitted `S3SAVE phase=file-committed` and `phase=completion-event` for slot 4.
3. Surface generation 2 was synchronously destroyed, then generation 3 was created.
4. The exact committed slot-4 path was booted with Vulkan/Turnip.
5. `first-frame-confirmed` was emitted and the pending recovery marker was cleared.

Slot 4 changed to 55,404,480 bytes. Final screenshot: `/tmp/samba-tablet-save-slot4-restored.png`.

## Interrupted-save recovery

1. Started another slot-4 save and force-stopped the app immediately after the durable completion event.
2. Relaunched `MainActivity`.
3. The durable record handed off the exact slot-4 path to `RPCSXActivity`; it did not boot the original game path first.
4. Vulkan initialized, the first frame was confirmed, and the pending marker was cleared.

Final screenshot: `/tmp/samba-tablet-cold-recovery-final.png`.

No SambaS3 fatal signal, Scudo invalid-chunk error, Vulkan initialization failure, or PPU link failure was observed. The tablet remained in `RPCSXActivity` with live gameplay after both restores.
