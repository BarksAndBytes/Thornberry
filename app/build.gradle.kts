plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.thornberry.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.thornberry.spike"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-spike"
    }

    buildTypes {
        release {
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
