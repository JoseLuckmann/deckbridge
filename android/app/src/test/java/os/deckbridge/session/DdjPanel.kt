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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Quadro do painel: o estado físico (MIDI) e os LEDs que o app mandaria (`ledTargets`). */
class DdjFrame(val surface: DdjSurface, val leds: Map<Int, Int>, val now: Double)

private val Body = Color(0xFF0B0C0E)
private val DeckPlate = Color(0xFF17181B)
private val MixPlate = Color(0xFF121315)
private val Edge = Color(0xFF30333A)
private val Silver = Color(0xFFA9AFB8)
private val Cap = Color(0xFF2B2D32)
private val Ink = Color(0xFFDDE3EA)
private val Dim = Color(0xFF8A939E)
private val Press = Color(0xFFFFFFFF)
private val Green = Color(0xFF39D98A)
private val Orange = Color(0xFFFF9A1F)
private val PadEdge = Color(0xFF9C3B22)
private val RedLit = Color(0xFFE5483F)
private val Blue = Color(0xFF3F9BFF)
private val Red = Color(0xFFFF4D4D)

/** Largura e altura de projeto: a foto da DDJ-400 de cima (894 × 505). Tudo é desenhado nessa grade e escalado. */
private const val W = 894f
private const val H = 505f

/**
 * O que os pads do SAMPLER fazem no RMX (reduceRmxPad): deck 1 cenas e X-PAD, deck 2 a bateria do X-PAD. Os pads ROLL,
 * TRANS, DRUM e HI-PASS: tocar liga e desliga; segurar toca enquanto aperta e o roll acelera. O BEAT FX ON/OFF é o HOLD (soltar
 * o pad mantém) e o LEVEL/DEPTH é o knob: velocidade do roll segurado ou intensidade da cena.
 */
private val SAMPLER_LABELS = listOf(
    listOf("ECHO UP", "NOISE UP", "SPIRAL UP", "REVERB UP", "ROLL", "TRANS", "DRUM", "HI-PASS"),
    listOf("KICK", "CLAP", "SNARE", "KICK+CLAP", "4 COMP", "8 COMP", "16 COMP", "GAP"),
)
private val LOOP_LABELS = listOf("1/4", "1/2", "1", "2", "4", "8", "16", "32")
private val JUMP_LABELS = listOf("◄ 1", "1 ►", "◄ 2", "2 ►", "◄ 4", "4 ►", "◄ 8", "8 ►")

/**
 * DDJ-400 vista de cima, na disposição e na proporção da controladora: em cada deck, IN/4BEAT, OUT, RELOOP/EXIT, CUE/LOOP
 * CALL e o BEAT SYNC "MASTER" em cima, o prato grande, o fader de tempo na lateral, SHIFT, CUE e PLAY embaixo à esquerda e
 * os modos de pad com os oito pads; no meio o mixer (BROWSE, TRIM, EQ, FILTER, fones, CUE, faders e crossfader) e, à direita
 * dele, o Beat FX. As medidas vêm de uma foto de produto, não do desenho técnico da Pioneer. No modo SAMPLER os pads mostram
 * o que fazem no RMX do app. Abaixo da controladora fica a linha com o último MIDI recebido.
 */
@Composable
fun DdjPanel(frame: DdjFrame, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        Painter(this, measurer, size.width / W, frame).draw()
    }
}

private class Painter(val s: DrawScope, val measurer: TextMeasurer, val u: Float, val f: DdjFrame) {
    val surface = f.surface

    fun led(status: Int, note: Int): Int = f.leds[(status shl 8) or note] ?: 0

    fun o(x: Float, y: Float) = Offset(x * u, y * u)

