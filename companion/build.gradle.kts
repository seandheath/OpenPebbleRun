// Root build file. Plugin versions are declared here and applied per-module.

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0+ ships Compose as a separate plugin (formerly bundled into the
    // Kotlin Gradle plugin). Versions must match the Kotlin plugin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
