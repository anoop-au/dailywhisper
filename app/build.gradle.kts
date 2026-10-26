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
        versionCode = 8
        versionName = "0.1.7"
        // ML Kit (16 MB) and ONNX Runtime (19 MB) ship native libraries per ABI. The Fold 8 (and every current phone) is
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
    // Opus-MT translation on the phone (OpusMt.kt), Anoop's OK (Oct 2026). 1.23.2: the Android build adds a telemetry
    // ContentProvider and network permissions from 1.29, and 1.24+ is bigger (arm64 native: 19 MB here vs 34 MB in 1.31).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
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
