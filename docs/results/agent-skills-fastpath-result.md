# Result: agent-skills-fastpath

## Task
Rework the SambaS3 on-device agent skills/scripts around one deterministic
device-test path (fix the 200-call loop causes: non-exported activity launch,
coordinate-based input, `debug-pad.sh` parse bug, incomplete log collection),
add a `sambas3-device-test` orchestrator skill, then verify live on the
OnePlus Pad 3 tablet.

## Final Status
DONE — with justification below. All acceptance criteria verified by script
checks + live device runs; one documented correction (no `DEBUG_BOOT_GAME`
handler exists in product code, so the launcher uses the equivalent warm
start; no product code was modified).

## Workflow Summary
| Stage | Agent | Pass | Iteration | Verdict |
|-------|-------|------|-----------|---------|
| Investigation | opencode | 1 | 1 | READY |
| Implementation | opencode | 1 | 1 | DONE |
| Device verification | opencode | 1 | 1 | PASS |

## Implementation
- `scripts/debug-pad.sh` — fixed shorthand parser (was `CROSS/UP/CIRCLE`
  only, now all 17 buttons), refuses ambiguous multi-device selection,
  verifies the `DebugPad` log instead of trusting `Broadcast completed`.
- `scripts/debug-launch-game.sh` (new) — starts exported `MainActivity`
  (RPCSX init + receiver), probes `DEBUG_BOOT_GAME`, warm-starts
  `RPCSXActivity` with exact path extras; 20s focus cap, max 2 attempts.
- `scripts/get-samba-logs.sh` — one-shot collector: rotated
  backend/vulkan/app logs, cache `TTY/RPCSX*.log(.gz)`, logcat
  crash/main+system/events/kernel, `logcat-sambas3.txt` slice, dropbox,
  tombstone/ANR listing, exit-info, activity/window/SurfaceFlinger/input,
  thermal, meminfo, pstore + manifest; prints triage summaries.
- `scripts/gamepad.sh` (new) — gameplay wrapper: `list/press/hold/seq/stick/eula`.
- `.agents/skills/sambas3-device-test/SKILL.md` (new orchestrator) with the
  canonical 4-command run and hard rules (~10-command budget, 2-attempt cap,
  agent-device = milestone screenshots only).
- Rewrote `sambas3-game-launch`, `sambas3-controller`, `sambas3-logs` skills:
  no direct `RPCSXActivity` shell-start, no coordinate math, no manual
  multi-pull logging.
- Key correction: reviewer claimed a `DEBUG_BOOT_GAME` handler already
  exists — verified absent in `debug/DebugPadReceiver.kt`, so the launcher
  uses the equivalent `MainActivity` → warm `RPCSXActivity` ordering and only
  probes for the broadcast (future-proof). Product code untouched.

## Files Changed
- `.agents/skills/sambas3-device-test/SKILL.md` — new orchestrator skill
- `.agents/skills/sambas3-game-launch/SKILL.md` — deterministic warm launch
- `.agents/skills/sambas3-controller/SKILL.md` — broadcast bridge + gamepad.sh
- `.agents/skills/sambas3-logs/SKILL.md` — one-shot evidence doctrine
- `scripts/debug-launch-game.sh` — new deterministic launcher
- `scripts/debug-pad.sh` — parse fix + log verification
- `scripts/get-samba-logs.sh` — all-types collector
- `scripts/gamepad.sh` — new gameplay input helper

## Tests
| Suite | Result | Evidence |
|-------|--------|----------|
| `bash -n` ×4 + `git diff --check` | PASS | terminal output `ALL_OK` |
| Button-parse matrix 17/17 | PASS | shorthand resolves `Ambiguous` (button recognized) for every button; old code handled 3/17 |
| Live `debug-launch-game.sh` on 7d6afed8 | PASS | `RPCSXActivity` focused, GTA SA EULA rendering (screenshot) |
| Live `gamepad.sh press CROSS` on 7d6afed8 | PASS | `sent CROSS (verified: DebugPad log shows CROSS)` |
| Live `get-samba-logs.sh` on 7d6afed8 | PASS | 30 files in `/tmp/tablet-logs-test`, triage printed |

## Runtime / Manual Verification
- Device: OnePlus Pad 3 `7d6afed8` (OPD2403, SM8650, Android 16), app
  `2026.07.22`, Renderer Vulkan, game `BLUS31584` (GTA SA).
- Launch: `RPCSXActivity` focused post-launch; snapshot showed EULA screen
  with PadOverlay + perf overlay (APP CPU 247%, 34.0°C).
- Pad: CROSS delivered and log-verified.
- Logs: collector captured 3× `APP CRASH(EXCEPTION)` from earlier that day
  (15:27/16:58/17:16, `FATAL EXCEPTION: main` in crash buffer) — flagged for
  follow-up, unrelated to this change.

## Review Findings
- Self-reviewed diff before commit; no external plan/impl reviewers in this
  flow. Main finding incorporated: `DEBUG_BOOT_GAME` does not exist, handled
  via probe + warm start instead of new product code.

## Evidence
- Git diff: `git diff HEAD --stat` (5 modified + 3 new files below)
- Device evidence dir: `/tmp/tablet-logs-test` (30 files)
- Live outputs quoted in Tests table above

## Remaining Limitations
- `gamepad.sh list` bridge check is heuristic (recent-logcat grep); a quiet
  buffer can warn even when the bridge works (observed once, widened to 1000
  lines case-insensitive).
- `dumpsys` pulls (exit-info/SurfaceFlinger/pstore) are best-effort; restricted
  builds may return partial data without failing the run.
- No formal `manual-tester` PASS report; verification is script-level + live
  device runs documented above.

## Final Acceptance Criteria
| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | No direct `RPCSXActivity` shell-start in skills | VERIFIED | skill texts + launcher script |
| 2 | All-button `debug-pad.sh` shorthand | VERIFIED | 17/17 parse matrix |
| 3 | Pad delivery log-verified | VERIFIED | live CROSS run on tablet |
| 4 | One-call full log capture | VERIFIED | 30-file `/tmp/tablet-logs-test` |
| 5 | Orchestrator skill + budgets | VERIFIED | `sambas3-device-test/SKILL.md` |
| 6 | No product-code changes | VERIFIED | `git status` shows skills+scripts only |

## Final Verdict
DONE — deterministic device-test path implemented, verified live on OnePlus
Pad 3, report written, pushed.

## Verification Statement
"I verify that criteria 1–6 are VERIFIED by the `bash -n`/`git diff --check`
output, the 17/17 button-parse matrix, and the live OnePlus Pad 3 runs
(launch focus + EULA screenshot, log-verified CROSS press, 30-file log
capture); the `DEBUG_BOOT_GAME` absence is VERIFIED by reading
`debug/DebugPadReceiver.kt`; remaining limitations above are NOT_VERIFIED
items documented honestly."
