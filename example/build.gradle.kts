import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Set stringalize.token (and stringalize.server, if not the local development server) in local.properties.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.stringalize.example"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.stringalize.example"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "STRINGALIZE_TOKEN", "\"${local.getProperty("stringalize.token", "")}\"")
        // 10.0.2.2 is the Mac, seen from the Android emulator.
        buildConfigField("String", "STRINGALIZE_SERVER", "\"${local.getProperty("stringalize.server", "http://10.0.2.2:3000")}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":stringalize"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
