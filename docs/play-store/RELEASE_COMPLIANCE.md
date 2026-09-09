# Google Play release compliance

Last audited: September 9, 2026

This is the repository-side release checklist for SambaS3. It records what the artifact proves and which declarations still have to be completed in Play Console. It does not replace legal advice or Play's review.

## Verified in the release artifact

- The Play build targets Android API 37 and supports 64-bit `arm64-v8a` and `x86_64`.
- All packaged native libraries use 16 KiB ELF load alignment.
- R8 full-mode optimization, shrinking, obfuscation, optimized resource shrinking, and the baseline profile are enabled for release builds.
- The release DEX payload is well below the current 50 MB threshold at which Play's game DEX-optimization metric applies.
- The Play flavor bundles the RPCSX runtime and approved Turnip packages. It does not download DEX, JAR, or native executable code.
- Release builds require production signing and cannot silently fall back to the Android debug certificate.
- Release lint is enforced. Partial Chinese translations use Android's complete default-language fallback and are reported as warnings.
- The app has no advertising, analytics, online account, or billing SDK. Logs and performance captures stay local unless the user explicitly invokes Android's share interface.
- The privacy policy is committed at `PRIVACY_POLICY.md` and is linked from Settings.

## Play Console declarations required before rollout

- Add the public privacy-policy URL: `https://github.com/jica98/samba-s3/blob/master/PRIVACY_POLICY.md`.
- Complete Data safety consistently with the app and privacy policy. Do not declare user-triggered log sharing as automatic developer collection.
- Complete the content-rating questionnaire for an emulator/launcher whose game content is user supplied.
- Declare every foreground-service type shown in the merged manifest. Explain that game emulation and isolated PPU/graphics compilation are user-started, perceptible, interruptible, and exposed through ongoing controls. Supply the required demonstration video.
- Use Play App Signing and verify the upload certificate matches the registered upload key.
- Confirm the selected countries, store listing, support contact, app access, ads declaration, target audience, and news-app status.
- Upload to an internal test track first and check the pre-launch report, Android vitals, bundle warnings, device catalog, and policy status before production rollout.

## Memory and quality follow-up

Google Play's February 2027 game memory thresholds are measured at the 90th percentile by device RAM tier. Emulator memory use is workload-dependent, so release decisions must use Play vitals plus long-running tests on representative 4, 6, 8, and 12 GB devices. The isolated PPU worker already exits after each batch to release compiler memory; no functionality is removed to meet the metric.

## Reproducible release checks

```bash
chmod 600 /path/to/signing.properties /path/to/release.jks
KEYSTORE_PROPERTIES_PATH=/path/to/signing.properties \
  ./gradlew :app:testStandardDebugUnitTest \
    :app:testPlaystoreDebugUnitTest \
    :app:lintPlaystoreRelease \
    :app:bundlePlaystoreRelease

java -jar bundletool-all.jar validate \
  --bundle app/build/outputs/bundle/playstoreRelease/samba-s3-playstore-release.aab
jarsigner -verify -verbose -certs \
  app/build/outputs/bundle/playstoreRelease/samba-s3-playstore-release.aab
```

References:

- [Play technical quality requirements](https://support.google.com/googleplay/android-developer/answer/17492799)
- [Device and network abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646)
- [User data and privacy policy requirements](https://support.google.com/googleplay/android-developer/answer/10144311)
- [App size limits](https://support.google.com/googleplay/android-developer/answer/9859372)
- [Enable app optimization with R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
