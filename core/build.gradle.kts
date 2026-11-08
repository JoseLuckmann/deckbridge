import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
}

group = "os.deckbridge"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // O estado observável do modelo usa o runtime do Compose. Ele vem de quem usa o núcleo (o runtime do JetBrains no
    // desktop, o androidx no Android; a API é a mesma), então aqui é só para compilar.
    compileOnly("org.jetbrains.compose.runtime:runtime:1.7.3")
    testImplementation("org.jetbrains.compose.runtime:runtime:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
    maxHeapSize = "1g"
    // Nomes de arquivo com acento exigem um locale UTF-8 no processo de teste.
    environment("LC_ALL", "C.UTF-8")
}
