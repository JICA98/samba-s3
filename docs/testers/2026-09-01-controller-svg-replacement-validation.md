# Controller SVG replacement validation — 2026-09-01

## Build under test

- Branch: `recovery/ingame-menu-fix`
- Implementation commit before validation docs: `3b93c37e271dc65e6cf8890447bdd38137b4456a`
- Variant: Standard debug
- APK: `app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk`
- APK SHA-256: `39455a63f224b99c96168c2a387c6384f5bb26a36c45dd2d4d8617e3a0ff3f45`
- Source hashes and runtime hashes: [controller SVG sanitization](../../controller-svg-sanitization.md)

## Two-pass result

### Pass 1 — visual replacement

- [x] Source keyboard reference rendered at 1510×450.
- [x] Source DS3 reference rendered at 1000×560.
- [x] Runtime keyboard rendered at 1510×450 with no status/script content.
- [x] Runtime DS3 rendered at 1000×500 with clean SELECT/START captions and no status overlap.
- [x] OnePlus Pad 2 (`OPD2403`) Controls page shows the supplied DS3 artwork, with no `XBOX`, asset filename, or debug status labels.
- [x] Keyboard test screen shows the supplied full ANSI artwork and corrected arrow glyphs.

### Pass 2 — interaction and regression

- [x] D-pad source region tap selected the expected logical D-pad control.
- [x] The selected D-pad region received a source-aligned lightweight highlight.
- [x] Keyboard map contains all 104 source `data-code` groups exactly once.
- [x] DS3 map contains all 17 source button/stick regions.
- [x] Logical-to-region round trips pass for all controls and gamepad families.
- [x] Physical keyboard registry covers the full ANSI key set used by Android key events.
- [x] No `S3SVG` decode errors appeared in the Pad 2 logcat sample.
- [x] Existing input mapping/lifecycle code remains unchanged outside visual/region integration.

## Automated checks

```text
./gradlew :app:testStandardDebugUnitTest --tests '*ControllerLayoutResolverTest' \
  --tests '*ControllerAssetsPresenceTest' \
  --tests '*ControllerHotspotLayoutTest'
BUILD SUCCESSFUL

./gradlew :app:compileStandardDebugKotlin
BUILD SUCCESSFUL

./gradlew assembleStandardDebug
BUILD SUCCESSFUL

./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest
BUILD SUCCESSFUL
```

The environment did not have `agent-device` installed, so the equivalent device pass used the project APK plus targeted ADB commands. The physical keyboard was not connected during the final Pad 2 run; the keyboard artwork and map were validated on the test screen, while physical key routing remains covered by the existing mapper tests.

## Evidence

- `00-keyboard-source-reference.png`
- `01-controller-source-reference.png`
- `02-keyboard-runtime-reference.png`
- `03-controller-runtime-reference.png`
- `04-pad2-controls-idle.png`
- `05-pad2-controller-highlight.png`
- `06-pad2-keyboard-test.png`
