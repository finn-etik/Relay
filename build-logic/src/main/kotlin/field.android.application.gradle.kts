// Convention plugin `field.android.application`: the Android, Kotlin and
// Compose setup common to every app in this repository (Relay and the Field
// apps). Field apps apply `field.app`, which builds on this one.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Precompiled plugins cannot use the generated `libs` accessors; look the
// catalog (gradle/libs.versions.toml) up by name instead.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk = catalog.findVersion("compile-sdk").get().requiredVersion.toInt()

    defaultConfig {
        targetSdk = catalog.findVersion("target-sdk").get().requiredVersion.toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    "testImplementation"(catalog.findLibrary("junit").get())
}
