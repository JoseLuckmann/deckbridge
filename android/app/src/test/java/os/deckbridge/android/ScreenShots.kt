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
import os.deckbridge.logic.RbAnalysis
import os.deckbridge.logic.RbPlaylist
import os.deckbridge.logic.analyzeTrack
import os.deckbridge.logic.autoCues
import os.deckbridge.logic.banksFromCues
import os.deckbridge.logic.wavesFromPcm
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
import os.deckbridge.logic.BrowseCategory
import os.deckbridge.logic.RealCorpus
import os.deckbridge.logic.loadRekordbox
import org.junit.Assume.assumeTrue
import os.deckbridge.logic.readArtworkFile
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.FolderVolume
import os.deckbridge.logic.decodeWavMono
import os.deckbridge.logic.loadPlainFolder
import java.io.File

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
    fun mixerView() {
        val catalog = simulatedCatalog()
        val mixer = os.deckbridge.logic.MixerState(
            eq = listOf(listOf(0.0f, 0.62f, 0.5f), listOf(0.5f, 0.3f, 0.85f)),
            trim = listOf(0.55f, 0.45f),
            volume = listOf(0.9f, 0.55f),
            cross = 0.42f,
            headMix = 0.35f,
            headLevel = 0.8f,
            filter = listOf(64, 30),
            colorFx = 1,
            levels = listOf(0.82f, 0.55f, 0.78f, 0.74f),
        )
        // COMP no canal 1, isolador do master com o grave cortado, Send de REVERB no canal 2 e curva de corte no crossfader.
        val console = os.deckbridge.logic.ConsoleState(
            comp = listOf(0.6f, 0f), isolatorOn = true, isolator = listOf(0f, 0.5f, 0.75f),
            send = listOf(0f, 0.45f), sendType = 3, sendSize = 0.6f, sendTime = 0.4f, sendTone = 0.62f,
        )
        capture("$PRINTS/mixer.png") { activity ->
            activity.setContentView(
                player(
                    activity, catalog, WaveZoom.BARS_4, BeatFxState(), overlay = "mixer", nav = "MIXER", mixer = mixer,
                    settings = PlayerSettings(chFaderCurve = 1, crossCurve = 2), console = console,
                ),
            )
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w1280dp-h720dp-mdpi")
class ScreenShotsCompact {
    @Test
    fun playerCompact() {
        val catalog = simulatedCatalog()
        capture("$PRINTS/compact-player.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_8, BeatFxState(), leftTitle = "Quarto Escuro", posA = 100f))
        }
    }

    @Test
    fun popupsCompact() {
        val catalog = simulatedCatalog()
        for (kind in listOf("LOOP", "KEY")) {
            capture("$PRINTS/compact-popup-${kind.lowercase()}.png") { activity ->
                activity.setContentView(player(activity, catalog, WaveZoom.BARS_8, BeatFxState(), leftTitle = "Quarto Escuro", posA = 100f, startPopup = kind))
            }
        }
    }

    @Test
    fun rmxDockCompact() {
        val catalog = simulatedCatalog()
        val rmx = RmxState(panel = true, full = true, scene = 1, holding = true, intensity = 0.55f, bars = 8, xOn = true, xMode = 2, drumBuild = 1f)
        capture("$PRINTS/compact-rmx-dock.png") { activity ->
            activity.setContentView(player(activity, catalog, WaveZoom.BARS_8, BeatFxState(), leftTitle = "Quarto Escuro", posA = 100f, rmx = rmx))
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
    rightTitle: String = "Glasshouse",
    settings: PlayerSettings = PlayerSettings(),
    usbLabel: String = "USB simulado",
    historyIds: List<Int> = emptyList(),
    mixer: os.deckbridge.logic.MixerState = os.deckbridge.logic.MixerState(),
    startPopup: String = "",
    console: os.deckbridge.logic.ConsoleState = os.deckbridge.logic.ConsoleState(),
): ComposeView {
    val left = catalog.tracks.first { it.title == leftTitle }
    val right = catalog.tracks.first { it.title == rightTitle }
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
            mixer = mixer,
            startPopup = startPopup,
            console = console,
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
                historyIds = historyIds,
                usbLabel = usbLabel,
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w2000dp-h1200dp-mdpi")
class ScreenShotsReal {
    @Test
    fun realTracksAtDropAndBreak() {
        assumeTrue(java.io.File("/opt/cursor/artifacts/real-corpus").isDirectory)
        val ready = RealCorpus.prepare()
        assumeTrue(ready != null)
        val catalog = ready!!.catalog
        ready.shots.forEach { shot ->
            capture("$PRINTS/real-${shot.slug}-drop.png") { activity ->
                activity.setContentView(
                    player(
                        activity,
                        catalog,
                        WaveZoom.BARS_4,
                        BeatFxState(),
                        posA = shot.drop,
                        posB = shot.breakSec,
                        leftTitle = shot.title,
                        rightTitle = shot.title,
                    ),
                )
            }
            capture("$PRINTS/real-${shot.slug}-build.png") { activity ->
                activity.setContentView(
                    player(
                        activity,
                        catalog,
                        WaveZoom.BARS_4,
                        BeatFxState(),
                        posA = shot.buildSec,
                        posB = shot.drop,
                        leftTitle = shot.title,
                        rightTitle = shot.title,
                    ),
                )
            }
            capture("$PRINTS/real-${shot.slug}-break.png") { activity ->
                activity.setContentView(
                    player(
                        activity,
                        catalog,
                        WaveZoom.BARS_4,
                        BeatFxState(),
                        posA = shot.breakSec,
                        posB = shot.drop,
                        leftTitle = shot.title,
                        rightTitle = shot.title,
                    ),
                )
            }
        }
        val playlist = catalog.playlists.first { it.name == "Real" }.id
        val place = BrowsePlace(category = BrowseCategory.PLAYLIST, inList = true, playlistId = playlist, index = 0)
        val first = catalog.tracks.first().title
        val second = catalog.tracks.getOrElse(1) { catalog.tracks.first() }.title
        capture("$PRINTS/real-browse.png") { activity ->
            activity.setContentView(
                player(activity, catalog, WaveZoom.BARS_8, BeatFxState(), place, leftTitle = first, rightTitle = second),
            )
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w2000dp-h1200dp-mdpi")
class ScreenShotsPlainFolder {
    @Test
    fun plainFolderBrowseShowsBpmKeyAndWave() {
        val source = File("/opt/cursor/artifacts/ground-truth/mono")
        val picks = listOf(
            "3317417.wav" to "House.wav",
            "4014747.wav" to "Techno.wav",
            "1234750.wav" to "Trance.wav",
            "1234668.wav" to "Drum And Bass.wav",
        )
        assumeTrue(picks.all { File(source, it.first).isFile })
        val folder = File("/tmp/plain-browse").apply {
            deleteRecursively()
            mkdirs()
        }
        picks.forEach { (from, to) -> File(source, from).copyTo(File(folder, to), overwrite = true) }
        val cache = File("/tmp/plain-browse-cache").apply { deleteRecursively(); mkdirs() }
        var progress: os.deckbridge.logic.RbCatalog? = null
        val done = loadPlainFolder(FolderVolume(folder, "pasta solta"), cache, { _, bytes ->
            val pcm = decodeWavMono(bytes) ?: return@loadPlainFolder null
            pcm.samples to pcm.rate
        }) { step ->
            if (progress == null) progress = step
        }
        val place = BrowsePlace(category = BrowseCategory.PLAYLIST, inList = true, playlistId = done.playlists.first().id, index = 0)
        val left = done.tracks.first().title
        val right = done.tracks[1].title
        capture("$PRINTS/plain-browse-progress.png") { activity ->
            activity.setContentView(
                player(activity, progress!!, WaveZoom.BARS_8, BeatFxState(), place, leftTitle = left, rightTitle = right, usbLabel = "pasta solta"),
            )
        }
        capture("$PRINTS/plain-browse.png") { activity ->
            activity.setContentView(
                player(activity, done, WaveZoom.BARS_8, BeatFxState(), place, leftTitle = left, rightTitle = right, usbLabel = "pasta solta"),
            )
        }
        assert(done.tracks.all { it.bpm > 0.0 && it.keyName.isNotBlank() })
        assert(done.analyses.values.all { it.rgbPreview != null && it.rgbApp })
        assert(done.scanNote.contains("sem export"))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w2000dp-h1200dp-mdpi")
class ScreenShotsPhrases {
    @Test
    fun phraseBarCuesGainPhaseAndSuggest() {
        val morning = analyzed("morning-original-mix.wav", 1, "Morning (Original Mix)", "Miroslav Wilde", "House")
        val caught = analyzed("caught-in-the-beat.wav", 2, "Caught In The Beat", "Broke For Free", "Funk")
        assumeTrue(morning != null && caught != null)
        val real = catalogOf(listOf(morning!!, caught!!), "corpus")
        capture("$PRINTS/phrase-cues-gain.png") { activity ->
            activity.setContentView(
                player(
                    activity,
                    real,
                    WaveZoom.BARS_8,
                    BeatFxState(),
                    posA = morning.second.structure.dropSec.toFloat(),
                    posB = caught.second.structure.dropSec.toFloat(),
                    leftTitle = morning.first.title,
                    rightTitle = caught.first.title,
                    settings = PlayerSettings(autoGain = true, targetLufs = -10.0),
                    usbLabel = "corpus",
                ),
            )
        }
        val phase = catalogOf(
            listOf(
                synthetic(1, "Master", "Am", 120.0, -16.0),
                synthetic(2, "Vizinho", "Am", 120.0, -16.0),
            ),
            "fase",
        )
        capture("$PRINTS/phase-meter.png") { activity ->
            activity.setContentView(
                player(
                    activity,
                    phase,
                    WaveZoom.BARS_4,
                    BeatFxState(),
                    posA = 8f,
                    posB = 8.5f,
                    leftTitle = "Master",
                    rightTitle = "Vizinho",
                    settings = PlayerSettings(waveView = "PHASE", autoGain = true, targetLufs = -10.0),
                ),
            )
        }
        val master = synthetic(1, "Night Bus", "Am", 124.0, -14.0, 5.0)
        val next = synthetic(2, "Glasshouse", "Em", 124.0, -13.0, 6.0)
        val same = synthetic(3, "Mesmo", "Am", 124.0, -14.0, 5.2)
        val played = synthetic(4, "Ja foi", "Am", 124.0, -14.0, 5.0)
        val far = synthetic(5, "Longe", "C", 90.0, -12.0, 3.0)
        val shelf = catalogOf(listOf(master, next, same, played, far), "biblioteca")
        val matching = BrowsePlace(category = BrowseCategory.MATCHING, inList = true, index = 0)
        capture("$PRINTS/browse-matching.png") { activity ->
            activity.setContentView(
                player(
                    activity,
                    shelf,
                    WaveZoom.BARS_8,
                    BeatFxState(),
                    matching,
                    leftTitle = "Night Bus",
                    rightTitle = "Glasshouse",
                    historyIds = listOf(4),
                ),
            )
        }
        val suggest = BrowsePlace(category = BrowseCategory.SUGGEST, inList = true, index = 0)
        capture("$PRINTS/browse-suggest.png") { activity ->
            activity.setContentView(
                player(
                    activity,
                    shelf,
                    WaveZoom.BARS_8,
                    BeatFxState(),
                    suggest,
                    leftTitle = "Night Bus",
                    rightTitle = "Glasshouse",
                    historyIds = listOf(4),
                    settings = PlayerSettings(gentleSuggest = true),
                ),
            )
        }
    }

    private fun analyzed(file: String, id: Int, title: String, artist: String, genre: String): Triple<RbTrack, os.deckbridge.logic.TrackRead, RbAnalysis>? {
        val wav = File("/opt/cursor/artifacts/real-corpus/mono/$file")
        if (!wav.isFile) return null
        val pcm = decodeWavMono(wav.readBytes()) ?: return null
        val read = analyzeTrack(pcm.samples, pcm.rate)
        val waves = wavesFromPcm(pcm.samples, pcm.rate)
        val cues = autoCues(read.phrases, read.beats, "drop")
        val track = RbTrack(id, title, artist, "", genre, read.keyName, read.bpm, pcm.samples.size / pcm.rate, pcm.rate, file, file, "")
        val analysis = RbAnalysis(
            beats = read.beats,
            hotCues = cues.first,
            memoryCues = cues.second,
            mainCueMs = cues.first.firstOrNull()?.timeMs ?: 0,
            waveform = ByteArray(0),
            waveSource = "APP",
            rgbDetail = waves.rgbDetail,
            rgbPreview = waves.rgbPreview,
            phrases = read.phrases,
            loudnessLufs = read.loudnessLufs,
            energy = read.energy,
            hotCueBanks = banksFromCues(cues.first),
            rgbApp = true,
        )
        return Triple(track, read, analysis)
    }

    private fun synthetic(id: Int, title: String, key: String, bpm: Double, lufs: Double, energy: Double = 5.0): Triple<RbTrack, os.deckbridge.logic.TrackRead, RbAnalysis> {
        val track = RbTrack(id, title, "Artista", "Album", "House", key, bpm, 180, 44100, "$title.mp3", "$title.mp3", "")
        val analysis = RbAnalysis(
            beats = emptyList(),
            hotCues = emptyList(),
            memoryCues = emptyList(),
            mainCueMs = 0,
            waveform = ByteArray(0),
            loudnessLufs = lufs,
            energy = energy,
        )
        return Triple(track, os.deckbridge.logic.TrackRead(bpm, 1.0, 0.0, 0.0, 0.0, 1.0, key, 1.0, emptyList(), os.deckbridge.logic.StructurePoints(0.0, 0.0, 0.0, 0.0), 0L, emptyList(), lufs, energy), analysis)
    }

    private fun catalogOf(rows: List<Triple<RbTrack, os.deckbridge.logic.TrackRead, RbAnalysis>>, label: String): RbCatalog {
        val tracks = rows.map { it.first }
        return RbCatalog(
            tracks = tracks,
            playlists = listOf(RbPlaylist(1, 0, "Set", false, 0, tracks.map { it.id })),
            analyses = rows.associate { it.first.id to it.third },
            label = label,
            simulated = true,
            oneLibraryPresent = false,
            oneLibraryReadable = false,
            oneLibraryNote = "",
            oneLibraryContentRows = 0,
            trackTags = emptyMap(),
            warnings = emptyList(),
            error = null,
        )
    }
}
