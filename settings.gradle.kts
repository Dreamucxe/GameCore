pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GameCore"

// One module on purpose.
//
// The layering in the design (core / data / domain / ui) is enforced by package
// structure and by the interfaces between the layers, not by Gradle module
// boundaries. Splitting it would buy compile-time enforcement of those boundaries
// at the cost of several extra Kotlin compilations per build, and this project is
// built on an on-device ARM host with roughly 1.5 GB of usable heap and no Gradle
// daemon. One module builds; five modules run out of metaspace.
include(":app")

// The one exception, and a cheap one. gamecore-plugin-sdk is a pure Kotlin/JVM module with no
// Android, KSP, Hilt or Compose plugins — it is a build-time tool for authoring .gcplugin
// packages and the app does not depend on it. It adds a single lightweight Kotlin compilation
// that only runs when the SDK or its tests are built, not on every :app build, so it does not
// reintroduce the metaspace pressure the one-module decision above is avoiding.
include(":gamecore-plugin-sdk")
