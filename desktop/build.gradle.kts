import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "os.deckbridge"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    // As telas de montar set são as mesmas do app de celular (studio/).
    sourceSets["main"].kotlin.srcDir("../studio/src")
}

dependencies {
    implementation("os.deckbridge:core")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

compose.desktop {
    application {
        mainClass = "os.deckbridge.manager.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "DeckBridge Library Manager"
            packageVersion = "1.0.0"
        }
    }
}

tasks.test {
    useJUnit()
    // Nomes de arquivo com acento exigem um locale UTF-8 no processo de teste.
    environment("LC_ALL", "C.UTF-8")
    maxHeapSize = "1g"
}
