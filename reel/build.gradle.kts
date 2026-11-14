plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.field.reel"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.field.reel"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // The speech runtime ships native code for four CPU types; every
        // current phone (including Pixels) is arm64, so ship only that.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":field"))

    // sherpa-onnx (Apache-2.0): on-device speech recognition runtime.
    // The AAR is fetched by scripts/fetch-sherpa.sh (CI does this).
    implementation(files("libs/sherpa-onnx.aar"))

    testImplementation("junit:junit:4.13.2")
}
