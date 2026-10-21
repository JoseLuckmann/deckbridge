package os.deckbridge.android

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import os.deckbridge.logic.EngineStats
import os.deckbridge.logic.PerformanceAction
import os.deckbridge.logic.RbAnalysis
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.ReadableVolume
import os.deckbridge.logic.activeLoop
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.inkOn
import os.deckbridge.logic.sideBySide
import os.deckbridge.logic.syncPitch

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
) {
    val context = LocalContext.current
    val identity = remember { readIdentity(context) }
    var pitchA by remember { mutableFloatStateOf(1f) }
    var pitchB by remember { mutableFloatStateOf(1f) }
    var volA by remember { mutableFloatStateOf(0.8f) }
    var volB by remember { mutableFloatStateOf(0.8f) }
    var cross by remember { mutableFloatStateOf(0.5f) }
    var pflA by remember { mutableStateOf(false) }
    var pflB by remember { mutableStateOf(false) }
    var playA by remember { mutableStateOf(false) }
    var playB by remember { mutableStateOf(false) }
    var posA by remember { mutableFloatStateOf(0f) }
    var posB by remember { mutableFloatStateOf(0f) }
    var note by remember { mutableStateOf("Dois decks. O fone usa os canais 3–4 e não cai no master.") }
    var loose by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf<EngineStats?>(null) }
    var started by remember { mutableStateOf(false) }
    var consumed by remember { mutableStateOf(mapOf<Int, Int>()) }

    fun pushMix() {
        NativeEngine.setMix(volA, volB, pitchA, pitchB, cross, pflA, pflB)
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
        val analysis = catalog?.analyses?.get(track.id)
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
        if (deck == 0) playA = false else playB = false
        val loopText = if (loop == null) "" else " Loop ${loop.first}–${loop.second} ms."
        note = "Carregada no deck ${if (deck == 0) "A" else "B"}: ${track.title}.$loopText"
        pushMix()
    }

    DisposableEffect(Unit) {
        PerformanceHub.onAction = { action ->
            when (action.kind) {
                PerformanceAction.Kind.PLAY -> if (action.deck == 0) {
                    playA = !playA
                    NativeEngine.transport(0, playA)
                } else {
                    playB = !playB
                    NativeEngine.transport(1, playB)
                }
                PerformanceAction.Kind.CUE -> {
                    if (action.deck == 0) playA = false else playB = false
                    NativeEngine.cue(action.deck)
                }
                PerformanceAction.Kind.HOT_CUE -> NativeEngine.hotCue(action.deck, action.hotIndex)
                PerformanceAction.Kind.VOLUME -> if (action.deck == 0) volA = action.slider!!.toFloat() else volB = action.slider!!.toFloat()
                PerformanceAction.Kind.CROSSFADER -> cross = action.slider!!.toFloat()
                PerformanceAction.Kind.PITCH -> if (action.deck == 0) pitchA = action.slider!!.toFloat() else pitchB = action.slider!!.toFloat()
                PerformanceAction.Kind.JOG -> Unit
            }
            pushMix()
        }
        onDispose {
            PerformanceHub.onAction = null
            NativeEngine.transport(0, false)
            NativeEngine.transport(1, false)
        }
    }

    LaunchedEffect(pitchA, pitchB, volA, volB, cross, pflA, pflB) { pushMix() }

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
        engine = withContext(Dispatchers.IO) { NativeEngine.start(deviceId, 3) }
    }

    LaunchedEffect(autoplay, catalog, volume, engine) {
        if (!autoplay || started || engine == null || catalog == null || volume == null) return@LaunchedEffect
        val first = catalog.tracks.getOrNull(0)
        val second = catalog.tracks.getOrNull(1)
        if (first != null) loadTrack(0, first, volume)
        if (second != null) loadTrack(1, second, volume)
        cross = 0.5f
        pflB = true
        pushMix()
        playA = true
        playB = true
        NativeEngine.transport(0, true)
        NativeEngine.transport(1, true)
        started = true
        note = "SIMULAÇÃO tocando as duas faixas. Pré-escuta do deck B pedida nos canais 3–4."
    }

    LaunchedEffect(Unit) {
        while (true) {
            posA = NativeEngine.position(0)
            posB = NativeEngine.position(1)
            delay(200)
        }
    }

    val channels = engine?.openedChannels ?: 0
    val requested = engine?.requestedChannels ?: 4
    val phones = if (engine == null) {
        "Abrindo o motor."
    } else if (engine?.phonesSent == true) {
        "Fone enviado nos canais 3–4."
    } else {
        "Fone não foi para os canais 3–4."
    }
    DeckScreen(
        catalog = catalog,
        loaded = loaded,
        note = note,
        channelsText = "Canais abertos $channels de $requested. $phones",
        playA = playA,
        playB = playB,
        pitchA = pitchA,
        pitchB = pitchB,
        volA = volA,
        volB = volB,
        cross = cross,
        pflA = pflA,
        pflB = pflB,
        posA = posA,
        posB = posB,
        waveforms = classifyWaveforms(identity.cores, identity.ramMb),
        loose = loose,
        jvmBanner = jvmBanner,
        onPage = onPage,
        onPlay = { deck, playing ->
            if (deck == 0) playA = playing else playB = playing
            NativeEngine.transport(deck, playing)
        },
        onCue = { deck ->
            if (deck == 0) playA = false else playB = false
            NativeEngine.cue(deck)
        },
        onPitch = { deck, value -> if (deck == 0) pitchA = value else pitchB = value },
        onVolume = { deck, value -> if (deck == 0) volA = value else volB = value },
        onPfl = { deck, value -> if (deck == 0) pflA = value else pflB = value },
        onCross = { cross = it },
        onSync = { deck ->
            val own = loaded[deck]?.bpm ?: return@DeckScreen
            val other = loaded[1 - deck]?.bpm ?: return@DeckScreen
            val otherPitch = if (deck == 0) pitchB.toDouble() else pitchA.toDouble()
            val synced = syncPitch(own, other, otherPitch) ?: return@DeckScreen
            if (deck == 0) pitchA = synced.toFloat() else pitchB = synced.toFloat()
        },
        onHot = { deck, index -> NativeEngine.hotCue(deck, index) },
        onLoose = { deck, uri ->
            loose = readLoose(context, uri, deck)
        },
    )
}

