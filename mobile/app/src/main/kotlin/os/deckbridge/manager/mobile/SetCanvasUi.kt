package os.deckbridge.manager.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.manager.mix.CanvasCard
import os.deckbridge.manager.mix.CanvasLane
import os.deckbridge.manager.mix.CanvasLink
import os.deckbridge.manager.mix.SetCanvasLayout
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.TrackItem

// Medidas do quadro: cada faixa é um cartão; entre um e outro, o espaço da troca.
private val PLUS = 40.dp

/**
 * Onde cada coisa fica no quadro. Deitado (tela larga): o tempo corre para a direita e cada linha é uma fileira. Em pé
 * (celular na vertical): o tempo desce e cada linha é uma coluna, com os ramos abrindo para a direita.
 */
private class Geo(val vertical: Boolean) {
    val cardW = if (vertical) 150.dp else 132.dp
    val cardH = if (vertical) 58.dp else 64.dp
    /** De uma posição na linha para a próxima (com o espaço da troca). */
    val step = if (vertical) 96.dp else 176.dp
    /** De uma linha para a outra. */
    val lane = if (vertical) 172.dp else 104.dp
    val left = if (vertical) 12.dp else 30.dp
    val top = if (vertical) 34.dp else 22.dp
    fun x(row: Int, col: Int): Dp = if (vertical) left + lane * row else left + step * col
    fun y(row: Int, col: Int): Dp = if (vertical) top + step * col else top + lane * row
    fun x(c: CanvasCard) = x(c.row, c.col)
    fun y(c: CanvasCard) = y(c.row, c.col)
    fun width(l: SetCanvasLayout): Dp = if (vertical) x(l.rows, 0) + 8.dp else x(0, l.cols) + 24.dp
    fun height(l: SetCanvasLayout): Dp = if (vertical) y(0, l.cols) + 8.dp else y(l.rows, 0) + 8.dp
    /** O ＋ no fim da linha: centrado no lugar da próxima faixa. */
    fun plusX(lane: CanvasLane) = x(lane.row, lane.endCol) + if (vertical) (cardW - PLUS) / 2 else 0.dp
    fun plusY(lane: CanvasLane) = y(lane.row, lane.endCol) + if (vertical) 0.dp else (cardH - PLUS) / 2
}

/** A cor de cada linha: a principal em azul, os ramos nas cores de destaque do player. */
private fun laneColor(row: Int): Color =
    if (row == 0) Palette.blue else listOf(Palette.orange, Palette.keyInk, Palette.trackColors[5], Palette.trackColors[7], Palette.trackColors[0])[(row - 1) % 5]

/**
 * O set como um quadro: a linha principal e cada ramo ao lado, saindo da faixa de onde se divide e voltando onde volta.
 * Tocar num cartão o escolhe (e a linha dele passa a ser a que se mexe); o ＋ no fim de cada linha põe faixas; o número
 * entre dois cartões é a troca (quantos compassos): tocar abre o MIX.
 */
@Composable
fun SetBoard(
    app: ManagerApp, layout: SetCanvasLayout, plans: Map<Int, List<Transition>>, issues: Set<Pair<Int, Int>>, selected: CanvasCard?,
    rehearsing: Transition?, onSelect: (CanvasCard?) -> Unit, onAdd: (CanvasLane) -> Unit, onMix: (CanvasLink, Transition) -> Unit,
    onJoin: (CanvasLane) -> Unit, modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize().background(Palette.bg)) {
        Board(app, Geo(vertical = maxHeight > maxWidth), layout, plans, issues, selected, rehearsing, onSelect, onAdd, onMix, onJoin)
    }
}

