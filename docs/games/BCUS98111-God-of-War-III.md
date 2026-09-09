# God of War III (BCUS98111) — OnePlus 13R

| Field | Value |
|---|---|
| Title ID | `BCUS98111` |
| Device | OnePlus 13R (`CPH2691`), Snapdragon 8 Gen 3 / Adreno 750 |
| Test date | 2026-09-09 |
| Result | In-game, approximately 15 FPS (user-verified) |

God of War III reaches gameplay on the OnePlus 13R. The reported 15 FPS is an observed result, not a stable-scene benchmark, so it should be re-measured in a later title-specific performance pass.

The game also reproduced the shared frontend savestate issue: opening SambaS3's menu paused the emulator before RPCSX tried to acquire its savestate-safe SPU boundary. The Android save path now resumes behind the frozen transition frame immediately before RPCSX performs its own SPU pause, and failed preparation always emits a terminal event instead of leaving “Saving” stuck. The shared multi-slot and teardown fix was fully validated with inFamous 2 on the same OnePlus; a God of War III-specific save/load cycle remains to be checked later.

Status: **in-game; performance and final slot-save validation pending**.
