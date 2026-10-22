package os.deckbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.DeckFace
import os.deckbridge.logic.LETTER_COLORS
import os.deckbridge.logic.MarkerKind
import os.deckbridge.logic.RbAnalysis
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.WaveZoom
import os.deckbridge.logic.activeLoop
import os.deckbridge.logic.barsFromSeconds
import os.deckbridge.logic.GridEdit
import os.deckbridge.logic.applyGrid
import os.deckbridge.logic.gridBpm
import os.deckbridge.logic.gridLines
import os.deckbridge.logic.nextCueSeconds
import os.deckbridge.logic.markerFraction
import os.deckbridge.logic.monoSample
import os.deckbridge.logic.needleSeconds
import os.deckbridge.logic.rgbSample
import os.deckbridge.logic.phraseColor
import os.deckbridge.logic.windowSeconds
import kotlin.math.roundToInt

internal const val PLAYHEAD_ANCHOR = 0.30

@Composable
internal fun ZoomedWave(
    deck: Int,
    track: RbTrack?,
    analysis: RbAnalysis?,
    face: DeckFace,
    zoom: WaveZoom,
    grid: Boolean,
    onZoom: (Int, WaveZoom) -> Unit,
    onSeek: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
    anchor: Double = PLAYHEAD_ANCHOR,
    waveMode: Int = 1,
) {
    val measurer = rememberTextMeasurer()
    val duration = (track?.durationSeconds ?: 0).toDouble().coerceAtLeast(0.1)
    val bpm = if (track == null) 0.0 else gridBpm(track.bpm, GridEdit(face.gridShiftMs, face.gridStretch))
    val position = face.position.toDouble()
    val window = windowSeconds(zoom, if (bpm > 1.0) bpm else 120.0, duration)
    val whole = zoom == WaveZoom.TRACK
    val start = if (whole) 0.0 else position - window * anchor
    Box(
        modifier
            .background(Color(StagePalette.bg))
            .border(1.dp, Color(StagePalette.line))
            .pointerInput(zoom) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange > 1.08f) onZoom(deck, zoom.zoomIn())
                    else if (zoomChange < 0.92f) onZoom(deck, zoom.zoomOut())
                }
            }
            .pointerInput(start, window) {
                detectTapGestures { offset ->
                    val time = if (whole) duration * (offset.x / size.width) else start + window * (offset.x / size.width)
                    onSeek(deck, time.toFloat())
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val topG = (size.height * 0.16f).coerceAtLeast(28f)
            val botG = (size.height * 0.18f).coerceAtLeast(30f)
            val waveTop = topG
            val waveBot = size.height - botG
            val count = (size.width / 3f).toInt().coerceIn(160, 520)
            val low = FloatArray(count)
            val mid = FloatArray(count)
            val high = FloatArray(count)
            val rgbHeights = FloatArray(count)
            val rgbColors = IntArray(count)
            var rgbColumns = 0
            for (index in 0 until count) {
                val time = if (whole) duration * index / (count - 1).coerceAtLeast(1) else start + window * index / (count - 1).coerceAtLeast(1)
                if (waveMode == 0) {
                    val packed = rgbSample(analysis, "RGB", false, time, duration)
                    if (packed >= 0) {
                        rgbHeights[index] = ((packed ushr 32).toInt() and 0xFF) / 255f
                        rgbColors[index] = (packed and 0xFFFFFF).toInt()
                        rgbColumns += 1
                    }
                } else if (waveMode == 2) {
                    low[index] = monoSample(analysis, false, time, duration)
                } else {
                    val sample = bandAt(analysis, duration, time, overview = false)
                    low[index] = sample.first
                    mid[index] = sample.second
                    high[index] = sample.third
                }
            }
            if (waveMode == 0 && rgbColumns > 0) {
                drawRgbColumns(smooth(rgbHeights), smoothRgb(rgbColors), waveTop, waveBot)
            } else {
                drawMirroredBands(smooth(low), smooth(mid), smooth(high), waveTop, waveBot, lowGain = 0.98f, midGain = 0.58f, highGain = 0.32f, waveMode = waveMode)
            }
            drawLine(
                Color.Black.copy(alpha = 0.55f),
                Offset(0f, (waveTop + waveBot) / 2f),
                Offset(size.width, (waveTop + waveBot) / 2f),
                strokeWidth = 1.2f,
            )
            val playX = if (whole) {
                (position / duration).coerceIn(0.0, 1.0).toFloat() * size.width
            } else {
                anchor.toFloat() * size.width
            }
            if (bpm > 1.0) {
                val lines = gridLines(bpm, duration, applyGrid(analysis?.beats.orEmpty(), GridEdit(face.gridShiftMs, face.gridStretch)))
                val numberStyle = TextStyle(color = Color(StagePalette.ink), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                lines.forEach { line ->
                    val fraction = markerFraction(line.seconds, position, window, whole, duration, anchor).toFloat()
                    if (fraction < -0.01f || fraction > 1.01f) return@forEach
                    val x = fraction * size.width
                    val downbeat = line.kind != MarkerKind.BEAT
                    val color = if (downbeat) Color(0xFFFF2A2A) else Color.White
                    val stroke = when {
                        grid && downbeat -> 3f
                        downbeat -> 2.2f
                        else -> 1.5f
                    }
                    val topReach = if (downbeat) 2f else 8f
                    drawLine(color, Offset(x, topReach), Offset(x, waveTop - 2f), stroke)
                    val botEnd = size.height - if (downbeat) 16f else 18f
                    drawLine(color, Offset(x, waveBot + 2f), Offset(x, botEnd), stroke)
                    if (downbeat) {
                        val barNo = (line.seconds / (60.0 / bpm) / 4.0).roundToInt() + 1
                        val show = if (whole) barNo % 8 == 0 else barNo % 4 == 0
                        if (show && barNo > 0) {
                            val text = barNo.toString()
                            val laid = measurer.measure(text, numberStyle)
                            val textX = x - laid.size.width / 2f
                            val textY = size.height - laid.size.height - 1f
                            if (textX >= 0f && textX + laid.size.width <= size.width && textY >= waveBot) {
                                drawText(measurer, text, Offset(textX, textY), numberStyle)
                            }
                        }
                    }
                }
            }
            val loop = if (face.loopEnd > face.loopStart) {
                (face.loopStart * 1000).roundToInt() to (face.loopEnd * 1000).roundToInt()
            } else {
                activeLoop(analysis)
            }
            if (loop != null) {
                val a = markerFraction(loop.first / 1000.0, position, window, whole, duration, anchor).toFloat()
                val b = markerFraction(loop.second / 1000.0, position, window, whole, duration, anchor).toFloat()
                if (b > 0f && a < 1f) {
                    val left = a.coerceAtLeast(0f) * size.width
                    val right = b.coerceAtMost(1f) * size.width
                    drawRect(
                        Color(StagePalette.accentOrange).copy(alpha = 0.22f),
                        Offset(left, waveTop),
                        Size((right - left).coerceAtLeast(1f), waveBot - waveTop),
                    )
                    drawLine(Color(StagePalette.accentOrange), Offset(left, 1f), Offset(right, 1f), strokeWidth = 2f)
                    if (a in 0f..1f) {
                        val tip = Path().apply {
                            moveTo(left, 1f)
                            lineTo(left + 11f, 13f)
                            lineTo(left, 13f)
                            close()
                        }
                        drawPath(tip, Color(StagePalette.accentOrange))
                    }
                }
            }
            analysis?.hotCues?.forEach { cue ->
                val fraction = markerFraction(cue.timeMs / 1000.0, position, window, whole, duration, anchor).toFloat()
                if (fraction !in 0f..1f) return@forEach
                val x = fraction * size.width
                val color = argb(LETTER_COLORS[cue.index.coerceIn(0, LETTER_COLORS.lastIndex)])
                val marker = Path().apply {
                    moveTo(x, waveTop + 1f)
                    lineTo(x - 5f, waveTop + 12f)
                    lineTo(x + 5f, waveTop + 12f)
                    close()
                }
                drawPath(marker, color)
                drawLine(color.copy(alpha = 0.7f), Offset(x, waveTop + 12f), Offset(x, waveBot), strokeWidth = 1.1f)
            }
            drawLine(Color(StagePalette.ink), Offset(playX, waveTop), Offset(playX, waveBot), strokeWidth = 1.6f)
            val nextAt = nextCueSeconds(analysis, position)
            if (nextAt != null && bpm > 1.0) {
                val bars = barsFromSeconds(nextAt - position, bpm)
                val label = "%.1f Bars".format(bars.coerceAtLeast(0.0))
                val style = TextStyle(color = Color(StagePalette.accentOrange), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val laid = measurer.measure(label, style)
                val x = (playX + 8f).coerceIn(0f, (size.width - laid.size.width).coerceAtLeast(0f))
                val y = waveTop + 6f
                if (x + laid.size.width <= size.width && y + laid.size.height <= size.height) {
                    drawRect(Color(StagePalette.bg).copy(alpha = 0.72f), Offset(x - 3f, y - 1f), Size(laid.size.width + 6f, laid.size.height + 2f))
                    drawText(measurer, label, Offset(x, y), style)
                }
            }
        }
    }
}

@Composable
internal fun OverviewWave(
    track: RbTrack?,
    analysis: RbAnalysis?,
    position: Double,
    modifier: Modifier = Modifier,
    waveMode: Int = 0,
    onSeek: (Float) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val duration = (track?.durationSeconds ?: 0).toDouble()
    Canvas(
        modifier.pointerInput(duration) {
            detectTapGestures { offset ->
                onSeek(needleSeconds(offset.x, size.width.toFloat(), duration))
            }
        }.background(Color(StagePalette.waveBg)),
    ) {
        val gutter = 11f
        val waveTop = gutter
        val waveBot = size.height - 1f
        val mode = when (waveMode) {
            2 -> "BLUE"
            1 -> "THREE_BAND"
            else -> "RGB"
        }
        drawOverview(analysis, mode, duration.coerceAtLeast(0.1), waveTop, waveBot)
        if (duration > 0.0) {
            analysis?.hotCues?.forEach { cue ->
                val x = (cue.timeMs / 1000.0 / duration).coerceIn(0.0, 1.0).toFloat() * size.width
                val color = argb(LETTER_COLORS[cue.index.coerceIn(0, LETTER_COLORS.lastIndex)])
                val style = TextStyle(color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                val letter = ('A' + cue.index.coerceIn(0, 7)).toString()
                val laid = measurer.measure(letter, style)
                val textX = (x - laid.size.width / 2f).coerceIn(0f, (size.width - laid.size.width).coerceAtLeast(0f))
                if (laid.size.width <= size.width && laid.size.height <= size.height) {
                    drawText(measurer, letter, Offset(textX, 0f), style)
                }
            }
            analysis?.memoryCues?.forEach { cue ->
                val x = (cue.timeMs / 1000.0 / duration).coerceIn(0.0, 1.0).toFloat() * size.width
                val tip = Path().apply {
                    moveTo(x, waveTop - 1f)
                    lineTo(x - 4f, 1f)
                    lineTo(x + 4f, 1f)
                    close()
                }
                drawPath(tip, Color(0xFFFF2A2A))
            }
            val play = (position / duration).coerceIn(0.0, 1.0).toFloat() * size.width
            drawLine(Color(StagePalette.ink), Offset(play, waveTop), Offset(play, waveBot), strokeWidth = 1.4f)
        }
    }
}

@Composable
internal fun PhraseBar(track: RbTrack?, phrases: List<os.deckbridge.logic.PhraseSpan>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth()) {
        val duration = (track?.durationSeconds ?: 0).toDouble()
        if (duration <= 0.0) return@Canvas
        if (phrases.isNotEmpty()) {
            phrases.forEach { phrase ->
                val x = (phrase.startMs / 1000.0 / duration).coerceIn(0.0, 1.0).toFloat() * size.width
                val end = (phrase.endMs / 1000.0 / duration).coerceIn(0.0, 1.0).toFloat() * size.width
                drawRect(argb(os.deckbridge.logic.phraseKindColor(phrase.kind)), Offset(x, 0f), Size((end - x).coerceAtLeast(1f), size.height))
                val fill = phrase.fillMs
                if (fill != null) {
                    val fx = (fill / 1000.0 / duration).coerceIn(0.0, 1.0).toFloat() * size.width
                    drawRect(Color.White.copy(alpha = 0.85f), Offset(fx, 0f), Size((end - fx).coerceAtLeast(1f), 1.5f))
                }
            }
            return@Canvas
        }
        val bpm = track?.bpm ?: return@Canvas
        if (bpm <= 1.0) return@Canvas
        val bar = 240.0 / bpm
        val bars = (duration / bar).toInt().coerceAtLeast(1).coerceAtMost(256)
        val width = size.width / bars
        for (index in 0 until bars) {
            drawRect(argb(phraseColor(index)), Offset(index * width, 0f), Size(width + 0.6f, size.height))
        }
    }
}

@Composable
internal fun PhaseMeter(
    leftBpm: Double,
    rightBpm: Double,
    leftSec: Double,
    rightSec: Double,
    master: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxWidth()) {
        val rows = listOf(0 to leftBpm to leftSec, 1 to rightBpm to rightSec)
        val gap = 6f
        val rowH = (size.height - gap) / 2f
        rows.forEachIndexed { index, pair ->
            val deck = index
            val bpm = if (deck == 0) leftBpm else rightBpm
            val sec = if (deck == 0) leftSec else rightSec
            val top = index * (rowH + gap)
            drawRect(Color(StagePalette.waveBg), Offset(0f, top), Size(size.width, rowH))
            val beats = 4
            val cell = size.width / beats
            for (beat in 0 until beats) {
                val x = beat * cell
                drawRect(Color(StagePalette.line), Offset(x, top), Size(1f, rowH))
            }
            val phase = os.deckbridge.logic.barPhase(sec, bpm)
            val head = (phase.coerceIn(0.0, 1.0) * size.width).toFloat()
            val ink = if (deck == master) Color(StagePalette.accentOrange) else Color(StagePalette.accentBlue)
            drawRect(ink, Offset(head.coerceAtMost(size.width - 2f), top + 2f), Size(2f, rowH - 4f))
            val otherBpm = if (deck == master) bpm else if (master == 0) leftBpm else rightBpm
            val otherSec = if (master == 0) leftSec else rightSec
            val offset = os.deckbridge.logic.phaseOffsetBeats(sec, bpm, otherSec, otherBpm)
            val mark = ((offset + 2.0) / 4.0).coerceIn(0.0, 1.0).toFloat() * size.width
            drawCircle(Color.White, 3.5f, Offset(mark, top + rowH / 2f))
        }
    }
}

