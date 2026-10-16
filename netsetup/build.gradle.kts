// Wi-Fi setup helper for the glasses, without the RayNeo phone app.
//
// targetSdk 28 is the point of this module, not an oversight: from Android 10 (API 29) on,
// WifiManager.addNetwork / enableNetwork / removeNetwork / setWifiEnabled always fail for apps
// that target 29+, unless the app is a system app or a device owner. Apps that still target 28
// keep the old behaviour. KLLA itself targets 34, so it cannot do this; this separate APK can.
// It is never shipped to a store and is driven over adb (see docs/rayneo-platform-notes.md §10).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.woolab.klla.netsetup"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.woolab.klla.netsetup"
        minSdk = 30
        @Suppress("ExpiredTargetSdkVersion")
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
