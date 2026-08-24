# GTA San Andreas ISO Import and Intro Validation Plan

## Status

- Planning document only. This document does not authorize treating the current implementation as validated.
- Target title: GTA San Andreas, `BLUS31584`.
- Primary test device: `7d6afed8`, OPD2403, Snapdragon 8 Gen 3 (`SM8650`).
- Required endpoint: a fresh installation must import the complete ISO, boot with Vulkan, pass the opening menus and cutscene, and reach the scene/gameplay where CJ is left in Ballas territory.
- Repository policy: keep the final commit local; do not push. Stop Gradle after every Gradle invocation.

## Objective

Make ISO game installation fail-safe rather than merely fixing GTA's known large-file symptom. The worker must prove all of the following:

1. ISO9660/Joliet multi-extent files are assembled exactly and streamed without loading whole files into memory.
2. Invalid, truncated, malicious, or unreadable ISO structures fail explicitly and cannot be reported as successful imports.
3. An interrupted or failed import cannot replace a previously valid title with a mixed or partial directory.
4. The importer cannot write outside its owned staging/final game directory.
5. GTA's installed data matches the source ISO exactly.
6. The freshly imported game reaches the requested post-intro Ballas-territory checkpoint on the Snapdragon 8 Gen 3 device.

## Verified GTA Baseline

Use these values as non-negotiable test fixtures:

| Artifact | Expected value |
|---|---:|
| Source ISO filename | `GTA-San-Andreas-BLUS31584.iso` |
| Source ISO size | `2,489,647,104` bytes |
| Source ISO SHA-256 | `7c85be6c8652e9ec2e0915a0e5aa5b72464b75660ef383a2bdc310c6ce7084bd` |
| `PS3Data.obb` | `708,640,703` bytes |
| `PS3Data.obb` SHA-256 | `484b4fd07331d00ca6aeea6758772d848e166ee45bb885ba17a2dc115e58d905` |
| `PS3DataMain.obb` | `1,479,813,213` bytes |
| `PS3DataMain.obb` SHA-256 | `8cdcca8047ce083b4cf27316f266affebc7c565ccee38642029ff327397f680f` |
| `EBOOT.BIN` | `8,113,656` bytes |
| `PARAM.SFO` | `1,040` bytes |

The real GTA ISO contains two immediately consecutive Joliet directory records for `PS3DataMain.obb`:

| Extent | LBA | Length | Flags |
|---|---:|---:|---:|
| 1 | `351720` | `1,073,739,776` | `0x80` (`MultiExtent`) |
| 2/final | `876007` | `406,073,437` | `0x00` |

Their checked sum is exactly `1,479,813,213`. The previous importer copied only the first extent (`1,073,739,776` bytes), causing the later GTA loading failure.

## Current Worktree State

The worker must preserve and audit the dirty worktree. Do not reset, discard, or overwrite unrelated changes.

Relevant draft changes currently exist inside the `app/src/main/cpp/rpcsx` submodule:

- `rpcs3/dev/iso.hpp` and `iso.cpp`: `MultiExtent` support, streaming extent reads, gathered multi-extent files, and draft malformed-input checks.
- `android/src/atomic-file-copier.hpp/.cpp`: bounded streaming copy through `fs::pending_file`.
- `android/src/rpcsx-android.cpp`: `installIso` calls the atomic copier and queues PPU compilation.
- `android/CMakeLists.txt`: includes the copier component.

Important: the latest fail-safe edits to `iso.cpp/.hpp` were applied immediately before this plan and have not yet been rebuilt or device-tested. Treat them as a draft requiring compilation, review, and regression testing. The root `patches/rpcsx-submodule-changes.patch` may be stale and must only be regenerated after the implementation passes.

## Independent Review Findings to Resolve

### P0: Whole-title installation is not transactional

Per-file atomic replacement prevents a single destination file from being truncated, but `installIso` still writes files directly into `config/games/<TITLE_ID>`. A failure after several successful files can leave a mixed old/new or partial title directory. `PARAM.SFO` may also make that directory discoverable before the import is complete.

Required design:

