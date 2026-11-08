package os.deckbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.RmxState
import os.deckbridge.logic.rmxKnob
import os.deckbridge.logic.rmxKnobLabel
import os.deckbridge.logic.rmxKnobValue
import os.deckbridge.logic.rmxRolling
import os.deckbridge.logic.rmxScenePress
import os.deckbridge.logic.rmxToggleHold
import os.deckbridge.logic.rmxXPadDown
import os.deckbridge.logic.rmxXPadUp
import os.deckbridge.logic.SCENE_BARS
import os.deckbridge.logic.SCENE_NAMES
import os.deckbridge.logic.DRUM_VOICES
import os.deckbridge.logic.drumPatternLabel
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.XBEAT_LABELS
import os.deckbridge.logic.XPAD_NAMES
import os.deckbridge.logic.filterTracks

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
    TextInk(
        if (rmx.xOn && rmx.xMode == 2) drumPatternLabel(rmx) else if (rmx.xOn) XPAD_NAMES[rmx.xMode] else "X-PAD",
        fontSize = 11.sp,
        color = Color(StagePalette.inkMuted),
        modifier = Modifier.padding(top = 6.dp),
    )
    UtilityButton(if (rmx.full) "FECHAR RMX" else "ABRIR RMX", onOpen, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
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
private fun MiniChoice(label: String, selected: Boolean, modifier: Modifier, big: Boolean = false, onClick: () -> Unit) {
    val fill = if (selected) Color(0xFF1A3A68) else Color(if (big) StagePalette.panelRaised else StagePalette.panel)
    val ink = if (selected) Color.White else Color(StagePalette.ink)
    Box(
        modifier.heightIn(min = if (big) 40.dp else 26.dp).background(fill).border(1.dp, Color(if (selected) StagePalette.accentBlue else StagePalette.line)).clickable(onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = ink, fontSize = if (big) 14.sp else 11.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

private fun List<Float>.setAt(index: Int, value: Float): List<Float> = mapIndexed { item, current -> if (item == index) value else current }
