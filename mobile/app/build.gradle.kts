plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    // As telas de montar set são as mesmas do app de computador (studio/).
    sourceSets["main"].java.srcDir("../../studio/src")
    namespace = "os.deckbridge.manager.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "os.deckbridge.manager"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "0.20.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed") }
    // Uma JVM por classe de teste: a classe que abre a atividade de verdade (PreviewLifecycleTest) deixava estado do
    // Compose/Robolectric que fazia as capturas seguintes esperarem para sempre por "ocioso". Isoladas, todas passam.
    forkEvery = 1
    maxHeapSize = "4g"
    environment("LC_ALL", "C.UTF-8")
    systemProperty("roborazzi.test.record", "true")
    // Vídeo de teste de mixagem com músicas locais (fora do repositório): -PmixVideo=<pasta com in/ e out/>.
    systemProperty("mix.video", (findProperty("mixVideo") as String?) ?: "")
    systemProperty("journey.video", (findProperty("journeyVideo") as String?) ?: "")
    systemProperty("roborazzi.output.dir", System.getProperty("prints.dir") ?: layout.buildDirectory.dir("prints").get().asFile.absolutePath)
}

dependencies {
    implementation("os.deckbridge:core")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.44.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.44.0")
}
