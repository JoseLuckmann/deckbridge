package os.deckbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import os.deckbridge.logic.BeatFxState
import os.deckbridge.logic.BrowseCategory
import os.deckbridge.logic.BrowsePlace
import os.deckbridge.logic.DeckFace
import os.deckbridge.logic.MarkerKind
import os.deckbridge.logic.RbAnalysis
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.RbHotCue
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.WaveZoom
import os.deckbridge.logic.activeLoop
import os.deckbridge.logic.beatsUntil
import os.deckbridge.logic.gridLines
import os.deckbridge.logic.inkOn
import os.deckbridge.logic.loopLengthLabel
import os.deckbridge.logic.markerFraction
import os.deckbridge.logic.tempoRangeLabel
import os.deckbridge.logic.windowSeconds
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    catalog: RbCatalog?,
    tracks: List<RbTrack?>,
    faces: List<DeckFace>,
    master: Int,
    quantize: Boolean,
    zoom: List<WaveZoom>,
    zoomLink: Boolean,
    fx: BeatFxState,
    jvmBanner: String?,
    browse: BrowsePlace? = null,
    historyIds: List<Int> = emptyList(),
    usbLabel: String = "",
    onPage: (Int) -> Unit = {},
    onBrowse: () -> Unit = {},
    onBrowseClose: () -> Unit = {},
    onZoom: (Int, WaveZoom) -> Unit = { _, _ -> },
    onLink: (Boolean) -> Unit = {},
    onPlay: (Int) -> Unit = {},
    onCue: (Int) -> Unit = {},
    onHot: (Int, Int) -> Unit = { _, _ -> },
    onSync: (Int) -> Unit = {},
    onMaster: (Int) -> Unit = {},
    onKey: (Int) -> Unit = {},
    onRemain: (Int) -> Unit = {},
    onRange: (Int) -> Unit = {},
    onFader: (Int, Float) -> Unit = { _, _ -> },
    onScratch: (Int, Float) -> Unit = { _, _ -> },
    onJump: (Int, Int) -> Unit = { _, _ -> },
    onQuantize: () -> Unit = {},
    onAuthorize: () -> Unit = {},
    onSimulate: () -> Unit = {},
    onBrowseMove: (Int) -> Unit = {},
    onBrowseEnter: () -> Unit = {},
    onBrowseBack: () -> Unit = {},
    onBrowsePick: (Int) -> Unit = {},
    onBrowseCategory: (BrowseCategory) -> Unit = {},
    onQuery: (String) -> Unit = {},
    onLoad: (RbTrack, Int) -> Unit = { _, _ -> },
    status: String = "",
) {
    val note = if (jvmBanner.isNullOrBlank()) null else "SIMULAÇÃO · $jvmBanner"
    Box(Modifier.fillMaxSize().background(Color(StagePalette.bg))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            TopBar(note, zoom.first(), zoomLink, onBrowse, onPage, onZoom, onLink)
            FxBar(fx)
            if (status.isNotBlank()) {
                TextInk(status, fontSize = 12.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DeckCdj(0, tracks.getOrNull(0), faces.getOrElse(0) { DeckFace() }, catalog, master == 0, quantize, zoom.getOrElse(0) { WaveZoom.BARS_8 }, fx, Modifier.weight(1f).fillMaxHeight(), onPlay, onCue, onHot, onSync, onMaster, onKey, onRemain, onRange, onFader, onScratch, onJump, onZoom, onQuantize)
                PhaseMeter(faces.getOrElse(0) { DeckFace() }, faces.getOrElse(1) { DeckFace() }, tracks, Modifier.width(28.dp).fillMaxHeight())
                DeckCdj(1, tracks.getOrNull(1), faces.getOrElse(1) { DeckFace() }, catalog, master == 1, quantize, if (zoomLink) zoom.first() else zoom.getOrElse(1) { zoom.first() }, fx, Modifier.weight(1f).fillMaxHeight(), onPlay, onCue, onHot, onSync, onMaster, onKey, onRemain, onRange, onFader, onScratch, onJump, onZoom, onQuantize)
            }
        }
        if (browse != null && catalog != null) {
            BrowseScreen(
                catalog = catalog,
                place = browse,
                historyIds = historyIds,
                usbLabel = usbLabel,
                jvmBanner = jvmBanner,
                onClose = onBrowseClose,
                onMove = onBrowseMove,
                onEnter = onBrowseEnter,
                onBack = onBrowseBack,
                onPick = onBrowsePick,
                onCategory = onBrowseCategory,
                onQuery = onQuery,
                onLoad = onLoad,
                onAuthorize = onAuthorize,
                onSimulate = onSimulate,
            )
        }
    }
}

