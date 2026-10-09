plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// google-services.json aate hi plugin apne aap lag jayega.
// Abhi file nahi hai, is liye bina Firebase ke bhi app chalega (koi crash nahi).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
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

dependencies {
    // Firebase (BoM = sab libraries ke versions apne aap match)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")   // messages + presence + typing
    implementation("com.google.firebase:firebase-messaging")   // push (agle step mein)
}
