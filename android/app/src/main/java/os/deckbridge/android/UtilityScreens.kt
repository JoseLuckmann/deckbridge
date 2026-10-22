package os.deckbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.PlayerSettings
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.RmxState
import os.deckbridge.logic.SCENE_BARS
import os.deckbridge.logic.SCENE_NAMES
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.XBEAT_LABELS
import os.deckbridge.logic.XPAD_NAMES
import os.deckbridge.logic.filterTracks
import os.deckbridge.logic.tempoRangeLabel

@Composable
fun MenuScreen(
    settings: PlayerSettings,
    midiLine: String,
    onSettings: (PlayerSettings) -> Unit,
    onClose: () -> Unit,
    onDiagnostics: () -> Unit,
    onTestTone: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color(StagePalette.bg)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("MENU", fontSize = 22.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentBlue), modifier = Modifier.weight(1f))
            UtilityButton("FECHAR", onClose)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("PLAYER") {
                TextInk("Faixa de tempo", fontSize = 13.sp, color = Color(StagePalette.inkMuted))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.06 to "6", 0.10 to "10", 0.16 to "16", 0.25 to "WIDE").forEach { (value, label) ->
                        UtilityButton(label, { onSettings(settings.copy(tempoRange = value)) }, settings.tempoRange == value, Modifier.weight(1f))
                    }
                }
                ToggleRow("Master Tempo", "Key lock aproximado: o tempo muda e um deslocador de tom tenta devolver a afinação. Não é o algoritmo comercial.", settings.keyLock) {
                    onSettings(settings.copy(keyLock = !settings.keyLock))
                }
                ToggleRow("Quantize", "Snap na grade. O valor é a subdivisão.", settings.quantize) {
                    onSettings(settings.copy(quantize = !settings.quantize))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.25 to "1/16", 0.5 to "1/8", 1.0 to "1/4", 2.0 to "1/2", 4.0 to "1").forEach { (beats, label) ->
                        UtilityButton(label, { onSettings(settings.copy(quantizeBeats = beats, quantize = true)) }, settings.quantizeBeats == beats, Modifier.weight(1f))
                    }
                }
                ToggleRow("Auto cue", "Ao carregar, o deck vai para o cue principal.", settings.autoCue) { onSettings(settings.copy(autoCue = !settings.autoCue)) }
                ToggleRow("Hot cue auto load", "Carrega os hot cues da biblioteca.", settings.hotCueAutoLoad) { onSettings(settings.copy(hotCueAutoLoad = !settings.hotCueAutoLoad)) }
                TextInk("Jog", fontSize = 13.sp, color = Color(StagePalette.inkMuted))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UtilityButton("VINYL", { onSettings(settings.copy(jogVinyl = true)) }, settings.jogVinyl, Modifier.weight(1f))
                    UtilityButton("CDJ", { onSettings(settings.copy(jogVinyl = false)) }, !settings.jogVinyl, Modifier.weight(1f))
                }
                TextInk("A sensibilidade do prato é provisória até o teste na controladora.", fontSize = 12.sp, color = Color(StagePalette.inkMuted))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f to "BAIXA", 1f to "MÉDIA", 1.6f to "ALTA").forEach { (value, label) ->
                        UtilityButton(label, { onSettings(settings.copy(jogSensitivity = value)) }, settings.jogSensitivity == value, Modifier.weight(1f))
                    }
                }
                TextInk("Cor da onda", fontSize = 13.sp, color = Color(StagePalette.inkMuted))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("RGB" to "RGB", "THREE_BAND" to "3 BANDAS", "BLUE" to "AZUL").forEach { (value, label) ->
                        UtilityButton(label, { onSettings(settings.copy(waveColor = value)) }, settings.waveColor == value, Modifier.weight(1f))
                    }
                }
                TextInk("Zoom inicial", fontSize = 13.sp, color = Color(StagePalette.inkMuted))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 4, 8, 16).forEach { bars ->
                        UtilityButton("$bars", { onSettings(settings.copy(zoomBars = bars)) }, settings.zoomBars == bars, Modifier.weight(1f))
                    }
                }
                TextInk("Playhead", fontSize = 13.sp, color = Color(StagePalette.inkMuted))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.20f to "20%", 0.30f to "30%", 0.50f to "50%").forEach { (value, label) ->
                        UtilityButton(label, { onSettings(settings.copy(playhead = value)) }, settings.playhead == value, Modifier.weight(1f))
                    }
                }
            }
            Section("AUDIO") {
                TextInk("Master nos canais 1–2. Fone nos canais 3–4 quando o aparelho abre quatro canais.", fontSize = 14.sp)
                TextInk("Buffer pedido: ${settings.bufferFrames} frames. A latência real continua UNKNOWN até o aparelho.", fontSize = 13.sp, color = Color(StagePalette.inkMuted))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(128, 256, 512, 1024).forEach { frames ->
                        UtilityButton("$frames", { onSettings(settings.copy(bufferFrames = frames)) }, settings.bufferFrames == frames, Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UtilityButton("TOM MASTER", { onTestTone(1) }, false, Modifier.weight(1f))
                    UtilityButton("TOM FONE", { onTestTone(2) }, false, Modifier.weight(1f))
                    UtilityButton("MIXER", { onTestTone(3) }, false, Modifier.weight(1f))
                }
            }
            Section("CONTROLADORA") {
                TextInk(if (midiLine.isBlank()) "DDJ-400: sem aparelho nesta sessão." else midiLine, fontSize = 14.sp)
                ToggleRow("Encoder BROWSE", "Provisório. Inverte 1..63 / 65..127. O sentido real não foi medido.", settings.encoderFlip) {
                    onSettings(settings.copy(encoderFlip = !settings.encoderFlip))
                }
                TextInk("Mapeamento RMX — provisório até o teste na controladora", fontSize = 14.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentOrange))
                TextInk(
                    "O botão de modo dos pads não manda MIDI. Com RMX ligado na tela, o modo SAMPLER (notas 0x30–0x37) vira RMX: pads 1–4 seguram Scene FX, pads 5–8 disparam o X-Pad. SHIFT+SAMPLER continua eject/stop. LEVEL/DEPTH é a intensidade. Beat < > escolhe 1/2/4/8/16 compassos. CH SELECT escolhe o canal. O isolador é nesta tela: o mapa não tem SHIFT+EQ separado.",
                    fontSize = 13.sp,
                )
            }
            Section("BIBLIOTECA") {
                TextInk("Fonte USB só de leitura. Re-scan pede de novo a pasta. OneLibrary real não é aberto: o arquivo sintético não é um banco.", fontSize = 14.sp)
                TextInk("Faixa de tempo actual ${tempoRangeLabel(settings.tempoRange)}.", fontSize = 13.sp, color = Color(StagePalette.inkMuted))
            }
            Section("DIAGNÓSTICO") {
                UtilityButton("ABRIR DIAGNÓSTICO", onDiagnostics, modifier = Modifier.fillMaxWidth())
                TextInk("O relatório exportado descreve esta sessão. Não é medição do tablet.", fontSize = 13.sp, color = Color(StagePalette.inkMuted))
            }
            Section("SOBRE") {
                TextInk("DeckBridge 0.8.0", fontSize = 18.sp, weight = FontWeight.Bold)
                TextInk("Remix FX e Beat FX deste programa são DSP original. Não é firmware comercial.", fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun SearchScreen(
    tracks: List<RbTrack>,
    query: String,
    bpmMin: Int,
    bpmMax: Int,
    keyFilter: String,
    onQuery: (String) -> Unit,
    onBpm: (Int, Int) -> Unit,
    onKey: (String) -> Unit,
    onLoad: (RbTrack, Int) -> Unit,
    onClose: () -> Unit,
) {
    val rows = filterTracks(tracks, query, bpmMin, bpmMax, keyFilter)
    Column(Modifier.fillMaxSize().background(Color(StagePalette.bg)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("SEARCH", color = Color(StagePalette.accentBlue), fontSize = 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            UtilityButton("FECHAR", onClose)
        }
        TextInk(if (query.isBlank()) "Título ou artista" else query, fontSize = 22.sp, weight = FontWeight.Medium, modifier = Modifier.padding(vertical = 6.dp), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInk("BPM ${bpmMin.coerceAtLeast(0)}–$bpmMax", fontSize = 13.sp, modifier = Modifier.weight(1f))
            UtilityButton("−", { onBpm((bpmMin - 5).coerceAtLeast(0), bpmMax) })
            UtilityButton("+", { onBpm(bpmMin, (bpmMax + 5).coerceAtMost(999)) })
            UtilityButton("LARGO", { onBpm(0, 999) })
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("", "Am", "Em", "Gm", "8A", "4A").forEach { key ->
                UtilityButton(if (key.isEmpty()) "TOM" else key, { onKey(key) }, keyFilter == key)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp).verticalScroll(rememberScrollState())) {
            if (rows.isEmpty()) {
                TextInk("Nenhuma faixa com esse filtro.", fontSize = 16.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 12.dp))
            }
            rows.take(40).forEach { track ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        TextInk(track.title, fontSize = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
                        TextInk("${track.artist}   ${"%.1f".format(track.bpm)}   ${track.keyName}", fontSize = 12.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
                    }
                    UtilityButton("1", onClick = { onLoad(track, 0) })
                    Spacer(Modifier.width(6.dp))
                    UtilityButton("2", onClick = { onLoad(track, 1) })
                }
            }
        }
        Keyboard(query, onQuery)
    }
}

@Composable
fun TagScreen(
    tracks: List<RbTrack>,
    tags: Map<Int, List<String>>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    onTag: (Int, String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val names = tags.values.flatten().distinct().sorted()
    val selected = tracks.firstOrNull { it.id == selectedId } ?: tracks.firstOrNull()
    Column(Modifier.fillMaxSize().background(Color(StagePalette.bg)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("TAG LIST", color = Color(StagePalette.accentBlue), fontSize = 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            UtilityButton("FECHAR", onClose)
        }
        TextInk(selected?.let { "${it.title} · ${it.artist}" } ?: "Nenhuma faixa carregada.", fontSize = 16.sp, modifier = Modifier.padding(vertical = 6.dp), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UtilityButton("ADD ABERTURA", {
                selected?.let { onTag(it.id, "Abertura", true) }
            })
            UtilityButton("ADD PEAK", { selected?.let { onTag(it.id, "Peak", true) } })
            UtilityButton("REMOVER", {
                val name = selected?.let { tags[it.id].orEmpty().lastOrNull() }
                if (selected != null && name != null) onTag(selected.id, name, false)
            })
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (names.isEmpty()) TextInk("Sem tags. Adicione uma na faixa seleccionada.", fontSize = 14.sp, color = Color(StagePalette.inkMuted))
                names.forEach { name ->
                    val count = tags.count { name in it.value }
                    UtilityButton("$name  $count", {}, modifier = Modifier.fillMaxWidth())
                }
            }
            Column(Modifier.weight(0.6f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tracks.take(24).forEach { track ->
                    val mark = tags[track.id].orEmpty().joinToString(" ")
                    val on = track.id == selected?.id
                    Row(
                        Modifier.fillMaxWidth().border(1.dp, Color(if (on) StagePalette.accentBlue else StagePalette.line)).background(Color(if (on) 0xFF123E86.toInt() else StagePalette.panel)).clickable { onSelect(track.id) }.padding(8.dp),
                    ) {
                        TextInk(track.title, fontSize = 15.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                        TextInk(mark, fontSize = 12.sp, color = Color(StagePalette.accentOrange), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun RmxRail(
    rmx: RmxState,
    compact: Boolean,
    onRmx: (RmxState) -> Unit,
    onOpen: () -> Unit,
) {
    TextInk("REMIX FX", fontSize = if (compact) 10.sp else 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentOrange))
    TextInk(rmx.sceneName(), fontSize = if (compact) 13.sp else 16.sp, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    IntensityBar(rmx.intensity, Modifier.fillMaxWidth().height(if (compact) 10.dp else 14.dp).padding(top = 4.dp))
    TextInk("${rmx.bars} bars", fontSize = 11.sp, color = Color(StagePalette.accentBlue), modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        SCENE_BARS.forEach { bars ->
            MiniChoice("$bars", rmx.bars == bars, Modifier.weight(1f)) { onRmx(rmx.copy(bars = bars)) }
        }
    }
    TextInk(if (rmx.xOn) XPAD_NAMES[rmx.xMode] else "X-PAD", fontSize = 11.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp))
    UtilityButton("ABRIR RMX", onOpen, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

@Composable
fun RmxScreen(rmx: RmxState, onRmx: (RmxState) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(StagePalette.bg)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("RMX", color = Color(StagePalette.accentOrange), fontSize = 22.sp, weight = FontWeight.Bold)
            TextInk(rmx.sceneName(), fontSize = 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1)
            UtilityButton("FECHAR", onClose)
        }
        Row(Modifier.weight(1.1f).fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(rmx.intensity, rmx.sceneName(), Modifier.width(180.dp).height(180.dp))
            Column(Modifier.weight(1f)) {
                TextInk("SCENE FX", fontSize = 12.sp, color = Color(StagePalette.inkMuted))
                IntensityBar(rmx.intensity, Modifier.fillMaxWidth().height(28.dp).padding(top = 6.dp))
                TextInk("Intensidade ${(rmx.intensity * 100).toInt()}%", fontSize = 16.sp, weight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SCENE_BARS.forEach { bars ->
                        UtilityButton("$bars", { onRmx(rmx.copy(bars = bars)) }, rmx.bars == bars, Modifier.weight(1f))
                    }
                }
            }
        }
        TextInk("Segure um pad para o build-up ou o break. Soltar deixa a cauda.", fontSize = 13.sp, color = Color(StagePalette.inkMuted))
        Row(Modifier.padding(top = 6.dp).height(72.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SCENE_NAMES.forEachIndexed { index, name ->
                HoldPad(name, rmx.scene == index && rmx.holding, Modifier.weight(1f).fillMaxHeight()) { down ->
                    onRmx(if (down) rmx.copy(scene = index, holding = true, panel = true) else rmx.copy(holding = false))
                }
            }
        }
        TextInk("X-PAD", fontSize = 12.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            XBEAT_LABELS.forEachIndexed { index, label ->
                UtilityButton(label, { onRmx(rmx.copy(xBeat = index)) }, rmx.xBeat == index)
            }
        }
        Row(Modifier.padding(top = 6.dp).height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            XPAD_NAMES.forEachIndexed { index, name ->
                UtilityButton(name, { onRmx(rmx.copy(xMode = index, xOn = !(rmx.xOn && rmx.xMode == index), panel = true)) }, rmx.xOn && rmx.xMode == index, Modifier.weight(1f).fillMaxHeight())
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IsolatorColumn("CH 1", rmx.low[0], rmx.mid[0], rmx.high[0], Modifier.weight(1f)) { band, value ->
                onRmx(rmx.copy(low = rmx.low.setAt(0, if (band == 0) value else rmx.low[0]), mid = rmx.mid.setAt(0, if (band == 1) value else rmx.mid[0]), high = rmx.high.setAt(0, if (band == 2) value else rmx.high[0])))
            }
            IsolatorColumn("CH 2", rmx.low[1], rmx.mid[1], rmx.high[1], Modifier.weight(1f)) { band, value ->
                onRmx(rmx.copy(low = rmx.low.setAt(1, if (band == 0) value else rmx.low[1]), mid = rmx.mid.setAt(1, if (band == 1) value else rmx.mid[1]), high = rmx.high.setAt(1, if (band == 2) value else rmx.high[1])))
            }
            Column(Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextInk("RELEASE", fontSize = 12.sp, color = Color(StagePalette.inkMuted))
                UtilityButton("BRAKE", { onRmx(rmx.copy(release = 0, panel = true)) }, rmx.release == 0, Modifier.fillMaxWidth())
                UtilityButton("ECHO OUT", { onRmx(rmx.copy(release = 1, panel = true)) }, rmx.release == 1, Modifier.fillMaxWidth())
                UtilityButton("BACKSPIN", { onRmx(rmx.copy(release = 2, panel = true)) }, rmx.release == 2, Modifier.fillMaxWidth())
                UtilityButton(if (rmx.gate) "GATE ON" else "GATE", { onRmx(rmx.copy(gate = !rmx.gate)) }, rmx.gate, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("1" to 0, "2" to 1, "MST" to 2).forEach { (label, channel) ->
                        UtilityButton(label, { onRmx(rmx.copy(channel = channel)) }, rmx.channel == channel, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun IsolatorColumn(title: String, low: Float, mid: Float, high: Float, modifier: Modifier, onChange: (Int, Float) -> Unit) {
    Column(modifier) {
        TextInk(title, fontSize = 13.sp, weight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IsoBar("LO", low, Modifier.weight(1f).fillMaxHeight()) { onChange(0, it) }
            IsoBar("MID", mid, Modifier.weight(1f).fillMaxHeight()) { onChange(1, it) }
            IsoBar("HI", high, Modifier.weight(1f).fillMaxHeight()) { onChange(2, it) }
        }
    }
}

@Composable
private fun IsoBar(label: String, gain: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.weight(1f).fillMaxWidth().border(1.dp, Color(StagePalette.line)).pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    val fraction = 1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                    onChange((fraction * 2f).coerceIn(0f, 2f))
                }
            },
        ) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val h = size.height * (gain / 2f).coerceIn(0f, 1f)
                drawRect(Color(StagePalette.panelRaised), Offset(0f, 0f), size)
                drawRect(Color(StagePalette.accentBlue), Offset(0f, size.height - h), Size(size.width, h))
                val unity = size.height * 0.5f
                drawLine(Color(StagePalette.accentOrange), Offset(0f, unity), Offset(size.width, unity), 2f)
            }
        }
        TextInk(label, fontSize = 11.sp, weight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ProgressRing(intensity: Float, name: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14f
            drawArc(Color(StagePalette.line), -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Color(StagePalette.accentOrange), -90f, 360f * intensity.coerceIn(0f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TextInk("${(intensity * 100).toInt()}", fontSize = 36.sp, weight = FontWeight.Bold, mono = true)
            TextInk(name, fontSize = 11.sp, color = Color(StagePalette.inkMuted), maxLines = 2)
        }
    }
}

@Composable
private fun IntensityBar(intensity: Float, modifier: Modifier) {
    Canvas(modifier.background(Color(StagePalette.panelRaised))) {
        drawRect(Color(StagePalette.accentOrange), size = Size(size.width * intensity.coerceIn(0f, 1f), size.height))
    }
}

@Composable
private fun HoldPad(label: String, active: Boolean, modifier: Modifier, onHold: (Boolean) -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(Color(if (active) StagePalette.accentOrange else StagePalette.panelRaised)).border(1.dp, Color(if (active) StagePalette.accentOrange else StagePalette.line), RoundedCornerShape(4.dp)).pointerInput(label) {
            detectTapGestures(onPress = {
                onHold(true)
                tryAwaitRelease()
                onHold(false)
            })
        }.padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = Color(if (active) StagePalette.playInk else StagePalette.ink), fontSize = 11.sp, weight = FontWeight.Bold, maxLines = 2)
    }
}

@Composable
private fun Keyboard(query: String, onQuery: (String) -> Unit) {
    val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { letter ->
                    UtilityButton(letter.toString(), { onQuery(query + letter) }, modifier = Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            UtilityButton("ESPAÇO", { onQuery("$query ") }, modifier = Modifier.weight(1f))
            UtilityButton("APAGAR", { onQuery(query.dropLast(1)) }, modifier = Modifier.weight(1f))
            UtilityButton("LIMPAR", { onQuery("") }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextInk(title, fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentBlue))
        content()
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            TextInk(title, fontSize = 16.sp, weight = FontWeight.SemiBold)
            TextInk(detail, fontSize = 12.sp, color = Color(StagePalette.inkMuted))
        }
        TextInk(if (on) "ON" else "OFF", color = Color(if (on) StagePalette.accentBlue else StagePalette.inkMuted), fontSize = 16.sp, weight = FontWeight.Bold)
    }
}

@Composable
internal fun UtilityButton(label: String, onClick: () -> Unit, selected: Boolean = false, modifier: Modifier = Modifier) {
    val fill = if (selected) Color(StagePalette.accentBlue) else Color(StagePalette.panelRaised)
    val ink = if (selected) Color(StagePalette.navSelectedInk) else Color(StagePalette.accentBlue)
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(6.dp)).background(fill).border(1.dp, Color(if (selected) StagePalette.accentBlue else StagePalette.line), RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = ink, fontSize = 13.sp, weight = FontWeight.Bold, maxLines = 2)
    }
}

@Composable
private fun MiniChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(28.dp).border(1.dp, Color(if (selected) StagePalette.accentBlue else StagePalette.line)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = Color(if (selected) StagePalette.accentBlue else StagePalette.ink), fontSize = 11.sp, weight = FontWeight.Bold)
    }
}

private fun List<Float>.setAt(index: Int, value: Float): List<Float> = mapIndexed { item, current -> if (item == index) value else current }
