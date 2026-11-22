plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Build flags decided by the feasibility spike (task 2). The spike results are not yet
// recorded in design.md, so these hold the conservative defaults until they are:
//  - ASR_ENGINE: Vosk is the fallback unless S7 proves Whisper meets p90 <= 2.5 s and >= 90% intent accuracy.
//  - SILENT_SMS_ENABLED: S8 unproven, so every report uses ACTION_SENDTO.
val asrEngine = "VOSK_STREAMING" // or "WHISPER_BASE_INT8"
val silentSmsEnabled = false

// Public identifiers of the deployed lambda/polly_tts stack (SAM outputs). Set them in ~/.gradle/gradle.properties
// or with -P; blank values leave the account feature switched off. None of them is a secret.
fun awsProp(name: String): String = (project.findProperty(name) as String?).orEmpty()
android {
    namespace = "com.callanswer.app"
    compileSdkVersion("android-37.1")
    defaultConfig {
        applicationId = "com.callanswer.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "ASR_ENGINE", "\"$asrEngine\"")
        buildConfigField("boolean", "SILENT_SMS_ENABLED", silentSmsEnabled.toString())
        buildConfigField("String", "AWS_REGION", "\"${awsProp("callanswer.aws.region")}\"")
        buildConfigField("String", "COGNITO_USER_POOL_ID", "\"${awsProp("callanswer.cognito.userPoolId")}\"")
        buildConfigField("String", "COGNITO_CLIENT_ID", "\"${awsProp("callanswer.cognito.clientId")}\"")
        buildConfigField("String", "COGNITO_IDENTITY_POOL_ID", "\"${awsProp("callanswer.cognito.identityPoolId")}\"")
        buildConfigField("String", "TTS_API_URL", "\"${awsProp("callanswer.tts.apiUrl")}\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    androidResources {
        // Model files must stay uncompressed so they can be memory-mapped.
        noCompress += listOf("onnx", "bin", "mdl")
    }
    packaging {
        // Keep native libraries uncompressed and page-aligned (16 KB page-size compatibility).
        jniLibs.useLegacyPackaging = false
    }
    lint {
        disable += "PermissionImpliesUnsupportedChromeOsHardware"
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)

    // sherpa-onnx (Silero VAD, Piper TTS, optional Whisper) AAR is not on Maven; see spike/S7-README.md.
    implementation(files("libs/sherpa-onnx-2.0.2.aar"))
    // ASR_ENGINE = VOSK_STREAMING (S7 fallback). Whisper runs through sherpa-onnx above.
    implementation(libs.vosk.android)

    // On-device translation of Delivery_Steps and quick replies (9.19); only used when the model is already downloaded.
    implementation(libs.mlkit.translate)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}

// Renders the fallback phrase WAVs into src/main/assets/fallback (needs the AWS CLI profile "ldots"; run manually).
tasks.register<Exec>("generateFallbackPhrases") {
    group = "assets"
    commandLine(rootProject.file("tools/generate_fallback_phrases.sh").absolutePath)
}
