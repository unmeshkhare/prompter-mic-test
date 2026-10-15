import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream

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
        versionCode = 6
        versionName = "0.6-prompter-hindi-fast"
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

// Hindi speech model (Vosk small Hindi, ~40 MB), downloaded while building so nothing else needs uploading.
// If the download fails the build still succeeds (English keeps working; only Hindi would be unavailable).
val hindiModelDir = file("src/main/assets/model-hi")
val fetchHindiModel = tasks.register("fetchHindiModel") {
    doLast {
        val marker = File(hindiModelDir, "uuid")
        if (!marker.exists()) {
            try {
                hindiModelDir.deleteRecursively()
                hindiModelDir.mkdirs()
                val conn = URL("https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip")
                    .openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 30000
                conn.readTimeout = 120000
                val zin = ZipInputStream(BufferedInputStream(conn.inputStream))
                try {
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val rel = entry.name.substringAfter('/')
                        if (rel.isNotEmpty() && !rel.contains("..")) {
                            val target = File(hindiModelDir, rel)
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile.mkdirs()
                                val os = FileOutputStream(target)
                                try {
                                    zin.copyTo(os)
                                } finally {
                                    os.close()
                                }
                            }
                        }
                        entry = zin.nextEntry
                    }
                } finally {
                    zin.close()
                }
                if (File(hindiModelDir, "am").exists()) {
                    marker.writeText(UUID.randomUUID().toString())
                } else {
                    println("WARNING: Hindi model download looks incomplete, Hindi will not work in this build")
                    hindiModelDir.deleteRecursively()
                }
            } catch (ex: Exception) {
                println("WARNING: could not download the Hindi model: " + ex)
                hindiModelDir.deleteRecursively()
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchHindiModel) }
