plugins {
    id("field.android.application")
}

// Relay: streams the phone's audio to Sonos. It predates the Field apps and
// draws its own UI, so it does not depend on :field.
android {
    namespace = "dev.relay"

    defaultConfig {
        applicationId = "dev.relay"
        minSdk = 29
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
}

dependencies {
    // No Material: the UI is drawn with foundation primitives only,
    // which keeps the APK small.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
}
