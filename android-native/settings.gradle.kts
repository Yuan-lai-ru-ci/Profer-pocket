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

rootProject.name = "ProferAndroid"

include(":app")
include(":core:model")
include(":core:data")
include(":core:designsystem")
include(":feature:chat")
include(":feature:sessions")
include(":feature:connect")
include(":feature:settings")
