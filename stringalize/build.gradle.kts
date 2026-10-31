plugins {
    id("com.android.library")
    `maven-publish`
}

group = "com.github.cbarbera80"
version = "0.1.0"

android {
    namespace = "app.stringalize"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "VERSION", "\"$version\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.cbarbera80"
            artifactId = "stringalize-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
}
