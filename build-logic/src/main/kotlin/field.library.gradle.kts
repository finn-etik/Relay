// Convention plugin `field.library`: an Android library with Compose, on
// the same SDK levels and Java target as the apps. Used by :field.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk = catalog.findVersion("compile-sdk").get().requiredVersion.toInt()

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
