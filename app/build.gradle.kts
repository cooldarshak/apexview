import java.time.LocalDateTime
import java.util.Properties
import java.time.format.DateTimeFormatter

// Release signing lives outside the project (see keystore.properties); without it, release
// builds stay unsigned and only debug builds work.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

android {
    namespace = "app.apexview"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.apexview_custom"
        minSdk = 29
        targetSdk = 35
        // CI builds get a monotonically increasing version code so a same-signature APK installs
        // over the previous one instead of being rejected as a downgrade.
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100_000 + ciRun
        versionName = "1.0.$ciRun"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val builtAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
        val castReceiverId = System.getenv("PITWALL_CAST_RECEIVER_ID").orEmpty().trim().uppercase()
        require(castReceiverId.isEmpty() || castReceiverId.matches(Regex("[0-9A-F]{8}"))) {
            "PITWALL_CAST_RECEIVER_ID must be the eight-character app ID from the Google Cast Console"
        }
        buildConfigField("String", "CAST_RECEIVER_APP_ID", "\"$castReceiverId\"")
        buildConfigField("String", "BUILT_AT", "\"$builtAt\"")
        // Where updates come from (owner/repo on GitHub). A fork points this at its own releases.
        buildConfigField("String", "UPDATE_REPO", "\"mikeyyy0/apexview\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // CI debug APKs use the same persistent key as release builds when keystore.properties
        // is supplied. This is essential: hosted runners' default debug keys change every run.
        debug {
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // The build to share: signed with its own key and shrunk by R8 (the icon set alone is ~40 MB
        // unshrunk), so it installs like a normal app instead of a debug build.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        // The release build under its own app ID, to smoke-test shrinking on a phone without
        // replacing the installed app.
        create("smoke") {
            initWith(getByName("release"))
            applicationIdSuffix = ".smoke"
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric fetches android-all-instrumented outside Gradle's resolver. CI
            // runners can receive HTTP 429 from both Maven Central endpoints. Use Google's
            // documented mirror of Maven Central for these runtime-only test SDK jars.
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            it.systemProperty("robolectric.dependency.repo.id", "googleMavenCentralMirror")
        }
    }

    // CI generates this disposable MP4 from the complementary synthetic tile slices.
    // It is included only in the instrumentation APK and never shipped in the app.
    val hevcTileFixtureDirectory = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
    if (!hevcTileFixtureDirectory.isNullOrBlank()) {
        sourceSets.getByName("androidTest").assets.srcDir(hevcTileFixtureDirectory)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val media3 = "1.5.1"

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // The app uses the Android Cast sender UI and Default Media Receiver for the composed
    // archive HEVC stream. Scene/layout customization remains receiver-side.
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Cast transitive ranges can otherwise resolve prerelease libraries that require
    // a newer Android Gradle plugin and SDK than this project supports.
    implementation("androidx.mediarouter:mediarouter") { version { strictly("1.7.0") } }
    implementation("androidx.core:core") { version { strictly("1.15.0") } }
    implementation("androidx.core:core-ktx") { version { strictly("1.15.0") } }
    implementation("com.google.android.gms:play-services-cast-framework:22.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // WebSocket for F1's live timing feed (SignalR Core).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Fold posture (half-open like a laptop) for laptop mode.
    implementation("androidx.window:window:1.3.0")
    // Chrome sheet for the Driver of the Day vote, so the race stays on screen.
    implementation("androidx.browser:browser:1.8.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests; android.jar only has stubs.
    testImplementation("org.json:json:20240303")
    // Compose on the JVM, for what a remote's arrow keys reach.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