1. Extract into a unique staging directory outside the scanned `config/games` tree, on the same filesystem as the final directory.
2. Build and retain a manifest containing every relative path, type, and expected size.
3. Verify the staging tree against the manifest after extraction.
4. Require readable `PARAM.SFO` and `EBOOT.BIN` before commit.
5. Keep an existing valid destination untouched until all staging checks pass.
6. Commit with a rename/swap protocol that can roll back:
   - rename the existing destination to a unique owned backup;
   - rename staging to the final destination;
   - if the second rename fails, restore the backup;
   - remove the backup only after the new final directory is confirmed;
   - never recursively remove a path that was not created and recorded by this installer.
7. Add an incomplete/transaction marker and startup recovery if a process death between renames can otherwise strand the title.
8. Do not publish the persistent game entry or queue compilation until commit succeeds. A temporary UI preview may be shown, but it must not make a partial folder playable.

### P0: Path and title validation

Both `TITLE_ID` and decoded ISO names currently participate in filesystem joins.

Required invariants:

- Validate GTA/PS3 title IDs with the expected PS3 form before using them as a path component. For the current importer, accept only the documented supported format such as `^[A-Z]{4}[0-9]{5}$`; expand only with evidence for another valid supported format.
- Reject empty entry names, `.`, `..`, embedded NUL, `/`, `\`, rooted/absolute paths, drive/root names, and any component whose normalized form escapes staging.
- Construct targets from validated individual components.
- Lexically normalize and containment-check every destination against the normalized staging root before creating or writing it.
- Skip only the intended root `PS3_UPDATE` directory, using the ISO lookup's defined case rules.
- Detect duplicate normalized destination paths instead of silently overwriting them.

### P0: Strict multi-extent chain validation

The reader must distinguish an exact on-disc identifier from its UI/path-normalized name.

Required invariants:

- Preserve the exact decoded ISO identifier, including `;version`, for chain identity.
- Normalize `;version` only for exposed lookup/display names.
- Start a chain only when the first record has bit `0x80` set.
- Require following extents to be immediately consecutive records with the exact same raw identifier.
- Require a terminating extent with bit `0x80` clear.
- Reject missing-final, mismatched-name, interleaved, duplicate-version, and invalid-directory chains.
- Sum lengths in `u64` with explicit overflow checks.
- Ensure every extent's LBA and rounded block range is inside the declared volume and physical block device.
- Ensure `stat`, `open`, and directory enumeration expose the same checked total size.

### P0: Directory parser must fail, never hang

Required behavior:

- A zero/short block read returns a parse/I/O failure; it must not leave the block cursor unchanged in a loop.
- Validate the fixed directory-record header before reading fields.
- Validate `entry_length`, filename length, remaining buffer size, and remaining bytes in the current logical block.
- Do not assume a block size is a power of two when advancing over zero padding.
- Validate the primary/supplementary descriptor's logical block size, declared block count, and root directory range.
- Propagate directory-read failure through `open_entry`, `open_dir`, manifest construction, and `installIso`.
- Distinguish a valid empty directory from a failed directory read.

### P0: Missing EBOOT must fail

`locateEbootPath` can currently return an empty path, while `CompilationQueue` treats an empty workload as success.

Required behavior:

- Locate and verify `EBOOT.BIN` inside staging before commit.
- Require it to be a regular, readable, non-empty file.
- Treat an empty compilation path as an importer error; do not queue it as successful work.
- Queue compilation only for the committed final path.

### P1: Progress and cancellation correctness

- Check every `iso.open_dir()` call in both manifest and extraction passes.
- Report extraction completion using `report(++processedFiles, filesCount)` so the final file reaches `N/N`.
- Give scanning, extracting, verifying, committing, and PPU compilation distinct messages.
- Preserve cancellation/failure through staging cleanup without deleting a previous valid title.
- Ensure completion is sent exactly once and only after the final title is committed and compilation has either been successfully queued or deliberately separated as its own job.

## Target Component Boundaries

Keep `installIso` as orchestration. Avoid adding more parsing, security, or transaction details directly to `rpcsx-android.cpp`.

Suggested cohesive components:

1. `iso_dev` / ISO parser
   - volume validation;
   - exact directory records;
   - strict multi-extent assembly;
   - bounded streaming read/seek/stat.
2. `IsoInstallManifest`
   - walks the ISO once;
   - validates relative components and duplicates;
   - stores directories/files and checked expected sizes;
   - identifies required `PARAM.SFO` and `EBOOT.BIN`.
3. `AtomicFileCopier`
   - bounded copy buffer;
   - exact source/read/write/final-size checks;
   - neighboring temporary file with sync and atomic commit;
   - injectable failure seam for tests.
4. `StagedGameInstaller`
   - owns staging and backup paths;
   - extracts the manifest;
   - verifies staging;
   - commits or rolls back;
   - performs recovery/cleanup only for paths it owns.
5. Android/JNI orchestration
   - reads SFO metadata;
   - drives progress callbacks;
   - publishes the committed game;
   - queues compilation for the committed EBOOT.

Names may change to match project style, but these responsibilities must not be recombined into one large importer function.

## Implementation Sequence

### Phase 1: Preserve evidence and establish a baseline

1. Record root and submodule `git status --short`.
2. Save the current submodule diff to a temporary review artifact; do not rewrite the tracked patch yet.
3. Verify the source ISO size and SHA-256 in `/sdcard/Download` on `7d6afed8`.
4. Verify the firmware PUP remains in Downloads.
5. Record any currently installed GTA sizes before uninstalling; this is evidence only and must not be used as proof for the rebuilt app.

### Phase 2: Complete parser correctness

1. Finish and compile the draft optional/error-propagating `read_dir` changes.
2. Introduce exact raw identifiers alongside normalized exposed names.
3. Implement a strict chain assembler with checked size arithmetic.
4. Validate volume, root, directory, and extent ranges.
5. Make `stat`, `open`, and `open_dir` share the same assembled-chain result.
6. Ensure zero-byte files remain valid and do not allocate or read an invalid zero-sized buffer.

### Phase 3: Add safe manifest and transaction layers

1. Build the manifest with path validation and duplicate detection.
2. Extract only into a unique staging directory.
3. Verify every staged regular file's exact size and required metadata/executable files.
4. Implement same-filesystem commit, backup, rollback, and stale-transaction recovery.
5. Publish game metadata and queue PPU compilation only after successful commit.
6. Fix progress phase messages and off-by-one reporting.

### Phase 4: Focused automated tests

Add the smallest maintainable native/unit-test seam supported by this tree. Tests must cover:

1. The real GTA chain shape: first extent `1,073,739,776`, final extent `406,073,437`, total `1,479,813,213`.
2. Reads wholly within each extent and reads crossing the extent boundary.
3. `seek_set`, `seek_cur`, `seek_end`, EOF, one-byte, block-boundary, and zero-length reads.
4. Missing terminal extent.
5. Mismatched/interleaved raw identifier.
6. `NAME;1` versus `NAME;2` collision after normalization.
7. Short and zero block-device reads.
8. Directory records truncated at header, filename, and block boundaries.
9. Extent range outside the declared volume/device.
10. Checked length overflow.
11. Zero-byte file copy.
12. Short source read, short destination write, and commit failure.
13. Traversal/rooted/NUL/separator components and invalid title IDs.
14. Duplicate normalized destination paths.
15. Injected extraction failure preserving the old installed title byte-for-byte.
16. Missing `PARAM.SFO` and missing/empty `EBOOT.BIN`.
17. Successful staging commit and recovery from each interrupted rename state.

### Phase 5: Build and package

1. Build the ARM64 RPCSX release core using the repository's native build script.
2. Confirm the rebuilt `librpcsx-android.so` is copied into the APK inputs.
3. Run relevant unit tests and assemble the standard debug APK.
4. Stop Gradle after every Gradle command, including failed commands. Use a shell pattern that preserves the original task exit code:

   ```bash
   task_status=0
   ./gradlew <tasks> || task_status=$?
   ./gradlew --stop
   exit "$task_status"
   ```

5. Verify no `GradleDaemon` remains.
6. Verify the APK contains the rebuilt ARM64 library and expected new diagnostic strings.

### Phase 6: Fresh Snapdragon 8 Gen 3 installation and import

1. Confirm `7d6afed8` reports model OPD2403 and SoC `SM8650`.
2. Confirm the full ISO and firmware PUP are present in public Downloads before uninstalling.
3. Uninstall `com.zenithblue.sambas3`, install the rebuilt standard debug APK, and warm through `MainActivity`.
4. Install firmware 4.92 through the app and wait for explicit completion. Automatic VSH PPU precompilation remains disabled unless the implementation task explicitly changes that policy.
5. Import the GTA ISO through the normal SAF/UI path; do not seed the final game folder manually.
6. Monitor progress and logs. A generic permanent `Importing...` state is not acceptable; scanning/extracting/verifying/committing/compiling phases must be distinguishable.
7. After commit, verify on-device sizes for all baseline files.
8. Compute on-device SHA-256 for both OBB files and compare to the baseline table.
9. Confirm there is no incomplete marker, staging directory, backup directory, or neighboring pending file left after success.
10. Confirm `games.json` contains exactly the committed final title path and no nested-path duplicate.

### Phase 7: Vulkan and game-boot preflight

1. Confirm global and per-game renderer selection is Vulkan.
2. Disable the historical `Skip null modelinfo crash` patch; it is harmful with complete data.
3. Keep 720p/100%, MSAA disabled, Async Shader Recompiler, and on-disk shader cache for the validation run.
4. Launch safely through initialized `MainActivity`, then `RPCSXActivity` with the committed GTA path.
5. Wait for first-run PPU compilation rather than bypassing required cache work.
6. Require backend evidence for the Snapdragon GPU and Vulkan renderer initialization. Reject software/null rendering.
7. Reject the run on `Access violation`, `Emulation has been frozen`, fatal signal, `VK_ERROR_DEVICE_LOST`, or missing-data/modelinfo signatures.

### Phase 8: Automated GTA intro checkpoint

Use the debug controller broadcast bridge so rotation cannot misroute touches.

1. Verify `DebugPadReceiver` is registered after `MainActivity`/`RPCSXActivity` starts.
2. Capture an initial screenshot and backend-log timestamp.
3. Poll screenshots/log state instead of sending blind rapid input during PPU compilation.
4. Send deterministic Cross/Start/D-pad pulses as required to pass:
   - initial title/continue prompt;
   - EULA/confirmation screens;
   - main menu;
   - Start Game;
   - New Game.
5. Allow the intro video/cutscene to play; do not treat a long decoder/shader pause as success.
6. Capture checkpoints showing:
   - menu progression;
   - active intro cutscene;
   - CJ in the Ballas territory scene after the cutscene transition;
   - controllable gameplay/HUD immediately afterward if available.
7. Confirm controller input is accepted at the final checkpoint.
8. Pull backend, Vulkan, app, and crash logs plus screenshots into an attempt-specific directory.

## Failure Loop

Repeat until the acceptance criteria pass. Each iteration must change only the subsystem implicated by evidence.

1. Create `/tmp/gta-sm8650-attempt-N/` and save APK identity, configuration, screenshots, file sizes/hashes, and logs.
2. Classify the earliest failure:
   - import/size/hash mismatch;
   - parser/short-read error;
   - transaction/staging residue;
   - missing EBOOT/boot failure;
   - PPU compilation failure;
   - Vulkan/renderer failure;
   - controller/menu automation failure;
   - video/cutscene failure;
   - GTA modelinfo/access violation;
   - timeout with continuing work versus true hang.
3. Fix only that cause and add a regression test before rebuilding.
4. Rebuild and always stop Gradle.
5. Fresh-uninstall/reinstall whenever importer correctness or persisted app state could affect the result.
6. Reimport from the public Downloads ISO; never copy a host-extracted game folder as a substitute.
7. Rerun from boot through the final checkpoint.
8. Do not claim success from an old cache, old installed folder, or screenshot from another device/run.

## Acceptance Criteria

All conditions must be true in one final fresh-install run on `7d6afed8`:

- Native/unit tests pass, including malformed multi-extent and transaction-failure cases.
- Standard debug APK builds successfully and Gradle is stopped.
- Firmware installation completes without automatic VSH compilation starting in the library UI.
- GTA ISO import completes through the app's normal import path.
- `PS3DataMain.obb` is exactly `1,479,813,213` bytes with SHA-256 `8cdcca8047ce083b4cf27316f266affebc7c565ccee38642029ff327397f680f`.
- `PS3Data.obb` is exactly `708,640,703` bytes with SHA-256 `484b4fd07331d00ca6aeea6758772d848e166ee45bb885ba17a2dc115e58d905`.
- `PARAM.SFO` and `EBOOT.BIN` exist, are readable, and have the baseline sizes.
- No partial/staging/backup/pending artifact remains after success.
- Vulkan initialization is proven in the final launch logs.
- No fatal/access-violation/frozen/device-lost/missing-modelinfo signature occurs in the final launch window.
- Screenshots and logs prove progression through the intro and arrival at the requested CJ/Ballas-territory checkpoint.
- Controller input works at or immediately after that checkpoint.
- `patches/rpcsx-submodule-changes.patch` is regenerated from the final submodule diff and reverse-apply checked.
- Documentation records the verified Snapdragon 8 Gen 3 result and exact import hashes.
- Final changes are committed locally only; nothing is pushed.

## Required Evidence Bundle

Store final evidence under a timestamped `/tmp/gta-sm8650-pass-YYYYMMDD-HHMMSS/` directory:

- APK path, size, SHA-256, version code/name, and embedded native-library identity.
- Device model, SoC, Android version, and Vulkan renderer lines.
- Source ISO/PUP sizes and hashes.
- Installed GTA manifest, sizes, and OBB hashes.
- Import/PPU/boot backend log window.
- Vulkan and crash logs.
- Screenshots for menus, cutscene, Ballas-territory checkpoint, and controllable gameplay.
- Exact controller commands and timestamps.
- Test/build command results and confirmation that Gradle was stopped.
- Final root/submodule status and local commit ID.

## Worker Agent Prompt

```text
Work in /home/abhaybyte/repos/samba-s3 and execute docs/plans/gta-iso-import-and-intro-validation-plan.md completely.

