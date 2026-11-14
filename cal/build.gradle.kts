plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.field.cal"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.field.cal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    implementation(project(":field"))

    testImplementation("junit:junit:4.13.2")
}
