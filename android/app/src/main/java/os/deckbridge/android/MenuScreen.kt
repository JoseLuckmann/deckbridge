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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Path
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
    usbWaveNote: String = "",
    onHistory: () -> Unit = {},
    onMixer: () -> Unit = {},
) {
    var section by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(StagePalette.bg))) {
        val wide = maxWidth >= 1400.dp
        Column(Modifier.fillMaxSize().padding(if (wide) 16.dp else 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextInk("MENU", fontSize = 22.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // O outro modo do app (ADR-0012): sai do DJ e abre a Biblioteca, que roda no processo dela.
                val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                if (activity != null) {
                    UtilityButton("BIBLIOTECA ›", { os.deckbridge.manager.mobile.Modes.switchTo(activity, os.deckbridge.manager.mobile.Modes.Mode.LIBRARY) }, modifier = Modifier.padding(end = 8.dp))
                }
                UtilityButton("FECHAR", onClose)
            }
            Row(Modifier.fillMaxSize().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.width(if (wide) 280.dp else 220.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MENU_SECTIONS.forEachIndexed { index, label ->
                        NavRow(label, index, section == index) { section = index }
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (section) {
                        0 -> PlayerPane(settings, wide, usbWaveNote, onSettings)
                        1 -> AudioPane(settings, wide, onSettings, onTestTone, onMixer)
                        2 -> ControllerPane(settings, wide, midiLine, onSettings)
                        3 -> LibraryPane(wide)
                        4 -> RecordPane(settings, wide, onSettings)
                        5 -> HistoryPane(settings, wide, onSettings, onHistory)
                        6 -> DiagnosticPane(wide, onDiagnostics)
                        else -> AboutPane(wide)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRow(label: String, index: Int, selected: Boolean, onClick: () -> Unit) {
    val fill = if (selected) Color(0xFF1A3A68) else Color(StagePalette.panel)
    val ink = if (selected) Color.White else Color(StagePalette.accentBlue)
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(fill).border(if (selected) 1.5.dp else 1.dp, Color(if (selected) StagePalette.accentBlue else StagePalette.line)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuGlyph(index, ink, Modifier.width(28.dp).height(28.dp))
        TextInk(label, color = ink, fontSize = 16.sp, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun MenuGlyph(index: Int, ink: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(2.2f)
        when (index) {
            0 -> drawPath(
                Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.18f)
                    lineTo(size.width * 0.82f, size.height * 0.5f)
                    lineTo(size.width * 0.28f, size.height * 0.82f)
                    close()
                },
                ink,
            )
            1 -> {
                drawPath(
                    Path().apply {
                        moveTo(size.width * 0.12f, size.height * 0.38f)
                        lineTo(size.width * 0.32f, size.height * 0.38f)
                        lineTo(size.width * 0.52f, size.height * 0.18f)
                        lineTo(size.width * 0.52f, size.height * 0.82f)
                        lineTo(size.width * 0.32f, size.height * 0.62f)
                        lineTo(size.width * 0.12f, size.height * 0.62f)
                        close()
                    },
                    ink,
                )
                drawArc(ink, -40f, 80f, false, topLeft = Offset(size.width * 0.48f, size.height * 0.28f), size = Size(size.width * 0.28f, size.height * 0.44f), style = stroke)
            }
            2 -> {
                drawCircle(ink, radius = size.minDimension * 0.38f, style = stroke)
                drawCircle(ink, radius = size.minDimension * 0.12f)
            }
            3 -> {
                drawRoundRectish(ink, stroke)
                drawLine(ink, Offset(size.width * 0.22f, size.height * 0.38f), Offset(size.width * 0.78f, size.height * 0.38f), 2f)
                drawLine(ink, Offset(size.width * 0.22f, size.height * 0.55f), Offset(size.width * 0.78f, size.height * 0.55f), 2f)
                drawLine(ink, Offset(size.width * 0.22f, size.height * 0.72f), Offset(size.width * 0.62f, size.height * 0.72f), 2f)
            }
            4 -> {
                drawCircle(ink, radius = size.minDimension * 0.38f, style = stroke)
                drawCircle(Color(StagePalette.onAir), radius = size.minDimension * 0.16f)
            }
            5 -> {
                drawCircle(ink, radius = size.minDimension * 0.38f, style = stroke)
                drawLine(ink, Offset(size.width * 0.5f, size.height * 0.5f), Offset(size.width * 0.5f, size.height * 0.28f), 2.2f)
                drawLine(ink, Offset(size.width * 0.5f, size.height * 0.5f), Offset(size.width * 0.68f, size.height * 0.58f), 2.2f)
            }
            6 -> {
                drawLine(ink, Offset(2f, size.height * 0.62f), Offset(size.width * 0.28f, size.height * 0.62f), 2.2f)
                drawLine(ink, Offset(size.width * 0.28f, size.height * 0.62f), Offset(size.width * 0.4f, size.height * 0.28f), 2.2f)
                drawLine(ink, Offset(size.width * 0.4f, size.height * 0.28f), Offset(size.width * 0.55f, size.height * 0.74f), 2.2f)
                drawLine(ink, Offset(size.width * 0.55f, size.height * 0.74f), Offset(size.width * 0.7f, size.height * 0.4f), 2.2f)
                drawLine(ink, Offset(size.width * 0.7f, size.height * 0.4f), Offset(size.width - 2f, size.height * 0.4f), 2.2f)
            }
            else -> {
                drawCircle(ink, radius = size.minDimension * 0.38f, style = stroke)
                drawCircle(ink, radius = 1.8f, center = Offset(size.width * 0.5f, size.height * 0.32f))
                drawLine(ink, Offset(size.width * 0.5f, size.height * 0.46f), Offset(size.width * 0.5f, size.height * 0.74f), 2.2f)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectish(ink: Color, stroke: Stroke) {
    drawRect(ink, Offset(size.width * 0.12f, size.height * 0.16f), Size(size.width * 0.76f, size.height * 0.68f), style = stroke)
}

@Composable
private fun GroupCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        TextInk(title, fontSize = 13.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
        content()
    }
}

@Composable
private fun Line(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        TextInk(label, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
        control()
    }
}

@Composable
private fun Chips(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { index, (label, selected) ->
            val fill = if (selected) Color(0xFF1A3A68) else Color(StagePalette.bg)
            val ink = if (selected) Color.White else Color(StagePalette.accentBlue)
            Box(
                Modifier.height(48.dp).widthIn(min = 64.dp).background(fill).border(1.dp, Color(if (selected) StagePalette.accentBlue else StagePalette.line)).clickable { onPick(index) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextInk(label, color = ink, fontSize = 16.sp, weight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PairRow(left: @Composable (Modifier) -> Unit, right: (@Composable (Modifier) -> Unit)?, wide: Boolean) {
    if (!wide || right == null) {
        left(Modifier.fillMaxWidth())
        if (right != null) {
            Spacer(Modifier.height(10.dp))
            right(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            left(Modifier.weight(1f))
            right(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlayerPane(settings: PlayerSettings, wide: Boolean, usbWaveNote: String, onSettings: (PlayerSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PairRow(
            { modifier ->
                GroupCard("Tempo", modifier) {
                    Line("Faixa de tempo") {
                        Chips(listOf("6" to (settings.tempoRange == 0.06), "10" to (settings.tempoRange == 0.10), "16" to (settings.tempoRange == 0.16), "WIDE" to (settings.tempoRange == 0.25))) { index ->
                            onSettings(settings.copy(tempoRange = listOf(0.06, 0.10, 0.16, 0.25)[index]))
                        }
                    }
                    Line("Master Tempo") {
                        Chips(listOf("OFF" to !settings.keyLock, "ON" to settings.keyLock)) { onSettings(settings.copy(keyLock = it == 1)) }
                    }
                    TextInk("Deslocador de tom aproximado. Não é o algoritmo comercial.", fontSize = 13.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(bottom = 6.dp))
                }
            },
            { modifier ->
                GroupCard("Quantize", modifier) {
                    Line("Quantize") {
                        Chips(listOf("OFF" to !settings.quantize, "ON" to settings.quantize)) { onSettings(settings.copy(quantize = it == 1)) }
                    }
                    Line("Snap") {
                        val marks = listOf(0.25 to "1/16", 0.5 to "1/8", 1.0 to "1/4", 2.0 to "1/2", 4.0 to "1")
                        Chips(marks.map { it.second to (settings.quantizeBeats == it.first) }) { index ->
                            onSettings(settings.copy(quantizeBeats = marks[index].first, quantize = true))
                        }
                    }
                }
            },
            wide,
        )
        PairRow(
            { modifier ->
                GroupCard("Cues", modifier) {
                    Line("Auto cue") {
                        Chips(listOf("OFF" to !settings.autoCue, "ON" to settings.autoCue)) { onSettings(settings.copy(autoCue = it == 1)) }
                    }
                    Line("Hot cue") {
                        Chips(listOf("OFF" to !settings.hotCueAutoLoad, "ON" to settings.hotCueAutoLoad)) { onSettings(settings.copy(hotCueAutoLoad = it == 1)) }
                    }
                    Line("Cues automáticos") {
                        Chips(listOf("drop" to "QUEDA", "intro" to "INTRO").map { it.second to (settings.cuePreset == it.first) }) {
                            onSettings(settings.copy(cuePreset = listOf("drop", "intro")[it]))
                        }
                    }
                    Line("Auto gain") {
                        Chips(listOf("OFF" to !settings.autoGain, "ON" to settings.autoGain)) {
                            onSettings(settings.copy(autoGain = it == 1))
                        }
                    }
                    Line("Alvo LUFS") {
                        val marks = listOf(-14.0, -10.0, -8.0)
                        Chips(marks.map { "${it.toInt()}" to (settings.targetLufs == it) }) {
                            onSettings(settings.copy(targetLufs = marks[it], autoGain = true))
                        }
                    }
                    Line("Bloqueio de carga") {
                        Chips(listOf("OFF" to !settings.loadLock, "ON" to settings.loadLock)) { onSettings(settings.copy(loadLock = it == 1)) }
                    }
                    Line("Color FX") {
                        val names = os.deckbridge.logic.COLOR_FX_NAMES
                        Chips(listOf(names[settings.colorFx.coerceIn(0, names.lastIndex)] to true)) {
                            onSettings(settings.copy(colorFx = (settings.colorFx + 1) % names.size))
                        }
                    }
                    Line("Sugestão") {
                        Chips(listOf(true to "SUAVE", false to "SOBE").map { it.second to (settings.gentleSuggest == it.first) }) {
                            onSettings(settings.copy(gentleSuggest = it == 0))
                        }
                    }
                }
            },
            { modifier ->
                GroupCard("Prato", modifier) {
                    Line("Jog") {
                        Chips(listOf("VINYL" to settings.jogVinyl, "CDJ" to !settings.jogVinyl)) { onSettings(settings.copy(jogVinyl = it == 0)) }
                    }
                    Line("Sensibilidade") {
                        val steps = listOf(0.5f to "BAIXA", 1f to "MÉDIA", 1.6f to "ALTA")
                        Chips(steps.map { it.second to (settings.jogSensitivity == it.first) }) { onSettings(settings.copy(jogSensitivity = steps[it].first)) }
                    }
                    TextInk("A sensibilidade do prato é provisória até o teste na controladora.", fontSize = 13.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(bottom = 6.dp))
                }
            },
            wide,
        )
        GroupCard("Onda") {
            Line("Cor da onda") {
                val modes = listOf("RGB" to "RGB", "THREE_BAND" to "3 BANDAS", "BLUE" to "AZUL")
                Chips(modes.map { it.second to (settings.waveColor == it.first) }) {
                    onSettings(settings.copy(waveColor = modes[it].first, followUsb = false))
                }
            }
            Line("Usar configuração do pendrive") {
                Chips(listOf("OFF" to !settings.followUsb, "ON" to settings.followUsb)) {
                    onSettings(settings.copy(followUsb = it == 1))
                }
            }
            TextInk(
                if (usbWaveNote.isBlank()) "Usar configuração do pendrive liga o DEVSETTING.DAT quando ele existe." else usbWaveNote,
                fontSize = 13.sp,
                color = Color(StagePalette.inkMuted),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Line("Zoom") {
                Chips(listOf(2, 4, 8, 16).map { "$it" to (settings.zoomBars == it) }) { onSettings(settings.copy(zoomBars = listOf(2, 4, 8, 16)[it])) }
            }
            Line("Vista") {
                Chips(listOf("WAVE" to "WAVEFORM", "PHASE" to "PHASE METER").map { it.second to (settings.waveView == it.first) }) {
                    onSettings(settings.copy(waveView = listOf("WAVE", "PHASE")[it]))
                }
            }
            Line("Playhead") {
                val spots = listOf(0.20f to "20%", 0.30f to "30%", 0.50f to "50%")
                Chips(spots.map { it.second to (settings.playhead == it.first) }) {
                    onSettings(settings.copy(playhead = spots[it].first, followUsb = false))
                }
            }
        }
    }
}

@Composable
private fun AudioPane(settings: PlayerSettings, wide: Boolean, onSettings: (PlayerSettings) -> Unit, onTestTone: (Int) -> Unit, onMixer: () -> Unit) {
    PairRow(
        { modifier ->
            GroupCard("Saída", modifier) {
                TextInk("Master 1–2. Fone 3–4 quando o aparelho abre quatro canais.", fontSize = 14.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp))
                Line("Buffer") {
                    Chips(listOf(128, 256, 512, 1024).map { "$it" to (settings.bufferFrames == it) }) {
                        onSettings(settings.copy(bufferFrames = listOf(128, 256, 512, 1024)[it]))
                    }
                }
                TextInk("Latência real UNKNOWN até o aparelho.", fontSize = 13.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(bottom = 6.dp))
                // A mesa (EQ, TRIM, fones) não existe na tela de um leitor; fica aqui, fora do caminho do palco.
                Line("Mesa") {
                    Chips(listOf("ABRIR EQ / TRIM / FONES" to false)) { onMixer() }
                }
            }
        },
        { modifier ->
            GroupCard("Tom de teste", modifier) {
                Line("Tom") {
                    Chips(listOf("MASTER" to false, "FONE" to false, "MIXER" to false)) { onTestTone(it + 1) }
                }
            }
        },
        wide,
    )
}

@Composable
private fun ControllerPane(settings: PlayerSettings, wide: Boolean, midiLine: String, onSettings: (PlayerSettings) -> Unit) {
    PairRow(
        { modifier ->
            GroupCard("BROWSE", modifier) {
                TextInk(if (midiLine.isBlank()) "DDJ-400: sem aparelho nesta sessão." else midiLine, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                Line("Encoder") {
                    Chips(listOf("NORMAL" to !settings.encoderFlip, "INVERTIDO" to settings.encoderFlip)) { onSettings(settings.copy(encoderFlip = it == 1)) }
                }
                TextInk("Sentido do BROWSE provisório. Não foi medido na controladora.", fontSize = 13.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(bottom = 6.dp))
            }
        },
        { modifier ->
            GroupCard("RMX provisório", modifier) {
                TextInk(
                    "O botão de modo dos pads não manda MIDI. Com RMX ligado, SAMPLER (notas 0x30–0x37) vira RMX: pads 1–4 seguram a cena, pads 5–8 disparam o X-Pad. SHIFT+SAMPLER continua eject/stop. LEVEL/DEPTH é a intensidade. Beat < > escolhe os compassos. O isolador é na tela.",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
            }
        },
        wide,
    )
}

@Composable
private fun LibraryPane(wide: Boolean) {
    GroupCard("USB", Modifier.fillMaxWidth()) {
        TextInk("USB só de leitura. Nada desta tela grava no pendrive.", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        TextInk("Re-scan pede a pasta de novo. OneLibrary real ainda não foi aberto.", fontSize = 14.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp))
        TextInk("RGB lê PWV5 e PWV4. 3 BANDAS lê PWV7 e PWV6. AZUL lê PWV3 e PWAV. Sem a tag, a onda é analisada no app e o USB não é escrito.", fontSize = 14.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    }
}

@Composable
private fun RecordPane(settings: PlayerSettings, wide: Boolean, onSettings: (PlayerSettings) -> Unit) {
    GroupCard("Master", Modifier.fillMaxWidth()) {
        TextInk("O REC grava o master já com os efeitos, em WAV, na pasta do app.", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        Line("AAC") {
            Chips(listOf("OFF" to !settings.recordAac, "ON" to settings.recordAac)) { onSettings(settings.copy(recordAac = it == 1)) }
        }
        TextInk("AAC usa o codificador do Android. LAME não entra neste build.", fontSize = 13.sp, color = Color(StagePalette.inkMuted))
        TextInk("Abaixo de 64 MB livres a gravação não começa. A cópia para outra pasta é pedida ao sistema e não toca o USB.", fontSize = 14.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    }
}

@Composable
private fun HistoryPane(settings: PlayerSettings, wide: Boolean, onSettings: (PlayerSettings) -> Unit, onHistory: () -> Unit) {
    GroupCard("No ar", Modifier.fillMaxWidth()) {
        Line("No ar") {
            Chips(listOf(5, 10, 20, 30).map { "$it s" to (settings.historySeconds == it) }) {
                onSettings(settings.copy(historySeconds = listOf(5, 10, 20, 30)[it]))
            }
        }
        TextInk("Uma faixa entra no histórico quando o fader do canal está aberto e ela toca pelo menos esse tempo. O BPM gravado é o da biblioteca; o BPM no ar multiplica pelo pitch.", fontSize = 14.sp, color = Color(StagePalette.inkMuted))
        Box(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
            UtilityButton("ABRIR HISTÓRICO", onHistory)
        }
    }
}

@Composable
private fun DiagnosticPane(wide: Boolean, onDiagnostics: () -> Unit) {
    GroupCard("Sessão", Modifier.fillMaxWidth()) {
        Box(Modifier.padding(top = 10.dp)) { UtilityButton("ABRIR DIAGNÓSTICO", onDiagnostics) }
        TextInk("O relatório descreve esta sessão. Não é medição do tablet.", fontSize = 14.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
    }
}

@Composable
private fun AboutPane(wide: Boolean) {
    GroupCard("Versão", Modifier.fillMaxWidth()) {
        TextInk("DeckBridge 0.14.1", fontSize = 20.sp, weight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        TextInk("Beat FX e REMIX FX são DSP original. Não é firmware comercial.", fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        RgbColorLegend(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp))
    }
}
