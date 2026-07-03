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

## Key Build/Run Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build + install + launch
./build_and_install.sh debug

# Output APK
# app/build/outputs/apk/debug/samba-s3-debug.apk
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
