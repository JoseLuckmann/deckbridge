package os.deckbridge.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** Quadro do painel: o estado físico (MIDI) e os LEDs que o app mandaria (`ledTargets`). */
class DdjFrame(val surface: DdjSurface, val leds: Map<Int, Int>, val now: Double)

private val Body = Color(0xFF1A1C20)
private val Plate = Color(0xFF24272D)
private val Edge = Color(0xFF3A3F47)
private val Ink = Color(0xFFDDE3EA)
private val Dim = Color(0xFF8A939E)
private val Press = Color(0xFFFFFFFF)
private val Green = Color(0xFF39D98A)
private val Orange = Color(0xFFFF9A3C)
private val Blue = Color(0xFF4FA3FF)
private val Red = Color(0xFFFF4D4D)

/**
 * DDJ-400 vista de cima, em 1280 × 720 unidades: decks iguais (loops em cima, prato, tempo com BEAT SYNC à direita,
 * SHIFT/CUE/PLAY e pads embaixo) e o mixer no meio. A disposição segue a da controladora; as medidas não são do
 * desenho técnico da Pioneer. No modo SAMPLER os pads mostram o que fazem no RMX do app.
 */
@Composable
fun DdjPanel(frame: DdjFrame, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        val u = size.width / 1280f
        val p = Painter(this, measurer, u, frame)
        p.draw()
    }
}

/** O que os pads do SAMPLER fazem no RMX (reduceRmxPad): deck 1 cenas e X-PAD, deck 2 a bateria do X-PAD. */
private val SAMPLER_LABELS = listOf(
    listOf("ECHO UP", "NOISE UP", "SPIRAL UP", "REVERB UP", "ROLL", "TRANS", "DRUM", "HI-PASS"),
    listOf("KICK", "CLAP", "SNARE", "KICK+CLAP", "4 COMP", "8 COMP", "16 COMP", "GAP"),
)

private class Painter(val s: DrawScope, val measurer: TextMeasurer, val u: Float, val f: DdjFrame) {
    val surface = f.surface

    fun led(status: Int, note: Int): Int = f.leds[(status shl 8) or note] ?: 0

    fun o(x: Float, y: Float) = Offset(x * u, y * u)

