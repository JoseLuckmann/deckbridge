import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "os.deckbridge.android"
    // As telas e a lógica dos decks são as mesmas do modo DJ no computador (stage/, ADR-0012).
    sourceSets["main"].java.srcDir("../../stage/src")
    // O que as telas do modo DJ e do MIX dividem (shared/) vem pelo módulo :biblioteca, que já o compila: incluir de
    // novo aqui duplicava as classes.
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // Um app só (ADR-0012): o id é o do antigo app da biblioteca, para quem já tem a biblioteca no aparelho não
        // perder nada ao atualizar (o armazenamento do app é por id).
        applicationId = "os.deckbridge.manager"
        minSdk = 26
        targetSdk = 34
        versionCode = 45
        versionName = "0.33.0"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    flavorDimensions += "abi"
    productFlavors {
        create("multi") {
            dimension = "abi"
            isDefault = true
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        create("arm64") {
            dimension = "abi"
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    // Assinatura de release: a chave NUNCA entra no repositório. Vem de android/keystore.properties (ignorado pelo git)
    // ou das variáveis DECKBRIDGE_KEYSTORE, DECKBRIDGE_KEYSTORE_PASSWORD, DECKBRIDGE_KEY_ALIAS, DECKBRIDGE_KEY_PASSWORD.
    // Sem nenhuma das duas, o release sai sem assinatura (app-release-unsigned.apk), nunca com a chave de debug.
    val keystoreProps = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }
    fun secret(property: String, env: String): String? = keystoreProps.getProperty(property) ?: System.getenv(env)
    val releaseStore = secret("storeFile", "DECKBRIDGE_KEYSTORE")
    val releaseSigned = releaseStore != null && file(releaseStore).isFile
    if (releaseSigned) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = secret("storePassword", "DECKBRIDGE_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "DECKBRIDGE_KEY_ALIAS")
                keyPassword = secret("keyPassword", "DECKBRIDGE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseSigned) signingConfigs.getByName("release") else null
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    beforeVariants(selector().withFlavor("abi" to "arm64")) { builder ->
        builder.enableUnitTest = false
        builder.enableAndroidTest = false
    }
}

// A lógica de controle da DDJ-400 é C++ (src/main/cpp/controller.h), a mesma do app e do Pi. Nos testes JVM ela
// roda de verdade: este passo compila o mesmo código como biblioteca do host (Linux, g++), e o ControllerCore a carrega.
val hostControlLib = layout.buildDirectory.file("host/libdeckbridge_control_host.so")
val buildHostControl by tasks.registering(Exec::class) {
    val sources = listOf(file("src/main/cpp/controller_jni.cpp"), file("src/main/cpp/controller.h"), file("src/main/cpp/leds.h"))
    inputs.files(sources)
    outputs.file(hostControlLib)
    val jdk = System.getProperty("java.home")
    val output = hostControlLib.get().asFile
    doFirst { output.parentFile.mkdirs() }
    commandLine(
        "g++", "-std=c++17", "-O2", "-fwrapv", "-shared", "-fPIC",
        "-I$jdk/include", "-I$jdk/include/linux",
        "-o", output.absolutePath, sources[0].absolutePath,
    )
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostControl)
    systemProperty("deckbridge.controlLib", hostControlLib.get().asFile.absolutePath)
    // O worker padrão (~512 MB) estoura no lowpass das faixas longas depois
    // das 248 análises de tom. O algoritmo em si cabe; o heap do teste não.
    maxHeapSize = "2g"
    systemProperty("roborazzi.test.record", "true")
    systemProperty("roborazzi.output.dir", "/opt/cursor/artifacts/prints")
    systemProperty("usb.folder", System.getProperty("usb.folder") ?: "")
    systemProperty("usb.report", System.getProperty("usb.report") ?: "")
}

dependencies {
    // O núcleo (lógica compartilhada) e o modo Biblioteca (módulo, processo ":biblioteca").
    implementation("os.deckbridge:core")
    implementation(project(":biblioteca"))
    implementation("com.google.oboe:oboe:1.11.0")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.44.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.44.0")
    testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
}
