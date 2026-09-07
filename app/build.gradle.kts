plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val libxposedVersion = "102.0.0"

android {
    namespace = "io.github.colorosfeiniu.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.colorosfeiniu.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.2.0"
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
    compileOnly("io.github.libxposed:api:$libxposedVersion")
    testImplementation("io.github.libxposed:api:$libxposedVersion")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    System.getProperty("gallery.dex.path")?.let { path ->
        systemProperty("gallery.dex.path", path)
    }
}
