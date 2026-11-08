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
    // As telas de montar set são as mesmas do app de celular (studio/); as de DJ também (stage/, ADR-0012).
    sourceSets["main"].kotlin.srcDir("../studio/src")
    sourceSets["main"].kotlin.srcDir("../stage/src")
    sourceSets["main"].kotlin.srcDir("../shared/src")
}

// O mapa MIDI da DDJ-400 (pinado do Mixxx) é o mesmo do app Android.
sourceSets["main"].resources.srcDir("../android/app/src/main/resources")

// O motor de áudio do modo DJ é o C++ do app (engine.cpp + controller_jni.cpp), com o Oboe trocado pelo ALSA do Pi
// (pi/alsa/oboe/Oboe.h): o mesmo código, compilado como biblioteca JNI do computador (Linux; g++ e libasound2-dev).
val engineLib = layout.buildDirectory.file("native/libdeckbridge-engine.so")
val buildEngine by tasks.registering(Exec::class) {
    val cpp = file("../android/app/src/main/cpp")
    inputs.dir(cpp)
    inputs.dir(file("../pi/alsa"))
    outputs.file(engineLib)
    val jdk = System.getProperty("java.home")
    val output = engineLib.get().asFile
    doFirst { output.parentFile.mkdirs() }
    commandLine(
        "g++", "-std=c++17", "-O2", "-fwrapv", "-pthread", "-shared", "-fPIC",
        "-I", file("../pi/alsa").absolutePath, "-I", cpp.absolutePath,
        "-I$jdk/include", "-I$jdk/include/linux",
        "-o", output.absolutePath, File(cpp, "engine.cpp").absolutePath, File(cpp, "controller_jni.cpp").absolutePath, "-lasound",
    )
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
        jvmArgs += "-Djava.library.path=${engineLib.get().asFile.parentFile.absolutePath}"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "DeckBridge Library Manager"
            packageVersion = "1.0.0"
        }
    }
}

tasks.withType<JavaExec>().configureEach { dependsOn(buildEngine) }

tasks.test {
    useJUnit()
    dependsOn(buildEngine)
    systemProperty("java.library.path", engineLib.get().asFile.parentFile.absolutePath)
    // Sem placa de som na máquina de teste: o PCM "null" do ALSA anda no relógio (o motor roda de verdade, sem saída).
    environment("DECKBRIDGE_ALSA_DEVICE", "null")
    // Nomes de arquivo com acento exigem um locale UTF-8 no processo de teste.
    environment("LC_ALL", "C.UTF-8")
    maxHeapSize = "1g"
}
