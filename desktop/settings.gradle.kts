pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "deckbridge-manager"

// O núcleo (banco, ANLZ, gravação segura, importação, organizar) é compartilhado com o app Android.
includeBuild("../core")
