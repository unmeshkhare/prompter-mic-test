plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.graviton.promptertest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.graviton.promptertest"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4-prompter"
        // Poco M6 and Xiaomi Pad 6 are both arm64; keeps the APK small.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
}
