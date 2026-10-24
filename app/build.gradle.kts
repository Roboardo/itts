plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.biavati.itts"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "net.biavati.itts"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Only ship the ABI real phones use; keeps the APK small.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // Offline speech recognition (prebuilt native libs, no NDK needed).
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
