package os.deckbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.CH_FADER_CURVES
import os.deckbridge.logic.COLOR_FX_NAMES
import os.deckbridge.logic.CROSS_CURVES
import os.deckbridge.logic.ConsoleState
import os.deckbridge.logic.SEND_TYPES
import os.deckbridge.logic.EQ_BAND_NAMES
import os.deckbridge.logic.MixerState
import os.deckbridge.logic.PerformanceAction
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.crossfaderLabel
import os.deckbridge.logic.eqDbLabel
import os.deckbridge.logic.headLevelDbLabel
import os.deckbridge.logic.masterLabel
import os.deckbridge.logic.headMixLabel
import os.deckbridge.logic.isolatorDbLabel
import os.deckbridge.logic.trimDbLabel

/**
 * Vista da mesa: EQ de 3 bandas, TRIM, filtro e fader por canal, crossfader e fones.
 * Mostra o que a DDJ-400 manda e aceita toque, para o aluno ver o nome do controle que mexe.
 */
@Composable
fun MixerScreen(
    mixer: MixerState,
    onKnob: (PerformanceAction) -> Unit,
    onColorFx: (Int) -> Unit,
    console: ConsoleState = ConsoleState(),
    chCurve: Int = 2,
    crossCurve: Int = 0,
    onConsole: (ConsoleState) -> Unit = {},
    onCurves: (Int, Int) -> Unit = { _, _ -> },
    onClose: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(StagePalette.bg))) {
        // Celular deitado: não cabe tudo de uma vez sem cortar os números; vira três abas (canais, master, opções).
        if (maxHeight < os.deckbridge.shared.ScreenSize.TIGHT_HEIGHT || maxWidth < 1000.dp) {
            TightMixer(mixer, onKnob, onColorFx, console, chCurve, crossCurve, onConsole, onCurves, onClose)
        } else {
            WideMixer(mixer, onKnob, onColorFx, console, chCurve, crossCurve, onConsole, onCurves, onClose)
        }
    }
}

private val MIXER_PAGES = listOf("CANAIS", "MASTER", "OPÇÕES")

@Composable
private fun TightMixer(
    mixer: MixerState,
    onKnob: (PerformanceAction) -> Unit,
    onColorFx: (Int) -> Unit,
    console: ConsoleState,
    chCurve: Int,
    crossCurve: Int,
    onConsole: (ConsoleState) -> Unit,
    onCurves: (Int, Int) -> Unit,
    onClose: () -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInk("MIXER", color = Color(StagePalette.accentBlue), fontSize = 16.sp, weight = FontWeight.Bold, modifier = Modifier.width(72.dp))
            // O mesmo seletor de abas do app todo, em vez de botões.
            os.deckbridge.shared.UnderlineTabs(MIXER_PAGES, page, { page = it }, Modifier.weight(1f), height = 40.dp)
            UtilityButton("FECHAR", onClose)
        }
        when (page) {
            0 -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Channel(0, mixer, console, onKnob, onConsole, Modifier.weight(1f), small = true)
                Channel(1, mixer, console, onKnob, onConsole, Modifier.weight(1f), small = true)
            }
            1 -> TightCenter(mixer, console, onKnob, onConsole, Modifier.weight(1f).fillMaxWidth())
            else -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MixerOptions(mixer, onColorFx, console, chCurve, crossCurve, onConsole, onCurves)
            }
        }
    }
}

