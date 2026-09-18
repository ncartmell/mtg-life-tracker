rootProject.name = "mtg-life-tracker"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":engine")

// The Compose application targets Android and iOS as well as desktop, so it is only
// included when an Android SDK is available. The :engine module — which holds all of
// the game rules — always builds, and its tests run anywhere with a JDK.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    File(rootDir, "local.properties").takeIf { it.exists() }
        ?.readLines().orEmpty().any { it.startsWith("sdk.dir=") }

if (hasAndroidSdk) {
    include(":composeApp")
} else {
    logger.lifecycle("No Android SDK found — skipping :composeApp. Run './gradlew :engine:test' for the rules tests.")
}
