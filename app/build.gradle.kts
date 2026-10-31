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
        versionCode = 12
        versionName = "2.8.0-Native"
    }

    // Keystore fija (secreto de CI) para que cada versión se instale encima de la anterior sin desinstalar.
    // Sin OMNICAST_KEYSTORE (build local) se usa la firma debug de la máquina.
    val omnicastKeystore = System.getenv("OMNICAST_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (omnicastKeystore != null) {
            create("omnicast") {
                storeFile = omnicastKeystore
                storeType = "pkcs12"
                storePassword = System.getenv("OMNICAST_KEYSTORE_PASSWORD")
                keyAlias = "omnicast"
                keyPassword = System.getenv("OMNICAST_KEYSTORE_PASSWORD")
            }
        }
    }
    val appSigning = signingConfigs.findByName("omnicast") ?: signingConfigs.getByName("debug")

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = appSigning
        }
        debug {
            signingConfig = appSigning
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
// todas las clases y tablas S-Box de FairPlay SAP v2.5 (FairPlay, OmgHax, OmgHaxConst, HandGarble, ModifiedMD5, SapHash)
val injectFairPlayEngine by tasks.registering {
    doLast {
        val targetJavaDir = file("src/main/java/com/omnicast/tv/playfair")
        val targetAssetsDir = file("src/main/assets")
        targetJavaDir.mkdirs()
        targetAssetsDir.mkdirs()

        val fairPlayFile = File(targetJavaDir, "FairPlay.java")
        val omgHaxConstFile = File(targetJavaDir, "OmgHaxConst.java")
        // OmgHaxConst carga table_s1..s10 con getResource("/table_sN"): sin ellas FairPlay falla con ExceptionInInitializerError
        val tablesDir = file("src/main/resources")
        tablesDir.mkdirs()
        if (!fairPlayFile.exists() || !omgHaxConstFile.exists() || !File(tablesDir, "table_s10").exists()) {
            val urls = listOf(
                "https://codeload.github.com/serezhka/java-airplay-lib/zip/refs/heads/master",
                "https://github.com/serezhka/java-airplay-lib/archive/HEAD.zip",
                "https://jitpack.io/com/github/serezhka/java-airplay-lib/1.0.5/java-airplay-lib-1.0.5-sources.jar"
            )

            // Prefijos en minúsculas para capturar FairPlay, OmgHax, OmgHaxConst, HandGarble, ModifiedMD5, SapHash y cualquier *Const
            val neededPrefixes = listOf("fairplay", "playfair", "omghax", "handgarble", "modifiedmd5", "saphash")
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
                                if (!entry.isDirectory && !entry.name.contains("/test/")) {
                                    val fileName = entry.name.substringAfterLast("/")
                                    val lowerName = fileName.lowercase()
                                    if (fileName.endsWith(".java") && neededPrefixes.any { lowerName.startsWith(it) }) {
                                        val className = fileName.substringBeforeLast(".")
                                        var content = zis.readBytes().toString(Charsets.UTF_8)

                                        // Reemplazar paquete tanto en `package ...` como en `import static com.github.serezhka.jap2lib.OmgHaxConst.*;`
                                        content = content.replace("com.github.serezhka.jap2lib", "com.omnicast.tv.playfair")
                                        content = content.replace("io.github.serezhka.jap2lib", "com.omnicast.tv.playfair")
                                        content = content.replace(Regex("(?m)^import\\s+lombok[^;]+;\\s*$"), "")
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
                                        // Hacer públicos los métodos package-private en FairPlay.java
                                        if (className == "FairPlay") {
                                            content = content.replace(
                                                Regex("(?m)^(\\s+)(void\\s+fairPlaySetup)"),
                                                "$1public $2"
                                            )
                                            content = content.replace(
                                                Regex("(?m)^(\\s+)(byte\\[\\]\\s+decryptAesKey)"),
                                                "$1public $2"
                                            )
                                        }
                                        File(targetJavaDir, fileName).writeText(content, Charsets.UTF_8)
                                        copiedCount++
                                        println(">>> [OmniCast] Inyectado en com.omnicast.tv.playfair: $fileName")
                                    } else if (Regex("table_s\\d+").matches(fileName) && entry.name.contains("/src/main/resources/")) {
                                        File(tablesDir, fileName).writeBytes(zis.readBytes())
                                        println(">>> [OmniCast] Inyectada tabla FairPlay: $fileName")
                                    } else if (lowerName == "info.plist") {
                                        val plistBytes = zis.readBytes()
                                        File(targetAssetsDir, "airplay_info.plist").writeBytes(plistBytes)
                                        println(">>> [OmniCast] Inyectado asset binario: airplay_info.plist (${plistBytes.size} bytes)")
                                    }
                                }
                                entry = zis.nextEntry
                            }
                        }
                        if (copiedCount >= 6) break
                    }
                } catch (e: Exception) {
                    println(">>> [OmniCast] Aviso intentando $urlStr: ${e.message}")
                }
            }
            println(">>> [OmniCast] Total clases FairPlay inyectadas: $copiedCount")
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
    // QR en la pantalla de inicio para abrir el emisor web desde un teléfono
    implementation("com.google.zxing:core:3.5.3")
    // Criptografía AirPlay 2 Nativa (Ed25519, Curve25519 ECDH, Apple Binary Plist y SLF4J)
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.whispersystems:curve25519-java:0.5.0")
    implementation("com.googlecode.plist:dd-plist:1.27")
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-simple:1.7.36")
}
