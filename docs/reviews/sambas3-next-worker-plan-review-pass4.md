# Plan Review: sambas3-next-worker — PASS 4

## Verdict: APPROVE
## Counts: CRITICAL 0 MAJOR 0 MINOR 0 SUGGESTIONS 1

### Context
Fourth pass verification of `docs/plans/sambas3-next-worker-plan.md` per user instruction. Checks residual 3 fixes (File Map JNI jstring, VSH grep -A2, reset-ppu-cache --manifest-only) plus regression scan of prior APPROVE-gated items (Phases 0-7). Pass 1-3 reviews not present under `docs/reviews/` for this slug; treated as fresh comprehensive review with explicit residual gates.

### Residual Fix Verification (user-gated)

#### [RESOLVED] File Map JNI jstring not std::string
- Location: `docs/plans/sambas3-next-worker-plan.md:78,116-118` and File Map row `PPUThread.cpp`
- Required: JNI export must be `extern "C" jstring Java_net_rpcsx_RPCSX_getPpuManifestKey(JNIEnv* env, jclass, jstring jTitleId)` returning `env->NewStringUTF(...dump().c_str())`, not `std::string` across C/JNI boundary.
- Evidence: `read` line 78 now states `extern "C" jstring Java_net_rpcsx_RPCSX_getPpuManifestKey(JNIEnv* env, jclass, jstring jTitleId)` with `returns env->NewStringUTF(ppu_manifest_key(...).dump().c_str()) — do not return std::string across C boundary`. File Map line 116 mirrors `expose extern "C" jstring Java_net_rpcsx_RPCSX_getPpuManifestKey(JNIEnv* env, jclass, jstring jTitleId) wrapping ppu_manifest_key at PPUThread.cpp:317-353 (lift static to exported helper ppu_manifest_key_exported)`. Previous defect (std::string return) no longer present; `grep std::string` now only hits warning text, not signature.
- Impact if not fixed: ABI mismatch, `std::string` dtor across `.so` boundary, memory corruption/crash on `getPpuManifestKey()` call; PpuReadinessStore fingerprint would crash.
- Status: PASS. Wire also correctly cites `rpcsx-android.cpp Java_net_rpcsx_RPCSX_getPpuManifestKey + native-lib.cpp dlsym / RPCSX.getPpuManifestKey(String)` staying single source of truth (cache_abi v7-kusa at PPUThread.cpp:199, llvm_cpu at JITLLVM.cpp:514, ppu_settings at PPUThread.cpp:5410-5426, bin_patch.h:31 + patchesList at rpcsx-android.cpp:2715/2717).

#### [RESOLVED] VSH grep -A2
- Location: `docs/plans/sambas3-next-worker-plan.md:54,60` (Phase 0.3 and Phase 1.5 verification commands)
- Required: VSH queue absence check must handle two-line `g_compilationQueue.push(...)` + `vsh.self` split across lines; `grep -A2` needed, not bare `grep -n "vsh.self" ==0`.
- Evidence: line 54 now: `check with grep -A2 "sendVshBootable" patches/...`; line 60 now: `! grep -A2 "g_compilationQueue.push" patches/... | grep -q "vsh\.self" (or grep -n "vsh\.self" ... ==0)`. Prior revision used bare `grep -n "vsh.self"` which would miss multi-line push. Patch under review currently contains VSH push at `patches/rpcsx-submodule-changes.patch:39-44` (`sendVshBootable(env, progressId); g_compilationQueue.push(progress, g_cfg_vfs.get_dev_flash()+"/vsh/module/vsh.self")`) — verification correctly targets its removal to `sendVshBootable(env,-1); progress.success(...)`.
- Status: PASS. Both forward (`grep -A2 "sendVshBootable"`) and alternative (`grep -A2 "g_compilationQueue.push"`) forms present and logically sound.

#### [RESOLVED] reset-ppu-cache --manifest-only
- Location: `docs/plans/sambas3-next-worker-plan.md:103,127` (Phase 6.26 + File Map)
- Required: `scripts/perf/reset-ppu-cache.sh` must accept `--manifest-only` flag deleting only `ppu_manifest/<ID>.json` for perf experiment B isolation (warm/no-manifest vs warm/manifest with identical objects), default deletes both cache + manifest, guarded on TITLE_ID_PATTERN, refuses empty/wildcard.
- Evidence: line 103: ``reset-ppu-cache.sh --title-id <ID> [--manifest-only] guarded (refuse empty/wildcard; default deletes cache/cache/<ID> + ppu_manifest/<ID>.json after verifying TITLE_ID_PATTERN; --manifest-only deletes only ppu_manifest/<ID>.json for perf B isolation)``. File Map row 127 lists `scripts/perf/capture-sambas3.sh + reset-ppu-cache.sh + parse-ppu-profile.py + summarize-run.py (new) | create scripts/perf/ (currently missing), perf harness, guarded cache reset (TITLE_ID_PATTERN), log parsing`. Aligns with Acceptance Criteria B isolation requirement (line 144: `B is warm/no-manifest vs warm/manifest (delete manifest only, same objects) not cold vs warm`).
- Status: PASS.

