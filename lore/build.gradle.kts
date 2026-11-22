plugins {
    id("field.app")
}

android {
    namespace = "dev.field.lore"

    defaultConfig {
        applicationId = "dev.field.lore"
        versionCode = 1
        versionName = "1.0"

        // Both libraries below ship native code for several CPU types; every
        // phone that can run a language model is arm64, so ship only that.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // LiteRT-LM (Apache-2.0, Google): the runtime that executes the
    // language model on the phone's GPU or CPU. There is no AndroidX way
    // to run one.
    implementation(libs.litertlm.android)

    // zstd-jni (BSD-2-Clause): Zstandard decompression, which every Kiwix
    // archive since 2021 uses. Android has no built-in decoder. The `aar`
    // variant carries the Android native libraries.
    implementation(variantOf(libs.zstd.jni) { artifactType("aar") })

    // The unit tests run on the build machine, which needs the plain jar
    // (it carries the desktop native libraries).
    testImplementation(libs.zstd.jni)

    // Likewise the model runtime: its desktop build, so ModelLiveTest can
    // run real models through Llm.kt in CI.
    testRuntimeOnly(libs.litertlm.jvm)
}

// The Android and the desktop build of LiteRT-LM hold the same classes;
// the tests must load the desktop one (the other has no native code for
// the build machine).
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.google.ai.edge.litertlm", module = "litertlm-android")
}
