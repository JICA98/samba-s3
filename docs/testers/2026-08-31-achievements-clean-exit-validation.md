# SambaS3 achievements and clean-exit validation — 2026-08-31

## Source and build provenance

- Baseline root: `b21dc01849cb44e18945c322ad250949711cf54b`
- RPCSX source: `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`
- Patch SHA-256: `1fd9a2e6992540f52c7235ad69c26ea59e695f6e2dbf0c75692828072e3097f5`
- APK: `app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk`
- APK SHA-256: `cdf522e067ee96e6f2e1a4643447e836cbd4a1dae9522af6421bf82c95ecd6ee`
- Stripped ARM64 RPCSX core SHA-256: `2d2640ca83310ecb4ab852c5968bbd638609b6e23ff8e563d09d6bccf4226ab5`
- Stripped x86_64 RPCSX core SHA-256: `6dbc780a7a3e387160aa15682cbd0832b413d10a995602186f53ddcf951786f1`
- Device: `OPD2403`, Vulkan Turnip Adreno (TM) 750. The requested ADB endpoint reconnected during validation as `adb-7d6afed8-mU47CV (2)._adb-tls-connect._tcp`.

The runtime logged `S3CAP boot_savestate=1 load_state=1 surface_v2=1` and the core was built with the RPCSX patch above. No real `TROPUSR.DAT` or game savestate was edited by the tests.

## Exact code audit

- Live and stopped-title queries now converge in [`AchievementRepository.kt`](../../app/src/main/java/com/zenithblue/sambas3/ui/achievements/AchievementRepository.kt), consumed by [`InGameTrophiesPage.kt`](../../app/src/main/java/com/zenithblue/sambas3/ui/ingame/InGameTrophiesPage.kt), [`StoppedTrophiesDialog.kt`](../../app/src/main/java/com/zenithblue/sambas3/ui/games/launch/StoppedTrophiesDialog.kt), and [`AchievementsContent.kt`](../../app/src/main/java/com/zenithblue/sambas3/ui/achievements/AchievementsContent.kt).
- The cache key is title ID + RPCS3 user + trophy set + TROPUSR generation/fingerprint. Unlock invalidation is wired through `AchievementEvents`; Home reloads after invalidation and does not poll.
- Rich `S3TROPHY` logging records source, title, user, set, TROPUSR path/existence/size/mtime/generation, cache key/result, totals, unlocked IDs, and query duration.
- Explicit exit is coordinated by [`EmulatorStopCoordinator.kt`](../../app/src/main/java/com/zenithblue/sambas3/session/EmulatorStopCoordinator.kt) and finalized by `EmulationSessionJournal`: native `Stopped` → trophy flush boundary → durable `CLEAN_STOP`/`FAILED` → `activeGame=null` → host unregister/finish.
- [`HomeRecoveryRepository.kt`](../../app/src/main/java/com/zenithblue/sambas3/crash/HomeRecoveryRepository.kt) gives current-session fatal evidence precedence and ignores historical GPU lines after a matching clean terminal record. [`CrashEvidenceCollector.kt`](../../app/src/main/java/com/zenithblue/sambas3/crash/CrashEvidenceCollector.kt) reads bounded log tails.
- [`DebugPadReceiver.kt`](../../app/src/main/java/com/zenithblue/sambas3/debug/DebugPadReceiver.kt) provides the debug-only simulated fatal path. `MainActivity` unregisters its receiver while paused so the Home and emulator activities cannot both consume one PS broadcast.

## Trophy mismatch root cause and parity

The mismatch was caused by live and Home/title surfaces not sharing one snapshot/cache identity. Home could retain a stopped-title snapshot that had been read before the unlock, while live used the current runtime state. The repair uses one provider, one user/set identity, TROPUSR fingerprinting, event invalidation, and the existing persistence boundary before Home refresh.

Captured final-build `S3TROPHY` records agree on:

