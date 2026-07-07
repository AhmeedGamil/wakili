pluginManagement {
    includeBuild("build-logic")
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

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "wakili-android"

include(":app")
include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:ui")
include(":core:network")
include(":core:data")
include(":core:datastore")
include(":feature:connect")
include(":feature:sessions")
include(":feature:chat")
include(":feature:files")
include(":feature:terminal")
include(":feature:settings")
