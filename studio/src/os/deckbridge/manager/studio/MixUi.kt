package os.deckbridge.manager.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.ColorWave
import os.deckbridge.logic.waveStrip
import os.deckbridge.manager.anlz.Cue
import os.deckbridge.manager.mix.Ctl
import os.deckbridge.manager.mix.Levels
import os.deckbridge.manager.mix.MixPreview
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.TrackGrid
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.smart.Smart

/** Janela das ondas ampliadas, em ms (como o zoom de 8 compassos do player a ~120 BPM). */
private const val WINDOW_MS = 10_000

/** A cabeça fica a 40% da largura, como no player: dá para ver o que vem. */
private const val HEAD_AT = 0.4f

private fun clock(ms: Int): String {
    val v = ms.coerceAtLeast(0)
    return "%d:%02d.%d".format(v / 60000, v / 1000 % 60, v / 100 % 10)
}

private fun mmss(ms: Int): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)

private fun camelotOf(t: TrackItem) = Smart.camelot(t.key)

private fun keyText(k: Pair<Int, Char>?, t: TrackItem) = k?.let { "${it.first}${it.second}" } ?: t.key.ifEmpty { "—" }

/**
 * Teste de mixagem com cara de player: as duas faixas como DECK 1 e DECK 2, ondas coloridas ampliadas rolando juntas
 * (a que entra já no andamento da que sai, então as batidas vermelhas se alinham), o trecho em que as duas tocam
 * marcado, o mixer mostrando o que cada canal faz (grave, resto, volume) e as fileiras de botões do player para
 * ajustar. Cada ajuste monta a prévia de novo, sem sair da tela.
 */
@Composable
fun MixScreen(app: StudioHost) = CappedText { MixContent(app) }

@Composable
private fun MixContent(app: StudioHost) {
    val desk = app.sets
    val tr = desk.mixing ?: return
    val v = app.model.view ?: return
    val a = v.track(tr.fromId)
    val b = v.track(tr.toId)
    if (a == null || b == null) {
        desk.closeMix()
        return
    }
    BackHandler { desk.closeMix() }
    val p = desk.mixPreview
    val playing = desk.mixPlaying
    // Enquanto toca, a cabeça segue o som a cada quadro (e a passada da mão fecha quando o loop volta).
    LaunchedEffect(playing) {
        while (playing) {
            withFrameMillis { }
            desk.tick()
        }
    }
    val ga = desk.grid(a)
    val gb = desk.grid(b)
    // A posição muda a cada quadro enquanto toca: só quem desenha a cabeça (ondas, contadores, passo) a lê, por esta
    // função. Ler aqui recompunha a tela inteira 60 vezes por segundo — o plano do set, a checagem e o resumo junto — e o
    // lixo disso disputava com o áudio.
    val pos: () -> Int = remember(desk) { { desk.cursorMs } }
    // As frases das duas (quando já foram ouvidas): chegam em segundo plano e a tela recompõe.
    @Suppress("UNUSED_EXPRESSION") desk.heard
    val pa = desk.cachedProfile(a)
    val pb = desk.cachedProfile(b)
    var advanced by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(MixPage.TURN) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        val wide = maxWidth >= WIDE_DP.dp
        // Validar uma troca é ouvir, julgar e ir para a próxima (Nielsen, heurística 8: só o que importa à vista).
        // Em cima, o que é a troca e o veredito; no meio, as duas ondas com as frases; embaixo, uma aba por tarefa.
        val decks = @Composable { waveH: Dp ->
            DeckLine(1, a, tr, p, pos, ga, master = true)
            DeckWave(p, p?.waveA, pos, ga, isA = true, height = waveH, tr = tr, phrases = pa) { desk.seekMix(it) }
            DeckWave(p, p?.waveB, pos, gb, isA = false, height = waveH, tr = tr, shiftMs = p?.shiftMs(tr) ?: 0.0, phrases = pb) { desk.seekMix(it) }
            DeckLine(2, b, tr, p, pos, gb, master = false, from = a)
        }
        val tabs = @Composable {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PadButton("TROCA", Modifier.weight(1f), on = page == MixPage.TURN) { page = MixPage.TURN }
                PadButton("MIXER", Modifier.weight(1f), on = page == MixPage.MIXER) { page = MixPage.MIXER }
                PadButton("AJUSTAR", Modifier.weight(1f), on = page == MixPage.SETTINGS) { page = MixPage.SETTINGS }
            }
        }
        val pageContent = @Composable {
            when (page) {
                MixPage.TURN -> {
                    StepsPanel(tr, p, pos)
                    WhyPanel(tr, a, b, pa, pb)
                    NotePanel(desk, tr)
                }
                MixPage.MIXER -> {
                    TakeBar(desk, tr, playing)
                    MixerNow(desk, tr, p)
                    SyncPanel(desk, tr, p)
                }
                MixPage.SETTINGS -> Controls(app, a, b, tr, ga, gb, advanced) { advanced = !advanced }
            }
        }
        val transport = @Composable { Transport(p, playing, onPlay = { desk.playMix() }, onSeek = { desk.seekMix(it) }, pos = pos, jumps = advanced, loop = desk.mixLoop, onLoop = { desk.toggleLoop() }) }
        Column(Modifier.fillMaxSize()) {
            MixTopBar(desk, a, b, busy = desk.mixBusy, estimateMs = desk.lastBuildMs)
            if (desk.mixBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Palette.blue, trackColor = Palette.bg)
            desk.mixError?.let { Text(it, color = Palette.danger, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
            if (wide) {
                Row(Modifier.weight(1f)) {
                    Column(Modifier.weight(1.4f).fillMaxHeight()) {
                        decks(118.dp)
                        Overview(p, pos, tr) { desk.seekMix(it) }
                        KeyHint(app, "Espaço toca / para  ·  ← → um compasso  ·  , . encaixe  ·  L repete  ·  ? atalhos")
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = 6.dp)) {
                        Summary(desk, a, b, tr, ga, gb, pa, pb)
                        tabs()
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { pageContent() }
                        transport()
                    }
                }
            } else {
                Summary(desk, a, b, tr, ga, gb, pa, pb)
                decks(72.dp)
                Overview(p, pos, tr) { desk.seekMix(it) }
                tabs()
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) { pageContent() }
                    if (scroll.canScrollForward) Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                            .background(Brush.verticalGradient(listOf(Palette.bg.copy(alpha = 0f), Palette.bg))),
                        contentAlignment = Alignment.BottomCenter,
                    ) { Text("▾", color = Palette.mutedBright, fontSize = 14.sp) }
                }
                Box(Modifier.fillMaxWidth().border(BorderStroke(1.dp, Palette.line)).padding(vertical = 2.dp)) { Column { transport() } }
            }
        }
    }
}

/** As abas do MIX: o que acontece na troca (e por quê), o mixer para mexer, e ajustar como ela é. */
enum class MixPage { TURN, MIXER, SETTINGS }

/**
 * Topo do MIX: fechar, qual virada do set é ("VIRADA 2/5") e quem sai e quem entra, e ◀ ▶ para ir à anterior e à
 * seguinte sem sair da tela — validar o set inteiro de uma vez.
 */
