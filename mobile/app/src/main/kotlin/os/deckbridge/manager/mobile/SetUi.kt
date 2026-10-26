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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.manager.mix.Fit
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.smart.Smart

private fun mmss(ms: Int): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

private fun keyLabel(t: TrackItem): String = Smart.camelot(t.key)?.let { "${it.first}${it.second}" } ?: t.key.ifEmpty { "—" }

/**
 * O set: as faixas da playlist na ordem, e entre cada par a transição programada (tom e andamento, estilo, compassos,
 * ▶ para ouvir montada). No topo, a curva de andamento do set e o ▶ que toca só as transições, uma depois da outra.
 * Embaixo, as próximas que combinam com a última.
 */
@Composable
fun SetScreen(app: ManagerApp) {
    val desk = app.sets
    val m = app.model
    val v = m.view ?: return
    val p = v.playlists.firstOrNull { it.id == desk.playlistId } ?: run { desk.close(); return }
    val ids = desk.ids()
    val plan = remember(ids, desk.saved, v) { desk.plan(ids) }
    androidx.activity.compose.BackHandler { if (desk.rehearsal != null) desk.stopRehearsal() else desk.close() }
    val allKey = desk.setKey(plan)
    val issues = remember(ids, plan, v) { desk.issues(ids, plan) }
    var sortOpen by remember { mutableStateOf(false) }
    var bridgeFor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    Column(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).border(BorderStroke(1.dp, Palette.line)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { desk.close() }) { Text("‹", fontSize = 22.sp) }
            Text("SET", color = Palette.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(p.name, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text("≈" + mmss(SetPlanner.lengthMs(v, ids, plan)), color = Palette.orange, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.padding(horizontal = 8.dp))
            PlayButton(on = app.preview.playingKey == allKey, enabled = plan.isNotEmpty(), label = "OUVIR") { desk.playAll(plan) }
        }
        if (ids.size >= 2) FlowStrip(ids.mapNotNull { v.track(it) }, plan)
        if (ids.size >= 2) ActionsRow(
            issues = issues.map { it.fromId to it.toId }.distinct().size,
            redo = plan.count { it.mark == Transition.Mark.REDO },
            onRehearse = { desk.startRehearsal(plan) },
            onSort = { sortOpen = true },
        )
        LazyColumn(Modifier.weight(1f)) {
            ids.forEachIndexed { n, id ->
                val t = v.track(id) ?: return@forEachIndexed
                item(key = "t$id") { SetTrackRow(n + 1, t, playing = app.preview.playingId == id) { app.preview.toggle(t) } }
                val tr = plan.firstOrNull { it.fromId == id && n < ids.lastIndex && it.toId == ids[n + 1] }
                if (tr != null) {
                    val b = v.track(tr.toId)!!
                    item(key = "x$id-${tr.toId}") {
                        val here = issues.filter { it.fromId == id && it.toId == tr.toId }
                        TransitionRow(
                            t, b, tr, SetPlanner.fit(t, b), playing = app.preview.playingKey == desk.keyFor(tr), issues = here,
                            rehearsing = desk.rehearsal?.let { plan.getOrNull(it) } == tr,
                            onPlay = { desk.play(tr) }, onEdit = { desk.openMix(tr) },
                            onBridge = if (here.any { it.bridgeable }) ({ bridgeFor = id to tr.toId }) else null,
                        )
                    }
                }
            }
            if (ids.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("Playlist vazia", color = Palette.muted, fontSize = 14.sp) } }
            val next = desk.suggestions(ids)
            if (next.isNotEmpty()) {
                item(key = "next") { Text("PRÓXIMA", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp)) }
                val last = v.track(ids.last())!!
                next.forEach { s ->
                    item(key = "s${s.id}") { SuggestionRow(s, SetPlanner.fit(last, s), onPlay = { app.preview.toggle(s) }, playing = app.preview.playingId == s.id) { desk.append(s) } }
                }
            }
        }
        desk.rehearsal?.let { at -> plan.getOrNull(at)?.let { tr -> RehearsalBar(app, at, plan, tr) } }
    }
    if (sortOpen) {
        val shapes = os.deckbridge.manager.mix.SetShape.values().toList()
        ChoiceDialog("Ordenar para mixar", shapes.map { it.title }, onPick = { n -> sortOpen = false; desk.reorder(shapes[n]) }, onClose = { sortOpen = false })
    }
    bridgeFor?.let { (a, b) ->
        val options = remember(a, b, ids) { desk.bridges(a, b) }
        if (options.isEmpty()) {
            ConfirmDialog("Ponte", "Nenhuma faixa da biblioteca combina com as duas.", "OK", onConfirm = { bridgeFor = null }, onClose = { bridgeFor = null })
        } else {
            ChoiceDialog("Ponte entre as duas", options.map { "${it.title.ifEmpty { it.fileName }} · ${keyLabel(it)} · %.0f".format(it.bpm) }, onPick = { n ->
                bridgeFor = null
                desk.insertBridge(a, options[n])
            }, onClose = { bridgeFor = null })
        }
    }
}

