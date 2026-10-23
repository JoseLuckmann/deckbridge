package os.deckbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.COLOR_FX_NAMES
import os.deckbridge.logic.EQ_BAND_NAMES
import os.deckbridge.logic.MixerState
import os.deckbridge.logic.PerformanceAction
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.crossfaderLabel
import os.deckbridge.logic.eqDbLabel
import os.deckbridge.logic.headLevelDbLabel
import os.deckbridge.logic.headMixLabel
import os.deckbridge.logic.trimDbLabel

/**
 * Vista da mesa: EQ de 3 bandas, TRIM, filtro e fader por canal, crossfader e fones.
 * Mostra o que a DDJ-400 manda e aceita toque, para o aluno ver o nome do controle que mexe.
 */
@Composable
fun MixerScreen(mixer: MixerState, onKnob: (PerformanceAction) -> Unit, onColorFx: (Int) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(StagePalette.bg)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("MIXER", color = Color(StagePalette.accentBlue), fontSize = 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            UtilityButton("FECHAR", onClose)
        }
        // COLOR FX: o tipo vale para os dois canais; o knob FILTER de cada canal faz o resto.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInk("COLOR FX", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted), modifier = Modifier.width(84.dp))
            COLOR_FX_NAMES.forEachIndexed { index, name ->
                UtilityButton(name, { onColorFx(index) }, selected = mixer.colorFx == index, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Channel(0, mixer, onKnob, Modifier.weight(1f))
            Center(mixer, onKnob, Modifier.width(150.dp))
            Channel(1, mixer, onKnob, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Channel(deck: Int, mixer: MixerState, onKnob: (PerformanceAction) -> Unit, modifier: Modifier) {
    Column(modifier.border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextInk("CH ${deck + 1}", fontSize = 16.sp, weight = FontWeight.Bold)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Bar("TRIM", mixer.trim[deck], trimDbLabel(mixer.trim[deck]), true, Modifier.weight(1f)) {
                onKnob(PerformanceAction(deck, PerformanceAction.Kind.TRIM, slider = it.toDouble()))
            }
            // HI no alto, como no mixer: a ordem da tela é HI, MID, LOW.
            for (band in 2 downTo 0) {
                val knob = mixer.eq[deck][band]
                Bar(EQ_BAND_NAMES[band], knob, eqDbLabel(knob), true, Modifier.weight(1f), kill = knob <= 0.03f) {
                    onKnob(PerformanceAction(deck, PerformanceAction.Kind.EQ, hotIndex = band, slider = it.toDouble()))
                }
            }
            val filter = mixer.filter[deck] / 127f
            Bar("FILTER", filter, filterLabel(mixer.filter[deck]), true, Modifier.weight(1f)) {
                onKnob(PerformanceAction(deck, PerformanceAction.Kind.FILTER, slider = it.toDouble()))
            }
            Bar("FADER", mixer.volume[deck], "%d%%".format((mixer.volume[deck] * 100).toInt()), false, Modifier.weight(1f)) {
                onKnob(PerformanceAction(deck, PerformanceAction.Kind.VOLUME, slider = it.toDouble()))
            }
            Meter("LVL", listOf(mixer.levels[deck]), Modifier.width(30.dp))
        }
    }
}

@Composable
private fun Center(mixer: MixerState, onKnob: (PerformanceAction) -> Unit, modifier: Modifier) {
    Column(modifier.border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextInk("MASTER", fontSize = 16.sp, weight = FontWeight.Bold)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Bar("HEAD MIX", mixer.headMix, headMixLabel(mixer.headMix), false, Modifier.weight(1f)) {
                onKnob(PerformanceAction(-1, PerformanceAction.Kind.HEAD_MIX, slider = it.toDouble()))
            }
            Bar("HEAD LVL", mixer.headLevel, headLevelDbLabel(mixer.headLevel), false, Modifier.weight(1f)) {
                onKnob(PerformanceAction(-1, PerformanceAction.Kind.HEAD_GAIN, slider = it.toDouble()))
            }
        }
        Meter("MASTER", listOf(mixer.levels[2], mixer.levels[3]), Modifier.height(90.dp).fillMaxWidth())
        TextInk("CROSSFADER", fontSize = 11.sp, color = Color(StagePalette.inkMuted))
        TextInk(crossfaderLabel(mixer.cross), fontSize = 14.sp, weight = FontWeight.Bold, mono = true)
        Canvas(Modifier.fillMaxWidth().height(14.dp).background(Color(StagePalette.bg)).border(1.dp, Color(StagePalette.line))) {
            val x = size.width * mixer.cross.coerceIn(0f, 1f)
            drawLine(Color(StagePalette.ink), Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height), 1f)
            drawRect(Color(StagePalette.accentBlue), Offset((x - 5f).coerceAtLeast(0f), 0f), Size(10f, size.height))
        }
    }
}

private fun filterLabel(value: Int): String = when {
    value in 60..67 -> "OFF"
    value < 60 -> "LPF"
    else -> "HPF"
}

/** Barra vertical: o valor sobe de baixo; a linha marca o centro. Arrastar muda o valor. */
@Composable
private fun Bar(label: String, knob: Float, text: String, centred: Boolean, modifier: Modifier, kill: Boolean = false, onChange: (Float) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TextInk(text, fontSize = 13.sp, weight = FontWeight.Bold, mono = true, color = Color(if (kill) StagePalette.onAir else StagePalette.ink), maxLines = 1)
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp).border(1.5.dp, Color(if (kill) StagePalette.onAir else StagePalette.line)).pointerInput(label) {
                detectVerticalDragGestures { change, _ ->
                    onChange((1f - (change.position.y / size.height.toFloat())).coerceIn(0f, 1f))
                }
            },
        ) {
            Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                val h = size.height * knob.coerceIn(0f, 1f)
                drawRect(if (kill) Color(0xFF3A1014) else Color(StagePalette.bg), Offset(0f, 0f), size)
                if (!kill && h > 0f) drawRect(Color(StagePalette.accentBlue), Offset(0f, size.height - h), Size(size.width, h))
                if (centred) drawLine(Color(StagePalette.ink), Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), 2f)
            }
        }
        TextInk(label, fontSize = 12.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** Medidor de pico: verde até −9 dB, amarelo até −3 dB, vermelho acima. Uma barra por canal de áudio. */
@Composable
private fun Meter(label: String, fractions: List<Float>, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp).background(Color(StagePalette.bg)).border(1.dp, Color(StagePalette.line))) {
            val gap = 3f
            val barWidth = (size.width - gap * (fractions.size + 1)) / fractions.size
            fractions.forEachIndexed { index, value ->
                val x = gap + index * (barWidth + gap)
                val top = size.height * (1f - value.coerceIn(0f, 1f))
                val green = size.height * (1f - 0.8125f)
                val yellow = size.height * (1f - 0.9375f)
                if (value > 0f) {
                    drawRect(Color(0xFF3FBF6B), Offset(x, maxOf(top, green)), Size(barWidth, size.height - maxOf(top, green)))
                    if (top < green) drawRect(Color(0xFFE8C23A), Offset(x, maxOf(top, yellow)), Size(barWidth, green - maxOf(top, yellow)))
                    if (top < yellow) drawRect(Color(0xFFE5484D), Offset(x, top), Size(barWidth, yellow - top))
                }
            }
        }
        TextInk(label, fontSize = 10.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
    }
}
