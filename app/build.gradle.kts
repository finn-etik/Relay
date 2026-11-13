plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.relay"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.relay"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            // R8 strips unused Compose/Kotlin code: the release APK is a
            // fraction of the debug one.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Personal sideload app: sign release builds with the local
            // debug key so `assembleRelease` produces an installable APK.
            // Swap in your own keystore if you ever distribute it.
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = false
    }
}

dependencies {
    // No Material: the UI is drawn with foundation primitives only,
    // which keeps the APK small.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    testImplementation("junit:junit:4.13.2")
}
