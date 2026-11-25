plugins {
    id("org.jetbrains.kotlin.jvm")
}

// The Amazfit Helio Strap transport: the Zepp channel over BLE, with its own
// authentication and framing.
//
// Everything under org.openstrap.protocol is vendored from sayed0am/unstrap
// and keeps its upstream package, exactly as :air keeps Golden Gate's. The
// reason is the same in both cases: a vendored copy that has been renamed
// cannot be diffed against the project it came from, and this one will have
// to be, every time that project learns something new about the strap.
// THIRD_PARTY_NOTICES.md names the commit it was taken at.
//
// This is a plain Kotlin module rather than an Android library because the
// protocol is plain Kotlin: it reads and writes byte arrays and knows nothing
// about Bluetooth. Whatever carries those bytes to the strap belongs in the
// Android layer above, which is also where the one-session discipline the
// rest of Cuff depends on will live.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Test-only, and never shipped: B163Test cross-checks the hand-written
    // sect163k1 arithmetic against a reference implementation. A curve
    // nobody checks is a curve nobody should trust.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
