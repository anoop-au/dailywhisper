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
        versionCode = 7
        versionName = "0.1.6"
        // Fold 8 (and every current phone) is arm64; one ABI keeps the APK small (ML Kit + llama.cpp are native).
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release") } }
    }
    ndkVersion = "27.2.12479018"
    // Hy-MT translator: llama.cpp (vendored in src/main/cpp/llama.cpp) + a thin JNI bridge.
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
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
}
kotlin { jvmToolchain(17) }

// Dependencies: Android framework + Kotlin stdlib, plus ML Kit on-device translation (Anoop's OK, Oct 2026)
// for the built-in translator. The Hy-MT translator is native (llama.cpp, vendored source).
dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
}

// Copy the signed release APK to out/DailyWhisper-v<version>.apk after assembleRelease.
val copyReleaseApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*-release.apk") }
    into(rootProject.layout.projectDirectory.dir("out"))
    rename { "DailyWhisper-v${android.defaultConfig.versionName}.apk" }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyReleaseApk) }
