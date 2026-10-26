import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing secrets live OUTSIDE the repo (own key, never Vion's). Override with DAILYWHISPER_KEYSTORE_PROPS.
val keystorePropsFile = file(System.getenv("DAILYWHISPER_KEYSTORE_PROPS") ?: (System.getProperty("user.home") + "/dailywhisper-keys/keystore.properties"))
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) } }

android {
    namespace = "com.anoop.dailywhisper"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.anoop.dailywhisper"
        minSdk = 30
        targetSdk = 36
        versionCode = 12
        versionName = "0.1.11"
        // ML Kit (16 MB) and ONNX Runtime (29 MB) ship native libraries per ABI. The Fold 8 (and every current phone) is
        // arm64, so only that one.
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { checkReleaseBuilds = false }
    // JVM UI tests (Robolectric) for the first-run flow; ./gradlew testDebugUnitTest. Test-only: nothing ships in the APK.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
kotlin { jvmToolchain(17) }

// Dependencies: Android framework + Kotlin stdlib, plus ML Kit and ONNX Runtime for on-device translation (Anoop's OK, Oct 2026).
dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    // Opus-MT translation on the phone (OpusMt.kt), Anoop's OK (Oct 2026). 1.28.0 (Anoop's OK to update, Oct 2026): 1.24.3
    // fixed the SIGILL on Arm SME processors (Snapdragon 8 Elite Gen 5, Galaxy Z Fold 8) and 1.28.0 the wrong-results
    // regression of 1.27. Stay below 1.29: its Android build adds a telemetry ContentProvider and network permissions.
    // 1.28.0's AAR declares no permissions or components, and its native code has no network calls (checked Oct 2026).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

// Copy the signed release APK to out/DailyWhisper-v<version>.apk after assembleRelease.
val copyReleaseApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*-release.apk") }
    into(rootProject.layout.projectDirectory.dir("out"))
    rename { "DailyWhisper-v${android.defaultConfig.versionName}.apk" }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyReleaseApk) }
