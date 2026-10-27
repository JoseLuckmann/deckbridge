package os.deckbridge.manager.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.ColorWave
import os.deckbridge.logic.waveStrip
import os.deckbridge.manager.anlz.Cue
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
fun MixScreen(app: ManagerApp) {
    val desk = app.sets
    val tr = desk.mixing ?: return
    val v = app.model.view ?: return
    val a = v.track(tr.fromId)
    val b = v.track(tr.toId)
    if (a == null || b == null) {
        desk.closeMix()
        return
    }
    androidx.activity.compose.BackHandler { desk.closeMix() }
    val p = desk.mixPreview
    val playing = desk.mixPlaying
    // Enquanto toca, a cabeça segue o player a cada quadro.
    LaunchedEffect(playing) {
        while (playing) {
            withFrameMillis { }
            desk.cursorMs = app.preview.positionMs()
        }
    }
    val ga = desk.grid(a)
    val gb = desk.grid(b)
    val pos = desk.cursorMs
    val levels = p?.levelsAt(pos) ?: Levels()
    // Simples por padrão: ondas, PLAY, compassos e estilo. "AJUSTAR" abre o resto.
    var advanced by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        val wide = maxWidth >= WIDE_DP.dp
        val decks = @Composable { waveH: Dp ->
            DeckHeader(1, a, tr, p, pos, ga, master = true)
            DeckWave(p, p?.waveA, pos, ga, isA = true, height = waveH, tr = tr) { desk.seekMix(it) }
            DeckWave(p, p?.waveB, pos, gb, isA = false, height = waveH, tr = tr) { desk.seekMix(it) }
            DeckHeader(2, b, tr, p, pos, gb, master = false, from = a)
        }
        val controls = @Composable {
            Overview(p, pos) { desk.seekMix(it) }
            if (advanced) MixerStrip(levels)
            Transport(p, playing, onPlay = { desk.playMix() }, onSeek = { desk.seekMix(it) }, pos = pos, jumps = advanced)
            Controls(app, a, b, tr, ga, gb, advanced) { advanced = !advanced }
        }
        Column(Modifier.fillMaxSize()) {
            StatusStrip(a, b, p, pos, busy = desk.mixBusy) { desk.closeMix() }
            if (desk.mixBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Palette.blue, trackColor = Palette.bg)
            desk.mixError?.let { Text(it, color = Palette.danger, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
            if (wide) {
                Row(Modifier.weight(1f)) {
                    Column(Modifier.weight(1.4f).fillMaxHeight()) { decks(118.dp) }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 6.dp)) { controls() }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    decks(104.dp)
                    controls()
                }
            }
        }
    }
}

/** Faixa de cima, como a do player: o que está aberto, o tempo da prévia e fechar. */
@Composable
private fun StatusStrip(a: TrackItem, b: TrackItem, p: MixPreview?, pos: Int, busy: Boolean, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).border(BorderStroke(1.dp, Palette.line)).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("MIX", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Text(
            if (busy) "  MONTANDO…" else "", color = Palette.noteInk, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (p != null) {
            Text("REMAIN", color = Palette.muted, fontSize = 9.sp, modifier = Modifier.padding(end = 4.dp))
            Text(clock(p.lengthMs - pos), color = Palette.text, fontSize = 14.sp, fontFamily = Mono)
        }
        TextButton(onClick = onClose) { Text("✕", fontSize = 16.sp) }
    }
}

