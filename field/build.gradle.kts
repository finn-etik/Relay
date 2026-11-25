plugins {
    id("field.library")
}

// Shared Field Design Language components for the CAL, DIAL, GATE, PAGE,
// REEL and TALLY apps. Each app supplies its own signal accent via FieldTheme.
android {
    namespace = "dev.field.ui"
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.foundation)
    api(libs.compose.ui)
    api(libs.activity.compose)
    api(libs.core.ktx)
    api(libs.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    // Android ships org.json, the build machine's JVM does not: the Drime
    // client's test needs the real thing.
    testImplementation(libs.json)
}
