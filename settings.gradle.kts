pluginManagement {
    repositories {
        google()
        // Use the Maven Central mirror for Kotlin Compose plugin markers and
        // compiler transitive dependencies when the public Maven index is rate-limited.
        // These are the same published coordinates; never skip compiler validation.
        maven {
            name = "GoogleMavenCentralPluginMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // GitHub-hosted runners repeatedly receive HTTP 429 from Maven Central,
        // most notably for Robolectric nativeruntime-dist-compat. Use Google's
        // public Maven Central mirror first, with the upstream repository as a
        // fallback. This is the same artifact coordinate and content, not a
        // dependency downgrade or test exclusion.
        maven {
            name = "GoogleMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
    }
}

rootProject.name = "F1Multiview"
include(":app")
include(":macrobenchmark")
