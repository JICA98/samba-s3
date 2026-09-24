plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-parcelize")
}

android {
    namespace = "com.zenithblue.sambas3"
    compileSdk = 37
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.zenithblue.sambas3"
        minSdk = 29
        targetSdk = 37
        versionCode = 20260917
        versionName = "${System.getenv("RX_VERSION") ?: "2026.09.17"}${if (System.getenv("RX_SHA") != null) "-" + System.getenv("RX_SHA") else ""}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "Version", "\"v${versionName}\"")
    }

    signingConfigs {
        val keystoreAlias = System.getenv("KEYSTORE_ALIAS")?.takeIf { it.isNotBlank() } ?: "samba-s3"
        val keystorePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "sambas3key2026"
        val keystorePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() } ?: "/home/abhaybyte/Downloads/samba-s3-release.jks"

        if (keystorePath.isNotEmpty()) {
            val keyFile = file(keystorePath)
            val resolvedFile = when {
                keyFile.exists() -> keyFile
                rootProject.file(keystorePath).exists() -> rootProject.file(keystorePath)
                else -> keyFile
            }
            if (resolvedFile.exists() && resolvedFile.length() > 0) {
                create("custom-key") {
                    keyAlias = keystoreAlias
                    keyPassword = keystorePassword
                    storeFile = resolvedFile
                    storePassword = keystorePassword
                }
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "IS_PLAYSTORE_BUILD", "false")
            buildConfigField("boolean", "ALLOW_EXTERNAL_GPU_DRIVERS", "true")
            buildConfigField("boolean", "INCLUDE_BUNDLED_TURNIP_DRIVERS", "true")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_PLAYSTORE_BUILD", "true")
            buildConfigField("boolean", "ALLOW_EXTERNAL_GPU_DRIVERS", "false")
            buildConfigField("boolean", "INCLUDE_BUNDLED_TURNIP_DRIVERS", "true")
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DIRECT_ISO_LOADING", "true")
            signingConfig = signingConfigs.findByName("custom-key")
        }
        release {
            // SAF-selected ISO files are a supported production feature; keep the
            // direct, no-copy path enabled in release builds as well as debug builds.
            buildConfigField("boolean", "DIRECT_ISO_LOADING", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Never publish a release artifact under the Android debug certificate.
            // Without production credentials the explicit release-task guard below fails the build.
            signingConfig = signingConfigs.findByName("custom-key")
        }
    }

    androidResources {
        // Keep driver ZIPs and catalog uncompressed for direct AssetManager reads / SHA-256.
        noCompress += listOf("zip", "json")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
        aidl = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        // This is necessary for libadrenotools custom driver loading
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/librpcsx-android.so"
    }

    testOptions {
        // android.util.Log etc. are stubbed (return defaults) in JVM unit tests
        // instead of throwing "not mocked" — needed by PadInputInjector failure-path tests.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // The Chinese catalog is intentionally partial and Android correctly falls back
        // to the complete default catalog. Compose 1.10's context-resource migration is
        // tracked separately; neither condition is a release correctness failure.
        warning += setOf("MissingTranslation", "LocalContextGetResourceValueCall", "RememberInComposition")
    }
}

base.archivesName = "samba-s3"

val customReleaseSigning = android.signingConfigs.findByName("custom-key")
gradle.taskGraph.whenReady {
    val createsReleaseArtifact = allTasks.any {
        (it.name.startsWith("bundle") || it.name.startsWith("assemble")) &&
            it.name.endsWith("Release")
    }
    if (createsReleaseArtifact && customReleaseSigning == null) {
        throw GradleException(
            "Production signing is required for release artifacts. Set KEYSTORE_PROPERTIES_PATH " +
                "or KEYSTORE_PATH, KEYSTORE_ALIAS, and KEYSTORE_PASSWORD."
        )
    }
}

