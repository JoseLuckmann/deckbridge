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
    androidx.activity.compose.BackHandler { desk.close() }
    val allKey = desk.setKey(plan)
    Column(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).border(BorderStroke(1.dp, Palette.line)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { desk.close() }) { Text("‹", fontSize = 22.sp) }
            Text("SET", color = Palette.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(p.name, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text("≈" + mmss(SetPlanner.lengthMs(v, ids, plan)), color = Palette.orange, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.padding(horizontal = 8.dp))
            PlayButton(on = app.preview.playingKey == allKey, enabled = plan.isNotEmpty(), label = "OUVIR") { desk.playAll(plan) }
        }
        if (ids.size >= 2) FlowStrip(ids.mapNotNull { v.track(it) }, plan)
        LazyColumn(Modifier.weight(1f)) {
            ids.forEachIndexed { n, id ->
                val t = v.track(id) ?: return@forEachIndexed
                item(key = "t$id") { SetTrackRow(n + 1, t, playing = app.preview.playingId == id) { app.preview.toggle(t) } }
                val tr = plan.firstOrNull { it.fromId == id && n < ids.lastIndex && it.toId == ids[n + 1] }
                if (tr != null) {
                    val b = v.track(tr.toId)!!
                    item(key = "x$id-${tr.toId}") {
                        TransitionRow(t, b, tr, SetPlanner.fit(t, b), playing = app.preview.playingKey == desk.keyFor(tr), onPlay = { desk.play(tr) }, onEdit = { desk.openMix(tr) })
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
private fun TransitionRow(a: TrackItem, b: TrackItem, tr: Transition, fit: Fit, playing: Boolean, onPlay: () -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onEdit).padding(start = 22.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // O fio que liga uma faixa à outra.
        Box(Modifier.width(2.dp).fillMaxHeight().background(if (fit.good) Palette.keyInk.copy(alpha = 0.6f) else Palette.orange.copy(alpha = 0.6f)))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${keyLabel(a)}→${keyLabel(b)}", color = if (fit.keyOk) Palette.keyInk else Palette.orange, fontSize = 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
                Text("  " + fitText(fit), color = if (fit.bpmOk) Palette.mutedBright else Palette.orange, fontSize = 12.sp, fontFamily = Mono)
            }
            Text("${tr.style.title} · ${tr.bars} comp. · ${mmss(tr.outMs)}", color = Palette.muted, fontSize = 12.sp, maxLines = 1)
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

