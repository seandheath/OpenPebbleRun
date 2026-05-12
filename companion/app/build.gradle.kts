plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "run.openpebble.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "run.openpebble.companion"
        minSdk = 26       // spec §5.1
        targetSdk = 35    // spec §5.1
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Kotlin source dirs (we use src/main/kotlin instead of src/main/java).
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
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

    // PebbleKitAndroid2 dependency is deliberately omitted at the skeleton stage.
    // It is wired in step 5 (end-to-end run start/stop). See docs/log.md TODOs.
}
