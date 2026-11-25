import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

// Release signing comes from key.properties (local, never committed) or
// CUFF_* environment variables (CI). Without either, release builds fall
// back to the debug key and must not be distributed.
val releaseKey = Properties().apply {
    rootProject.file("key.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
// Blank counts as absent, and that distinction is the whole of a CI failure.
// A workflow that decides whether to sign cannot leave an environment
// variable out of a step -- it sets it to "" instead. That is not null, so a
// plain `?:` chain hands "" on as if it were a path, and the build dies on
// `Cannot convert '' to File` at assembleRelease, after the tests and the
// lint have passed. Locally the variable is simply unset and none of this
// shows up, which is the worst kind of difference between two machines.
fun releaseSetting(name: String, env: String): String? =
    (releaseKey.getProperty(name) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

android {
    namespace = "com.srineere.airbridge"
    compileSdk = 36

    defaultConfig {
        // Final: Play and GitHub releases share this ID; it can never change.
        // The install id, which is what makes this a different app on the
        // phone rather than an update to Air Bridge. The Kotlin package
        // underneath stays com.srineere.airbridge: moving 163 files would
        // change nothing anybody can see, and this is the only line the
        // system reads when it decides whether two APKs are the same app.
        //
        // Consequence worth knowing: a new id starts with nothing. No
        // Health Connect grants, no tiles, no goals, no food log. That is
        // the point of it here, and it is also why the old app is still
        // sitting there with all of that in it.
        applicationId = "com.srineere.cuff"
        minSdk = 28
        targetSdk = 36
        versionCode = 26
        versionName = "0.4.0"

        // The transport ships prebuilt .so files for three ABIs and is only
        // tested on arm64 -- upstream says so and so does our README. Packing
        // the other two adds about 1.2 MB of native code that would crash in
        // a different way if it ever ran, so the APK carries the one that is
        // real. Revisit the day armeabi-v7a is actually verified.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    buildFeatures {
        compose = true
        // About shows the version; reading it from here keeps one source.
        buildConfig = true
    }

    sourceSets {
        getByName("main") { java.srcDirs("src/main/kotlin") }
        getByName("test") { java.srcDirs("src/test/kotlin") }
    }

    signingConfigs {
        create("release") {
            releaseSetting("storeFile", "CUFF_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storePassword = releaseSetting("storePassword", "CUFF_KEYSTORE_PASSWORD")
                keyAlias = releaseSetting("keyAlias", "CUFF_KEY_ALIAS")
                keyPassword = releaseSetting("keyPassword", "CUFF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // The Flutter build still owns com.srineere.airbridge on the phone, so
        // debug installs beside it rather than over it.
        debug {
            applicationIdSuffix = ".dev"
            // The label says Cuff, not Cuff dev. The .dev suffix below is
            // the install id and stays: it is what lets a release build sit
            // beside this one, and changing it would uninstall this app and
            // everything set up in it for the sake of a word nobody sees.
            resValue("string", "app_name", "Cuff")
        }
        // The second band's workbench, installed beside a working Cuff
        // rather than over it.
        //
        // This branch adds a network permission and a cloud sign-in to an
        // app whose whole job is to be trusted with every notification the
        // phone posts. Trying that on the install somebody actually relies
        // on is how you lose an evening and a day of alert history, so it
        // gets an id of its own: com.srineere.cuff.helio is a third app to
        // Android, with its own storage, its own permission grants and its
        // own Health Connect consent. Deleting it takes nothing with it.
        //
        // Everything else is debug's: unminified, debuggable, debug key.
        create("helio") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".helio"
            // Here the label does change, because the point is to tell two
            // icons apart in the launcher at a glance.
            resValue("string", "app_name", "Cuff Helio")
            // :air and :helio publish debug and release and nothing else,
            // so this build type has to say which of theirs it wants.
            matchingFallbacks += listOf("debug")
            // Minified, unlike debug, because this is the build that gets
            // sent to somebody: unminified Compose is 117 MB of dex, which
            // is a thing you cannot put in a chat window. R8 runs with the
            // app's own rules, so this is also the only variant that proves
            // they still cover the second band's code.
            // Not debuggable, which is what lets R8 actually optimise:
            // a debuggable build keeps every optimisation off and still
            // came out at 73 MB. Nothing is lost that matters remotely --
            // the About screen's own crash log is what a tester sends
            // back, and it survives minification.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            resValue("string", "app_name", "Cuff")
            // Debug stays unminified, so what is installed on the phone during
            // development is never what R8 produced. See proguard-rules.pro
            // for the one part of this app it cannot reason about.
            // Not debuggable, which is what lets R8 actually optimise:
            // a debuggable build keeps every optimisation off and still
            // came out at 73 MB. Nothing is lost that matters remotely --
            // the About screen's own crash log is what a tester sends
            // back, and it survives minification.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":air"))
    implementation(project(":helio"))
    // Writes the recorded profile into the APK. The library that
    // installs it at runtime is already here through Compose.
    baselineProfile(project(":baselineprofile"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // Health Connect. The 1.2 alphas want AGP 9 and compileSdk 37, which is a
    // toolchain jump this app has no other reason to make; 1.1.0 is stable,
    // needs only compileSdk 36, and already carries the background-read
    // permission and the feature-availability check.
    implementation("androidx.health.connect:connect-client:1.1.0")
    // Reading while the app is not on screen, for the goal poll and widgets.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Home screen widgets. Compose for the launcher, so the tiles are written
    // the same way as the tab rather than in RemoteViews.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
