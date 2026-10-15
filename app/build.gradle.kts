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
        versionCode = 5
        versionName = "0.5-prompter-hindi"
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

// Hindi speech model (Vosk small Hindi, ~40 MB). Downloaded while building, so nothing else needs to be uploaded.
val hindiModelDir = file("src/main/assets/model-hi")
val fetchHindiModel = tasks.register("fetchHindiModel") {
    outputs.dir(hindiModelDir)
    doLast {
        if (!File(hindiModelDir, "uuid").exists()) {
            hindiModelDir.deleteRecursively()
            hindiModelDir.mkdirs()
            val conn = java.net.URL("https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip")
                .openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30000
            conn.readTimeout = 120000
            java.util.zip.ZipInputStream(conn.inputStream.buffered()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val name = e.name.substringAfter('/')
                    if (name.isNotEmpty() && !name.contains("..")) {
                        val f = File(hindiModelDir, name)
                        if (e.isDirectory) {
                            f.mkdirs()
                        } else {
                            f.parentFile.mkdirs()
                            f.outputStream().use { out -> zin.copyTo(out) }
                        }
                    }
                    e = zin.nextEntry
                }
            }
            if (!File(hindiModelDir, "am").exists()) {
                throw GradleException("Hindi model download looks incomplete")
            }
            File(hindiModelDir, "uuid").writeText(java.util.UUID.randomUUID().toString())
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchHindiModel) }