### Prior APPROVE Items Regression Scan (5 Axes)

**1. Architecture & Ownership — PASS**
- Verified real files: `app/src/main/cpp/rpcsx` pin e8ae148 vs 685e353 (`bash: git -C app/src/main/cpp/rpcsx rev-parse HEAD` = 685e353aa, `e8ae1481a` baseline), `app/src/main/java/com/zenithblue/sambas3/utils/FileUtil.kt:36-144` exists (GameFolderMatch, scanGameFolder), `GameRepository.kt:32-98` exists (GameInfoStore "$", GameIdentity), `app/src/main/cpp/native-lib.cpp` exists (RPCSXApi dlsym table), `app/build.gradle.kts:62-75` flavors standard/playstore with `INCLUDE_BUNDLED_TURNIP_DRIVERS`/`IS_PLAYSTORE_BUILD` correctly cited. File Map changes stay minimal and authoritative.

**2. Data / Control Flow & API Contracts — PASS**
- `FileUtil.scanGameFolder()` BFS over `SimpleDocument` from `listFilesStrict()` at `FileUtil.kt:351-391` (DocumentsContract COLUMN_DISPLAY_NAME/MIME_TYPE/SIZE + buildChildDocumentsUriUsingTree) correctly extended in plan to query MIME_TYPE_DIR and COLUMN_SIZE. Candidate `GameLibraryCandidate(sourceUri, sourceKind DIRECTORY|ISO, displayName, titleId, sizeBytes)` keeps `GameFolderMatch` as deprecated alias — no breakage. ISO titleId flow: filename regex `([A-Z]{4}\d{5})` provisional + bounded native `isoProbeTitleId(fd) -> Pair<titleId?, bytesRead>` via ParcelFileDescriptor → android_fd_file counting file_view_block_dev 64 blocks (~128 KiB) wrapped, bytes_read <1MiB assertion prevents multi-GB read. Contract `RPCSX.getPpuManifestKey(String): String` via jstring correctly bridges native fingerprint.
- `GameRepository.add()` dedupe via `GameIdentity.key()` at `GameRepository.kt:82-98,204-269` and `save() filter info.path != "$"` at line 119 preserved; plan's `$` placeholder non-rendering + `content://` filter avoids injecting SAF URIs into `preferPath`/`collectGameInfo()`.

**3. Lifecycle & Threading — PASS**
- `g_emulator_lifecycle_mutex` ordering at `rpcsx-android.cpp:1962-2012` (surfaceEvent boot/kill gate) preserved per plan Phase 0.5. `CompilationQueue::impl()` lifecycle mutex at `rpcsx-android.cpp:1380` plus `g_mainThreadProcessor` dispatch for compile progress retained. `PrecompilerService.kt:90 currentInstallIsFirmware` clearing `FirmwareRepository.progressChannel` vs `GameRepository.activeInstallProgress` correctly avoids firmware-origin PPU after terminal `progress.success()`; warmup gated by `GeneralSettings.firmwarePpuWarmup` default false with `origin=firmware_warmup` S3PPU separate notification/jobId.
- ISO import progress ownership `ProgressRepository.createForeground()` with `progressId=3000` attached via `GameRepository.activeInstallProgress → addOrUpdateLocked() with copyProgress()` preserves identity across ISO→dir migration; `S3UI progress_attach/identity_merge/progress_detach` sequence coherent.