@Composable
private fun Board(
    app: ManagerApp, g: Geo, layout: SetCanvasLayout, plans: Map<Int, List<Transition>>, issues: Set<Pair<Int, Int>>, selected: CanvasCard?,
    rehearsing: Transition?, onSelect: (CanvasCard?) -> Unit, onAdd: (CanvasLane) -> Unit, onMix: (CanvasLink, Transition) -> Unit,
    onJoin: (CanvasLane) -> Unit,
) {
    val v = app.model.view ?: return
    val desk = app.sets
    val width = g.width(layout)
    val height = g.height(layout)
    fun transition(l: CanvasLink): Transition? = plans[l.playlistId]?.firstOrNull { it.fromId == l.from.trackId && it.toId == l.to.trackId }
    val laneOf = layout.lanes.associateBy { it.playlistId }
    val good = Palette.keyInk
    val warn = Palette.orange
    val loose = Palette.muted
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
        Box(Modifier.size(width, height).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onSelect(null) }) {
            // Os fios: dentro da linha, do fim de uma ao começo da outra; o ramo sai pelo lado da faixa de onde se divide e
            // chega pelo lado da faixa onde volta.
            Canvas(Modifier.size(width, height)) {
                val cw = g.cardW.toPx()
                val ch = g.cardH.toPx()
                val pl = PLUS.toPx()
                fun pos(c: CanvasCard) = Offset(g.x(c).toPx(), g.y(c).toPx())
                /** Saída para a próxima da linha e chegada da anterior. */
                fun out(c: CanvasCard) = pos(c).let { if (g.vertical) Offset(it.x + cw / 2, it.y + ch) else Offset(it.x + cw, it.y + ch / 2) }
                fun into(c: CanvasCard) = pos(c).let { if (g.vertical) Offset(it.x + cw / 2, it.y) else Offset(it.x, it.y + ch / 2) }
                /** O lado da faixa por onde um ramo sai ou volta. */
                fun side(c: CanvasCard) = pos(c).let { if (g.vertical) Offset(it.x + cw, it.y + ch / 2) else Offset(it.x + cw / 2, it.y + ch) }
                fun plusIn(lane: CanvasLane) = Offset(g.plusX(lane).toPx(), g.plusY(lane).toPx()).let { if (g.vertical) Offset(it.x + pl / 2, it.y) else Offset(it.x, it.y + pl / 2) }
                fun plusOut(lane: CanvasLane) = Offset(g.plusX(lane).toPx(), g.plusY(lane).toPx()).let { if (g.vertical) Offset(it.x + pl / 2, it.y + pl) else Offset(it.x + pl, it.y + pl / 2) }
                /** [leaving]: sai pelo lado (vai para a outra linha antes de seguir); senão segue a linha antes de virar. */
                fun curve(a: Offset, b: Offset, leaving: Boolean, color: Color, dashed: Boolean) {
                    val p = Path().apply {
                        moveTo(a.x, a.y)
                        if (a.x == b.x || a.y == b.y) lineTo(b.x, b.y) else {
                            // Primeiro na vertical quando sai pelo lado no quadro deitado, ou volta no quadro em pé.
                            val c = if (leaving != g.vertical) Offset(a.x, b.y) else Offset(b.x, a.y)
                            cubicTo(c.x, c.y, c.x, c.y, b.x, b.y)
                        }
                    }
                    drawPath(p, color, style = Stroke(2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null))
                }
                for (l in layout.links) {
                    val lane = laneOf[l.playlistId] ?: continue
                    val tr = transition(l)
                    val color = when {
                        l.pool || tr == null -> loose
                        (l.from.trackId to l.to.trackId) in issues -> warn
                        v.track(l.from.trackId)?.let { a -> v.track(l.to.trackId)?.let { b -> SetPlanner.fit(a, b).good } } == true -> good
                        else -> warn
                    }.copy(alpha = 0.8f)
                    val start = if (l.from.row == lane.row) out(l.from) else side(l.from)
                    if (l.to.row == lane.row) {
                        curve(start, into(l.to), leaving = l.from.row != lane.row, color, l.pool)
                    } else {
                        // Chega na volta: passa pelo ＋ do fim do ramo e entra pelo lado da faixa onde volta.
                        if (l.from.row != lane.row) curve(start, plusIn(lane), leaving = true, color, l.pool)
                        curve(plusOut(lane), side(l.to), leaving = false, color, l.pool)
                    }
                }
            }
            layout.lanes.forEach { lane ->
                val on = lane.playlistId == desk.playlistId
                // A letra da linha (tocar passa a mexer nela): à esquerda da fileira, ou em cima da coluna.
                val (lx, ly) = if (g.vertical) g.x(lane.row, 0) + g.cardW / 2 - 11.dp to 6.dp else 4.dp to g.y(lane.row, 0) + g.cardH / 2 - 12.dp
                Box(
                    Modifier.offset(lx, ly).size(22.dp, 24.dp).clickable { desk.focus(lane.playlistId); onSelect(null) }
                        .semantics { contentDescription = "Linha ${lane.letter}" },
                    contentAlignment = Alignment.Center,
                ) { Text("${lane.letter}", color = laneColor(lane.row).copy(alpha = if (on) 1f else 0.55f), fontSize = if (on) 16.sp else 13.sp, fontWeight = FontWeight.Bold) }
                // A pool da linha: um rótulo junto da primeira faixa dela.
                lane.cards.firstOrNull { it.pool }?.let { c ->
                    Text(
                        "POOL", color = Palette.orange, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                        modifier = Modifier.offset(g.x(c) + if (g.vertical) g.cardW - 30.dp else 0.dp, g.y(c) - 13.dp),
                    )
                }
                lane.cards.forEach { c ->
                    val t = v.track(c.trackId) ?: return@forEach
                    BoardCard(g, t, c, selected = selected == c, playing = app.preview.playingId == c.trackId, color = laneColor(lane.row)) { onSelect(if (selected == c) null else c) }
                }
                PlusCard(g, lane, laneColor(lane.row)) { onAdd(lane) }
                // Num ramo: onde ele junta de volta (ou FIM, se é outro final); tocar muda.
                if (lane.branch != null) {
                    val back = lane.rejoin?.let { v.track(it.trackId) }
                    Text(
                        if (back != null) "↩ " + shortTitle(back.title.ifEmpty { back.fileName }) else "FIM ▾",
                        color = if (back != null) Palette.mutedBright else Palette.orange, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Por cima do fio da volta (fundo da tela): lê como um rótulo no fio.
                        modifier = Modifier.offset(g.x(lane.row, lane.endCol), g.plusY(lane) + PLUS + 4.dp).width(g.cardW).background(Palette.bg)
                            .clickable { onJoin(lane) }.semantics { contentDescription = "Junta ${lane.letter}" }.padding(vertical = 4.dp),
                        textAlign = if (g.vertical) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
                    )
                }
            }
            // A troca entre dois cartões: os compassos; tocar abre o MIX dela.
            layout.links.forEach { l ->
                val lane = laneOf[l.playlistId] ?: return@forEach
                if (l.pool) return@forEach
                val tr = transition(l) ?: return@forEach
                val issue = (l.from.trackId to l.to.trackId) in issues
                val gap = g.step - (if (g.vertical) g.cardH else g.cardW)
                val (x, y) = when {
                    // Entre dois cartões da mesma linha: no meio do espaço.
                    l.from.row == lane.row && l.to.row == lane.row ->
                        if (g.vertical) g.x(l.from) + g.cardW / 2 - 18.dp to g.y(l.from) + g.cardH + gap / 2 - 11.dp
                        else g.x(l.from) + g.cardW + gap / 2 - 18.dp to g.y(l.from) + g.cardH / 2 - 11.dp
                    // Saindo do ponto de virada: logo antes da primeira do ramo.
                    l.to.row == lane.row ->
                        if (g.vertical) g.x(l.to) + g.cardW / 2 + 6.dp to g.y(l.to) - 26.dp
                        else g.x(l.to) - 40.dp to g.y(l.to) + g.cardH / 2 - 11.dp
                    // Chegando na volta: junto do ＋ do fim do ramo.
                    else -> if (g.vertical) g.plusX(lane) + PLUS + 6.dp to g.plusY(lane) + PLUS / 2 - 11.dp
                    else g.plusX(lane) + PLUS + 6.dp to g.plusY(lane) - 22.dp
                }
                LinkPill(tr, issue, on = rehearsing == tr, playing = app.preview.playingKey == desk.keyFor(tr), modifier = Modifier.offset(x, y)) { onMix(l, tr) }
            }
        }
    }
}

