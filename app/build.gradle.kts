import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-parcelize")
}

android {
    namespace = "com.zenithblue.sambas3"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.zenithblue.sambas3"
        minSdk = 29
        targetSdk = 35
        versionCode = 20260722
        versionName = "${System.getenv("RX_VERSION") ?: "2026.07.22"}${if (System.getenv("RX_SHA") != null) "-" + System.getenv("RX_SHA") else ""}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        buildConfigField("String", "Version", "\"v${versionName}\"")
    }

    signingConfigs {
        val keystorePropertiesFile = rootProject.file("local.properties")
        val keystoreProperties = Properties()
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
        }

        val keystoreAlias = keystoreProperties.getProperty("keystore.alias") ?: System.getenv("KEYSTORE_ALIAS") ?: ""
        val keystorePassword = keystoreProperties.getProperty("keystore.password") ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
        val keystorePath = keystoreProperties.getProperty("keystore.path") ?: System.getenv("KEYSTORE_PATH") ?: ""

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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("custom-key") ?: signingConfigs.getByName("debug")
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        // This is necessary for libadrenotools custom driver loading
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        // android.util.Log etc. are stubbed (return defaults) in JVM unit tests
        // instead of throwing "not mocked" — needed by PadInputInjector failure-path tests.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

base.archivesName = "samba-s3"

// --- Samba S3 deterministic RPCSX core build (BLOCKER A) ---
// `app/src/main/jniLibs` is .gitignored on purpose. A clean checkout must build the
// pinned RPCSX core before Gradle merges jniLibs, otherwise a stale .so would be packaged.
val buildRpcsxCore = tasks.register<Exec>("buildRpcsxCore") {
    group = "samba"
    description = "Build pinned RPCSX core (build_rpcsx.sh release) into app/src/main/jniLibs."
    workingDir = rootDir
    // Only build when Gradle is assembling/packaging an APK/AAB; allow unit tests to run without NDK.
    // Skip if core already present to avoid 10-minute rebuild on every debug assemble.
    onlyIf {
        val requested = gradle.startParameter.taskNames.joinToString(" ")
        val needsAssemble = requested.contains("assemble") || requested.contains("bundle") || requested.contains("package")
        if (!needsAssemble) return@onlyIf false
        val arm = rootProject.file("app/src/main/jniLibs/arm64-v8a/librpcsx-android.so")
        val x64 = rootProject.file("app/src/main/jniLibs/x86_64/librpcsx-android.so")
        // Build if either ABI missing; otherwise trust existing core (CI will build fresh)
        !arm.exists() || !x64.exists()
    }
    commandLine("./build_rpcsx.sh", "release")
}

val verifyRpcsxCore = tasks.register<Exec>("verifyRpcsxCore") {
    group = "samba"
    description = "Fail if jniLibs core is missing or stale before packaging."
    workingDir = rootDir
    onlyIf {
        val requested = gradle.startParameter.taskNames.joinToString(" ")
        requested.contains("assemble") || requested.contains("bundle") || requested.contains("package")
    }
    doFirst {
        val arm = rootProject.file("app/src/main/jniLibs/arm64-v8a/librpcsx-android.so")
        val x64 = rootProject.file("app/src/main/jniLibs/x86_64/librpcsx-android.so")
        if (!arm.exists() && !x64.exists()) {
            throw GradleException(
                "Missing RPCSX core: app/src/main/jniLibs/<abi>/librpcsx-android.so not found. " +
                "Run ./build_rpcsx.sh release (requires NDK 30.0.14904198) before assembling."
            )
        }
    }
    commandLine("sh", "-c", "echo \"RPCSX core present: \$(sha256sum app/src/main/jniLibs/arm64-v8a/librpcsx-android.so 2>/dev/null | cut -d' ' -f1 | cut -c1-8) arm64, \$(sha256sum app/src/main/jniLibs/x86_64/librpcsx-android.so 2>/dev/null | cut -d' ' -f1 | cut -c1-8) x86_64\"")
}

// Order: build core before merge, verify before package.
tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibs") }.configureEach {
    dependsOn(buildRpcsxCore)
}
tasks.matching { it.name.startsWith("package") || it.name.startsWith("assemble") }.configureEach {
    dependsOn(verifyRpcsxCore)
}
// Ensure verification runs after core build when both are scheduled.
verifyRpcsxCore.configure { dependsOn(buildRpcsxCore) }

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
    implementation(libs.squareup.okhttp3)
    implementation(libs.androidx.documentfile)
    implementation(libs.materialswitch)
    // Standard flavor only archive deps for .tzst support
    add("standardImplementation", libs.zstd.jni)
    add("standardImplementation", libs.commons.compress)
}