**4. Persistence / Storage & Error Handling — PASS**
- Cumulative patch regeneration recipe is correct: `git -C rpcsx reset --hard e8ae148 && git clean -fdx`, then `cherry-pick --no-commit 685e353` (20-file bulk 2481±421) layered with PPU delta (1271-line), then `add -A && git diff --cached > patches/... && git reset`. Verified current patch is delta vs 685e353 (1271 lines, 12 files, 0 hits atomic-file-copier) while `git diff e8ae148..685e353` is 20 files; plan's grep `atomic-file-copier|iso-install-manifest|staged-game-installer|_rpcsx_setCompileProgressListener >=1` and `ppu_manifest >=1` gates catch missing bulk. Pin via `git checkout e8ae148 && git add app/src/main/cpp/rpcsx patches/... && git diff --cached --stat` (not update-index) correctly noted.
- `PpuReadinessStore` at `config/prefs/ppu_state.json` versioned, keyed by `GameIdentity.key()`, fingerprint via `RPCSX.instance.getPpuManifestKey(titleId)` single source; invalidation on mismatch logged `S3PPU event=invalidate reason=...`. `Telemetry.kt` new file with `S3LIFE/S3LIB/S3PPU/S3UI/S3DRV/S3PERF`, session `S3-<epoch_ms>-<4hex>` (SecureRandom 2 bytes), JSONL at `<external-files>/perf/<session>.jsonl` 5MiB cap, gated `isEnabled = DEBUG || enablePerfCapture` before __android_log_print/JSONL — overhead gate validated.
- `removeGame()` at `FileUtil.kt:412-445` already guards canonical path + managed roots + TITLE_ID_PATTERN; new reset script mirrors same guard (refuse empty/wildcard, verify TITLE_ID_PATTERN).

**5. Compatibility & Testing & Scope — PASS**
- minSdk 29 / target 35 / compile 36 / NDK 30.0.14904198 at `app/build.gradle.kts:12-14,16,19` consistent with SAF DocumentFile usage (MIME_TYPE_DIR, buildChildDocumentsUriUsingTree already in FileUtil). No unrelated refactoring: `AppNavHost.kt:257` patches gate intentionally left (plan notes do not touch), only `OnboardingDestination.kt:269,305` IS_PLAYSTORE_BUILD → INCLUDE_BUNDLED_TURNIP_DRIVERS.
- Testing strategy adequate: patch gate forward+reverse, unit `FileUtilTest.isoProbeDoesNotReadFullFile` bytes_read cap, `PpuReadinessStoreTest` transitions + fingerprint invalidation, `GameRepository` dedupe/progress migrate, Telemetry disabled→no I/O / cap / JSON valid, instrumentation disabled ×3 vs enabled ×3 overhead ≤1-2% (grep steady_clock::now only inside if(isEnabled)), integration OnePlus Pad 2 / SD8Gen3 / Adreno 750 for A-H experiments with ≥3 runs median, thermalservice/battery capture, distinct warm/no-manifest vs warm/manifest.

### Findings

#### [SUGGESTION] JNI symbol naming consistency
- Location: `docs/plans/sambas3-next-worker-plan.md:78,116-118`
- Problem: Step 12 text lists `Java_net_rpcsx_RPCSX_getPpuManifestKey` alternative `Java_com_zenithblue_sambas3_RPCSX_getPpuManifestKey` and File Map row says `native-lib.cpp dlsym for _rpcsx_getPpuManifestKey / isoProbeTitleId`. Kotlin package is `com.zenithblue.sambas3` (per `app/build.gradle.kts:12 namespace`), so canonical JNI should be `Java_com_zenithblue_sambas3_RPCSX_getPpuManifestKey`. Using `net/rpcsx` alias risks mismatched `FindClass` string. Existing `native-lib.cpp` exports `Java_com_zenithblue_sambas3_RPCSX_*` (lines 141-385) and dlsym keys are `_rpcsx_*` (lines 88-119). New fingerprint should follow same split: rpcsx.so exports `extern "C" jstring _rpcsx_getPpuManifestKey(...)` or JNI `Java_net_rpcsx_RPCSX_...` but worker must pick one and keep `RPCSX.kt` `external fun getPpuManifestKey(String): String` JNI name in sync.
- Evidence: `app/src/main/cpp/native-lib.cpp:15-53` RPCSXApi struct + `app/build.gradle.kts:12` namespace.
- Impact: Low — worker will resolve at build time via `javap -s` or `nm -D`; mismatch would be caught by `nm -D lib_rpcsx.so | grep _rpcsx_getPpuManifestKey` verify step.
- Required planner change: None blocking. Worker: choose `com_zenithblue` JNI name; keep dlsym key `_rpcsx_getPpuManifestKey` if exporting via C ABI, or JNI name if exporting via JNI. Document chosen symbol in File Map before coding.

## Next Agent: Worker
## Next Action: Implement per File Map; run Phase 0 patch regeneration first and gate on `grep -n atomic-file-copier|iso-install-manifest|staged-game-installer|_rpcsx_setCompileProgressListener >=1` + `! grep -A2 "g_compilationQueue.push" | grep -q "vsh\.self"` + `apply --check` forward+reverse OK before touching Kotlin. Verify jstring ABI via `javap` and `nm -D` after native build; validate `--manifest-only` isolation with identical objects for perf B.
