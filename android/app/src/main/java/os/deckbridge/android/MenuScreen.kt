package os.deckbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.PlayerSettings
import os.deckbridge.logic.StagePalette

private val MENU_SECTIONS = listOf(
    "Player",
    "Áudio",
    "Controladora",
    "Biblioteca",
    "Gravação",
    "Histórico",
    "Diagnóstico",
    "Sobre",
)

@Composable
fun MenuScreen(
    settings: PlayerSettings,
    midiLine: String,
    onSettings: (PlayerSettings) -> Unit,
    onClose: () -> Unit,
    onDiagnostics: () -> Unit,
    onTestTone: (Int) -> Unit,
    onHistory: () -> Unit = {},
) {
    var section by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(StagePalette.bg))) {
        val compact = maxWidth < 1200.dp
        Column(Modifier.fillMaxSize().padding(if (compact) 6.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextInk("MENU", fontSize = if (compact) 16.sp else 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                UtilityButton("FECHAR", onClose)
            }
            Row(Modifier.fillMaxSize().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    Modifier.width(if (compact) 156.dp else 220.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MENU_SECTIONS.forEachIndexed { index, label ->
                        NavRow(label, index, section == index, compact) { section = index }
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).verticalScroll(rememberScrollState()).padding(if (compact) 8.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    when (section) {
                        0 -> PlayerPane(settings, compact, onSettings)
                        1 -> AudioPane(settings, compact, onSettings, onTestTone)
                        2 -> ControllerPane(settings, midiLine, onSettings)
                        3 -> LibraryPane()
                        4 -> RecordPane(settings, onSettings)
                        5 -> HistoryPane(settings, onSettings, onHistory)
                        6 -> DiagnosticPane(onDiagnostics)
                        else -> AboutPane()
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRow(label: String, index: Int, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val fill = if (selected) Color(0xFF1A3A68) else Color(StagePalette.panel)
    val ink = if (selected) Color.White else Color(StagePalette.accentBlue)
    Row(
        Modifier.fillMaxWidth().height(if (compact) 36.dp else 44.dp).background(fill).border(if (selected) 1.5.dp else 1.dp, Color(if (selected) StagePalette.accentBlue else StagePalette.line)).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuGlyph(index, ink, Modifier.width(16.dp).height(16.dp))
        TextInk(label, color = ink, fontSize = if (compact) 12.sp else 14.sp, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun MenuGlyph(index: Int, ink: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(1.6f)
        when (index) {
            0 -> drawCircle(ink, style = stroke)
            1 -> drawRect(ink, Offset(size.width * 0.2f, size.height * 0.25f), Size(size.width * 0.6f, size.height * 0.5f), style = stroke)
            2 -> drawCircle(ink, radius = size.minDimension * 0.22f, center = Offset(size.width * 0.5f, size.height * 0.45f))
            3 -> drawRect(ink, Offset(2f, 3f), Size(size.width - 4f, size.height - 6f), style = stroke)
            4 -> drawCircle(Color(StagePalette.onAir), radius = size.minDimension * 0.28f)
            5 -> drawLine(ink, Offset(2f, size.height * 0.3f), Offset(size.width - 2f, size.height * 0.3f), 1.6f)
            6 -> drawLine(ink, Offset(size.width * 0.5f, 2f), Offset(size.width * 0.5f, size.height - 2f), 1.6f)
            else -> drawCircle(ink, radius = 2.2f)
        }
        if (index == 5) {
            drawLine(ink, Offset(2f, size.height * 0.55f), Offset(size.width * 0.7f, size.height * 0.55f), 1.6f)
            drawLine(ink, Offset(2f, size.height * 0.8f), Offset(size.width * 0.45f, size.height * 0.8f), 1.6f)
        }
    }
}

@Composable
private fun Line(label: String, compact: Boolean, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp), verticalAlignment = Alignment.CenterVertically) {
        TextInk(label, fontSize = if (compact) 12.sp else 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
        control()
    }
}

@Composable
private fun Chips(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, (label, selected) ->
            val fill = if (selected) Color(0xFF1A3A68) else Color(StagePalette.bg)
            val ink = if (selected) Color.White else Color(StagePalette.accentBlue)
            Box(
                Modifier.height(28.dp).background(fill).border(1.dp, Color(if (selected) StagePalette.accentBlue else StagePalette.line)).clickable { onPick(index) }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextInk(label, color = ink, fontSize = 12.sp, weight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PlayerPane(settings: PlayerSettings, compact: Boolean, onSettings: (PlayerSettings) -> Unit) {
    Line("Faixa de tempo", compact) {
        Chips(listOf("6" to (settings.tempoRange == 0.06), "10" to (settings.tempoRange == 0.10), "16" to (settings.tempoRange == 0.16), "WIDE" to (settings.tempoRange == 0.25))) { index ->
            onSettings(settings.copy(tempoRange = listOf(0.06, 0.10, 0.16, 0.25)[index]))
        }
    }
    Line("Master Tempo", compact) {
        Chips(listOf("OFF" to !settings.keyLock, "ON" to settings.keyLock)) { onSettings(settings.copy(keyLock = it == 1)) }
    }
    TextInk("Deslocador de tom aproximado. Não é o algoritmo comercial.", fontSize = 11.sp, color = Color(StagePalette.inkMuted))
    Line("Quantize", compact) {
        Chips(listOf("OFF" to !settings.quantize, "ON" to settings.quantize)) { onSettings(settings.copy(quantize = it == 1)) }
    }
    Line("Snap", compact) {
        val marks = listOf(0.25 to "1/16", 0.5 to "1/8", 1.0 to "1/4", 2.0 to "1/2", 4.0 to "1")
        Chips(marks.map { it.second to (settings.quantizeBeats == it.first) }) { index ->
            onSettings(settings.copy(quantizeBeats = marks[index].first, quantize = true))
        }
    }
    Line("Auto cue", compact) {
        Chips(listOf("OFF" to !settings.autoCue, "ON" to settings.autoCue)) { onSettings(settings.copy(autoCue = it == 1)) }
    }
    Line("Hot cue", compact) {
        Chips(listOf("OFF" to !settings.hotCueAutoLoad, "ON" to settings.hotCueAutoLoad)) { onSettings(settings.copy(hotCueAutoLoad = it == 1)) }
    }
    Line("Jog", compact) {
        Chips(listOf("VINYL" to settings.jogVinyl, "CDJ" to !settings.jogVinyl)) { onSettings(settings.copy(jogVinyl = it == 0)) }
    }
    Line("Sensibilidade", compact) {
        val steps = listOf(0.5f to "BAIXA", 1f to "MÉDIA", 1.6f to "ALTA")
        Chips(steps.map { it.second to (settings.jogSensitivity == it.first) }) { onSettings(settings.copy(jogSensitivity = steps[it].first)) }
    }
    TextInk("A sensibilidade do prato é provisória até o teste na controladora.", fontSize = 11.sp, color = Color(StagePalette.inkMuted))
    Line("Cor da onda", compact) {
        val modes = listOf("RGB" to "RGB", "THREE_BAND" to "3 BANDAS", "BLUE" to "AZUL")
        Chips(modes.map { it.second to (settings.waveColor == it.first) }) { onSettings(settings.copy(waveColor = modes[it].first)) }
    }
    Line("Zoom", compact) {
        Chips(listOf(2, 4, 8, 16).map { "$it" to (settings.zoomBars == it) }) { onSettings(settings.copy(zoomBars = listOf(2, 4, 8, 16)[it])) }
    }
    Line("Playhead", compact) {
        val spots = listOf(0.20f to "20%", 0.30f to "30%", 0.50f to "50%")
        Chips(spots.map { it.second to (settings.playhead == it.first) }) { onSettings(settings.copy(playhead = spots[it].first)) }
    }
}

@Composable
private fun AudioPane(settings: PlayerSettings, compact: Boolean, onSettings: (PlayerSettings) -> Unit, onTestTone: (Int) -> Unit) {
    TextInk("Master 1–2. Fone 3–4 quando o aparelho abre quatro canais.", fontSize = 12.sp, color = Color(StagePalette.inkMuted))
    Line("Buffer", compact) {
        Chips(listOf(128, 256, 512, 1024).map { "$it" to (settings.bufferFrames == it) }) {
            onSettings(settings.copy(bufferFrames = listOf(128, 256, 512, 1024)[it]))
        }
    }
    TextInk("Latência real UNKNOWN até o aparelho.", fontSize = 11.sp, color = Color(StagePalette.inkMuted))
    Line("Tom", compact) {
        Chips(listOf("MASTER" to false, "FONE" to false, "MIXER" to false)) { onTestTone(it + 1) }
    }
}

@Composable
private fun ControllerPane(settings: PlayerSettings, midiLine: String, onSettings: (PlayerSettings) -> Unit) {
    TextInk(if (midiLine.isBlank()) "DDJ-400: sem aparelho nesta sessão." else midiLine, fontSize = 13.sp)
    Line("Encoder", true) {
        Chips(listOf("NORMAL" to !settings.encoderFlip, "INVERTIDO" to settings.encoderFlip)) { onSettings(settings.copy(encoderFlip = it == 1)) }
    }
    TextInk("Sentido do BROWSE provisório. Não foi medido na controladora.", fontSize = 11.sp, color = Color(StagePalette.inkMuted))
    TextInk("RMX provisório", fontSize = 13.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentOrange), modifier = Modifier.padding(top = 8.dp))
    TextInk(
        "O botão de modo dos pads não manda MIDI. Com RMX ligado, SAMPLER (notas 0x30–0x37) vira RMX: pads 1–4 seguram a cena, pads 5–8 disparam o X-Pad. SHIFT+SAMPLER continua eject/stop. LEVEL/DEPTH é a intensidade. Beat < > escolhe os compassos. O isolador é na tela.",
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun LibraryPane() {
    TextInk("USB só de leitura. Nada desta tela grava no pendrive.", fontSize = 13.sp)
    TextInk("Re-scan pede a pasta de novo. OneLibrary real ainda não foi aberto.", fontSize = 12.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp))
    TextInk("PWV7 e PWV5, quando existem, desenham a onda. PWB3 é o fallback deste projeto.", fontSize = 12.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun RecordPane(settings: PlayerSettings, onSettings: (PlayerSettings) -> Unit) {
    TextInk("O REC grava o master já com os efeitos, em WAV, na pasta do app.", fontSize = 13.sp)
    Line("AAC", true) {
        Chips(listOf("OFF" to !settings.recordAac, "ON" to settings.recordAac)) { onSettings(settings.copy(recordAac = it == 1)) }
    }
    TextInk("AAC usa o codificador do Android. LAME não entra neste build.", fontSize = 11.sp, color = Color(StagePalette.inkMuted))
    TextInk("Abaixo de 64 MB livres a gravação não começa. A cópia para outra pasta é pedida ao sistema e não toca o USB.", fontSize = 12.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun HistoryPane(settings: PlayerSettings, onSettings: (PlayerSettings) -> Unit, onHistory: () -> Unit) {
    Line("No ar", true) {
        Chips(listOf(5, 10, 20, 30).map { "$it s" to (settings.historySeconds == it) }) {
            onSettings(settings.copy(historySeconds = listOf(5, 10, 20, 30)[it]))
        }
    }
    TextInk("Uma faixa entra no histórico quando o fader do canal está aberto e ela toca pelo menos esse tempo.", fontSize = 12.sp, color = Color(StagePalette.inkMuted))
    Box(Modifier.padding(top = 8.dp)) {
        UtilityButton("ABRIR HISTÓRICO", onHistory)
    }
}

@Composable
private fun DiagnosticPane(onDiagnostics: () -> Unit) {
    UtilityButton("ABRIR DIAGNÓSTICO", onDiagnostics)
    TextInk("O relatório descreve esta sessão. Não é medição do tablet.", fontSize = 12.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun AboutPane() {
    TextInk("DeckBridge 0.9.0", fontSize = 18.sp, weight = FontWeight.Bold)
    TextInk("Beat FX e REMIX FX são DSP original. Não é firmware comercial.", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
}