@Composable
private fun BoardCard(g: Geo, t: TrackItem, c: CanvasCard, selected: Boolean, playing: Boolean, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.offset(g.x(c), g.y(c)).size(g.cardW, g.cardH).clip(RbShape)
            .background(if (playing) Palette.selectedRow else if (c.pool) Palette.bg else Palette.surface)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Palette.blue else if (c.pool) Palette.orange.copy(alpha = 0.5f) else Palette.line), RbShape)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // A tirinha da cor da linha: de que caminho é a faixa.
            Box(Modifier.padding(top = 3.dp, end = 6.dp).size(3.dp, 12.dp).background(color.copy(alpha = 0.8f)))
            Text(shortTitle(t.title.ifEmpty { t.fileName }), color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = if (g.vertical) 1 else 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        }
        Row {
            Text(keyLabel(t.key), color = Palette.keyInk, fontSize = 11.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
            Text("  " + bpmLabel(t.bpm), color = Palette.orange, fontSize = 11.sp, fontFamily = Mono)
        }
    }
}

@Composable
private fun PlusCard(g: Geo, lane: CanvasLane, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.offset(g.plusX(lane), g.plusY(lane)).size(PLUS).clip(RbShape).background(Palette.surface)
            .border(BorderStroke(1.dp, color.copy(alpha = 0.6f)), RbShape).clickable(onClick = onClick)
            .semantics { contentDescription = "Pôr faixa na linha ${lane.letter}" },
        contentAlignment = Alignment.Center,
    ) { Text("＋", color = color, fontSize = 18.sp) }
}
@Composable
private fun LinkPill(tr: Transition, issue: Boolean, on: Boolean, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = if (issue) Palette.orange else Palette.mutedBright
    Box(
        modifier.size(36.dp, 22.dp).clip(RbShape).background(if (on || playing) Palette.blueActiveFill else Palette.surfaceHigh)
            .border(BorderStroke(1.dp, if (on || playing) Palette.blue else ink.copy(alpha = 0.5f)), RbShape).clickable(onClick = onClick)
            .semantics { contentDescription = "Troca ${tr.fromId}→${tr.toId}" },
        contentAlignment = Alignment.Center,
    ) {
        val mark = when {
            issue -> "⚠"
            tr.mark == Transition.Mark.GOOD -> "✓"
            tr.mark == Transition.Mark.REDO -> "✗"
            else -> ""
        }
        Text(mark + tr.bars, color = if (tr.mark == Transition.Mark.GOOD && !issue) Palette.keyInk else ink, fontSize = 11.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    }
}

/** A faixa escolhida no quadro: ouvir, andar para os lados, dividir a linha daqui e tirar. */
@Composable
fun BoardActions(app: ManagerApp, card: CanvasCard, onMove: (Int) -> Unit, onSplit: () -> Unit, onRemove: () -> Unit) {
    val t = app.model.view?.track(card.trackId) ?: return
    val count = app.sets.entries().size
    Column(Modifier.fillMaxWidth().background(Palette.surfaceHigh).border(BorderStroke(1.dp, Palette.blueActiveLine)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { app.preview.toggle(t) }, modifier = Modifier.height(40.dp)) { Text(if (app.preview.playingId == t.id) "■" else "▶", fontSize = 14.sp) }
            OutlinedButton(onClick = { onMove(-1) }, enabled = card.index > 0, modifier = Modifier.height(40.dp).semantics { contentDescription = "Para trás" }) { Text("‹", fontSize = 18.sp) }
            OutlinedButton(onClick = { onMove(1) }, enabled = card.index < count - 1, modifier = Modifier.height(40.dp).semantics { contentDescription = "Para frente" }) { Text("›", fontSize = 18.sp) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onSplit, modifier = Modifier.height(40.dp)) { Text("DIVIDIR", fontSize = 12.sp) }
            OutlinedButton(onClick = onRemove, modifier = Modifier.height(40.dp)) { Text("TIRAR", color = Palette.danger, fontSize = 12.sp) }
        }
    }
}

