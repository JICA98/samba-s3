# Achievements and clean-exit validation — 2026-08-31

## Build

- Root start: `b3aa1ae9d799a23fb93e97b1b334bf98bca2463c`
- RPCSX source: `657b26a0`
- APK: `app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk`
- APK SHA-256: `2a763ff86bc8ed8f3928f4bef6e5fe1fc0ac4c2bec65d166e481fa76d37a05c3`
- Stripped ARM64 core SHA-256: `2d2640ca83310ecb4ab852c5968bbd638609b6e23ff8e563d09d6bccf4226ab5`
- Device: `adb-7d6afed8-mU47CV._adb-tls-connect._tcp` / OPD2403
- Renderer: Vulkan, Turnip Adreno (TM) 750

The native core exports `_rpcsx_bootSavestate`; the installed runtime also logged
`S3CAP boot_savestate=1 load_state=1 surface_v2=1`.

## Fixes validated

The Home and in-game achievement surfaces now use the same `TrophySnapshotProvider`.
The snapshot identity includes title, RPCS3 user, trophy set, and TROPUSR generation.
Unlock events invalidate the shared cache immediately. Device traces include source,
title, user, set, TROPUSR identity, cache key, counts, IDs, and query duration.

The explicit in-game Exit path is single-owner and ordered through native `Stopped`,
the trophy flush boundary, durable `CLEAN_STOP`, `activeGame=null`, Activity finish,
and Home recovery classification. A fatal marker is preserved during cleanup so a
failed session cannot be reclassified as a clean exit.

Home recovery diagnostics read bounded log tails so a large rotated backend log cannot
delay the recovery card during startup.

## Device results

- Home opened directly after force-stop with no standalone crash page: PASS — [01-home-open.png](artifacts/2026-08-31-achievements-clean-exit-validation/01-home-open.png)
- Launch Center displayed the known save slots: PASS — [02-launch-center.png](artifacts/2026-08-31-achievements-clean-exit-validation/02-launch-center.png)
- Selected Slot 0 restored into gameplay using `UserSelectedSavestate`: PASS — [07-load-restored-game.png](artifacts/2026-08-31-achievements-clean-exit-validation/07-load-restored-game.png)
- Performance graph visible during gameplay: PASS — [07-load-restored-game.png](artifacts/2026-08-31-achievements-clean-exit-validation/07-load-restored-game.png)
- In-game achievements: `1/33`, `Getting Started`, `BRONZE · UNLOCKED`: PASS — [09-ingame-achievements.png](artifacts/2026-08-31-achievements-clean-exit-validation/09-ingame-achievements.png)
- Home achievements before exit: `1/33`, same unlocked trophy: PASS — [03-home-achievements.png](artifacts/2026-08-31-achievements-clean-exit-validation/03-home-achievements.png)
- Clean Exit reached native `Stopped` and durable `CLEAN_STOP`: PASS — [15-logcat-clean-exit.txt](artifacts/2026-08-31-achievements-clean-exit-validation/15-logcat-clean-exit.txt)
- Home after clean Exit retained `1/33`: PASS — [16-home-after-clean-exit-achievements.png](artifacts/2026-08-31-achievements-clean-exit-validation/16-home-after-clean-exit-achievements.png)
- Force-stop/reopen returned to Home without a false crash card and retained `1/33`: PASS — [17-home-after-force-stop-reopen.png](artifacts/2026-08-31-achievements-clean-exit-validation/17-home-after-force-stop-reopen.png), [18-home-after-force-stop-achievements.png](artifacts/2026-08-31-achievements-clean-exit-validation/18-home-after-force-stop-achievements.png)

## Two final passes on the unchanged APK

Both final passes used the same APK SHA-256 above after the last source change.

1. Pass 1: selected Slot 0 → gameplay/graph → PS menu → explicit Exit → native `Stopped` → trophy flush → Home `CLEAN_STOP`.
2. Pass 2: selected Slot 0 → gameplay/graph → PS menu → explicit Exit → native `Stopped` → trophy flush → Home `CLEAN_STOP` → force-stop/reopen → no recovery card.

Final pass evidence: [final-pass1-gameplay.png](artifacts/2026-08-31-achievements-clean-exit-validation/final-pass1-gameplay.png), [final-pass1-home-after-clean-exit.png](artifacts/2026-08-31-achievements-clean-exit-validation/final-pass1-home-after-clean-exit.png), [final-pass2-gameplay.png](artifacts/2026-08-31-achievements-clean-exit-validation/final-pass2-gameplay.png), and [final-pass2-home-reopen.png](artifacts/2026-08-31-achievements-clean-exit-validation/final-pass2-home-reopen.png). The final pass 2 trace is [final-pass2-logcat.txt](artifacts/2026-08-31-achievements-clean-exit-validation/final-pass2-logcat.txt).

The raw device logs are in [logs/](artifacts/2026-08-31-achievements-clean-exit-validation/logs/), including the selected-save `S3HOMELOAD` sequence, Vulkan initialization, `S3TROPHY` parity, and `S3EXIT` ordering.

### Trophy identity evidence

The captured native queries agree on the same identity and result:

| Surface | Source | RPCS3 user | Trophy set | TROPUSR identity | Total | Unlocked |
|---|---|---|---|---|---:|---:|
| In-game | live | `00000001` | `NPWR10029_00` | same path, `6976` bytes | 33 | 1 |
| Home | title | `00000001` | `NPWR10029_00` | same path, `6976` bytes | 33 | 1 |

The exact worker handoff images are in [artifacts/2026-08-31-achievements-clean-exit/](artifacts/2026-08-31-achievements-clean-exit/), including both passes' Home/in-game summaries, unlocked filters, details, and clean-exit states. The PNG/XML captures and complete pulled logs remain in [artifacts/2026-08-31-achievements-clean-exit-validation/](artifacts/2026-08-31-achievements-clean-exit-validation/).

Recovery UI evidence: [pass1-real-crash-card.webp](artifacts/2026-08-31-achievements-clean-exit/pass1-real-crash-card.webp), [pass1-real-crash-details.webp](artifacts/2026-08-31-achievements-clean-exit/pass1-real-crash-details.webp), [pass2-real-crash-card.webp](artifacts/2026-08-31-achievements-clean-exit/pass2-real-crash-card.webp), and [pass2-real-crash-details.webp](artifacts/2026-08-31-achievements-clean-exit/pass2-real-crash-details.webp). Pass 1 uses a session-scoped failed-session fixture to exercise the card deterministically; Pass 2 uses an actual `adb am force-stop` during gameplay and reports `stopped unexpectedly`.

## Automated gates

- `./build_rpcsx.sh debug`: PASS for `arm64-v8a` and `x86_64`
- `./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest --no-daemon`: PASS
- `./gradlew :app:assembleStandardDebug --no-daemon`: PASS
- `git diff --check`: existing whitespace warnings are limited to the generated RPCSX patch context; source changes are clean.

The intentional recovery-card exercise is kept separate from the clean-exit passes and is not represented by the older full-screen recovery capture.
