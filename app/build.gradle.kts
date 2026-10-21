import java.io.File
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.omnicast.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.omnicast.tv"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "2.5.0-Native"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,DEPENDENCIES,LICENSE*,NOTICE*}"
        }
    }
}

// Tarea automática de Gradle que descarga por HTTPS directo (sin usar git ni credenciales)
// las tablas FairPlay SAP v2.5 de serezhka/java-airplay-lib e inyecta el código en com.omnicast.tv.playfair
val injectFairPlayEngine by tasks.registering {
    doLast {
        val targetJavaDir = file("src/main/java/com/omnicast/tv/playfair")
        val targetAssetsDir = file("src/main/assets")
        targetJavaDir.mkdirs()
        targetAssetsDir.mkdirs()

        val fairPlayFile = File(targetJavaDir, "FairPlay.java")
        if (!fairPlayFile.exists()) {
            val urls = listOf(
                "https://codeload.github.com/serezhka/java-airplay-lib/zip/refs/heads/master",
                "https://codeload.github.com/serezhka/java-airplay-lib/zip/refs/heads/main",
                "https://github.com/serezhka/java-airplay-lib/archive/HEAD.zip",
                "https://jitpack.io/com/github/serezhka/java-airplay-lib/1.0.5/java-airplay-lib-1.0.5-sources.jar"
            )

            val neededClasses = setOf("FairPlay.java", "PlayFair.java", "ModifiedMd5.java", "HandGarble.java", "OmgHax.java")
            var copiedCount = 0

            for (urlStr in urls) {
                try {
                    println(">>> [OmniCast] Descargando motor FairPlay SAP v2.5 desde: $urlStr")
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0 (OmniCast-Gradle-Build)")
                    }
                    if (conn.responseCode in 200..299) {
                        val zipBytes = conn.inputStream.use { it.readBytes() }
                        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    val fileName = entry.name.substringAfterLast("/")
                                    if (fileName in neededClasses) {
                                        val className = fileName.substringBeforeLast(".")
                                        var content = zis.readBytes().toString(Charsets.UTF_8)
                                        content = content.replace(Regex("(?m)^package\\s+[^;]+;"), "package com.omnicast.tv.playfair;")
                                        content = content.replace(Regex("(?m)^import\\s+lombok[^;]+;\\s*$"), "")
                                        content = content.replace(Regex("(?m)^import\\s+(com|io)\\.github\\.serezhka[^;]+;\\s*$"), "")
                                        content = content.replace("@Slf4j", "")

                                        if (content.contains("log.") && !content.contains("LoggerFactory.getLogger")) {
                                            content = content.replace(
                                                Regex("(public\\s+)?class\\s+$className\\b([^{]*)\\{"),
                                                "public class $className$2{\n    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger($className.class);"
                                            )
                                        } else {
                                            content = content.replace(
                                                Regex("(public\\s+)?class\\s+$className\\b"),
                                                "public class $className"
                                            )
                                        }
                                        File(targetJavaDir, fileName).writeText(content, Charsets.UTF_8)
                                        copiedCount++
                                        println(">>> [OmniCast] Inyectado en com.omnicast.tv.playfair: $fileName")
                                    } else if (fileName == "info.plist") {
                                        val plistBytes = zis.readBytes()
                                        File(targetAssetsDir, "airplay_info.plist").writeBytes(plistBytes)
                                        println(">>> [OmniCast] Inyectado asset binario: airplay_info.plist (${plistBytes.size} bytes)")
                                    }
                                }
                                entry = zis.nextEntry
                            }
                        }
                        if (copiedCount >= 5) break
                    }
                } catch (e: Exception) {
                    println(">>> [OmniCast] Aviso intentando $urlStr: ${e.message}")
                }
            }
            println(">>> [OmniCast] Clases FairPlay listas: $copiedCount de 5")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(injectFairPlayEngine)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // ExoPlayer (Media3) para AirPlay Video / DLNA / Streaming 4K nativo por Hardware
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    // JmDNS para anunciar servicios nativos _airplay._tcp.local. y _raop._tcp.local.
    implementation("org.jmdns:jmdns:3.5.9")
    // Criptografía AirPlay 2 Nativa (Ed25519, Curve25519 ECDH, Apple Binary Plist y SLF4J)
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.whispersystems:curve25519-java:0.5.0")
    implementation("com.googlecode.plist:dd-plist:1.27")
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-simple:1.7.36")
}
