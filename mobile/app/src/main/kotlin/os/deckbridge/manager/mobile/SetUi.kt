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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.manager.mix.Fit
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.SetTempo
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
    val branch = desk.branch
    val masters = remember(ids, desk.masterFixed, v, branch) { desk.masters(ids) }
    val branchesHere = desk.branchesHere()
    fun back() = if (desk.rehearsal != null) desk.stopRehearsal() else if (branch != null) desk.openMain() else desk.close()
    androidx.activity.compose.BackHandler { back() }
    var masterOpen by remember { mutableStateOf(false) }
    var branchFrom by remember { mutableStateOf<Int?>(null) }
    val allKey = desk.setKey(plan)
    val issues = remember(ids, plan, v) { desk.issues(ids, plan) }
    var sortOpen by remember { mutableStateOf(false) }
    var bridgeFor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    Column(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).border(BorderStroke(1.dp, Palette.line)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { back() }) { Text("‹", fontSize = 22.sp) }
            if (branch != null) {
                // Num ramo: a letra e o set de onde sai.
                Text("RAMO ${desk.letterOf(branch)}", color = Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text(v.playlists.firstOrNull { it.id == branch.mainId }?.name.orEmpty(), color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            } else {
                Text("SET", color = Palette.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Text(p.name, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            }
            Text("≈" + mmss(SetPlanner.lengthMs(v, ids, plan)), color = Palette.orange, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.padding(horizontal = 8.dp))
            PlayButton(on = app.preview.playingKey == allKey, enabled = plan.isNotEmpty(), label = "OUVIR") { desk.playAll(plan) }
        }
        if (ids.size >= 2) FlowStrip(ids.mapNotNull { v.track(it) }, masters) { desk.mapOpen = true }
        if (ids.size >= 2) ActionsRow(
            issues = issues.map { it.fromId to it.toId }.distinct().size,
            redo = plan.count { it.mark == Transition.Mark.REDO },
            master = desk.masterFixed,
            onRehearse = { desk.startRehearsal(plan) },
            onSort = { sortOpen = true },
            onMaster = { masterOpen = true },
        )
        LazyColumn(Modifier.weight(1f)) {
            ids.forEachIndexed { n, id ->
                val t = v.track(id) ?: return@forEachIndexed
                // Num ramo, o ponto de virada e a volta são da linha principal: aparecem, mas não são do ramo.
                val anchor = branch != null && (n == 0 || (id == branch.rejoinTrackId && n == ids.lastIndex))
                item(key = "t$id-$n") {
                    SetTrackRow(
                        n + 1, t, masters.getOrNull(n), playing = app.preview.playingId == id, anchor = anchor,
                        onBranch = if (branch == null) ({ branchFrom = id }) else null,
                    ) { app.preview.toggle(t) }
                }
                branchesHere.filter { it.fromTrackId == id }.forEach { b ->
                    item(key = "b${b.playlistId}") { BranchRow(app, b, ids) { desk.open(b.playlistId) } }
                }
                val tr = plan.firstOrNull { it.fromId == id && n < ids.lastIndex && it.toId == ids[n + 1] }
                if (tr != null) {
                    val b = v.track(tr.toId)!!
                    item(key = "x$id-${tr.toId}") {
                        val here = issues.filter { it.fromId == id && it.toId == tr.toId }
                        TransitionRow(
                            t, b, tr, SetPlanner.fit(t, b), stretch = SetTempo.stretchPct(b.bpm, masters.getOrNull(n) ?: t.bpm), playing = app.preview.playingKey == desk.keyFor(tr), issues = here,
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
    if (masterOpen) {
        // Automático ou um valor fixo perto do meio do set.
        val mid = ids.mapNotNull { v.track(it)?.bpm }.filter { it > 0 }.sorted().let { if (it.isEmpty()) 128.0 else it[it.size / 2] }
        val values = (-3..3).map { kotlin.math.round(mid) + it * 1.0 }
        ChoiceDialog("Master do set", listOf("Automático (segue as faixas)") + values.map { "%.0f BPM".format(it) }, onPick = { n ->
            masterOpen = false
            desk.setMaster(if (n == 0) null else values[n - 1])
        }, onClose = { masterOpen = false })
    }
    branchFrom?.let { from -> BranchDialog(app, from) { branchFrom = null } }
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
private fun ActionsRow(issues: Int, redo: Int, master: Double?, onRehearse: () -> Unit, onSort: () -> Unit, onMaster: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onRehearse, modifier = Modifier.height(40.dp)) { Text("ENSAIAR", fontSize = 12.sp) }
        OutlinedButton(onClick = onSort, modifier = Modifier.height(40.dp)) { Text("ORDENAR", fontSize = 12.sp) }
        OutlinedButton(onClick = onMaster, modifier = Modifier.height(40.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
            Text(if (master == null) "MASTER AUTO" else "MASTER %.0f".format(master), fontSize = 11.sp)
        }
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

/**
 * A curva do master do set (linha azul) e o BPM de cada faixa (pontos): a distância entre os dois é quanto a faixa estica
 * para tocar no master. Ponto verde entre duas faixas: troca de tom suave; laranja: não.
 */
@Composable
private fun FlowStrip(tracks: List<TrackItem>, masters: List<Double>, onMap: () -> Unit) {
    val bpms = tracks.map { it.bpm }.filter { it > 0 } + masters
    if (bpms.isEmpty()) return
    val lo = bpms.min() - 2
    val hi = bpms.max() + 2
    val good = tracks.zipWithNext().map { (a, b) -> SetPlanner.fit(a, b).keyOk }
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Palette.surface).border(BorderStroke(1.dp, Palette.line)).clickable(onClick = onMap)
            .semantics { contentDescription = "Mapa do set" }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(52.dp)) {
            Text("MASTER", color = Palette.blue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("%.0f".format(hi - 2), color = Palette.muted, fontSize = 10.sp, fontFamily = Mono)
            Spacer(Modifier.weight(1f))
            Text("%.0f".format(lo + 2), color = Palette.muted, fontSize = 10.sp, fontFamily = Mono)
        }
        val line = Palette.blue
        val ok = Palette.keyInk
        val warn = Palette.orange
        val dot = Palette.text
        val tick = Palette.mutedBright
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val n = tracks.size
            fun x(i: Int) = if (n == 1) size.width / 2 else size.width * i / (n - 1)
            fun y(bpm: Double) = size.height * (1 - ((bpm - lo) / (hi - lo)).toFloat())
            for (i in 0 until n) {
                val m = masters.getOrNull(i) ?: continue
                val b = tracks[i].bpm
                // O quanto a faixa estica até o master.
                if (b > 0) drawLine(tick.copy(alpha = 0.5f), Offset(x(i), y(m)), Offset(x(i), y(b)), strokeWidth = 1.dp.toPx())
                if (i > 0) masters.getOrNull(i - 1)?.let { pm -> drawLine(line, Offset(x(i - 1), y(pm)), Offset(x(i), y(m)), strokeWidth = 2.5.dp.toPx()) }
            }
            for (i in 0 until n - 1) {
                val m = masters.getOrNull(i) ?: continue
                val mn = masters.getOrNull(i + 1) ?: m
                drawCircle(if (good[i]) ok else warn, 3.5.dp.toPx(), Offset((x(i) + x(i + 1)) / 2, (y(m) + y(mn)) / 2))
            }
            for (i in 0 until n) if (tracks[i].bpm > 0) drawCircle(dot, 2.5.dp.toPx(), Offset(x(i), y(tracks[i].bpm)))
        }
        // O mapa: a linha principal, os ramos e o andamento de cada caminho.
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("MAPA", color = Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("›", color = Palette.blue, fontSize = 20.sp)
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
private fun SetTrackRow(n: Int, t: TrackItem, master: Double?, playing: Boolean, anchor: Boolean, onBranch: (() -> Unit)?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(if (playing) Palette.selectedRow else if (anchor) Palette.bg else Palette.surface).clickable(onClick = onOpen)
            .padding(start = 14.dp, end = if (onBranch != null) 2.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (anchor) "↔" else "$n", color = if (anchor) Palette.orange else Palette.muted, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title.ifEmpty { t.fileName }, color = if (anchor) Palette.mutedBright else Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (anchor) "linha principal" else t.artist, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // O BPM em que toca (o master) e, embaixo, o da faixa e quanto ela estica.
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
            Text(master?.let { "%.1f".format(it) } ?: if (t.bpm > 0) "%.1f".format(t.bpm) else "—", color = Palette.orange, fontSize = 13.sp, fontFamily = Mono)
            if (master != null && t.bpm > 0) {
                val st = SetTempo.stretchPct(t.bpm, master)
                Text(
                    "%.0f %+.0f%%".format(t.bpm, st), fontSize = 10.sp, fontFamily = Mono,
                    color = when {
                        kotlin.math.abs(st) > SetTempo.MAX_PCT -> Palette.danger
                        kotlin.math.abs(st) > SetTempo.COMFORT_PCT -> Palette.orange
                        else -> Palette.muted
                    },
                )
            }
        }
        KeyBadge(t)
        if (onBranch != null) BranchButton(onBranch)
    }
}

/** O desenho do ramo (a linha que se divide em duas), feito à mão: o símbolo de bifurcação não existe em toda fonte. */
@Composable
private fun BranchIcon(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(w * 0.3f, h), Offset(w * 0.3f, h * 0.1f), stroke)
        drawLine(color, Offset(w * 0.3f, h * 0.55f), Offset(w * 0.8f, h * 0.15f), stroke)
        drawCircle(color, 2.2.dp.toPx(), Offset(w * 0.8f, h * 0.15f))
        drawCircle(color, 2.2.dp.toPx(), Offset(w * 0.3f, h * 0.1f))
    }
}

/** Criar um ramo a partir desta faixa. */
@Composable
private fun BranchButton(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clickable(onClick = onClick).semantics { contentDescription = "Criar ramo" },
        contentAlignment = Alignment.Center,
    ) { BranchIcon(Palette.blue) }
}

/** Um ramo que sai desta faixa: a letra, quantas faixas e onde volta. Tocar abre o SET do ramo. */
@Composable
private fun BranchRow(app: ManagerApp, b: os.deckbridge.manager.mix.SetBranch, line: List<Int>, onOpen: () -> Unit) {
    val v = app.model.view ?: return
    val p = v.playlists.firstOrNull { it.id == b.playlistId } ?: return
    val n = v.entries[b.playlistId].orEmpty().size
    val back = b.rejoinTrackId?.let { r -> line.indexOf(r).takeIf { it >= 0 }?.let { "volta no ${it + 1}" } } ?: "outro final"
    Row(
        Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onOpen).padding(start = 40.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BranchIcon(Palette.orange)
        Text(" RAMO ${app.sets.letterOf(b)}", color = Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("  $n faixas · $back", color = Palette.mutedBright, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("›", color = Palette.blue, fontSize = 18.sp)
    }
}

/** Criar um ramo daqui: quantas faixas, o rumo e se volta para a linha principal. */
@Composable
private fun BranchDialog(app: ManagerApp, from: Int, onClose: () -> Unit) {
    var count by remember { mutableStateOf(3) }
    var shape by remember { mutableStateOf(os.deckbridge.manager.mix.SetShape.RISING) }
    var rejoin by remember { mutableStateOf(true) }
    val title = app.model.view?.track(from)?.title.orEmpty()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ramo depois de\n$title", maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("FAIXAS", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 3, 4, 6).forEach { c -> FilterChip(count == c, { count = c }, { Text("$c") }) }
                }
                Text("RUMO", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        os.deckbridge.manager.mix.SetShape.RISING to "Mais energia",
                        os.deckbridge.manager.mix.SetShape.FLOW to "Manter",
                        os.deckbridge.manager.mix.SetShape.FALLING to "Mais calmo",
                    ).forEach { (sh, label) -> FilterChip(shape == sh, { shape = sh }, { Text(label, maxLines = 1) }) }
                }
                Text("VOLTA PARA A LINHA", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(rejoin, { rejoin = true }, { Text("Sim") })
                    FilterChip(!rejoin, { rejoin = false }, { Text("Não (outro final)") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onClose(); app.sets.createBranch(from, count, shape, rejoin) }) { Text("CRIAR") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
        containerColor = Palette.surfaceHigh,
    )
}

private fun fitText(f: Fit): String = if (f.bpmDiffPct == 0.0) "=" else "%+.1f%%".format(f.bpmDiffPct)

@Composable
private fun TransitionRow(
    a: TrackItem, b: TrackItem, tr: Transition, fit: Fit, stretch: Double, playing: Boolean, issues: List<os.deckbridge.manager.mix.SetIssue>, rehearsing: Boolean,
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
                // Quanto a que entra estica para tocar no master (até 10%: normal).
                Text(
                    "  %+.1f%%".format(stretch), fontSize = 12.sp, fontFamily = Mono,
                    color = if (kotlin.math.abs(stretch) <= SetTempo.COMFORT_PCT) Palette.mutedBright else if (kotlin.math.abs(stretch) <= SetTempo.MAX_PCT) Palette.orange else Palette.danger,
                )
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

