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
        // Exact Synkplay v0.23.0 JNI/native bundle is built against Android 8 (API 26).
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.0"
        // Reference player ships one native MPV ABI; keeping it exact also avoids a
        // needlessly huge universal APK.
        ndk { abiFilters += listOf("arm64-v8a") }
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

    packaging {
        // libmpv's native libraries must be installed uncompressed on older devices.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Firebase (BoM = sab libraries ke versions apne aap match)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")   // messages + presence + typing
    implementation("androidx.recyclerview:recyclerview:1.3.2")   // smooth list (Instagram jaisi)
    implementation("com.google.firebase:firebase-messaging")   // push (agle step mein)
    // Party Room ka selected public MQTT tower (EMQX / HiveMQ / tyckr), pure native WSS.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // Pure-native 1:1 voice calls. The stripped build keeps audio/WebRTC while omitting
    // unused software video codecs; its Java package is relocated to avoid collisions.
    implementation("io.github.webrtc-sdk:android-prefixed-stripped:150.7871.01")

    // ACT7 ka Kotlin surface/wrapper. CI final APK mein iske stock native bundle ko
    // tests/mpv023 ke verified Synkplay v0.23.0 engine + rebuilt JNI se replace karta hai.
    implementation("io.github.yuroyami:libmpvkt:0.3.0")
    implementation("io.github.yuroyami:libmpvkt-view:0.3.0")

    // YouTube page URL ko device par signed video + audio streams mein resolve karta hai.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
