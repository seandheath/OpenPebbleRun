// Root build file. Plugin versions are declared here and applied per-module.

plugins {
    // AGP 8.9+ is required by androidx.core 1.17.0 (pulled in transitively
    // via PebbleKitAndroid2 1.1.0). 8.9.x requires gradle 8.11.1+ (see
    // gradle/wrapper/gradle-wrapper.properties).
    id("com.android.application") version "8.9.3" apply false
    // Kotlin 2.3.20: matches the metadata version baked into PebbleKitAndroid2
    // 1.1.0's published artifacts ("metadata is 2.3.0, expected version is 2.0.0"
    // when running 2.0.21 against those jars). Newer compiler reading older
    // metadata is fine; the reverse is what fails.
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    // Compose compiler plugin version must match the Kotlin Gradle plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
