plugins {
    id("field.app")
}

android {
    namespace = "dev.field.dial"

    defaultConfig {
        applicationId = "dev.field.dial"
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    // AndroidX Media3 (Apache-2.0). Android's built-in MediaPlayer never
    // finishes preparing Sveriges Radio's AAC streams and rejects FLAC;
    // ExoPlayer plays them, and reads the programme title from the stream.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
}
