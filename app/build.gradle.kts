plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}


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
        ndk { abiFilters += "arm64-v8a" }
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


    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
