package os.deckbridge.android

import androidx.compose.ui.platform.ComposeView
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import os.deckbridge.logic.BeatFxState
import os.deckbridge.logic.BrowsePlace
import os.deckbridge.logic.DeckFace
import os.deckbridge.logic.Heard
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.SyntheticRekordbox
import os.deckbridge.logic.WaveZoom
import os.deckbridge.logic.faderFromPitch
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
        capture("$PRINTS/diagnostico.png") { activity ->
            activity.setContentView(ComposeView(activity).apply {
                setContent {
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
            })
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-mdpi")
class ScreenShotsSevenInch {
    @Test
    fun player() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/player-1024x600.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState()))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w2000dp-h1200dp-mdpi")
class ScreenShotsTablet {
    @Test
    fun player() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/player-2000x1200.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState()))
        }
    }

    @Test
    fun browseCategories() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/browse-categories.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_8, BeatFxState(), BrowsePlace()))
        }
    }

    @Test
    fun browseTracks() {
        val catalog = simulatedCatalog()
        val playlist = catalog.playlists.first { it.name == "Abertura" }.id
        val place = BrowsePlace(inList = true, playlistId = playlist, index = 0)
        capture("$PRINTS/browse-tracks.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_8, BeatFxState(), place))
        }
    }

    @Test
    fun zoomIn() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/zoom-in.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_2, BeatFxState()))
        }
    }

    @Test
    fun zoomOut() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/zoom-out.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.TRACK, BeatFxState()))
        }
    }

    @Test
    fun beatFx() {
        val catalog = simulatedCatalog()
        val fx = BeatFxState(effect = 0, fraction = 5, channel = 0, on = true, level = 96)
        capture("$PRINTS/beatfx.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, fx))
        }
    }
}

private fun capture(path: String, fill: (ScreenshotActivity) -> Unit) {
    val activity = Robolectric.buildActivity(ScreenshotActivity::class.java).setup().get()
    fill(activity)
    ShadowLooper.idleMainLooper()
    activity.window.decorView.captureRoboImage(path)
}

private fun player(
    activity: ScreenshotActivity,
    catalog: RbCatalog,
    zoom: WaveZoom,
    fx: BeatFxState,
    browse: BrowsePlace? = null,
): ComposeView {
    val tracks = catalog.tracks
    val faces = listOf(
        DeckFace(playing = true, position = 1.5f, pitch = 1f, fader = 0.5f, filter = 96),
        DeckFace(
            playing = true,
            position = 0.6f,
            pitch = 1.06f,
            range = 0.16,
            fader = faderFromPitch(1.06, 0.16).toFloat(),
            remain = true,
            sync = true,
        ),
    )
    return ComposeView(activity).apply {
        setContent {
            PlayerScreen(
                catalog = catalog,
                tracks = listOf(tracks[0], tracks[1]),
                faces = faces,
                master = 0,
                quantize = true,
                zoom = listOf(zoom, zoom),
                zoomLink = true,
                fx = fx,
                jvmBanner = BANNER,
                browse = browse,
                usbLabel = "USB simulado",
                onPage = {},
                onBrowse = {},
                onBrowseClose = {},
                onZoom = { _, _ -> },
                onLink = {},
                onPlay = {},
                onCue = {},
                onHot = { _, _ -> },
                onSync = {},
                onMaster = {},
                onKey = {},
                onRemain = {},
                onRange = {},
                onFader = { _, _ -> },
                onScratch = { _, _ -> },
                onJump = { _, _ -> },
                onQuantize = {},
                onAuthorize = {},
                onSimulate = {},
                onBrowseMove = {},
                onBrowseEnter = {},
                onBrowseBack = {},
                onQuery = {},
                onLoad = { _, _ -> },
            )
        }
    }
}