/** Cartão de deck, como os de baixo do player: número, título, tempo restante, TEMPO, BPM em caixa, tom e MASTER/MT. */
@Composable
private fun DeckHeader(n: Int, t: TrackItem, tr: Transition, p: MixPreview?, pos: Int, g: TrackGrid?, master: Boolean, from: TrackItem? = null) {
    val trackMs = when {
        p == null -> if (master) tr.outMs else tr.inMs
        master -> p.aAt(pos)
        else -> p.bAt(pos)
    }
    val dur = g?.durationMs ?: (t.durationSeconds * 1000)
    // As duas tocam no master do set: TEMPO mostra quanto o pitch de cada uma anda até ele.
    val tempo = p?.tempo ?: if (from != null) from.bpm else t.bpm
    val speed = if (t.bpm > 0 && tempo > 0) tempo / t.bpm else 1.0
    val shown = t.bpm * speed
    val key = camelotOf(t)
    val semi = Math.round(SetPlanner.semitones(speed)).toInt()
    val shifted = if (!master && !tr.masterTempo && key != null && semi != 0) SetPlanner.shiftKey(key, semi) else null
    val ok = master || from == null || SetPlanner.fit(from, t).keyOk && shifted == null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).clip(RbShape).background(Palette.surface).border(BorderStroke(1.dp, Palette.line), RbShape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(RbShape).background(if (master) Palette.blue else Palette.surfaceHigh).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text("DECK $n", color = if (master) Palette.bg else Palette.text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("REMAIN ", color = Palette.muted, fontSize = 9.sp)
                Text(clock(dur - trackMs), color = Palette.mutedBright, fontSize = 12.sp, fontFamily = Mono)
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 6.dp)) {
            Text("TEMPO", color = Palette.muted, fontSize = 9.sp)
            Text("%+.2f%%".format((speed - 1) * 100), color = Palette.text, fontSize = 12.sp, fontFamily = Mono)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.border(BorderStroke(1.dp, if (master) Palette.orange else Palette.text), RbShape).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text("%.1f".format(shown), color = Palette.text, fontSize = 16.sp, fontFamily = Mono)
            }
            Text(
                if (master) "MASTER" else if (tr.masterTempo) "MT" else "SEM MT",
                color = if (master) Palette.orange else if (tr.masterTempo) Palette.blue else Palette.noteInk, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            )
        }
        Box(Modifier.padding(start = 6.dp).clip(RbShape).background(if (ok) Palette.keyFill else Palette.dangerBg).padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(keyText(shifted ?: key, t), color = if (ok) Palette.keyInk else Palette.orange, fontSize = 13.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Onda ampliada de um deck na base de tempo da prévia. Marcas de batida em cima e embaixo (início de compasso em
 * vermelho, com o número do compasso contado a partir do ponto de saída), cues da faixa, o trecho em que as duas
 * tocam juntas sombreado em azul e a cabeça branca. Arrastar ou tocar move a cabeça.
 */
@Composable
private fun DeckWave(p: MixPreview?, wave: ColorWave?, pos: Int, g: TrackGrid?, isA: Boolean, height: Dp, tr: Transition, onSeek: (Int) -> Unit) {
    val start = pos - (WINDOW_MS * HEAD_AT).toInt()
    Row(Modifier.fillMaxWidth().height(height).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Canvas(
            Modifier.weight(1f).fillMaxHeight().clip(RbShape).background(Palette.waveBg).border(BorderStroke(1.dp, Palette.line), RbShape)
                .pointerInput(p) {
                    detectHorizontalDragGestures { change, drag ->
                        change.consume()
                        onSeek(pos - (drag / size.width * WINDOW_MS).toInt())
                    }
                }
                .pointerInput(p, pos) { detectTapGestures { o -> onSeek(start + (o.x / size.width * WINDOW_MS).toInt()) } },
        ) {
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
                val strip = waveStrip(
                    start / 1000.0, WINDOW_MS / 1000.0, pixels, wave.perSecond, wave.columns,
                    { wave.height[it].toInt() and 0xFF }, { wave.rgb[it] },
                )
                val peak = (wave.height.maxOfOrNull { it.toInt() and 0xFF } ?: 255).coerceAtLeast(1) / 255f
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
                for (beat in g.beats) {
                    val at = if (isA) p.fromA(beat.timeMs) else p.fromB(beat.timeMs)
                    if (!isA && beat.timeMs < tr.inMs) continue
                    val bx = x(at)
                    if (bx < -2 || bx > w + 2) continue
                    val bar = beat.inBar == 1
                    val color = if (bar) Palette.danger else Palette.text.copy(alpha = 0.6f)
                    val len = if (bar) tickH else tickH * 0.55f
                    drawLine(color, Offset(bx, 0f), Offset(bx, len), if (bar) 2.5f else 1.2f)
                    drawLine(color, Offset(bx, h - len), Offset(bx, h), if (bar) 2.5f else 1.2f)
                }
                for (c in g.cues) {
                    val at = if (isA) p.fromA(c.timeMs) else p.fromB(c.timeMs)
                    val cx = x(at)
                    if (cx < -8 || cx > w + 8 || (!isA && c.timeMs < tr.inMs)) continue
                    val color = if (c.isHot) rgb(c.rgb ?: padRgb(c.hot)) else Palette.orange
                    drawLine(color, Offset(cx, waveTop), Offset(cx, waveBot), 2f)
                    val tri = androidx.compose.ui.graphics.Path().apply { moveTo(cx - 7f, waveTop); lineTo(cx + 7f, waveTop); lineTo(cx, waveTop + 9f); close() }
                    drawPath(tri, color)
                }
                // Ponto de saída (deck 1) / entrada (deck 2): linha laranja cheia.
                val px = x(p.leadMs)
                if (px in 0f..w) drawLine(Palette.orange, Offset(px, waveTop), Offset(px, waveBot), 2.5f)
            }
            val head = w * HEAD_AT
            drawLine(Palette.text, Offset(head, 0f), Offset(head, h), 2.5f)
        }
        BarsBox(p, pos, isA, Modifier.width(64.dp).fillMaxHeight())
    }
}

/** Ao lado da onda, como no player: quantos compassos faltam para o ponto (ou desde ele). */
@Composable
private fun BarsBox(p: MixPreview?, pos: Int, isA: Boolean, modifier: Modifier) {
    Column(modifier.padding(start = 4.dp), verticalArrangement = Arrangement.Center) {
        if (p == null) return@Column
        val bars = (pos - p.leadMs) / p.barMsA
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
private fun Overview(p: MixPreview?, pos: Int, onSeek: (Int) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 6.dp, vertical = 3.dp).background(Palette.waveBg).border(BorderStroke(1.dp, Palette.line))
            .pointerInput(p) { detectTapGestures { o -> if (p != null) onSeek((o.x / size.width * p.lengthMs).toInt()) } },
    ) {
        if (p == null) return@Canvas
        val w = size.width
        val h = size.height
        val len = p.lengthMs.coerceAtLeast(1)
        fun x(ms: Int) = ms.toFloat() / len * w
        drawRect(Palette.blueActiveFill, Offset(x(p.leadMs), 0f), Size(x(p.overlapMs), h))
        fun lane(wave: ColorWave?, top: Float, bottom: Float) {
            if (wave == null || wave.columns == 0) return
            val pixels = (w / 2).toInt().coerceAtLeast(1)
            val strip = waveStrip(0.0, len / 1000.0, pixels, wave.perSecond, wave.columns, { wave.height[it].toInt() and 0xFF }, { wave.rgb[it] })
            val peak = strip.heights.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f
            val slot = w / pixels
            val mid = (top + bottom) / 2
            for (i in 0 until pixels) {
                val r = strip.heights[i] / peak * (bottom - top) / 2
                if (r > 0) drawLine(rgb(strip.colors[i]), Offset(i * slot, mid - r), Offset(i * slot, mid + r), slot + 0.4f)
            }
        }
        lane(p.waveA, 2f, h / 2 - 1f)
        lane(p.waveB, h / 2 + 1f, h - 2f)
        drawLine(Palette.text, Offset(x(pos), 0f), Offset(x(pos), h), 2f)
    }
}

/** O mixer da transição, nos mesmos medidores do mixer do player: LOW, HI e FADER de cada canal. Grave cortado em vermelho. */
@Composable
private fun MixerStrip(lv: Levels) {
    Row(Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Channel("CH 1", lv.aLow, lv.aHigh, lv.aVol, lv.echo, Modifier.weight(1f))
        Column(Modifier.width(70.dp).fillMaxHeight().rbPanel().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("X FADER", color = Palette.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            val total = (lv.aVol + lv.bVol).coerceAtLeast(0.001f)
            val xf = lv.bVol / total
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                drawRect(Palette.bg, Offset(0f, size.height / 2 - 2f), Size(size.width, 4f))
                val cx = xf * (size.width - 10f)
                drawRect(Palette.blue, Offset(cx, 0f), Size(10f, size.height))
            }
            Text(if (xf < 0.34f) "◀ CH 1" else if (xf > 0.66f) "CH 2 ▶" else "◀ ▶", color = Palette.text, fontSize = 10.sp, fontFamily = Mono)
        }
        Channel("CH 2", lv.bLow, lv.bHigh, lv.bVol, false, Modifier.weight(1f))
    }
}

@Composable
private fun Channel(name: String, low: Float, high: Float, vol: Float, echo: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxHeight().rbPanel().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Palette.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (echo) Text("ECHO", color = Palette.bg, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RbShape).background(Palette.blue).padding(horizontal = 4.dp))
        }
        Row(Modifier.weight(1f).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Meter("HI", high, Modifier.weight(1f))
            Meter("LOW", low, Modifier.weight(1f))
            Meter("FADER", vol, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Meter(label: String, value: Float, modifier: Modifier) {
    val kill = value < 0.02f && label != "FADER"
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.weight(1f).fillMaxWidth().background(if (kill) Palette.dangerBg else Palette.bg).border(BorderStroke(1.dp, if (kill) Palette.danger else Palette.line)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f)).background(Palette.blue))
        }
        Text(if (kill) "KILL" else label, color = if (kill) Palette.danger else Palette.muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Fileira de transporte: pular compassos e PLAY/PAUSE grande, verde como no player. */
@Composable
private fun Transport(p: MixPreview?, playing: Boolean, onPlay: () -> Unit, onSeek: (Int) -> Unit, pos: Int, jumps: Boolean) {
    val bar = p?.barMsA ?: 2000.0
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        PadButton("◀◀ 4", Modifier.weight(1f)) { onSeek(pos - (4 * bar).toInt()) }
        PadButton("◀ 1", Modifier.weight(1f)) { onSeek(pos - bar.toInt()) }
        Box(
            Modifier.weight(1.6f).height(48.dp).clip(RbShape).background(if (playing) Palette.blueActiveFill else Palette.playBg)
                .border(BorderStroke(1.dp, if (playing) Palette.blue else Palette.playInk.copy(alpha = 0.4f)), RbShape).clickable(enabled = p != null, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) { Text(if (playing) "❚❚ PAUSE" else "▶ PLAY", color = if (playing) Palette.text else Palette.playInk, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        PadButton("1 ▶", Modifier.weight(1f)) { onSeek(pos + bar.toInt()) }
        PadButton("4 ▶▶", Modifier.weight(1f)) { onSeek(pos + (4 * bar).toInt()) }
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
    Box(
        modifier.height(38.dp).clip(RbShape).background(if (on) Palette.blue else Palette.surfaceHigh)
            .border(BorderStroke(1.dp, if (on) Palette.blue else Palette.line), RbShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) Palette.bg else color ?: if (enabled) Palette.blue else Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun RowLabel(text: String) = Text(text, color = Palette.text, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))

/** Os ajustes, em fileiras como LOOP / KEY do player. */
@Composable
private fun Controls(app: ManagerApp, a: TrackItem, b: TrackItem, tr: Transition, ga: TrackGrid?, gb: TrackGrid?, advanced: Boolean, onToggle: () -> Unit) {
    val desk = app.sets
    fun edit(t: Transition) = desk.editMix(t)
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            listOf(MixStyle.BASS_SWAP to "GRAVE", MixStyle.BLEND to "MISTURA", MixStyle.ECHO to "ECHO", MixStyle.CUT to "CORTE").forEach { (s, label) ->
                PadButton(label, Modifier.weight(1f), on = tr.style == s) { edit(tr.copy(style = s)) }
            }
        }
        PadButton(if (advanced) "MENOS ▴" else "AJUSTAR ▾", Modifier.fillMaxWidth()) { onToggle() }
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
        val p = desk.mixPreview
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RowLabel("SYNC")
            PadButton("SYNC FINO", Modifier.weight(1.4f), on = tr.phaseLock) { edit(tr.copy(phaseLock = !tr.phaseLock)) }
            // Quanto os bumbos estavam fora com as grades alinhadas (o que o sync do player faria).
            val m = p?.measuredMs
            Box(Modifier.weight(1f).height(38.dp).border(BorderStroke(1.dp, if (m != null && kotlin.math.abs(m) >= 8) Palette.orange else Palette.line), RbShape), contentAlignment = Alignment.Center) {
                Text(m?.let { "%+.0f ms".format(it) } ?: "—", color = if (m != null && kotlin.math.abs(m) >= 8) Palette.orange else Palette.mutedBright, fontSize = 13.sp, fontFamily = Mono)
            }
            PadButton("GRADE D2", Modifier.weight(1.2f), enabled = m != null && kotlin.math.abs(m) >= 3) { desk.fixGridOfIncoming() }
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
