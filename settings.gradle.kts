pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Smart Music Watch Party ka proven on-device YouTube resolver.
        maven { url = uri("https://jitpack.io") }
        // libmpvKt ka official Maven repository.
        maven("https://yuroyami.github.io/maven") {
            content { includeModuleByRegex("io\\.github\\.yuroyami", "libmpvkt.*") }
        }
    }
}
rootProject.name = "WatchPartyNative"
include(":app")
