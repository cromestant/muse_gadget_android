plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cromestant.musegadget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cromestant.musegadget"
        minSdk = 29
        // Portal Mini runs API 29; targetSdk 29 governs runtime behavior.
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // No Play Services, no Firebase, no gms — ever. Main has zero deps.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
