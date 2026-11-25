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

    signingConfigs {
        create("field") {
            System.getenv("FIELD_KEYSTORE_PATH")?.let { path ->
                val password = System.getenv("FIELD_KEYSTORE_PASSWORD")
                storeFile = file(path)
                storePassword = password
                keyAlias = System.getenv("FIELD_KEY_ALIAS").orEmpty().ifEmpty { "field" }
                keyPassword = System.getenv("FIELD_KEY_PASSWORD").orEmpty().ifEmpty { password }
            }
        }
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
            // CI signs with the kit's permanent key (see build.yml), so new
            // builds install over old ones; a local build uses the debug key.
            signingConfig = signingConfigs.getByName(if (System.getenv("FIELD_KEYSTORE_PATH") != null) "field" else "debug")
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
