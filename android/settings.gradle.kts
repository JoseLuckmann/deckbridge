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

// O núcleo (lógica compartilhada, biblioteca, sets) e o modo Biblioteca, que é um módulo do mesmo app (ADR-0012).
includeBuild("../core")

rootProject.name = "DeckBridge"
include(":app")
include(":biblioteca")
project(":biblioteca").projectDir = file("../mobile/app")
