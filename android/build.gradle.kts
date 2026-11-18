plugins {
    id("com.android.library") version "9.2.0"
}

// Mirrors the module the PAM CLI generates from pam-native.plugin.json, plus the
// plugin's unit and instrumented tests.
android {
    namespace = "dev.pam.notifications"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            kotlin.directories.add("src/main/kotlin")
        }
        getByName("test") {
            kotlin.directories.add("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.directories.add("src/androidTest/kotlin")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

dependencies {
    api(project(":plugin-api"))
    implementation("androidx.core:core-ktx:1.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

// Keep framework sources read-only: plugin-api outputs live in this harness.
subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("subprojects/$name"))
}