/** O master num arranjo baixo: isolador, Send e fones lado a lado, o medidor à direita e o crossfader embaixo. */
@Composable
private fun TightCenter(mixer: MixerState, console: ConsoleState, onKnob: (PerformanceAction) -> Unit, onConsole: (ConsoleState) -> Unit, modifier: Modifier) {
    Column(modifier.border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.width(56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextInk("ISOLATOR", fontSize = 10.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted), maxLines = 1)
                UtilityButton("ON", { onConsole(console.copy(isolatorOn = !console.isolatorOn)) }, selected = console.isolatorOn)
            }
            for (band in 2 downTo 0) {
                val knob = console.isolator[band]
                Bar(EQ_BAND_NAMES[band], knob, isolatorDbLabel(knob), true, Modifier.weight(1f), kill = console.isolatorOn && knob <= 0.03f, small = true) {
                    onConsole(console.copy(isolator = console.isolator.mapIndexed { index, value -> if (index == band) it else value }))
                }
            }
            SendBars(console, onConsole, small = true)
            Bar("HEAD MIX", mixer.headMix, headMixLabel(mixer.headMix), false, Modifier.weight(1f), small = true) {
                onKnob(PerformanceAction(-1, PerformanceAction.Kind.HEAD_MIX, slider = it.toDouble()))
            }
            Bar("HEAD LVL", mixer.headLevel, headLevelDbLabel(mixer.headLevel), false, Modifier.weight(1f), small = true) {
                onKnob(PerformanceAction(-1, PerformanceAction.Kind.HEAD_GAIN, slider = it.toDouble()))
            }
            Bar("OUT", mixer.masterLevel, masterLabel(mixer.masterLevel), false, Modifier.weight(1f), small = true) {
                onKnob(PerformanceAction(-1, PerformanceAction.Kind.MASTER_LEVEL, slider = it.toDouble()))
            }
            Meter("MASTER", listOf(mixer.levels[2], mixer.levels[3]), Modifier.width(44.dp).fillMaxHeight())
        }
        Crossfader(mixer)
    }
}

@Composable
private fun WideMixer(
    mixer: MixerState,
    onKnob: (PerformanceAction) -> Unit,
    onColorFx: (Int) -> Unit,
    console: ConsoleState,
    chCurve: Int,
    crossCurve: Int,
    onConsole: (ConsoleState) -> Unit,
    onCurves: (Int, Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("MIXER", color = Color(StagePalette.accentBlue), fontSize = 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            UtilityButton("FECHAR", onClose)
        }
        MixerOptions(mixer, onColorFx, console, chCurve, crossCurve, onConsole, onCurves)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Channel(0, mixer, console, onKnob, onConsole, Modifier.weight(1f))
            Center(mixer, console, onKnob, onConsole, Modifier.width(260.dp))
            Channel(1, mixer, console, onKnob, onConsole, Modifier.weight(1f))
        }
    }
}

/** COLOR FX, curvas e SEND: as escolhas que valem para a mesa toda. */
@Composable
private fun MixerOptions(
    mixer: MixerState,
    onColorFx: (Int) -> Unit,
    console: ConsoleState,
    chCurve: Int,
    crossCurve: Int,
    onConsole: (ConsoleState) -> Unit,
    onCurves: (Int, Int) -> Unit,
) {
        // COLOR FX: o tipo vale para os dois canais; o knob FILTER de cada canal faz o resto.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInk("COLOR FX", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted), modifier = Modifier.width(84.dp))
            COLOR_FX_NAMES.forEachIndexed { index, name ->
                UtilityButton(name, { onColorFx(index) }, selected = mixer.colorFx == index, modifier = Modifier.weight(1f))
            }
        }
        // Curvas dos faders (DJM-V10, p. 30) e do crossfader. Ficam salvas nas preferências.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInk("CH CURVE", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted), modifier = Modifier.width(84.dp))
            CH_FADER_CURVES.forEachIndexed { index, name ->
                UtilityButton(name, { onCurves(index, crossCurve) }, selected = chCurve == index, modifier = Modifier.weight(1f))
            }
            TextInk("X CURVE", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted), modifier = Modifier.width(74.dp).padding(start = 8.dp))
            CROSS_CURVES.forEachIndexed { index, name ->
                UtilityButton(name, { onCurves(chCurve, index) }, selected = crossCurve == index, modifier = Modifier.weight(1f))
            }
        }
        // SEND (DJM-V10, p. 43–48): o efeito interno escolhido; tocar de novo desliga. O retorno vai ao master.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInk("SEND", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted), modifier = Modifier.width(84.dp))
            SEND_TYPES.forEachIndexed { index, name ->
                UtilityButton(name, { onConsole(console.copy(sendType = if (console.sendType == index) -1 else index)) }, selected = console.sendType == index, modifier = Modifier.weight(1f))
            }
        }
}

