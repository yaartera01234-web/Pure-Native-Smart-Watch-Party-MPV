plugins {
    id("com.android.application") version "8.9.2" apply false
    // libmpvKt 0.3.0 metadata is Kotlin 2.4; older compilers cannot read it.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    // Firebase (google-services) — jab app/google-services.json maujood hoga tab lagega
    id("com.google.gms.google-services") version "4.4.2" apply false
}
