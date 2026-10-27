package os.deckbridge.manager.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import os.deckbridge.manager.importer.AudioIo
import os.deckbridge.manager.mix.MixRenderer
import os.deckbridge.manager.mix.SegmentReader
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.MixPreview
import os.deckbridge.manager.mix.SetBranch
import os.deckbridge.manager.mix.SetBranches
import os.deckbridge.manager.mix.SetRoute
import os.deckbridge.manager.mix.SetRoutes
import os.deckbridge.manager.mix.RouteTempo
import os.deckbridge.manager.mix.SetPool
import os.deckbridge.manager.mix.SetBuilder
import os.deckbridge.manager.mix.SetShape
import os.deckbridge.manager.mix.SetTempo
import os.deckbridge.manager.mix.TrackGrid
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackItem

/**
 * Planejar um set a partir de uma playlist: as transições entre faixas vizinhas (onde sai, onde entra, quantos compassos
 * juntos e como), a prévia de cada uma montada no aparelho, e as "só transições" em sequência para ouvir o set de ponta a
 * ponta. O plano fica num arquivo ao lado da biblioteca (o rekordbox não tem onde guardar isso); o pendrive não muda.
 */
class SetDesk(private val app: StudioHost) {
    var playlistId by mutableStateOf<Int?>(null)
        private set

    /** Transições que o DJ ajustou (as outras são automáticas, calculadas da ordem atual). */
    var saved by mutableStateOf<List<Transition>>(emptyList())
        private set