@Composable
private fun Channel(deck: Int, mixer: MixerState, console: ConsoleState, onKnob: (PerformanceAction) -> Unit, onConsole: (ConsoleState) -> Unit, modifier: Modifier, small: Boolean = false) {
    Column(modifier.border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("CH ${deck + 1}", fontSize = 16.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (mixer.pfl.getOrElse(deck) { false }) {
                TextInk("CUE FONES", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentOrange))
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (small) 4.dp else 8.dp)) {
            Bar("TRIM", mixer.trim[deck], trimDbLabel(mixer.trim[deck]), true, Modifier.weight(1f), small = small) {
                onKnob(PerformanceAction(deck, PerformanceAction.Kind.TRIM, slider = it.toDouble()))
            }
            // COMP (DJM-V10): todo embaixo é o som original.
            val comp = console.comp[deck]
            Bar("COMP", comp, if (comp < 0.01f) "OFF" else "%d".format((comp * 10).toInt()), false, Modifier.weight(1f), small = small) {
                onConsole(console.copy(comp = console.comp.mapIndexed { index, value -> if (index == deck) it else value }))
            }
            // HI no alto, como no mixer: a ordem da tela é HI, MID, LOW.
            for (band in 2 downTo 0) {
                val knob = mixer.eq[deck][band]
                Bar(EQ_BAND_NAMES[band], knob, eqDbLabel(knob), true, Modifier.weight(1f), kill = knob <= 0.03f, small = small) {
                    onKnob(PerformanceAction(deck, PerformanceAction.Kind.EQ, hotIndex = band, slider = it.toDouble()))
                }
            }
            val filter = mixer.filter[deck] / 127f
            Bar("FILTER", filter, filterLabel(mixer.filter[deck]), true, Modifier.weight(1f), small = small) {
                onKnob(PerformanceAction(deck, PerformanceAction.Kind.FILTER, slider = it.toDouble()))
            }
            val send = console.send[deck]
            Bar("SEND", send, if (send < 0.01f) "OFF" else "%d%%".format((send * 100).toInt()), false, Modifier.weight(1f), small = small) {
                onConsole(console.copy(send = console.send.mapIndexed { index, value -> if (index == deck) it else value }))
            }
            Bar("FADER", mixer.volume[deck], "%d%%".format((mixer.volume[deck] * 100).toInt()), false, Modifier.weight(1f), small = small) {
                onKnob(PerformanceAction(deck, PerformanceAction.Kind.VOLUME, slider = it.toDouble()))
            }
            Meter("LVL", listOf(mixer.levels[deck]), Modifier.width(30.dp))
        }
    }
}

@Composable
private fun Center(mixer: MixerState, console: ConsoleState, onKnob: (PerformanceAction) -> Unit, onConsole: (ConsoleState) -> Unit, modifier: Modifier) {
    Column(modifier.border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextInk("MASTER", fontSize = 16.sp, weight = FontWeight.Bold)
        // MASTER ISOLATOR (DJM-V10, p. 33): ON, e HI/MID/LOW de −∞ a +9 dB.
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.width(56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextInk("ISOLATOR", fontSize = 10.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted), maxLines = 1)
                UtilityButton("ON", { onConsole(console.copy(isolatorOn = !console.isolatorOn)) }, selected = console.isolatorOn)
            }
            for (band in 2 downTo 0) {
                val knob = console.isolator[band]
                Bar(EQ_BAND_NAMES[band], knob, isolatorDbLabel(knob), true, Modifier.weight(1f), kill = console.isolatorOn && knob <= 0.03f) {
                    onConsole(console.copy(isolator = console.isolator.mapIndexed { index, value -> if (index == band) it else value }))
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SendBars(console, onConsole)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Bar("HEAD MIX", mixer.headMix, headMixLabel(mixer.headMix), false, Modifier.weight(1f)) {
                onKnob(PerformanceAction(-1, PerformanceAction.Kind.HEAD_MIX, slider = it.toDouble()))
            }
            Bar("HEAD LVL", mixer.headLevel, headLevelDbLabel(mixer.headLevel), false, Modifier.weight(1f)) {
                onKnob(PerformanceAction(-1, PerformanceAction.Kind.HEAD_GAIN, slider = it.toDouble()))
            }
            Bar("OUT", mixer.masterLevel, masterLabel(mixer.masterLevel), false, Modifier.weight(1f)) {
                onKnob(PerformanceAction(-1, PerformanceAction.Kind.MASTER_LEVEL, slider = it.toDouble()))
            }
        }
        Meter("MASTER", listOf(mixer.levels[2], mixer.levels[3]), Modifier.height(90.dp).fillMaxWidth())
        Crossfader(mixer)
    }
}

