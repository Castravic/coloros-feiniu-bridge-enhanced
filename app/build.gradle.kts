plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val libxposedVersion = "102.0.0"
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
        versionCode = 15
        versionName = "0.4.0"
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

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Modern libxposed API 102 module (see META-INF/xposed descriptors).
    compileOnly("io.github.libxposed:api:$libxposedVersion")
    implementation("org.luckypray:dexkit:2.2.0")
    testImplementation("io.github.libxposed:api:$libxposedVersion")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf("gallery.dex.path", "gallery.apk.path", "mydevices.dex.path").forEach { property ->
        System.getProperty(property)?.let { path -> systemProperty(property, path) }
    }
}