@Composable
private fun TopBar(
    note: String?,
    zoom: WaveZoom,
    link: Boolean,
    onBrowse: () -> Unit,
    onPage: (Int) -> Unit,
    onZoom: (Int, WaveZoom) -> Unit,
    onLink: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Chip("SOURCE", onBrowse)
        Chip("BROWSE", onBrowse)
        Chip("−", { onZoom(0, zoom.zoomOut()) })
        TextInk(zoom.label(), fontSize = 13.sp, weight = FontWeight.Bold, mono = true, modifier = Modifier.width(52.dp))
        Chip("+", { onZoom(0, zoom.zoomIn()) })
        Chip(if (link) "LINK" else "SOLO", { onLink(!link) }, selected = link)
        if (note != null) {
            Box(
                Modifier.weight(1f).padding(horizontal = 6.dp).clip(RoundedCornerShape(3.dp)).background(Color(StagePalette.simBg)).padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                TextInk(note, color = Color(StagePalette.simInk), fontSize = 11.sp, maxLines = 1)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Chip("ESTADO", { onPage(0) })
    }
}

@Composable
private fun FxBar(fx: BeatFxState) {
    val onFill = if (fx.on) Color(StagePalette.playBg) else Color(StagePalette.panelRaised)
    val onInk = if (fx.on) Color(StagePalette.playInk) else Color(StagePalette.ink)
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).height(26.dp).clip(RoundedCornerShape(4.dp)).background(Color(StagePalette.panel)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextInk("BEAT FX", fontSize = 11.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
        TextInk(fx.name(), fontSize = 14.sp, weight = FontWeight.SemiBold)
        TextInk(fx.fractionLabel(), fontSize = 14.sp, weight = FontWeight.Bold, mono = true)
        TextInk("CH ${fx.channelLabel()}", fontSize = 13.sp, weight = FontWeight.Bold, mono = true)
        Box(Modifier.clip(RoundedCornerShape(3.dp)).background(onFill).padding(horizontal = 6.dp, vertical = 1.dp)) {
            TextInk(if (fx.on) "ON" else "OFF", color = onInk, fontSize = 12.sp, weight = FontWeight.Bold)
        }
        TextInk("LVL ${fx.level}", fontSize = 13.sp, weight = FontWeight.Bold, mono = true)
    }
}

@Composable
private fun DeckCdj(
    deck: Int,
    track: RbTrack?,
    face: DeckFace,
    catalog: RbCatalog?,
    master: Boolean,
    quantize: Boolean,
    zoom: WaveZoom,
    fx: BeatFxState,
    modifier: Modifier,
    onPlay: (Int) -> Unit,
    onCue: (Int) -> Unit,
    onHot: (Int, Int) -> Unit,
    onSync: (Int) -> Unit,
    onMaster: (Int) -> Unit,
    onKey: (Int) -> Unit,
    onRemain: (Int) -> Unit,
    onRange: (Int) -> Unit,
    onFader: (Int, Float) -> Unit,
    onScratch: (Int, Float) -> Unit,
    onJump: (Int, Int) -> Unit,
    onZoom: (Int, WaveZoom) -> Unit,
    onQuantize: () -> Unit,
) {
    val analysis = track?.let { catalog?.analyses?.get(it.id) }
    val accentRgb = if (deck == 0) StagePalette.deckA else StagePalette.deckB
    val accent = argb(accentRgb)
    val badgeInk = argb(inkOn(accentRgb))
    val loop = activeLoop(analysis)
    val duration = track?.durationSeconds ?: 0
    val percent = (face.pitch - 1f) * 100f
    val shown = if (face.remain) (duration - face.position).coerceAtLeast(0f) else face.position
    BoxWithConstraints(modifier.clip(RoundedCornerShape(8.dp)).background(Color(StagePalette.panel)).border(1.dp, Color(StagePalette.line), RoundedCornerShape(8.dp)).padding(6.dp)) {
        val compact = maxHeight < 700.dp
        val bpmSize = if (compact) 26.sp else 40.sp
        val titleSize = if (compact) 15.sp else 18.sp
        val timeSize = if (compact) 14.sp else 18.sp
        val art = if (compact) 32.dp else 44.dp
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(accent), contentAlignment = Alignment.Center) {
                    TextInk("${deck + 1}", color = badgeInk, fontSize = 13.sp, weight = FontWeight.Bold, mono = true)
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    TextInk(track?.title ?: "Sem faixa", fontSize = titleSize, weight = FontWeight.SemiBold, maxLines = 1)
                    TextInk(track?.artist ?: "—", fontSize = 13.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
                }
                CoverArt(track?.id ?: 0, track?.title.orEmpty(), art)
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                TextInk(if (track == null) "—.—" else "%05.1f".format((track.bpm * face.pitch)), fontSize = bpmSize, weight = FontWeight.Bold, mono = true)
                Column(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                    TextInk(tempoText(percent), fontSize = 16.sp, weight = FontWeight.Bold, mono = true)
                    Chip(tempoRangeLabel(face.range), { onRange(deck) }, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    TextInk(track?.keyName ?: "—", fontSize = 22.sp, weight = FontWeight.Bold, mono = true)
                    TextInk(if (face.remain) "REMAIN" else "ELAPSED", fontSize = 10.sp, color = Color(StagePalette.inkMuted))
                    TextInk(clock(shown), fontSize = timeSize, weight = FontWeight.Bold, mono = true, modifier = Modifier.clickable { onRemain(deck) })
                }
            }
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge("MASTER", master, accentRgb) { onMaster(deck) }
                Badge("SYNC", face.sync, accentRgb) { onSync(deck) }
                Badge("KEY", face.keyLock, accentRgb) { onKey(deck) }
                Badge(if (quantize) "Q" else "Q OFF", quantize, accentRgb, onQuantize)
                if (loop != null && track != null) {
                    TextInk("LOOP ${loopLengthLabel(loop.first, loop.second, track.bpm)}", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(0xFFB6FFC8.toInt()))
                }
                if (fx.hitsDeck(deck)) {
                    TextInk("FX ON", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.playBg))
                }
                TextInk(filterLabel(face.filter), fontSize = 12.sp, weight = FontWeight.Bold, mono = true, color = Color(StagePalette.inkMuted))
            }
            ScrollingWave(
                analysis = analysis,
                position = face.position,
                duration = duration,
                bpm = track?.bpm ?: 0.0,
                zoom = zoom,
                fxOn = fx.hitsDeck(deck),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                onZoomIn = { onZoom(deck, zoom.zoomIn()) },
                onZoomOut = { onZoom(deck, zoom.zoomOut()) },
            )
            OverviewWave(analysis, face.position, duration, Modifier.fillMaxWidth().height(if (compact) 20.dp else 28.dp).padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (index in 0..7) {
                    HotPad(index, analysis?.hotCues?.firstOrNull { it.index == index }, Modifier.weight(1f)) { onHot(deck, index) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(if (face.playing) "PLAY" else "PLAY", { onPlay(deck) }, selected = face.playing)
                Chip("CUE", { onCue(deck) })
                Chip("SYNC", { onSync(deck) }, selected = face.sync)
                Chip("−1", { onJump(deck, -1) })
                Chip("+1", { onJump(deck, 1) })
                Platter(face.position, track?.bpm ?: 120.0, accent, Modifier.size(36.dp)) {
                    onScratch(deck, it)
                }
                Spacer(Modifier.weight(1f))
                TempoFader(face.fader, accent, if (compact) 36.dp else 52.dp) { onFader(deck, it) }
            }
        }
    }
}

