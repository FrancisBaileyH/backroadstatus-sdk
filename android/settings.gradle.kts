pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://api.mapbox.com/downloads/v2/releases/maven") }
    }
}

rootProject.name = "BackroadStatus"

include(":core")
include(":mapkit")
include(":composer-ui")
include(":maplibre-ui")
include(":mapbox-ui")