@Composable
internal fun MiniWave(analysis: RbAnalysis?, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(StagePalette.waveBg))) {
        drawOverview(analysis, "RGB", 1.0, 1f, size.height - 1f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOverview(
    analysis: RbAnalysis?,
    mode: String,
    duration: Double,
    top: Float,
    bottom: Float,
) {
    val count = (size.width / 2f).toInt().coerceIn(24, 360)
    if (mode == "RGB") {
        val heights = FloatArray(count)
        val colors = IntArray(count)
        var found = 0
        for (index in 0 until count) {
            val time = duration * index / (count - 1).coerceAtLeast(1)
            val packed = rgbSample(analysis, "RGB", true, time, duration)
            if (packed >= 0) {
                heights[index] = ((packed ushr 32).toInt() and 0xFF) / 255f
                colors[index] = (packed and 0xFFFFFF).toInt()
                found += 1
            }
        }
        if (found > 0) {
            drawRgbColumns(heights, colors, top, bottom)
            return
        }
    }
    if (mode == "BLUE") {
        val mono = FloatArray(count) { index ->
            val time = duration * index / (count - 1).coerceAtLeast(1)
            monoSample(analysis, true, time, duration)
        }
        drawMirroredBands(mono, FloatArray(count), FloatArray(count), top, bottom, waveMode = 2)
        return
    }
    val columns = columnsOf(analysis, overview = true)
    drawMirroredBands(columns.first, columns.second, columns.third, top, bottom, lowGain = 0.9f, midGain = 0.7f, highGain = 0.45f, waveMode = 1)
}

internal fun bandAt(analysis: RbAnalysis?, duration: Double, time: Double, overview: Boolean = false): Triple<Float, Float, Float> {
    if (duration <= 0.0 || time < 0.0 || time > duration) return Triple(0f, 0f, 0f)
    val bands = if (overview) analysis?.bandPreview ?: analysis?.bands else analysis?.bandDetail ?: analysis?.bands
    if (bands != null && bands.columns > 1) {
        val pos = (time / duration).coerceIn(0.0, 1.0)
        val scaled = pos * (bands.columns - 1)
        val index = scaled.toInt().coerceIn(0, bands.columns - 1)
        val next = (index + 1).coerceAtMost(bands.columns - 1)
        val mix = (scaled - index).toFloat()
        fun value(src: ByteArray): Float {
            val a = src[index].toInt() and 0xFF
            val b = src[next].toInt() and 0xFF
            return (a * (1f - mix) + b * mix) / 255f
        }
        return Triple(value(bands.low), value(bands.mid), value(bands.high))
    }
    val wave = analysis?.waveform ?: return Triple(0.08f, 0.05f, 0.02f)
    if (wave.isEmpty()) return Triple(0.08f, 0.05f, 0.02f)
    val index = ((time / duration) * (wave.size - 1)).roundToInt().coerceIn(0, wave.lastIndex)
    val amp = (wave[index].toInt() and 0xFF) / 255f
    return Triple(amp, amp * 0.62f, amp * 0.28f)
}

internal fun columnsOf(analysis: RbAnalysis?, overview: Boolean = false): Triple<FloatArray, FloatArray, FloatArray> {
    val bands = if (overview) analysis?.bandPreview ?: analysis?.bands else analysis?.bandDetail ?: analysis?.bands
    if (bands != null && bands.columns > 1) {
        fun lift(src: ByteArray) = FloatArray(bands.columns) { (src[it].toInt() and 0xFF) / 255f }
        return Triple(lift(bands.low), lift(bands.mid), lift(bands.high))
    }
    val wave = analysis?.waveform
    if (wave != null && wave.isNotEmpty()) {
        val low = FloatArray(wave.size) { (wave[it].toInt() and 0xFF) / 255f }
        return Triple(low, FloatArray(wave.size) { low[it] * 0.6f }, FloatArray(wave.size) { low[it] * 0.25f })
    }
    return Triple(floatArrayOf(0.05f, 0.05f), floatArrayOf(0.03f, 0.03f), floatArrayOf(0.02f, 0.02f))
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMirroredBands(
    low: FloatArray,
    mid: FloatArray,
    high: FloatArray,
    top: Float,
    bottom: Float,
    lowGain: Float = 0.94f,
    midGain: Float = 0.72f,
    highGain: Float = 0.46f,
    waveMode: Int = 1,
) {
    when (waveMode) {
        0 -> {
            drawBand(low, Color(0xFFE23B4A), top, bottom, lowGain)
            drawBand(mid, Color(0xFF3DDC6A), top, bottom, midGain)
            drawBand(high, Color(StagePalette.waveLow), top, bottom, highGain)
        }
        2 -> drawBand(low, Color(StagePalette.waveLow), top, bottom, lowGain)
        else -> {
            drawBand(low, Color(StagePalette.waveLow), top, bottom, lowGain)
            drawBand(mid, Color(StagePalette.waveMid), top, bottom, midGain)
            drawBand(high, Color(StagePalette.waveHigh), top, bottom, highGain)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBand(
    samples: FloatArray,
    color: Color,
    top: Float,
    bottom: Float,
    gain: Float,
) {
    if (samples.size < 2 || bottom <= top) return
    val mid = (top + bottom) / 2f
    val amp = (bottom - top) / 2f * gain
    val path = Path()
    path.moveTo(0f, mid)
    for (index in samples.indices) {
        val x = index / (samples.size - 1f) * size.width
        path.lineTo(x, mid - samples[index].coerceIn(0f, 1f) * amp)
    }
    for (index in samples.indices.reversed()) {
        val x = index / (samples.size - 1f) * size.width
        path.lineTo(x, mid + samples[index].coerceIn(0f, 1f) * amp * 0.9f)
    }
    path.close()
    drawPath(path, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRgbColumns(
    heights: FloatArray,
    colors: IntArray,
    top: Float,
    bottom: Float,
) {
    if (heights.size < 2 || bottom <= top) return
    val mid = (top + bottom) / 2f
    val amp = (bottom - top) / 2f
    val slot = size.width / heights.size
    for (index in heights.indices) {
        val height = heights[index].coerceIn(0f, 1f)
        if (height <= 0.01f) continue
        val color = colors.getOrElse(index) { 0x888888 }
        val red = ((color shr 16) and 0xFF) / 255f
        val green = ((color shr 8) and 0xFF) / 255f
        val blue = (color and 0xFF) / 255f
        val bright = Color(red, green, blue)
        val shade = Color(red * 0.42f, green * 0.42f, blue * 0.42f)
        val reach = height * amp
        val x = index * slot
        drawRect(
            brush = Brush.verticalGradient(listOf(bright, shade), startY = mid - reach, endY = mid),
            topLeft = Offset(x, mid - reach),
            size = Size(slot + 0.6f, reach),
        )
        val lower = reach * 0.92f
        drawRect(
            brush = Brush.verticalGradient(listOf(shade, bright.copy(alpha = 0.72f)), startY = mid, endY = mid + lower),
            topLeft = Offset(x, mid),
            size = Size(slot + 0.6f, lower),
        )
    }
}

private fun smoothRgb(colors: IntArray): IntArray {
    if (colors.size < 5) return colors
    val kernel = floatArrayOf(0.08f, 0.18f, 0.48f, 0.18f, 0.08f)
    return IntArray(colors.size) { index ->
        var red = 0f
        var green = 0f
        var blue = 0f
        for (offset in -2..2) {
            val at = (index + offset).coerceIn(0, colors.lastIndex)
            val color = colors[at]
            val weight = kernel[offset + 2]
            red += ((color shr 16) and 0xFF) * weight
            green += ((color shr 8) and 0xFF) * weight
            blue += (color and 0xFF) * weight
        }
        (red.toInt().coerceIn(0, 255) shl 16) or (green.toInt().coerceIn(0, 255) shl 8) or blue.toInt().coerceIn(0, 255)
    }
}

private fun smooth(samples: FloatArray): FloatArray {
    if (samples.size < 5) return samples
    val kernel = floatArrayOf(0.08f, 0.18f, 0.48f, 0.18f, 0.08f)
    return FloatArray(samples.size) { index ->
        var sum = 0f
        for (offset in -2..2) {
            val at = (index + offset).coerceIn(0, samples.lastIndex)
            sum += samples[at] * kernel[offset + 2]
        }
        sum
    }
}