@Composable
private fun ScrollingWave(
    analysis: RbAnalysis?,
    position: Float,
    duration: Int,
    bpm: Double,
    zoom: WaveZoom,
    fxOn: Boolean,
    modifier: Modifier,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
) {
    val whole = zoom == WaveZoom.TRACK
    val window = windowSeconds(zoom, bpm, duration.toDouble())
    val lines = gridLines(bpm, duration.toDouble(), analysis?.beats.orEmpty())
    val next = analysis?.hotCues?.filter { it.timeMs / 1000f > position }?.minByOrNull { it.timeMs }
    val until = next?.let { beatsUntil(position.toDouble(), it.timeMs / 1000.0, bpm) }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Color(StagePalette.waveBg))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(zoom) {
                detectTransformGestures { _, _, change, _ ->
                    if (change > 1.08f) onZoomIn()
                    if (change < 0.92f) onZoomOut()
                }
            },
        ) {
            val gutter = 18f
            val top = gutter
            val bottom = size.height - gutter
            val mid = (top + bottom) / 2f
            val half = (bottom - top) * 0.46f
            val columns = 180
            val loop = activeLoop(analysis)
            if (loop != null && duration > 0) {
                val a = markerFraction(loop.first / 1000.0, position.toDouble(), window, whole, duration.toDouble())
                val b = markerFraction(loop.second / 1000.0, position.toDouble(), window, whole, duration.toDouble())
                val left = (a.coerceAtMost(b) * size.width).toFloat()
                val right = (a.coerceAtLeast(b) * size.width).toFloat()
                if (right > left) drawRect(Color(0x332EE6A6), Offset(left, top), Size(right - left, bottom - top))
            }
            for (column in 0 until columns) {
                val time = if (whole) duration * column / columns.toDouble() else (position - window / 2.0) + window * column / columns.toDouble()
                val (low, midBand, high) = bandsAt(analysis, duration.toDouble(), time)
                val x = column * size.width / columns
                drawLine(Color(StagePalette.waveLow), Offset(x, mid - low * half), Offset(x, mid + low * half), strokeWidth = (size.width / columns).coerceAtLeast(1.4f))
                drawLine(Color(StagePalette.waveMid), Offset(x, mid - midBand * half * 0.72f), Offset(x, mid + midBand * half * 0.72f), strokeWidth = (size.width / columns * 0.7f).coerceAtLeast(1f))
                drawLine(Color(StagePalette.waveHigh), Offset(x, mid - high * half * 0.45f), Offset(x, mid + high * half * 0.45f), strokeWidth = 1f)
            }
            lines.forEach { line ->
                val fraction = markerFraction(line.seconds, position.toDouble(), window, whole, duration.toDouble())
                if (fraction < 0.0 || fraction > 1.0) return@forEach
                val x = (fraction * size.width).toFloat()
                val reach = when (line.kind) {
                    MarkerKind.PHRASE -> gutter
                    MarkerKind.BAR -> 13f
                    MarkerKind.BEAT -> 7f
                }
                val stroke = when (line.kind) {
                    MarkerKind.PHRASE -> 3f
                    MarkerKind.BAR -> 2.2f
                    MarkerKind.BEAT -> 1.7f
                }
                val color = if (line.kind == MarkerKind.BEAT) Color(StagePalette.inkMuted) else Color(StagePalette.ink)
                drawLine(color, Offset(x, top - reach), Offset(x, top + 5f), strokeWidth = stroke)
                drawLine(color, Offset(x, bottom - 5f), Offset(x, bottom + reach), strokeWidth = stroke)
                if (line.kind == MarkerKind.PHRASE) {
                    drawLine(color.copy(alpha = 0.45f), Offset(x, top), Offset(x, bottom), strokeWidth = 1.6f)
                }
            }
            analysis?.hotCues?.forEach { cue ->
                val fraction = markerFraction(cue.timeMs / 1000.0, position.toDouble(), window, whole, duration.toDouble())
                if (fraction < 0.0 || fraction > 1.0) return@forEach
                val x = (fraction * size.width).toFloat()
                drawLine(argb(cue.colorRgb ?: StagePalette.pads[cue.index.coerceIn(0, 7)]), Offset(x, top), Offset(x, bottom), strokeWidth = 2f)
            }
            val head = if (whole && duration > 0) (position / duration).coerceIn(0f, 1f) * size.width else size.width / 2f
            drawLine(Color(StagePalette.ink), Offset(head, 0f), Offset(head, size.height), strokeWidth = 2f)
        }
        TextInk(
            buildString {
                append(if (whole) "FAIXA" else "${zoom.bars} BARS")
                if (until != null) append("   ${if (until >= 0) "+" else ""}$until")
            },
            fontSize = 11.sp,
            weight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        )
        if (fxOn) {
            TextInk("FX ON", fontSize = 11.sp, weight = FontWeight.Bold, color = Color(StagePalette.playBg), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("−", onZoomOut, fontSize = 12.sp)
            Chip("+", onZoomIn, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OverviewWave(analysis: RbAnalysis?, position: Float, duration: Int, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(3.dp)).background(Color(StagePalette.waveBg))) {
        val data = analysis?.waveform ?: ByteArray(0)
        val mid = size.height / 2f
        if (data.isNotEmpty()) {
            val step = size.width / data.size
            data.forEachIndexed { index, byte ->
                val amp = (byte.toInt() and 0xFF) / 255f * (size.height * 0.42f)
                val x = index * step
                drawLine(Color(StagePalette.waveLow), Offset(x, mid - amp), Offset(x, mid + amp), strokeWidth = step.coerceAtLeast(1f))
            }
        }
        if (duration > 0) {
            val x = (position / duration).coerceIn(0f, 1f) * size.width
            drawRect(Color(0x88000000), Offset(0f, 0f), Size(x, size.height))
            drawLine(Color(StagePalette.ink), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
        }
        activeLoop(analysis)?.let { loop ->
            if (duration <= 0) return@let
            val a = loop.first / 1000f / duration * size.width
            val b = loop.second / 1000f / duration * size.width
            drawRect(Color(0x553DDC97), Offset(a, 0f), Size((b - a).coerceAtLeast(2f), size.height))
        }
        analysis?.hotCues?.forEach { cue ->
            if (duration <= 0) return@forEach
            val x = cue.timeMs / 1000f / duration * size.width
            drawLine(argb(cue.colorRgb ?: StagePalette.pads[cue.index.coerceIn(0, 7)]), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
        }
        analysis?.memoryCues?.forEach { cue ->
            if (duration <= 0) return@forEach
            val x = cue.timeMs / 1000f / duration * size.width
            drawLine(Color(StagePalette.ink), Offset(x, 0f), Offset(x, size.height * 0.45f), strokeWidth = 1.2f)
        }
    }
}

@Composable
private fun PhaseMeter(a: DeckFace, b: DeckFace, tracks: List<RbTrack?>, modifier: Modifier) {
    val delta = os.deckbridge.logic.phaseOffset(a.position.toDouble(), tracks.getOrNull(0)?.bpm ?: 0.0, b.position.toDouble(), tracks.getOrNull(1)?.bpm ?: 0.0)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TextInk("PH", fontSize = 10.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp)) {
            val mid = size.height / 2f
            drawLine(Color(StagePalette.line), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1f)
            drawLine(Color(StagePalette.ink), Offset(2f, mid), Offset(size.width - 2f, mid), 1f)
            val y = mid - (delta.toFloat() * size.height * 0.8f)
            drawCircle(Color(StagePalette.deckA), radius = 4f, center = Offset(size.width / 2f, y))
        }
    }
}

@Composable
private fun Platter(position: Float, bpm: Double, accent: Color, modifier: Modifier, onDrag: (Float) -> Unit) {
    val turns = if (bpm > 1.0) position * bpm.toFloat() / 60f else position
    Canvas(
        modifier
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, drag ->
                    val beat = if (bpm > 1.0) 60f / bpm.toFloat() else 0.5f
                    onDrag(drag / 120f * beat)
                }
            },
    ) {
        drawCircle(Color(StagePalette.waveBg))
        drawCircle(accent, style = Stroke(width = 3f))
        rotate(turns * 360f) {
            drawLine(Color(StagePalette.ink), Offset(size.width / 2f, size.height / 2f), Offset(size.width / 2f, 4f), strokeWidth = 2f)
        }
        drawCircle(accent, radius = 4f)
    }
}

