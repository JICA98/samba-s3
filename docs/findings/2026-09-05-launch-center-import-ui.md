# Launch Center import and layout fix (2026-09-05)

## Symptom

The Launch Center rendered the persisted game name
`PRIMARY%3ADOWNLOAD%2FSAMBAS3TEST%2FGTA-SA` verbatim. The encoded document-provider
prefix wrapped the header, made the sheet too tall, and obscured the PPU action
row. The same entry showed `RE-IMPORT REQUIRED` with a disabled START button,
but the UI did not make it clear that selecting the game again is the supported
install-origin PPU compilation path.

## Fix

- Added a defensive display-name resolver that URL-decodes provider metadata and
  renders the final component (`GTA-SA`) without changing the stored path or
  identity key.
- Applied the resolver to the Launch Center and active-game header.
- Capped the Launch Center at 90% of the viewport / 720dp, reduced its width and
  padding, ellipsized long metadata, and made the footer horizontally scrollable
  so START and preparation controls remain reachable on compact screens.
- Renamed the action to `RE-IMPORT GAME`; it remains wired to the existing import
  flow, which starts install-origin PPU compilation after the selected game is
  imported. No headless PPU success is fabricated.
- Title-ID matching now decodes encoded names before applying the PS3 title-ID
  pattern.

## Verification

- `GameIdentityTest` and `LaunchPpuPresentationTest`: passed.
- `./gradlew assembleStandardDebug`: passed.
- APK: `app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk`.
- The requested Pad 2 serial was offline during installation (`adb devices`
  listed no `adb-7d6afed8-mU47CV._adb-tls-connect._tcp` and mDNS did not expose
  it), so the APK was not pushed to an unrelated connected handset.

## Related emulator status

The existing RDR Adreno/Turnip investigation remains open: the post-intro black
output is not claimed fixed. The current native workaround is narrowed to
Turnip-labelled drivers after the broad Android fallback was not safe on the
Mali control device. See `2026-09-04-rdr-adreno750-gpulabel-validation.md` for
the device evidence.
