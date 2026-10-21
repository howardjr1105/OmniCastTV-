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
        versionCode = 6
        versionName = "2.2.0-Native"
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

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // ExoPlayer (Media3) para AirPlay Video / DLNA / Streaming 4K nativo por Hardware
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    // JmDNS para anunciar servicios nativos _airplay._tcp.local. y _raop._tcp.local.
    implementation("org.jmdns:jmdns:3.5.9")
    // Criptografía AirPlay 2 Nativa (Ed25519, Curve25519 ECDH y Apple Binary Plist)
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.whispersystems:curve25519-java:0.5.0")
    implementation("com.googlecode.plist:dd-plist:1.27")
}
