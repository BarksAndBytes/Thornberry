plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    // Pinned on purpose. Track Bouncy Castle security advisories and update deliberately.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
    testLogging { events("failed", "passed"); showStandardStreams = false }
}
