# AGENTS.md — SambaS3 (RPCSX-UI-Android)

## Project Identity

| Field | Value |
|---|---|
| **What** | PS3 emulator UI/launcher for Android (RPCSX frontend) |
| **Package** | `com.zenithblue.sambas3` |
| **App name** | SambaS3 |
| **Build system** | Gradle Kotlin DSL + CMake for native code |
| **Language** | Kotlin (UI), C++ (native JNI bridge), C (emulator core loaded at runtime) |
| **UI framework** | Jetpack Compose (Material3) |
| **Min SDK** | 29 (Android 10) |
| **Target SDK** | 35 |
| **Compile SDK** | 36 |
| **NDK** | 30.0.14904198 |
| **ABIs** | `arm64-v8a`, `x86_64` |

## Architecture

```
Kotlin Compose UI  →  JNI bridge (native-lib.cpp → libsambas3-android.so)  →  Runtime-loaded RPCSX emulator .so
```

The emulator core is **not compiled into the APK**. It's downloaded from GitHub releases as a separate `.so` and loaded at runtime via `dlopen()`. The JNI bridge resolves ~25 function pointers (boot, kill, resume, surface, USB, settings, etc.).

## Key Source Directories

| Path | What |
|---|---|
| `app/src/main/java/com/zenithblue/sambas3/` | Kotlin app: activities, UI screens, repos, utilities |
| `app/src/main/cpp/` | Native JNI bridge (`native-lib.cpp`), CMake |
| `app/src/main/res/` | Resources, layouts, themes |
| `app/src/main/AndroidManifest.xml` | Manifest |
| `design/design.md` | Full design spec (CRT retro aesthetic) |
| `gradle/libs.versions.toml` | Version catalog |

## Key Activities

- **MainActivity** — Launcher: game library, settings, firmware management
- **RPCSXActivity** — Fullscreen emulator rendering surface (landscape, singleTask)
- **OverlayEditActivity** — Controller overlay editor

## Product flavors (`distribution`)

| Flavor | Default | Bundled Turnip | External GPU import/download |
|--------|---------|----------------|------------------------------|
| `standard` | yes | no | yes |
| `playstore` | no | yes (from assets) | no |

BuildConfig flags: `IS_PLAYSTORE_BUILD`, `ALLOW_EXTERNAL_GPU_DRIVERS`, `INCLUDE_BUNDLED_TURNIP_DRIVERS`.

Package approved drivers with `./scripts/package-bundled-turnip-drivers.sh` (inputs under `drivers/input/`). See `docs/BUNDLED_TURNIP_DRIVERS.md`.

## Key Build/Run Commands

```bash
# Debug APKs (standard is default flavor)
./gradlew assembleStandardDebug
./gradlew assemblePlaystoreDebug

# Play Store release APK / AAB
./gradlew assemblePlaystoreRelease
./gradlew bundlePlaystoreRelease

# Unit tests
./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest

# Build + install + launch (default FLAVOR=standard; override FLAVOR=playstore)
./build_and_install.sh debug

# Output APKs
# app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk
# app/build/outputs/apk/playstore/debug/samba-s3-playstore-debug.apk
# app/build/outputs/bundle/playstoreRelease/samba-s3-playstore-release.aab
```

## Logging

- Kotlin: `Log.e("Main", ...)`, `Log.w("RPCSX State", ...)`, `Log.i("USB", ...)`
- Native: `__android_log_print(ANDROID_LOG_ERROR/INFO, "RPCSX-UI", ...)`

## Log Monitoring

```bash
# Full logcat, filtered by package
adb logcat | grep -E "com\.zenithblue\.sambas3|RPCSX|Main"

# Or clear first then watch
adb logcat -c && adb logcat | grep -E "sambas3|RPCSX|Main"
```

## AGP / Toolchain Versions

- AGP: 8.13.2
- Kotlin: 2.3.21
- Compose Compiler Extension: 1.5.15
