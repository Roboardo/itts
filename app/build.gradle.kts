import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key lives outside the repo; see README.
val signingProps = file(
    providers.gradleProperty("itts.signing").orNull
        ?: "${System.getProperty("user.home")}/keystores/itts-release.properties"
)

android {
    namespace = "net.biavati.itts"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "net.biavati.itts"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0"
        // Only ship the ABI real phones use; keeps the APK small.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (signingProps.exists()) {
            create("release") {
                val p = Properties().apply { signingProps.inputStream().use { load(it) } }
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release version instead of conflicting with it.
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Trascrivi (dev)")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            resValue("string", "app_name", "Trascrivi")
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
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

// sherpa-onnx (offline speech recognition, prebuilt native libs, no NDK needed)
// isn't on Maven Central, so fetch the release AAR once into app/libs.
val sherpaVersion = "2.0.0"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
        .toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
}

dependencies {
    implementation(files(sherpaAar))
}
