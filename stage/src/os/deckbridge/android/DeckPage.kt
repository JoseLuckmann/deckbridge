package os.deckbridge.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import os.deckbridge.logic.BeatFxState
import os.deckbridge.logic.BrowseCategory
import os.deckbridge.logic.BrowsePlace
import os.deckbridge.logic.DeckFace
import os.deckbridge.logic.DEFAULT_MASTER_LEVEL
import os.deckbridge.logic.GlitchWatch
import os.deckbridge.logic.TracklistLogger
import os.deckbridge.logic.cueSheet
import os.deckbridge.logic.tracklistText
import os.deckbridge.logic.masterGainLinear
import os.deckbridge.logic.LOOP_ADJUST_STEP
import os.deckbridge.logic.JOG_BEND_HOLD_MS
import os.deckbridge.logic.adjustLoopPoint
import os.deckbridge.logic.jogBentPitch
import os.deckbridge.logic.nextJumpSize
import os.deckbridge.logic.PerformanceAction
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.ReadableVolume
import os.deckbridge.logic.WaveZoom
import os.deckbridge.logic.toArray
import os.deckbridge.logic.lacksDetail
import os.deckbridge.logic.withDetail
import os.deckbridge.logic.GridEdit
import os.deckbridge.logic.ListSort
import os.deckbridge.logic.activeLoop
import os.deckbridge.logic.applyGrid
import os.deckbridge.logic.arrivedAtActiveLoop
import os.deckbridge.logic.gridBpm
import os.deckbridge.logic.toggleListSort
import os.deckbridge.logic.backBrowse
import os.deckbridge.logic.PlayerSettings
import os.deckbridge.logic.RmxState
import os.deckbridge.logic.beatFxAudioKind
import os.deckbridge.logic.beatFxBeats
import os.deckbridge.logic.beatJumpPadBeats
import os.deckbridge.logic.HistoryRow
import os.deckbridge.logic.OnAirLog
import os.deckbridge.logic.onAirSample
import os.deckbridge.logic.jumpSeconds
import os.deckbridge.logic.storageWarning
import os.deckbridge.logic.mergedTagNames
import os.deckbridge.logic.reduceRmxPad
import os.deckbridge.logic.rmxKnob
import os.deckbridge.logic.rmxPayload
import os.deckbridge.logic.rmxToggleHold
import os.deckbridge.logic.rmxRiseLeft
import os.deckbridge.logic.rmxRiseStep
import os.deckbridge.logic.scaleLoop
import os.deckbridge.logic.stepJumpScale
import os.deckbridge.logic.toggleBeatLoop
import os.deckbridge.logic.resolveUsbSettings
import os.deckbridge.logic.zoomFromBars
import os.deckbridge.logic.browseRows
import os.deckbridge.logic.emergencyLoopRange
import os.deckbridge.logic.enterBrowse
import os.deckbridge.logic.keySyncShift
import os.deckbridge.logic.nextCueBank
import os.deckbridge.logic.faderFromPitch
import os.deckbridge.logic.jogSeconds
import os.deckbridge.logic.moveBrowse
import os.deckbridge.logic.nextTempoRange
import os.deckbridge.logic.openCategory
import os.deckbridge.logic.pickBrowse
import os.deckbridge.logic.pitchFromFader
import os.deckbridge.logic.quantizeSeconds
import os.deckbridge.logic.reduceBeatFx
import os.deckbridge.logic.syncPhaseShiftSeconds
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
    onAuthorize: () -> Unit = {},
    onSimulate: () -> Unit = {},
    /** O que a plataforma dá ao modo DJ (Android ou computador; ADR-0012). */
    host: DjHost,
) {
    val liveCatalog = rememberUpdatedState(catalog)
    // Detalhe de onda refeito na carga quando o cache da pasta só trouxe a prévia (ver lacksDetail).
    val detailWaves = remember { androidx.compose.runtime.mutableStateMapOf<Int, os.deckbridge.logic.RbAnalysis>() }
    val shownCatalog by remember(catalog) {
        androidx.compose.runtime.derivedStateOf {
            if (detailWaves.isEmpty() || catalog == null) catalog else catalog.copy(analyses = catalog.analyses + detailWaves)
        }
    }
    val liveVolume = rememberUpdatedState(volume)
    val liveLoaded = rememberUpdatedState(loaded)
    var pitchA by remember { mutableFloatStateOf(1f) }
    var pitchB by remember { mutableFloatStateOf(1f) }
    var volA by remember { mutableFloatStateOf(0.8f) }
    var volB by remember { mutableFloatStateOf(0.8f) }
    var cross by remember { mutableFloatStateOf(0.5f) }
    // COMP, isolador do master, Send/Return e FX FREQUENCY (na tela, a DDJ-400 não tem esses controles).
    var console by remember { mutableStateOf(os.deckbridge.logic.ConsoleState()) }
    // Touch Preview: a faixa da lista que está soando no fone (só no fone; não passa pelos canais).
    var previewId by remember { mutableStateOf<Int?>(null) }
    // Estado do mixer da controladora (EQ, TRIM, fones), 0..1 com o centro em 0,5.
    var eqKnobs by remember { mutableStateOf(listOf(listOf(0.5f, 0.5f, 0.5f), listOf(0.5f, 0.5f, 0.5f))) }
    var trimKnobs by remember { mutableStateOf(listOf(0.5f, 0.5f)) }
    var headMix by remember { mutableStateOf(0f) }
    var headLevel by remember { mutableStateOf(0.75f) }
    var levels by remember { mutableStateOf(listOf(0f, 0f, 0f, 0f)) }
    // Picos com queda, lidos só pelos laços (medidores e LEDs): um vetor fixo, sem estado do Compose nem alocação.
    val peakHeld = remember { FloatArray(4) }
    var pflA by remember { mutableStateOf(false) }
    var pflB by remember { mutableStateOf(false) }
    var faces by remember { mutableStateOf(listOf(DeckFace(), DeckFace())) }
    var gridEdits by remember { mutableStateOf(mapOf<Int, GridEdit>()) }
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
    var settings by remember { mutableStateOf(PlayerSettings()) }
    var historyRows by remember { mutableStateOf(listOf<HistoryRow>()) }
    var sessionId by remember { mutableLongStateOf(-1L) }
    var recording by remember { mutableStateOf(false) }
    val tracklist = remember { TracklistLogger() }
    var recStamp by remember { mutableStateOf("") }
    var recStartMs by remember { mutableStateOf(0L) }
    var recClock by remember { mutableStateOf("00:00") }
    var recLevel by remember { mutableFloatStateOf(0f) }
    var recWarn by remember { mutableStateOf<String?>(null) }
    // Como parar o AAC que grava ao lado do WAV (só onde a plataforma codifica AAC).
    var aacWriter by remember { mutableStateOf<(() -> Unit)?>(null) }
    val historyDb = host.history
    val onAir = remember { OnAirLog() }
    val liveFaces = rememberUpdatedState(faces)
    val liveVols = rememberUpdatedState(volA to volB)
    val livePitch = rememberUpdatedState(pitchA to pitchB)
    val liveRecording = rememberUpdatedState(recording)
    val liveAac = rememberUpdatedState(aacWriter)
    val liveSettings = rememberUpdatedState(settings)
    val exportText = host.rememberTextExport { note = it }
    var rmx by remember { mutableStateOf(RmxState()) }
    var overlay by remember { mutableStateOf("") }
    var nav by remember { mutableStateOf("") }
    var addedTags by remember { mutableStateOf(mapOf<Int, Set<String>>()) }
    var removedTags by remember { mutableStateOf(mapOf<Int, Set<String>>()) }
    var searchBpm by remember { mutableStateOf(0 to 999) }
    var searchKey by remember { mutableStateOf("") }
    var queryText by remember { mutableStateOf("") }
    var tagTrack by remember { mutableStateOf<Int?>(null) }
    var fxList by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cueBanks = remember { Array(2) { Array(8) { FloatArray(8) { -1f } } } }
    val liveRmx = rememberUpdatedState(rmx)

    fun pushMix() {
        NativeEngine.setMix(volA, volB, pitchA, pitchB, cross, pflA, pflB)
        repeat(2) { deck ->
            val track = liveLoaded.value.getOrNull(deck)
            val lufs = track?.let { liveCatalog.value?.analyses?.get(it.id)?.loudnessLufs } ?: Double.NaN
            val linear = os.deckbridge.logic.dbToLinear(os.deckbridge.logic.trimDb(lufs, settings.targetLufs, settings.autoGain))
            NativeEngine.setTrim(deck, linear.toFloat())
        }
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

    // Ler e decodificar a faixa leva segundos: roda fora da thread da tela, senão a interface e os comandos
    // da controladora congelam. O deck que toca não é afetado (o motor só troca o vetor no fim).
    fun loadTrack(deck: Int, track: RbTrack, source: ReadableVolume, force: Boolean = false): kotlinx.coroutines.Job {
        if (settings.loadLock && !force && faces[deck].playing) {
            // Carregar num deck que toca derrubaria a música do público. O leitor de club recusa o mesmo gesto.
            return scope.launch { note = "Deck ${deck + 1} está tocando: pare para carregar (bloqueio de carga)." }
        }
        note = "Carregando ${track.title}…"
        return scope.launch {
            val analysis = liveCatalog.value?.analyses?.get(track.id)
            val hot = FloatArray(8) { -1f }
            if (settings.hotCueAutoLoad) {
                analysis?.hotCues?.forEach { cue ->
                    if (cue.index in hot.indices) hot[cue.index] = cue.timeMs / 1000f
                }
            }
            val loop = activeLoop(analysis)
            var detail: os.deckbridge.logic.RbAnalysis? = null
            val loadedPcm = withContext(Dispatchers.Default) {
                val bytes = source.read(track.filePath)
                if (bytes == null) {
                    null to "Áudio ausente para ${track.title}."
                } else {
                    val decoded = host.decodeAudio(track.fileName.ifBlank { track.filePath }, bytes)
                    if (decoded == null) {
                        null to "Não decodifiquei ${track.fileName}. Nesta versão o deck aceita WAV, MP3, FLAC e AAC."
                    } else {
                        NativeEngine.loadDeck(deck, decoded, track.bpm.toFloat(), (analysis?.mainCueMs ?: 0) / 1000f, hot, -1f, -1f)
                        if (analysis != null && analysis.lacksDetail() && track.id !in detailWaves) {
                            // O catálogo do app não guarda o detalhe (ver loadRekordbox): primeiro o ANLZ da faixa, que
                            // é a onda do rekordbox; sem ANLZ, a onda sai do PCM (canal esquerdo basta).
                            val fromUsb = if (track.analyzePath.isNotBlank()) os.deckbridge.logic.loadTrackDetail(source, track) else null
                            val filled = if (fromUsb != null && !fromUsb.lacksDetail()) {
                                fromUsb
                            } else {
                                val left = if (decoded.left.size == decoded.frames) decoded.left else decoded.left.copyOf(decoded.frames)
                                analysis.withDetail(os.deckbridge.logic.wavesFromPcm(left, decoded.sampleRate))
                            }
                            detail = filled
                        }
                        // Só o aviso de faixa cortada sai daqui: o PCM em Java (127 MB numa faixa de 6 min) já está no
                        // motor e fica livre para o GC agora, não no fim da carga.
                        decoded.truncated to ""
                    }
                }
            }
            // De volta ao contexto da tela (sem depender do "Main" de cada plataforma): só o detalhe das faixas que
            // estão nos decks fica na memória.
            detail?.let { filled ->
                val keep = liveLoaded.value.mapNotNull { it?.id }.toSet() + track.id
                detailWaves.keys.retainAll(keep)
                detailWaves[track.id] = filled
            }
            val truncated = loadedPcm.first
            if (truncated == null) {
                note = loadedPcm.second
                return@launch
            }
            onLoaded(deck, track)
            rememberLoaded(track)
            val cueSec = if (settings.autoCue) (analysis?.mainCueMs ?: 0) / 1000f else 0f
            if (cueSec > 0f) NativeEngine.seek(deck, cueSec)
            val savedGrid = gridEdits[track.id] ?: os.deckbridge.logic.GridEdit()
            edit(deck) {
                it.copy(
                    playing = false,
                    position = cueSec,
                    loopStart = -1f,
                    loopEnd = -1f,
                    memoryStart = (loop?.first ?: -1) / 1000f,
                    memoryEnd = (loop?.second ?: -1) / 1000f,
                    keyLock = settings.keyLock,
                    keyShift = 0,
                    slip = false,
                    cueBank = 0,
                    emergency = false,
                    reversed = false,
                    activeLoopArmed = loop != null,
                    gridShiftMs = savedGrid.shiftMs,
                    gridStretch = savedGrid.stretch,
                )
            }
            NativeEngine.setKeyShift(deck, 0)
            NativeEngine.setSlip(deck, false)
            NativeEngine.setReverse(deck, false)
            val loopText = if (loop == null) "" else " Loop armado em ${loop.first}–${loop.second} ms. Entra quando a cabeça chegar."
            note = "Carregada no deck ${deck + 1}: ${track.title}.$loopText"
            pushMix()
            if (truncated) note = "$note Faixa longa: só os primeiros $MAX_TRACK_MINUTES minutos foram carregados."
        }
    }

    fun placeNow(): BrowsePlace = browse ?: stored

    fun rowsOf(place: BrowsePlace): List<os.deckbridge.logic.BrowseRow> {
        val catalog = liveCatalog.value ?: return emptyList()
        val masterTrack = liveLoaded.value.getOrNull(master)
        return browseRows(catalog, place, history, masterTrack, settings.gentleSuggest)
    }

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
        val face = faces.getOrElse(deck) { DeckFace() }
        val beforeMemory = face.memoryEnd > face.memoryStart && clamped < face.memoryStart
        if (beforeMemory) NativeEngine.setLoop(deck, -1f, -1f)
        edit(deck) {
            it.copy(
                position = clamped,
                activeLoopArmed = if (beforeMemory) true else it.activeLoopArmed,
                loopStart = if (beforeMemory) -1f else it.loopStart,
                loopEnd = if (beforeMemory) -1f else it.loopEnd,
            )
        }
    }

    fun hotCue(deck: Int, index: Int) {
        val bank = faces[deck].cueBank
        val track = liveLoaded.value.getOrNull(deck)
        val analysis = track?.let { liveCatalog.value?.analyses?.get(it.id) }
        val export = analysis?.hotCueBanks.orEmpty()
        if (bank < export.size) {
            val cue = export[bank].firstOrNull { it.index == index } ?: return
            seekTo(deck, cue.timeMs / 1000f)
            if (faces[deck].slip) NativeEngine.hotCue(deck, index)
            return
        }
        val local = (bank - export.size).coerceAtLeast(0)
        if (export.isNotEmpty() || bank != 0) {
            val slot = local.coerceIn(0, 7)
            val stored = cueBanks[deck][slot][index]
            if (stored >= 0f) {
                seekTo(deck, stored)
            } else if (export.isEmpty()) {
                val at = faces[deck].position
                cueBanks[deck][slot][index] = at
                seekTo(deck, at)
            }
            return
        }
        val cue = analysis?.hotCues?.firstOrNull { it.index == index }
        if (cue != null && quantize) {
            val edit = GridEdit(faces[deck].gridShiftMs, faces[deck].gridStretch)
            val seconds = quantizeSeconds(cue.timeMs / 1000.0, applyGrid(analysis.beats, edit), gridBpm(track.bpm, edit))
            seekTo(deck, seconds.toFloat())
        } else {
            NativeEngine.hotCue(deck, index)
            if (cue != null) edit(deck) { it.copy(position = cue.timeMs / 1000f) }
        }
    }

    val reverseHoldWasSlip = remember { BooleanArray(2) }
    val jogBendUntil = remember { LongArray(2) }
    val scratching = remember { BooleanArray(2) }

    fun deckAction(deck: Int, action: String) {
        val face = faces.getOrElse(deck) { DeckFace() }
        val track = loaded.getOrNull(deck)
        when (action) {
            "KEY-", "KEY+" -> {
                val next = (face.keyShift + if (action == "KEY+") 1 else -1).coerceIn(-12, 12)
                edit(deck) { it.copy(keyShift = next, keyLock = true) }
                NativeEngine.setKeyLock(deck, true)
                NativeEngine.setKeyShift(deck, next)
            }
            "REV" -> {
                val next = !face.reversed
                edit(deck) { it.copy(reversed = next) }
                NativeEngine.setReverse(deck, next)
            }
            "G-", "G+" -> {
                val delta = if (action == "G+") 10.0 else -10.0
                val next = (face.gridShiftMs + delta).coerceIn(-2000.0, 2000.0)
                edit(deck) { it.copy(gridShiftMs = next) }
                track?.let { gridEdits = gridEdits + (it.id to GridEdit(next, face.gridStretch)) }
            }
            "S-", "S+" -> {
                val factor = if (action == "S+") 1.001 else 0.999
                val next = (face.gridStretch * factor).coerceIn(0.9, 1.1)
                edit(deck) { it.copy(gridStretch = next) }
                track?.let { gridEdits = gridEdits + (it.id to GridEdit(face.gridShiftMs, next)) }
            }
            "G0" -> {
                edit(deck) { it.copy(gridShiftMs = 0.0, gridStretch = 1.0) }
                track?.let { gridEdits = gridEdits + (it.id to GridEdit()) }
            }
            "KEY0" -> {
                edit(deck) { it.copy(keyShift = 0) }
                NativeEngine.setKeyShift(deck, 0)
            }
            "SYNC" -> {
                val masterKey = loaded.getOrNull(master)?.keyName.orEmpty()
                val shift = keySyncShift(track?.keyName.orEmpty(), masterKey)
                edit(deck) { it.copy(keyShift = shift, keyLock = true) }
                NativeEngine.setKeyLock(deck, true)
                NativeEngine.setKeyShift(deck, shift)
            }
            "SLIP" -> {
                val next = !face.slip
                NativeEngine.setSlip(deck, next)
                edit(deck) { current ->
                    if (next) current.copy(slip = true) else current.copy(slip = false, loopStart = -1f, loopEnd = -1f)
                }
            }
            "EMERG" -> {
                if (track == null || track.bpm <= 1.0) return
                val near = emergencyLoopRange(face.position.toDouble(), track.durationSeconds.toDouble(), track.bpm, false)
                val range = near ?: (face.position to face.position + (4.0 * 60.0 / track.bpm).toFloat())
                NativeEngine.setLoop(deck, range.first, range.second)
                edit(deck) { it.copy(loopStart = range.first, loopEnd = range.second, emergency = true) }
            }
            "BANK" -> edit(deck) { it.copy(cueBank = nextCueBank(it.cueBank)) }
            "JUMPSIZE" -> edit(deck) { it.copy(jumpBeats = nextJumpSize(it.jumpBeats)) }
        }
    }

    fun applyRange(deck: Int, range: Double, fader: Float) {
        PerformanceHub.tempo?.setRange(deck, range)
        val pitch = pitchFromFader(fader.toDouble(), range).toFloat()
        setPitch(deck, pitch)
        edit(deck) { it.copy(range = range, fader = fader, pitch = pitch, sync = false) }
    }

    fun toggleRecord() {
        if (recording) {
            aacWriter?.invoke()
            aacWriter = null
            NativeEngine.recordStop()
            recording = false
            recLevel = 0f
            // Lista das faixas do set, ao lado da gravação: .cue (players e programas de mix) e .txt (capítulos).
            val entries = tracklist.entries.toList()
            val stamp = recStamp
            if (entries.isNotEmpty() && stamp.isNotEmpty()) {
                val dir = java.io.File(host.filesDir, "recordings")
                val audioName = if (settings.recordAac) "set-$stamp.m4a" else "set-$stamp.wav"
                runCatching {
                    java.io.File(dir, "set-$stamp.cue").writeText(cueSheet(audioName, "Set $stamp", entries))
                    java.io.File(dir, "set-$stamp.txt").writeText(tracklistText(entries))
                }
                note = "Set gravado: ${entries.size} faixa(s) na lista (arquivos .cue e .txt ao lado da gravação)."
            }
            return
        }
        val dir = java.io.File(host.filesDir, "recordings").apply { mkdirs() }
        val warn = storageWarning(dir.usableSpace)
        if (warn != null) {
            recWarn = warn
            return
        }
        recWarn = null
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val wav = java.io.File(dir, "set-$stamp.wav")
        if (!NativeEngine.recordStart(wav.absolutePath, settings.recordAac)) {
            recWarn = "Motor ausente nesta sessão."
            return
        }
        recording = true
        recClock = "00:00"
        recStamp = stamp
        recStartMs = System.currentTimeMillis()
        tracklist.reset()
        if (settings.recordAac) {
            aacWriter = host.startAac(java.io.File(dir, "set-$stamp.m4a"), 48000) {
                val buffer = FloatArray(1024)
                val frames = NativeEngine.recordPull(buffer)
                if (frames <= 0) FloatArray(0) else buffer.copyOf(frames * 2)
            }
        }
    }

    fun persist(next: PlayerSettings) {
        settings = next
        quantize = next.quantize
        scope.launch { host.saveSettings(next) }
        NativeEngine.setBuffer(next.bufferFrames)
    }

    fun jumpBy(deck: Int, beats: Double) {
        val track = liveLoaded.value.getOrNull(deck) ?: return
        val face = faces[deck]
        val loopOn = face.loopEnd > face.loopStart
        val seconds = jumpSeconds(
            face.position.toDouble(),
            beats,
            gridBpm(track.bpm, GridEdit(face.gridShiftMs, face.gridStretch)),
            track.sampleRate,
            quantize,
            settings.quantizeBeats,
            if (loopOn) face.loopStart.toDouble() else -1.0,
            if (loopOn) face.loopEnd.toDouble() else -1.0,
            track.durationSeconds.toDouble(),
        )
        seekTo(deck, seconds.toFloat())
        edit(deck) { it.copy(lastJump = beats, jumpBeats = kotlin.math.abs(beats).toInt().takeIf { size -> size > 0 } ?: it.jumpBeats) }
    }

    // Grade do deck: origem do compasso 1 em segundos e BPM da grade, com os ajustes G+/G- já aplicados.
    fun gridOf(deck: Int): Pair<Double, Double>? {
        val track = liveLoaded.value.getOrNull(deck) ?: return null
        val face = faces[deck]
        val edit = GridEdit(face.gridShiftMs, face.gridStretch)
        val beats = applyGrid(liveCatalog.value?.analyses?.get(track.id)?.beats.orEmpty(), edit)
        val bpm = gridBpm(track.bpm, edit)
        if (bpm <= 1.0) return null
        val origin = if (beats.isEmpty()) {
            0.0
        } else {
            val first = beats.first()
            first.timeMs / 1000.0 - (first.beatInBar.coerceIn(1, 4) - 1) * (60.0 / bpm)
        }
        return origin to bpm
    }

    // SYNC: iguala o tempo ao do deck líder (o master, ou o outro se este for o master), alinha a fase da
    // batida quando os dois tocam com quantize ligado e passa a seguir o líder. Apertar de novo solta.
    fun syncDeck(deck: Int) {
        if (deck !in 0..1) return
        if (faces[deck].sync) {
            edit(deck) { it.copy(sync = false) }
            return
        }
        val lead = if (master != deck) master else 1 - deck
        val own = liveLoaded.value.getOrNull(deck)?.bpm ?: return
        val other = liveLoaded.value.getOrNull(lead)?.bpm ?: return
        val leadPitch = if (lead == 0) pitchA.toDouble() else pitchB.toDouble()
        val synced = syncPitch(own, other, leadPitch) ?: return
        val range = faces[deck].range
        setPitch(deck, synced.toFloat())
        edit(deck) { it.copy(pitch = synced.toFloat(), sync = true, fader = faderFromPitch(synced, range).toFloat()) }
        if (quantize && faces[deck].playing && faces[lead].playing) {
            val ownGrid = gridOf(deck)
            val leadGrid = gridOf(lead)
            if (ownGrid != null && leadGrid != null) {
                val ownSec = NativeEngine.position(deck).toDouble()
                val shift = syncPhaseShiftSeconds(
                    ownSec, ownGrid.first, ownGrid.second,
                    NativeEngine.position(lead).toDouble(), leadGrid.first, leadGrid.second,
                )
                if (kotlin.math.abs(shift) > 0.002) NativeEngine.seek(deck, (ownSec + shift).toFloat())
            }
        }
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
            PerformanceAction.Kind.EQ -> if (deck in 0..1 && action.hotIndex in 0..2) {
                val level = action.slider!!.toFloat()
                eqKnobs = eqKnobs.mapIndexed { index, row ->
                    if (index == deck) row.mapIndexed { band, value -> if (band == action.hotIndex) level else value } else row
                }
                NativeEngine.setEq(deck, eqKnobs[deck][0], eqKnobs[deck][1], eqKnobs[deck][2])
            }
            PerformanceAction.Kind.TRIM -> if (deck in 0..1) {
                val level = action.slider!!.toFloat()
                trimKnobs = trimKnobs.mapIndexed { index, value -> if (index == deck) level else value }
                NativeEngine.setTrimKnob(deck, level)
            }
            PerformanceAction.Kind.START -> if (deck in 0..1) {
                seekTo(deck, 0f)
                edit(deck) { it.copy(playing = true) }
                NativeEngine.transport(deck, true)
            }
            PerformanceAction.Kind.PFL -> if (deck in 0..1) {
                if (deck == 0) pflA = !pflA else pflB = !pflB
            }
            PerformanceAction.Kind.HEAD_MIX -> {
                headMix = action.slider!!.toFloat()
                NativeEngine.setHead(headMix, headLevel)
            }
            PerformanceAction.Kind.MASTER_LEVEL -> {
                val level = (action.slider ?: DEFAULT_MASTER_LEVEL.toDouble()).toFloat().coerceIn(0f, 1f)
                NativeEngine.setMasterGain(masterGainLinear(level))
                persist(settings.copy(masterLevel = level))
            }
            PerformanceAction.Kind.HEAD_GAIN -> {
                headLevel = action.slider!!.toFloat()
                NativeEngine.setHead(headMix, headLevel)
            }
            PerformanceAction.Kind.PITCH -> {
                if (deck !in 0..1) return
                val range = PerformanceHub.tempo?.rangeOf(deck) ?: faces[deck].range
                val pitch = action.slider!!.toFloat()
                setPitch(deck, pitch)
                edit(deck) {
                    it.copy(pitch = pitch, range = range, fader = faderFromPitch(pitch.toDouble(), range).toFloat(), sync = false)
                }
            }
            PerformanceAction.Kind.JOG_TOUCH -> {
                if (deck !in 0..1) return
                // Modo vinil: tocar no topo do prato segura a faixa e o giro faz scratch; soltar devolve o prato.
                val on = action.hotIndex == 1 && settings.jogVinyl
                scratching[deck] = on
                NativeEngine.scratchTouch(deck, on)
            }
            PerformanceAction.Kind.JOG -> {
                if (deck !in 0..1) return
                if (scratching[deck]) {
                    val seconds = jogSeconds((action.slider ?: 0.0) * settings.jogSensitivity).toFloat()
                    NativeEngine.scratchMove(deck, seconds)
                    edit(deck) { it.copy(position = (it.position + seconds).coerceAtLeast(0f)) }
                    return
                }
                if (faces[deck].loopAdjust != 0) {
                    val face = faces[deck]
                    val duration = liveLoaded.value.getOrNull(deck)?.durationSeconds?.toDouble() ?: 0.0
                    val moved = adjustLoopPoint(face.loopStart, face.loopEnd, face.loopAdjust, (action.slider ?: 0.0) * LOOP_ADJUST_STEP, duration)
                    if (moved == null) {
                        edit(deck) { it.copy(loopAdjust = 0) }
                    } else {
                        edit(deck) { it.copy(loopStart = moved.first, loopEnd = moved.second, memoryStart = moved.first, memoryEnd = moved.second) }
                        NativeEngine.setLoop(deck, moved.first, moved.second)
                        return
                    }
                }
                val bend = (action.slider ?: 0.0) * settings.jogSensitivity
                if (!settings.jogVinyl && kotlin.math.abs(bend) < 20.0) {
                    // Bend temporário: o tempo do fader (face.pitch) não muda, o motor volta a ele quando o prato para.
                    setPitch(deck, jogBentPitch(faces[deck].pitch, bend))
                    jogBendUntil[deck] = System.currentTimeMillis() + JOG_BEND_HOLD_MS
                } else {
                    val seconds = (jogSeconds(bend)).toFloat()
                    NativeEngine.nudge(deck, seconds)
                    edit(deck) { it.copy(position = (it.position + seconds).coerceAtLeast(0f)) }
                }
            }
            PerformanceAction.Kind.BROWSE_MOVE -> {
                val place = browse ?: return
                val sign = if (settings.encoderFlip) -1 else 1
                browse = moveBrowse(place, (action.slider ?: 0.0).roundToInt() * sign, rowsOf(place).size)
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
            PerformanceAction.Kind.BEAT_FX -> {
                if (rmx.panel && action.hotIndex == 8 && rmx.xOn && rmx.xMode == 2) {
                    rmx = rmx.copy(drumBuild = ((action.slider ?: 0.0) / 127.0).toFloat().coerceIn(0f, 1f))
                } else if (rmx.panel && action.hotIndex == 8) {
                    // O LEVEL/DEPTH da DDJ-400 é o knob do RMX: a velocidade do roll segurado ou a intensidade da cena.
                    val v = ((action.slider ?: 0.0) / 127.0).toFloat().coerceIn(0f, 1f)
                    val turned = rmxKnob(rmx, v)
                    rmx = if (turned == rmx) rmx.copy(intensity = v) else turned
                } else if (rmx.panel && action.hotIndex == 5) {
                    // O botão BEAT FX ON/OFF é o HOLD do RMX (como o do RMX-1000).
                    rmx = rmxToggleHold(rmx)
                } else if (rmx.panel && action.hotIndex == 3) {
                    val index = os.deckbridge.logic.SCENE_BARS.indexOf(rmx.bars).coerceAtLeast(0)
                    rmx = rmx.copy(bars = os.deckbridge.logic.SCENE_BARS[(index - 1).coerceAtLeast(0)])
                } else if (rmx.panel && action.hotIndex == 4) {
                    val index = os.deckbridge.logic.SCENE_BARS.indexOf(rmx.bars).coerceAtLeast(0)
                    rmx = rmx.copy(bars = os.deckbridge.logic.SCENE_BARS[(index + 1).coerceAtMost(os.deckbridge.logic.SCENE_BARS.lastIndex)])
                } else {
                    fx = reduceBeatFx(fx, action)
                }
            }
            PerformanceAction.Kind.FILTER -> {
                if (deck !in 0..1) return
                val value = ((action.slider ?: 0.5) * 127.0).roundToInt().coerceIn(0, 127)
                edit(deck) { it.copy(filter = value) }
                NativeEngine.setFilter(deck, value / 127f)
            }
            PerformanceAction.Kind.QUANTIZE -> {
                quantize = !quantize
                persist(settings.copy(quantize = !settings.quantize))
            }
            PerformanceAction.Kind.SYNC -> syncDeck(deck)
            PerformanceAction.Kind.MASTER -> if (deck in 0..1) master = deck
            PerformanceAction.Kind.BEAT_JUMP -> {
                if (deck !in 0..1) return
                val beats = if (action.hotIndex in 0..7) beatJumpPadBeats(action.hotIndex) * faces[deck].jumpScale else action.slider ?: return
                jumpBy(deck, beats)
            }
            PerformanceAction.Kind.BEAT_JUMP_PAGE -> {
                if (deck !in 0..1) return
                val next = stepJumpScale(faces[deck].jumpScale, action.hotIndex == 1)
                edit(deck) { it.copy(jumpScale = next) }
            }
            PerformanceAction.Kind.REVERSE_HOLD -> {
                if (deck !in 0..1) return
                if (action.hotIndex == 1) {
                    // Censor: slip ligado, reverso enquanto segura. Ao soltar, volta onde a faixa estaria.
                    reverseHoldWasSlip[deck] = faces[deck].slip
                    NativeEngine.setSlip(deck, true)
                    NativeEngine.setReverse(deck, true)
                    edit(deck) { it.copy(slip = true, reversed = true) }
                } else {
                    NativeEngine.setReverse(deck, false)
                    if (!reverseHoldWasSlip[deck]) NativeEngine.setSlip(deck, false)
                    edit(deck) { it.copy(reversed = false, slip = reverseHoldWasSlip[deck]) }
                }
            }
            PerformanceAction.Kind.LOOP_ADJUST -> {
                if (deck !in 0..1) return
                val face = faces[deck]
                val next = if (face.loopAdjust == action.hotIndex || face.loopEnd <= face.loopStart) 0 else action.hotIndex
                edit(deck) { it.copy(loopAdjust = next) }
            }
            PerformanceAction.Kind.LOOP_IN -> {
                if (deck !in 0..1) return
                edit(deck) { it.copy(loopStart = it.position, loopEnd = -1f) }
                NativeEngine.setLoop(deck, faces[deck].position, -1f)
            }
            PerformanceAction.Kind.LOOP_OUT -> {
                if (deck !in 0..1) return
                val start = faces[deck].loopStart
                val end = faces[deck].position
                if (end > start && start >= 0f) {
                    edit(deck) { it.copy(loopEnd = end, memoryStart = start, memoryEnd = end) }
                    NativeEngine.setLoop(deck, start, end)
                }
            }
            PerformanceAction.Kind.RELOOP -> {
                if (deck !in 0..1) return
                val face = faces[deck]
                if (face.loopEnd > face.loopStart) {
                    edit(deck) { it.copy(memoryStart = it.loopStart, memoryEnd = it.loopEnd, loopStart = -1f, loopEnd = -1f, playing = if (action.hotIndex == 1) false else it.playing) }
                    NativeEngine.setLoop(deck, -1f, -1f)
                    if (action.hotIndex == 1) NativeEngine.transport(deck, false)
                } else if (face.memoryEnd > face.memoryStart) {
                    edit(deck) { it.copy(loopStart = it.memoryStart, loopEnd = it.memoryEnd, position = it.memoryStart, playing = if (action.hotIndex == 1) false else it.playing) }
                    NativeEngine.setLoop(deck, face.memoryStart, face.memoryEnd)
                    NativeEngine.seek(deck, face.memoryStart)
                    if (action.hotIndex == 1) NativeEngine.transport(deck, false)
                }
            }
            PerformanceAction.Kind.LOOP_SCALE -> {
                if (deck !in 0..1) return
                val scaled = scaleLoop(faces[deck].loopStart, faces[deck].loopEnd, action.slider ?: 1.0) ?: return
                edit(deck) { it.copy(loopStart = scaled.first, loopEnd = scaled.second, memoryStart = scaled.first, memoryEnd = scaled.second) }
                NativeEngine.setLoop(deck, scaled.first, scaled.second)
            }
            PerformanceAction.Kind.BEAT_LOOP -> {
                if (deck !in 0..1) return
                val track = liveLoaded.value.getOrNull(deck) ?: return
                val face = faces[deck]
                val next = toggleBeatLoop(face.loopStart, face.loopEnd, face.position, action.slider ?: 4.0, track.bpm)
                if (next == null) {
                    edit(deck) { it.copy(memoryStart = it.loopStart, memoryEnd = it.loopEnd, loopStart = -1f, loopEnd = -1f) }
                    NativeEngine.setLoop(deck, -1f, -1f)
                } else {
                    edit(deck) { it.copy(loopStart = next.first, loopEnd = next.second, memoryStart = next.first, memoryEnd = next.second) }
                    NativeEngine.setLoop(deck, next.first, next.second)
                }
            }
            PerformanceAction.Kind.RMX -> if (rmx.panel) rmx = reduceRmxPad(rmx, action)
        }
        if (deck in 0..1) active = deck
        pushMix()
    }

    DisposableEffect(Unit) {
        PerformanceHub.onAction = { action -> onPerformance(action) }
        onDispose {
            PerformanceHub.onAction = null
            liveAac.value?.invoke()
            NativeEngine.recordStop()
            NativeEngine.transport(0, false)
            NativeEngine.transport(1, false)
        }
    }

    LaunchedEffect(pitchA, pitchB, volA, volB, cross, pflA, pflB) { pushMix() }

    // O roll do X-PAD acelera enquanto o dedo segura (ou o HOLD mantém): a cada compasso o TEMPO desce um passo, com os
    // golpes saindo no ritmo da música (1/2 → 1/4 → 1/8 → 1/16).
    LaunchedEffect(rmx.xOn, rmx.xHeld, rmx.xLatched, rmx.xMode) {
        while (rmxRiseLeft(liveRmx.value) > 0) {
            val bpm = (loaded.getOrNull(0)?.bpm ?: 120.0).coerceAtLeast(1.0)
            delay((4.0 * 60000.0 / bpm).toLong().coerceAtLeast(400L))
            val now = liveRmx.value
            if (rmxRiseLeft(now) > 0) rmx = rmxRiseStep(now)
        }
    }

    LaunchedEffect(rmx.holding) {
        if (rmx.holding) {
            val from = liveRmx.value.intensity
            val bpm = (loaded.getOrNull(0)?.bpm ?: 120.0).coerceAtLeast(1.0)
            val rampMs = (liveRmx.value.bars * 4.0 * 60000.0 / bpm).toLong().coerceAtLeast(300L)
            val startedAt = System.currentTimeMillis()
            while (liveRmx.value.holding && !liveRmx.value.manual) {
                val t = ((System.currentTimeMillis() - startedAt).toFloat() / rampMs.toFloat()).coerceIn(0f, 1f)
                rmx = liveRmx.value.copy(intensity = from + (1f - from) * t)
                if (t >= 1f) break
                delay(40)
            }
        } else if (liveRmx.value.intensity > 0.02f) {
            var level = liveRmx.value.intensity
            while (level > 0.02f && !liveRmx.value.holding) {
                level *= 0.8f
                rmx = liveRmx.value.copy(intensity = level, holding = false)
                delay(40)
            }
            if (!liveRmx.value.holding) rmx = liveRmx.value.copy(intensity = 0f, holding = false)
        }
    }

    LaunchedEffect(catalog?.usbWaveColor, catalog?.usbPlayhead, settings.followUsb, settings.waveColor, settings.playhead) {
        val next = resolveUsbSettings(settings, catalog?.usbWaveColor, catalog?.usbPlayhead)
        if (next != settings) persist(next)
    }

    LaunchedEffect(Unit) {
        host.settings().collect { incoming ->
            settings = incoming
            quantize = incoming.quantize
            zoom = listOf(zoomFromBars(incoming.zoomBars), zoomFromBars(incoming.zoomBars))
            NativeEngine.setBuffer(incoming.bufferFrames)
            NativeEngine.setMasterGain(masterGainLinear(incoming.masterLevel))
            NativeEngine.setKeyLock(0, incoming.keyLock)
            NativeEngine.setKeyLock(1, incoming.keyLock)
        }
    }

    LaunchedEffect(rmx, fx, pitchA, pitchB, master, loaded) {
        val deck = when (rmx.channel) {
            1 -> 1
            2 -> master
            else -> 0
        }
        val bpm = ((loaded.getOrNull(deck)?.bpm ?: 120.0) * (if (deck == 0) pitchA else pitchB)).toFloat()
        if (rmx.panel) {
            NativeEngine.setBeatFx(beatFxAudioKind(fx.effect), false, fx.level, fx.channel, bpm, beatFxBeats(fx.fractionLabel()).toFloat())
            NativeEngine.setRmx(rmxPayload(rmx, bpm, deck))
        } else {
            NativeEngine.setRmx(rmxPayload(rmx, bpm, deck))
            NativeEngine.setBeatFx(beatFxAudioKind(fx.effect), fx.on, fx.level, fx.channel, bpm, beatFxBeats(fx.fractionLabel()).toFloat())
        }
    }

    LaunchedEffect(loaded, gridEdits, faces, catalog) {
        for (deck in 0..1) {
            val track = loaded.getOrNull(deck) ?: continue
            val face = faces[deck]
            val edit = GridEdit(face.gridShiftMs, face.gridStretch)
            val beats = applyGrid(catalog?.analyses?.get(track.id)?.beats.orEmpty(), edit)
            val bpm = gridBpm(track.bpm, edit)
            val origin = if (beats.isEmpty() || bpm <= 1.0) {
                0.0
            } else {
                val first = beats.first()
                val period = 60.0 / bpm
                first.timeMs / 1000.0 - (first.beatInBar.coerceIn(1, 4) - 1) * period
            }
            NativeEngine.setGrid(deck, origin.toFloat(), bpm.toFloat())
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            clock = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            delay(15_000)
        }
    }

    LaunchedEffect(Unit) {
        historyRows = historyDb.rows()
        while (true) {
            delay(250)
            onAir.minSeconds = liveSettings.value.historySeconds
            val now = System.currentTimeMillis()
            val vols = liveVols.value
            for (deck in 0..1) {
                val track = liveLoaded.value.getOrNull(deck)
                val face = liveFaces.value.getOrElse(deck) { DeckFace() }
                val level = if (deck == 0) vols.first else vols.second
                val pitch = if (deck == 0) livePitch.value.first else livePitch.value.second
                if (face.playing && NativeEngine.running()) {
                    val pos = NativeEngine.position(deck)
                    val current = liveFaces.value.getOrElse(deck) { DeckFace() }
                    if (current.activeLoopArmed && arrivedAtActiveLoop(current.position, pos, current.memoryStart, current.memoryEnd)) {
                        NativeEngine.setLoop(deck, current.memoryStart, current.memoryEnd)
                        edit(deck) {
                            it.copy(
                                position = pos,
                                loopStart = it.memoryStart,
                                loopEnd = it.memoryEnd,
                                activeLoopArmed = false,
                            )
                        }
                    } else if (pos > 0.05f || current.reversed) {
                        edit(deck) { it.copy(position = pos) }
                    }
                    if (track != null && !current.emergency) {
                        val range = emergencyLoopRange(
                            if (pos > 0.05f) pos.toDouble() else current.position.toDouble(),
                            track.durationSeconds.toDouble(),
                            track.bpm,
                            current.loopEnd > current.loopStart,
                        )
                        if (range != null) {
                            NativeEngine.setLoop(deck, range.first, range.second)
                            edit(deck) { it.copy(loopStart = range.first, loopEnd = range.second, emergency = true, position = if (pos > 0.05f) pos else it.position) }
                        }
                    }
                }
                val sample = track?.let { onAirSample(it, pitch.toDouble()) }
                val row = onAir.tick(deck, face.playing, level > 0.2f, now, sample)
                if (row != null) {
                    val id = if (sessionId > 0) sessionId else historyDb.session(now).also { sessionId = it }
                    historyDb.insert(id, row)
                    historyRows = historyDb.rows()
                }
            }
            if (liveRecording.value) {
                val parts = NativeEngine.recordPoll().split(",")
                val frames = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val rate = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 } ?: 48000
                val peak = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val seconds = frames / rate
                recClock = "%02d:%02d".format(seconds / 60, seconds % 60)
                recLevel = (peak / 1000f).coerceIn(0f, 1f)
            }
        }
    }

    // Sessão: grava o que está nos decks a cada 4 s e restaura (parado) se o app reabrir com os mesmos arquivos.
    LaunchedEffect(Unit) {
        while (true) {
            delay(4_000)
            val tracks = liveLoaded.value
            if (tracks.any { it != null }) {
                host.saveSession(
                    (0..1).map { deck ->
                        tracks.getOrNull(deck)?.let { track ->
                            SessionDeck(track.filePath, NativeEngine.position(deck), faces[deck].pitch)
                        }
                    },
                )
            }
        }
    }
    var sessionRestored by remember { mutableStateOf(false) }
    LaunchedEffect(catalog, volume) {
        val source = volume ?: return@LaunchedEffect
        val library = catalog ?: return@LaunchedEffect
        if (sessionRestored || loaded.any { it != null }) return@LaunchedEffect
        sessionRestored = true
        val saved = host.readSession()
        var restoredAny = false
        saved.forEachIndexed { deck, item ->
            val track = item?.let { entry -> library.tracks.firstOrNull { it.filePath == entry.path } } ?: return@forEachIndexed
            loadTrack(deck, track, source, force = true).join()
            seekTo(deck, item.position)
            setPitch(deck, item.pitch)
            edit(deck) { it.copy(pitch = item.pitch, fader = faderFromPitch(item.pitch.toDouble(), it.range).toFloat()) }
            restoredAny = true
        }
        if (restoredAny) note = "Sessão anterior restaurada, parada no ponto em que estava. Aperte PLAY quando quiser."
    }

    // Marca as faixas do set enquanto grava (meio segundo por tick).
    LaunchedEffect(recording) {
        while (recording) {
            val elapsed = System.currentTimeMillis() - recStartMs
            tracklist.update(elapsed, faces.map { it.playing && it.onAir }, liveLoaded.value)
            delay(500)
        }
    }
    var controllerUp by remember { mutableStateOf(PerformanceHub.controllerConnected) }
    var audioWarning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            val now = System.currentTimeMillis()
            for (deck in 0..1) {
                if (jogBendUntil[deck] > 0L && now >= jogBendUntil[deck]) {
                    jogBendUntil[deck] = 0L
                    setPitch(deck, faces[deck].pitch)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        val watch = GlitchWatch()
        while (true) {
            controllerUp = PerformanceHub.controllerConnected
            audioWarning = watch.update(NativeEngine.glitches(), System.currentTimeMillis())
            delay(1_000)
        }
    }

    // Um só leitor dos picos do motor (o contador zera ao ler): alimenta os medidores da mesa e o VU da controladora.
    LaunchedEffect(Unit) {
        val raw = FloatArray(4)
        while (true) {
            NativeEngine.levels(raw)
            for (index in 0..3) peakHeld[index] = kotlin.math.max(raw[index], peakHeld[index] * 0.8f)
            if (overlay == "mixer") levels = peakHeld.map { os.deckbridge.logic.meterFraction(it) }
            delay(50)
        }
    }

    // LEDs da DDJ-400 e VU: só as mudanças, 20 vezes por segundo. Reenvia tudo quando a controladora reconecta.
    LaunchedEffect(Unit) {
        val diff = os.deckbridge.logic.LedDiff()
        var epoch = -1
        while (true) {
            if (epoch != PerformanceHub.ledEpoch) {
                epoch = PerformanceHub.ledEpoch
                diff.resend()
            }
            if (PerformanceHub.ledPort != null) {
                val peaks = peakHeld
                val state = os.deckbridge.logic.LedState(
                    decks = (0..1).map { deck ->
                        val face = faces[deck]
                        val track = loaded.getOrNull(deck)
                        val cues = track?.let { liveCatalog.value?.analyses?.get(it.id)?.hotCues }.orEmpty().map { it.index }.filter { it in 0..7 }.toMutableSet()
                        cueBanks[deck][0].forEachIndexed { index, value -> if (value >= 0f) cues += index }
                        val bpm = track?.bpm ?: 0.0
                        val loopBeats = if (face.loopEnd > face.loopStart && bpm > 1.0) (face.loopEnd - face.loopStart) * bpm / 60.0 else 0.0
                        os.deckbridge.logic.DeckLed(
                            loaded = track != null,
                            playing = face.playing,
                            sync = face.sync,
                            pfl = if (deck == 0) pflA else pflB,
                            hotCues = cues,
                            beatLoop = os.deckbridge.logic.beatLoopIndex(loopBeats),
                            peak = peaks[deck],
                        )
                    },
                    beatFxOn = fx.on && !rmx.panel,
                    samplerPads = os.deckbridge.logic.rmxSamplerPads(rmx),
                )
                diff.messages(state).forEach { PerformanceHub.sendMidi(it) }
            }
            delay(50)
        }
    }

    // Deck com SYNC ligado segue o tempo do líder enquanto o líder mexe no pitch.
    LaunchedEffect(pitchA, pitchB, master, faces[0].sync, faces[1].sync) {
        for (deck in 0..1) {
            if (!faces[deck].sync || deck == master) continue
            val own = loaded.getOrNull(deck)?.bpm ?: continue
            val other = loaded.getOrNull(master)?.bpm ?: continue
            val leadPitch = if (master == 0) pitchA else pitchB
            val target = syncPitch(own, other, leadPitch.toDouble()) ?: continue
            val current = if (deck == 0) pitchA else pitchB
            if (kotlin.math.abs(current - target.toFloat()) > 1e-5f) {
                setPitch(deck, target.toFloat())
                edit(deck) { it.copy(pitch = target.toFloat(), fader = faderFromPitch(target, it.range).toFloat()) }
            }
        }
    }

    LaunchedEffect(settings.colorFx) {
        NativeEngine.setColorKind(0, settings.colorFx)
        NativeEngine.setColorKind(1, settings.colorFx)
    }

    LaunchedEffect(settings.tempoRange) {
        applyRange(0, settings.tempoRange, faces[0].fader)
        applyRange(1, settings.tempoRange, faces[1].fader)
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
        NativeEngine.setConsole(console.copy(fxBands = fx.bands).toArray(settings.chFaderCurve, settings.crossCurve))
    }

    LaunchedEffect(previewId) {
        // O trecho para sozinho no fim; a borda da onda na lista some junto.
        while (previewId != null) {
            delay(300)
            if (!NativeEngine.previewPlaying()) previewId = null
        }
    }

    LaunchedEffect(console, settings.chFaderCurve, settings.crossCurve, fx.bands) {
        NativeEngine.setConsole(console.copy(fxBands = fx.bands).toArray(settings.chFaderCurve, settings.crossCurve))
    }

    LaunchedEffect(autoplay, catalog, volume) {
        if (!autoplay || started || catalog == null || volume == null) return@LaunchedEffect
        val first = catalog.tracks.getOrNull(0)
        val second = catalog.tracks.getOrNull(1)
        if (first != null) loadTrack(0, first, volume).join()
        if (second != null) loadTrack(1, second, volume).join()
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
            // Só recompõe quando alguma posição mudou: com os dois decks parados a tela fica quieta (antes eram 12,5
            // recomposições por segundo do DeckPage inteiro sem nada a mostrar).
            if (NativeEngine.running() && (faces.getOrNull(0)?.position != left || faces.getOrNull(1)?.position != right)) {
                faces = faces.mapIndexed { index, face ->
                    face.copy(position = if (index == 0) left else right)
                }
            }
            delay(80)
        }
    }

    PlayerScreen(
        mixer = os.deckbridge.logic.MixerState(
            eq = eqKnobs,
            trim = trimKnobs,
            volume = listOf(volA, volB),
            cross = cross,
            headMix = headMix,
            headLevel = headLevel,
            masterLevel = settings.masterLevel,
            filter = listOf(faces[0].filter, faces[1].filter),
            colorFx = settings.colorFx,
            levels = levels,
            pfl = listOf(pflA, pflB),
        ),
        onMixerKnob = { action -> onPerformance(action) },
        console = console,
        onConsole = { console = it },
        catalog = shownCatalog,
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
        warnings = buildList {
            if (!controllerUp && jvmBanner.isNullOrBlank()) add("SEM DDJ-400 (MIDI)")
            if (deviceId <= 0 && jvmBanner.isNullOrBlank()) add("SAÍDA: ${host.deviceName}, SEM USB")
            audioWarning?.let { add(it) }
        },
        onPage = onPage,
        onBrowse = { browse = stored },
        onBrowseClose = {
            browse?.let { stored = it }
            browse = null
            if (previewId != null) NativeEngine.previewStop()
            previewId = null
        },
        previewId = previewId,
        onPreview = { track, fraction ->
            val source = volume
            if (source != null) {
                scope.launch {
                    val start = fraction.toDouble() * track.durationSeconds.coerceAtLeast(0)
                    val pcm = withContext(Dispatchers.Default) {
                        source.read(track.filePath)?.let { bytes ->
                            host.decodeSegment(track.fileName.ifBlank { track.filePath }, bytes, start, PREVIEW_SECONDS)
                        }
                    }
                    if (pcm == null) {
                        note = "Pré-escuta: não decodifiquei ${track.title}."
                    } else {
                        NativeEngine.previewLoad(pcm)
                        previewId = track.id
                        note = "PRÉ-ESCUTA no fone: ${track.title} a partir de %d:%02d.".format(start.toInt() / 60, start.toInt() % 60)
                    }
                }
            }
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
        onHotUp = { deck, _ -> if (faces.getOrElse(deck) { DeckFace() }.slip) NativeEngine.hotCueRelease(deck) },
        onSync = ::syncDeck,
        onMaster = { master = it },
        onKey = { deck ->
            val next = !faces[deck].keyLock
            edit(deck) { it.copy(keyLock = next) }
            NativeEngine.setKeyLock(deck, next)
        },
        onRemain = { deck -> edit(deck) { it.copy(remain = !it.remain) } },
        onRange = { deck -> applyRange(deck, nextTempoRange(faces[deck].range), faces[deck].fader) },
        onFader = { deck, position ->
            val pitch = pitchFromFader(position.toDouble(), faces[deck].range).toFloat()
            setPitch(deck, pitch)
            edit(deck) { it.copy(fader = position, pitch = pitch, sync = false) }
        },
        onScratch = { deck, seconds ->
            if (seconds == 0f && faces.getOrElse(deck) { DeckFace() }.slip) {
                NativeEngine.nudge(deck, 0f)
            } else {
                NativeEngine.nudge(deck, seconds)
                edit(deck) { it.copy(position = (it.position + seconds).coerceAtLeast(0f)) }
            }
        },
        onJump = { deck, beats -> jumpBy(deck, beats.toDouble()) },
        onBeatLoop = { deck, beats ->
            onPerformance(PerformanceAction(deck, PerformanceAction.Kind.BEAT_LOOP, slider = beats))
        },
        onLoopButton = { deck, which ->
            when (which) {
                "IN" -> onPerformance(PerformanceAction(deck, PerformanceAction.Kind.LOOP_IN))
                "OUT" -> onPerformance(PerformanceAction(deck, PerformanceAction.Kind.LOOP_OUT))
                "HALF" -> onPerformance(PerformanceAction(deck, PerformanceAction.Kind.LOOP_SCALE, slider = 0.5))
                "DOUBLE" -> onPerformance(PerformanceAction(deck, PerformanceAction.Kind.LOOP_SCALE, slider = 2.0))
                "RELOOP" -> onPerformance(PerformanceAction(deck, PerformanceAction.Kind.RELOOP))
                "4" -> onPerformance(PerformanceAction(deck, PerformanceAction.Kind.BEAT_LOOP, slider = 4.0))
            }
        },
        onQuantize = {
            val next = !quantize
            quantize = next
            persist(settings.copy(quantize = next))
        },
        onAuthorize = onAuthorize,
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
            if (category == BrowseCategory.HISTORY) {
                browse = null
                overlay = "history"
            } else {
                browse = openCategory(browse ?: stored, category)
            }
        },
        onQuery = { text ->
            queryText = text
            val place = browse
            if (place != null) browse = place.copy(query = text, index = 0)
        },
        onActive = { active = it },
        onFxChange = { fx = it },
        onOpenCategory = { category -> browse = openCategory(stored, category) },
        onSeek = { deck, seconds -> seekTo(deck, seconds) },
        queryText = queryText,
        nav = nav,
        overlay = overlay,
        onOverlay = { next ->
            overlay = next
            if (next.isNotEmpty()) browse = null
        },
        settings = settings,
        onSettings = { persist(it) },
        rmx = rmx,
        onRmx = { rmx = it },
        fxList = fxList,
        tagNames = mergedTagNames(catalog?.trackTags.orEmpty(), addedTags, removedTags),
        onTag = { id, name, add ->
            if (add) {
                addedTags = addedTags + (id to (addedTags[id].orEmpty() + name))
                removedTags = removedTags + (id to (removedTags[id].orEmpty() - name))
            } else {
                removedTags = removedTags + (id to (removedTags[id].orEmpty() + name))
                addedTags = addedTags + (id to (addedTags[id].orEmpty() - name))
            }
        },
        midiLine = if (note.isBlank()) "DDJ-400: sem aparelho nesta sessão." else note,
        onDiagnostics = { onPage(0) },
        onTestTone = { route -> scope.launch(kotlinx.coroutines.Dispatchers.IO) { NativeEngine.start(deviceId, route) } },
        searchBpm = searchBpm,
        onSearchBpm = { min, max -> searchBpm = min to max },
        searchKey = searchKey,
        onSearchKey = { searchKey = it },
        tagTrack = tagTrack ?: loaded.firstOrNull { it != null }?.id,
        onTagTrack = { tagTrack = it },
        recording = recording,
        recClock = recClock,
        recLevel = recLevel,
        recWarn = recWarn,
        onRecord = { toggleRecord() },
        historyRows = historyRows,
        onHistoryExport = { text ->
            exportText(if (text.startsWith("#EXTM3U")) "deckbridge-set.m3u8" else "deckbridge-set.csv", text)
        },
        onLoad = { track, deck ->
            val source = volume
            if (source != null) {
                if (previewId != null) NativeEngine.previewStop()
                previewId = null
                loadTrack(deck, track, source)
                browse?.let { stored = it }
                browse = null
            }
        },
        artworkOf = { track ->
            val source = volume
            if (track == null || source == null) null else host.artwork(source, track)
        },
        onDeckAction = ::deckAction,
        onMatchKey = { key ->
            val place = browse
            if (place != null) browse = place.copy(matchKey = key, index = 0)
        },
        onSort = { sort ->
            val place = browse
            if (place != null) browse = toggleListSort(place, sort)
        },
        onBpmWindow = {
            val place = browse
            if (place != null) {
                val bpm = loaded.getOrNull(master)?.bpm?.toInt() ?: 0
                browse = if (place.bpmMin == 0 && place.bpmMax >= 999 && bpm > 0) {
                    place.copy(bpmMin = (bpm - 6).coerceAtLeast(0), bpmMax = bpm + 6, index = 0)
                } else {
                    place.copy(bpmMin = 0, bpmMax = 999, index = 0)
                }
            }
        },
    )
}