    /** Texto centrado em (x, y); `size` em unidades de projeto (7 unidades ≈ 10 px a 1280 de largura). */
    fun text(x: Float, y: Float, value: String, size: Float = 7f, color: Color = Dim, center: Boolean = true, bold: Boolean = false, mono: Boolean = false) {
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

    /** Retângulo arredondado centrado em (cx, cy). */
    fun rect(cx: Float, cy: Float, w: Float, h: Float, fill: Color, stroke: Color? = Edge, radius: Float = 3f, width: Float = 1f) {
        s.drawRoundRect(fill, o(cx - w / 2f, cy - h / 2f), Size(w * u, h * u), CornerRadius(radius * u))
        if (stroke != null) s.drawRoundRect(stroke, o(cx - w / 2f, cy - h / 2f), Size(w * u, h * u), CornerRadius(radius * u), style = Stroke(width * u))
    }

    fun circle(cx: Float, cy: Float, r: Float, fill: Color?, stroke: Color? = null, width: Float = 1f) {
        if (fill != null) s.drawCircle(fill, r * u, o(cx, cy))
        if (stroke != null) s.drawCircle(stroke, r * u, o(cx, cy), style = Stroke(width * u))
    }

    /** Botão retangular pequeno: aceso com a cor do LED, contorno branco grosso enquanto a mão aperta. */
    fun button(cx: Float, cy: Float, w: Float, h: Float, label: String, lit: Color?, pressed: Boolean, size: Float = 5.5f, edge: Color = Edge) {
        rect(cx, cy, w, h, lit?.copy(alpha = 0.92f) ?: Cap, if (pressed) Press else edge, width = if (pressed) 2.2f else 1f)
        if (label.isNotEmpty()) text(cx, cy, label, size, if (lit != null) Color.Black else Ink, bold = true)
    }

    /** Botão redondo (IN, OUT, SYNC, CUE, PLAY, ON/OFF). */
    fun round(cx: Float, cy: Float, r: Float, label: String, fill: Color, ring: Color, pressed: Boolean, ink: Color = Ink, size: Float = 6f) {
        circle(cx, cy, r, fill)
        circle(cx, cy, r, null, if (pressed) Press else ring, if (pressed) 2.4f else 1.4f)
        if (label.isNotEmpty()) text(cx, cy, label, size, ink, bold = true)
    }

    /** Botão giratório: 0..127 em 270°, ponteiro branco; o anel fica da cor do acento quando fora do centro. */
    fun knob(cx: Float, cy: Float, r: Float, value: Int, accent: Color = Ink) {
        circle(cx, cy, r + 2f, Color(0xFF1A1B1F), Edge, 1f)
        circle(cx, cy, r, Cap, if (accent == Ink) Silver else accent, 1.2f)
        val angle = Math.toRadians(-225.0 + value / 127.0 * 270.0)
        val tip = Offset((cx + cos(angle).toFloat() * r * 0.9f) * u, (cy + sin(angle).toFloat() * r * 0.9f) * u)
        s.drawLine(Color.White, o(cx, cy), tip, 2f * u)
    }

    fun vSlot(x: Float, top: Float, bottom: Float) {
        s.drawLine(Color.Black, o(x, top), o(x, bottom), 3.2f * u)
        s.drawLine(Edge, o(x - 2.2f, top), o(x - 2.2f, bottom), 0.8f * u)
        s.drawLine(Edge, o(x + 2.2f, top), o(x + 2.2f, bottom), 0.8f * u)
    }

    fun draw() {
        s.drawRect(Body, Offset.Zero, s.size)
        // Chapas: dois decks e o mixer, com a borda prateada da controladora.
        rect(152.5f, 252f, 304f, 494f, DeckPlate, Edge, 6f, 1.4f)
        rect(741.5f, 252f, 304f, 494f, DeckPlate, Edge, 6f, 1.4f)
        rect(446f, 252f, 278f, 494f, MixPlate, Edge, 6f, 1.4f)
        deck(0, 0f)
        deck(1, 589f)
        mixer()
        beatFx()
        log()
    }

    fun deck(index: Int, ox: Float) {
        val d = surface.decks[index]
        val now = f.now
        val ch = 0x90 + index
        // Fileira de cima: IN/4BEAT, OUT, RELOOP/EXIT, CUE/LOOP CALL ◄ ► e o BEAT SYNC "MASTER".
        text(ox + 43f, 41f, "IN / ÷4BEAT", 5f, Silver)
        text(ox + 90f, 41f, "OUT", 5f, Silver)
        round(ox + 43f, 60f, 16f, "", Orange, Color(0xFFFFC36B), surface.lit(d.pressedAt["LOOP_IN"], now))
        round(ox + 90f, 60f, 16f, "", Orange, Color(0xFFFFC36B), surface.lit(d.pressedAt["LOOP_OUT"], now))
        text(ox + 43f, 85f, "IN ADJUST", 4.5f)
        text(ox + 90f, 85f, "OUT ADJUST", 4.5f)
        text(ox + 143f, 41f, "RELOOP/EXIT", 5f, Silver)
        button(ox + 143f, 60f, 30f, 20f, "LOOP", null, surface.lit(d.pressedAt["RELOOP"], now), 5f)
        text(ox + 143f, 85f, "ACTIVE LOOP", 4.5f)
        text(ox + 206f, 41f, "CUE/LOOP CALL", 5f, Silver)
        button(ox + 190f, 60f, 22f, 16f, "◄", null, surface.lit(d.pressedAt["CALL_LEFT"], now), 7f)
        button(ox + 222f, 60f, 22f, 16f, "►", null, surface.lit(d.pressedAt["CALL_RIGHT"], now), 7f)
        text(ox + 190f, 76f, "1/2X", 4.5f)
        text(ox + 206f, 76f, "LOOP", 4.5f)
        text(ox + 222f, 76f, "2X", 4.5f)
        text(ox + 265f, 41f, "MASTER", 5f, Silver)
        val syncLit = led(ch, 0x58) > 0
        round(ox + 265f, 60f, 16f, "BEAT SYNC", if (syncLit) RedLit else Cap, RedLit, surface.lit(d.pressedAt["SYNC"], now), if (syncLit) Color.White else Ink, 4.4f)
        text(ox + 265f, 85f, "TEMPO RANGE", 4.5f)

        // Prato: anel externo (laranja quando a mão toca o topo), borda prateada, platter, logo e marcador do ângulo.
        val cx = ox + 130f
        val cy = 218f
        circle(cx, cy, 104f, Color(0xFF0E0F11), if (d.jogTouched) Orange else Color(0xFF3A3D44), if (d.jogTouched) 3.4f else 2.2f)
        text(cx - 66f, cy - 78f, "SEARCH", 4.6f, Dim, bold = true)
        circle(cx, cy, 78f, Color(0xFF1B1C20), Silver, 2.6f)
        circle(cx, cy, 62f, null, Color(0xFF26282D), 0.8f)
        circle(cx, cy, 48f, null, Color(0xFF26282D), 0.8f)
        circle(cx, cy, 32f, Color(0xFF111215), Silver, 1.6f)
        hexagon(cx, cy, 20f)
        s.rotate(d.jogAngle.toFloat(), o(cx, cy)) {
            drawLine(Color.White, Offset(cx * u, (cy - 60f) * u), Offset(cx * u, (cy - 76f) * u), 3f * u)
        }
        if (d.jogTouched) text(cx, cy - 92f, "TOQUE (VINIL)", 6f, Orange, bold = true)
        text(cx, cy + 92f, "%+.0f°".format(d.jogAngle % 360.0), 5.5f, Ink, mono = true)

        // Tempo: fader na lateral direita, "+" embaixo e "–" em cima (0 = todo em cima).
        text(ox + 267f, 322f, "%d".format(d.tempo), 5f, Dim, mono = true)
        vSlot(ox + 267f, 332f, 470f)
        listOf(332f to "–", 401f to "0", 470f to "+").forEach { (y, label) ->
            text(ox + 246f, y, label, 6f, Silver, bold = true)
            s.drawLine(Edge, o(ox + 255f, y), o(ox + 261f, y), 1f * u)
        }
        val tempoY = 332f + (470f - 332f) * (d.tempo / 16383f)
        rect(ox + 267f, tempoY, 30f, 15f, Cap, Silver, 2f, 1.2f)
        s.drawLine(Color.White, o(ox + 256f, tempoY), o(ox + 278f, tempoY), 1.2f * u)
        text(ox + 267f, 482f, "TEMPO", 5.5f, Silver, bold = true)

        // SHIFT e REV/FWD do prato.
        text(ox + 40f, 330f, "SHIFT", 5.5f, Silver, bold = true)
        button(ox + 40f, 347f, 20f, 14f, "", if (d.shift) Ink else null, d.shift)
        text(ox + 82f, 348f, "– REV", 5.2f, Silver)
        text(ox + 218f, 348f, "FWD +", 5.2f, Silver)

        // CUE e PLAY/PAUSE redondos, embaixo à esquerda.
        val cueLit = led(ch, 0x0C) > 0
        val playLit = led(ch, 0x0B) > 0
        circle(ox + 38f, 396f, 25f, null, Color(0xFF2A2C31), 1f)
        round(ox + 38f, 396f, 20f, "CUE", if (cueLit) Orange else Cap, Silver, surface.lit(d.pressedAt["CUE"], now), if (cueLit) Color.Black else Orange, 6.5f)
        circle(ox + 38f, 446f, 25f, null, Color(0xFF2A2C31), 1f)
        round(ox + 38f, 446f, 20f, "▶/II", if (playLit) Green else Cap, Silver, surface.lit(d.pressedAt["PLAY"], now), if (playLit) Color.Black else Green, 6.5f)
        text(ox + 38f, 478f, "PLAY/PAUSE", 5.2f, Silver, bold = true)

        // Modos de pad (a faixa de notas que o hardware manda) e os oito pads.
        val modes = DdjSurface.PadMode.entries
        val subs = listOf("KEYBOARD", "PAD FX1", "PAD FX2", "KEY SHIFT")
        modes.forEachIndexed { i, mode ->
            val x = ox + 92f + 43f * i
            val on = d.padMode == mode
            rect(x, 376f, 39f, 15f, if (on) RedLit else Cap, PadEdge, 2f, 1f)
            text(x, 376f, mode.label, 5.3f, if (on) Color.White else Color(0xFFFF8A7A), bold = true)
            text(x, 389f, subs[i], 4f)
        }
        val padStatus = 0x97 + 2 * index
        for (pad in 0..7) {
            val col = pad % 4
            val row = pad / 4
            val x = ox + 92f + 43f * col
            val y = 414f + 38f * row
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
            rect(x, y, 40f, 34f, if (lit) color.copy(alpha = 0.95f) else Color(0xFF1B1C20), if (pressed) Press else PadEdge, 4f, if (pressed) 2.4f else 1.3f)
            val label = when (d.padMode) {
                DdjSurface.PadMode.HOT_CUE -> "${pad + 1}"
                DdjSurface.PadMode.BEAT_LOOP -> LOOP_LABELS[pad]
                DdjSurface.PadMode.BEAT_JUMP -> JUMP_LABELS[pad]
                DdjSurface.PadMode.SAMPLER -> SAMPLER_LABELS[index][pad]
            }
            text(x, y, label, if (label.length > 6) 5.3f else 7f, if (lit) Color.Black else Color(0xFFC9CED6), bold = true)
        }
    }

    fun hexagon(cx: Float, cy: Float, r: Float) {
        for (i in 0 until 6) {
            val a = PI / 3.0 * i + PI / 6.0
            val b = PI / 3.0 * (i + 1) + PI / 6.0
            s.drawLine(
                Silver,
                o(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat()),
                o(cx + (r * cos(b)).toFloat(), cy + (r * sin(b)).toFloat()),
                1.6f * u,
            )
        }
        circle(cx, cy, 6f, null, Silver, 1.2f)
    }

    fun mixer() {
        val now = f.now
        val mx = 446f
        // Topo: LOAD, BROWSE giratório e os knobs de fones/master.
        button(372f, 40f, 34f, 14f, "LOAD", null, surface.lit(surface.pressedAt["LOAD1"], now), 5.5f)
        button(508f, 40f, 34f, 14f, "LOAD", null, surface.lit(surface.pressedAt["LOAD2"], now), 5.5f)
        val browsePressed = surface.lit(surface.pressedAt["BROWSE"], now)
        circle(mx, 38f, 15f, Cap, if (browsePressed) Press else Silver, if (browsePressed) 2.4f else 1.4f)
        s.rotate(surface.browseAngle.toFloat(), o(mx, 38f)) {
            drawLine(Color.White, Offset(mx * u, 26f * u), Offset(mx * u, 33f * u), 2f * u)
        }
        text(mx, 60f, "BROWSE", 4.6f)

        // TRIM, MASTER LEVEL (sem MIDI no mapa: fica no ponto 0 dB), meter, EQ e FILTER.
        text(410f, 62f, "TRIM", 5f, Silver, bold = true)
        text(485f, 62f, "TRIM", 5f, Silver, bold = true)
        text(548f, 50f, "MASTER", 5f, Silver, bold = true)
        text(548f, 58f, "LEVEL", 5f, Silver, bold = true)
        text(446f, 110f, "CHANNEL", 4.6f, Silver)
        text(446f, 117f, "LEVEL", 4.6f, Silver)
        knob(548f, 82f, 11f, 96)
        button(548f, 124f, 26f, 13f, "CUE", null, false, 5f, Orange)

        for (deck in 0..1) {
            val d = surface.decks[deck]
            val x = if (deck == 0) 410f else 485f
            val labelX = if (deck == 0) 378f else 517f
            knob(x, 82f, 10f, d.trim)
            knob(x, 128f, 10f, d.hi)
            knob(x, 171f, 10f, d.mid)
            knob(x, 214f, 10f, d.low, if (d.low < 20) Red else Ink)
            text(labelX, 128f, "HI", 5.5f, Silver, bold = true)
            text(labelX, 171f, "MID", 5.5f, Silver, bold = true)
            text(labelX, 214f, "LOW", 5.5f, Silver, bold = true)
            text(x, 246f, "FILTER", 5.2f, Silver, bold = true)
            knob(x, 268f, 12f, surface.filter[deck], if (surface.filter[deck] != 64) Blue else Ink)
            text(x - 17f, 285f, "LPF", 4.2f)
            text(x + 17f, 285f, "HPF", 4.2f)
            val cueLit = led(0x90 + deck, 0x54) > 0
            button(x, 310f, 30f, 14f, "CUE", if (cueLit) Orange else null, surface.lit(d.pressedAt["PFL"], now), 5.5f, Orange)
            text(x, 331f, "${deck + 1}", 8f, Ink, bold = true)
            // Fader de canal com a escala de 0 a 10 dos dois lados.
            vSlot(x, 345f, 420f)
            for (n in 0..10 step 2) {
                val y = 420f - 75f * n / 10f
                text(x - 21f, y, "$n", 4f)
                text(x + 21f, y, "$n", 4f)
            }
            val y = 420f - 75f * (d.volume / 127f)
            rect(x, y, 36f, 15f, Cap, Silver, 2f, 1.2f)
            s.drawLine(Color.White, o(x - 15f, y), o(x + 15f, y), 1.4f * u)
            // Meter do canal: o valor que o app manda (0xB0/0xB1 0x02).
            val meterX = if (deck == 0) 436f else 456f
            val level = led(0xB0 + deck, 0x02) / 127f
            for (seg in 0 until 12) {
                val on = level >= (seg + 1) / 12f
                val color = when {
                    seg >= 10 -> Red
                    seg >= 8 -> Orange
                    else -> Green
                }
                rect(meterX, 215f - seg * 8f, 9f, 5.5f, if (on) color else Color(0xFF2A2E35), null, 1f)
            }
        }
        // Fones: MIXING e LEVEL, à esquerda do mixer.
        text(343f, 202f, "HEADPHONES", 5f, Silver, bold = true)
        text(343f, 220f, "MIXING", 5f, Silver, bold = true)
        knob(343f, 242f, 11f, surface.headMix)
        text(327f, 258f, "CUE", 4.2f)
        text(359f, 258f, "MASTER", 4.2f)
        text(343f, 284f, "LEVEL", 5f, Silver, bold = true)
        knob(343f, 304f, 11f, surface.headLevel)

        // Crossfader: 0 = deck 1.
        text(446f, 448f, "CROSSFADER", 4.6f)
        s.drawLine(Color.Black, o(407f, 475f), o(485f, 475f), 3.2f * u)
        val cx = 407f + 78f * (surface.cross / 127f)
        rect(cx, 475f, 16f, 24f, Cap, Silver, 2f, 1.2f)
        s.drawLine(Color.White, o(cx, 466f), o(cx, 484f), 1.4f * u)
    }

    fun beatFx() {
        val now = f.now
        val bx = 548f
        text(bx, 150f, "BEAT FX", 6f, Silver, bold = true)
        s.drawLine(Edge, o(522f, 156f), o(574f, 156f), 1f * u)
        text(bx, 166f, "BEAT", 5f, Silver, bold = true)
        button(bx - 17f, 180f, 24f, 14f, "◄", null, surface.lit(surface.pressedAt["BEAT_LEFT"], now), 7f)
        button(bx + 17f, 180f, 24f, 14f, "►", null, surface.lit(surface.pressedAt["BEAT_RIGHT"], now), 7f)
        text(bx - 17f, 194f, "AUTO", 4.2f)
        text(bx + 17f, 194f, "TAP", 4.2f)
        text(bx, 208f, "FX SELECT", 5f, Silver, bold = true)
        val select = surface.lit(surface.pressedAt["FX_SELECT"], now)
        button(bx - 14f, 222f, 22f, 12f, "▼", null, select, 5.5f)
        button(bx + 14f, 222f, 22f, 12f, "▲", null, select, 5.5f)
        // Chave de canal: 1, 2 e MASTER.
        listOf("1", "2", "MASTER").forEachIndexed { i, label ->
            val x = bx - 22f + 22f * i
            text(x, 244f, label, if (i == 2) 4.4f else 5.5f, if (surface.fxChannel == i) Color.White else Silver, bold = true)
        }
        s.drawLine(Color.Black, o(bx - 22f, 256f), o(bx + 22f, 256f), 3.4f * u)
        rect(bx - 22f + 22f * surface.fxChannel, 256f, 13f, 12f, Cap, Silver, 2f, 1.2f)
        // LEVEL/DEPTH e ON/OFF azul.
        text(bx, 282f, "LEVEL/DEPTH", 5f, Silver, bold = true)
        knob(bx, 312f, 13f, surface.fxLevel)
        text(bx - 17f, 330f, "MIN", 4.2f)
        text(bx + 17f, 330f, "MAX", 4.2f)
        text(bx, 360f, "ON/OFF", 5f, Silver, bold = true)
        val on = led(0x94, 0x47) > 0
        round(bx, 384f, 15f, "", if (on) Blue else Color(0xFF14243A), Blue, surface.lit(surface.pressedAt["FX_ON"], now))
        text(bx, 406f, "RELEASE FX", 4.2f)
    }

    fun log() {
        // Linha do último MIDI, abaixo da área da controladora.
        val top = H * u
        val height = s.size.height - top
        if (height < 8f) return
        s.drawRect(Color(0xFF0E1013), Offset(0f, top), Size(s.size.width, height))
        val lines = surface.log.toList().takeLast(3)
        val joined = lines.reversed().joinToString("   ·   ") { (time, line) -> "%.2f  %s".format(time, line) }.take(150)
        text(10f, (top + height / 2f) / u, "MIDI  $joined", 7f, if (lines.isEmpty()) Dim else Ink, center = false, mono = true)
    }
}
