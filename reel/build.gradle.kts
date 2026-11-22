plugins {
    id("field.app")
}

android {
    namespace = "dev.field.reel"

    defaultConfig {
        applicationId = "dev.field.reel"
        versionCode = 1
        versionName = "1.0"

        // The speech runtime ships native code for four CPU types; every
        // current phone (including Pixels) is arm64, so ship only that.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // sherpa-onnx (Apache-2.0): on-device speech recognition runtime.
    // The AAR is fetched by scripts/fetch-sherpa.sh (CI does this).
    implementation(files("libs/sherpa-onnx.aar"))
}
