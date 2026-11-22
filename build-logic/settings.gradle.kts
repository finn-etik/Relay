// build-logic: the build setup the Field apps share, as Gradle convention
// plugins (see src/main/kotlin). Included by ../settings.gradle.kts.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
