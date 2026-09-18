# Google Play release compliance

Last audited: September 15, 2026

This is the repository-side release checklist for SambaS3. It records what the artifact proves and which declarations have to be completed in Play Console. It does not replace legal advice or Play's review.

## Verified in the release artifact

- **AAB delivery:** Google Play mandates the Android App Bundle (`.aab`) format for store publishing. Both `bundlePlaystoreRelease` and `bundleStandardRelease` pass `bundletool validate` without errors.
- **16 KiB ELF alignment:** All packaged native libraries in `arm64-v8a` and `x86_64` use 16 KiB (`0x4000`) ELF load segment alignment, complying with Android 15+ / API 35+ requirements.
- **Native library extraction:** `android:extractNativeLibs="true"` / `jniLibs.useLegacyPackaging = true` is fully supported in App Bundles.
- **Dynamic code policy:** Network driver downloads have been de-networked (`docs/plans/denetwork-drivers-and-inapp-patch-import-plan.md`). `ALLOW_EXTERNAL_GPU_DRIVERS` enables user-selected local ZIP import via SAF without downloading executable code over network, matching standard emulator implementations on Google Play.
- **Foreground services:** Manifest defines `FOREGROUND_SERVICE_SPECIAL_USE` along with the required platform metadata `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" ... />` on `PpuBatchWorkerService`, `CompilationMonitorService`, and `GameSessionService`.
- **Optimization & size:** R8 full-mode optimization, shrinking, obfuscation, optimized resource shrinking, and baseline profiles are enabled. The release DEX payload is well below Play limits.
- **Signing:** Release builds require production signing (`custom-key`) and fail the build if credentials are missing.
- **Privacy & telemetry:** Zero advertising, analytics, online account, or billing SDKs. Logs and captures stay local unless explicitly shared by the user. Privacy policy is committed at `PRIVACY_POLICY.md` and linked in Settings.

## Play Console declarations required before rollout

- Add public privacy-policy URL: `https://github.com/jica98/samba-s3/blob/master/PRIVACY_POLICY.md`.
- Complete Data Safety form consistently with the app and privacy policy (no automated collection).
- Complete content-rating questionnaire for an emulator/launcher with user-supplied content.
- Complete foreground service questionnaire in Play Console for `specialUse` (compilation and background emulation).
- Use Play App Signing and verify upload key matches the release keystore.
- Upload to internal test track first and verify pre-launch reports before production rollout.

## Reproducible release checks

```bash
chmod 600 /path/to/signing.properties /path/to/release.jks

# Build release bundle
KEYSTORE_PROPERTIES_PATH=/path/to/signing.properties \
  ./gradlew :app:bundleStandardRelease :app:bundlePlaystoreRelease

# Validate bundle
java -jar bundletool-all.jar validate \
  --bundle app/build/outputs/bundle/standardRelease/samba-s3-standard-release.aab

# Verify signing
jarsigner -verify -verbose -certs \
  app/build/outputs/bundle/standardRelease/samba-s3-standard-release.aab
```

References:

- [Play technical quality requirements](https://support.google.com/googleplay/android-developer/answer/17492799)
- [Device and network abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646)
- [User data and privacy policy requirements](https://support.google.com/googleplay/android-developer/answer/10144311)
- [App size limits](https://support.google.com/googleplay/android-developer/answer/9859372)
- [Enable app optimization with R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
