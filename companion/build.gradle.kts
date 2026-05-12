// Root build file. Plugin versions are declared here and applied per-module.

plugins {
    // AGP 8.7+ supports compileSdk 35 without falling back to build-tools 34.0.0.
    // 8.5.x triggers an auto-install attempt that fails on the read-only nix
    // Android SDK in /nix/store. Stay on the 8.7 line — 8.9.x bumps the
    // minimum gradle/JDK and isn't needed yet.
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0+ ships Compose as a separate plugin (formerly bundled into the
    // Kotlin Gradle plugin). Versions must match the Kotlin plugin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
