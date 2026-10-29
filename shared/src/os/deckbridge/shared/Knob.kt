package os.deckbridge.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import os.deckbridge.logic.StagePalette

/** Quanto arrastar (dp) leva um botão de ponta a ponta. */
const val KNOB_TRAVEL_DP = 140f

/**
 * O arco de um botão: o fundo (7 horas a 5 horas), o valor na cor [ink] e o ponteiro. No bipolar o arco sai do meio-dia
 * (o FILTER do mixer, o isolador do RMX). O mesmo desenho no MIX, no mixer e no RMX.
 */
@Composable
fun KnobDial(v: Float, bipolar: Boolean, ink: Color, strokeWidth: Float, modifier: Modifier) {
    val ring = Color(StagePalette.line)
    val dot = Color(StagePalette.ink)
    Canvas(modifier) {
        val r = size.minDimension / 2 - 2f
        val o = Offset(size.width / 2, size.height / 2)
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth)
        drawArc(ring, 135f, 270f, false, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r), style = stroke)
        if (bipolar) drawArc(ink, 270f, 270f * (v - 0.5f), false, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r), style = stroke)
        else drawArc(ink, 135f, 135f * v, false, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r), style = stroke)
        val ang = Math.toRadians(if (bipolar) 135.0 + 270.0 * v else 135.0 + 135.0 * v)
        drawLine(dot, o, Offset(o.x + (r - 2f) * kotlin.math.cos(ang).toFloat(), o.y + (r - 2f) * kotlin.math.sin(ang).toFloat()), strokeWidth * 0.9f)
    }
}

/**
 * Arrastar o botão para cima abre, para baixo fecha; e a acessibilidade (valor e ajuste direto). Um só jeito de mexer
 * para todos os botões do app (EQ, FILTER e LEVEL do mixer, isolador e sub-parâmetros do RMX): [onValue] recebe o valor
 * novo (0 a 1); [onEnd] quando o dedo solta.
 */
fun Modifier.knobGestures(
    enabled: Boolean, key: Any, current: Float, density: Float, bipolar: Boolean, name: String, v: Float,
    onEnd: () -> Unit = {}, onValue: (Float) -> Unit,
): Modifier =
    this.pointerInput(enabled, key) {
        if (!enabled) return@pointerInput
        var acc = 0f
        detectVerticalDragGestures(onDragStart = { acc = current }, onDragEnd = onEnd, onDragCancel = onEnd) { change, dy ->
            change.consume()
            acc = (acc - dy / (KNOB_TRAVEL_DP * density)).coerceIn(0f, 1f)
            // O FILTER prende no meio (desligado) ao passar por ele.
            onValue(if (bipolar && kotlin.math.abs(acc - 0.5f) < 0.03f) 0.5f else acc)
        }
    }.semantics {
        contentDescription = name
        progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f)
        if (enabled) setProgress { target -> onValue(target.coerceIn(0f, 1f)); onEnd(); true }
    }
