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
        // PebbleKitAndroid2 is published on JitPack (spec §3, §10.2 risk note).
        // Added now so wiring in step 5 doesn't require a build-config change.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "OpenPebbleRun"
include(":app")
