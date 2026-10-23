import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.jarvis.ev"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.jarvis.ev"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}


dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}

val voskModel by tasks.registering {
    doLast {
        val out = project.file("src/main/assets/vosk-model")
        if (!File(out, "am").exists()) {
            out.deleteRecursively()
            out.mkdirs()
            val url = URL("https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip")
            ZipInputStream(url.openStream().buffered()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val rel = e.name.split("/").drop(1).joinToString("/")
                    if (rel.isNotEmpty()) {
                        val f = File(out, rel)
                        if (e.isDirectory) f.mkdirs() else {
                            f.parentFile.mkdirs()
                            f.outputStream().use { o -> zin.copyTo(o) }
                        }
                    }
                    e = zin.nextEntry
                }
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(voskModel) }
