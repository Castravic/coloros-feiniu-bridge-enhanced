plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val xposedCompileApiVersion = "82"

android {
    namespace = "io.github.colorosfeiniu.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.colorosfeiniu.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
    // The legacy Xposed bridge API is still compiled against api:82; LSPosed runtime
    // compatibility is declared separately through xposedminversion=101 in AndroidManifest.xml.
    compileOnly("de.robv.android.xposed:api:$xposedCompileApiVersion")
}
