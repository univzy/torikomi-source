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
    }
}

rootProject.name = "torikomi-extensions"

// Include available extension modules
val extensionIds = listOf(
    "musicaldown",
    "snapsave_twitter",
    "snapsave_instagram",
    "snapsave_facebook",
    "ytdown"
)

extensionIds.forEach { id ->
    include(":extensions:$id")
    project(":extensions:$id").projectDir = file("extensions/$id/android/app")
}