private fun classifyWaveforms(cores: Int?, ramMb: Int?): Boolean {
    return os.deckbridge.logic.classifyCapability(cores, ramMb).waveforms != "off"
}

@Composable
fun DeckScreen(
    catalog: RbCatalog?,
    loaded: List<RbTrack?>,
    note: String,
    channelsText: String,
    playA: Boolean,
    playB: Boolean,
    pitchA: Float,
    pitchB: Float,
    volA: Float,
    volB: Float,
    cross: Float,
    pflA: Boolean,
    pflB: Boolean,
    posA: Float,
    posB: Float,
    waveforms: Boolean,
    loose: String,
    jvmBanner: String?,
    onPage: (Int) -> Unit = {},
    onPlay: (Int, Boolean) -> Unit,
    onCue: (Int) -> Unit,
    onPitch: (Int, Float) -> Unit,
    onVolume: (Int, Float) -> Unit,
    onPfl: (Int, Boolean) -> Unit,
    onCross: (Float) -> Unit,
    onSync: (Int) -> Unit,
    onHot: (Int, Int) -> Unit,
    onLoose: (Int, Uri) -> Unit,
) {
    val simulated = jvmBanner != null || note.contains("SIMULA")
    StageScaffold(page = 2, onPage = onPage, simulationNote = simulationNote(jvmBanner, simulated)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }.toInt()
            val heightPx = with(density) { maxHeight.toPx() }.toInt()
            val tall = heightPx >= 1000
            val bpmSize = if (tall) 56.sp else if (heightPx >= 700) 42.sp else 32.sp
            val titleSize = if (tall) 26.sp else if (heightPx >= 700) 20.sp else 16.sp
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextInk(
                        "A ${if (playA) "PLAY" else "PAUSE"}    B ${if (playB) "PLAY" else "PAUSE"}",
                        fontSize = if (tall) 16.sp else 13.sp,
                        weight = FontWeight.SemiBold,
                        mono = true,
                    )
                    Box(Modifier.weight(1f))
                    TextInk(channelsText, fontSize = 13.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
                }
                if (note.isNotBlank()) {
                    TextInk(note, fontSize = 13.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
                }
                if (sideBySide(widthPx, heightPx)) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeckColumn("A", 0, loaded[0], catalog, playA, pitchA, pflA, posA, waveforms, bpmSize, titleSize, Color(StagePalette.deckA), Modifier.weight(1f).fillMaxHeight(), onPlay, onCue, onPitch, onPfl, onSync, onHot, onLoose)
                        MixerColumn(volA, volB, cross, onVolume, onCross, Modifier.width(if (widthPx >= 1600) 200.dp else 148.dp).fillMaxHeight())
                        DeckColumn("B", 1, loaded[1], catalog, playB, pitchB, pflB, posB, waveforms, bpmSize, titleSize, Color(StagePalette.deckB), Modifier.weight(1f).fillMaxHeight(), onPlay, onCue, onPitch, onPfl, onSync, onHot, onLoose)
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeckColumn("A", 0, loaded[0], catalog, playA, pitchA, pflA, posA, waveforms, bpmSize, titleSize, Color(StagePalette.deckA), Modifier.fillMaxWidth().height(420.dp), onPlay, onCue, onPitch, onPfl, onSync, onHot, onLoose)
                        MixerColumn(volA, volB, cross, onVolume, onCross, Modifier.fillMaxWidth().height(220.dp))
                        DeckColumn("B", 1, loaded[1], catalog, playB, pitchB, pflB, posB, waveforms, bpmSize, titleSize, Color(StagePalette.deckB), Modifier.fillMaxWidth().height(420.dp), onPlay, onCue, onPitch, onPfl, onSync, onHot, onLoose)
                    }
                }
                if (loose.isNotBlank()) TextInk(loose, fontSize = 13.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun MixerColumn(
    volA: Float,
    volB: Float,
    cross: Float,
    onVolume: (Int, Float) -> Unit,
    onCross: (Float) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(StagePalette.panel))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextInk("MIXER", fontSize = 13.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VerticalFader("VOL A", volA, { onVolume(0, it) }, Color(StagePalette.deckA), Modifier.weight(1f).fillMaxHeight())
            VerticalFader("VOL B", volB, { onVolume(1, it) }, Color(StagePalette.deckB), Modifier.weight(1f).fillMaxHeight())
        }
        TextInk("Cross ${(cross * 100).toInt()}", fontSize = 13.sp, weight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Slider(
            value = cross,
            onValueChange = onCross,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            colors = sliderColors(),
        )
    }
}