| Stage | Query | Total | Unlocked | Getting Started | User | Set | Cache key/result |
|---|---|---:|---:|---|---|---|---|
| In game | `live` | 33 | 1 | `BRONZE · UNLOCKED` | `00000001` | `NPWR10029_00` | `BLUS31584|00000001|NPWR10029_00|{}:{}` / miss |
| After Exit | `title` | 33 | 1 | `BRONZE · UNLOCKED` | `00000001` | `NPWR10029_00` | same / miss |
| Reopen | `title` | 33 | 1 | `BRONZE · UNLOCKED` | `00000001` | `NPWR10029_00` | same / miss |
| After SAVE/LOAD | `live`/`title` | 33 | 1 | `BRONZE · UNLOCKED` | `00000001` | `NPWR10029_00` | same identity |

TROPUSR for all records was `/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/dev_hdd0/home/00000001/trophy/NPWR10029_00/TROPUSR.DAT`, present and `6976` bytes. Final trace: [`final-build-device-trace.txt`](artifacts/2026-08-31-achievements-clean-exit-validation/logs/final-build-device-trace.txt).

## Achievements UI

The shared browser shows `1/33 unlocked · 3%`, Bronze/Silver/Gold/Platinum counts, real trophy artwork, All/Unlocked/Locked and grade filters, Show Hidden, sorting, and a selectable detail view with title, grade, status, description, and timestamp metadata when present. It adapts to the tablet layout and remains controller/keyboard reachable; Compose content descriptions and readable text are present in the UIAutomator captures. The old repeated “Platinum relevant” presentation is gone.

Final-build image evidence is in [`artifacts/2026-08-31-achievements-clean-exit/`](artifacts/2026-08-31-achievements-clean-exit/), including in-game/Home summary, filter, unlocked, detail, clean Home, and recovery-card/details captures for both passes.

## False-crash root cause and clean-exit fix

The false card was produced when Home classified an expected stop using broad/stale evidence before terminalization. The repair makes the exit transaction session-scoped and waits for the real native terminal state. Historical Vulkan/driver text cannot taint a later `CLEAN_STOP`; a fatal marker keeps a failed session from being mislabeled clean.

Representative final trace ordering:

```text
S3EXIT event=requested ... stopReason=InGameExit ... fatalEventId=none
S3EXIT event=STOPPING ... journalState=STOPPING
S3EXIT event=native-Stopped ... nativeState=Stopped
S3EXIT event=trophy-flush-complete ... boundary=native-stop
S3EXIT event=CLEAN_STOP ... journalState=CLEAN_STOP ... fatalEventId=none
S3EXIT event=activeGame-null ... activeGame=null
S3EXIT event=finish-external-stop ... activeGame=null
S3EXIT event=host-unregistered ... nativeState=Stopped
S3RECOVERY classification=None reason=CLEAN_STOP
```

| Test | Native final | Journal final | Recovery classification | Card | Pass |
|---|---|---|---|---|---|
| Normal Exit | Stopped | CLEAN_STOP | None | No | PASS |
| Exit after Achievements | Stopped | CLEAN_STOP | None | No | PASS |
| Exit after PS menu → resume → PS menu | Stopped | CLEAN_STOP | None | No | PASS |
| Exit after SAVE → restore | Stopped | CLEAN_STOP | None | No | PASS |
| Exit after LOAD → gameplay | Stopped | CLEAN_STOP | None | No | PASS |
| Force-stop during active/restore session | process interrupted | unclean | Interrupted | stopped unexpectedly | PASS/expected |

## Real-crash recovery UI

The debug-only `DEBUG_FATAL` test produced `S3CRASH in-process fault evidence=DEBUG_SIMULATED_FATAL VK_ERROR_DEVICE_LOST`, a session `FAILED` terminal, and the compact Home card `BLUS31584 crashed` with `CONTINUE SAVE`, `RETRY`, `DETAILS`, and `DISMISS`. Details visibly contain `CHOOSE SAVE`, `SAFE RETRY`, `VIEW LOGS`, and `EXPORT REPORT`; Export Report opened the Android chooser and returned safely. Recovery card images are `pass1-real-crash-card.webp`, `pass1-real-crash-details.webp`, `pass2-real-crash-card.webp`, and `pass2-real-crash-details.webp`.

