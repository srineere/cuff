plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The Amazfit Helio Strap: the Zepp channel over BLE, and the one network
// call that makes it possible at all.
//
// Everything under org.openstrap is vendored from sayed0am/unstrap and keeps
// its upstream package, exactly as :air keeps Golden Gate's. A vendored copy
// that has been renamed cannot be diffed against the project it came from,
// and this one will have to be. THIRD_PARTY_NOTICES.md names the commit.
//
// This module, alone in the repository, holds the INTERNET permission --
// declared in its own manifest rather than the app's, so that the permission
// arrives with the code that needs it and leaves with it. The strap's auth
// key is issued by Zepp's cloud and there is no offline way to obtain one,
// which is the whole reason the permission exists. See PRIVACY.md.
//
// An Android library rather than a plain Kotlin one, which it was until the
// cloud client arrived: ZeppCloud reads android.os.Build. The vendored
// protocol underneath is still platform-free and its tests still run on the
// JVM, now as :helio:testDebugUnitTest.
android {
    namespace = "com.srineere.cuff.helio"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    sourceSets {
        getByName("main") { java.srcDirs("src/main/kotlin") }
        getByName("test") { java.srcDirs("src/test/kotlin") }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")
    // Test-only, and never shipped: B163Test cross-checks the hand-written
    // sect163k1 arithmetic against a reference implementation. A curve
    // nobody checks is a curve nobody should trust.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
