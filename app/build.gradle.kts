plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.cliqplace.printbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.cliqplace.printbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Jeden společný podpisový klíč, aby šly nové verze instalovat přes starou bez odinstalace.
    signingConfigs {
        create("shared") {
            storeFile = file("signing.keystore")
            storeType = "PKCS12"
            storePassword = "entradio"
            keyAlias = "bridge"
            keyPassword = "entradio"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    // Vestavěná tiskárna Sunmi
    implementation("com.sunmi:printerlibrary:1.0.24")
    // WebSocket server, který se pro web tváří jako QZ Tray
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("org.slf4j:slf4j-nop:2.0.13")
    // Generování lokálního certifikátu pro wss://localhost
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
