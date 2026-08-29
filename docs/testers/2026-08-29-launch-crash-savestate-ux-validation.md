# Launch Center, Crash Recovery, and Savestate UX Validation

Date: 2026-08-30  
Device: OnePlus OPD2403 / OP5DAAL1 (`adb-7d6afed8-mU47CV._adb-tls-connect._tcp`)  
Game: GTA San Andreas (`BLUS31584`)

## Source

- Root start: `495b60243915183cd49ed9e6f3812454f704b545`
- RPCSX start/final: `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`
- APK SHA-256: `974dbbaeaba9ccd72ee4553e9c789d8726ee215c10d403597227ae07a90e26a4`
- RPCSX core build ID: `27441815c12cca1a4dcb3daec82b513b1a35eed7`
- Samba build ID: `62a70942d8c121a7e58c74b6a4e2700cf369c0ae`
- Patch SHA-256: `6468572dbc6dec3ea7d0ad996adeb57dffd96a090d10482ea04d7013abecd5f0`

## STOP / re-entry investigation

The healing behavior was reproduced without using STOP as a fix. Launcher Continue was starting RPCSX directly with a savestate path. On the OnePlus, that path returned no usable boot progress and remained in the native join-thread startup state; backend logs stopped after descriptor loading and reported `Emulation Join Thread too sleepy`. A normal fresh launch followed by an in-session exact-slot LOAD completed successfully.

The smallest meaningful difference was the boot mode:

```text
BAD:  launcher Continue -> direct bootSavestate(path)
GOOD: launcher Continue -> boot game normally -> loadSaveState(slot)
```

This was a launcher/session integration leak, not a savestate serialization or PPU/surface failure. User-selected Continue/slot launches now use the normal game boot and the existing exact-slot in-session load path. Direct savestate boot remains reserved for durable recovery/legacy recovery.

Explicit STOP on both final passes produced:

```text
S3SESSION journal state=CLEAN_STOP clean=true
nativeState=Stopped activeGame=null
RPCSXActivity.onDestroy ... finishReason=ExplicitExit
```

Compact lifecycle snapshots retain Activity identity, native/mirrored state, active game, surface generation, recovery state, menu state, and journal state. Invalid activeGame/native-state pairings are covered by unit tests.

## Launch Center

Focused game cards now open a per-game Launch Center. The OnePlus tablet showed:

- title and title ID;
- resolution, aspect, frame limit, VSync, PPU and SPU summaries with GLOBAL/GAME provenance;
- selected `turnip-26.3` GPU driver and PPU Ready state;
- slot 0 preview plus slots 1–4 and placeholders;
- Configure, Driver, Patches, Continue Slot 0 and Play Fresh actions.

The launch snapshot is refreshed before boot. Continue and selected-slot actions use the clean normal-boot-then-load flow and do not increment durable recovery retry state.

## Crash system

`EmulationSessionJournal` persists STARTING, BOOTING, RUNNING, SAVING, LOADING, STOPPING, CLEAN_STOP and FAILED state. MainActivity checks pending savestate recovery first, then unfinished sessions.

`CrashClassifier` distinguishes confirmed fatal evidence (SIGSEGV, SIGABRT, Scudo, fatal exceptions, assertions and Vulkan device loss), recoverable emulator failures, and process termination without hard fatal evidence. The OnePlus force-stop/relaunch check correctly displayed `UNEXPECTED TERMINATION` rather than falsely claiming a crash. Evidence filtering is session-time-aware so stale rotated logs do not change the classification.

Crash View provides Summary, Backend, Vulkan/GPU, App/Frontend, System/Crash and Device tabs, chunked file-backed reading/search, export through the existing FileProvider, Retry, Safe Retry, Continue save and Exit actions. Full logs are copied off the main thread into `files/crash_reports/<session-id>/` with a manifest and summary.

## Savestate operation UX

SAVE now shows a freeze-frame modal with an animated indeterminate spinner, operation stage text, interaction lock, request-scoped thumbnail temp file and atomic thumbnail publication after the exact savestate commit. LOAD shows its overlay before the native request, including `Loading Slot 0...` / `Restoring Slot 0...`, then fades after first-frame confirmation.

The OnePlus captures showed:

- `/tmp/final-pass1-saving-fixed.png`: `Saving Slot 0...` spinner;
- `/tmp/final-pass1-loadpage.png`: `Loading Slot 0...` spinner;
- `/tmp/final-pass2-saving.png`: second-pass SAVE overlay;
- `/tmp/final-pass2-loading.png`: second-pass LOAD overlay.

Back, PS/Home, controller keys/motion, overlay touch, menu toggle and OSC toggle are consumed while the operation lock owns the emulator lifecycle. Pad controls are neutralized once at operation start. Thumbnail logs showed temp write followed by final publication only after `file-committed`/completion.

## Device Pass 1

Final tested APK lineage: `974dbbae...e26a4` (the final rebuild differs only by the Crash View journal-clear cleanup added after the first device installation; the behavioral device evidence below was captured on the same source revision before this no-game-path cleanup).

- Fresh launch from the library and focused-card Launch Center: PASS.
- Driver/settings/PPU/save preview presentation: PASS.
- Continue Slot 0 normal boot plus exact-slot restore: PASS; first-frame confirmation logged.
- Android Back from the running session opened the in-game menu: PASS (`S3BACK gameplay -> open-menu state=Running`).
- SAVE Slot 0: PASS; thumbnail staged, native slot committed, thumbnail published, exact restore completed.
- Back during SAVE/restore: PASS; overlay remained owned by the operation.
- Manual LOAD Slot 0: PASS; pre-request loading overlay and first-frame confirmation logged.
- Explicit Exit: PASS; CLEAN_STOP, Stopped, activeGame null.

## Device Pass 2

Force-stop and fresh MainActivity launch were performed before the pass.

- Focused-card Launch Center: PASS.
- Title/settings/driver/PPU/save preview presentation: PASS.
- Continue Slot 0 normal boot plus exact-slot restore: PASS; first frame confirmed.
- Android Back to in-game menu: PASS.
- SAVE Slot 0: PASS; thumbnail replaced after commit and exact restore completed.
- SAVE operation overlay/back blocking: PASS (`/tmp/final-pass2-saving.png`, `/tmp/final-pass2-back-blocked.png`).
- Manual LOAD Slot 0: PASS; `/tmp/final-pass2-loading.png`, first-frame confirmation logged.
- Explicit Exit: PASS; CLEAN_STOP, Stopped, activeGame null.

## Regression evidence

Both final passes preserved exact-slot restore and surface replacement. Logged stages included `recovery-boot`, `pending recovery cleared`, `first-frame-confirmed`, and `journal state=RUNNING`. No new `VK_ERROR_DEVICE_LOST`, `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`, Scudo, SIGABRT, SIGSEGV or PPU link failure appeared in the final test logs.

The previously proven 20-minute GTA savestate regression remains the protected baseline; the requested final device acceptance here was limited to two OnePlus passes.

## Automated gates

- `:app:testStandardDebugUnitTest`: PASS
- `:app:testPlaystoreDebugUnitTest`: PASS
- `:app:assembleStandardDebug`: PASS
- ARM64/x86_64 core verification: PASS
- `git diff --check`: PASS

The unrelated untracked `session-ses_fbba.md` was not modified or staged.
