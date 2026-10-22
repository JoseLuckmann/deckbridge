package os.deckbridge.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import os.deckbridge.logic.BeatFxState
import os.deckbridge.logic.BrowseCategory
import os.deckbridge.logic.BrowsePlace
import os.deckbridge.logic.DeckFace
import os.deckbridge.logic.PerformanceAction
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.ReadableVolume
import os.deckbridge.logic.WaveZoom
import os.deckbridge.logic.activeLoop
import os.deckbridge.logic.backBrowse
import os.deckbridge.logic.beatFxAudioKind
import os.deckbridge.logic.beatFxBeats
import os.deckbridge.logic.browseRows
import os.deckbridge.logic.enterBrowse
import os.deckbridge.logic.faderFromPitch
import os.deckbridge.logic.jogSeconds
import os.deckbridge.logic.moveBrowse
import os.deckbridge.logic.nextTempoRange
import os.deckbridge.logic.openCategory
import os.deckbridge.logic.pickBrowse
import os.deckbridge.logic.pitchFromFader
import os.deckbridge.logic.quantizeSeconds
import os.deckbridge.logic.reduceBeatFx
import os.deckbridge.logic.syncPitch
import kotlin.math.roundToInt

@Composable
fun DeckPage(
    catalog: RbCatalog?,
    volume: ReadableVolume?,
    deviceId: Int,
    autoplay: Boolean,
    loaded: List<RbTrack?>,
    onLoaded: (Int, RbTrack?) -> Unit,
    jvmBanner: String? = null,
    onPage: (Int) -> Unit = {},
    usbLabel: String = "",
    browsePulse: Int = 0,
    onAuthorize: (Uri?) -> Unit = {},
    onSimulate: () -> Unit = {},
) {
    val context = LocalContext.current
    val liveCatalog = rememberUpdatedState(catalog)
    val liveVolume = rememberUpdatedState(volume)
    val liveLoaded = rememberUpdatedState(loaded)
    var pitchA by remember { mutableFloatStateOf(1f) }
    var pitchB by remember { mutableFloatStateOf(1f) }
    var volA by remember { mutableFloatStateOf(0.8f) }
    var volB by remember { mutableFloatStateOf(0.8f) }
    var cross by remember { mutableFloatStateOf(0.5f) }
    var pflA by remember { mutableStateOf(false) }
    var pflB by remember { mutableStateOf(false) }
    var faces by remember { mutableStateOf(listOf(DeckFace(), DeckFace())) }
    var master by remember { mutableIntStateOf(0) }
    var quantize by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(listOf(WaveZoom.BARS_8, WaveZoom.BARS_8)) }
    var zoomLink by remember { mutableStateOf(true) }
    var fx by remember { mutableStateOf(BeatFxState()) }
    var browse by remember { mutableStateOf<BrowsePlace?>(null) }
    var stored by remember { mutableStateOf(BrowsePlace()) }
    var history by remember { mutableStateOf(listOf<Int>()) }
    var note by remember { mutableStateOf("") }
    var active by remember { mutableIntStateOf(0) }
    var clock by remember { mutableStateOf(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))) }
    var consumed by remember { mutableStateOf(mapOf<Int, Int>()) }
    var started by remember { mutableStateOf(false) }
    val tree = rememberLauncherForActivityResult(OpenTreeReadOnly()) { uri -> onAuthorize(uri) }

    fun pushMix() {
        NativeEngine.setMix(volA, volB, pitchA, pitchB, cross, pflA, pflB)
    }

    fun setPitch(deck: Int, value: Float) {
        if (deck == 0) pitchA = value else pitchB = value
    }

    fun edit(deck: Int, block: (DeckFace) -> DeckFace) {
        if (deck !in 0..1) return
        faces = faces.mapIndexed { index, face -> if (index == deck) block(face) else face }
    }

    fun rememberLoaded(track: RbTrack) {
        history = (listOf(track.id) + history.filter { it != track.id }).take(32)
    }

    fun loadTrack(deck: Int, track: RbTrack, source: ReadableVolume) {
        val bytes = source.read(track.filePath)
        if (bytes == null) {
            note = "Áudio ausente para ${track.title}."
            return
        }
        val pcm = decodeTrackAudio(context, track.fileName.ifBlank { track.filePath }, bytes)
        if (pcm == null) {
            note = "Não decodifiquei ${track.fileName}. Nesta versão o deck aceita WAV e MP3."
            return
        }
        val analysis = liveCatalog.value?.analyses?.get(track.id)
        val hot = FloatArray(8) { -1f }
        analysis?.hotCues?.forEach { cue ->
            if (cue.index in hot.indices) hot[cue.index] = cue.timeMs / 1000f
        }
        val loop = activeLoop(analysis)
        NativeEngine.loadDeck(
            deck,
            pcm,
            track.bpm.toFloat(),
            (analysis?.mainCueMs ?: 0) / 1000f,
            hot,
            (loop?.first ?: -1) / 1000f,
            (loop?.second ?: -1) / 1000f,
        )
        onLoaded(deck, track)
        rememberLoaded(track)
        edit(deck) { it.copy(playing = false, position = 0f) }
        val loopText = if (loop == null) "" else " Loop ${loop.first}–${loop.second} ms."
        note = "Carregada no deck ${deck + 1}: ${track.title}.$loopText"
        pushMix()
    }

    fun placeNow(): BrowsePlace = browse ?: stored

    fun rowsOf(place: BrowsePlace) = liveCatalog.value?.let { browseRows(it, place, history) }.orEmpty()

    fun loadSelected(deck: Int) {
        val source = liveVolume.value ?: return
        val place = placeNow()
        val row = rowsOf(place).getOrNull(place.index) ?: return
        if (row.folder) {
            browse = enterBrowse(place, row)
            return
        }
        val track = row.track ?: return
        loadTrack(deck, track, source)
        stored = place
        browse = null
    }

    fun seekTo(deck: Int, seconds: Float) {
        val clamped = seconds.coerceAtLeast(0f)
        NativeEngine.seek(deck, clamped)
        edit(deck) { it.copy(position = clamped) }
    }

    fun hotCue(deck: Int, index: Int) {
        val track = liveLoaded.value.getOrNull(deck)
        val analysis = track?.let { liveCatalog.value?.analyses?.get(it.id) }
        val cue = analysis?.hotCues?.firstOrNull { it.index == index }
        if (cue != null && quantize) {
            val seconds = quantizeSeconds(cue.timeMs / 1000.0, analysis.beats, track.bpm)
            seekTo(deck, seconds.toFloat())
        } else {
            NativeEngine.hotCue(deck, index)
            if (cue != null) edit(deck) { it.copy(position = cue.timeMs / 1000f) }
        }
    }

    fun applyRange(deck: Int, range: Double, fader: Float) {
        PerformanceHub.tempo?.setRange(deck, range)
        val pitch = pitchFromFader(fader.toDouble(), range).toFloat()
        setPitch(deck, pitch)
        edit(deck) { it.copy(range = range, fader = fader, pitch = pitch, sync = false) }
    }

    fun syncDeck(deck: Int) {
        if (deck !in 0..1) return
        val own = liveLoaded.value.getOrNull(deck)?.bpm ?: return
        val other = liveLoaded.value.getOrNull(1 - deck)?.bpm ?: return
        val otherPitch = if (deck == 0) pitchB.toDouble() else pitchA.toDouble()
        val synced = syncPitch(own, other, otherPitch) ?: return
        val range = faces[deck].range
        setPitch(deck, synced.toFloat())
        edit(deck) { it.copy(pitch = synced.toFloat(), sync = true, fader = faderFromPitch(synced, range).toFloat()) }
    }

    fun onPerformance(action: PerformanceAction) {
        val deck = action.deck
        when (action.kind) {
            PerformanceAction.Kind.PLAY -> {
                if (deck !in 0..1) return
                val playing = !faces[deck].playing
                edit(deck) { it.copy(playing = playing) }
                NativeEngine.transport(deck, playing)
            }
            PerformanceAction.Kind.CUE -> {
                if (deck !in 0..1) return
                edit(deck) { it.copy(playing = false) }
                NativeEngine.cue(deck)
            }
            PerformanceAction.Kind.HOT_CUE -> if (deck in 0..1) hotCue(deck, action.hotIndex)
            PerformanceAction.Kind.VOLUME -> {
                val level = action.slider!!.toFloat()
                if (deck == 0) volA = level else if (deck == 1) volB = level
                if (deck in 0..1) edit(deck) { it.copy(onAir = level > 0.2f) }
            }
            PerformanceAction.Kind.CROSSFADER -> cross = action.slider!!.toFloat()
            PerformanceAction.Kind.PITCH -> {
                if (deck !in 0..1) return
                val range = PerformanceHub.tempo?.rangeOf(deck) ?: faces[deck].range
                val pitch = action.slider!!.toFloat()
                setPitch(deck, pitch)
                edit(deck) {
                    it.copy(pitch = pitch, range = range, fader = faderFromPitch(pitch.toDouble(), range).toFloat(), sync = false)
                }
            }
            PerformanceAction.Kind.JOG -> {
                if (deck !in 0..1) return
                val seconds = jogSeconds(action.slider ?: 0.0).toFloat()
                NativeEngine.nudge(deck, seconds)
                edit(deck) { it.copy(position = (it.position + seconds).coerceAtLeast(0f)) }
            }
            PerformanceAction.Kind.BROWSE_MOVE -> {
                val place = browse ?: return
                browse = moveBrowse(place, (action.slider ?: 0.0).roundToInt(), rowsOf(place).size)
            }
            PerformanceAction.Kind.BROWSE_ENTER -> {
                val place = browse
                if (place == null) {
                    browse = stored
                } else {
                    val rows = rowsOf(place)
                    val safe = if (rows.isEmpty()) 0 else place.index.coerceIn(0, rows.lastIndex)
                    browse = enterBrowse(place, rows.getOrNull(safe))
                }
            }
            PerformanceAction.Kind.BROWSE_BACK -> {
                val place = browse ?: return
                val next = backBrowse(place)
                browse = if (next == place) null else next
                if (browse == null) stored = place
            }
            PerformanceAction.Kind.LOAD -> if (deck in 0..1) loadSelected(deck)
            PerformanceAction.Kind.BEAT_FX -> fx = reduceBeatFx(fx, action)
            PerformanceAction.Kind.FILTER -> {
                if (deck !in 0..1) return
                val value = ((action.slider ?: 0.5) * 127.0).roundToInt().coerceIn(0, 127)
                edit(deck) { it.copy(filter = value) }
            }
            PerformanceAction.Kind.QUANTIZE -> quantize = !quantize
            PerformanceAction.Kind.SYNC -> syncDeck(deck)
            PerformanceAction.Kind.MASTER -> if (deck in 0..1) master = deck
        }
        if (deck in 0..1) active = deck
        pushMix()
    }

    DisposableEffect(Unit) {
        PerformanceHub.onAction = { action -> onPerformance(action) }
        onDispose {
            PerformanceHub.onAction = null
            NativeEngine.transport(0, false)
            NativeEngine.transport(1, false)
        }
    }

    LaunchedEffect(pitchA, pitchB, volA, volB, cross, pflA, pflB) { pushMix() }

    LaunchedEffect(Unit) {
        while (true) {
            clock = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            delay(15_000)
        }
    }

    LaunchedEffect(fx, pitchA, pitchB, master, loaded) {
        val deck = when (fx.channel) {
            1 -> 1
            2 -> master
            else -> 0
        }
        val bpm = (loaded.getOrNull(deck)?.bpm ?: 120.0) * (if (deck == 0) pitchA else pitchB)
        NativeEngine.setBeatFx(
            beatFxAudioKind(fx.effect),
            fx.on,
            fx.level,
            fx.channel,
            bpm.toFloat(),
            beatFxBeats(fx.fractionLabel()).toFloat(),
        )
    }

    LaunchedEffect(browsePulse) {
        if (browsePulse > 0) browse = stored
    }

    LaunchedEffect(loaded[0]?.id, loaded[1]?.id, volume) {
        if (autoplay) return@LaunchedEffect
        val source = volume ?: return@LaunchedEffect
        loaded.forEachIndexed { index, track ->
            if (track != null && consumed[index] != track.id) {
                loadTrack(index, track, source)
                consumed = consumed + (index to track.id)
            }
        }
    }

    LaunchedEffect(deviceId) {
        withContext(Dispatchers.IO) { NativeEngine.start(deviceId, 3) }
    }

    LaunchedEffect(autoplay, catalog, volume) {
        if (!autoplay || started || catalog == null || volume == null) return@LaunchedEffect
        val first = catalog.tracks.getOrNull(0)
        val second = catalog.tracks.getOrNull(1)
        if (first != null) loadTrack(0, first, volume)
        if (second != null) loadTrack(1, second, volume)
        cross = 0.5f
        pflB = true
        pushMix()
        edit(0) { it.copy(playing = true) }
        edit(1) { it.copy(playing = true) }
        NativeEngine.transport(0, true)
        NativeEngine.transport(1, true)
        started = true
        note = "SIMULAÇÃO tocando as duas faixas. Pré-escuta do deck 2 pedida nos canais 3–4."
    }

    LaunchedEffect(Unit) {
        while (true) {
            val left = NativeEngine.position(0)
            val right = NativeEngine.position(1)
            if (NativeEngine.running()) {
                faces = faces.mapIndexed { index, face ->
                    face.copy(position = if (index == 0) left else right)
                }
            }
            delay(80)
        }
    }

    PlayerScreen(
        catalog = catalog,
        tracks = loaded,
        faces = faces,
        master = master,
        quantize = quantize,
        zoom = zoom,
        zoomLink = zoomLink,
        fx = fx,
        jvmBanner = jvmBanner,
        browse = browse,
        historyIds = history,
        usbLabel = usbLabel,
        clock = clock,
        activeDeck = active,
        status = note,
        onPage = onPage,
        onBrowse = { browse = stored },
        onBrowseClose = {
            browse?.let { stored = it }
            browse = null
        },
        onZoom = { deck, next ->
            zoom = if (zoomLink) listOf(next, next) else zoom.mapIndexed { index, item -> if (index == deck) next else item }
        },
        onLink = { linked ->
            zoomLink = linked
            if (linked) zoom = listOf(zoom.first(), zoom.first())
        },
        onPlay = { deck ->
            val playing = !faces[deck].playing
            edit(deck) { it.copy(playing = playing) }
            NativeEngine.transport(deck, playing)
        },
        onCue = { deck ->
            edit(deck) { it.copy(playing = false) }
            NativeEngine.cue(deck)
        },
        onHot = ::hotCue,
        onSync = ::syncDeck,
        onMaster = { master = it },
        onKey = { deck -> edit(deck) { it.copy(keyLock = !it.keyLock) } },
        onRemain = { deck -> edit(deck) { it.copy(remain = !it.remain) } },
        onRange = { deck -> applyRange(deck, nextTempoRange(faces[deck].range), faces[deck].fader) },
        onFader = { deck, position ->
            val pitch = pitchFromFader(position.toDouble(), faces[deck].range).toFloat()
            setPitch(deck, pitch)
            edit(deck) { it.copy(fader = position, pitch = pitch, sync = false) }
        },
        onScratch = { deck, seconds ->
            NativeEngine.nudge(deck, seconds)
            edit(deck) { it.copy(position = (it.position + seconds).coerceAtLeast(0f)) }
        },
        onJump = { deck, beats ->
            val track = loaded.getOrNull(deck)
            if (track != null) {
                val beat = if (track.bpm > 1.0) 60.0 / track.bpm else 0.5
                val raw = faces[deck].position + beats * beat
                val analysis = catalog?.analyses?.get(track.id)
                val seconds = if (quantize && analysis != null) quantizeSeconds(raw, analysis.beats, track.bpm) else raw
                seekTo(deck, seconds.toFloat())
            }
        },
        onQuantize = { quantize = !quantize },
        onAuthorize = { tree.launch(removableInitialUri(context)) },
        onSimulate = onSimulate,
        onBrowseMove = { steps ->
            val place = browse
            if (place != null) browse = moveBrowse(place, steps, rowsOf(place).size)
        },
        onBrowseEnter = {
            val place = browse
            if (place != null) {
                val rows = rowsOf(place)
                browse = enterBrowse(place, rows.getOrNull(place.index))
            }
        },
        onBrowseBack = {
            val place = browse
            if (place != null) {
                val next = backBrowse(place)
                if (next == place) {
                    stored = place
                    browse = null
                } else {
                    browse = next
                }
            }
        },
        onBrowsePick = { index ->
            val place = browse
            if (place != null) {
                val rows = rowsOf(place)
                browse = pickBrowse(place, index, rows.getOrNull(index))
            }
        },
        onBrowseCategory = { category: BrowseCategory ->
            browse = openCategory(browse ?: stored, category)
        },
        onQuery = { query ->
            val place = browse
            if (place != null) browse = place.copy(query = query, index = 0)
        },
        onActive = { active = it },
        onFxChange = { fx = it },
        onOpenCategory = { category -> browse = openCategory(stored, category) },
        onSeek = { deck, seconds -> seekTo(deck, seconds) },
        onLoad = { track, deck ->
            val source = volume
            if (source != null) {
                loadTrack(deck, track, source)
                browse?.let { stored = it }
                browse = null
            }
        },
    )
}