@Composable
private fun MixTopBar(desk: SetDesk, a: TrackItem, b: TrackItem, busy: Boolean, estimateMs: Long?) {
    val idx = desk.mixIndex()
    Row(Modifier.fillMaxWidth().height(48.dp).border(BorderStroke(1.dp, Palette.line)).padding(start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { desk.closeMix() }, modifier = Modifier.semantics { contentDescription = "Fechar o MIX" }) { Text("✕", fontSize = 16.sp) }
        Column(Modifier.weight(1f)) {
            Text(
                (if (idx != null) "VIRADA ${idx.first + 1}/${idx.second}" else "VIRADA") +
                    (if (busy) "  · montando" + (estimateMs?.let { " ≈${((it + 999) / 1000).coerceAtLeast(1)} s" } ?: "…") else ""),
                color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.title, color = Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  →  ", color = Palette.mutedBright, fontSize = 13.sp)
                Text(b.title, color = Palette.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
        TextButton(onClick = { desk.stepMix(-1) }, enabled = idx != null && idx.first > 0, modifier = Modifier.semantics { contentDescription = "Virada anterior" }) { Text("◀", fontSize = 16.sp) }
        TextButton(onClick = { desk.stepMix(1) }, enabled = idx != null && idx.first < idx.second - 1, modifier = Modifier.semantics { contentDescription = "Próxima virada" }) { Text("▶", fontSize = 16.sp) }
    }
}

/** Uma etiqueta do resumo: o fato em poucas letras, verde quando está bom, laranja quando pede atenção. */
@Composable
private fun Fact(text: String, good: Boolean? = null, modifier: Modifier = Modifier) {
    val color = when (good) { true -> Palette.keyInk; false -> Palette.orange; null -> Palette.text }
    Box(modifier.clip(RbShape).background(when (good) { true -> Palette.keyFill; false -> Palette.dangerBg; null -> Palette.surfaceHigh }).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Nome da frase como o DJ fala. */
private fun phraseName(kind: String) = when (kind) {
    "Chorus" -> "DROP"
    "Down" -> "BREAK"
    "Up" -> "SOBE"
    os.deckbridge.logic.Section.OUTRO -> "OUTRO"
    else -> "INTRO"
}

/** A cor da frase na onda e no resumo: drop laranja, break azul, sobe amarelo-claro, intro e outro cinza. */
private fun phraseColor(kind: String): Color = when (kind) {
    "Chorus" -> Palette.orange
    "Down" -> Palette.blue
    "Up" -> Palette.noteInk
    else -> Palette.mutedBright
}

/**
 * O resumo da troca, para julgar de relance: estilo e duração, se os tons combinam, quanto a que entra estica, onde o
 * grave vira, em que parte de cada faixa a troca acontece e onde cai o drop, a nota (quando a troca foi achada ouvindo
 * as faixas) e os avisos. E o veredito: ✓ BOA ou ↻ REFAZER (fica no plano; o ensaio e o "Pronto pro show?" usam).
 */
@Composable
private fun Summary(
    desk: SetDesk, a: TrackItem, b: TrackItem, tr: Transition, ga: TrackGrid?, gb: TrackGrid?,
    pa: os.deckbridge.manager.mix.TrackProfile?, pb: os.deckbridge.manager.mix.TrackProfile?,
) {
    val fit = SetPlanner.fit(a, b)
    val ka = keyText(camelotOf(a), a)
    val kb = keyText(camelotOf(b), b)
    val toDrop = SetPlanner.barsToDrop(b, gb, tr.inMs)
    val score = desk.mixOptions()?.firstOrNull { sameMix(it.transition, tr) }?.score
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).rbPanel().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // Curtas, para caberem numa linha do celular (e rolam de lado se não couberem).
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = when (tr.style) {
                MixStyle.BASS_SWAP -> "GRAVE"
                MixStyle.BLEND -> "MISTURA"
                MixStyle.FILTER -> "FILTRO"
                MixStyle.ECHO -> "ECHO"
                MixStyle.CUT -> "CORTE"
            }
            Fact("$style · ${tr.bars} COMP.")
            Fact("$ka→$kb", good = fit.keyDistance?.let { it <= 1 })
            Fact("%+.1f%% BPM".format(fit.bpmDiffPct), good = fit.bpmOk)
            if (score != null) Fact("NOTA $score", good = score >= 60)
        }
        // Onde a troca acontece em cada faixa (pelas frases ouvidas) e onde fica o grave e o drop.
        val outKind = pa?.phraseAt(pa.barAt(tr.outMs + 5))?.kind
        val inKind = pb?.phraseAt(pb.barAt(tr.inMs + 5))?.kind
        val parts = buildList {
            if (outKind != null) add("sai no ${phraseName(outKind)}")
            if (inKind != null) add("entra no ${phraseName(inKind)}")
            if (tr.style == MixStyle.BASS_SWAP) add("grave no comp. ${(tr.swapAt * tr.bars).toInt().coerceIn(1, tr.bars)}")
            if (toDrop != null) add(if (toDrop == tr.bars) "drop no fim ✓" else "drop em $toDrop comp.")
            if (tr.aLoopBars > 0) add("loop ${tr.aLoopBars} no D1")
            if (tr.bLoopBars > 0 && tr.bLoopTimes > 1) add("loop ${tr.bLoopBars}×${tr.bLoopTimes} no D2")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(parts.joinToString(" · ").ifEmpty { "ouvindo as faixas…" }, color = Palette.mutedBright, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // O veredito, sempre à vista: a pergunta da tela é "esta troca está boa?".
            VerdictButton("✓ BOA", on = tr.mark == Transition.Mark.GOOD, good = true) { desk.setMark(Transition.Mark.GOOD) }
            Spacer(Modifier.width(4.dp))
            VerdictButton("↻ REFAZER", on = tr.mark == Transition.Mark.REDO, good = false) { desk.setMark(Transition.Mark.REDO) }
        }
        val warn = desk.issues(listOf(a.id, b.id), listOf(tr)).firstOrNull { it.fromId == a.id && it.toId == b.id }
        if (warn != null) Text("⚠ ${warn.text}", color = Palette.orange, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VerdictButton(text: String, on: Boolean, good: Boolean, onClick: () -> Unit) {
    val ink = if (good) Palette.keyInk else Palette.orange
    Box(
        Modifier.height(32.dp).clip(RbShape).background(if (on) (if (good) Palette.keyFill else Palette.dangerBg) else Palette.surfaceHigh)
            .border(BorderStroke(1.dp, if (on) ink else Palette.line), RbShape).clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (on) ink else Palette.mutedBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/** Por que esta troca: o porquê guardado (quando foi achada ouvindo as faixas) ou o que se sabe dela. */
@Composable
private fun WhyPanel(tr: Transition, a: TrackItem, b: TrackItem, pa: os.deckbridge.manager.mix.TrackProfile?, pb: os.deckbridge.manager.mix.TrackProfile?) {
    val lines = tr.why.ifEmpty {
        buildList {
            val fit = SetPlanner.fit(a, b)
            add(if (fit.keyOk) "os tons combinam (${keyText(camelotOf(a), a)} → ${keyText(camelotOf(b), b)})" else "tons longe: melhor juntar pouco as duas melodias")
            if (!fit.bpmOk) add("andamentos longe: troca curta soa melhor")
            if (pa == null || pb == null) add("ouvindo as faixas para achar onde virar o grave…")
        }
    }
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).rbPanel().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("POR QUE ESTA TROCA", color = Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        lines.forEach { Text("· $it", color = Palette.keyInk, fontSize = 12.sp) }
    }
}

/**
 * A linha de um deck, fina (os cartões grandes repetiam o que as ondas mostram): DECK n, título, BPM no master (com o
 * quanto estica), tom e quanto falta da faixa.
 */
@Composable
private fun DeckLine(n: Int, t: TrackItem, tr: Transition, p: MixPreview?, posNow: () -> Int, g: TrackGrid?, master: Boolean, from: TrackItem? = null) {
    val pos = posNow()
    val trackMs = when {
        p == null -> if (master) tr.outMs else tr.inMs
        master -> p.aAt(pos)
        else -> p.bAt(pos, tr)
    }
    val dur = g?.durationMs ?: (t.durationSeconds * 1000)
    val tempo = p?.tempo ?: if (from != null) from.bpm else t.bpm
    val speed = if (t.bpm > 0 && tempo > 0) tempo / t.bpm else 1.0
    val key = camelotOf(t)
    val semi = Math.round(SetPlanner.semitones(speed)).toInt()
    val shifted = if (!master && !tr.masterTempo && key != null && semi != 0) SetPlanner.shiftKey(key, semi) else null
    val ok = master || from == null || SetPlanner.fit(from, t).keyOk && shifted == null
    val accent = if (master) Palette.orange else Palette.blue
    Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("DECK $n", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("  " + t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("%.1f".format(t.bpm * speed), color = Palette.text, fontSize = 12.sp, fontFamily = Mono)
        if (!master && kotlin.math.abs(speed - 1) > 0.0005) Text(" %+.1f%%".format((speed - 1) * 100), color = Palette.mutedBright, fontSize = 10.sp, fontFamily = Mono)
        Text("  " + keyText(shifted ?: key, t), color = if (ok) Palette.keyInk else Palette.orange, fontSize = 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
        Text("  −" + mmss(dur - trackMs), color = Palette.mutedBright, fontSize = 11.sp, fontFamily = Mono)
    }
}

/**
 * Onda ampliada de um deck na base de tempo da prévia. Marcas de batida em cima e embaixo (início de compasso em
 * vermelho, com o número do compasso contado a partir do ponto de saída), cues da faixa, o trecho em que as duas
 * tocam juntas sombreado em azul e a cabeça branca. Arrastar ou tocar move a cabeça.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
private fun DeckWave(
    p: MixPreview?, wave: ColorWave?, posNow: () -> Int, g: TrackGrid?, isA: Boolean, height: Dp, tr: Transition, shiftMs: Double = 0.0,
    /** As frases da faixa (quando já ouvida): uma faixa colorida embaixo da onda com INTRO, DROP, BREAK, OUTRO. */
    phrases: os.deckbridge.manager.mix.TrackProfile? = null,
    onSeek: (Int) -> Unit,
) {
    fun startOf(pos: Int) = pos - (WINDOW_MS * HEAD_AT).toInt()
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    // O pico da onda inteira não muda enquanto toca: medido uma vez (antes, a cada quadro, nas duas ondas).
    val peak = remember(wave) { ((wave?.height?.maxOfOrNull { it.toInt() and 0xFF } ?: 255).coerceAtLeast(1)) / 255f }
    val seek by androidx.compose.runtime.rememberUpdatedState(onSeek)
    Row(Modifier.fillMaxWidth().height(height).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Canvas(
            // A posição é lida na hora do gesto (posNow), não a do começo: arrastar tocando pulava para trás, e o toque
            // reiniciava o detector a cada quadro e se perdia.
            Modifier.weight(1f).fillMaxHeight().clip(RbShape).background(Palette.waveBg).border(BorderStroke(1.dp, Palette.line), RbShape)
                .pointerInput(p) {
                    detectHorizontalDragGestures { change, drag ->
                        change.consume()
                        seek(posNow() - (drag / size.width * WINDOW_MS).toInt())
                    }
                }
                .pointerInput(p) { detectTapGestures { o -> seek(startOf(posNow()) + (o.x / size.width * WINDOW_MS).toInt()) } },
        ) {
            val start = startOf(posNow())
            val w = size.width
            val h = size.height
            val tickH = h * 0.12f
            val waveTop = tickH + 2f
            val waveBot = h - tickH - 2f
            val mid = (waveTop + waveBot) / 2
            fun x(ms: Int) = (ms - start).toFloat() / WINDOW_MS * w
            if (p != null) {
                // Trecho das duas juntas.
                val o0 = x(p.leadMs)
                val o1 = x(p.leadMs + p.overlapMs)
                if (o1 > 0 && o0 < w) {
                    drawRect(Palette.blueActiveFill.copy(alpha = 0.55f), Offset(o0.coerceAtLeast(0f), waveTop), Size((o1.coerceAtMost(w) - o0.coerceAtLeast(0f)), waveBot - waveTop))
                    drawLine(Palette.orange, Offset(o0.coerceAtLeast(0f), 1f), Offset(o1.coerceAtMost(w), 1f), 3f)
                }
            }
            if (wave != null && wave.columns > 0) {
                val pixels = (w / 2).toInt().coerceAtLeast(1)
                // A onda da que entra foi feita sem sync: o sync (fino e à mão) a desloca, como as batidas.
                val strip = waveStrip(
                    (start + shiftMs) / 1000.0, WINDOW_MS / 1000.0, pixels, wave.perSecond, wave.columns,
                    { wave.height[it].toInt() and 0xFF }, { wave.rgb[it] },
                )
                val slot = w / pixels
                for (i in 0 until pixels) {
                    val hgt = strip.heights[i] / peak
                    if (hgt <= 0f) continue
                    val reach = hgt * (mid - waveTop) * 0.98f
                    val c = rgb(strip.colors[i])
                    drawLine(c, Offset(i * slot, mid - reach), Offset(i * slot, mid), slot + 0.5f)
                    drawLine(c.copy(alpha = 0.72f), Offset(i * slot, mid), Offset(i * slot, mid + reach * 0.92f), slot + 0.5f)
                }
            } else {
                drawLine(Palette.line, Offset(0f, mid), Offset(w, mid), 2f)
            }
            if (p != null && g != null) {
                // Batidas da faixa levadas para o tempo da prévia; compassos contados a partir do ponto de saída.
                // Num loop a faixa passa várias vezes pelo mesmo trecho: cada batida aparece em cada volta.
                for (beat in g.beats) {
                    if (!isA && beat.timeMs < tr.inMs) continue
                    for (at in if (isA) p.timesA(beat.timeMs) else p.timesB(beat.timeMs, tr)) {
                        val bx = x(at)
                        if (bx < -2 || bx > w + 2) continue
                        val bar = beat.inBar == 1
                        val color = if (bar) Palette.danger else Palette.text.copy(alpha = 0.6f)
                        val len = if (bar) tickH else tickH * 0.55f
                        drawLine(color, Offset(bx, 0f), Offset(bx, len), if (bar) 2.5f else 1.2f)
                        drawLine(color, Offset(bx, h - len), Offset(bx, h), if (bar) 2.5f else 1.2f)
                    }
                }
                // O trecho em loop: uma faixa verde em cima, de cada volta.
                val loopMs = if (isA) p.aLoopMs else p.bLoopMs
                if (loopMs > 0) {
                    val turns = if (isA) ((p.lengthMs - p.leadMs) / loopMs).toInt() + 1 else p.bLoopTimes
                    val from = if (isA) p.leadMs.toDouble() else p.leadMs - p.shiftMs(tr)
                    for (k in 0 until turns) {
                        val l0 = x((from + k * loopMs).toInt())
                        val l1 = x((from + (k + 1) * loopMs).toInt())
                        if (l1 < 0 || l0 > w) continue
                        drawLine(Palette.keyInk, Offset(l0.coerceAtLeast(0f) + 2f, tickH + 1f), Offset(l1.coerceAtMost(w) - 2f, tickH + 1f), 3f)
                    }
                }
                for (c in g.cues) {
                    if (!isA && c.timeMs < tr.inMs) continue
                    val at = (if (isA) p.timesA(c.timeMs) else p.timesB(c.timeMs, tr)).firstOrNull() ?: continue
                    val cx = x(at)
                    if (cx < -8 || cx > w + 8) continue
                    val color = if (c.isHot) rgb(c.rgb ?: padRgb(c.hot)) else Palette.orange
                    drawLine(color, Offset(cx, waveTop), Offset(cx, waveBot), 2f)
                    val tri = androidx.compose.ui.graphics.Path().apply { moveTo(cx - 7f, waveTop); lineTo(cx + 7f, waveTop); lineTo(cx, waveTop + 9f); close() }
                    drawPath(tri, color)
                }
                // Ponto de saída (deck 1) / entrada (deck 2): linha laranja cheia.
                val px = x(p.leadMs)
                if (px in 0f..w) drawLine(Palette.orange, Offset(px, waveTop), Offset(px, waveBot), 2.5f)
                // As frases da faixa: o DJ julga o encaixe por elas (sai no outro, entra na intro, o drop cai no fim).
                if (phrases != null) {
                    val band = 5f
                    for (ph in phrases.phrases) {
                        val s0 = phrases.msOf(ph.start)
                        val s1 = phrases.msOf(ph.end)
                        if (!isA && s1 < tr.inMs) continue
                        val x0 = x(if (isA) p.fromA(s0) else p.fromB(maxOf(s0, tr.inMs), tr))
                        val x1 = x(if (isA) p.fromA(s1) else p.fromB(s1, tr))
                        if (x1 < 0 || x0 > w) continue
                        val c = phraseColor(ph.kind)
                        drawRect(c, Offset(x0.coerceAtLeast(0f), waveBot - band), Size((x1.coerceAtMost(w) - x0.coerceAtLeast(0f)).coerceAtLeast(0f), band))
                        // O nome só onde cabe inteiro: uma frase começando na borda direita deixava largura negativa para o
                        // texto e o Compose derrubava o app (queda achada no S25 Ultra pelo registro do modo desenvolvedor).
                        if (x1 - x0 > 40f) {
                            val style = TextStyle(color = c, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            val laid = measurer.measure(phraseName(ph.kind), style)
                            val tx = x0.coerceAtLeast(0f) + 4f
                            val ty = waveBot - band - laid.size.height - 2f
                            if (tx + laid.size.width <= w && ty >= 0f) drawText(laid, topLeft = Offset(tx, ty))
                        }
                    }
                }
                // Onde o grave vira (troca de grave): traço verde na onda.
                if (tr.style == MixStyle.BASS_SWAP) {
                    val sx = x(p.leadMs + (tr.swapAt * p.overlapMs).toInt())
                    if (sx in 0f..w) drawLine(Palette.keyInk, Offset(sx, waveTop), Offset(sx, waveBot), 2f)
                }
            }
            val head = w * HEAD_AT
            drawLine(Palette.text, Offset(head, 0f), Offset(head, h), 2.5f)
        }
        BarsBox(p, posNow, isA, Modifier.width(56.dp).fillMaxHeight())
    }
}

/** Ao lado da onda, como no player: quantos compassos faltam para o ponto (ou desde ele). */
@Composable
private fun BarsBox(p: MixPreview?, posNow: () -> Int, isA: Boolean, modifier: Modifier) {
    Column(modifier.padding(start = 4.dp), verticalArrangement = Arrangement.Center) {
        if (p == null) return@Column
        val bars = (posNow() - p.leadMs) / p.barMsA
        val label = when {
            bars < 0 -> if (isA) "SAI EM" else "ENTRA EM"
            bars * p.barMsA < p.overlapMs -> "JUNTAS"
            else -> if (isA) "SAIU" else "SOZINHA"
        }
        Text(label, color = Palette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text("%.1f".format(kotlin.math.abs(bars)), color = Palette.orange, fontSize = 16.sp, fontFamily = Mono)
        Text("Bars", color = Palette.orange, fontSize = 10.sp)
    }
}

/** A prévia inteira em miniatura: deck 1 em cima, deck 2 embaixo, o trecho junto e a cabeça. Tocar pula. */
@Composable
private fun Overview(p: MixPreview?, posNow: () -> Int, tr: Transition, onSeek: (Int) -> Unit) {
    val seek by androidx.compose.runtime.rememberUpdatedState(onSeek)
    // A miniatura é a prévia inteira e não rola: as duas faixas de onda são feitas uma vez por prévia e largura (antes,
    // a cada quadro tocando).
    val strips = remember(p) { HashMap<Int, Pair<os.deckbridge.logic.WaveStrip?, os.deckbridge.logic.WaveStrip?>>() }
    Canvas(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 6.dp, vertical = 3.dp).background(Palette.waveBg).border(BorderStroke(1.dp, Palette.line))
            .pointerInput(p) { detectTapGestures { o -> if (p != null) seek((o.x / size.width * p.lengthMs).toInt()) } },
    ) {
        if (p == null) return@Canvas
        val w = size.width
        val h = size.height
        val len = p.lengthMs.coerceAtLeast(1)
        fun x(ms: Int) = ms.toFloat() / len * w
        drawRect(Palette.blueActiveFill, Offset(x(p.leadMs), 0f), Size(x(p.overlapMs), h))
        val pixels = (w / 2).toInt().coerceAtLeast(1)
        fun stripOf(wave: ColorWave?) = if (wave == null || wave.columns == 0) null
            else waveStrip(0.0, len / 1000.0, pixels, wave.perSecond, wave.columns, { wave.height[it].toInt() and 0xFF }, { wave.rgb[it] })
        val (stripA, stripB) = strips.getOrPut(pixels) { stripOf(p.waveA) to stripOf(p.waveB) }
        fun lane(strip: os.deckbridge.logic.WaveStrip?, top: Float, bottom: Float) {
            if (strip == null) return
            val peak = strip.heights.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f
            val slot = w / pixels
            val mid = (top + bottom) / 2
            for (i in 0 until pixels) {
                val r = strip.heights[i] / peak * (bottom - top) / 2
                if (r > 0) drawLine(rgb(strip.colors[i]), Offset(i * slot, mid - r), Offset(i * slot, mid + r), slot + 0.4f)
            }
        }
        lane(stripA, 2f, h / 2 - 1f)
        lane(stripB, h / 2 + 1f, h - 2f)
        // Onde o grave vira.
        if (tr.style == MixStyle.BASS_SWAP) {
            val sx = x(p.leadMs + (tr.swapAt * p.overlapMs).toInt())
            drawLine(Palette.keyInk, Offset(sx, 0f), Offset(sx, h), 2f)
        }
        val pos = posNow()
        drawLine(Palette.text, Offset(x(pos), 0f), Offset(x(pos), h), 2f)
    }
}

/**
 * A mixagem desta troca: no automático, a sua (guardada) ou a sua mão ainda não guardada — com GUARDAR e DESFAZER. A mão
 * é gravada enquanto toca e vira "a mixagem certa desta troca neste set"; depois de algumas, as trocas novas começam do
 * seu jeito. Ver `docs/ux-mixagem.md`.
 */
@Composable
private fun TakeBar(desk: SetDesk, tr: Transition, playing: Boolean) {
    val changed = desk.takeChanged
    val mine = desk.draftTake != null
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).rbPanel().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    changed -> "● SUA MÃO"
                    mine -> "SUA MIXAGEM"
                    else -> "AUTOMÁTICO"
                },
                color = if (changed || mine) Palette.orange else Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
            )
            Text(
                when {
                    changed -> "  não guardada"
                    mine -> "  esta troca toca assim"
                    playing -> "  mexa no mixer: grava"
                    else -> "  toque e mexa no mixer"
                },
                color = Palette.mutedBright, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (changed) {
                TextButton(onClick = { desk.discardTake() }) { Text("DESFAZER", fontSize = 12.sp) }
                TextButton(onClick = { desk.keepTake() }) { Text("GUARDAR", color = Palette.orange, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            } else if (mine) {
                TextButton(onClick = { desk.autoTake() }) { Text("AUTOMÁTICO", fontSize = 12.sp) }
            }
        }
        desk.habits()?.let { h ->
            Text("As trocas novas já começam do seu jeito (${h.takes} mixagens suas)", color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * O mixer da prévia, como o da controladora, e de mexer: em cada canal HI, MID, LOW e FILTER (arrastar para cima ou para
 * baixo), o ECHO e o fader (arrastar). Mexer vale na hora e grava ([SetDesk.setControl]); o que está na sua mão fica
 * laranja, o automático azul. AUTO devolve o canal ao automático dali em diante.
 */
@Composable
private fun MixerNow(desk: SetDesk, tr: Transition, p: MixPreview?) {
    // O que o mixer mostra: o automático, a mixagem em ensaio e a mão agora — o mesmo que se ouve. Lê a posição aqui
    // dentro: a cada quadro só o mixer recompõe.
    val pos = desk.cursorMs
    val pass = desk.mixPlayer?.pass
    @Suppress("UNUSED_EXPRESSION") desk.touchedNow
    val levels = p?.levelsAt(pos, tr, desk.draftTake, pass) ?: Levels()
    val bar = p?.relBar(pos) ?: 0.0
    val hand: (Ctl) -> Boolean = { c -> pass?.at(c, bar) != null || desk.draftTake?.at(c, bar) != null }
    MixerPanel(levels, hand, enabled = p != null, onSet = { c, v -> desk.setControl(c, v) }, onRelease = { desk.releaseDeck(it) })
}

@Composable
private fun MixerPanel(lv: Levels, hand: (Ctl) -> Boolean, enabled: Boolean, onSet: (Ctl, Float) -> Unit, onRelease: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(172.dp).padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MixerChannel(0, lv, hand, enabled, onSet, onRelease, Modifier.weight(1f))
        Column(Modifier.width(44.dp).fillMaxHeight().rbPanel().padding(4.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("MIX", color = Palette.muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("X-F", color = Palette.muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                drawRect(Palette.bg, Offset(0f, size.height / 2 - 2f), Size(size.width, 4f))
                drawRect(Palette.line, Offset(size.width / 2 - 1f, 0f), Size(2f, size.height))
                drawRect(Palette.text, Offset(size.width / 2 - 5f, 1f), Size(10f, size.height - 2f))
            }
        }
        MixerChannel(1, lv, hand, enabled, onSet, onRelease, Modifier.weight(1f))
    }
}

@Composable
private fun MixerChannel(deck: Int, lv: Levels, hand: (Ctl) -> Boolean, enabled: Boolean, onSet: (Ctl, Float) -> Unit, onRelease: (Int) -> Unit, modifier: Modifier) {
    fun c(label: String) = Ctl.of(deck, label)
    val anyHand = Ctl.entries.any { it.deck == deck && hand(it) }
    Row(modifier.fillMaxHeight().rbPanel().padding(horizontal = 4.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("CH ${deck + 1}", color = Palette.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val echo = c("ECHO")
                val on = lv[echo] >= 0.5f
                SmallToggle("ECHO", on, hand(echo), enabled) { onSet(echo, if (on) 0f else 1f) }
                if (anyHand) SmallToggle("AUTO", false, false, enabled) { onRelease(deck) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Knob(c("HI"), lv[c("HI")], hand(c("HI")), enabled, Modifier.weight(1f), onSet)
                Knob(c("MID"), lv[c("MID")], hand(c("MID")), enabled, Modifier.weight(1f), onSet)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Knob(c("LOW"), lv[c("LOW")], hand(c("LOW")), enabled, Modifier.weight(1f), onSet)
                Knob(c("FILTER"), lv[c("FILTER")], hand(c("FILTER")), enabled, Modifier.weight(1f), onSet, bipolar = true)
            }
        }
        Fader(c("FADER"), lv[c("FADER")], hand(c("FADER")), enabled, Modifier.width(30.dp).fillMaxHeight(), onSet)
    }
}

/** Um botão pequeno de liga e desliga no cabeçalho do canal (a área de toque cresce até 48 dp em volta). */
@Composable
private fun SmallToggle(text: String, on: Boolean, hand: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = when {
        on -> Palette.bg
        hand -> Palette.orange
        else -> Palette.blue
    }
    Box(
        Modifier.height(24.dp).clip(RbShape).background(if (on) (if (hand) Palette.orange else Palette.blue) else Palette.surfaceHigh)
            .border(BorderStroke(1.dp, if (on) Palette.blue else Palette.line), RbShape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = ink, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
}

/** Quanto arrastar (dp) leva um botão de ponta a ponta. */
private const val KNOB_TRAVEL_DP = 140f

/**
 * Botão de EQ (ou FILTER): o arco vai do corte total (7 horas) ao normal (meio-dia); no FILTER o meio é desligado e o arco
 * sai do meio para um lado. Arrastar para cima abre, para baixo fecha. Cortado, fica vermelho e diz KILL; na sua mão,
 * laranja.
 */
@Composable
private fun Knob(c: Ctl, value: Float, hand: Boolean, enabled: Boolean, modifier: Modifier, onSet: (Ctl, Float) -> Unit, bipolar: Boolean = false) {
    val v = value.coerceIn(0f, 1f)
    val kill = !bipolar && v < 0.02f
    val current by androidx.compose.runtime.rememberUpdatedState(v)
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val name = "${c.label} CH ${c.deck + 1}"
    Row(
        modifier.height(44.dp)
            .pointerInput(enabled, c) {
                if (!enabled) return@pointerInput
                var acc = 0f
                detectVerticalDragGestures(onDragStart = { acc = current }) { change, dy ->
                    change.consume()
                    acc = (acc - dy / (KNOB_TRAVEL_DP * density)).coerceIn(0f, 1f)
                    // O FILTER prende no meio (desligado) ao passar por ele.
                    val out = if (bipolar && kotlin.math.abs(acc - 0.5f) < 0.03f) 0.5f else acc
                    onSet(c, out)
                }
            }
            .semantics {
                contentDescription = name
                progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f)
                if (enabled) setProgress { target -> onSet(c, target.coerceIn(0f, 1f)); true }
            },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val ink = when {
            kill -> Palette.danger
            hand -> Palette.orange
            else -> Palette.blue
        }
        val ring = Palette.line
        val dot = Palette.text
        Canvas(Modifier.size(30.dp)) {
            val r = size.minDimension / 2 - 2f
            val o = Offset(size.width / 2, size.height / 2)
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(3.5f)
            drawArc(ring, 135f, 270f, false, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r), style = stroke)
            if (bipolar) {
                // Do meio-dia para o lado do filtro.
                drawArc(ink, 270f, 270f * (v - 0.5f), false, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r), style = stroke)
            } else {
                drawArc(ink, 135f, 135f * v, false, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r), style = stroke)
            }
            val ang = Math.toRadians(if (bipolar) 135.0 + 270.0 * v else 135.0 + 135.0 * v)
            drawLine(dot, o, Offset(o.x + (r - 2f) * kotlin.math.cos(ang).toFloat(), o.y + (r - 2f) * kotlin.math.sin(ang).toFloat()), 3f)
        }
        Text(
            if (kill) "KILL" else if (bipolar) when { v < 0.47f -> "LPF"; v > 0.53f -> "HPF"; else -> c.label } else c.label,
            color = if (kill) Palette.danger else if (hand) Palette.orange else Palette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

/** Fader do canal, de pé: em cima é o volume cheio. Arrastar ou tocar põe onde o dedo está. */
@Composable
private fun Fader(c: Ctl, value: Float, hand: Boolean, enabled: Boolean, modifier: Modifier, onSet: (Ctl, Float) -> Unit) {
    val v = value.coerceIn(0f, 1f)
    val track = Palette.bg
    val line = Palette.line
    val cap = if (hand) Palette.orange else Palette.text
    val fill = if (hand) Palette.orange else Palette.blue
    val name = "FADER CH ${c.deck + 1}"
    Canvas(
        modifier
            .pointerInput(enabled, c) {
                if (!enabled) return@pointerInput
                fun at(y: Float) = (1 - (y - 6f) / (size.height - 12f)).coerceIn(0f, 1f)
                detectVerticalDragGestures(onDragStart = { o -> onSet(c, at(o.y)) }) { change, _ ->
                    change.consume()
                    onSet(c, at(change.position.y))
                }
            }
            .semantics {
                contentDescription = name
                progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f)
                if (enabled) setProgress { target -> onSet(c, target.coerceIn(0f, 1f)); true }
            },
    ) {
        val w = size.width
        val h = size.height
        val capH = 12f
        val usable = h - capH
        drawRect(track, Offset(w / 2 - 3f, 0f), Size(6f, h))
        drawRect(line, Offset(w / 2 - 3f, 0f), Size(6f, h), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        val y = (1 - v) * usable
        drawRect(fill, Offset(w / 2 - 3f, y + capH / 2), Size(6f, h - y - capH / 2))
        drawRect(cap, Offset(2f, y), Size(w - 4f, capH))
    }
}

/**
 * O encaixe da que entra, à mão e na hora: quando a marcação errou um pouco (o bumbo "galopa") ou caiu na batida errada.
 * ◀ atrasa e ▶ adianta 5 ms (como frear ou empurrar o prato); ±1 BAT desloca uma batida inteira. Diferenças de ~10 ms já
 * se ouvem como bumbo duplo (Hirsh 1959; Repp 2005): o passo de 5 ms acha o encaixe sem passar dele.
 */
@Composable
private fun SyncPanel(desk: SetDesk, tr: Transition, p: MixPreview?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).rbPanel().padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ENCAIXE DO CH 2", color = Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
            val m = p?.measuredMs
            Text(
                "medido " + (m?.let { "%+.0f ms".format(it) } ?: "—"),
                color = if (m != null && kotlin.math.abs(m) >= 8) Palette.orange else Palette.mutedBright, fontSize = 11.sp, fontFamily = Mono,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PadButton("-1 BAT", Modifier.weight(1f), enabled = p != null) { desk.shiftBeat(-1) }
            PadButton("◀ 5 ms", Modifier.weight(1f), enabled = p != null) { desk.nudge(-5) }
            Column(
                Modifier.weight(1.3f).height(38.dp).border(BorderStroke(1.dp, if (tr.nudgeMs != 0 || tr.beatShift != 0) Palette.orange else Palette.line), RbShape),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text("%+d ms".format(tr.nudgeMs), color = if (tr.nudgeMs != 0) Palette.orange else Palette.mutedBright, fontSize = 11.sp, lineHeight = 12.sp, fontFamily = Mono, maxLines = 1)
                Text("%+d bat".format(tr.beatShift), color = if (tr.beatShift != 0) Palette.orange else Palette.muted, fontSize = 9.sp, lineHeight = 10.sp, fontFamily = Mono, maxLines = 1)
            }
            PadButton("5 ms ▶", Modifier.weight(1f), enabled = p != null) { desk.nudge(5) }
            PadButton("+1 BAT", Modifier.weight(1f), enabled = p != null) { desk.shiftBeat(1) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PadButton("SYNC FINO", Modifier.weight(1.3f), on = tr.phaseLock) { desk.editMix(tr.copy(phaseLock = !tr.phaseLock)) }
            val m = p?.measuredMs
            PadButton("GRADE D2", Modifier.weight(1f), enabled = m != null && kotlin.math.abs(m) >= 3) { desk.fixGridOfIncoming() }
            PadButton("ZERAR", Modifier.weight(1f), enabled = tr.nudgeMs != 0 || tr.beatShift != 0) { desk.resetSync() }
        }
    }
}

/**
 * O passo a passo da virada: em que compasso da troca a cabeça está, o que fazer agora (aceso) e o que vem depois, com
 * quantos compassos faltam. É o mesmo que a prévia faz no mixer acima.
 */
@Composable
private fun StepsPanel(tr: Transition, p: MixPreview?, posNow: () -> Int) {
    val steps = remember(tr) { os.deckbridge.manager.mix.MixGuide.steps(tr) }
    val bar = if (p == null) -1.0 else (posNow() - p.leadMs) / p.barMsA
    val (now, next) = os.deckbridge.manager.mix.MixGuide.at(steps, bar)
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).rbPanel().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PASSO", color = Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            Text(
                when {
                    p == null -> ""
                    bar < 0 -> "saída em ${kotlin.math.ceil(-bar).toInt()} comp."
                    bar < tr.bars -> "comp. ${bar.toInt() + 1} / ${tr.bars}"
                    else -> "troca feita"
                },
                color = Palette.orange, fontSize = 12.sp, fontFamily = Mono,
            )
        }
        if (now.isEmpty() && next == null) Text(if (bar >= tr.bars) "Só o CH 2 tocando" else "—", color = Palette.muted, fontSize = 13.sp)
        now.forEach { s -> Text("▶ " + s.text, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        next?.let { s ->
            val left = kotlin.math.ceil(s.fromBar - bar).toInt().coerceAtLeast(1)
            Text("em $left comp. · ${s.text}", color = Palette.mutedBright, fontSize = 12.sp)
        }
    }
}

/**
 * O lembrete da troca: uma frase do DJ para a hora ("quando o vocal entrar, tiro o grave"). Fica no plano e aparece na
 * cola e ao vivo, para não ter de guardar de cabeça.
 */
@Composable
private fun NotePanel(desk: SetDesk, tr: Transition) {
    var editing by remember(tr.fromId, tr.toId) { mutableStateOf(false) }
    var text by remember(tr.fromId, tr.toId) { mutableStateOf(tr.note) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).rbPanel().padding(horizontal = 10.dp, vertical = 4.dp)) {
        if (editing) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(140) }, maxLines = 3, textStyle = TextStyle(fontSize = 14.sp),
                placeholder = { Text("Ex.: quando o vocal entrar, tiro o grave", fontSize = 13.sp) }, modifier = Modifier.fillMaxWidth().typingField(),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { text = tr.note; editing = false }) { Text("CANCELAR", fontSize = 12.sp) }
                TextButton(onClick = { desk.setNote(text); editing = false }) { Text("GUARDAR", color = Palette.blue, fontSize = 12.sp) }
            }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LEMBRETE", color = Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Text(
                "  " + tr.note.ifEmpty { "o que lembrar na hora" }, color = if (tr.note.isEmpty()) Palette.muted else Palette.text,
                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { text = tr.note; editing = true }) { Text(if (tr.note.isEmpty()) "ESCREVER" else "MUDAR", fontSize = 12.sp) }
        }
    }
}

/** Fileira de transporte: pular compassos e PLAY/PAUSE grande, verde como no player. */
@Composable
private fun Transport(p: MixPreview?, playing: Boolean, onPlay: () -> Unit, onSeek: (Int) -> Unit, pos: () -> Int, jumps: Boolean, loop: Boolean = false, onLoop: () -> Unit = {}) {
    val bar = p?.barMsA ?: 2000.0
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        PadButton("◀◀ 4", Modifier.weight(1f)) { onSeek(pos() - (4 * bar).toInt()) }
        PadButton("◀ 1", Modifier.weight(1f)) { onSeek(pos() - bar.toInt()) }
        Box(
            // A ação principal da tela maior que as outras e de outra cor: acha-se de relance (pesquisa do Material 3
            // Expressive, Google 2025; ver docs/design-referencias.md).
            Modifier.weight(2f).height(56.dp).clip(RbShape).background(if (playing) Palette.blueActiveFill else Palette.playBg)
                .border(BorderStroke(1.dp, if (playing) Palette.blue else Palette.playInk.copy(alpha = 0.4f)), RbShape).clickable(enabled = p != null, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) { Text(if (playing) "❚❚ PAUSE" else "▶ PLAY", color = if (playing) Palette.text else Palette.playInk, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        PadButton("1 ▶", Modifier.weight(1f)) { onSeek(pos() + bar.toInt()) }
        PadButton("4 ▶▶", Modifier.weight(1f)) { onSeek(pos() + (4 * bar).toInt()) }
        // Repetir a troca: ensaiar de novo, mexendo, até achar o jeito.
        PadButton("LOOP", Modifier.weight(0.9f), on = loop, enabled = p != null) { onLoop() }
    }
    if (jumps) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PadButton("CUE SAÍDA", Modifier.weight(1f)) { if (p != null) onSeek(p.leadMs - (2 * bar).toInt()) }
        PadButton("MEIO", Modifier.weight(1f)) { if (p != null) onSeek(p.leadMs + p.overlapMs / 2 - (bar).toInt()) }
        PadButton("FIM", Modifier.weight(1f)) { if (p != null) onSeek(p.leadMs + p.overlapMs - (2 * bar).toInt()) }
    }
}

/** Botão das fileiras do player: painel escuro, contorno de linha, texto azul-claro; ativo em azul cheio. */
@Composable
private fun PadButton(text: String, modifier: Modifier = Modifier, on: Boolean = false, color: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    // Um toque leve no aparelho a cada botão: confirma pelo tato o que o dedo apertou (Hoggan et al. 2008). No computador não faz nada.
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier.height(38.dp).clip(RbShape).background(if (on) Palette.blue else Palette.surfaceHigh)
            .border(BorderStroke(1.dp, if (on) Palette.blue else Palette.line), RbShape)
            .clickable(enabled = enabled) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) Palette.bg else color ?: if (enabled) Palette.blue else Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** Uma moldura com título para um grupo de fileiras de ajuste. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().rbPanel().padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 2.dp))
        content()
    }
}

@Composable
private fun RowLabel(text: String) = Text(text, color = Palette.text, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))

/** Os ajustes, em fileiras como LOOP / KEY do player. */
@Composable
private fun Controls(app: StudioHost, a: TrackItem, b: TrackItem, tr: Transition, ga: TrackGrid?, gb: TrackGrid?, advanced: Boolean, onToggle: () -> Unit) {
    val desk = app.sets
    fun edit(t: Transition) = desk.editMix(t)
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      // Os ajustes em grupos dentro de molduras: o que está na mesma moldura se lê como uma coisa só (região comum,
      // Palmer 1992). A troca; o grave; corrigir a análise.
      FinderPanel(desk, tr)
      Group("A TROCA") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("COMP.")
            // Trocar a duração recua a saída de frase em frase se a troca não couber mais antes do fim.
            listOf(8, 16, 32, 64).forEach { n -> PadButton("$n", Modifier.weight(1f), on = tr.bars == n) { edit(tr.copy(bars = n, outMs = SetPlanner.fitOut(a, ga, tr.outMs, n))) } }
        }
        // O drop da próxima: um toque deixa a troca do tamanho exato para ele cair quando a outra termina de sair.
        val toDrop = SetPlanner.barsToDrop(b, gb, tr.inMs)
        if (toDrop != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("DROP")
            if (toDrop == tr.bars) {
                Text("o drop da próxima cai no fim da troca", color = Palette.keyInk, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
            } else {
                PadButton("ATÉ O DROP · $toDrop", Modifier.weight(1f)) { edit(tr.copy(bars = toDrop, outMs = SetPlanner.fitOut(a, ga, tr.outMs, toDrop))) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("ESTILO")
            listOf(MixStyle.BASS_SWAP to "GRAVE", MixStyle.BLEND to "MISTURA", MixStyle.FILTER to "FILTRO", MixStyle.ECHO to "ECHO", MixStyle.CUT to "CORTE").forEach { (s, label) ->
                PadButton(label, Modifier.weight(1f), on = tr.style == s) { edit(tr.copy(style = s)) }
            }
        }
        // O ritmo: rápida, média ou lenta (no corte seco não há o que variar).
        if (tr.style != MixStyle.CUT) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("RITMO")
            listOf(Transition.Pace.FAST to "RÁPIDA", Transition.Pace.NORMAL to "MÉDIA", Transition.Pace.SLOW to "LENTA").forEach { (p, label) ->
                PadButton(label, Modifier.weight(1f), on = tr.pace == p) { edit(tr.copy(pace = p)) }
            }
        }
        // Loops, como no player: segurar o fim da que sai (quando ele é curto para a troca) ou repetir o começo da que
        // entra (para o drop dela cair na hora, ou para ter mais tempo de batida clara).
        if (tr.style != MixStyle.CUT) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("LOOP D1")
            listOf(0 to "SEM", 4 to "↻ 4", 8 to "↻ 8").forEach { (n, label) ->
                PadButton(label, Modifier.weight(1f), on = tr.aLoopBars == n, enabled = n < tr.bars) { edit(tr.copy(aLoopBars = n)) }
            }
        }
        if (tr.style != MixStyle.CUT) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("LOOP D2")
            listOf(Triple(0, 0, "SEM"), Triple(4, 2, "↻ 4×2"), Triple(8, 2, "↻ 8×2"), Triple(4, 4, "↻ 4×4")).forEach { (n, times, label) ->
                PadButton(label, Modifier.weight(1f), on = tr.bLoopBars == n && (n == 0 || tr.bLoopTimes == times), enabled = n * times <= tr.bars) {
                    edit(tr.copy(bLoopBars = n, bLoopTimes = times))
                }
            }
        }
      }
      if (tr.style == MixStyle.BASS_SWAP) Group("O GRAVE") {
        // Na troca de grave: quando o grave passa de uma para a outra (no fim é no drop, se a troca vai até ele).
        if (tr.style == MixStyle.BASS_SWAP) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("GRAVE")
            val end = if (SetPlanner.barsToDrop(b, gb, tr.inMs) == tr.bars) "NO DROP" else "NO FIM"
            listOf(0.25 to "1/4", 0.5 to "1/2", 0.75 to "3/4", 1.0 to end).forEach { (at, label) ->
                PadButton(label, Modifier.weight(1f), on = kotlin.math.abs(tr.swapAt - at) < 0.01) { edit(tr.copy(swapAt = at)) }
            }
        }
        // E como ele passa: de uma vez no ponto, ou aos poucos em 4, 8 ou 16 compassos que terminam no ponto.
        if (tr.style == MixStyle.BASS_SWAP) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("PASSA")
            listOf(0 to "DE UMA VEZ", 4 to "4 COMP.", 8 to "8 COMP.", 16 to "16 COMP.").forEach { (n, label) ->
                // Mais compassos que a troca inteira não faz sentido: fica apagado.
                PadButton(label, Modifier.weight(if (n == 0) 1.4f else 1f), on = tr.swapBars == n, enabled = n < tr.bars) { edit(tr.copy(swapBars = n)) }
            }
        }
      }
      Group("CORRIGIR A ANÁLISE") {
        // A análise errou o começo, o drop ou o fim: põe a cabeça no lugar certo e marca (fica na faixa).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("MARCAR")
            listOf(SetDesk.Spot.INTRO to "INTRO AQUI", SetDesk.Spot.DROP to "DROP AQUI", SetDesk.Spot.OUTRO to "OUTRO AQUI").forEach { (s, label) ->
                PadButton(label, Modifier.weight(1f), enabled = desk.mixPreview != null) { desk.markHere(s) }
            }
        }
      }
        PadButton(if (advanced) "MENOS ▴" else "MAIS ▾", Modifier.fillMaxWidth()) { onToggle() }
        if (ga == null || gb == null) Text("Sem grade: analise as faixas para cair na batida.", color = Palette.noteInk, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (!advanced) {
            Spacer(Modifier.height(12.dp))
            return@Column
        }
        PointRow("SAÍDA D1", tr.outMs, onNudge = { edit(tr.copy(outMs = SetPlanner.nudge(a, ga, tr.outMs, it))) })
        CueRow(ga?.cues.orEmpty(), tr.outMs) { edit(tr.copy(outMs = it)) }
        PointRow("ENTRA D2", tr.inMs, onNudge = { edit(tr.copy(inMs = SetPlanner.nudge(b, gb, tr.inMs, it))) })
        CueRow(gb?.cues.orEmpty(), tr.inMs) { edit(tr.copy(inMs = it)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("TEMPO")
            PadButton("MASTER TEMPO", Modifier.weight(2f), on = tr.masterTempo) { edit(tr.copy(masterTempo = !tr.masterTempo)) }
            PadButton("AUTO", Modifier.weight(1f)) {
                desk.reset(tr)
                val auto = desk.plan().firstOrNull { it.fromId == tr.fromId && it.toId == tr.toId }
                if (auto != null) edit(auto.copy(masterTempo = tr.masterTempo))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PointRow(label: String, ms: Int, onNudge: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RowLabel(label)
        PadButton("-4", Modifier.weight(1f)) { onNudge(-4) }
        PadButton("-1", Modifier.weight(1f)) { onNudge(-1) }
        Box(Modifier.weight(1.4f).height(38.dp).border(BorderStroke(1.dp, Palette.orange), RbShape), contentAlignment = Alignment.Center) {
            Text(clock(ms), color = Palette.orange, fontSize = 14.sp, fontFamily = Mono)
        }
        PadButton("+1", Modifier.weight(1f)) { onNudge(1) }
        PadButton("+4", Modifier.weight(1f)) { onNudge(4) }
    }
}

/** Os hot cues e memórias da faixa como pads coloridos: tocar põe o ponto ali. */
@Composable
private fun CueRow(cues: List<Cue>, current: Int, onPick: (Int) -> Unit) {
    if (cues.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RowLabel("CUE")
        cues.take(6).forEach { c ->
            val color = if (c.isHot) rgb(c.rgb ?: padRgb(c.hot)) else Palette.orange
            val name = if (c.hot > 0) ('A' + c.hot - 1).toString() else "◆"
            Row(
                Modifier.weight(1f).height(34.dp).clip(RbShape).background(if (c.timeMs == current) Palette.blueActiveFill else Palette.surfaceHigh)
                    .border(BorderStroke(1.dp, if (c.timeMs == current) Palette.blue else Palette.line), RbShape).clickable { onPick(c.timeMs) }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(" " + mmss(c.timeMs), color = Palette.mutedBright, fontSize = 10.sp, fontFamily = Mono, maxLines = 1)
            }
        }
        repeat((6 - cues.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * Achar a troca: as formas que o app achou ouvindo as duas faixas (onde cada uma tem grave, bateria e melodia, e as
 * frases), da melhor para a pior, com a nota e o porquê. Tocar uma usa ela na troca; o porquê da troca aberta fica embaixo.
 */
@Composable
private fun FinderPanel(desk: SetDesk, tr: Transition) {
    val options = desk.mixOptions()
    Group("ACHAR A TROCA") {
        when {
            options == null -> Text("Ouvindo as duas faixas: grave, bateria, melodia e frases…", color = Palette.mutedBright, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp))
            options.isEmpty() -> Text("Nada melhor achado (faixas curtas ou sem grade).", color = Palette.mutedBright, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp))
            else -> options.forEach { o -> OptionRow(o, current = sameMix(o.transition, tr)) { desk.useOption(o) } }
        }
    }
}

/** A opção é a troca aberta? (mesmos pontos, duração, estilo e loops; o resto se ajusta por cima.) */
private fun sameMix(a: Transition, b: Transition) = a.outMs == b.outMs && a.inMs == b.inMs && a.bars == b.bars && a.style == b.style &&
    a.aLoopBars == b.aLoopBars && a.bLoopBars == b.bLoopBars && a.bLoopTimes == b.bLoopTimes

@Composable
private fun OptionRow(o: os.deckbridge.manager.mix.MixPlan, current: Boolean, onUse: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RbShape).background(if (current) Palette.blueActiveFill else Palette.surfaceHigh)
            .border(BorderStroke(1.dp, if (current) Palette.blue else Palette.line), RbShape).clickable(onClick = onUse).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(34.dp).height(28.dp).border(BorderStroke(1.dp, if (o.score >= 70) Palette.keyInk else Palette.line), RbShape), contentAlignment = Alignment.Center) {
            Text("${o.score}", color = if (o.score >= 70) Palette.keyInk else Palette.mutedBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(o.title, color = if (current) Palette.blue else Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            o.reasons.firstOrNull()?.let { Text(it, color = Palette.mutedBright, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Text(if (current) "EM USO" else "USAR", color = if (current) Palette.blue else Palette.orange, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
    }
}