// --- Samba S3 deterministic RPCSX core build ---
// jniLibs is .gitignored. Packaging requires both ABIs with provenance manifests.
// Direct buildRpcsxCore always runs the script; ninja skips unchanged compile.
// Unit-test-only tasks do not depend on these, so they do not require NDK.
val buildRpcsxCore = tasks.register<Exec>("buildRpcsxCore") {
    group = "samba"
    description = "Build pinned RPCSX core (build_rpcsx.sh release) into app/src/main/jniLibs."
    workingDir = rootDir
    inputs.files(
        rootProject.file("build_rpcsx.sh"),
        rootProject.file("patches/rpcsx-submodule-changes.patch"),
        rootProject.file("app/src/main/cpp/rpcsx/android/CMakeLists.txt"),
        rootProject.file("scripts/lib/core_provenance.py"),
        rootProject.file("scripts/verify-apk-core.sh"),
    )
    outputs.files(
        rootProject.file("app/src/main/jniLibs/arm64-v8a/librpcsx-android.so"),
        rootProject.file("app/src/main/jniLibs/arm64-v8a/librpcsx-android.manifest.json"),
    )
    outputs.upToDateWhen { false }
    commandLine("./build_rpcsx.sh", "release")
}

val verifyRpcsxCore = tasks.register<Exec>("verifyRpcsxCore") {
    group = "samba"
    description = "Fail if required ABI cores or provenance manifests are missing before packaging."
    workingDir = rootDir
    commandLine("python3", "scripts/lib/core_provenance.py", "verify-jnilibs", "--root", rootDir.absolutePath)
}

val verifyStandardReleaseApk = tasks.register<Exec>("verifyStandardReleaseApk") {
    group = "samba"
    description = "Verify standard release APK contains genuine RPCSX core matching provenance."
    workingDir = rootDir
    commandLine(
        "python3", "scripts/lib/core_provenance.py", "verify-package",
        "--root", rootDir.absolutePath,
        "--artifact", rootDir.resolve("app/build/outputs/apk/standard/release/samba-s3-standard-release.apk").absolutePath
    )
}

val verifyStandardReleaseBundle = tasks.register<Exec>("verifyStandardReleaseBundle") {
    group = "samba"
    description = "Verify standard release AAB contains genuine RPCSX core matching provenance."
    workingDir = rootDir
    commandLine(
        "python3", "scripts/lib/core_provenance.py", "verify-package",
        "--root", rootDir.absolutePath,
        "--artifact", rootDir.resolve("app/build/outputs/bundle/standardRelease/samba-s3-standard-release.aab").absolutePath
    )
}

val verifyPackagedArtifacts = tasks.register<Exec>("verifyPackagedArtifacts") {
    group = "samba"
    description = "Verify packaged core provenance in generated APKs and bundles."
    workingDir = rootDir
    commandLine("python3", "scripts/lib/core_provenance.py", "verify-package", "--root", rootDir.absolutePath)
}

fun isApkOrBundleTask(name: String): Boolean {
    if (name.contains("UnitTest") || name.contains("AndroidTest") ||
        name.contains("Resources") || name.contains("Classes")
    ) {
        return false
    }
    val flavorBuild = Regex("^(package|assemble|bundle)[A-Z].*(Debug|Release)$")
    return flavorBuild.matches(name)
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLib") }.configureEach {
    dependsOn(buildRpcsxCore)
}
tasks.matching { isApkOrBundleTask(it.name) }.configureEach {
    dependsOn(verifyRpcsxCore)
}
verifyRpcsxCore.configure { dependsOn(buildRpcsxCore) }

tasks.matching { it.name == "assembleStandardRelease" }.configureEach {
    finalizedBy(verifyStandardReleaseApk)
}
tasks.matching { it.name == "bundleStandardRelease" }.configureEach {
    finalizedBy(verifyStandardReleaseBundle)
}
tasks.matching {
    it.name.matches(Regex("^(assemble|bundle)[A-Z].*(Debug|Release)$")) &&
        it.name != "assembleStandardRelease" && it.name != "bundleStandardRelease"
}.configureEach {
    finalizedBy(verifyPackagedArtifacts)
}
tasks.matching {
    it.name.startsWith("install") && !it.name.contains("Test")
}.configureEach {
    if (name.contains("StandardRelease")) {
        dependsOn(verifyStandardReleaseApk)
    } else {
        dependsOn(verifyPackagedArtifacts)
    }
}

dependencies {
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.ui.tooling.preview.android)
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.squareup.okhttp3)
    implementation(libs.androidx.documentfile)
    implementation(libs.materialswitch)
    // Standard flavor only archive deps for .tzst support
    add("standardImplementation", libs.zstd.jni)
    add("standardImplementation", libs.commons.compress)
}
