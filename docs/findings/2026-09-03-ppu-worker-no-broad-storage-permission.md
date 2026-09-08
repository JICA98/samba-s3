# PPU Worker Without Broad Storage Permission

**Date:** 2026-09-03
**Cleanup commit:** `bdf3a75`

## Finding

`92b3b73` added `READ_EXTERNAL_STORAGE` + `MANAGE_EXTERNAL_STORAGE`.
No production code path requests or checks them (`Permission.kt` handles only
`POST_NOTIFICATIONS`; no `checkSelfPermission`/`requestPermissions` for
storage anywhere). Final delta vs `4033d83`: **NONE**
(`docs/testers/artifacts/2026-09-03-rdr-crash-fix/permission-diff.txt`).

## Why removal is safe

- Stage A install opens the user-picked file via SAF:
  `ContentResolver.openFileDescriptor(uri, "r")` → fd → native install.
  Same app UID, persistable URI grant — no broad permission involved.
- Stage B (`PpuInstallOrchestrator`) compiles the Stage A **app-private**
  installed path (`config/dev_hdd0/game/...` under `getExternalFilesDir`),
  resolved from `ImportSession`/merged `GameRepository` info — never a raw
  `/storage/emulated/0/...` path.
- The `:ppu_compile` worker (`PpuBatchWorkerService`) runs under the same app
  UID with `PpuWorkerNativeBootstrap` rooted at the app-private dir, so the
  persisted URI grant / app-private files remain accessible without All Files
  Access. No FD-passing API was needed.
- The only raw-filesystem consumer was the debug harness
  (`DEBUG_INSTALL_FILE` direct-path install), which is debug-only and was
  reverted to the `92b3b73` state.

## Proof

- `aapt dump permissions` on the fresh `assembleStandardDebug` APK lists
  exactly the 7 baseline permissions (`apk-permissions.txt`).
- On-device `dumpsys` shows only `POST_NOTIFICATIONS` as runtime permission.
- Fresh no-permission build installs and launches to Home cleanly
  (`00-home-smoke.png`); no storage prompt, no crash.
- PPU sources frozen after cleanup: `git diff --name-only bdf3a75..HEAD`
  over `ppu/**`, PPU AIDL, `CompileProgressBridge`,
  `PrecompilerService`, and the native patch is empty
  (`ppu-freeze-diff.txt`).

## Tests

- `:app:testStandardDebugUnitTest` — 428 tests, 0 failures.
- `:app:testPlaystoreDebugUnitTest` — 425 tests, 0 failures.
- Includes PPU batch policy / overall-progress reducer suites (unchanged,
  still green) and the new settings-isolation + driver-resolver suites.
