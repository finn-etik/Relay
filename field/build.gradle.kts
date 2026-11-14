plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Shared Field Design Language components for the CAL, GATE, REEL and
// TALLY apps. Each app supplies its own signal accent via FieldTheme.
android {
    namespace = "dev.field.ui"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
        buildConfig = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    api(composeBom)
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.ui:ui")
    api("androidx.activity:activity-compose:1.9.3")
    api("androidx.core:core-ktx:1.15.0")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
