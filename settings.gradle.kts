pluginManagement {
    // Shared build setup for the Field apps (the `field.app` plugin).
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Relay"
include(":app")          // Relay
include(":field")        // shared Field Design Language UI
include(":cal", ":dial", ":gate", ":list", ":lore", ":page", ":reel", ":tally")
