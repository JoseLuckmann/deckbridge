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
    sourceSets.main {
        // Os leitores e a análise do app (puro JVM, sem import de Android) são os mesmos nos dois programas.
        kotlin.srcDir("../android/app/src/main/java/os/deckbridge/logic")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    testImplementation(kotlin("test"))
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
