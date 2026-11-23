// Convention plugin `field.library`: an Android library with Compose, on
// the same SDK levels and Java target as the apps. Used by :field.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Kotlin support is built into the Android Gradle plugin (since 9.0); only
// the Compose compiler is a separate plugin.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk {
        version = release(catalog.findVersion("compile-sdk").get().requiredVersion.toInt()) {
            minorApiLevel = catalog.findVersion("compile-sdk-minor").get().requiredVersion.toInt()
        }
    }

    defaultConfig {
        minSdk = catalog.findVersion("min-sdk").get().requiredVersion.toInt()
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
