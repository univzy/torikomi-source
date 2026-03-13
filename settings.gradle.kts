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

rootProject.name = "torikomi-extensionss"

include(":extension-framework")
project(":extension-framework").projectDir = file("extension-framework")

// Include all 12 extensions
val extensionIds = listOf(
    "tiktok", "youtube", "instagram", "facebook", "twitter", "threads",
    "pinterest", "spotify", "soundcloud", "douyin", "bilibili", "whatsapp_status"
)

extensionIds.forEach { id ->
    include(":extensions:$id")
    project(":extensions:$id").projectDir = file("extensions/$id")
}