First read the repository AGENTS.md and the full sambas3-game-launch, sambas3-controller, and sambas3-logs SKILL.md files. Treat the current dirty root/submodule worktree as valuable in-progress work: preserve it, inspect it, and never reset or discard it. The current ISO parser includes unbuilt draft fail-safe edits, and patches/rpcsx-submodule-changes.patch may be stale.

Implement a cohesive, decoupled, fail-safe ISO importer. Resolve every P0/P1 finding in the plan: strict exact-identifier multi-extent assembly, bounded parser errors, path/title containment, complete staging manifest, whole-title transaction/rollback, required PARAM.SFO/EBOOT validation, correct progress, and no publication or PPU queueing before commit. Add focused regression tests for malformed input and injected transaction/copy failures.

Use 7d6afed8 (OPD2403, Snapdragon 8 Gen 3/SM8650) as the only primary validation device. Build the ARM64 native core and standard debug APK. After every Gradle invocation, including failures, run ./gradlew --stop and verify no GradleDaemon remains. Fresh-uninstall/install the app, retain the firmware PUP and full GTA ISO in public Downloads, install firmware, and import the ISO through the real app UI/SAF path. Do not manually seed an extracted final game folder.

Verify the installed GTA files by exact size and SHA-256 from the plan. Require PS3DataMain.obb = 1,479,813,213 bytes and SHA-256 8cdcca8047ce083b4cf27316f266affebc7c565ccee38642029ff327397f680f. Disable the obsolete null-modelinfo skip patch, require Vulkan, launch GTA, and use the debug controller broadcast bridge to progress through the prompts, EULA, Start Game, New Game, and intro cutscene. Continue evidence-driven fix/rebuild/fresh-import/test loops until a single fresh run reaches CJ in Ballas territory after the opening cutscene with working input and without fatal, frozen, device-lost, access-violation, or modelinfo errors.

Save attempt artifacts and the final pass bundle exactly as described in the plan. Update the root submodule patch only after the final implementation passes, reverse-apply check it, update relevant docs, and make a local commit. Do not push. Provide concise progress updates during long compilation/gameplay waits and do not stop at partial import, title screen, or cutscene-only evidence.
```
