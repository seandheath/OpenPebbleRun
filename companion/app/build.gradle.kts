import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "run.openpebble.companion"
    // compileSdk bumped to 36 because transitive deps (notably androidx.core
    // 1.17.0 from PebbleKitAndroid2 1.1.0's dep closure) require Android 36
    // APIs to be available. targetSdk stays at 35 per spec §5.1 — bumping
    // compileSdk doesn't change runtime behavior, only what APIs the code is
    // allowed to call against.
    compileSdk = 36

    defaultConfig {
        applicationId = "run.openpebble.companion"
        minSdk = 26       // spec §5.1
        targetSdk = 35    // spec §5.1 — runtime behavior, kept on 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Skeleton: unsigned. Production signing added in spec §10.2 (IzzyOnDroid submission step).
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Kotlin source dirs (we use src/main/kotlin instead of src/main/java).
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
}

// Kotlin 2.3 removed the legacy `android { kotlinOptions { … } }` DSL.
// Use the top-level `kotlin { compilerOptions { … } }` block instead.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // === AndroidX core ===
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // === Compose ===
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // === PebbleKitAndroid2 (spec §3, §5.1, §10.2) ===
    // Published to Maven Central as of v1.0.0 (spec §10.2's JitPack/F-Droid risk
    // note is obsolete — see docs/log.md). Pin to 1.1.0 per spec §11 guidance.
    implementation("io.rebble.pebblekit2:client:1.1.0")
}
