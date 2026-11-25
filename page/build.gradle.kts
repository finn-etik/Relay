plugins {
    id("field.app")
}

android {
    namespace = "dev.field.page"

    defaultConfig {
        applicationId = "dev.field.page"
        versionCode = 2
        versionName = "2.0"
    }
}

dependencies {
    // Android ships org.json, the build machine's JVM does not: the Drime
    // client's test needs the real thing.
    testImplementation(libs.json)
}