| Test | Fatal evidence | Journal | Classification | Card wording | Pass |
|---|---|---|---|---|---|
| Simulated renderer fatal | current session yes | FAILED | ConfirmedCrash | `BLUS31584 crashed` | PASS |
| Process force-stop | no fatal signal | unclean | Interrupted | `stopped unexpectedly` | PASS/expected |
| Historical Vulkan line + clean Exit | historical only | CLEAN_STOP | None | none | PASS |

## Stress results

All rows below ran against the unchanged final APK SHA-256 above. Every normal/specialized cycle returned to Home with no card; final journals had no `STOPPING` leftover and clean-stop traces ended with `activeGame=null`.

| Matrix | Result |
|---|---:|
| Normal in-game Exit | 20/20 PASS |
| Exit after Achievements → close → Exit | 5/5 PASS |
| SAVE → restore → Exit | 5/5 PASS |
| LOAD → gameplay → Exit | 5/5 PASS |
| PS menu → resume → PS menu → Exit | 5/5 PASS |
| False crash cards in clean matrices | 0 |

## Automated gates

- `./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest --no-daemon` — PASS.
- `./gradlew :app:assembleStandardDebug --no-daemon` — PASS.
- `./build_rpcsx.sh debug` — PASS for `arm64-v8a` and `x86_64`.
- Provider parity fixture (33 trophies, 1 unlocked), wrong-user/set identity, cache invalidation, UI filters/detail, recovery classifier, and session-scope tests — PASS.
- Final source `git diff --check` is clean for the changed source/docs; generated RPCSX patch context retains its pre-existing whitespace warnings only.

## Final Pass 1

On the final APK: in-game achievements showed `1/33` and `Getting Started` unlocked; unlocked filter and detail were exercised; explicit Exit reached `Stopped`, trophy flush, `CLEAN_STOP`, `activeGame=null`, and Home with no card; Home achievements showed the same `1/33`; force-stop/reopen preserved the state with no card. SAVE/LOAD and the clean-exit matrices above were green on this same APK.

Evidence: `pass1-ingame-achievements-summary.webp`, `pass1-ingame-achievements-unlocked.webp`, `pass1-ingame-achievements-filters.webp`, `pass1-ingame-achievements-detail.webp`, `pass1-home-achievements-summary.webp`, `pass1-home-achievements-unlocked.webp`, `pass1-home-achievements-detail.webp`, `pass1-home-after-clean-exit.webp`, and `final-build-pass1-home-reopen.png`.

## Final Pass 2

With no code changes after Pass 1: the same in-game/Home parity, filters/detail, clean Exit, and force-stop/reopen flow passed again on the same APK. The strict PS menu/resume matrix and SAVE/LOAD matrices were also run without source changes. The `pass2-real-crash-*` images preserve the verified final-build fatal capture because a second optional `DEBUG_FATAL` injection lost the ADB transport; they are not presented as a second independent fatal run.

Evidence: `pass2-ingame-achievements-summary.webp`, `pass2-ingame-achievements-unlocked.webp`, `pass2-ingame-achievements-filters.webp`, `pass2-ingame-achievements-detail.webp`, `pass2-home-achievements-summary.webp`, `pass2-home-achievements-unlocked.webp`, `pass2-home-achievements-detail.webp`, `pass2-home-after-clean-exit.webp`, and `final-build-pass2-home-reopen.png`.

## Fatal grep and remaining risks

The intentional fatal is the only `ConfirmedCrash`/`S3CRASH` event recorded during validation. Clean-exit trace lines contain no fatal event and all terminal records are `CLEAN_STOP`; no unintentional crash card was observed in the required stress matrices. The only observed interrupted card was deliberately induced by force-stopping an active restore session and was dismissed through the recovery UI.

Remaining risk is limited to device-specific Vulkan/Turnip behavior outside this OPD2403 validation matrix; no evidence implicates the repaired trophy or exit paths.
