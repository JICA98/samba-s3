# Savestate Load Recovery Worker

Baseline reviewed: latest `rdr-adreno750-fastpath-validation` branch state on 2026-09-07. The existing `docs/SAVESTATE_PLAN.md` describes the earlier savestate implementation and remains useful as historical/reference material; this worker is focused on the current restore regression.

## Scope

Target branch: `rdr-adreno750-fastpath-validation`

Current regression:
- Saving creates a savestate file.
- Restoring from the home/game launcher card can crash immediately.
- Loading from the in-game Load menu can remain stuck in the loading phase.

This worker is restore-first. Do not redesign the savestate format until the two restore entry paths have deterministic terminal success/failure behavior.

## Review findings

1. Cold launcher restore and in-game restore use different orchestration paths.
   - Cold launcher restore goes through `RPCSXActivity` and `bootSavestateSerialized(...)`.
   - In-game restore goes through `RpcsxInGameMenuCoreGateway` plus the activity manual-load transition watcher.
2. Direct-ISO cold restore passes a `/proc/self/fd/<n>` original-game path into native restore. That path is only valid while the backing `DirectIsoSession` FD remains alive.
3. Commit `846e9f9c30abb4066ee9037bce52b3155d2caac4` changed both direct savestate boot routing and direct-ISO FD/session lifetime. Use it as the first split-bisect boundary; it is not yet a proven root cause.
4. `watchManualLoad()` has a concrete terminal-state bug: after the load timeout it logs and exits without failing/unlocking the active transition. A failed native load can therefore leave the in-game UI permanently in “loading”.
5. The Adreno 750 fastpath increases the risk of restoring stale host-only GPU state. Savestates may restore emulated/guest state, but transient host Vulkan/driver/fastpath state must be recreated or revalidated.

## P0 — Make restore observable and terminal

- Add a restore request ID propagated through Kotlin, JNI, and native logs.
- Log:
  - source: `launcher` or `in_game`
  - savestate path, readable size, header/version/game ID
  - original-game path
  - direct-ISO raw FD / proc-FD path and FD validity
  - `DirectIsoSession` acquire/retain/release/close
  - core/surface/renderer readiness
  - native deserialize start, completion, and error
- Native restore must return exactly one terminal result: success or structured failure.
- Fix `watchManualLoad()` immediately so timeout/error calls the transition failure path and clears the loading overlay.
- Stop treating a filesystem watcher or timeout as the authoritative native completion signal.

## P1 — Prove/fix Direct ISO FD ownership

- Split-bisect commit `846e9f9`:
  1. old vs new savestate/direct boot routing
  2. old vs new `DirectIsoSession` / FD lifetime behavior
- Preferred ownership model:
  - duplicate (`dup`) the ISO FD for the native restore/boot transaction
  - native owns that duplicate until restore reaches terminal success/failure
  - close exactly once on terminal completion
- Acceptable alternative:
  - retain `DirectIsoSession` until an explicit native terminal callback
- Never retain only `/proc/self/fd/N` after releasing the object that owns `N`.
- Validate FD liveness immediately before every native open/reopen.
- Verify no descriptor leaks across repeated failed and successful restores.

## P2 — Unify launcher and in-game restore

Introduce one restore coordinator/request carrying:
- request ID
- savestate path
- game identity/original game source
- restore source (`launcher` / `in_game`)
- direct ISO session/owned FD when required

Launcher flow:
1. acquire game/direct-ISO source
2. initialize core/surface/renderer
3. wait for an explicit restore-ready barrier
4. submit the restore
5. finish from the native terminal result

In-game flow:
1. pause/quiesce guest execution
2. submit the same restore transaction
3. await the same native terminal result
4. resume only after a successful restore
5. fail and release the UI transition on any error/timeout

Reject overlapping requests and stale callbacks.

## P3 — Rebuild Adreno 750 fastpath host state after deserialize

- Audit savestate members for raw host pointers/handles and transient GPU state.
- Do not restore raw Vulkan object handles, mapped host pointers, descriptor allocations, transient command state, or JIT-derived host pointers as if they remain valid.
- Invalidate/recreate host-only GPU resources after guest state deserialize.
- Rebind/revalidate the Adreno 750 fastpath before the first resumed frame.
- If fastpath validation fails:
  - use the safe path when supported, or
  - return a recoverable restore error
- Never continue rendering with stale host handles.

## P4 — Harden failure behavior

The following must each produce one terminal failure, not a crash or infinite spinner:
- missing savestate
- truncated/corrupt savestate
- incompatible state version
- wrong game/title
- direct ISO open/reopen failure
- invalid/stale FD
- deserialize failure
- renderer/fastpath rebind failure

Do not silently start a fresh game after restore failure.

## P5 — Verify save durability after restore is stable

Once restore is deterministic:
- write a temporary state file
- flush/fsync it
- validate required metadata/checksum
- atomically rename to the final slot
- update launcher/home save metadata only after commit succeeds

Keep state format/build/game compatibility explicit.

## Regression matrix

Run at minimum:
- save -> in-game load, same slot, 20 iterations
- save -> exit activity -> launcher-card restore
- save -> kill process -> launcher-card restore
- launcher restore with Adreno 750 fastpath ON/OFF
- in-game restore with fastpath ON/OFF
- background/resume during restore
- corrupt/truncated/missing/wrong-game/wrong-version state
- repeated failed loads: no FD/thread/session leaks
- first resumed frame renders correctly
- input and audio resume
- in-game loading overlay always terminates
- launcher restore never process-crashes on a bad state

## Definition of done

- Launcher-card restore returns to the saved state after activity/process recreation without a crash.
- In-game Load reaches the saved state without indefinite loading.
- Every restore request produces exactly one terminal success/failure signal.
- No use-after-close of direct-ISO FDs and no FD leak.
- Adreno 750 fastpath is rebuilt/revalidated after restore or safely falls back/fails.
- Bad states produce actionable logs and a recoverable UI error.
- Automated or scripted regression coverage exercises both restore entry paths.

## Implementation order

1. Add request-scoped logging and fix manual-load timeout terminalization.
2. Split-bisect `846e9f9`; fix direct-ISO FD ownership/cold launcher restore.
3. Replace watcher-based completion with a shared explicit restore result.
4. Rebuild/revalidate host GPU + Adreno 750 fastpath state.
5. Harden save commit/durability.
6. Add regression tests for both entry paths.

## Non-solutions

- Do not add arbitrary sleeps to “wait for restore”.
- Do not leave completion dependent on polling/filesystem watchers.
- Do not serialize/restore raw host GPU/Vulkan handles or host pointers.
- Do not hide restore failures by automatically starting a fresh boot.
