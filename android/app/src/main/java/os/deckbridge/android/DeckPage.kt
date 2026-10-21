package os.deckbridge.android

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import os.deckbridge.logic.classifyCapability
import os.deckbridge.logic.sideBySide
import os.deckbridge.logic.syncPitch
import os.deckbridge.logic.textSp

@Composable
fun DeckPage(
    catalog: RbCatalog?,
    volume: ReadableVolume?,
    deviceId: Int,
    autoplay: Boolean,
    loaded: List<RbTrack?>,
    onLoaded: (Int, RbTrack?) -> Unit,
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
        NativeEngine.loadDeck(deck, pcm.mono, pcm.sampleRate, track.bpm.toFloat(), (analysis?.mainCueMs ?: 0) / 1000f, hot)
        onLoaded(deck, track)
        if (deck == 0) playA = false else playB = false
        note = "Carregada no deck ${if (deck == 0) "A" else "B"}: ${track.title}."
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

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.toInt()
        val heightPx = with(density) { maxHeight.toPx() }.toInt()
        val size = textSp(widthPx).sp
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(note, fontSize = size)
            val channels = engine?.openedChannels ?: 0
            val requested = engine?.requestedChannels ?: 4
            val phones = if (engine == null) {
                "Abrindo o motor."
            } else if (engine?.phonesSent == true) {
                "Fone enviado nos canais 3–4."
            } else {
                "Fone não foi para os canais 3–4."
            }
            Text("Canais abertos $channels de $requested. $phones", fontSize = size)
            Text("Crossfader", fontSize = size)
            Slider(value = cross, onValueChange = { cross = it })
            val waves = classifyCapability(identity.cores, identity.ramMb).waveforms != "off"
            if (sideBySide(widthPx, heightPx)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeckColumn("A", 0, loaded[0], catalog, volume, playA, pitchA, volA, pflA, posA, waves, size, loaded[1]?.bpm, pitchB.toDouble(), Modifier.weight(1f), { playA = it }, { pitchA = it }, { volA = it }, { pflA = it }, ::loadTrack)
                    DeckColumn("B", 1, loaded[1], catalog, volume, playB, pitchB, volB, pflB, posB, waves, size, loaded[0]?.bpm, pitchA.toDouble(), Modifier.weight(1f), { playB = it }, { pitchB = it }, { volB = it }, { pflB = it }, ::loadTrack)
                }
            } else {
                DeckColumn("A", 0, loaded[0], catalog, volume, playA, pitchA, volA, pflA, posA, waves, size, loaded[1]?.bpm, pitchB.toDouble(), Modifier.fillMaxWidth(), { playA = it }, { pitchA = it }, { volA = it }, { pflA = it }, ::loadTrack)
                DeckColumn("B", 1, loaded[1], catalog, volume, playB, pitchB, volB, pflB, posB, waves, size, loaded[0]?.bpm, pitchA.toDouble(), Modifier.fillMaxWidth(), { playB = it }, { pitchB = it }, { volB = it }, { pflB = it }, ::loadTrack)
            }
        }
    }
}

@Composable
private fun DeckColumn(
    name: String,
    deck: Int,
    track: RbTrack?,
    catalog: RbCatalog?,
    volume: ReadableVolume?,
    playing: Boolean,
    pitch: Float,
    volumeLevel: Float,
    pfl: Boolean,
    position: Float,
    waves: Boolean,
    size: androidx.compose.ui.unit.TextUnit,
    otherBpm: Double?,
    otherPitch: Double,
    modifier: Modifier,
    onPlay: (Boolean) -> Unit,
    onPitch: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onPfl: (Boolean) -> Unit,
    onLoad: (Int, RbTrack, ReadableVolume) -> Unit,
) {
    val context = LocalContext.current
    var loose by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loose = readLoose(context, uri, deck)
    }
    val analysis = track?.let { catalog?.analyses?.get(it.id) }
    val bpm = (track?.bpm ?: 0.0) * pitch
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Deck $name", fontSize = size)
        Text(track?.title ?: "vazio", fontSize = size)
        Text(track?.let { "${it.artist} · ${it.keyName}" } ?: "sem faixa", fontSize = size)
        Text("BPM ${if (track == null) "—" else "%.1f".format(bpm)}", fontSize = size)
        Text("${clock(position)}  ${if (playing) "PLAY" else "PAUSE"}", fontSize = size)
        if (waves) Waveform(analysis, position, track?.durationSeconds ?: 0)
        else Text("Forma de onda desligada neste perfil.", fontSize = size)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val next = !playing
                onPlay(next)
                NativeEngine.transport(deck, next)
            }) { Text(if (playing) "Pausar" else "Play") }
            Button(onClick = {
                onPlay(false)
                NativeEngine.cue(deck)
            }) { Text("Cue") }
            Button(onClick = { onPfl(!pfl) }) { Text(if (pfl) "Fone on" else "Fone") }
        }
        Text("Pitch", fontSize = size)
        Slider(value = pitch, onValueChange = onPitch, valueRange = 0.5f..2f)
        Text("Volume", fontSize = size)
        Slider(value = volumeLevel, onValueChange = onVolume)
        Button(onClick = {
            val own = track?.bpm ?: return@Button
            val other = otherBpm ?: return@Button
            val synced = syncPitch(own, other, otherPitch) ?: return@Button
            onPitch(synced.toFloat())
        }) { Text("Sync BPM") }
        Text("Hot cues", fontSize = size)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (index in 0..3) HotButton(deck, index, analysis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (index in 4..7) HotButton(deck, index, analysis)
        }
        Button(onClick = { picker.launch(arrayOf("audio/wav", "audio/mpeg", "audio/*")) }) { Text("WAV/MP3 avulso") }
        if (loose.isNotBlank()) Text(loose, fontSize = size)
        if (volume != null && track == null && catalog != null) {
            Text("Use a biblioteca para carregar.", fontSize = size)
        }
    }
}

@Composable
private fun HotButton(deck: Int, index: Int, analysis: RbAnalysis?) {
    val cue = analysis?.hotCues?.firstOrNull { it.index == index }
    Button(onClick = { NativeEngine.hotCue(deck, index) }, enabled = cue != null) {
        Text(if (cue == null) "${index + 1}" else "${index + 1} ${cue.comment.ifBlank { "${cue.timeMs}ms" }}")
    }
}

@Composable
private fun Waveform(analysis: RbAnalysis?, position: Float, duration: Int) {
    val data = analysis?.waveform ?: ByteArray(0)
    Canvas(Modifier.fillMaxWidth().height(42.dp)) {
        if (data.isEmpty()) return@Canvas
        val step = size.width / data.size
        data.forEachIndexed { index, byte ->
            val height = (byte.toInt() and 0xFF) / 255f * size.height
            val x = index * step
            drawLine(Color(0xFF3DDCFF), Offset(x, size.height), Offset(x, size.height - height), strokeWidth = step.coerceAtLeast(1f))
        }
        if (duration > 0) {
            val x = (position / duration).coerceIn(0f, 1f) * size.width
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
    }
}

private fun clock(seconds: Float): String {
    val whole = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(whole / 60, whole % 60)
}

private fun readLoose(context: android.content.Context, uri: Uri, deck: Int): String {
    val name = displayName(context, uri)
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "Não li o arquivo."
    val pcm = decodeTrackAudio(context, name, bytes) ?: return "Só WAV e MP3 neste player."
    NativeEngine.loadDeck(deck, pcm.mono, pcm.sampleRate, 0f, 0f, FloatArray(8) { -1f })
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
