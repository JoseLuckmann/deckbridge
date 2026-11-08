package os.deckbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import os.deckbridge.logic.DRUM_VOICES
import os.deckbridge.logic.RMX_HOLD_MS
import os.deckbridge.logic.RmxState
import os.deckbridge.logic.SCENE_BARS
import os.deckbridge.logic.SCENE_NAMES
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.XBEAT_LABELS
import os.deckbridge.logic.XPAD_NAMES
import os.deckbridge.logic.drumPatternLabel
import os.deckbridge.logic.rmxKnob
import os.deckbridge.logic.rmxKnobLabel
import os.deckbridge.logic.rmxKnobValue
import os.deckbridge.logic.rmxRolling
import os.deckbridge.logic.rmxScenePress
import os.deckbridge.logic.rmxToggleHold
import os.deckbridge.logic.rmxXPadDown
import os.deckbridge.logic.rmxXPadUp
import os.deckbridge.shared.KnobDial
import os.deckbridge.shared.drawTextFitting
import os.deckbridge.shared.knobGestures

/** Os rolls do X-PAD, do mais lento (esquerda da faixa) ao mais rápido (direita): `xBeat = 4 - célula`. */
private val STRIP_CELLS = listOf("1", "1/2", "1/4", "1/8", "1/16")

/** Os bumbos do DRUM-roll em compassos (o SUB PARAMETER 2). */
private val ROLL_BARS = listOf(4, 8, 16)

/**
 * O RMX acoplado, na disposição do RMX-1000: à esquerda o ISOLATOR (três botões LOW, MID e HI), no meio o X-PAD FX (os
 * efeitos, o HOLD verde e a faixa de toque com os LEDs do TEMPO), o anel do SCENE FX com o knob no centro (as cenas de
 * subida em vermelho em cima, as de queda em azul embaixo) e, à direita, o RELEASE FX e os dois SUB PARAMETER. O RMX vale
 * sempre no master: não há seletor de canal. Ocupa o lugar dos hot cues e dos cartões dos decks, com as ondas à vista em
 * cima para ver o ponto da música em que se aplica.
 */
