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

// O núcleo (leitor/gravador de export.pdb, ANLZ, exportação) é o mesmo do programa de computador.
includeBuild("../core")

rootProject.name = "DeckBridgeBiblioteca"
include(":app")
