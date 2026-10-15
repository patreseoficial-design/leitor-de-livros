plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}

// Assinatura da versão da Play Store: os dados vêm dos "Secrets"
// do GitHub (veja docs/PUBLICAR-NA-PLAY-STORE.md). Sem eles, só a
// versão de teste (debug) é gerada.
val arquivoChave = System.getenv("KEYSTORE_FILE")
val temChave = !arquivoChave.isNullOrBlank() && file(arquivoChave).exists()

android {
    namespace = "com.leitordelivros.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.leitordelivros.app"
        minSdk = 24
        targetSdk = 36

        // Cada envio para a Play Store precisa de um número maior:
        // usa o número da execução do GitHub Actions
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("VERSION_CODE") ?: "0"}"
    }

    signingConfigs {
        if (temChave) {
            create("release") {
                storeFile = file(arquivoChave!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (temChave) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}