/** Os quatro controles do Send (tamanho/realimentação, tempo, tom, retorno), numa linha com peso. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.SendBars(console: ConsoleState, onConsole: (ConsoleState) -> Unit, small: Boolean = false) {
    Bar(if (console.sendType == 3) "SIZE" else "FEEDBACK", console.sendSize, "%d".format((console.sendSize * 10).toInt()), false, Modifier.weight(1f), small = small) {
        onConsole(console.copy(sendSize = it))
    }
    Bar("TIME", console.sendTime, sendTimeLabel(console.sendType, console.sendTime), false, Modifier.weight(1f), small = small) {
        onConsole(console.copy(sendTime = it))
    }
    Bar("TONE", console.sendTone, filterLabel((console.sendTone * 127).toInt()), true, Modifier.weight(1f), small = small) {
        onConsole(console.copy(sendTone = it))
    }
    Bar("RETURN", console.sendReturn, "%d%%".format((console.sendReturn * 100).toInt()), false, Modifier.weight(1f), small = small) {
        onConsole(console.copy(sendReturn = it))
    }
}

/** O crossfader: o rótulo e o valor numa linha, a barra embaixo. */
@Composable
private fun Crossfader(mixer: MixerState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextInk("CROSSFADER", fontSize = 11.sp, color = Color(StagePalette.inkMuted), maxLines = 1, modifier = Modifier.weight(1f))
        TextInk(crossfaderLabel(mixer.cross), fontSize = 14.sp, weight = FontWeight.Bold, mono = true, maxLines = 1)
    }
    Canvas(Modifier.fillMaxWidth().height(14.dp).background(Color(StagePalette.bg)).border(1.dp, Color(StagePalette.line))) {
        val x = size.width * mixer.cross.coerceIn(0f, 1f)
        drawLine(Color(StagePalette.ink), Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height), 1f)
        drawRect(Color(StagePalette.accentBlue), Offset((x - 5f).coerceAtLeast(0f), 0f), Size(10f, size.height))
    }
}

/** TIME do Send em ms (mesmas faixas de `SendReturn::delaySeconds`) ou o tempo de reverberação. */
private fun sendTimeLabel(type: Int, time: Float): String = when (type) {
    0 -> "%d ms".format((20 + 180 * time).toInt())
    1 -> "%d ms".format((150 + 1050 * time).toInt())
    2 -> "%d ms".format((120 + 680 * time).toInt())
    3 -> "%.1f s".format(0.4f + 5.6f * time)
    else -> "—"
}

private fun filterLabel(value: Int): String = when {
    value in 60..67 -> "OFF"
    value < 60 -> "LPF"
    else -> "HPF"
}

/** Barra vertical: o valor sobe de baixo; a linha marca o centro. Arrastar muda o valor. */
@Composable
private fun Bar(label: String, knob: Float, text: String, centred: Boolean, modifier: Modifier, kill: Boolean = false, small: Boolean = false, onChange: (Float) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TextInk(text, fontSize = if (small) 10.sp else 13.sp, weight = FontWeight.Bold, mono = true, color = Color(if (kill) StagePalette.onAir else StagePalette.ink), maxLines = 1)
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
        TextInk(label, fontSize = if (small) 9.sp else 12.sp, weight = FontWeight.Bold, maxLines = 1)
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