@Composable
private fun DeckColumn(
    name: String,
    deck: Int,
    track: RbTrack?,
    catalog: RbCatalog?,
    playing: Boolean,
    pitch: Float,
    pfl: Boolean,
    position: Float,
    waves: Boolean,
    bpmSize: TextUnit,
    titleSize: TextUnit,
    accent: Color,
    modifier: Modifier,
    onPlay: (Int, Boolean) -> Unit,
    onCue: (Int) -> Unit,
    onPitch: (Int, Float) -> Unit,
    onPfl: (Int, Boolean) -> Unit,
    onSync: (Int) -> Unit,
    onHot: (Int, Int) -> Unit,
    onLoose: (Int, Uri) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onLoose(deck, uri)
    }
    val analysis = track?.let { catalog?.analyses?.get(it.id) }
    val bpm = (track?.bpm ?: 0.0) * pitch
    val loop = activeLoop(analysis)
    val tags = track?.let { catalog?.trackTags?.get(it.id).orEmpty() }.orEmpty()
    val duration = track?.durationSeconds ?: 0
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(StagePalette.panel))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(8.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            TextInk("DECK $name", fontSize = 13.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
            Box(Modifier.weight(1f))
            PlayChip(playing)
        }
        TextInk(track?.title ?: "Vazio", fontSize = titleSize, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        TextInk(track?.artist ?: "Sem faixa", fontSize = 14.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
            TextInk(if (track == null) "—.—" else "%05.1f".format(bpm), fontSize = bpmSize, weight = FontWeight.Bold, mono = true)
            TextInk(" BPM", fontSize = 14.sp, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            Box(Modifier.weight(1f))
            TextInk(track?.keyName ?: "—", fontSize = if (bpmSize.value >= 48f) 28.sp else 20.sp, weight = FontWeight.Bold, mono = true)
        }
        TextInk(
            "${clock(position)}  /  ${clock(duration.toFloat())}",
            fontSize = if (bpmSize.value >= 48f) 22.sp else 16.sp,
            weight = FontWeight.SemiBold,
            mono = true,
        )
        if (waves) {
            Waveform(analysis, position, duration, accent, Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp))
        } else {
            TextInk("Forma de onda desligada neste perfil.", fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
        if (loop != null || tags.isNotEmpty()) {
            val extra = buildList {
                if (loop != null) add("Loop ${clock(loop.first / 1000f)}–${clock(loop.second / 1000f)}")
                if (tags.isNotEmpty()) add(tags.joinToString(" · ") { if (it.category.isBlank()) it.name else it.name })
            }.joinToString("   ")
            TextInk(extra, fontSize = 13.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (index in 0..7) {
                HotPad(index, analysis, Modifier.weight(1f)) { onHot(deck, index) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInk("Pitch", fontSize = 13.sp, weight = FontWeight.Bold)
            Box(Modifier.weight(1f))
            TextInk(pitchLabel(pitch), fontSize = 16.sp, weight = FontWeight.Bold, mono = true)
        }
        Slider(
            value = pitch,
            onValueChange = { onPitch(deck, it) },
            valueRange = 0.5f..2f,
            modifier = Modifier.fillMaxWidth().height(28.dp),
            colors = sliderColors(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StageButton(if (playing) "Pausar" else "Play", { onPlay(deck, !playing) }, Modifier.weight(1f), fontSize = 13.sp)
            StageButton("Cue", { onCue(deck) }, Modifier.weight(1f), fontSize = 13.sp)
            StageButton("Sync", { onSync(deck) }, Modifier.weight(1f), fontSize = 13.sp)
            StageButton(if (pfl) "Fone on" else "Fone", { onPfl(deck, !pfl) }, Modifier.weight(1f), selected = pfl, fontSize = 13.sp)
            StageButton("Arquivo", { picker.launch(arrayOf("audio/wav", "audio/mpeg", "audio/*")) }, Modifier.weight(1f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun PlayChip(playing: Boolean) {
    val fill = if (playing) Color(StagePalette.playBg) else Color(StagePalette.pauseBg)
    val ink = if (playing) Color(StagePalette.playInk) else Color(StagePalette.ink)
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        TextInk(if (playing) "PLAY" else "PAUSE", color = ink, fontSize = 13.sp, weight = FontWeight.Bold, mono = true)
    }
}

@Composable
private fun HotPad(index: Int, analysis: RbAnalysis?, modifier: Modifier, onClick: () -> Unit) {
    val cue = analysis?.hotCues?.firstOrNull { it.index == index }
    val stored = cue?.colorRgb
    val fillRgb = stored ?: StagePalette.pads[index]
    val active = cue != null
    val fill = if (active) argb(fillRgb) else Color(StagePalette.panelRaised)
    val ink = if (active) argb(inkOn(fillRgb)) else Color(StagePalette.ink)
    val label = when {
        cue == null -> "${index + 1}"
        cue.comment.isBlank() -> "${index + 1}"
        cue.comment.length <= 5 -> cue.comment
        else -> "${index + 1}"
    }
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .border(2.dp, argb(fillRgb), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            TextInk(label, color = ink, fontSize = 12.sp, weight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun Waveform(analysis: RbAnalysis?, position: Float, duration: Int, accent: Color, modifier: Modifier) {
    val data = analysis?.waveform ?: ByteArray(0)
    val loop = activeLoop(analysis)
    Canvas(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(StagePalette.waveBg)),
    ) {
        val mid = size.height / 2f
        drawLine(Color(StagePalette.line), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        if (data.isNotEmpty()) {
            val step = size.width / data.size
            data.forEachIndexed { index, byte ->
                val amp = (byte.toInt() and 0xFF) / 255f * (size.height * 0.46f)
                val x = index * step + step / 2f
                drawLine(accent, Offset(x, mid - amp), Offset(x, mid + amp), strokeWidth = step.coerceAtLeast(1.5f))
            }
        }
        if (duration > 0 && loop != null) {
            val start = (loop.first / 1000f / duration).coerceIn(0f, 1f) * size.width
            val end = (loop.second / 1000f / duration).coerceIn(0f, 1f) * size.width
            drawRect(Color(0x553DDC97), Offset(start, 0f), Size((end - start).coerceAtLeast(2f), size.height))
        }
        analysis?.hotCues?.forEach { cue ->
            if (duration <= 0) return@forEach
            val x = (cue.timeMs / 1000f / duration).coerceIn(0f, 1f) * size.width
            val color = argb(cue.colorRgb ?: StagePalette.pads[cue.index.coerceIn(0, 7)])
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
        if (duration > 0) {
            val x = (position / duration).coerceIn(0f, 1f) * size.width
            drawLine(Color(StagePalette.ink), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Color(StagePalette.ink),
    activeTrackColor = Color(StagePalette.ink),
    inactiveTrackColor = Color(StagePalette.line),
)

private fun pitchLabel(pitch: Float): String {
    val percent = (pitch - 1f) * 100f
    val sign = if (percent > 0.05f) "+" else ""
    return "$sign${"%.1f".format(percent)}%"
}

private fun clock(seconds: Float): String {
    val whole = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(whole / 60, whole % 60)
}

private fun readLoose(context: android.content.Context, uri: Uri, deck: Int): String {
    val name = displayName(context, uri)
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "Não li o arquivo."
    val pcm = decodeTrackAudio(context, name, bytes) ?: return "Só WAV e MP3 neste player."
    NativeEngine.loadDeck(deck, pcm, 0f, 0f, FloatArray(8) { -1f }, -1f, -1f)
    return "Avulso no deck ${if (deck == 0) "A" else "B"}: $name. Sem hot cue."
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return it.getString(index) ?: "arquivo"
        }
    }
    return "arquivo"
}
