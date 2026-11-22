plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.callanswer.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.callanswer.spike"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Telephony hardware is required for this app; ChromeOS is not a target.
        disable += "PermissionImpliesUnsupportedChromeOsHardware"
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // S7: sherpa-onnx AAR (Whisper, Silero VAD, Piper) is not in Maven; see spike/S7-README.md.
    implementation(files("libs/sherpa-onnx-2.0.2.aar"))
    // S7: Vosk streaming ASR.
    implementation("com.alphacephei:vosk-android:0.3.75")
}
