import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The app ships with the server's release: versionName IS pyproject.toml's
// version, versionCode is derived from it (1.2.3 → 10203) so it only ever
// goes up, which Android requires for an update to install.
val pyVersion: String = rootProject.file("../pyproject.toml").readLines()
    .first { it.trim().startsWith("version") }.substringAfter('"').substringBefore('"')
val pyCode: Int = pyVersion.split('.').map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

// Release signing comes from the environment (CI: the ANDROID_KEYSTORE_*
// secrets, see android/README.md). Without it the release build is signed
// with the debug key so a locally built APK still installs — but such an
// APK can't later be UPDATED by a properly signed one (different key).
val ksPath: String? = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.darylno.cardscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.darylno.cardscanner"
        minSdk = 29          // SslCertificate.getX509Certificate (pinning in the WebView)
        targetSdk = 35
        versionCode = pyCode
        versionName = pyVersion
        ndk { abiFilters += "arm64-v8a" }   // Nord N200 is arm64; keeps OpenCV to one ABI
    }

    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "cardscanner"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric smoke tests (ScreensSmokeTest) inflate the real manifest/resources.
        unitTests.isIncludeAndroidResources = true
    }
    // The ident/ tests (PhoneIdentifier, ArtPackStore) replay :core's committed
    // server fixtures — the art pack, the arthash index, recorded Scryfall pages,
    // ranker scans/images — instead of copying them.
    sourceSets.getByName("test").resources.srcDir("../core/src/test/resources")
    // …and the golden API replay (core/src/testShared), run against the SQLite store.
    sourceSets.getByName("test").java.srcDir("../core/src/testShared/kotlin")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.opencv.android)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.okhttp)
    implementation(libs.cronet.embedded)   // Diagnostics → Network test only (f2f/F2fProbe)
    implementation(libs.mlkit.text.recognition)   // collector-line OCR (ocr/MlKitOcrEngine), bundled model
    implementation(libs.zxing.core)
    implementation(libs.nanohttpd)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.opencv.jvm)   // CapturePipeline tests (natives via nu.pattern.OpenCV)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