    private val grids = HashMap<Int, TrackGrid?>()
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "deckbridge-grades").apply { isDaemon = true } }

    private fun file(id: Int) = File(app.setsDir, "$id.properties")

    private val branchesFile get() = File(app.setsDir, "branches.properties")
    private fun masterFile(id: Int) = File(app.setsDir, "$id.master")

    /** Os ramos de todos os sets (as playlists de ramo e de onde saem). */
    var branches by mutableStateOf<List<SetBranch>>(emptyList())
        private set

    /** O master do set aberto: null é automático (segue as faixas), um número é fixo. */
    var masterFixed by mutableStateOf<Double?>(null)
        private set

    fun open(id: Int) {
        synchronized(grids) { grids.clear() }
        prefetch(id)
        branches = SetBranches.load(branchesFile)
        saved = SetPlanner.load(file(id))
        poolFrom = poolFromOf(id)
        poolNow = null
        poolPlayed = emptySet()
        playlistId = id
        masterFixed = SetTempo.loadMode(masterFile(rootId() ?: id))
    }

    /**
     * Lê as grades das faixas do set (a linha e os ramos) numa thread de fundo, para a tela não esperar o disco quando
     * calcula as trocas. O que a tela pedir antes de chegar é lido na hora, como antes.
     */
    private fun prefetch(id: Int) {
        val v = app.model.view ?: return
        val all = SetBranches.load(branchesFile)
        val root = SetBranches.rootOf(all, id)
        val tracks = (listOf(root) + SetBranches.tree(all, root).map { it.playlistId }).flatMap { v.entries[it].orEmpty() }.distinct().mapNotNull { v.track(it) }
        loader.execute { for (t in tracks) if (playlistId != null) grid(t) }
    }

    /** O ramo aberto, se a playlist aberta é um ramo de outro set. */
    val branch: SetBranch? get() {
        val v = app.model.view ?: return null
        return SetBranches.alive(v, branches).firstOrNull { it.playlistId == playlistId }
    }

    /** O set principal: o próprio, ou aquele de onde o ramo (ou o ramo do ramo) sai. */
    fun rootId(): Int? = playlistId?.let { SetBranches.rootOf(branches, it) }

    /** Os ramos que saem da linha aberta (do set principal ou de um ramo: um ramo pode ter ramos). */
    fun branchesHere(): List<SetBranch> {
        val v = app.model.view ?: return emptyList()
        return SetBranches.alive(v, branches).filter { it.mainId == playlistId }
    }

    /** Todos os ramos do set principal aberto (os que saem dele e os que saem dos ramos dele), na ordem de criação. */
    fun tree(): List<SetBranch> {
        val v = app.model.view ?: return emptyList()
        val root = rootId() ?: return emptyList()
        return SetBranches.tree(SetBranches.alive(v, branches), root)
    }

    /** A letra do ramo (B, C…), pela ordem em que foram criados no set. */
    fun letterOf(b: SetBranch): Char = SetBranches.letter(tree().indexOfFirst { it.playlistId == b.playlistId }.coerceAtLeast(0))

    fun openMain() {
        val main = branch?.mainId ?: return
        app.preview.stop()
        rehearsal = null
        open(main)
    }

    fun close() {
        app.preview.stop()
        rehearsal = null
        mapOpen = false
        playlistId = null
    }

    /** A linha que se toca: a playlist, ou — num ramo — o ponto de virada, as faixas do ramo e onde volta. */
    fun ids(): List<Int> {
        val v = app.model.view ?: return emptyList()
        branch?.let { return SetBranches.line(v, it) }
        return entries()
    }

    /** Só as faixas da playlist aberta (o que se edita: num ramo, sem o ponto de virada e a volta). */
    fun entries(): List<Int> {
        val v = app.model.view ?: return emptyList()
        return v.entries[playlistId ?: return emptyList()].orEmpty().filter { v.track(it) != null }
    }

    /**
     * O master em cada posição de [ids]. Num ramo, é o do caminho inteiro (desde a abertura do set, passando pelos ramos
     * de onde ele sai): começa no master que a linha tem no ponto de virada.
     */
    fun masters(ids: List<Int> = ids()): List<Double> {
        val v = app.model.view ?: return emptyList()
        val natives = ids.map { v.track(it)?.bpm ?: 0.0 }
        val b = branch ?: return SetTempo.plan(natives, masterFixed)
        val r = route(b.playlistId) ?: return SetTempo.plan(natives, masterFixed)
        val all = SetTempo.plan(r.ids.map { v.track(it)?.bpm ?: 0.0 }, masterFixed)
        val start = all.getOrNull(r.split ?: 0) ?: natives.firstOrNull() ?: 120.0
        return listOf(start) + SetTempo.plan(natives.drop(1), masterFixed, start)
    }

    /** Os caminhos da árvore do set aberto (sem o andamento), para achar de onde um ramo vem. */
    private fun rawRoutes(): List<SetRoute> {
        val v = app.model.view ?: return emptyList()
        val root = rootId() ?: return emptyList()
        return SetRoutes.routes(v, v.entries[root].orEmpty().filter { v.track(it) != null }, tree(), ::poolFromOf, mainId = root)
    }

    private fun route(playlist: Int): SetRoute? = rawRoutes().firstOrNull { it.branch?.playlistId == playlist }

    // ---- pool: um trecho sem ordem, escolhido na hora -----------------------------------------------------------

    private fun poolFile(id: Int) = File(app.setsDir, "$id.pool")

    private fun poolFromOf(id: Int): Int? = runCatching { SetPool.load(poolFile(id)) }.getOrNull()

    /** A playlist [id] tem pool (toda ou de uma faixa em diante). */
    fun isPool(id: Int): Boolean = poolFromOf(id) != null

    /** Da faixa [poolFrom] da playlist aberta em diante é pool (null: é toda lista). */
    var poolFrom by mutableStateOf<Int?>(null)
        private set

    /** A faixa da pool que está tocando agora (para mostrar o que combina com ela) e as que já tocaram. Só na tela. */
    var poolNow by mutableStateOf<Int?>(null)
        private set
    var poolPlayed by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** De que posição de [ids] (a linha aberta) começa a pool, ou null. Num ramo, [ids] tem o ponto de virada antes. */
    fun poolStart(ids: List<Int> = ids()): Int? {
        val k = poolFrom ?: return null
        val at = if (branch != null) k + 1 else k
        return at.takeIf { it < poolEnd(ids) }
    }

    /** Até onde vai a pool em [ids] (exclusivo): a faixa da volta de um ramo não é da pool. */
    fun poolEnd(ids: List<Int> = ids()): Int = if (branch?.rejoinTrackId != null) ids.size - 1 else ids.size

    /** A parte com ordem de [ids]: tudo antes da pool (é onde há transições programadas). */
    fun fixedPart(ids: List<Int> = ids()): List<Int> = poolStart(ids)?.let { ids.take(it) } ?: ids

    /** Faz da faixa [entryIndex] da playlist em diante uma pool (null: volta a ser lista). */
    fun makePool(entryIndex: Int?) {
        val id = playlistId ?: return
        poolFrom = entryIndex
        poolNow = null
        poolPlayed = emptySet()
        runCatching { SetPool.save(poolFile(id), entryIndex) }
        app.did(if (entryIndex == null) "Lista" else "Pool a partir da ${entryIndex + 1}ª")
    }

    /** Marca [id] como a que está tocando: a de antes vai para as tocadas. Tocar de novo na mesma desmarca. */
    fun playingNow(id: Int) {
        val now = poolNow
        if (now == id) { poolNow = null; return }
        if (now != null) poolPlayed = poolPlayed + now
        poolPlayed = poolPlayed - id
        poolNow = id
    }

    fun resetPool() {
        poolNow = null
        poolPlayed = emptySet()
    }

    /** De onde se entra na pool: a última faixa antes dela (num ramo que é todo pool, o ponto de virada). */
    fun poolEntry(ids: List<Int> = ids()): Int? = poolStart(ids)?.let { ids.getOrNull(it - 1) }

    /** As faixas da pool na linha aberta. */
    fun poolIds(ids: List<Int> = ids()): List<Int> = poolStart(ids)?.let { ids.subList(it, poolEnd(ids)) } ?: emptyList()

    /** O master da troca [tr] na linha aberta (as duas faixas tocam nele). */
    fun tempoFor(tr: Transition, ids: List<Int> = ids()): Double? = masters(ids).getOrNull(ids.indexOf(tr.fromId))

    fun setMaster(fixed: Double?) {
        val root = rootId() ?: return
        masterFixed = fixed
        runCatching { SetTempo.saveMode(masterFile(root), fixed) }
        app.did(if (fixed == null) "Master automático" else "Master %.1f".format(fixed))
    }

    /** Um caminho do mapa, o andamento dele e quantas trocas têm aviso. */
    class RouteView(val route: SetRoute, val tempo: RouteTempo, val issues: Int, val issuePairs: Set<Pair<Int, Int>> = emptySet())

    /** O mapa do set aberto (a linha principal e os ramos, com o andamento) está na tela. */
    var mapOpen by mutableStateOf(false)

    /**
     * Os caminhos do set principal — a linha e um por ramo (até o ponto de virada, o ramo e o resto da linha a partir da
     * volta) — com o andamento de cada um no master do set. As transições guardadas valem: as da linha e as de cada ramo.
     */
    fun routes(): List<RouteView> {
        val v = app.model.view ?: return emptyList()
        val root = rootId() ?: return emptyList()
        val tree = tree()
        // As transições guardadas de cada linha do set (a principal e cada ramo) valem em qualquer caminho que passe nela.
        val keep = (listOf(root) + tree.map { it.playlistId }).flatMap { runCatching { SetPlanner.load(file(it)) }.getOrDefault(emptyList()) }
        return rawRoutes().map { r ->
            val masters = SetTempo.plan(r.ids.map { v.track(it)?.bpm ?: 0.0 }, masterFixed)
            val plan = SetPlanner.plan(v, r.ids, ::grid, keep = keep, bars = app.transitionBars)
            // Dentro de uma pool não há troca programada (a ordem é escolhida na hora): não conta como aviso.
            val issues = os.deckbridge.manager.mix.SetDoctor.check(v, r.ids, plan, ::grid, masters)
                .filter { i -> r.ids.indices.none { n -> r.ids[n] == i.fromId && n + 1 < r.ids.size && r.ids[n + 1] == i.toId && (r.pool[n] || r.pool[n + 1]) } }
                .map { it.fromId to it.toId }.toSet()
            RouteView(r, SetRoutes.tempo(v, r.ids, plan, masters), issues.size, issues)
        }
    }

    /**
     * Um caminho inteiro do set, do começo ao fim: as trocas programadas (sem as de dentro de uma pool), o master de cada uma
     * e a duração. É o que se ouve para comparar vertentes ("abertura → B → fechamento" contra "→ C →").
     */
    class PathPlan(val route: SetRoute, val plan: List<Transition>, val tempos: List<Double?>, val lengthMs: Long)

    /** O caminho que passa pela linha [pid] (a principal: ela mesma; um ramo: até ele, ele e o resto depois da volta). */
    fun pathPlan(pid: Int? = playlistId): PathPlan? {
        val v = app.model.view ?: return null
        val root = rootId() ?: return null
        val r = rawRoutes().firstOrNull { (it.branch?.playlistId ?: root) == pid } ?: return null
        val keep = (listOf(root) + tree().map { it.playlistId }).flatMap { runCatching { SetPlanner.load(file(it)) }.getOrDefault(emptyList()) }
        val masters = SetTempo.plan(r.ids.map { v.track(it)?.bpm ?: 0.0 }, masterFixed)
        val all = SetPlanner.plan(v, r.ids, ::grid, keep = keep, bars = app.transitionBars)
        val steps = all.indices.filter { i -> !(r.pool.getOrElse(i) { false } || r.pool.getOrElse(i + 1) { false }) }
        return PathPlan(r, steps.map { all[it] }, steps.map { masters.getOrNull(it) }, SetRoutes.tempo(v, r.ids, all, masters).lengthMs)
    }

    /** Ouve o caminho [path] inteiro: as trocas uma depois da outra, cada uma no master daquele ponto do caminho. */
    fun playPath(path: PathPlan) = playAll(path.plan, path.tempos)

    /** Abre a linha de um caminho do mapa: a principal ou a do ramo. */
    fun openRoute(r: SetRoute) {
        mapOpen = false
        val target = r.branch?.playlistId ?: rootId() ?: return
        if (target == playlistId) return
        app.preview.stop()
        rehearsal = null
        open(target)
    }

    // ---- o quadro: o set inteiro numa tela, cada linha numa fileira ------------------------------------------------

    /** O quadro do set principal aberto: a linha e os ramos (de ramos também), com onde cada um sai e volta. */
    fun canvas(): os.deckbridge.manager.mix.SetCanvasLayout? {
        val v = app.model.view ?: return null
        val root = rootId() ?: return null
        return os.deckbridge.manager.mix.SetCanvas.layout(v, root, tree(), ::poolFromOf)
    }

    /** As transições da linha [pid] do quadro (a principal ou um ramo), com as que foram ajustadas nela. */
    fun lanePlan(pid: Int): List<Transition> {
        val v = app.model.view ?: return emptyList()
        val b = SetBranches.alive(v, branches).firstOrNull { it.playlistId == pid }
        val line = if (b == null) v.entries[pid].orEmpty().filter { v.track(it) != null } else SetBranches.line(v, b)
        // Como em [fixedPart]: só a parte com ordem, antes da pool (num ramo, a linha começa no ponto de virada).
        val end = if (b?.rejoinTrackId != null) line.size - 1 else line.size
        val k = poolFromOf(pid)?.let { if (b != null) it + 1 else it }?.takeIf { it < end }
        val keep = if (pid == playlistId) saved else runCatching { SetPlanner.load(file(pid)) }.getOrDefault(emptyList())
        return SetPlanner.plan(v, if (k != null) line.take(k) else line, ::grid, keep = keep, bars = app.transitionBars)
    }

    /** As trocas com aviso em algum caminho do set (tom ou andamento longe demais). */
    fun issuePairs(): Set<Pair<Int, Int>> = routes().flatMap { it.issuePairs }.toSet()

    /** Passa a mexer na linha [pid] do quadro (o que se põe, tira, ensaia e ajusta vai para ela). */
    fun focus(pid: Int) {
        if (pid == playlistId) return
        rehearsal = null
        branches = SetBranches.load(branchesFile)
        saved = SetPlanner.load(file(pid))
        poolFrom = poolFromOf(pid)
        poolNow = null
        poolPlayed = emptySet()
        playlistId = pid
    }

    /** As faixas que saem da linha aberta a partir da posição [index] (ramos que somem se ela sair). */
    fun branchesAt(index: Int): List<SetBranch> {
        val id = entries().getOrNull(index) ?: return emptyList()
        return branchesHere().filter { it.fromTrackId == id }
    }

    /** Tira a faixa da posição [index] da linha aberta. Desfazível. */
    fun removeAt(index: Int) {
        val pid = playlistId ?: return
        val ids = entries()
        val id = ids.getOrNull(index) ?: return
        val rest = ids.toMutableList().apply { removeAt(index) }
        val title = app.model.view?.track(id)?.title.orEmpty()
        // A mesma faixa duas vezes na linha: tira as duas e põe de volta a que fica, no lugar.
        val edits = listOf(Edit.RemoveFromPlaylist(pid, listOf(id)) to "Tirar \"$title\" do set") +
            if (id in rest) listOf(Edit.AddToPlaylist(pid, listOf(id)) to "Manter \"$title\"", Edit.ReorderPlaylist(pid, rest) to "Ordem do set") else emptyList()
        if (app.model.submitAll(edits) == edits.size) app.did("− $title")
    }

    /**
     * Arrastar no quadro: a faixa da posição [fromIndex] da linha [fromPid] vai para a posição [toIndex] da linha [toPid]
     * (contada sem ela). Na mesma linha só muda a ordem; para outra, sai de uma e entra na outra (um desfazer volta tudo).
     * Não move uma faixa de onde sai um ramo para outra linha, nem para uma linha que já a tem.
     */
    fun moveCard(fromPid: Int, fromIndex: Int, toPid: Int, toIndex: Int): Boolean {
        val v = app.model.view ?: return false
        fun line(pid: Int) = v.entries[pid].orEmpty().filter { v.track(it) != null }
        val src = line(fromPid)
        val id = src.getOrNull(fromIndex) ?: return false
        val title = v.track(id)?.title.orEmpty()
        val rest = src.toMutableList().apply { removeAt(fromIndex) }
        if (fromPid == toPid) {
            val order = rest.toMutableList().apply { add(toIndex.coerceIn(0, size), id) }
            if (order == src) return false
            if (!app.model.submit(Edit.ReorderPlaylist(fromPid, order), "Mover \"$title\" no set")) return false
            app.did("Movida: $title")
            return true
        }
        val dst = line(toPid)
        if (id in dst) {
            app.notice = os.deckbridge.manager.Banner("\"$title\" já está nessa linha", true)
            return false
        }
        if (SetBranches.alive(v, branches).any { it.mainId == fromPid && it.fromTrackId == id }) {
            app.notice = os.deckbridge.manager.Banner("Um ramo sai de \"$title\": ela fica na linha dela", true)
            return false
        }
        val order = dst.toMutableList().apply { add(toIndex.coerceIn(0, size), id) }
        val edits = listOf(Edit.RemoveFromPlaylist(fromPid, listOf(id)) to "Tirar \"$title\" da linha") +
            (if (id in rest) listOf(Edit.AddToPlaylist(fromPid, listOf(id)) to "Manter \"$title\"", Edit.ReorderPlaylist(fromPid, rest) to "Ordem da linha") else emptyList()) +
            listOf(Edit.AddToPlaylist(toPid, listOf(id)) to "Pôr \"$title\" na outra linha", Edit.ReorderPlaylist(toPid, order) to "Lugar de \"$title\"")
        if (app.model.submitAll(edits) != edits.size) return false
        focus(toPid)
        app.did("Movida para a outra linha: $title")
        return true
    }

    /** Anda com a faixa da posição [index] uma casa para trás ([delta] -1) ou para frente (+1). Devolve a nova posição. */
    fun move(index: Int, delta: Int): Int {
        val pid = playlistId ?: return index
        val ids = entries()
        val to = index + delta
        if (index !in ids.indices || to !in ids.indices) return index
        val order = ids.toMutableList().apply { add(to, removeAt(index)) }
        return if (app.model.submit(Edit.ReorderPlaylist(pid, order), "Mover no set")) to else index
    }

    /**
     * O que pôr no fim da linha aberta: com [query], as faixas da biblioteca que batem com a busca; sem, as que combinam com
     * a última (num ramo que volta, também). Nada que já está no set.
     */
    fun addChoices(query: String): List<TrackItem> {
        val v = app.model.view ?: return emptyList()
        val ids = ids()
        val busy = ids.toSet() + tree().flatMap { v.entries[it.playlistId].orEmpty() } + (rootId()?.let { v.entries[it].orEmpty() } ?: emptyList())
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isNotEmpty()) return v.tracks.filter { it.id !in busy && words.all { w -> "${it.title} ${it.artist}".lowercase().contains(w) } }.take(30)
        if (poolStart(ids) != null) return suggestions(ids)
        val last = (entries().lastOrNull() ?: branch?.fromTrackId)?.let { v.track(it) }
            ?: return v.tracks.filter { it.id !in busy }.sortedByDescending { it.rating }.take(12)
        return SetPlanner.suggestNext(v, last, busy, limit = 6)
    }

    /** Como o ramo é montado: uma lista automática, uma pool automática ou vazio (o DJ escolhe as faixas). */
    enum class BranchKind { LIST, POOL, MANUAL }

    /**
     * Cria um ramo a partir de [fromTrackId] da linha aberta (a principal ou um ramo: ramo de ramo vale). [kind] LIST:
     * [count] faixas no rumo [shape]; POOL: [count] faixas que combinam entre si, sem ordem; MANUAL: vazio. Se [rejoin],
     * volta mais adiante no caminho de onde sai. Vira uma playlist "Set - ramo B" (vai para o pendrive). Abre o SET do
     * ramo e devolve o id da playlist, ou null.
     */
    fun createBranch(fromTrackId: Int, count: Int, shape: SetShape, rejoin: Boolean, kind: BranchKind = BranchKind.LIST, joinAt: Int? = null): Int? {
        val v = app.model.view ?: return null
        val parent = playlistId ?: return null
        val root = rootId() ?: return null
        // A linha onde pode voltar: o caminho inteiro de onde o ramo sai (num ramo, segue depois da volta dele).
        val line = joinLine()
        val tree = tree()
        val taken = tree.flatMap { v.entries[it.playlistId].orEmpty() }.toSet() + v.entries[root].orEmpty()
        // Sem escolha, junta onde as outras vertentes que saem da mesma faixa já juntam.
        val join = if (!rejoin) null else joinAt ?: siblingJoin(fromTrackId)
        val (ids, back) = when (kind) {
            BranchKind.LIST -> SetBuilder.branch(v, line, fromTrackId, count, shape, taken = taken, rejoin = rejoin, joinAt = join)
            BranchKind.POOL -> SetPool.branch(v, line, fromTrackId, count, taken = taken, rejoin = rejoin, joinAt = join)
            BranchKind.MANUAL -> emptyList<Int>() to join
        }
        if (ids.isEmpty() && kind != BranchKind.MANUAL) {
            app.notice = os.deckbridge.manager.Banner("Nenhuma faixa fora do set combina daqui", true)
            return null
        }
        val letter = SetBranches.letter(tree.size)
        val name = "${v.playlists.first { it.id == root }.name} - ramo $letter"
        val id = app.model.nextPlaylistId()
        val edits = listOf(Edit.CreatePlaylist(id, 0, name, false) to "Ramo \"$name\"") +
            (if (ids.isNotEmpty()) listOf(Edit.AddToPlaylist(id, ids) to "Faixas do ramo \"$name\"") else emptyList())
        if (app.model.submitAll(edits) != edits.size) return null
        branches = SetBranches.load(branchesFile) + SetBranch(id, parent, fromTrackId, back)
        runCatching { SetBranches.save(branchesFile, branches) }
        if (kind == BranchKind.POOL) runCatching { SetPool.save(poolFile(id), 0) }
        app.did(
            when (kind) {
                BranchKind.MANUAL -> "Ramo $letter · escolha as faixas"
                BranchKind.POOL -> "Ramo $letter · pool de ${ids.size}"
                BranchKind.LIST -> "Ramo $letter · ${ids.size} faixas"
            } + if (back != null) " · volta na linha" else "",
        )
        open(id)
        return id
    }

    /** O caminho da linha aberta inteiro (num ramo, com o que vem depois da volta): onde um ramo novo pode juntar. */
    private fun joinLine(): List<Int> = if (branch == null) entries() else playlistId?.let { route(it)?.ids } ?: ids()

    /** Onde um ramo novo que sai de [fromTrackId] pode juntar: as faixas do caminho depois dela. */
    fun joinChoices(fromTrackId: Int): List<Int> {
        val line = joinLine()
        val at = line.indexOf(fromTrackId).takeIf { it >= 0 } ?: return emptyList()
        return line.drop(at + 1).distinct()
    }

    /** Onde já juntam os ramos que saem de [fromTrackId] (as vertentes de um mesmo ponto juntam no mesmo lugar). */
    fun siblingJoin(fromTrackId: Int): Int? =
        branchesHere().firstOrNull { it.fromTrackId == fromTrackId && it.rejoinTrackId != null }?.rejoinTrackId?.takeIf { it in joinChoices(fromTrackId) }

    /** As grades mudaram (refazer grades): lê de novo na próxima vez. */
    fun forgetGrids() = synchronized(grids) { grids.clear() }

    fun grid(t: TrackItem): TrackGrid? {
        synchronized(grids) { if (grids.containsKey(t.id)) return grids[t.id] }
        // Lê fora da trava: a tela não espera a leitura de outra faixa que a thread de fundo esteja fazendo.
        val f = runCatching { app.model.trackGrid(t) }.getOrNull()
        val g = if (f == null || f.beats.isEmpty()) null else TrackGrid(f.beats, f.cues, f.durationMs)
        synchronized(grids) { grids[t.id] = g }
        return g
    }

    /** As transições da linha aberta: só na parte com ordem (numa pool a ordem é escolhida na hora). */
    fun plan(ids: List<Int> = ids()): List<Transition> {
        val v = app.model.view ?: return emptyList()
        return SetPlanner.plan(v, fixedPart(ids), ::grid, keep = saved, bars = app.transitionBars)
    }

    fun update(t: Transition) {
        val id = playlistId ?: return
        saved = saved.filterNot { it.fromId == t.fromId && it.toId == t.toId } + t
        runCatching { SetPlanner.save(file(id), saved) }
    }

    /** Volta a transição para o automático. */
    fun reset(t: Transition) {
        val id = playlistId ?: return
        saved = saved.filterNot { it.fromId == t.fromId && it.toId == t.toId }
        runCatching { SetPlanner.save(file(id), saved) }
    }

    fun suggestions(ids: List<Int>): List<TrackItem> {
        val v = app.model.view ?: return emptyList()
        // Na pool: as que combinam com a entrada, com a volta e com as que já estão nela.
        if (poolStart(ids) != null) {
            val anchors = listOfNotNull(poolEntry(ids), branch?.rejoinTrackId).mapNotNull { v.track(it) }
            val busy = ids.toSet() + tree().flatMap { v.entries[it.playlistId].orEmpty() } + (rootId()?.let { v.entries[it].orEmpty() } ?: emptyList())
            return SetPool.fill(v, anchors, poolIds(ids), busy, 3).mapNotNull { v.track(it) }
        }
        // Um ramo que volta para a linha já tem fim; não há "próxima".
        if (branch?.rejoinTrackId != null) return emptyList()
        val last = ids.lastOrNull()?.let { v.track(it) } ?: return emptyList()
        // Num ramo, nada que já está no set (na linha principal ou em outro ramo).
        val busy = ids.toSet() + if (branch != null) tree().flatMap { v.entries[it.playlistId].orEmpty() } + (rootId()?.let { v.entries[it].orEmpty() } ?: emptyList()) else emptyList()
        return SetPlanner.suggestNext(v, last, busy)
    }

    fun append(t: TrackItem) {
        val id = playlistId ?: return
        if (app.model.submit(Edit.AddToPlaylist(id, listOf(t.id)), "Pôr \"${t.title}\" no set")) app.did("+ ${t.title}")
    }

    /** Onde o ramo aberto pode voltar: as faixas do caminho de onde ele saiu, depois do ponto de virada. */
    fun rejoinChoices(): List<Int> {
        val b = branch ?: return emptyList()
        val v = app.model.view ?: return emptyList()
        val parent = if (b.mainId == rootId()) v.entries[b.mainId].orEmpty() else route(b.mainId)?.ids ?: return emptyList()
        val at = parent.indexOf(b.fromTrackId).takeIf { it >= 0 } ?: return emptyList()
        return parent.drop(at + 1).filter { v.track(it) != null }.distinct()
    }

    /** Muda onde o ramo aberto volta ([trackId]) ou faz dele outro final (null). */
    fun setRejoin(trackId: Int?) {
        val b = branch ?: return
        branches = SetBranches.load(branchesFile).map { if (it.playlistId == b.playlistId) it.copy(rejoinTrackId = trackId) else it }
        runCatching { SetBranches.save(branchesFile, branches) }
        val v = app.model.view
        app.did(trackId?.let { "Volta em ${v?.track(it)?.title.orEmpty()}" } ?: "Outro final")
    }

    private fun reader(t: TrackItem) = SegmentReader { start, len -> AudioIo.segment(File(app.root, t.filePath.replace('\\', '/').trimStart('/')), start, len) }

    /** [tempo]: o master da troca, lido na thread da tela antes de montar (a tela pode mudar enquanto monta). */
    private fun render(tr: Transition, lead: Int, tail: Int, waves: Boolean = false, tempo: Double? = tempoFor(tr)): MixPreview? {
        val v = app.model.view ?: return null
        val a = v.track(tr.fromId) ?: return null
        val b = v.track(tr.toId) ?: return null
        return MixRenderer.transition(reader(a), a.bpm, reader(b), b.bpm, tr, lead, tail, waves, tempo = tempo ?: a.bpm)
    }

    // ---- teste de mixagem (a tela de dois decks) ------------------------------------------------------------------

    /** Transição aberta na tela de teste. */
    var mixing by mutableStateOf<Transition?>(null)
        private set

    /** A prévia montada da transição aberta (com ondas); fica a anterior na tela enquanto a nova é montada. */
    var mixPreview by mutableStateOf<MixPreview?>(null)
        private set
    var mixFile by mutableStateOf<File?>(null)
        private set
    var mixBusy by mutableStateOf(false)
        private set
    var mixError by mutableStateOf<String?>(null)
        private set

    /** Onde a cabeça está na prévia (ms), parada ou tocando. */
    var cursorMs by mutableStateOf(0)

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "deckbridge-mix").apply { isDaemon = true } }
    @Volatile private var generation = 0
    private val previews = object : LinkedHashMap<String, MixPreview>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MixPreview>?) = size > 3
    }

    fun openMix(tr: Transition) {
        app.preview.stop()
        mixing = tr
        mixPreview = null
        mixFile = null
        rebuild(tr, startAtOut = true)
    }

    fun closeMix() {
        app.preview.stop()
        generation++
        mixing = null
        mixPreview = null
    }

    /** Ajuste na tela de teste: guarda no plano e monta de novo; a cabeça fica no mesmo lugar relativo ao ponto de saída. */
    fun editMix(changed: Transition) {
        // A transição mudou: o que o DJ marcou no ensaio era sobre a antiga.
        val tr = changed.copy(mark = null)
        val wasPlaying = app.preview.playingKey?.startsWith("mix|") == true
        val rel = cursorMs - (mixPreview?.leadMs ?: 0)
        app.preview.stop()
        mixing = tr
        update(tr)
        rebuild(tr, startAtOut = false, keepRel = rel, play = wasPlaying)
    }

    private fun rebuild(tr: Transition, startAtOut: Boolean, keepRel: Int = 0, play: Boolean = false) {
        val gen = ++generation
        mixBusy = true
        mixError = null
        val tempo = tempoFor(tr)
        val key = keyOf(tr, tempo)
        worker.execute {
            val result = runCatching {
                val p = synchronized(previews) { previews[key] } ?: render(tr, 12_000, 12_000, waves = true, tempo = tempo)?.also { synchronized(previews) { previews[key] = it } }
                    ?: throw IllegalStateException("Não consegui ler o áudio das duas faixas")
                val out = cacheFile(key)
                if (!out.isFile) publish(out) { it.writeBytes(MixRenderer.wav(p.audio)) }
                p to out
            }
            onMain {
                if (gen != generation) return@onMain
                mixBusy = false
                result.onSuccess { (p, f) ->
                    mixPreview = p
                    mixFile = f
                    cursorMs = (if (startAtOut) p.leadMs - (2 * p.barMsA).toInt() else p.leadMs + keepRel).coerceIn(0, p.lengthMs)
                    if (play) playMix()
                }.onFailure { mixError = it.message ?: it.javaClass.simpleName }
            }
        }
    }

    fun mixKey() = mixing?.let { "mix|" + keyOf(it) }

    val mixPlaying get() = app.preview.playingKey != null && app.preview.playingKey == mixKey()

    fun playMix() {
        val f = mixFile ?: return
        val key = mixKey() ?: return
        if (mixPlaying) {
            cursorMs = app.preview.positionMs()
            app.preview.stop()
        } else {
            app.preview.toggleFile(f, key, "teste de mix", cursorMs)
        }
    }

    fun seekMix(ms: Int) {
        val p = mixPreview ?: return
        cursorMs = ms.coerceIn(0, p.lengthMs)
        if (mixPlaying) app.preview.seekTo(cursorMs)
    }

    /**
     * Corrige a grade da que entra pelo atraso medido entre os bumbos: assim o sync do player (que alinha grades) também
     * fica certo, não só a prévia. A entrada anda junto, e a prévia é montada de novo (o sync fino passa a medir ~0).
     */
    fun fixGridOfIncoming() {
        val p = mixPreview ?: return
        val tr = mixing ?: return
        val measured = p.measuredMs ?: return
        val b = app.model.view?.track(tr.toId) ?: return
        val g = grid(b) ?: return
        val first = g.beats.firstOrNull()?.timeMs ?: return
        val delta = Math.round(measured * p.speed).toInt()
        if (delta == 0) return
        val period = 60000.0 / b.bpm
        var newFirst = first + delta
        while (newFirst < 0) newFirst += period.toInt()
        if (!app.model.submit(Edit.SetGrid(b.id, b.bpm, newFirst), "Grade de \"${b.title}\": %+d ms".format(delta))) return
        val fixed = tr.copy(inMs = (tr.inMs + delta).coerceAtLeast(0))
        app.background("Salvando a grade") { j ->
            // No computador com o pendrive aberto, a grade fica na fila até o usuário gravar.
            val saved = app.saveNow { j.text = it }
            onMain {
                synchronized(grids) { grids.remove(b.id) }
                editMix(fixed)
                app.did("Grade de ${b.title}: %+d ms".format(delta) + if (saved) "" else " (na fila)")
            }
        }
    }

    /** Os pontos que o DJ marca na onda quando a análise errou: o começo e o drop da que entra, o outro da que sai. */
    enum class Spot(val cue: String) { INTRO("Intro"), DROP("Drop"), OUTRO("Outro") }

    /**
     * Marca [spot] onde a cabeça está (no início de compasso mais perto), como um cue de memória com o nome ("Intro", "Drop",
     * "Outro") — o mesmo que a montagem automática procura — e acerta a troca aberta por ele: a entrada vai para o Intro, a
     * saída para o Outro, e com o Drop a troca dura até ele (o grave troca no drop). Vai para a fila de mudanças da faixa
     * (gravada como as outras: sozinha no celular, ao aplicar no computador) e vale para as outras trocas com ela também.
     * Devolve falso se a cabeça está fora da faixa.
     */
    fun markHere(spot: Spot): Boolean {
        val p = mixPreview ?: return false
        val tr = mixing ?: return false
        val v = app.model.view ?: return false
        val a = v.track(tr.fromId) ?: return false
        val b = v.track(tr.toId) ?: return false
        val t = if (spot == Spot.OUTRO) a else b
        val raw = if (spot == Spot.OUTRO) p.aAt(cursorMs) else p.bAt(cursorMs)
        val g = grid(t)
        val dur = g?.durationMs?.takeIf { it > 0 } ?: (t.durationSeconds * 1000)
        if (raw < 0 || raw > dur) {
            app.notice = os.deckbridge.manager.Banner(if (spot == Spot.OUTRO) "A cabeça já passou do fim da que sai" else "Ponha a cabeça onde a que entra já toca", true)
            return false
        }
        val at = SetPlanner.snap(g, raw)
        val base = g?.cues ?: runCatching { app.model.trackGrid(t).cues }.getOrDefault(emptyList())
        val cues = base.filterNot { !it.isHot && it.comment.equals(spot.cue, ignoreCase = true) } + os.deckbridge.manager.anlz.Cue(0, at, comment = spot.cue)
        if (!app.model.submit(Edit.SetCues(t.id, cues), "${spot.cue} de \"${t.title}\" em ${at / 60000}:%02d".format(at / 1000 % 60))) return false
        // A grade em memória já tem o ponto novo (no computador, o arquivo só muda quando o usuário aplica).
        synchronized(grids) { grids[t.id] = g?.let { TrackGrid(it.beats, cues, it.durationMs) } }
        val ga = grid(a)
        val gb = grid(b)
        val fixed = when (spot) {
            Spot.INTRO -> tr.copy(inMs = at)
            Spot.OUTRO -> tr.copy(outMs = SetPlanner.fitOut(a, ga, at, tr.bars))
            Spot.DROP -> SetPlanner.barsToDrop(b, gb, tr.inMs)?.let { n -> tr.copy(bars = n, outMs = SetPlanner.fitOut(a, ga, tr.outMs, n), swapAt = 1.0) } ?: tr
        }
        editMix(fixed)
        app.did(
            when (spot) {
                Spot.DROP -> if (fixed !== tr) "Drop marcado · troca de ${fixed.bars} compassos" else "Drop marcado"
                Spot.INTRO -> "Entrada no intro"
                Spot.OUTRO -> "Saída no outro"
            },
        )
        return true
    }

    private fun cacheFile(key: String) = File(app.cacheDir, "mix/${key.hashCode().toUInt()}.wav")

    private fun keyOf(tr: Transition, tempo: Double? = tempoFor(tr)): String {
        val v = app.model.view
        val sig = listOf(tr.fromId, tr.toId).joinToString { id -> v?.track(id)?.let { "${it.filePath}:${it.bpm}" } ?: "" }
        return "t|$tr|$sig|$tempo"
    }

    fun keyFor(tr: Transition) = keyOf(tr)

    /** Monta a prévia de [tr] num WAV (ou devolve a que já está montada). Bloqueante: chamar fora da thread da tela. */
    fun renderTransition(tr: Transition, tempo: Double? = tempoFor(tr)): File {
        val out = cacheFile(keyOf(tr, tempo))
        if (out.isFile) return out
        val s = render(tr, 12_000, 12_000, tempo = tempo)?.audio ?: throw IllegalStateException("não consegui ler o áudio das duas faixas")
        return publish(out) { it.writeBytes(MixRenderer.wav(s)) }
    }

    /** Todas as transições em sequência (8 s antes e 8 s depois de cada). Null se cancelado. Bloqueante. */
    fun renderSet(
        plan: List<Transition>, cancelled: () -> Boolean = { false }, tempos: List<Double?> = plan.map { tempoFor(it) }, progress: (Int) -> Unit = {},
    ): File? {
        val out = cacheFile(setKey(plan, tempos))
        if (out.isFile) return out
        var used = 0
        val file = publish(out) { tmp -> used = MixRenderer.writeSequence(tmp, plan.mapIndexed { i, tr -> { render(tr, 8_000, 8_000, tempo = tempos[i])?.audio } }, cancelled = cancelled, progress = progress) }
        if (cancelled() || used == 0) {
            file.delete()
            if (used == 0 && !cancelled()) throw IllegalStateException("não consegui ler o áudio")
            return null
        }
        return file
    }

    private fun publish(out: File, write: (File) -> Unit): File {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".novo")
        write(tmp)
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        return out
    }

    fun setKey(plan: List<Transition>, tempos: List<Double?> = plan.map { tempoFor(it) }) =
        "all|" + plan.withIndex().joinToString(";") { (i, tr) -> keyOf(tr, tempos[i]) }

    private fun onMain(block: () -> Unit) = app.onMain(block)

    /** Monta (se ainda não tem) e toca a prévia de uma transição; tocar de novo para. */
    fun play(tr: Transition) {
        app.seen("hear")
        val key = keyOf(tr)
        if (app.preview.playingKey == key) return app.preview.stop()
        val ready = cacheFile(key)
        if (ready.isFile) return app.preview.toggleFile(ready, key, "transição")
        val opened = playlistId
        val tempo = tempoFor(tr)
        app.background("Montando prévia") { j ->
            val f = renderTransition(tr, tempo)
            // Saiu do SET (ou abriu outro) enquanto montava: não começa a tocar numa tela que já não está aberta.
            if (!j.cancelled) onMain { if (playlistId == opened && opened != null) app.preview.toggleFile(f, key, "transição") }
        }
    }

    /** As transições do set uma depois da outra, para ouvir o set de ponta a ponta. */
    fun playAll(plan: List<Transition>, tempos: List<Double?> = plan.map { tempoFor(it) }) {
        if (plan.isEmpty()) return
        val key = setKey(plan, tempos)
        if (app.preview.playingKey == key) return app.preview.stop()
        val ready = cacheFile(key)
        if (ready.isFile) return app.preview.toggleFile(ready, key, "set")
        val opened = playlistId
        app.background("Montando o set") { j ->
            val f = renderSet(plan, { j.cancelled }, tempos) { j.text = "${it + 1}/${plan.size}" } ?: return@background
            onMain { if (playlistId == opened && opened != null) app.preview.toggleFile(f, key, "set") }
        }
    }

    // ---- montar, ordenar, checar e ensaiar ----------------------------------------------------------------------

    fun issues(ids: List<Int>, plan: List<Transition>): List<os.deckbridge.manager.mix.SetIssue> {
        val v = app.model.view ?: return emptyList()
        val fixed = fixedPart(ids)
        return os.deckbridge.manager.mix.SetDoctor.check(v, fixed, plan, ::grid, masters(ids).take(fixed.size))
    }

    /** Reordena a playlist para mixar (a primeira faixa fica: é a escolha do DJ para abrir). Desfazível. */
    fun reorder(shape: os.deckbridge.manager.mix.SetShape) {
        val id = playlistId ?: return
        val v = app.model.view ?: return
        val ids = entries()
        // Num ramo, a ordem começa no ponto de virada (que não é do ramo e fica fora da playlist).
        val anchor = branch?.fromTrackId
        val order = if (anchor != null) {
            os.deckbridge.manager.mix.SetBuilder.order(v, listOf(anchor) + ids, shape, first = anchor).drop(1)
        } else os.deckbridge.manager.mix.SetBuilder.order(v, ids, shape, first = ids.firstOrNull())
        if (order == ids) return app.did("Já está na melhor ordem")
        if (app.model.submit(Edit.ReorderPlaylist(id, order), "Ordenar \"${v.playlists.first { it.id == id }.name}\" para mixar (${shape.title})")) app.did("Ordenada: ${shape.title}")
    }

    fun bridges(a: Int, b: Int): List<TrackItem> {
        val v = app.model.view ?: return emptyList()
        val busy = ids().toSet() + (rootId()?.let { v.entries[it].orEmpty() } ?: emptyList())
        return os.deckbridge.manager.mix.SetDoctor.bridges(v, v.track(a) ?: return emptyList(), v.track(b) ?: return emptyList(), busy)
    }

    /** Põe [bridge] entre [after] e a seguinte. Um desfazer tira as duas mudanças. */
    fun insertBridge(after: Int, bridge: TrackItem) {
        val id = playlistId ?: return
        val ids = entries()
        // Depois do ponto de virada de um ramo, a ponte é a primeira do ramo.
        val at = if (after == branch?.fromTrackId) -1 else ids.indexOf(after).takeIf { it >= 0 } ?: return
        val order = ids.toMutableList().apply { add(at + 1, bridge.id) }
        val n = app.model.submitAll(listOf(
            Edit.AddToPlaylist(id, listOf(bridge.id)) to "Ponte \"${bridge.title}\"",
            Edit.ReorderPlaylist(id, order) to "Ponte \"${bridge.title}\" no lugar",
        ))
        if (n == 2) app.did("Ponte: ${bridge.title}")
    }

    fun mark(tr: Transition, mark: Transition.Mark?) = update(tr.copy(mark = mark))

    /** Ensaio: índice da transição sendo ouvida no plano, ou null. */
    var rehearsal by mutableStateOf<Int?>(null)
        private set

    fun startRehearsal(plan: List<Transition>) {
        if (plan.isEmpty()) return
        // Começa na primeira que ainda não está boa.
        val at = plan.indexOfFirst { it.mark != Transition.Mark.GOOD }.takeIf { it >= 0 } ?: 0
        rehearsal = at
        hear(plan[at])
    }

    private fun hear(tr: Transition) {
        if (app.preview.playingKey != keyOf(tr)) play(tr)
    }

    /** Marca a transição do ensaio e passa para a próxima; na última, termina com o resumo. */
    fun rehearse(plan: List<Transition>, mark: Transition.Mark) {
        val at = rehearsal ?: return
        val tr = plan.getOrNull(at) ?: return stopRehearsal()
        mark(tr, mark)
        val next = at + 1
        if (next >= plan.size) {
            stopRehearsal()
            val marks = plan.mapIndexed { i, t -> if (i == at) mark else t.mark }
            app.did("Ensaio: ${marks.count { it == Transition.Mark.GOOD }} boas · ${marks.count { it == Transition.Mark.REDO }} a refazer")
        } else {
            rehearsal = next
            hear(plan[next])
        }
    }

    fun stopRehearsal() {
        rehearsal = null
        app.preview.stop()
    }

    /**
     * Monta um set novo de [minutes] minutos a partir de [pool], cria a playlist já na ordem para mixar e abre o SET dela.
     * Devolve o id da playlist, ou null se não deu (nada com andamento no conjunto).
     */
    fun buildSet(pool: List<TrackItem>, minutes: Int, shape: os.deckbridge.manager.mix.SetShape, name: String): Int? {
        val v = app.model.view ?: return null
        val order = os.deckbridge.manager.mix.SetBuilder.build(v, pool, minutes, shape)
        if (order.size < 2) {
            app.notice = os.deckbridge.manager.Banner("Poucas faixas com BPM para montar um set", true)
            return null
        }
        val id = app.model.nextPlaylistId()
        val n = app.model.submitAll(listOf(
            Edit.CreatePlaylist(id, 0, name, false) to "Montar \"$name\"",
            Edit.AddToPlaylist(id, order) to "Faixas de \"$name\"",
        ))
        if (n != 2) return null
        open(id)
        app.did("$name · ${order.size} faixas")
        return id
    }
}
