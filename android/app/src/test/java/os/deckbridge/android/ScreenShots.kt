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
import os.deckbridge.logic.HistoryRow
import os.deckbridge.logic.historyRow
import os.deckbridge.logic.onAirSample
import os.deckbridge.logic.usbVariantReport
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.SyntheticRekordbox
import os.deckbridge.logic.PlayerSettings
import os.deckbridge.logic.RmxState
import os.deckbridge.logic.WaveZoom
import os.deckbridge.logic.faderFromPitch
import os.deckbridge.logic.loadRekordbox
import os.deckbridge.logic.readArtworkFile
import os.deckbridge.logic.RbTrack

private const val BANNER = "Renderização em JVM. Não é o tablet nem a DDJ-400."
private const val PRINTS = "/opt/cursor/artifacts/prints"

private fun simulatedCatalog(): RbCatalog = loadRekordbox(SyntheticRekordbox.volume())

private fun previewArt(track: RbTrack?): ByteArray? {
    if (track == null || track.artworkPath.isBlank()) return null
    return readArtworkFile(SyntheticRekordbox.volume(), track.artworkPath)
}

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
                        usbReport = usbVariantReport(),
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
            activity.setContentView(
                player(activity, catalog, WaveZoom.TRACK, BeatFxState(), leftTitle = "Quarto Escuro", posA = 100f),
            )
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
            activity.setContentView(
                player(activity, catalog, WaveZoom.TRACK, BeatFxState(), leftTitle = "Quarto Escuro", posA = 100f),
            )
        }
    }

    @Test
    fun browseList() {
        val catalog = simulatedCatalog()
        val playlist = catalog.playlists.first { it.name == "Todas" }.id
        val place = BrowsePlace(inList = true, playlistId = playlist, index = 4)
        capture("$PRINTS/browse-list.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_8, BeatFxState(), place))
        }
    }

    @Test
    fun beatFxZoom() {
        val catalog = simulatedCatalog()
        val fx = BeatFxState(effect = 1, fraction = 5, channel = 0, on = true, level = 96)
        capture("$PRINTS/beatfx-zoom.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_2, fx, posA = 14.1f))
        }
    }

    @Test
    fun menu() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/menu.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), overlay = "menu", nav = "MENU"))
        }
    }

    @Test
    fun search() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/search.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), overlay = "search", nav = "SEARCH", query = "Night"))
        }
    }

    @Test
    fun tagList() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/tag-list.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), overlay = "tags", nav = "TAG LIST"))
        }
    }

    @Test
    fun beatJumpLoop() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/beat-jump-loop.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), loop = 8f to 10f, jump = 4.0))
        }
    }

    @Test
    fun beatFxList() {
        val catalog = simulatedCatalog()
        val fx = BeatFxState(effect = 0, on = true, level = 90)
        capture("$PRINTS/beatfx-list.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, fx, fxList = true))
        }
    }

    @Test
    fun rmxPanel() {
        val catalog = simulatedCatalog()
        val rmx = RmxState(panel = true, scene = 0, intensity = 0.42f, bars = 4, xBeat = 2)
        capture("$PRINTS/rmx-panel.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), rmx = rmx))
        }
    }

    @Test
    fun history() {
        val catalog = simulatedCatalog()
        val night = catalog.tracks.first { it.title == "Night Bus" }
        val glass = catalog.tracks.first { it.title == "Glasshouse" }
        val rows = listOf(
            historyRow(onAirSample(night, 1.0), 1_700_000_000_000, 0, 42),
            historyRow(onAirSample(glass, 1.04), 1_700_000_120_000, 1, 18),
        )
        capture("$PRINTS/history.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), overlay = "history", nav = "MENU", history = rows))
        }
    }

    @Test
    fun recActive() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/rec-active.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), recording = true, recClock = "01:14", recLevel = 0.62f))
        }
    }

    @Test
    fun rmxFull() {
        val catalog = simulatedCatalog()
        val rmx = RmxState(
            panel = true,
            full = true,
            scene = 0,
            holding = true,
            intensity = 0.66f,
            bars = 4,
            xOn = true,
            xMode = 0,
            xBeat = 2,
            low = listOf(0f, 1f),
            mid = listOf(1.4f, 0.5f),
            high = listOf(1f, 1.6f),
        )
        capture("$PRINTS/rmx-full.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), rmx = rmx))
        }
    }

    @Test
    fun keyShift() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/key-shift.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), tune = { it.copy(keyShift = 2, keyLock = true) }))
        }
    }

    @Test
    fun slipMode() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/slip.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), tune = { it.copy(slip = true) }))
        }
    }

    @Test
    fun emergencyLoop() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/emergency-loop.png") { activity ->
            activity.setContentView(
                player(
                    activity,
                    catalog,
                    WaveZoom.BARS_4,
                    BeatFxState(),
                    posA = 30.2f,
                    tune = { it.copy(emergency = true, loopStart = 30f, loopEnd = 32f) },
                ),
            )
        }
    }

    @Test
    fun cueBank() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/cue-bank.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), tune = { it.copy(cueBank = 1) }))
        }
    }

    @Test
    fun infoArt() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/info-art.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), showInfo = true))
        }
    }

    @Test
    fun waveThreeBand() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/wave-3band.png") { activity ->
            activity.setContentView(
                player(
                    activity,
                    catalog,
                    WaveZoom.TRACK,
                    BeatFxState(),
                    leftTitle = "Quarto Escuro",
                    posA = 100f,
                    settings = PlayerSettings(waveColor = "THREE_BAND", followUsb = false),
                ),
            )
        }
    }

    @Test
    fun waveBlue() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/wave-blue.png") { activity ->
            activity.setContentView(
                player(
                    activity,
                    catalog,
                    WaveZoom.TRACK,
                    BeatFxState(),
                    leftTitle = "Quarto Escuro",
                    posA = 100f,
                    settings = PlayerSettings(waveColor = "BLUE", followUsb = false),
                ),
            )
        }
    }

    @Test
    fun trackFilter() {
        val catalog = simulatedCatalog()
        val playlist = catalog.playlists.first { it.name == "Todas" }.id
        val place = BrowsePlace(inList = true, playlistId = playlist, index = 0, matchKey = "Am", bpmMin = 118, bpmMax = 130)
        capture("$PRINTS/track-filter.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_4, BeatFxState(), place))
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
    posA: Float = 9.2f,
    posB: Float = 4.4f,
    overlay: String = "",
    nav: String = "",
    query: String = "",
    fxList: Boolean = false,
    rmx: RmxState = RmxState(),
    loop: Pair<Float, Float>? = null,
    jump: Double = 0.0,
    recording: Boolean = false,
    recClock: String = "00:00",
    recLevel: Float = 0f,
    history: List<HistoryRow> = emptyList(),
    tune: (DeckFace) -> DeckFace = { it },
    showInfo: Boolean = false,
    leftTitle: String = "Night Bus",
    settings: PlayerSettings = PlayerSettings(),
): ComposeView {
    val left = catalog.tracks.first { it.title == leftTitle }
    val right = catalog.tracks.first { it.title == "Glasshouse" }
    val faces = listOf(
        DeckFace(
            playing = true,
            position = posA,
            pitch = 1f,
            fader = 0.5f,
            onAir = true,
            remain = true,
            loopStart = loop?.first ?: -1f,
            loopEnd = loop?.second ?: -1f,
            lastJump = jump,
        ).let(tune),
        DeckFace(
            playing = true,
            position = posB,
            pitch = 1.0402f,
            range = 0.16,
            fader = faderFromPitch(1.0402, 0.16).toFloat(),
            remain = true,
            sync = true,
            onAir = false,
        ),
    )
    return ComposeView(activity).apply {
        setContent {
            PlayerScreen(
                catalog = catalog,
                tracks = listOf(left, right),
                clock = "21:14",
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
                nav = nav,
                overlay = overlay,
                queryText = query,
                fxList = fxList,
                rmx = rmx,
                settings = settings,
                tagNames = mapOf(left.id to listOf("Abertura")),
                tagTrack = left.id,
                recording = recording,
                recClock = recClock,
                recLevel = recLevel,
                historyRows = history,
                artworkOf = ::previewArt,
                showInfo = showInfo,
            )
        }
    }
}
