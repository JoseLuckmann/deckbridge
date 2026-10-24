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
 * DDJ-400 vista de cima, em 1280 × 480 unidades: decks iguais (loops em cima, prato, tempo com BEAT SYNC à direita,
 * SHIFT/CUE/PLAY e pads embaixo) e o mixer no meio. A disposição segue a da controladora; as medidas não são do
 * desenho técnico da Pioneer.
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

private class Painter(val s: DrawScope, val measurer: TextMeasurer, val u: Float, val f: DdjFrame) {
    val surface = f.surface

    fun led(status: Int, note: Int): Int = f.leds[(status shl 8) or note] ?: 0

    fun o(x: Float, y: Float) = Offset(x * u, y * u)

    fun text(x: Float, y: Float, value: String, size: Float = 11f, color: Color = Dim, center: Boolean = true, bold: Boolean = false, mono: Boolean = false) {
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

    fun box(x: Float, y: Float, w: Float, h: Float, fill: Color, stroke: Color? = Edge, radius: Float = 5f, width: Float = 1.5f) {
        s.drawRoundRect(fill, o(x, y), Size(w * u, h * u), CornerRadius(radius * u))
        if (stroke != null) s.drawRoundRect(stroke, o(x, y), Size(w * u, h * u), CornerRadius(radius * u), style = Stroke(width * u))
    }

    /** Botão: preenchido pela cor do LED quando aceso; contorno branco por um instante quando a mão aperta. */
    fun button(x: Float, y: Float, w: Float, h: Float, label: String, lit: Color?, pressed: Boolean, size: Float = 10f) {
        box(x, y, w, h, lit?.copy(alpha = 0.85f) ?: Plate, if (pressed) Press else Edge, width = if (pressed) 3f else 1.5f)
        text(x + w / 2f, y + h / 2f, label, size, if (lit != null) Color.Black else Ink, bold = true)
    }

    fun round(cx: Float, cy: Float, r: Float, label: String, lit: Color?, pressed: Boolean) {
        s.drawCircle(lit?.copy(alpha = 0.9f) ?: Plate, r * u, o(cx, cy))
        s.drawCircle(if (pressed) Press else Edge, r * u, o(cx, cy), style = Stroke((if (pressed) 3f else 1.5f) * u))
        text(cx, cy, label, 10f, if (lit != null) Color.Black else Ink, bold = true)
    }

    /** Botão giratório: 0..127 em 270°; marca no centro (64). */
    fun knob(cx: Float, cy: Float, r: Float, value: Int, label: String, accent: Color = Ink) {
        s.drawCircle(Plate, r * u, o(cx, cy))
        s.drawCircle(Edge, r * u, o(cx, cy), style = Stroke(1.5f * u))
        val angle = Math.toRadians(-225.0 + value / 127.0 * 270.0)
        val tip = Offset((cx + cos(angle).toFloat() * r * 0.85f) * u, (cy + sin(angle).toFloat() * r * 0.85f) * u)
        s.drawLine(accent, o(cx, cy), tip, 2.5f * u)
        s.drawCircle(Dim, 1.5f * u, o(cx, cy - r - 3f))
        text(cx, cy + r + 7f, label, 8f)
    }

    fun vFader(x: Float, top: Float, bottom: Float, fraction: Float, label: String, accent: Color, capW: Float = 26f) {
        s.drawLine(Color.Black, o(x, top), o(x, bottom), 4f * u)
        val y = bottom - (bottom - top) * fraction.coerceIn(0f, 1f)
        box(x - capW / 2f, y - 7f, capW, 14f, Color(0xFF3B4048), accent, radius = 2f, width = 2f)
        s.drawLine(accent, o(x - capW / 2f + 3f, y), o(x + capW / 2f - 3f, y), 2f * u)
        text(x, bottom + 11f, label, 8.5f)
    }

    fun draw() {
        box(2f, 2f, 1276f, 476f, Body, Edge, radius = 14f)
        deck(0, 0f)
        deck(1, 810f)
        mixer()
        log()
    }

    fun deck(index: Int, ox: Float) {
        val d = surface.decks[index]
        val now = f.now
        val ch = 0x90 + index
        text(ox + 22f, 14f, "DECK ${index + 1}", 11f, Ink, center = false, bold = true)
        // Loop: IN, OUT, RELOOP/EXIT, CUE/LOOP CALL ◄ ►.
        val loops = listOf("LOOP_IN" to "IN", "LOOP_OUT" to "OUT", "RELOOP" to "RELOOP", "CALL_LEFT" to "◄ 1/2", "CALL_RIGHT" to "2X ►")
        loops.forEachIndexed { i, (key, label) ->
            button(ox + 92f + i * 62f, 8f, 56f, 22f, label, null, surface.lit(d.pressedAt[key], now))
        }
        // Prato: anel claro quando tocado no topo; o traço gira com o ângulo dos passos recebidos.
        val cx = ox + 215f
        val cy = 160f
        val r = 100f
        s.drawCircle(Color(0xFF101114), r * u, o(cx, cy))
        s.drawCircle(if (d.jogTouched) Orange else Edge, r * u, o(cx, cy), style = Stroke((if (d.jogTouched) 5f else 3f) * u))
        s.drawCircle(Color(0xFF2B2F36), r * 0.62f * u, o(cx, cy))
        s.rotate(d.jogAngle.toFloat(), o(cx, cy)) {
            drawLine(Ink, Offset(cx * u, (cy - r * 0.62f) * u), Offset(cx * u, (cy - r + 4f) * u), 4f * u)
        }
        text(cx, cy - 10f, if (d.jogTouched) "TOQUE (VINIL)" else "JOG", 10f, if (d.jogTouched) Orange else Dim, bold = true)
        text(cx, cy + 10f, "%+.0f°".format(d.jogAngle % 360.0), 10f, Ink, mono = true)
        // Tempo à direita do prato, BEAT SYNC em cima. 0 = fader em cima ("−"), 16383 embaixo ("+").
        val syncLed = led(ch, 0x58) > 0
        button(ox + 400f, 40f, 58f, 22f, "SYNC", if (syncLed) Blue else null, surface.lit(d.pressedAt["SYNC"], now))
        val tempoFraction = 1f - d.tempo / 16383f
        vFader(ox + 429f, 76f, 250f, tempoFraction, "TEMPO", Ink, capW = 30f)
        text(ox + 429f, 278f, "%d".format(d.tempo), 8.5f, Ink, mono = true)
        text(ox + 408f, 80f, "−", 10f)
        text(ox + 408f, 246f, "+", 10f)
        // SHIFT, CUE e PLAY à esquerda dos pads.
        button(ox + 16f, 300f, 54f, 22f, "SHIFT", if (d.shift) Ink else null, d.shift)
        round(ox + 43f, 356f, 22f, "CUE", if (led(ch, 0x0C) > 0) Orange else null, surface.lit(d.pressedAt["CUE"], now))
        round(ox + 43f, 412f, 22f, "▶/II", if (led(ch, 0x0B) > 0) Green else null, surface.lit(d.pressedAt["PLAY"], now))
        // Modo de pad (o hardware troca a faixa de notas) e os 8 pads com os LEDs do modo.
        DdjSurface.PadMode.entries.forEachIndexed { i, mode ->
            button(ox + 92f + i * 92f, 296f, 86f, 20f, mode.label, if (d.padMode == mode) Color(0xFFB8C2CF) else null, false, 9f)
        }
        val padStatus = 0x97 + 2 * index
        for (pad in 0..7) {
            val row = pad / 4
            val col = pad % 4
            val x = ox + 92f + col * 92f
            val y = 322f + row * 62f
            val lit = when (d.padMode) {
                DdjSurface.PadMode.HOT_CUE -> led(padStatus, pad) > 0
                DdjSurface.PadMode.BEAT_LOOP -> led(padStatus, 0x60 + pad) > 0
                DdjSurface.PadMode.SAMPLER -> led(0x97, 0x30 + pad) > 0
                DdjSurface.PadMode.BEAT_JUMP -> false
            }
            val color = when (d.padMode) {
                DdjSurface.PadMode.HOT_CUE -> Color(0xFFEFF3F8)
                DdjSurface.PadMode.BEAT_LOOP -> Orange
                DdjSurface.PadMode.SAMPLER -> Blue
                DdjSurface.PadMode.BEAT_JUMP -> Green
            }
            button(x, y, 86f, 56f, "${pad + 1}", if (lit) color else null, surface.lit(d.padAt[pad], now, 0.25))
        }
    }

    fun mixer() {
        val now = f.now
        box(470f, 6f, 340f, 440f, Color(0xFF1F2227), Edge, radius = 8f)
        // Topo: fones, BROWSE e LOAD.
        knob(500f, 34f, 13f, surface.headMix, "H.MIX")
        knob(780f, 34f, 13f, surface.headLevel, "H.LEVEL")
        button(540f, 20f, 50f, 24f, "LOAD", null, surface.lit(surface.pressedAt["LOAD1"], now))
        button(690f, 20f, 50f, 24f, "LOAD", null, surface.lit(surface.pressedAt["LOAD2"], now))
        s.drawCircle(Plate, 18f * u, o(640f, 32f))
        s.drawCircle(if (surface.lit(surface.pressedAt["BROWSE"], now)) Press else Edge, 18f * u, o(640f, 32f), style = Stroke(2f * u))
        s.rotate(surface.browseAngle.toFloat(), o(640f, 32f)) {
            drawLine(Ink, Offset(640f * u, 18f * u), Offset(640f * u, 26f * u), 2.5f * u)
        }
        text(640f, 58f, "BROWSE", 8.5f)
        // Canais: TRIM, HI, MID, LOW e o FILTER (CFX).
        for (deck in 0..1) {
            val d = surface.decks[deck]
            val x = if (deck == 0) 506f else 774f
            knob(x, 84f, 11f, d.trim, "TRIM")
            knob(x, 126f, 11f, d.hi, "HI")
            knob(x, 168f, 11f, d.mid, "MID")
            knob(x, 210f, 11f, d.low, "LOW", if (d.low < 20) Red else Ink)
            knob(x, 254f, 13f, surface.filter[deck], "FILTER", if (surface.filter[deck] != 64) Blue else Ink)
            button(x - 22f, 284f, 44f, 20f, "CUE", if (led(0x90 + deck, 0x54) > 0) Orange else null, surface.lit(d.pressedAt["PFL"], now))
            vFader(x, 322f, 404f, d.volume / 127f, "CH ${deck + 1}", Ink)
            // VU do canal: o valor que o app manda (0xB0/0xB1 0x02).
            val vuX = if (deck == 0) 540f else 732f
            val level = led(0xB0 + deck, 0x02) / 127f
            for (seg in 0 until 12) {
                val on = level >= (seg + 1) / 12f
                val color = when {
                    seg >= 10 -> Red
                    seg >= 8 -> Orange
                    else -> Green
                }
                box(vuX, 380f - seg * 13f, 8f, 10f, if (on) color else Color(0xFF2A2E35), null, radius = 1f)
            }
        }
        // Beat FX no centro.
        box(560f, 74f, 160f, 190f, Color(0xFF191B1F), Edge, radius = 6f)
        text(640f, 86f, "BEAT FX", 10f, Ink, bold = true)
        button(574f, 98f, 132f, 22f, "FX SELECT", null, surface.lit(surface.pressedAt["FX_SELECT"], now))
        button(574f, 126f, 62f, 22f, "◄ BEAT", null, surface.lit(surface.pressedAt["BEAT_LEFT"], now))
        button(644f, 126f, 62f, 22f, "BEAT ►", null, surface.lit(surface.pressedAt["BEAT_RIGHT"], now))
        listOf("1", "2", "MST").forEachIndexed { i, label ->
            button(574f + i * 45f, 154f, 41f, 20f, label, if (surface.fxChannel == i) Color(0xFFB8C2CF) else null, false, 9f)
        }
        knob(605f, 206f, 17f, surface.fxLevel, "LEVEL/DEPTH")
        round(677f, 206f, 19f, "ON", if (led(0x94, 0x47) > 0) Red else null, surface.lit(surface.pressedAt["FX_ON"], now))
        // Crossfader: 0 = deck 1.
        val cx = 580f + 120f * (surface.cross / 127f)
        s.drawLine(Color.Black, o(580f, 428f), o(700f, 428f), 4f * u)
        box(cx - 9f, 416f, 18f, 24f, Color(0xFF3B4048), Ink, radius = 2f, width = 2f)
        text(640f, 410f, "CROSSFADER", 8.5f)
    }

    fun log() {
        box(8f, 450f, 1264f, 24f, Color(0xFF0E1013), null, radius = 4f)
        val lines = surface.log.toList().takeLast(3)
        val joined = lines.reversed().joinToString("   ·   ") { (time, line) -> "%.2f  %s".format(time, line) }.take(190)
        text(16f, 462f, "MIDI  $joined", 10f, if (lines.isEmpty()) Dim else Ink, center = false, mono = true)
    }
}
