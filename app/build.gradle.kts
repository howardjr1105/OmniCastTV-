import java.io.File

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
        versionCode = 8
        versionName = "2.4.0-Native"
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

// Tarea automática de Gradle que descarga e inyecta las tablas criptográficas FairPlay SAP v2.5 (PlayFair, ModifiedMd5, HandGarble, OmgHax, FairPlay) y el descriptor binario info.plist antes de compilar
val injectFairPlayEngine by tasks.registering {
    doLast {
        val targetJavaDir = file("src/main/java/com/omnicast/tv")
        val targetAssetsDir = file("src/main/assets")
        targetJavaDir.mkdirs()
        targetAssetsDir.mkdirs()

        val fairPlayFile = File(targetJavaDir, "FairPlay.java")
        if (!fairPlayFile.exists()) {
            println(">>> [OmniCast] Descargando motor criptográfico FairPlay SAP v2.5 (serezhka/jap2lib)...")
            val tmpCloneDir = File(buildDir, "jap2lib-tmp")
            if (tmpCloneDir.exists()) tmpCloneDir.deleteRecursively()

            val proc = ProcessBuilder("git", "clone", "--depth", "1", "https://github.com/serezhka/jap2lib.git", tmpCloneDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val outText = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw GradleException("No se pudo clonar jap2lib: $outText")
            }

            val neededClasses = setOf("FairPlay.java", "PlayFair.java", "ModifiedMd5.java", "HandGarble.java", "OmgHax.java")
            var copiedCount = 0

            tmpCloneDir.walkTopDown().forEach { srcFile ->
                if (srcFile.name in neededClasses) {
                    val className = srcFile.nameWithoutExtension
                    var content = srcFile.readText(Charsets.UTF_8)
                    // Cambiar al paquete com.omnicast.tv para acceso directo desde Kotlin
                    content = content.replace(Regex("(?m)^package\\s+[^;]+;"), "package com.omnicast.tv;")
                    // Eliminar imports de lombok o paquetes antiguos
                    content = content.replace(Regex("(?m)^import\\s+lombok[^;]+;\\s*$"), "")
                    content = content.replace(Regex("(?m)^import\\s+(com|io)\\.github\\.serezhka[^;]+;\\s*$"), "")
                    content = content.replace("@Slf4j", "")
                    // Asegurar que la clase y sus métodos sean públicos e inyectar logger SLF4J si usa 'log.'
                    if (content.contains("log.")) {
                        content = content.replace(
                            Regex("(public\\s+)?class\\s+$className\\s*\\{"),
                            "public class $className {\n    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger($className.class);"
                        )
                    } else {
                        content = content.replace(
                            Regex("(public\\s+)?class\\s+$className\\s*\\{"),
                            "public class $className {"
                        )
                    }
                    File(targetJavaDir, srcFile.name).writeText(content, Charsets.UTF_8)
                    copiedCount++
                    println(">>> [OmniCast] Inyectado: ${srcFile.name}")
                } else if (srcFile.name == "info.plist") {
                    srcFile.copyTo(File(targetAssetsDir, "airplay_info.plist"), overwrite = true)
                    println(">>> [OmniCast] Inyectado asset binario: airplay_info.plist (${srcFile.length()} bytes)")
                }
            }

            if (copiedCount < 5) {
                throw GradleException("Faltan archivos de FairPlay SAP v2.5 (copiados: $copiedCount de 5)")
            }
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