    fun text(x: Float, y: Float, value: String, size: Float = 13f, color: Color = Dim, center: Boolean = true, bold: Boolean = false, mono: Boolean = false) {
        val style = TextStyle(
            color = color,
            fontSize = (size * u / s.density / s.fontScale).sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
        val layout = measurer.measure(value, style)
        val left = if (center) x * u - layout.size.width / 2f else x * u
        s.drawText(layout, topLeft = Offset(left, y * u - layout.size.height / 2f))
    }

    fun box(x: Float, y: Float, w: Float, h: Float, fill: Color, stroke: Color? = Edge, radius: Float = 6f, width: Float = 2f) {
        s.drawRoundRect(fill, o(x, y), Size(w * u, h * u), CornerRadius(radius * u))
        if (stroke != null) s.drawRoundRect(stroke, o(x, y), Size(w * u, h * u), CornerRadius(radius * u), style = Stroke(width * u))
    }

    /** Botão: preenchido pela cor do LED quando aceso; contorno branco por um instante quando a mão aperta. */
    fun button(x: Float, y: Float, w: Float, h: Float, label: String, lit: Color?, pressed: Boolean, size: Float = 14f) {
        box(x, y, w, h, lit?.copy(alpha = 0.88f) ?: Plate, if (pressed) Press else Edge, width = if (pressed) 4f else 2f)
        text(x + w / 2f, y + h / 2f, label, size, if (lit != null) Color.Black else Ink, bold = true)
    }

    fun round(cx: Float, cy: Float, r: Float, label: String, lit: Color?, pressed: Boolean) {
        s.drawCircle(lit?.copy(alpha = 0.92f) ?: Plate, r * u, o(cx, cy))
        s.drawCircle(if (pressed) Press else Edge, r * u, o(cx, cy), style = Stroke((if (pressed) 4f else 2f) * u))
        text(cx, cy, label, 15f, if (lit != null) Color.Black else Ink, bold = true)
    }

    /** Botão giratório: 0..127 em 270°; marca no centro (64). */
    fun knob(cx: Float, cy: Float, r: Float, value: Int, label: String, accent: Color = Ink) {
        s.drawCircle(Plate, r * u, o(cx, cy))
        s.drawCircle(if (accent == Ink) Edge else accent, r * u, o(cx, cy), style = Stroke(2f * u))
        val angle = Math.toRadians(-225.0 + value / 127.0 * 270.0)
        val tip = Offset((cx + cos(angle).toFloat() * r * 0.85f) * u, (cy + sin(angle).toFloat() * r * 0.85f) * u)
        s.drawLine(accent, o(cx, cy), tip, 4f * u)
        s.drawCircle(Dim, 2f * u, o(cx, cy - r - 4f))
        text(cx, cy + r + 11f, label, 12f)
    }

    fun vFader(x: Float, top: Float, bottom: Float, fraction: Float, label: String, accent: Color, capW: Float = 40f) {
        s.drawLine(Color.Black, o(x, top), o(x, bottom), 6f * u)
        val y = bottom - (bottom - top) * fraction.coerceIn(0f, 1f)
        box(x - capW / 2f, y - 10f, capW, 20f, Color(0xFF3B4048), accent, radius = 3f, width = 3f)
        s.drawLine(accent, o(x - capW / 2f + 4f, y), o(x + capW / 2f - 4f, y), 3f * u)
        text(x, bottom + 16f, label, 12f)
    }

    fun draw() {
        box(2f, 2f, 1276f, 716f, Body, Edge, radius = 18f)
        deck(0, 0f)
        deck(1, 810f)
        mixer()
        log()
    }

    fun deck(index: Int, ox: Float) {
        val d = surface.decks[index]
        val now = f.now
        val ch = 0x90 + index
        text(ox + 20f, 30f, "DECK ${index + 1}", 16f, Ink, center = false, bold = true)
        // Loop: IN, OUT, RELOOP/EXIT, CUE/LOOP CALL ◄ ►.
        val loops = listOf("LOOP_IN" to "IN", "LOOP_OUT" to "OUT", "RELOOP" to "RELOOP", "CALL_LEFT" to "◄ 1/2", "CALL_RIGHT" to "2X ►")
        loops.forEachIndexed { i, (key, label) ->
            button(ox + 108f + i * 58f, 14f, 54f, 32f, label, null, surface.lit(d.pressedAt[key], now), 12f)
        }
        // Prato: anel laranja quando tocado no topo; o traço gira com o ângulo dos passos recebidos.
        val cx = ox + 200f
        val cy = 232f
        val r = 150f
        s.drawCircle(Color(0xFF101114), r * u, o(cx, cy))
        s.drawCircle(if (d.jogTouched) Orange else Edge, r * u, o(cx, cy), style = Stroke((if (d.jogTouched) 8f else 4f) * u))
        s.drawCircle(Color(0xFF2B2F36), r * 0.62f * u, o(cx, cy))
        s.rotate(d.jogAngle.toFloat(), o(cx, cy)) {
            drawLine(Ink, Offset(cx * u, (cy - r * 0.62f) * u), Offset(cx * u, (cy - r + 6f) * u), 6f * u)
        }
        text(cx, cy - 16f, if (d.jogTouched) "TOQUE (VINIL)" else "JOG", 16f, if (d.jogTouched) Orange else Dim, bold = true)
        text(cx, cy + 16f, "%+.0f°".format(d.jogAngle % 360.0), 16f, Ink, mono = true)
        // Tempo à direita do prato, BEAT SYNC em cima. 0 = fader em cima ("−"), 16383 embaixo ("+").
        val syncLed = led(ch, 0x58) > 0
        button(ox + 386f, 62f, 76f, 34f, "SYNC", if (syncLed) Blue else null, surface.lit(d.pressedAt["SYNC"], now), 15f)
        val tempoFraction = 1f - d.tempo / 16383f
        vFader(ox + 424f, 118f, 372f, tempoFraction, "TEMPO", Ink, capW = 44f)
        text(ox + 424f, 410f, "%d".format(d.tempo), 12f, Ink, mono = true)
        text(ox + 392f, 124f, "−", 16f)
        text(ox + 392f, 366f, "+", 16f)
        // SHIFT, CUE e PLAY à esquerda dos pads.
        button(ox + 12f, 444f, 64f, 32f, "SHIFT", if (d.shift) Ink else null, d.shift, 13f)
        round(ox + 44f, 534f, 32f, "CUE", if (led(ch, 0x0C) > 0) Orange else null, surface.lit(d.pressedAt["CUE"], now))
        round(ox + 44f, 622f, 32f, "▶/II", if (led(ch, 0x0B) > 0) Green else null, surface.lit(d.pressedAt["PLAY"], now))
        // Modo de pad (o hardware troca a faixa de notas) e os 8 pads com os LEDs do modo.
        DdjSurface.PadMode.entries.forEachIndexed { i, mode ->
            button(ox + 92f + i * 94f, 440f, 88f, 30f, mode.label, if (d.padMode == mode) Color(0xFFB8C2CF) else null, false, 12f)
        }
        val padStatus = 0x97 + 2 * index
        for (pad in 0..7) {
            val row = pad / 4
            val col = pad % 4
            val x = ox + 92f + col * 94f
            val y = 480f + row * 94f
            val lit = when (d.padMode) {
                DdjSurface.PadMode.HOT_CUE -> led(padStatus, pad) > 0
                DdjSurface.PadMode.BEAT_LOOP -> led(padStatus, 0x60 + pad) > 0
                DdjSurface.PadMode.SAMPLER -> led(0x97, 0x30 + pad) > 0 && index == 0
                DdjSurface.PadMode.BEAT_JUMP -> false
            }
            val color = when (d.padMode) {
                DdjSurface.PadMode.HOT_CUE -> Color(0xFFEFF3F8)
                DdjSurface.PadMode.BEAT_LOOP -> Orange
                DdjSurface.PadMode.SAMPLER -> Blue
                DdjSurface.PadMode.BEAT_JUMP -> Green
            }
            val pressed = surface.lit(d.padAt[pad], now, 0.25)
            button(x, y, 88f, 86f, "${pad + 1}", if (lit) color else null, pressed, 18f)
            if (d.padMode == DdjSurface.PadMode.SAMPLER) {
                text(x + 44f, y + 70f, SAMPLER_LABELS[index][pad], 11f, if (lit) Color.Black else Dim, bold = true)
            }
        }
    }

    fun mixer() {
        val now = f.now
        box(470f, 8f, 340f, 656f, Color(0xFF1F2227), Edge, radius = 10f)
        // Topo: fones, BROWSE e LOAD.
        knob(500f, 46f, 17f, surface.headMix, "H.MIX")
        knob(780f, 46f, 17f, surface.headLevel, "H.LEVEL")
        button(528f, 26f, 70f, 36f, "LOAD", null, surface.lit(surface.pressedAt["LOAD1"], now))
        button(682f, 26f, 70f, 36f, "LOAD", null, surface.lit(surface.pressedAt["LOAD2"], now))
        s.drawCircle(Plate, 26f * u, o(640f, 44f))
        s.drawCircle(if (surface.lit(surface.pressedAt["BROWSE"], now)) Press else Edge, 26f * u, o(640f, 44f), style = Stroke(3f * u))
        s.rotate(surface.browseAngle.toFloat(), o(640f, 44f)) {
            drawLine(Ink, Offset(640f * u, 22f * u), Offset(640f * u, 34f * u), 4f * u)
        }
        text(640f, 84f, "BROWSE", 12f)
        // Canais: TRIM, HI, MID, LOW e o FILTER (CFX).
        for (deck in 0..1) {
            val d = surface.decks[deck]
            val x = if (deck == 0) 508f else 772f
            knob(x, 126f, 17f, d.trim, "TRIM")
            knob(x, 186f, 17f, d.hi, "HI")
            knob(x, 246f, 17f, d.mid, "MID")
            knob(x, 306f, 17f, d.low, "LOW", if (d.low < 20) Red else Ink)
            knob(x, 372f, 20f, surface.filter[deck], "FILTER", if (surface.filter[deck] != 64) Blue else Ink)
            button(x - 30f, 414f, 60f, 30f, "CUE", if (led(0x90 + deck, 0x54) > 0) Orange else null, surface.lit(d.pressedAt["PFL"], now), 13f)
            vFader(x, 468f, 600f, d.volume / 127f, "CH ${deck + 1}", Ink)
            // VU do canal: o valor que o app manda (0xB0/0xB1 0x02).
            val vuX = if (deck == 0) 542f else 726f
            val level = led(0xB0 + deck, 0x02) / 127f
            for (seg in 0 until 12) {
                val on = level >= (seg + 1) / 12f
                val color = when {
                    seg >= 10 -> Red
                    seg >= 8 -> Orange
                    else -> Green
                }
                box(vuX, 590f - seg * 18f, 12f, 14f, if (on) color else Color(0xFF2A2E35), null, radius = 2f)
            }
        }
        // Beat FX no centro.
        box(562f, 108f, 156f, 270f, Color(0xFF191B1F), Edge, radius = 8f)
        text(640f, 126f, "BEAT FX", 14f, Ink, bold = true)
        button(574f, 144f, 132f, 32f, "FX SELECT", null, surface.lit(surface.pressedAt["FX_SELECT"], now), 13f)
        button(574f, 184f, 64f, 32f, "◄ BEAT", null, surface.lit(surface.pressedAt["BEAT_LEFT"], now), 12f)
        button(642f, 184f, 64f, 32f, "BEAT ►", null, surface.lit(surface.pressedAt["BEAT_RIGHT"], now), 12f)
        listOf("1", "2", "MST").forEachIndexed { i, label ->
            button(574f + i * 45f, 224f, 41f, 28f, label, if (surface.fxChannel == i) Color(0xFFB8C2CF) else null, false, 12f)
        }
        knob(604f, 304f, 24f, surface.fxLevel, "LEVEL/DEPTH")
        round(680f, 304f, 26f, "ON", if (led(0x94, 0x47) > 0) Red else null, surface.lit(surface.pressedAt["FX_ON"], now))
        // Crossfader: 0 = deck 1.
        val cx = 570f + 140f * (surface.cross / 127f)
        s.drawLine(Color.Black, o(570f, 636f), o(710f, 636f), 6f * u)
        box(cx - 12f, 618f, 24f, 36f, Color(0xFF3B4048), Ink, radius = 3f, width = 3f)
        text(640f, 606f, "CROSSFADER", 12f)
    }

    fun log() {
        box(8f, 676f, 1264f, 36f, Color(0xFF0E1013), null, radius = 6f)
        val lines = surface.log.toList().takeLast(3)
        val joined = lines.reversed().joinToString("   ·   ") { (time, line) -> "%.2f  %s".format(time, line) }.take(150)
        text(18f, 694f, "MIDI  $joined", 13f, if (lines.isEmpty()) Dim else Ink, center = false, mono = true)
    }
}
