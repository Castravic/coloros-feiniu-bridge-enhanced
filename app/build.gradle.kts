plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val xposedCompileApiVersion = "82"
val releaseStoreFile = System.getenv("SIGNING_STORE_FILE")
val releaseStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")

android {
    namespace = "io.github.colorosfeiniu.bridge"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.github.colorosfeiniu.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.3.1"
    }

    if (
        releaseStoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null
    ) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Pure legacy Xposed Bridge module. Do not add libxposed entry points here.
    compileOnly("de.robv.android.xposed:api:$xposedCompileApiVersion")
}
