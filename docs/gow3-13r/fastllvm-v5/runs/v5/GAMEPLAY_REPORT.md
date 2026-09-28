# Fast LLVM v5 final gameplay report — 2026-09-28

## Outcome

**60 FPS / under 6 GB target not met. Campaign stopped after v5 at the user's request.** Final combat screenshot: HUD **11.1 FPS**, **9.8 GB device RAM used**, 2.0 GB available, app RSS 3.3 GB / PSS 3.4 GB and swap 2.2 GB. These are individual HUD observations, not matched benchmark averages. No gameplay FPS improvement is established.

The 120-second observation completed without the earlier RSX fatal or unmatched FIFO RET. The game stopped cleanly; all eight temporary settings were restored and the lease cleared. This short, uncontrolled run does not establish a permanent fix for the intermittent rendering failure.

## Candidate

- Retains v4 Android VMA 64 MiB preferred blocks and best-fit allocation. No fixed memory cap or changed live-resource retirement.
- Removes v4b's expensive smaps snapshots and extra report writer/reset traces. Sparse existing VMA/report diagnostics remain.
- Adds BCUS98111 `Ordered & Atomic` FIFO through the reversible title settings lease; explicit user overrides retain precedence.
- Fast LLVM ON, existing warm SPU/PPU caches and saved slot preserved.
- Qualcomm system Adreno 750 Vulkan 512.762.41; no Turnip change or Settings/UI interaction.

## Identity and verification

- OnePlus 13R `d30a1726`, standard release `2026.09.28-fastllvm-v5`; installed with `adb install -r`.
- APK SHA-256 `7744e48b033b1db413537ecea8bcba9a232ab8ddc02bbda2aa8f12dbc1abcc42`.
- Native SHA-256 `607a30fd7cd0cbe58a9054b7b930f1bea9203250771040d75e79030bd1991d6a`.
- Core source digest `2e8d2eb2383dceaed0cbe746cd23e36585ac6ad3812c6e4ffab012d0aca55c9a`; native bytes equal v4.
- Native/app builds, core/APK provenance, release signer, 16 KiB alignment passed. 22 title-lease unit tests passed.
- The APK was built from core base `39c89414bb3cd2f19f8d779437afe5f92517e968` plus the private tested source union. Later source commits preserve these bytes; this APK is not claimed to embed their new commit IDs.
- PID `32429`; session `1790573274960-b26270b2`; request `s3-1790573274758760275-486794-30605`.

## Procedure and harness correction

Canonical slot-0 launch followed by acknowledged Square/Cross/Circle scripts. Physical controller input was also observed; gameplay was not a controlled replay. Passive screenshots and sparse memory samples were captured, with full logs before stopping.

The first host helper expected unquoted FIFO text, while native YAML logged `"Ordered & Atomic"`. Its host process was stopped and a corrected helper resumed capture for the **same device PID/session without relaunching**. `harness-resume.json`, the original `launch-logcat.txt`, `launch-resume-logcat.txt`, and resume scripts preserve this correction. The original helper's interruption is not a game crash.

Collector exit 0, canonical stop exit 0. `CLEAN_STOP` at epoch 1790573521.670; settings restore `8/8`, `leaseCleared=true` at 1790573521.802. Before/after global FIFO is `Fast`; Fast LLVM remains true. App PID 32429 survived and returned to the launcher. No gameplay remains running.

## Measurements and limitations

System RAM used is `MemTotal - MemAvailable`, not app PSS. 21 sparse samples ranged from **9.754 to 11.540 GB**. Surface-present counter delta: **17.294 Hz**, diagnostic only; not verified useful gameplay FPS. Maximum sampled VMA blocks: 1,579,745,280 bytes; slack: 743,939,904 bytes. VMA usage is reserved-block fallback without the budget extension and excludes other driver allocations.

The exact-session Fast LLVM summary reports attempted/emitted/tracked baseline entries/promotions all **zero**. The warm cache used ordinary LLVM programs. ON configuration does not prove baseline code execution or a Fast LLVM speedup. Earlier cold fixtures exercised promotion, but the retained-selector self-modification timeout remains unresolved.

Device thermal state was severe; it was recorded and was not a stopping criterion. User-directed closure follows v5; no v6 is authorized by this campaign request.

## Evidence

`gameplay.png`, `terminal.png`, `counter-summary.json`, `capture-result.json`, build/native manifests, session identity, lease-test XML, config snapshots, launch/live/stop logs, `all-logs/` and `evidence-sha256.txt`. Rotated logs may contain older sessions: attribute by the PID/session above. Full raw evidence remains in local `artifacts/fastllvm-v5/`; selected evidence is copied into the tracked campaign docs.
