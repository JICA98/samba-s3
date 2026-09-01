# GTA San Andreas FPS/power regression audit

Date: 2026-09-01/02<br>
Device: OnePlus Pad 2 (`OPD2403`, Snapdragon 8 Gen 3) only<br>
ADB endpoint: `adb-7d6afed8-mU47CV (2)._adb-tls-connect._tcp`<br>
Game: GTA San Andreas, `BLUS31584`<br>
Performance APK: `standardDebug`, SHA-256 `225150ae67f6a3c5ae663446bc974b9daaca0e20d98c488c50742665a0ec132a`<br>
Final validation APK: SHA-256 `3b86097c61f52d1e5a7f87c1a1e63d1eb7a7bc0f709ca5fa8a3a0e4f489e9415`

## Result

The reported ~20 FPS / ~8 W regression was not reproduced on the Pad 2. All
valid in-game samples stayed at the title's approximately 30 FPS cadence, not
20 FPS. The long unchanged-build run completed successfully with 1,191
one-second samples: mean 29.842 FPS, minimum 28.815, maximum 30.885, and mean
frame time 33.510 ms.

This is a gameplay result, not a menu result. The native sampler recorded about
30 presented frames per approximately 60 display-vblank intervals, which is
consistent with a 30 FPS game pacing path. It would be incorrect to claim that
this audit restored gameplay to 60 FPS.

The final rebuilt APK was also launched into gameplay and sampled for 19 valid
seconds: mean 29.833 FPS, minimum 29.399, maximum 30.427, with no crash.

## Launch/recovery matrix

The same APK, game data, and configuration were used for each path. No
resolution reduction, Turbo mode, artificial cap, or other tuning was applied.

| Path | Samples | Mean FPS | Mean frame time | Result |
|---|---:|---:|---:|---|
| Fresh gameplay | 29 | 29.815 | 33.519 ms | PASS; stable gameplay |
| Restart Game | 29 | 29.828 | 33.527 ms | PASS; stable gameplay |
| Manual Load, slot 0 | 30 | 29.867 | 33.488 ms | PASS; stable gameplay |
| Cold Continue Save, slot 0 | 29 | 29.851 | 33.507 ms | PASS; stable gameplay |
| Save then auto-recovery, slot 4 | 29 | 29.837 | 33.515 ms | PASS; resumed gameplay |
| 20-minute unchanged build | 1,191 | 29.842 | 33.510 ms | PASS; no drift/crash |

The raw logs and the aggregate are in
[`artifacts/2026-09-01-gta-fps-power-regression`](artifacts/2026-09-01-gta-fps-power-regression/):
`summary.csv`, `fps-summary.svg`, and the `gameplay-*.log` files.

## Controlled A/B checks

| Variant | Mean FPS | Mean frame time | Interpretation |
|---|---:|---:|---|
| LogMonitor ON | 29.805 | 33.508 ms | no material change |
| LogMonitor OFF | 29.838 | 33.513 ms | no material change |
| VSync ON | 29.825 | 33.516 ms | no material change |
| Frame limit OFF | 29.833 | 33.523 ms | no material change |

The LogMonitor ON/OFF captures are `gameplay-logmonitor-on-final.log` and
`gameplay-logmonitor-off-final.log`. The low-overhead benchmark uses the native
`emu_flip`/presented-frame counter and samples once per second; it does not
render the monitoring overlay or run a Compose graph.

## Changes made

- Removed automatic LogMonitor startup from `MainActivity` and
  `RPCSXActivity`. Log capture now starts only while the Log Monitor screen is
  open, or when explicitly requested by a debug A/B action. This eliminates
  persistent logcat parsing and disk writers during every game session.
- Added `tools/benchmark-gta.sh` for repeatable native FPS/frame-time/CPU
  sampling and `tools/summarize-gta-bench.py` for the CSV/SVG output.
- Lifted wide Settings pane selection state into `AppNavHost`. The Controls
  pane now survives the controller-test route and recomposition/hot-plug
  lifecycle instead of falling back to Storage Directory.

## Settings navigation validation

On the final validation APK, Controls was opened, the Settings route was left,
and Settings was reopened. Both the initial and reopened UI XML captures
contain `Controls` and `No gamepad or keyboard connected`; neither contains a
right-pane `Storage Directory` selection. Screenshots are
`settings-controls-final-build.png` and `settings-controls-final-reopen.png`.

No physical gamepad or keyboard was enumerated on the Pad 2 during this pass,
so the hot-plug path was verified by the lifted lifecycle state and the
no-device Controls surface; it was not claimed as a physical attach test.

## Power/thermal limitation

The tablet remained physically connected to power throughout testing, so
device/wall wattage was not measurable. The audit therefore makes no claim
about the historical ~2 W versus ~8 W report. Android reported `Thermal
Status: 0`; raw battery, thermal, display, and thread snapshots are included
in the artifact directory.

## Decision

The current evidence does not identify a reproducible 20 FPS or power
regression, and the A/B checks do not justify a native performance tuning
patch. The 30 FPS gameplay cadence should be treated as the current baseline
for this title/configuration until a physically unplugged power capture or a
different reproducible scene demonstrates otherwise.
