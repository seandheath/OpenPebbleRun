// Root build file. Plugin versions are declared here and applied per-module.

plugins {
    // AGP 8.9+ is required by androidx.core 1.17.0 (pulled in transitively
    // via PebbleKitAndroid2 1.1.0). 8.9.x requires gradle 8.11.1+ (see
    // gradle/wrapper/gradle-wrapper.properties).
    id("com.android.application") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0+ ships Compose as a separate plugin (formerly bundled into the
    // Kotlin Gradle plugin). Versions must match the Kotlin plugin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
