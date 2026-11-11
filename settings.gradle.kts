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
        // Firefox's browser engine (GeckoView) for Ghost Race in the launcher
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}
rootProject.name = "ADAS-Atto3"
include(":probe")
include(":launcher")
