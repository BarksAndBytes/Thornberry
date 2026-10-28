plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Tor binaries come from separate Maven artifacts as plain JARs with <abi>/lib*.so entries.
// We unpack them into a generated jniLibs folder so Android installs them next to the app.
val torNative: Configuration by configurations.creating
torNative.isTransitive = false

dependencies {
    implementation(project(":core"))
    // GPL-3.0 libraries (Briar project). Using them makes this app GPL-3.0-compatible (decision D7).
    implementation("org.briarproject:onionwrapper-android:0.1.7")
    implementation("org.briarproject:dont-kill-me-lib:0.2.8")
    // QR code drawing and scanning (Apache-2.0).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

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
    namespace = "app.thornberry.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.thornberry.chat"
        minSdk = 30
        targetSdk = 35
        versionCode = 15
        versionName = "0.7.0-test"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    sourceSets["main"].jniLibs.srcDir(torJniDir)

    // TEST BUILDS ONLY: the same fixed PUBLIC key as the spike, so updates install over each other.
    // It has no security value. Real releases will use a private key that is never committed.
    signingConfigs {
        create("testKey") {
            storeFile = file("../app/spike-test.keystore")
            storePassword = "android"
            keyAlias = "spiketest"
            keyPassword = "android"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
        resources {
            // Bouncy Castle ships signature and multi-release metadata that Android does not need.
            excludes += setOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("testKey") }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }

    kotlinOptions { jvmTarget = "17" }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(extractTorLibs) }
