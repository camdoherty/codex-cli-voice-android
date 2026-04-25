plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.codex_cli_voice_android.aecshim"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.codex_cli_voice_android.aecshim"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
}