/** Pôr faixas no fim de uma linha: as que combinam com a última, ou a busca na biblioteca. Fica aberto para pôr várias. */
@Composable
fun AddToLineDialog(app: ManagerApp, letter: Char, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    // A busca só aparece quando se pede: o normal é escolher entre as que combinam.
    var searching by remember { mutableStateOf(false) }
    val v = app.model.view ?: return
    val desk = app.sets
    val choices = remember(query, v, desk.playlistId) { desk.addChoices(query) }
    val last = (desk.entries().lastOrNull() ?: desk.branch?.fromTrackId)?.let { v.track(it) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pôr na linha $letter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (searching) {
                    androidx.compose.material3.OutlinedTextField(query, { query = it }, placeholder = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (last != null) "COMBINAM COM A ÚLTIMA" else "DA BIBLIOTECA", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { searching = true }) { Text("BUSCAR", fontSize = 12.sp) }
                }
                if (choices.isEmpty()) Text("Nada para pôr", color = Palette.muted, fontSize = 13.sp)
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    choices.forEach { t ->
                        val fit = last?.let { SetPlanner.fit(it, t) }
                        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (fit != null) Box(Modifier.size(8.dp).clip(RbShape).background(if (fit.good) Palette.keyInk else Palette.orange))
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${keyLabel(t.key)} · ${bpmLabel(t.bpm)}", color = Palette.mutedBright, fontSize = 12.sp, fontFamily = Mono)
                            }
                            TextButton(onClick = { app.preview.toggle(t) }) { Text(if (app.preview.playingId == t.id) "■" else "▶", fontSize = 14.sp) }
                            OutlinedButton(onClick = { desk.append(t); query = "" }, modifier = Modifier.height(36.dp).width(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("＋", fontSize = 16.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("PRONTO") } },
        containerColor = Palette.surfaceHigh,
    )
}
