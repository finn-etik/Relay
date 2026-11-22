// Convention plugin `field.app`: everything a Field app's build has in
// common. A module applies it and adds only what is its own:
//
//     plugins { id("field.app") }
//     android {
//         namespace = "dev.field.tally"
//         defaultConfig {
//             applicationId = "dev.field.tally"
//             versionCode = 1
//             versionName = "1.0"
//         }
//     }

plugins {
    id("field.android.application")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    defaultConfig {
        minSdk = catalog.findVersion("min-sdk").get().requiredVersion.toInt()
    }

    signingConfigs {
        // CI signs with a stable key when the FIELD_KEYSTORE secret is set,
        // so new builds install over old ones. Otherwise: the debug key.
        create("field") {
            System.getenv("FIELD_KEYSTORE_PATH")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("FIELD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FIELD_KEY_ALIAS")
                keyPassword = System.getenv("FIELD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 strips unused Compose / Kotlin code: a fraction of the debug size.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("FIELD_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("field")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    // The shared Field Design Language components (and, through them, Compose).
    "implementation"(project(":field"))
}
