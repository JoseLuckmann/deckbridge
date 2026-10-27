package os.deckbridge.manager.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import os.deckbridge.manager.mix.SetPool
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.smart.Smart

private fun mmss(ms: Int): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)


/**
 * O set: as faixas da playlist na ordem, e entre cada par a transição programada (tom e andamento, estilo, compassos,
 * ▶ para ouvir montada). No topo, a curva de andamento do set e o ▶ que toca só as transições, uma depois da outra.
 * Embaixo, as próximas que combinam com a última.
 */
@Composable
fun SetScreen(app: StudioHost) {
    val desk = app.sets
    val m = app.model
    val v = m.view ?: return
    val p = v.playlists.firstOrNull { it.id == desk.playlistId } ?: run { desk.close(); return }
    val ids = desk.ids()
    val plan = remember(ids, desk.saved, v) { desk.plan(ids) }
    val branch = desk.branch
    val masters = remember(ids, desk.masterFixed, v, branch) { desk.masters(ids) }
    val branchesHere = desk.branchesHere()
    // O quadro (padrão): o set inteiro numa tela; a lista é a tela antiga, por extenso.
    val board = !app.setAsList
    var picked by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var addTo by remember { mutableStateOf<Char?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    fun back() = when {
        desk.live -> desk.stopLive()
        desk.rehearsal != null -> desk.stopRehearsal()
        board && picked != null -> picked = null
        branch != null && !board -> desk.openMain()
        else -> desk.close()
    }
    BackHandler { back() }
    var masterOpen by remember { mutableStateOf(false) }
    var branchFrom by remember { mutableStateOf<Int?>(null) }
    var turnAt by remember { mutableStateOf<Int?>(null) }
    var rejoinOpen by remember { mutableStateOf(false) }
    val poolAt = desk.poolStart(ids)
    val poolEnd = desk.poolEnd(ids)
    val allKey = desk.setKey(plan)
    val issues = remember(ids, plan, v) { desk.issues(ids, plan) }
    var sortOpen by remember { mutableStateOf(false) }
    var bridgeFor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var replaceAt by remember { mutableStateOf<Int?>(null) }
    var showOpen by remember { mutableStateOf(false) }
    var requestOpen by remember { mutableStateOf(false) }
    var goalOpen by remember { mutableStateOf(false) }
    var issueFor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // O caminho inteiro da linha escolhida (do começo ao fim do set), para ouvir e comparar vertentes.
    val boardPath = if (board) remember(v, desk.playlistId, desk.saved, desk.branches, desk.masterFixed, app.transitionBars) { desk.pathPlan() } else null
    Column(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).border(BorderStroke(1.dp, Palette.line)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { back() }) { Text("‹", fontSize = 22.sp) }
            if (board) {
                val root = desk.rootId()
                Text("SET", color = Palette.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Text(v.playlists.firstOrNull { it.id == root }?.name.orEmpty(), color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            } else if (branch != null) {
                // Num ramo: a letra e o set de onde sai.
                Text("RAMO ${desk.letterOf(branch)}", color = Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text(v.playlists.firstOrNull { it.id == branch.mainId }?.name.orEmpty(), color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            } else {
                Text("SET", color = Palette.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Text(p.name, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
            }
            if (board) {
                // No quadro, o OUVIR fica embaixo, ao alcance do polegar; em cima só a duração do caminho.
                GoalLength((boardPath?.lengthMs ?: 0L).toInt(), desk.goalMinutes)
            } else {
                GoalLength(SetPlanner.lengthMs(v, ids, plan), desk.goalMinutes)
                PlayButton(on = app.preview.playingKey == allKey, enabled = plan.isNotEmpty(), label = "OUVIR") { desk.playAll(plan) }
            }
            // O que se usa pouco (ordenar) fica no ⋯; numa pool a ordem é escolhida na hora.
            TextButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = "Mais" }) { Text("⋯", fontSize = 20.sp) }
        }
        val layout = if (board) remember(v, desk.branches, desk.poolFrom, desk.rootId()) { desk.canvas() } else null
        if (layout != null) {
            val plans = remember(layout, desk.saved, app.transitionBars) { layout.lanes.associate { it.playlistId to desk.lanePlan(it.playlistId) } }
            // Os avisos de todos os caminhos (a parte mais pesada) são calculados fora da thread da tela e chegam depois.
            val boardIssues by androidx.compose.runtime.produceState(emptySet<Pair<Int, Int>>(), v, desk.branches, desk.masterFixed, desk.saved, app.transitionBars) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { desk.issuePairs() }
            }
            val card = picked?.let { (pid, i) -> layout.lanes.firstOrNull { it.playlistId == pid }?.cards?.getOrNull(i) }
            val liveView = if (!desk.live) null else {
                val now = desk.liveNow?.let { (pid, i) -> layout.lanes.firstOrNull { it.playlistId == pid }?.cards?.getOrNull(i) }
                LiveView(now, now?.let { os.deckbridge.manager.mix.SetCanvas.nextFrom(layout, it, desk.livePlayed).toSet() } ?: emptySet(), desk.livePlayed)
            }
            SetBoard(
                app, layout, plans, boardIssues, card, rehearsing = desk.rehearsal?.let { plan.getOrNull(it) },
                onSelect = { c ->
                    if (desk.live) {
                        if (c != null) desk.liveTo(c)
                    } else {
                        if (c != null) desk.focus(c.playlistId)
                        picked = c?.let { it.playlistId to it.index }
                    }
                },
                onAdd = { lane -> desk.focus(lane.playlistId); picked = null; addTo = lane.letter },
                onMix = { l, tr -> desk.focus(l.playlistId); desk.openMix(tr) },
                onJoin = { lane -> desk.focus(lane.playlistId); picked = null; rejoinOpen = true },
                onMove = { c, pid, i -> if (desk.moveCard(c.playlistId, c.index, pid, i)) picked = null },
                live = liveView,
                modifier = Modifier.weight(1f),
            )
            val rehearsal = desk.rehearsal?.let { at -> plan.getOrNull(at)?.let { at to it } }
            when {
                liveView != null -> LiveBar(app, layout, liveView, onPick = { desk.liveTo(it) }, onStop = { desk.stopLive() }, onRequest = { requestOpen = true })
                rehearsal != null -> RehearsalBar(app, rehearsal.first, plan, rehearsal.second)
                card != null -> BoardActions(
                    app, card,
                    onMove = { d -> picked = card.playlistId to desk.move(card.index, d) },
                    onSplit = { turnAt = card.index + (if (branch != null) 1 else 0); picked = null },
                    onRemove = { desk.removeAt(card.index); picked = null },
                    onReplace = { replaceAt = card.index },
                )
                plan.isNotEmpty() -> ActionsRow(
                    issues = issues.map { it.fromId to it.toId }.distinct().size,
                    redo = plan.count { it.mark == Transition.Mark.REDO },
                    onRehearse = { app.seen("rehearse"); desk.startRehearsal(plan) },
                    rehearseSpot = app.hint("rehearse"),
                    letter = layout.lanes.firstOrNull { it.playlistId == desk.playlistId }?.letter?.takeIf { layout.rows > 1 },
                    listen = boardPath?.takeIf { it.plan.isNotEmpty() }?.let { path ->
                        {
                            val letter = desk.branch?.let { desk.letterOf(it) } ?: 'A'
                            PlayButton(
                                on = app.preview.playingKey == desk.setKey(path.plan, path.tempos), modifier = Modifier.padding(start = 8.dp),
                                label = if (desk.tree().isNotEmpty()) "OUVIR $letter" else "OUVIR",
                            ) { desk.playPath(path) }
                        }
                    },
                )
            }
        }
        if (!board && ids.size >= 2) FlowStrip(
            ids.mapNotNull { v.track(it) }, masters, desk.masterFixed, pool = poolAt?.let { it until poolEnd },
            onMaster = { masterOpen = true },
        ) { desk.mapOpen = true }
        if (!board && ids.size >= 2) ActionsRow(
            issues = issues.map { it.fromId to it.toId }.distinct().size,
            redo = plan.count { it.mark == Transition.Mark.REDO },
            onRehearse = { app.seen("rehearse"); desk.startRehearsal(plan) },
            rehearseSpot = !app.hint("hear") && app.hint("rehearse"),
        )
        if (!board) LazyColumn(Modifier.weight(1f)) {
            if (branch != null) item(key = "volta") { RejoinRow(app, branch.rejoinTrackId) { rejoinOpen = true } }
            ids.forEachIndexed { n, id ->
                // A pool aparece num bloco só, no lugar da primeira faixa dela (sem ordem e sem transições).
                if (poolAt != null && n in poolAt until poolEnd) {
                    if (n == poolAt) poolItems(app, ids, masters, poolAt, poolEnd)
                    return@forEachIndexed
                }
                val t = v.track(id) ?: return@forEachIndexed
                // Num ramo, o ponto de virada e a volta são da linha principal: aparecem, mas não são do ramo.
                val anchor = branch != null && (n == 0 || (id == branch.rejoinTrackId && n == ids.lastIndex))
                item(key = "t$id-$n") {
                    SetTrackRow(
                        n + 1, t, masters.getOrNull(n), playing = app.preview.playingId == id, anchor = anchor,
                        onBranch = if (!anchor) ({ turnAt = n }) else null,
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
                            onIssue = { issueFor = id to tr.toId },
                            // Quem nunca ouviu uma transição: o ▶ da primeira mostra por onde começar.
                            spot = n == 0 && app.hint("hear"),
                        )
                    }
                }
            }
            if (ids.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("Playlist vazia", color = Palette.muted, fontSize = 14.sp) } }
            val next = desk.suggestions(ids)
            if (next.isNotEmpty()) {
                item(key = "next") { Text(if (poolAt != null) "PARA A POOL" else "PRÓXIMA", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp)) }
                val last = v.track(desk.poolEntry(ids) ?: ids.last())!!
                next.forEach { s ->
                    item(key = "s${s.id}") { SuggestionRow(s, SetPlanner.fit(last, s), onPlay = { app.preview.toggle(s) }, playing = app.preview.playingId == s.id) { desk.append(s) } }
                }
            }
        }
        if (!board) desk.rehearsal?.let { at -> plan.getOrNull(at)?.let { tr -> RehearsalBar(app, at, plan, tr) } }
    }
    if (menuOpen) {
        // O que se usa pouco fica aqui: ordenar (não numa pool: a ordem é escolhida na hora), master, andamento e a outra vista.
        val canSort = poolAt == null && ids.size >= 3
        val options = buildList {
            // Antes de sair de casa: a conferência do que dá problema na cabine.
            add("Pronto pro show?" to { picked = null; showOpen = true })
            if (canSort) add("Ordenar para mixar" to { sortOpen = true })
            add("Transição · " + (app.transitionBars?.let { "$it compassos" } ?: "até o drop") to { app.nextTransitionBars() })
            if (board) {
                add("Ao vivo" to { picked = null; desk.startLive() })
                add("Cola do set" to { app.share("Set ${v.playlists.firstOrNull { it.id == desk.rootId() }?.name.orEmpty()}", desk.sheet()) })
                add("Master · " + (desk.masterFixed?.let { "%.0f".format(it) } ?: "automático") to { masterOpen = true })
                add("Andamento do set" to { desk.mapOpen = true })
            }
            // Controle geral das sugestões (Amershi et al. 2019): volta a sugerir as que o DJ escondeu.
            add("Duração alvo · " + (desk.goalMinutes?.let { "$it min" } ?: "nenhuma") to { goalOpen = true })
            if (branch == null) add("Duplicar set (testar sem mexer)" to { picked = null; desk.duplicate() })
            if (desk.hidden.isNotEmpty()) add("Sugestões escondidas (${desk.hidden.size}) · mostrar de novo" to { desk.showAllSuggestions() })
            add((if (board) "Ver em lista" else "Ver em quadro") to { picked = null; app.setListView(board) })
        }
        ChoiceDialog("Set", options.map { it.first }, onPick = { n -> menuOpen = false; options[n].second() }, onClose = { menuOpen = false })
    }
    addTo?.let { letter -> AddToLineDialog(app, letter) { addTo = null } }
    replaceAt?.let { index -> ReplaceDialog(app, index) { replaceAt = null; picked = null } }
    if (showOpen) ShowDialog(app, onRehearse = { if (plan.isNotEmpty()) desk.startRehearsal(plan) }) { showOpen = false }
    if (requestOpen) RequestDialog(app) { requestOpen = false }
    SavePlayedDialog(app)
    if (goalOpen) {
        val goals = listOf(30, 45, 60, 90, 120, 180)
        ChoiceDialog("Duração alvo", goals.map { "${it} min" } + "Sem alvo", onPick = { n -> goalOpen = false; desk.setGoal(goals.getOrNull(n)) }, onClose = { goalOpen = false })
    }
    // O fim do ensaio: o resultado e, se alguma ficou para refazer, o caminho direto para ela (regra pico-fim).
    desk.rehearsalDone?.let { (good, redo) ->
        val first = plan.firstOrNull { it.mark == Transition.Mark.REDO }
        ConfirmDialog(
            "Ensaio terminado", "✓ $good boas" + (if (redo > 0) "  ·  ✗ $redo a refazer" else "  ·  nenhuma a refazer"),
            if (first != null) "REFAZER A PRIMEIRA" else "OK",
            onConfirm = { desk.rehearsalDone = null; if (first != null) desk.openMix(first) },
            onClose = { desk.rehearsalDone = null },
        )
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
    issueFor?.let { (a, b) ->
        // O aviso por extenso só quando se pede (tocar no ⚠); se uma ponte resolve, ela é a ação.
        val here = issues.filter { it.fromId == a && it.toId == b }
        val bridge = here.any { it.bridgeable }
        ChoiceDialog(here.joinToString("\n") { it.text }, listOf(if (bridge) "Pôr uma ponte" else "OK"), onPick = {
            issueFor = null
            if (bridge) bridgeFor = a to b
        }, onClose = { issueFor = null })
    }
    turnAt?.let { n ->
        // Ponto de virada: daqui sai um ramo, ou daqui em diante a linha vira pool (ou volta a ser lista).
        val id = ids.getOrNull(n)
        val entry = if (branch != null) n - 1 else n
        val options = listOf("Criar ramo daqui", if (desk.poolFrom == null) "Pool depois desta" else "Tirar a pool (lista)")
        ChoiceDialog("Ponto de virada · ${id?.let { v.track(it)?.title }.orEmpty()}", options, onPick = { k ->
            turnAt = null
            when {
                k == 0 && id != null -> branchFrom = id
                desk.poolFrom == null -> desk.makePool(entry + 1)
                else -> desk.makePool(null)
            }
        }, onClose = { turnAt = null })
    }
    if (rejoinOpen) {
        val choices = desk.rejoinChoices()
        ChoiceDialog("Junta em", listOf("Não junta (outro final)") + choices.map { v.track(it)?.title.orEmpty() }, onPick = { k ->
            rejoinOpen = false
            desk.setRejoin(if (k == 0) null else choices[k - 1])
        }, onClose = { rejoinOpen = false })
    }
    bridgeFor?.let { (a, b) ->
        val options = remember(a, b, ids) { desk.bridges(a, b) }
        if (options.isEmpty()) {
            ConfirmDialog("Ponte", "Nenhuma faixa da biblioteca combina com as duas.", "OK", onConfirm = { bridgeFor = null }, onClose = { bridgeFor = null })
        } else {
            ChoiceDialog("Ponte entre as duas", options.map { "${it.title.ifEmpty { it.fileName }} · ${keyLabel(it.key)} · %.0f".format(it.bpm) }, onPick = { n ->
                bridgeFor = null
                desk.insertBridge(a, options[n])
            }, onClose = { bridgeFor = null })
        }
    }
}