@Composable
fun RmxDock(rmx: RmxState, onRmx: (RmxState) -> Unit, onClose: () -> Unit, compact: Boolean, modifier: Modifier = Modifier, bpm: Double = 0.0) {
    // O estado de agora, para quem decide ao soltar o dedo (o gesto começou com o de antes).
    val latest = rememberUpdatedState(rmx)
    val send = rememberUpdatedState(onRmx)
    val update: ((RmxState) -> RmxState) -> Unit = { change -> send.value(change(latest.value)) }
    val build = rmx.scene < 4
    val accent = Color(if (build) StagePalette.accentOrange else StagePalette.accentBlue)
    BoxWithConstraints(modifier) {
        val tight = maxHeight < 300.dp
        Column(Modifier.fillMaxSize().border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                TextInk("REMIX FX", color = accent, fontSize = if (compact) 13.sp else 16.sp, weight = FontWeight.Bold)
                TextInk(
                    // Rolando (X-PAD segurado ou mantido pelo HOLD) o cabeçalho diz a velocidade; o anel é o knob dela.
                    if (rmxRolling(rmx)) "${XPAD_NAMES[rmx.xMode.coerceIn(0, XPAD_NAMES.lastIndex)].uppercase()} ${rmxKnobLabel(rmx)}${if (rmx.latch) "  ·  HOLD" else "  ·  segurando"}  ·  gire o anel"
                    else "${rmx.sceneName()}  ·  ${(rmx.intensity * 100).toInt()}%  ·  ${if (rmx.xOn && rmx.xMode == 2) "DRUM ${drumPatternLabel(rmx)}" else "${rmx.bars} compassos"}${if (rmx.latch) "  ·  HOLD" else ""}",
                    fontSize = if (compact) 11.sp else 13.sp, color = Color(StagePalette.inkMuted), maxLines = 1, modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                // O visor de BPM do RMX-1000 (vermelho, de LED).
                if (bpm > 1.0) {
                    TextInk("%.0f".format(bpm), color = Color(StagePalette.onAir), fontSize = if (compact) 16.sp else 20.sp, weight = FontWeight.Bold, mono = true)
                    TextInk(" BPM", color = Color(StagePalette.inkMuted), fontSize = 10.sp, modifier = Modifier.padding(end = 10.dp))
                }
                TextInk("FECHAR", color = Color(StagePalette.accentBlue), fontSize = if (compact) 12.sp else 14.sp, weight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IsolatorSection(rmx, update, tight, Modifier.weight(0.9f).fillMaxHeight())
                XPadSection(rmx, update, tight, Modifier.weight(1.45f).fillMaxHeight())
                SceneRing(rmx, update, tight, Modifier.fillMaxHeight().aspectRatio(1f))
                ReleaseSection(rmx, update, tight, Modifier.weight(0.75f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier.border(1.dp, Color(StagePalette.line)).padding(horizontal = 5.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        TextInk(title, fontSize = 10.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted), maxLines = 1)
        content()
    }
}

/** Um botão do painel: rótulo, aceso ou não, com a cor do LED ([tone]: verde no X-PAD, azul nos outros). */
@Composable
private fun RmxButton(label: String, selected: Boolean, modifier: Modifier, tone: Color = Color(StagePalette.accentBlue), fontSize: Int = 12, onClick: () -> Unit) {
    val lit = selected
    Box(
        modifier.background(if (lit) tone.copy(alpha = 0.22f) else Color(StagePalette.panelRaised)).border(1.dp, if (lit) tone else Color(StagePalette.line)).clickable(onClick = onClick).padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = if (lit) tone else Color(StagePalette.ink), fontSize = fontSize.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

// ---- ISOLATOR ------------------------------------------------------------------------------------------------------

/** ISOLATOR do master: três botões. O ganho vai de 0 (KILL) a 2, com 1 (0 dB) no meio do curso. */
@Composable
private fun IsolatorSection(rmx: RmxState, update: ((RmxState) -> RmxState) -> Unit, tight: Boolean, modifier: Modifier) {
    Section("ISOLATOR", modifier) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("LOW" to rmx.low[0], "MID" to rmx.mid[0], "HI" to rmx.high[0]).forEachIndexed { band, (label, gain) ->
                IsoKnob(label, gain, Modifier.weight(1f).fillMaxHeight()) { g ->
                    update { s ->
                        when (band) {
                            0 -> s.copy(low = listOf(g, g))
                            1 -> s.copy(mid = listOf(g, g))
                            else -> s.copy(high = listOf(g, g))
                        }
                    }
                }
            }
        }
        RmxButton(if (rmx.gate) "GATE ●" else "GATE", rmx.gate, Modifier.fillMaxWidth().height(if (tight) 28.dp else 34.dp)) { update { it.copy(gate = !it.gate) } }
    }
}

private fun isoDb(gain: Float): String {
    if (gain <= 0.02f) return "KILL"
    return "%+.0fdB".format(20.0 * kotlin.math.log10(gain.toDouble()))
}

@Composable
private fun IsoKnob(label: String, gain: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    val kill = gain <= 0.02f
    val density = LocalDensity.current.density
    val v = (gain / 2f).coerceIn(0f, 1f)
    BoxWithConstraints(modifier.knobGestures(true, label, v, density, true, "ISOLATOR $label", v) { onChange(it * 2f) }, contentAlignment = Alignment.Center) {
        val dial: Dp = minOf(maxWidth - 2.dp, maxHeight - 34.dp).coerceIn(26.dp, 96.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KnobDial(v, true, Color(if (kill) StagePalette.onAir else StagePalette.accentBlue), if (dial > 46.dp) 6f else 4.5f, Modifier.size(dial))
            TextInk(label, fontSize = 11.sp, weight = FontWeight.Bold, maxLines = 1)
            TextInk(isoDb(gain), fontSize = 9.sp, mono = true, color = Color(if (kill) StagePalette.onAir else StagePalette.inkMuted), maxLines = 1)
        }
    }
}

// ---- X-PAD ---------------------------------------------------------------------------------------------------------

/**
 * X-PAD FX: escolher o efeito (ROLL, TRANS, HI-PASS, DRUM), o HOLD verde e a faixa de toque. A faixa é o X-PAD do
 * RMX-1000: tocar liga o efeito enquanto o dedo está; a posição escolhe o tempo (da esquerda, lento, à direita, rápido) e,
 * segurando, o roll acelera sozinho; deslizar o dedo muda o tempo na hora. Com HOLD, soltar não solta.
 */
@Composable
private fun XPadSection(rmx: RmxState, update: ((RmxState) -> RmxState) -> Unit, tight: Boolean, modifier: Modifier) {
    val button = if (tight) 30.dp else 38.dp
    val green = Color(StagePalette.keyInk)
    Section("X-PAD FX  ·  segure e deslize", modifier) {
        Row(Modifier.fillMaxWidth().height(button), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            // Os efeitos: escolhem o que a faixa toca; ligar é tocar na faixa. Na ordem: ROLL, TRANS, DRUM, HI-PASS.
            val order = listOf(0, 1, 2, 3)
            order.forEach { index ->
                RmxButton(XPAD_NAMES[index].uppercase(), rmx.xMode == index, Modifier.weight(1f).fillMaxHeight(), tone = green) {
                    update { it.copy(xMode = index, panel = true, drumBuild = if (index == 2 && it.drumBuild < 0.2f) 1f else it.drumBuild) }
                }
            }
            // HOLD: o botão verde redondo do RMX-1000.
            Box(
                Modifier.size(button).clip(CircleShape).background(if (rmx.latch) green else Color(StagePalette.keyFill)).border(2.dp, green, CircleShape).clickable { update { rmxToggleHold(it) } },
                contentAlignment = Alignment.Center,
            ) { TextInk("HOLD", color = if (rmx.latch) Color(StagePalette.playInk) else green, fontSize = 9.sp, weight = FontWeight.Bold, maxLines = 1) }
        }
        if (rmx.xMode == 2) Row(Modifier.fillMaxWidth().height(if (tight) 26.dp else 32.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            DRUM_VOICES.forEachIndexed { index, name ->
                RmxButton(name, rmx.drumVoice == index, Modifier.weight(if (index == 3) 1.5f else 1f).fillMaxHeight(), tone = green, fontSize = 10) { update { it.copy(drumVoice = index) } }
            }
            RmxButton("GAP", rmx.drumGap, Modifier.weight(0.8f).fillMaxHeight(), tone = green, fontSize = 10) { update { it.copy(drumGap = !it.drumGap) } }
            RmxButton("TOM", rmx.drumPitch, Modifier.weight(0.8f).fillMaxHeight(), tone = green, fontSize = 10) { update { it.copy(drumPitch = !it.drumPitch) } }
        }
        XPadStrip(rmx, update, Modifier.weight(1f).fillMaxWidth())
    }
}

/** A faixa de toque do X-PAD, com os LEDs do TEMPO acesos (o roll que está tocando). */
@Composable
private fun XPadStrip(rmx: RmxState, update: ((RmxState) -> RmxState) -> Unit, modifier: Modifier) {
    val lit = if (rmx.xOn && rmx.xMode != 2) 4 - rmx.xBeat.coerceIn(0, 4) else -1
    val green = Color(StagePalette.keyInk)
    BoxWithConstraints(
        modifier.border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.bg)).pointerInput(Unit) {
            awaitEachGesture {
                fun cellAt(x: Float) = ((x / size.width) * STRIP_CELLS.size).toInt().coerceIn(0, STRIP_CELLS.lastIndex)
                val down = awaitFirstDown()
                var cell = cellAt(down.position.x)
                // O dedo pousou: liga o efeito escolhido já no tempo da célula (e acelera dali, segurando).
                update { rmxXPadDown(it, it.xMode).copy(xBeat = 4 - cell) }
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
                    val now = cellAt(change.position.x)
                    // Deslizou para outra célula: o tempo é o do dedo (a aceleração automática para).
                    if (now != cell) { cell = now; update { it.copy(xBeat = 4 - now, manual = true) } }
                    change.consume()
                } while (event.changes.any { it.pressed })
                // Soltou: vale como "segurou" (só toca enquanto o dedo está, a não ser com HOLD).
                update { rmxXPadUp(it, it.xMode, RMX_HOLD_MS, false) }
            }
        },
    ) {
        Row(Modifier.fillMaxSize().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            STRIP_CELLS.forEachIndexed { index, label ->
                val on = index == lit
                Box(
                    Modifier.weight(1f).fillMaxHeight().background(if (on) green.copy(alpha = 0.85f) else Color(StagePalette.panelRaised)),
                    contentAlignment = Alignment.Center,
                ) {
                    TextInk(label, color = if (on) Color(StagePalette.playInk) else Color(StagePalette.inkMuted), fontSize = 13.sp, weight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        if (rmx.xMode == 2) TextInk(if (rmx.xOn) "DRUM ▶" else "TOQUE PARA O DRUM", color = Color(StagePalette.keyInk), fontSize = 12.sp, weight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
    }
}

// ---- SCENE FX -------------------------------------------------------------------------------------------------------

private const val WEDGE_DEGREES = 45f

/** O início (em graus, no sentido horário a partir das 3 horas) da fatia de cada cena: as de subida em cima, as de queda embaixo. */
internal fun wedgeStart(index: Int): Float = if (index < 4) 180f + WEDGE_DEGREES * index else 135f - WEDGE_DEGREES * (index - 4)

/** Qual cena está sob o dedo (-1: fora do anel), pelo ângulo e pela distância do centro. */
internal fun sceneAt(x: Float, y: Float, width: Float, height: Float): Int {
    val cx = width / 2
    val cy = height / 2
    val radius = minOf(cx, cy)
    val distance = hypot(x - cx, y - cy)
    if (distance > radius || distance < radius * 0.6f) return -1
    var angle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
    if (angle < 0f) angle += 360f
    return if (angle >= 180f) ((angle - 180f) / WEDGE_DEGREES).toInt().coerceIn(0, 3)
    else 4 + (3 - (angle / WEDGE_DEGREES).toInt().coerceIn(0, 3))
}

/**
 * O SCENE FX do RMX-1000: oito fatias em anel — as cenas de subida (BUILD UP) em vermelho em cima, as de queda (BREAK DOWN)
 * em azul embaixo — e o knob no centro, com o anel azul aceso enquanto uma cena (ou o roll) está tocando. Apertar uma fatia
 * segura a cena; o knob do centro (arrastar para cima e para baixo) é a intensidade, ou a velocidade do roll.
 */
@Composable
private fun SceneRing(rmx: RmxState, update: ((RmxState) -> RmxState) -> Unit, tight: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val knob = rmxKnobValue(rmx)
    val active = rmx.holding || rmxRolling(rmx)
    val red = Color(StagePalette.onAir)
    val blue = Color(StagePalette.accentBlue)
    BoxWithConstraints(modifier) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onPress = { position ->
                    val index = sceneAt(position.x, position.y, size.width.toFloat(), size.height.toFloat())
                    if (index >= 0) {
                        update { rmxScenePress(it, index, true) }
                        tryAwaitRelease()
                        update { rmxScenePress(it, index, false) }
                    }
                })
            },
        ) {
            val radius = size.minDimension / 2f - 2f
            val inner = radius * 0.62f
            val mid = (radius + inner) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            for (index in SCENE_NAMES.indices) {
                val hot = rmx.scene == index && rmx.holding
                val top = index < 4
                val base = if (top) Color(StagePalette.dangerBg) else Color(0xFF10223F)
                val lit = if (top) red else blue
                drawArc(
                    color = if (hot) lit else base, startAngle = wedgeStart(index) + 1.5f, sweepAngle = WEDGE_DEGREES - 3f, useCenter = false,
                    topLeft = Offset(center.x - mid, center.y - mid), size = Size(mid * 2, mid * 2), style = Stroke(radius - inner),
                )
                // O nome na fatia, em duas linhas ("Echo" / "UP").
                val label = SCENE_NAMES[index].replace(" ", "\n")
                val laid = measurer.measure(label, TextStyle(color = if (hot) Color(StagePalette.bg) else Color(StagePalette.ink), fontSize = if (tight) 8.sp else 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = if (tight) 9.sp else 11.sp))
                val angle = Math.toRadians((wedgeStart(index) + WEDGE_DEGREES / 2f).toDouble())
                val at = Offset(center.x + mid * kotlin.math.cos(angle).toFloat() - laid.size.width / 2f, center.y + mid * kotlin.math.sin(angle).toFloat() - laid.size.height / 2f)
                drawTextFitting(laid, at)
            }
            // O centro: fundo, o anel azul aceso e o arco do valor do knob.
            drawCircle(Color(StagePalette.panel), radius = inner * 0.94f, center = center)
            if (active) drawCircle(blue.copy(alpha = 0.55f), radius = inner * 0.9f, center = center, style = Stroke(radius * 0.05f))
            val ringBox = inner * 0.78f
            drawArc(Color(StagePalette.line), -90f, 360f, false, Offset(center.x - ringBox, center.y - ringBox), Size(ringBox * 2, ringBox * 2), style = Stroke(radius * 0.07f))
            drawArc(if (rmx.scene < 4) Color(StagePalette.accentOrange) else blue, -90f, 360f * knob.coerceIn(0f, 1f), false, Offset(center.x - ringBox, center.y - ringBox), Size(ringBox * 2, ringBox * 2), style = Stroke(radius * 0.07f))
        }
        // O knob do centro: o mesmo arrastar dos outros botões.
        Box(
            Modifier.align(Alignment.Center).fillMaxSize(0.5f).knobGestures(true, "rmx-knob", knob, density, false, "Knob do RMX", knob) { v -> update { rmxKnob(it, v) } },
            contentAlignment = Alignment.Center,
        ) {
            TextInk(rmxKnobLabel(rmx), fontSize = if (tight) 17.sp else 24.sp, weight = FontWeight.Bold, mono = true, maxLines = 1)
        }
    }
}

// ---- RELEASE FX e SUB PARAMETER ------------------------------------------------------------------------------------

@Composable
private fun ReleaseSection(rmx: RmxState, update: ((RmxState) -> RmxState) -> Unit, tight: Boolean, modifier: Modifier) {
    Section("RELEASE FX", modifier) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("VINYL BRAKE", "ECHO", "BACK SPIN").forEachIndexed { index, name ->
                RmxButton(name, rmx.release == index, Modifier.fillMaxWidth().weight(1f), fontSize = if (tight) 10 else 12) {
                    update { it.copy(release = if (it.release == index) -1 else index, panel = true) }
                }
            }
        }
        // SUB PARAMETER 1 e 2: os compassos da cena e os compassos do roll de bateria.
        Row(Modifier.fillMaxWidth().height(if (tight) 58.dp else 72.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepKnob("SUB 1", "${rmx.bars} comp", SCENE_BARS.indexOf(rmx.bars).coerceAtLeast(0), SCENE_BARS.size, Modifier.weight(1f).fillMaxHeight()) { i -> update { it.copy(bars = SCENE_BARS[i]) } }
            StepKnob("SUB 2", "${rmx.drumBars}c", ROLL_BARS.indexOf(rmx.drumBars).coerceAtLeast(0), ROLL_BARS.size, Modifier.weight(1f).fillMaxHeight()) { i -> update { it.copy(drumBars = ROLL_BARS[i]) } }
        }
    }
}

/** Um botão de passos (o SUB PARAMETER): arrastar para cima e para baixo anda entre os valores. */
@Composable
private fun StepKnob(label: String, value: String, index: Int, count: Int, modifier: Modifier, onIndex: (Int) -> Unit) {
    val density = LocalDensity.current.density
    val v = if (count <= 1) 0f else index.toFloat() / (count - 1)
    BoxWithConstraints(modifier.knobGestures(true, label, v, density, false, "$label do RMX", v) { onIndex((it * (count - 1)).roundToInt().coerceIn(0, count - 1)) }, contentAlignment = Alignment.Center) {
        val dial: Dp = minOf(maxWidth - 2.dp, maxHeight - 26.dp).coerceIn(22.dp, 44.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KnobDial(v, false, Color(StagePalette.accentBlue), 4.5f, Modifier.size(dial))
            TextInk(value, fontSize = 10.sp, weight = FontWeight.Bold, mono = true, maxLines = 1)
            TextInk(label, fontSize = 8.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
        }
    }
}
