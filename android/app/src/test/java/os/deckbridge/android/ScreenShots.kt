package os.deckbridge.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.ComposeView
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import os.deckbridge.logic.Heard
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.SyntheticRekordbox
import os.deckbridge.logic.loadRekordbox

private const val BANNER = "Renderização em JVM. Não é o tablet nem a DDJ-400."
private const val PRINTS = "/opt/cursor/artifacts/prints"

private fun simulatedCatalog(): RbCatalog = loadRekordbox(SyntheticRekordbox.volume())

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w1600dp-h900dp-mdpi")
class ScreenShotsWide {
    @Test
    fun diagnostico() {
        capture("$PRINTS/01-diagnostico.png") { activity ->
            activity.setContentView(ComposeView(activity).apply {
                setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        DiagnosticScreen(
                            headline = "Prova A0. O som desta tela ainda não é prova até você mandar o relatório.",
                            identityLine = "JVM Renderização · Android JVM · SoC UNKNOWN",
                            ramLine = "RAM 4096 MB · núcleos 8",
                            engineLine = "Motor parado. Latência UNKNOWN. X-runs UNKNOWN.",
                            events = listOf("SIMULADO status=0x90 nota=0x0B valor=127 | play | PLAY/PAUSE (DECK1)"),
                            heard = Heard.NAO_TESTEI,
                            report = "Relatório de diagnóstico. Renderização em JVM. Não é medição do tablet.",
                            jvmBanner = BANNER,
                            onRefresh = {},
                            onMaster = {},
                            onPhones = {},
                            onStop = {},
                            onSimulate = {},
                            onHeard = {},
                            onShare = {},
                        )
                    }
                }
            })
        }
    }

    @Test
    fun bibliotecaPlaylists() {
        val catalog = simulatedCatalog()
        val folder = catalog.playlists.first { it.name == "Sets" }.id
        capture("$PRINTS/02-biblioteca-playlists.png") { activity ->
            activity.setContentView(library(activity, catalog, folder, 1600))
        }
    }

    @Test
    fun bibliotecaFaixas() {
        val catalog = simulatedCatalog()
        val playlist = catalog.playlists.first { it.name == "Abertura" }.id
        capture("$PRINTS/03-biblioteca-faixas.png") { activity ->
            activity.setContentView(library(activity, catalog, playlist, 1600))
        }
    }

    @Test
    fun decks() {
        capture("$PRINTS/04-decks.png") { activity ->
            activity.setContentView(decks(activity, simulatedCatalog()))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-mdpi")
class ScreenShotsSevenInch {
    @Test
    fun decks() {
        capture("$PRINTS/05-decks-1024x600.png") { activity ->
            activity.setContentView(decks(activity, simulatedCatalog()))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w2000dp-h1200dp-mdpi")
class ScreenShotsTablet {
    @Test
    fun decks() {
        capture("$PRINTS/06-decks-2000x1200.png") { activity ->
            activity.setContentView(decks(activity, simulatedCatalog()))
        }
    }
}

private fun capture(path: String, fill: (ScreenshotActivity) -> Unit) {
    val activity = Robolectric.buildActivity(ScreenshotActivity::class.java).setup().get()
    fill(activity)
    ShadowLooper.idleMainLooper()
    activity.window.decorView.captureRoboImage(path)
}

private fun library(activity: ScreenshotActivity, catalog: RbCatalog, selected: Int, widthPx: Int): ComposeView {
    return ComposeView(activity).apply {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LibraryPage(
                    catalog = catalog,
                    loading = false,
                    notice = "Pendrive simulado aberto. SIMULAÇÃO.",
                    usbLabel = "pendrive simulado",
                    selectedId = selected,
                    onSelect = {},
                    onAuthorize = {},
                    onSimulate = {},
                    onReloadPersisted = {},
                    onLoadDeck = { _, _ -> },
                    widthPx = widthPx,
                    jvmBanner = BANNER,
                )
            }
        }
    }
}

private fun decks(activity: ScreenshotActivity, catalog: RbCatalog): ComposeView {
    val tracks = catalog.tracks
    return ComposeView(activity).apply {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DeckScreen(
                    catalog = catalog,
                    loaded = listOf(tracks[0], tracks[1]),
                    note = "Pendrive simulado. Faixas carregadas. SIMULAÇÃO.",
                    channelsText = "Canais abertos 0 de 4. Renderização em JVM, sem áudio.",
                    playA = true,
                    playB = true,
                    pitchA = 1f,
                    pitchB = 1.2f,
                    volA = 0.8f,
                    volB = 0.8f,
                    cross = 0.5f,
                    pflA = false,
                    pflB = true,
                    posA = 1.5f,
                    posB = 0.6f,
                    waveforms = true,
                    loose = "",
                    jvmBanner = BANNER,
                    onPlay = { _, _ -> },
                    onCue = {},
                    onPitch = { _, _ -> },
                    onVolume = { _, _ -> },
                    onPfl = { _, _ -> },
                    onCross = {},
                    onSync = {},
                    onHot = { _, _ -> },
                    onLoose = { _, _ -> },
                )
            }
        }
    }
}
