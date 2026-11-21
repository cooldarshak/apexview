pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Fallback for intermittent Maven Central failures on CI runners.
        // This Google-hosted mirror is already used by our Robolectric tests.
        // Restrict it to Kotlin coordinates; do not change the app's dependency versions.
        maven {
            name = "GoogleMavenCentralKotlinMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
            content {
                includeGroup("org.jetbrains.kotlin")
            }
        }
    }
}

rootProject.name = "F1Multiview"
include(":app")