/** Ensaiar (o botão principal) e o resumo: quantas a refazer e quantas trocas com aviso. */
@Composable
private fun ActionsRow(issues: Int, redo: Int, onRehearse: () -> Unit, rehearseSpot: Boolean = false, letter: Char? = null, listen: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // No quadro com ramos: ensaia a linha escolhida (a letra diz qual).
        Button(onClick = onRehearse, modifier = Modifier.spotlight(rehearseSpot).height(40.dp)) { Text(if (letter != null) "ENSAIAR $letter" else "ENSAIAR", fontSize = 12.sp) }
        listen?.invoke()
        Spacer(Modifier.weight(1f))
        if (redo > 0) Text("✗ $redo", color = Palette.orange, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.padding(end = 10.dp))
        Text(if (issues == 0) "✓" else "⚠ $issues", color = if (issues == 0) Palette.keyInk else Palette.orange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** O ensaio: a transição que está tocando e os botões para dizer se ficou boa. */
@Composable
private fun RehearsalBar(app: StudioHost, at: Int, plan: List<Transition>, tr: Transition) {
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
private fun PlayButton(on: Boolean, enabled: Boolean = true, label: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(38.dp).clip(RbShape).background(if (on) Palette.blueActiveFill else Palette.playBg)
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
private fun FlowStrip(
    tracks: List<TrackItem>, masters: List<Double>, fixed: Double?, pool: IntRange? = null, onMaster: () -> Unit, onMap: () -> Unit,
) {
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
        // Tocar no master escolhe o andamento (automático ou fixo); o resto da faixa abre o mapa.
        Column(Modifier.width(60.dp).fillMaxHeight().clickable(onClick = onMaster).semantics { contentDescription = "Master" }, verticalArrangement = Arrangement.Center) {
            Text("MASTER", color = Palette.blue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(fixed?.let { "%.0f".format(it) } ?: "AUTO", color = Palette.orange, fontSize = 15.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
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
                // Dentro da pool não há troca marcada: a ordem é escolhida na hora.
                if (pool != null && (i in pool || i + 1 in pool)) continue
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
        Text(keyLabel(t.key), color = Palette.keyInk, fontSize = 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SetTrackRow(n: Int, t: TrackItem, master: Double?, playing: Boolean, anchor: Boolean, onBranch: (() -> Unit)?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(if (playing) Palette.selectedRow else if (anchor) Palette.bg else Palette.surface)
            .combinedClickable(onClick = onOpen, onLongClick = onBranch)
            .padding(start = 14.dp, end = if (onBranch != null) 0.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (anchor) "↔" else "$n", color = if (anchor) Palette.orange else Palette.dimOn(playing), fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title.ifEmpty { t.fileName }, color = if (anchor) Palette.mutedBright else Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (anchor) "linha principal" else t.artist, color = Palette.dimOn(playing), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // O BPM em que toca (o master). O da faixa e quanto estica só aparecem quando passa do confortável (10%).
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
            Text(bpmLabel(master ?: t.bpm), color = Palette.orange, fontSize = 13.sp, fontFamily = Mono)
            if (master != null && t.bpm > 0) {
                val st = SetTempo.stretchPct(t.bpm, master)
                if (kotlin.math.abs(st) > SetTempo.COMFORT_PCT) Text(
                    "%.0f ".format(t.bpm) + pctLabel(st), fontSize = 10.sp, fontFamily = Mono,
                    color = if (kotlin.math.abs(st) > SetTempo.MAX_PCT) Palette.danger else Palette.orange,
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

/** O ponto de virada desta faixa (também no toque longo): criar um ramo daqui ou fazer o resto da linha virar pool. */
@Composable
private fun BranchButton(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clickable(onClick = onClick).semantics { contentDescription = "Ponto de virada" },
        contentAlignment = Alignment.Center,
    ) { Text("⋯", color = Palette.mutedBright, fontSize = 18.sp) }
}

/** Um ramo que sai desta faixa: a letra, quantas faixas e onde volta. Tocar abre o SET do ramo. */
@Composable
private fun BranchRow(app: StudioHost, b: os.deckbridge.manager.mix.SetBranch, line: List<Int>, onOpen: () -> Unit) {
    val v = app.model.view ?: return
    val p = v.playlists.firstOrNull { it.id == b.playlistId } ?: return
    val n = v.entries[b.playlistId].orEmpty().size
    // Um ramo de ramo pode voltar numa faixa que não está nesta linha (mais adiante no caminho).
    val back = b.rejoinTrackId?.let { r -> line.indexOf(r).takeIf { it >= 0 }?.let { "volta no ${it + 1}" } ?: "volta em ${v.track(r)?.title.orEmpty()}" } ?: "outro final"
    val size = if (app.sets.isPool(b.playlistId)) "pool de $n" else "$n faixas"
    Row(
        Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onOpen).padding(start = 40.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BranchIcon(Palette.orange)
        Text(" RAMO ${app.sets.letterOf(b)}", color = Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("  $size · $back", color = Palette.mutedBright, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("›", color = Palette.blue, fontSize = 18.sp)
    }
}

/** Criar um ramo daqui: quantas faixas, o rumo e se volta para a linha principal. */
@Composable
private fun BranchDialog(app: StudioHost, from: Int, onClose: () -> Unit) {
    var kind by remember { mutableStateOf(SetDesk.BranchKind.LIST) }
    var count by remember { mutableStateOf(3) }
    var shape by remember { mutableStateOf(os.deckbridge.manager.mix.SetShape.RISING) }
    var rejoin by remember { mutableStateOf(true) }
    // Abre com o padrão e um CRIAR; as escolhas ficam em "mais opções". Onde junta fica sempre à vista: é o que faz das
    // vertentes de um mesmo ponto um trecho do set (sai daqui, junta ali).
    var more by remember { mutableStateOf(false) }
    val v = app.model.view
    val title = v?.track(from)?.title.orEmpty()
    val joins = remember(from) { app.sets.joinChoices(from) }
    var join by remember { mutableStateOf(app.sets.siblingJoin(from)) }
    var joinOpen by remember { mutableStateOf(false) }
    val joinText = when {
        !rejoin -> "não junta (outro final)"
        join != null -> "junta em " + shortTitle(v?.track(join!!)?.title.orEmpty())
        else -> "junta onde combinar"
    }
    val counts = if (kind == SetDesk.BranchKind.POOL) listOf(4, 6, 8, 12) else listOf(2, 3, 4, 6)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ramo depois de\n$title", maxLines = 2) },
        text = {
            if (!more) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$count faixas · mais energia", color = Palette.mutedBright, fontSize = 14.sp)
                if (kind != SetDesk.BranchKind.MANUAL || joins.isNotEmpty()) JoinButton(joinText) { joinOpen = true }
                TextButton(onClick = { more = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("MAIS OPÇÕES", fontSize = 12.sp) }
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("TIPO", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SetDesk.BranchKind.LIST to "Lista", SetDesk.BranchKind.POOL to "Pool", SetDesk.BranchKind.MANUAL to "Eu escolho").forEach { (k, label) ->
                        FilterChip(kind == k, {
                            kind = k
                            count = if (k == SetDesk.BranchKind.POOL) 6 else 3
                        }, { Text(label, maxLines = 1) })
                    }
                }
                // "Eu escolho": nasce vazio; as faixas e a volta se põem no ramo.
                if (kind != SetDesk.BranchKind.MANUAL) {
                    Text("FAIXAS", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        counts.forEach { c -> FilterChip(count == c, { count = c }, { Text("$c") }) }
                    }
                }
                if (kind == SetDesk.BranchKind.LIST) {
                    Text("RUMO", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            os.deckbridge.manager.mix.SetShape.RISING to "Mais energia",
                            os.deckbridge.manager.mix.SetShape.FLOW to "Manter",
                            os.deckbridge.manager.mix.SetShape.FALLING to "Mais calmo",
                        ).forEach { (sh, label) -> FilterChip(shape == sh, { shape = sh }, { Text(label, maxLines = 1) }) }
                    }
                }
                Text("JUNTA", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                JoinButton(joinText) { joinOpen = true }
            }
        },
        confirmButton = { TextButton(onClick = { onClose(); app.sets.createBranch(from, count, shape, rejoin, kind, joinAt = join) }) { Text("CRIAR") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
        containerColor = Palette.surfaceHigh,
    )
    if (joinOpen) {
        val names = joins.map { shortTitle(v?.track(it)?.title.orEmpty()) }
        ChoiceDialog("Junta em", listOf("Onde combinar") + names + "Não junta (outro final)", onPick = { k ->
            joinOpen = false
            when (k) {
                0 -> { rejoin = true; join = null }
                names.size + 1 -> rejoin = false
                else -> { rejoin = true; join = joins[k - 1] }
            }
        }, onClose = { joinOpen = false })
    }
}

/** Onde o ramo junta de volta, numa linha que se toca para mudar. */
@Composable
private fun JoinButton(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RbShape).background(Palette.surface).border(BorderStroke(1.dp, Palette.line), RbShape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("↩ ", color = Palette.orange, fontSize = 14.sp)
        Text(text, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("▾", color = Palette.blue, fontSize = 14.sp)
    }
}

private fun fitText(f: Fit): String = if (f.bpmDiffPct == 0.0) "=" else "%+.1f%%".format(f.bpmDiffPct)

/**
 * Uma transição numa linha: o tom de uma para a outra, quanto a que entra estica até o master e quantos compassos dura;
 * o estilo só quando foi mudado. O fio verde ou laranja já diz se a troca combina; o ⚠ abre o aviso. Tocar na linha abre o mix.
 */
@Composable
private fun TransitionRow(
    a: TrackItem, b: TrackItem, tr: Transition, fit: Fit, stretch: Double, playing: Boolean, issues: List<os.deckbridge.manager.mix.SetIssue>, rehearsing: Boolean,
    onPlay: () -> Unit, onEdit: () -> Unit, onIssue: () -> Unit, spot: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(if (rehearsing) Palette.blueActiveFill else Palette.bg).clickable(onClick = onEdit)
            .padding(start = 22.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // O fio que liga uma faixa à outra.
        Box(Modifier.width(2.dp).fillMaxHeight().background(if (fit.good && issues.isEmpty()) Palette.keyInk.copy(alpha = 0.6f) else Palette.orange.copy(alpha = 0.6f)))
        Row(Modifier.padding(start = 14.dp).weight(1f), verticalAlignment = Alignment.CenterVertically) {
            when (tr.mark) {
                Transition.Mark.GOOD -> Text("✓ ", color = Palette.keyInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Transition.Mark.REDO -> Text("✗ ", color = Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                null -> {}
            }
            Text((if (fit.keyOk) "✓ " else "! ") + "${keyLabel(a.key)}→${keyLabel(b.key)}", color = if (fit.keyOk) Palette.keyInk else Palette.orange, fontSize = 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
            Text(
                "  " + pctLabel(stretch), fontSize = 12.sp, fontFamily = Mono,
                color = if (kotlin.math.abs(stretch) <= SetTempo.COMFORT_PCT) Palette.mutedBright else if (kotlin.math.abs(stretch) <= SetTempo.MAX_PCT) Palette.orange else Palette.danger,
            )
            // Quantos compassos a troca dura (o automático varia com a música); o estilo só quando foi mudado.
            Text(
                "  ${tr.bars} comp." + (if (tr.style != MixStyle.BASS_SWAP) " · ${tr.style.title}" else "") +
                    (if (tr.pace != Transition.Pace.NORMAL) " · ${tr.pace.title.lowercase()}" else "") +
                    (if (tr.style == MixStyle.BASS_SWAP && tr.swapBars > 0) " · grave aos poucos" else ""), color = Palette.muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (issues.isNotEmpty()) Box(
            Modifier.size(40.dp).clickable(onClick = onIssue).semantics { contentDescription = "Aviso" },
            contentAlignment = Alignment.Center,
        ) { Text("⚠", color = Palette.orange, fontSize = 16.sp) }
        PlayButton(on = playing, modifier = Modifier.spotlight(spot), onClick = onPlay)
    }
}

@Composable
private fun SuggestionRow(t: TrackItem, fit: Fit, playing: Boolean, onPlay: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((if (fit.good) "✓ " else "! ") + "${keyLabel(t.key)} · ${fitText(fit)}", color = if (fit.good) Palette.keyInk else Palette.orange, fontSize = 12.sp, fontFamily = Mono)
        }
        PlayButton(on = playing, onClick = onPlay)
        OutlinedButton(onClick = onAdd, modifier = Modifier.padding(start = 6.dp).height(38.dp)) { Text("＋", fontSize = 16.sp) }
    }
}


/** Onde o ramo aberto volta (ou "outro final"); tocar muda. */
@Composable
private fun RejoinRow(app: StudioHost, rejoin: Int?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(Palette.surface).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("VOLTA", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Text(
            "  " + (rejoin?.let { app.model.view?.track(it)?.title } ?: "outro final"), color = Palette.text, fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("▾", color = Palette.blue, fontSize = 14.sp)
    }
}

/**
 * O bloco da pool: o cabeçalho (quantas, de onde entra e para onde sai) e as faixas. Com uma marcada como AGORA, as outras
 * vêm na ordem da melhor troca a partir dela; as que já tocaram vão para o fim, apagadas.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.poolItems(app: StudioHost, ids: List<Int>, masters: List<Double>, from: Int, until: Int) {
    val desk = app.sets
    val v = app.model.view ?: return
    val pool = ids.subList(from, until)
    val entry = ids.getOrNull(from - 1)?.let { v.track(it) }
    val now = desk.poolNow?.takeIf { it in pool }?.let { v.track(it) }
    val ref = now ?: entry
    val played = desk.poolPlayed
    val ordered: List<Int> = if (ref != null) {
        listOfNotNull(now?.id) + SetPool.next(v, ref, pool, played).map { it.first.id } + pool.filter { it in played }
    } else pool
    val links = SetPool.linkCount(v, pool)
    val exit = desk.branch?.rejoinTrackId?.let { v.track(it) }
    val exits = exit?.let { x -> pool.count { id -> v.track(id)?.let { SetPool.links(it, x) } == true } }
    item(key = "pool") { PoolHeader(pool.size, exit?.title, exits, hasLive = now != null || played.isNotEmpty()) { desk.resetPool() } }
    ordered.forEach { id ->
        val t = v.track(id) ?: return@forEach
        val n = ids.indexOf(id)
        item(key = "p$id") {
            PoolRow(
                t, masters.getOrNull(n), fit = ref?.takeIf { it.id != id }?.let { SetPlanner.fit(it, t) }, links = links[id] ?: 0,
                now = id == now?.id, played = id in played, previewing = app.preview.playingId == id,
                onPreview = { app.preview.toggle(t) }, onNow = { desk.playingNow(id) },
            )
        }
        desk.branchesHere().filter { it.fromTrackId == id }.forEach { b ->
            item(key = "b${b.playlistId}") { BranchRow(app, b, ids) { desk.open(b.playlistId) } }
        }
    }
}

@Composable
private fun PoolHeader(count: Int, exit: String?, exits: Int?, hasLive: Boolean, onReset: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Palette.surfaceHigh).border(BorderStroke(1.dp, Palette.line)).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("POOL", color = Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Text("  $count faixas" + (if (exit != null && exits != null) " · $exits levam a $exit" else ""), color = Palette.mutedBright, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (hasLive) TextButton(onClick = onReset) { Text("ZERAR", fontSize = 12.sp) }
    }
}

/** Uma faixa da pool: com quantas outras dela combina, a troca a partir da que está tocando e o botão AGORA. */
@Composable
private fun PoolRow(
    t: TrackItem, master: Double?, fit: Fit?, links: Int, now: Boolean, played: Boolean, previewing: Boolean, onPreview: () -> Unit, onNow: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(if (now) Palette.blueActiveFill else if (previewing) Palette.selectedRow else Palette.surface)
            .clickable(onClick = onPreview).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                t.title.ifEmpty { t.fileName }, color = if (played) Palette.dimOn(now || previewing) else Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Embaixo: tocando / tocada, ou um ponto verde/laranja (a troca a partir da que está tocando) e quanto estica.
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    now -> Text("tocando", color = Palette.dimOn(true), fontSize = 12.sp)
                    played -> Text("tocada", color = Palette.dimOn(previewing), fontSize = 12.sp)
                    else -> {
                        if (fit != null) {
                            FitMark(fit.good)
                            Text(" ${fitText(fit)}", color = Palette.mutedBright, fontSize = 12.sp, fontFamily = Mono)
                        }
                        if (links == 0) Text("  isolada", color = Palette.orange, fontSize = 12.sp)
                    }
                }
            }
        }
        Text(bpmLabel(master ?: t.bpm), color = Palette.orange, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.padding(end = 8.dp))
        KeyBadge(t)
        Box(
            Modifier.padding(start = 6.dp).height(36.dp).clip(RbShape).background(if (now) Palette.blue else Palette.bg)
                .border(BorderStroke(1.dp, Palette.blueActiveLine), RbShape).clickable(onClick = onNow).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text("AGORA", color = if (now) Palette.bg else Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    }
}


/**
 * A duração do set e, com alvo, o quanto falta ou passa: "≈1:48 / 2:00". Dentro de 5% do alvo ganha ✓ (não só a cor);
 * fora, diz quanto falta ou sobra em minutos.
 */
@Composable
private fun GoalLength(ms: Int, goalMin: Int?) {
    val text = if (goalMin == null) "≈" + mmss(ms) else {
        val goal = goalMin * 60_000
        val diffMin = (ms - goal) / 60_000
        val near = kotlin.math.abs(ms - goal) <= goal * 0.05
        "≈" + mmss(ms) + " / " + mmss(goal) + when {
            near -> " ✓"
            diffMin < 0 -> " · faltam ${-diffMin}"
            else -> " · +$diffMin"
        }
    }
    val near = goalMin != null && kotlin.math.abs(ms - goalMin * 60_000) <= goalMin * 60_000 * 0.05
    Text(text, color = if (near) Palette.keyInk else Palette.orange, fontSize = 13.sp, fontFamily = Mono, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp))
}
