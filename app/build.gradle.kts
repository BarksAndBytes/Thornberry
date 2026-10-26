plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Tor binaries come from separate Maven artifacts as plain JARs with <abi>/lib*.so entries.
// We unpack them into a generated jniLibs folder so Android installs them next to the app,
// which is the only place Android 10+ lets an app execute a binary from.
val torNative: Configuration by configurations.creating
torNative.isTransitive = false

dependencies {
    // GPL-3.0 libraries (Briar project). Using them makes this app GPL-3.0-compatible (decision D7).
    implementation("org.briarproject:onionwrapper-android:0.1.7")
    implementation("org.briarproject:dont-kill-me-lib:0.2.8")

    // Tor 0.4.9.12 is the version the Onion Wrapper maintainers test against.
    torNative("org.briarproject:tor-android:0.4.9.12")
    torNative("org.briarproject:lyrebird-android:0.6.2")
}

val torJniDir = layout.buildDirectory.dir("generated/torJniLibs")

val extractTorLibs by tasks.registering(Copy::class) {
    from({ torNative.files.map { zipTree(it) } }) {
        include("*/*.so")
    }
    into(torJniDir)
}

android {
    namespace = "app.thornberry.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.thornberry.spike"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.0.4-spike"
        // The Poco X7 Pro is arm64. Keeps the test APK small.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    sourceSets["main"].jniLibs.srcDir(torJniDir)

    // A fixed, PUBLIC test key so updates install over each other and keep app data.
    // It has no security value. Real releases will use a private key that is never committed.
    signingConfigs {
        create("spikeTest") {
            storeFile = file("spike-test.keystore")
            storePassword = "android"
            keyAlias = "spiketest"
            keyPassword = "android"
        }
    }

    packaging {
        jniLibs {
            // Required so libtor.so is extracted at install time and can be executed.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("spikeTest")
        }
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

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(extractTorLibs) }
