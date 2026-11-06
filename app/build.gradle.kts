plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.geanalves.tvonline"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.geanalves.tvonline"
        minSdk = 23
        targetSdk = 34
        // Cada compilação no GitHub ganha um número maior, para a TV aceitar a atualização.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Chave fixa para que as atualizações instalem por cima da versão anterior.
        create("fixa") {
            storeFile = file("tv-online.jks")
            storePassword = "tvonline"
            keyAlias = "tvonline"
            keyPassword = "tvonline"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixa")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Player da TV ao vivo (IPTV): toca HLS (.m3u8) e transmissões .ts
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
