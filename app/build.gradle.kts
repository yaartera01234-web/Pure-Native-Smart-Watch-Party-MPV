plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.party.wpnative"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.party.wpnative"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.16-FIRSTPAGE"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}