@Composable
private fun TempoFader(value: Float, accent: Color, barHeight: Dp, onChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextInk("TEMPO", fontSize = 9.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
        Box(
            Modifier
                .padding(top = 2.dp)
                .width(18.dp)
                .height(barHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(StagePalette.waveBg))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, _, _ ->
                        val next = (value - pan.y / size.height).coerceIn(0f, 1f)
                        onChange(next)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onChange((1f - offset.y / size.height).coerceIn(0f, 1f))
                    }
                },
        ) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(value.coerceIn(0.04f, 1f)).background(accent))
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(1.dp).background(Color(StagePalette.ink)))
        }
    }
}

@Composable
private fun HotPad(index: Int, cue: RbHotCue?, modifier: Modifier, onClick: () -> Unit) {
    val letter = ('A' + index).toString()
    val rgb = cue?.colorRgb ?: StagePalette.pads[index]
    val active = cue != null
    val fill = if (active) argb(rgb) else Color(StagePalette.panelRaised)
    val ink = if (active) argb(inkOn(rgb)) else Color(StagePalette.ink)
    Box(
        modifier.height(28.dp).clip(RoundedCornerShape(3.dp)).background(fill).border(1.dp, argb(rgb), RoundedCornerShape(3.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(if (cue?.comment?.isNotBlank() == true && cue.comment.length <= 4) cue.comment else letter, color = ink, fontSize = 11.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun CoverArt(seed: Int, title: String, size: Dp) {
    Canvas(Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(Color(StagePalette.panelRaised))) {
        val base = StagePalette.pads[abs(seed) % StagePalette.pads.size]
        drawRect(argb(base), size = Size(this.size.width * 0.62f, this.size.height))
        drawRect(Color(StagePalette.ink), topLeft = Offset(this.size.width * 0.62f, 0f), size = Size(this.size.width * 0.38f, this.size.height * 0.45f))
        drawRect(Color(StagePalette.bg), topLeft = Offset(this.size.width * 0.62f, this.size.height * 0.45f), size = Size(this.size.width * 0.38f, this.size.height * 0.55f))
        if (title.isNotEmpty()) {
            drawCircle(Color(StagePalette.bg), radius = this.size.minDimension * 0.12f, center = Offset(this.size.width * 0.28f, this.size.height * 0.62f))
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit, selected: Boolean = false, fontSize: TextUnit = 12.sp) {
    val fill = if (selected) Color(StagePalette.navSelected) else Color(StagePalette.panelRaised)
    val ink = if (selected) Color(StagePalette.navSelectedInk) else Color(StagePalette.ink)
    Box(
        Modifier.height(26.dp).clip(RoundedCornerShape(4.dp)).background(fill).border(1.dp, Color(StagePalette.line), RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = ink, fontSize = fontSize, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Badge(label: String, on: Boolean, accentRgb: Int, onClick: () -> Unit) {
    val fill = if (on) argb(accentRgb) else Color(StagePalette.panelRaised)
    val ink = if (on) argb(inkOn(accentRgb)) else Color(StagePalette.ink)
    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(fill).clickable(onClick = onClick).padding(horizontal = 5.dp, vertical = 2.dp)) {
        TextInk(label, color = ink, fontSize = 10.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

private fun bandsAt(analysis: RbAnalysis?, duration: Double, time: Double): Triple<Float, Float, Float> {
    val overview = analysis?.waveform ?: ByteArray(0)
    val amp = if (overview.isEmpty() || duration <= 0.0) {
        0.25f
    } else {
        val index = ((time / duration) * (overview.size - 1)).roundToInt().coerceIn(0, overview.lastIndex)
        (overview[index].toInt() and 0xFF) / 255f
    }
    val beat = analysis?.beats?.minByOrNull { abs(it.timeMs / 1000.0 - time) }
    val dist = if (beat == null) 1.0 else abs(beat.timeMs / 1000.0 - time)
    val peak = if (dist < 0.045) (1.0 - dist / 0.045).toFloat() else 0f
    return Triple(
        (amp * 0.9f).coerceIn(0.05f, 1f),
        (amp * 0.5f + peak * 0.75f).coerceIn(0f, 1f),
        (peak * 0.95f + amp * 0.12f).coerceIn(0f, 1f),
    )
}

private fun tempoText(percent: Float): String {
    val sign = if (percent > 0.05f) "+" else ""
    return "$sign${"%.1f".format(percent)}%"
}

private fun filterLabel(value: Int): String {
    val delta = ((value - 64) * 100) / 64
    val sign = if (delta > 0) "+" else ""
    return "FLT $sign$delta"
}

private fun clock(seconds: Float): String {
    val whole = seconds.toInt().coerceAtLeast(0)
    val frac = ((seconds - whole) * 10).toInt().coerceIn(0, 9)
    return "%d:%02d.%d".format(whole / 60, whole % 60, frac)
}