/** Ensaiar, ordenar e o resumo dos avisos, logo abaixo da curva. */
@Composable
private fun ActionsRow(issues: Int, redo: Int, onRehearse: () -> Unit, onSort: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onRehearse, modifier = Modifier.height(40.dp)) { Text("ENSAIAR", fontSize = 12.sp) }
        OutlinedButton(onClick = onSort, modifier = Modifier.height(40.dp)) { Text("ORDENAR", fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        if (redo > 0) Text("✗ $redo", color = Palette.orange, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.padding(end = 8.dp))
        Text(if (issues == 0) "✓ trocas ok" else "⚠ $issues", color = if (issues == 0) Palette.keyInk else Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** O ensaio: a transição que está tocando e os botões para dizer se ficou boa. */
@Composable
private fun RehearsalBar(app: ManagerApp, at: Int, plan: List<Transition>, tr: Transition) {
    val v = app.model.view ?: return
    val a = v.track(tr.fromId)
    val b = v.track(tr.toId)
    Column(Modifier.fillMaxWidth().background(Palette.surfaceHigh).border(BorderStroke(1.dp, Palette.blueActiveLine)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ENSAIO", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Text("  ${at + 1}/${plan.size}", color = Palette.orange, fontSize = 13.sp, fontFamily = Mono)
            Text(
                "  ${a?.title.orEmpty()} → ${b?.title.orEmpty()}", color = Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { app.sets.rehearse(plan, Transition.Mark.REDO) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("✗ REFAZER", color = Palette.orange, fontSize = 13.sp) }
            Box(
                Modifier.weight(1f).height(48.dp).clip(RbShape).background(Palette.keyFill).border(BorderStroke(1.dp, Palette.keyInk.copy(alpha = 0.5f)), RbShape)
                    .clickable { app.sets.rehearse(plan, Transition.Mark.GOOD) },
                contentAlignment = Alignment.Center,
            ) { Text("✓ BOA", color = Palette.keyInk, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { app.sets.stopRehearsal() }, modifier = Modifier.height(48.dp)) { Text("■", fontSize = 14.sp) }
        }
    }
}

@Composable
private fun PlayButton(on: Boolean, enabled: Boolean = true, label: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(38.dp).clip(RbShape).background(if (on) Palette.blueActiveFill else Palette.playBg)
            .border(BorderStroke(1.dp, if (on) Palette.blue else Palette.playInk.copy(alpha = 0.4f)), RbShape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (on) "■" else "▶", color = if (enabled) Palette.playInk else Palette.muted, fontSize = 14.sp)
        if (label != null) Text(" $label", color = if (enabled) Palette.playInk else Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** A curva de andamento do set, com os pontos verdes onde a troca de tom é suave e laranja onde não é. */
@Composable
private fun FlowStrip(tracks: List<TrackItem>, plan: List<Transition>) {
    val bpms = tracks.map { it.bpm }.filter { it > 0 }
    if (bpms.isEmpty()) return
    val lo = bpms.min() - 2
    val hi = bpms.max() + 2
    val good = tracks.zipWithNext().map { (a, b) -> SetPlanner.fit(a, b).good }
    Row(Modifier.fillMaxWidth().height(56.dp).background(Palette.surface).border(BorderStroke(1.dp, Palette.line)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(44.dp)) {
            Text("%.0f".format(hi - 2), color = Palette.muted, fontSize = 10.sp, fontFamily = Mono)
            Spacer(Modifier.weight(1f))
            Text("%.0f".format(lo + 2), color = Palette.muted, fontSize = 10.sp, fontFamily = Mono)
        }
        val line = Palette.blue
        val ok = Palette.keyInk
        val warn = Palette.orange
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val n = tracks.size
            fun pt(i: Int): Offset {
                val bpm = tracks[i].bpm.takeIf { it > 0 } ?: ((lo + hi) / 2)
                val x = if (n == 1) size.width / 2 else size.width * i / (n - 1)
                val y = size.height * (1 - ((bpm - lo) / (hi - lo)).toFloat())
                return Offset(x, y)
            }
            for (i in 0 until n - 1) drawLine(line, pt(i), pt(i + 1), strokeWidth = 2.dp.toPx())
            for (i in 0 until n - 1) {
                val mid = Offset((pt(i).x + pt(i + 1).x) / 2, (pt(i).y + pt(i + 1).y) / 2)
                drawCircle(if (good[i]) ok else warn, 3.5.dp.toPx(), mid)
            }
            for (i in 0 until n) drawCircle(Palette.text, 2.5.dp.toPx(), pt(i))
        }
    }
}

@Composable
private fun KeyBadge(t: TrackItem) {
    Box(Modifier.clip(RbShape).background(Palette.keyFill).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(keyLabel(t), color = Palette.keyInk, fontSize = 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SetTrackRow(n: Int, t: TrackItem, playing: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).background(if (playing) Palette.selectedRow else Palette.surface).clickable(onClick = onOpen).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$n", color = Palette.muted, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (t.bpm > 0) "%.1f".format(t.bpm) else "—", color = Palette.orange, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.padding(end = 8.dp))
        KeyBadge(t)
    }
}

private fun fitText(f: Fit): String = if (f.bpmDiffPct == 0.0) "=" else "%+.1f%%".format(f.bpmDiffPct)

@Composable
private fun TransitionRow(
    a: TrackItem, b: TrackItem, tr: Transition, fit: Fit, playing: Boolean, issues: List<os.deckbridge.manager.mix.SetIssue>, rehearsing: Boolean,
    onPlay: () -> Unit, onEdit: () -> Unit, onBridge: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (rehearsing) Palette.blueActiveFill else Palette.bg).clickable(onClick = onEdit)
            .padding(start = 22.dp, end = 10.dp, top = 4.dp, bottom = 4.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // O fio que liga uma faixa à outra.
        Box(Modifier.width(2.dp).fillMaxHeight().background(if (fit.good) Palette.keyInk.copy(alpha = 0.6f) else Palette.orange.copy(alpha = 0.6f)))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (tr.mark) {
                    Transition.Mark.GOOD -> Text("✓ ", color = Palette.keyInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Transition.Mark.REDO -> Text("✗ ", color = Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    null -> {}
                }
                Text("${keyLabel(a)}→${keyLabel(b)}", color = if (fit.keyOk) Palette.keyInk else Palette.orange, fontSize = 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
                Text("  " + fitText(fit), color = if (fit.bpmOk) Palette.mutedBright else Palette.orange, fontSize = 12.sp, fontFamily = Mono)
            }
            Text("${tr.style.title} · ${tr.bars} comp. · ${mmss(tr.outMs)}", color = Palette.muted, fontSize = 12.sp, maxLines = 1)
            if (issues.isNotEmpty()) Text("⚠ " + issues.joinToString(" · ") { it.text }, color = Palette.orange, fontSize = 11.sp, maxLines = 2)
        }
        if (onBridge != null) {
            OutlinedButton(onClick = onBridge, modifier = Modifier.height(38.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text("PONTE", fontSize = 10.sp) }
            Spacer(Modifier.width(4.dp))
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.height(38.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) { Text("MIX", fontSize = 11.sp) }
        Spacer(Modifier.width(6.dp))
        PlayButton(on = playing, onClick = onPlay)
    }
}

@Composable
private fun SuggestionRow(t: TrackItem, fit: Fit, playing: Boolean, onPlay: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${keyLabel(t)} · ${fitText(fit)}", color = if (fit.good) Palette.keyInk else Palette.orange, fontSize = 12.sp, fontFamily = Mono)
        }
        PlayButton(on = playing, onClick = onPlay)
        OutlinedButton(onClick = onAdd, modifier = Modifier.padding(start = 6.dp).height(38.dp)) { Text("＋", fontSize = 16.sp) }
    }
}

