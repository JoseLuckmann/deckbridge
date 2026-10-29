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

    init {
        app.model.setPlaylists = { knownSets() }
    }

    private var known: Pair<String, Set<Int>>? = null

    /**
     * As playlists que já foram abertas como set (a linha e os ramos), pelo que está guardado na pasta dos sets. Lido de
     * novo só quando a pasta muda.
     */
    fun knownSets(): Set<Int> {
        val dir = app.setsDir
        val stamp = "${dir.absolutePath}|${dir.lastModified()}|${branchesFile.lastModified()}"
        known?.let { (k, ids) -> if (k == stamp) return ids }
        val names = Regex("^(\\d+)\\.(set|properties|master|pool)$")
        val ids = (dir.list().orEmpty().mapNotNull { names.find(it)?.groupValues?.get(1)?.toIntOrNull() } +
            SetBranches.load(branchesFile).flatMap { listOf(it.playlistId, it.mainId) }).toSet()
        known = stamp to ids
        return ids
    }

    fun open(id: Int) {
        // Guarda que esta playlist é um set (para "Esquecidas" e "Onde está"), fora do pendrive.
        runCatching { app.setsDir.mkdirs(); File(app.setsDir, "$id.set").createNewFile() }
        synchronized(grids) { grids.clear() }
        hidden = loadHidden()
        prefetch(id)
        branches = SetBranches.load(branchesFile)
        saved = SetPlanner.load(file(id))
        poolFrom = poolFromOf(id)
        poolNow = null
        poolPlayed = emptySet()
        playlistId = id
        masterFixed = SetTempo.loadMode(masterFile(rootId() ?: id))
        loadGoal()
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
        if (!app.preview.inBackground) app.preview.stop()
        rehearsal = null
        open(main)
    }

    fun close() {
        // O set tocando em segundo plano continua (é um player de música); o resto (prévias, ensaio) para.
        if (!app.preview.inBackground) app.preview.stop()
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
        // Sem pedir confirmação: tira e avisa com DESFAZER (as vertentes que saem dela voltam junto).
        val gone = branchesAt(index).joinToString(", ") { "${letterOf(it)}" }
        // A mesma faixa duas vezes na linha: tira as duas e põe de volta a que fica, no lugar.
        val edits = listOf(Edit.RemoveFromPlaylist(pid, listOf(id)) to "Tirar \"$title\" do set") +
            if (id in rest) listOf(Edit.AddToPlaylist(pid, listOf(id)) to "Manter \"$title\"", Edit.ReorderPlaylist(pid, rest) to "Ordem do set") else emptyList()
        if (app.model.submitAll(edits) == edits.size) app.did("− $title" + if (gone.isNotEmpty()) " · a vertente $gone some junto" else "")
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
    fun addChoices(query: String, steer: os.deckbridge.manager.smart.Steer = os.deckbridge.manager.smart.Steer.MATCH): List<TrackItem> {
        val v = app.model.view ?: return emptyList()
        val ids = ids()
        val busy = ids.toSet() + tree().flatMap { v.entries[it.playlistId].orEmpty() } + (rootId()?.let { v.entries[it].orEmpty() } ?: emptyList())
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        // Buscar acha qualquer uma; as que o DJ pediu para não sugerir ficam fora só das sugestões.
        if (words.isNotEmpty()) return v.tracks.filter { it.id !in busy && words.all { w -> "${it.title} ${it.artist}".lowercase().contains(w) } }.take(30)
        val skip = busy + hidden
        if (poolStart(ids) != null) return suggestions(ids).filter { it.id !in hidden }
        val last = (entries().lastOrNull() ?: branch?.fromTrackId)?.let { v.track(it) }
            ?: return v.tracks.filter { it.id !in skip }.sortedByDescending { it.rating }.take(12)
        val inSets = os.deckbridge.manager.smart.Keep.inSets(v, knownSets())
        return os.deckbridge.manager.smart.Suggest.next(v, last, skip, steer, inSets)
    }

    private val hiddenFile get() = File(app.setsDir, "nao-sugerir.txt")

    /**
     * As faixas que o DJ pediu para não sugerir (retorno pontual sobre uma sugestão; ver Amershi et al. 2019). Ficam na
     * pasta dos sets, fora do pendrive; a busca ainda acha.
     */
    var hidden by mutableStateOf(loadHidden())
        private set

    private fun loadHidden(): Set<Int> = runCatching { hiddenFile.readLines().mapNotNull { it.trim().toIntOrNull() }.toSet() }.getOrDefault(emptySet())

    fun hideSuggestion(t: TrackItem) {
        hidden = hidden + t.id
        runCatching { app.setsDir.mkdirs(); hiddenFile.writeText(hidden.joinToString("\n")) }
        app.notice = os.deckbridge.manager.Banner("Não vou mais sugerir \"${t.title.ifEmpty { t.fileName }}\" (⋯ → Sugestões escondidas)", false)
    }

    /** Controle geral: volta a sugerir todas. */
    fun showAllSuggestions() {
        hidden = emptySet()
        runCatching { hiddenFile.delete() }
    }

    private val adjustedCache = HashMap<Int, Pair<Long, Set<Pair<Int, Int>>>>()

    /**
     * As trocas da linha [pid] que o DJ ajustou (as outras são as automáticas). O quadro mostra a diferença: deixar claro o
     * que o sistema fez sozinho (Amershi et al. 2019).
     */
    fun adjustedPairs(pid: Int): Set<Pair<Int, Int>> {
        if (pid == playlistId) return saved.map { it.fromId to it.toId }.toSet()
        val f = file(pid)
        val stamp = f.lastModified()
        adjustedCache[pid]?.let { (k, v) -> if (k == stamp) return v }
        val pairs = runCatching { SetPlanner.load(f).map { it.fromId to it.toId }.toSet() }.getOrDefault(emptySet())
        adjustedCache[pid] = stamp to pairs
        return pairs
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
    fun forgetGrids() {
        synchronized(grids) { grids.clear() }
        forgetProfiles()
    }

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
        return SetPlanner.plan(v, fixedPart(ids), ::grid, keep = saved, bars = app.transitionBars, habits = habits(), found = { a, b -> mixOptions(a, b)?.firstOrNull()?.transition })
    }

    // ---- achar a troca ouvindo as faixas (perfil por compasso e MixFinder) ------------------------------------------

    private val profilesDir get() = File(app.setsDir, "perfis")
    private val profiles = HashMap<Int, os.deckbridge.manager.mix.TrackProfile?>()
    private val options = HashMap<Pair<Int, Int>, List<os.deckbridge.manager.mix.MixPlan>>()
    private val pending = HashSet<Pair<Int, Int>>()
    private val profiler = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "deckbridge-perfis").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** Muda cada vez que chega um perfil ou uma lista de trocas: a tela recompõe. */
    var heard by mutableStateOf(0)
        private set

    /** Quantos pares ainda estão sendo ouvidos. */
    var hearing by mutableStateOf(0)
        private set

    /** O perfil guardado é o desta faixa com esta grade: muda o arquivo, o BPM ou a primeira batida 1, mede de novo. */
    private fun profileFile(t: TrackItem, g: TrackGrid?): File {
        val first = g?.beats?.firstOrNull { it.inBar == 1 }?.timeMs ?: 0
        val tag = Integer.toHexString(t.filePath.hashCode())
        return File(profilesDir, "${t.id}-$tag-${Math.round(t.bpm * 100)}-$first.txt")
    }

    /** O perfil de [t]: do cache, do disco ou medido agora (lê o áudio inteiro: só fora da thread da tela). */
    private fun profileNow(t: TrackItem): os.deckbridge.manager.mix.TrackProfile? {
        synchronized(profiles) { if (profiles.containsKey(t.id)) return profiles[t.id] }
        val g = grid(t)
        val f = profileFile(t, g)
        val p = os.deckbridge.manager.mix.TrackProfile.load(f) ?: runCatching {
            val audio = AudioIo.mono(audioFile(t))
            val first = g?.beats?.firstOrNull { it.inBar == 1 }?.timeMs?.toDouble() ?: 0.0
            os.deckbridge.manager.mix.TrackProfile.of(audio.samples, audio.rate, t.bpm, first)?.also {
                runCatching { os.deckbridge.manager.mix.TrackProfile.save(f, it) }
            }
        }.getOrNull()
        synchronized(profiles) { profiles[t.id] = p }
        return p
    }

    /**
     * As formas de fazer a troca de [a] para [b], da melhor para a pior, se as duas já foram ouvidas; senão pede para
     * ouvir (em segundo plano) e devolve null.
     */
    fun mixOptions(a: TrackItem, b: TrackItem): List<os.deckbridge.manager.mix.MixPlan>? {
        heard.let { }
        val key = a.id to b.id
        synchronized(options) {
            options[key]?.let { return it }
            if (!pending.add(key)) return null
        }
        // Pedido durante a composição da tela: o contador muda depois, fora dela.
        onMain { hearing++ }
        profiler.execute {
            val found = runCatching {
                val pa = profileNow(a)
                val pb = profileNow(b)
                if (pa == null || pb == null) emptyList()
                else os.deckbridge.manager.mix.MixFinder.find(a, pa, b, pb, grid(a), grid(b), limit = 4)
            }.getOrDefault(emptyList())
            synchronized(options) { options[key] = found; pending.remove(key) }
            onMain { hearing = (hearing - 1).coerceAtLeast(0); heard++ }
        }
        return null
    }

    /** As opções da troca aberta no MIX (null enquanto ouve as faixas). */
    fun mixOptions(): List<os.deckbridge.manager.mix.MixPlan>? {
        val tr = mixing ?: return null
        val v = app.model.view ?: return null
        return mixOptions(v.track(tr.fromId) ?: return null, v.track(tr.toId) ?: return null)
    }

    /** Usa uma das opções na troca aberta: fica o lembrete e o sync fino à mão; a mixagem gravada era de outra troca. */
    fun useOption(o: os.deckbridge.manager.mix.MixPlan) {
        val cur = mixing ?: return
        draftTake = null
        touchedNow = emptySet()
        editMix(o.transition.copy(note = cur.note, nudgeMs = cur.nudgeMs, masterTempo = cur.masterTempo, phaseLock = cur.phaseLock))
    }

    /** As grades ou o áudio mudaram: ouve de novo. */
    fun forgetProfiles() {
        synchronized(profiles) { profiles.clear() }
        synchronized(options) { options.clear() }
        heard++
    }

    // ---- o jeito do DJ, aprendido das mixagens guardadas ------------------------------------------------------------

    private val habitsFile get() = File(app.setsDir, "habitos.properties")
    private var habitsCache: os.deckbridge.manager.mix.MixHabits? = null

    /** O que o DJ costuma fazer nas trocas (das mixagens guardadas em todos os sets); null antes de haver o bastante. */
    fun habits(): os.deckbridge.manager.mix.MixHabits? {
        val h = habitsCache ?: (os.deckbridge.manager.mix.MixHabits.load(habitsFile) ?: os.deckbridge.manager.mix.MixHabits(null, null, 0)).also { habitsCache = it }
        return h.takeIf { it.ready }
    }

    /** Aprende de novo com todas as mixagens guardadas (depois de guardar uma). */
    private fun relearn() {
        val all = app.setsDir.listFiles { f -> f.name.matches(Regex("-?\\d+\\.properties")) }.orEmpty()
            .flatMap { runCatching { SetPlanner.load(it) }.getOrDefault(emptyList()) }
        val h = os.deckbridge.manager.mix.MixHabits.learn(all)
        habitsCache = h
        runCatching { os.deckbridge.manager.mix.MixHabits.save(habitsFile, h) }
    }

    /** O lembrete da troca aberta no MIX (não muda o som: não monta a prévia de novo). */
    fun setNote(text: String) {
        val tr = mixing ?: return
        val changed = tr.copy(note = text.trim().take(140))
        mixing = changed
        update(changed)
    }

    /** Por que [t] aparece como sugestão para a linha aberta: tom, andamento, nota e em quantos sets já entrou. */
    fun why(t: TrackItem): String {
        val v = app.model.view ?: return ""
        val last = (entries().lastOrNull() ?: branch?.fromTrackId)?.let { v.track(it) }
        return os.deckbridge.manager.smart.Keep.why(last, t, os.deckbridge.manager.smart.Keep.setCount(v, t.id, knownSets()))
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

    /** Quem toca a prévia ao vivo: mixa na hora com a troca de agora e a mão do DJ. */
    var mixPlayer by mutableStateOf<os.deckbridge.manager.mix.MixPlayer?>(null)
        private set
    var mixBusy by mutableStateOf(false)
        private set

    /** Quanto a última prévia levou para montar (ms), para dizer quanto a próxima deve demorar; null antes da primeira. */
    var lastBuildMs by mutableStateOf<Long?>(null)
        private set
    var mixError by mutableStateOf<String?>(null)
        private set

    /** Onde a cabeça está na prévia (ms), parada ou tocando. */
    var cursorMs by mutableStateOf(0)

    /**
     * A mixagem em ensaio: a guardada na troca mais o que a mão fez desde então (ainda não guardado). Null é o automático.
     */
    var draftTake by mutableStateOf<os.deckbridge.manager.mix.MixTake?>(null)
        private set

    /** Os controles que a mão mexeu nesta passada (a tela os pinta de laranja). */
    var touchedNow by mutableStateOf(setOf<os.deckbridge.manager.mix.Ctl>())
        private set

    /** Repetir a troca (do pouco antes da saída ao pouco depois do fim), para ensaiar de novo. */
    var mixLoop by mutableStateOf(false)
        private set

    /** Há mão do DJ ainda não guardada (ou a volta ao automático ainda não guardada). */
    val takeChanged get() = draftTake != mixing?.take || touchedNow.isNotEmpty()

    private var seenWraps = 0

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "deckbridge-mix").apply { isDaemon = true } }
    @Volatile private var generation = 0
    private val previews = object : LinkedHashMap<String, MixPreview>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MixPreview>?) = size > 2
    }

    fun openMix(tr: Transition) {
        app.preview.stop()
        mixing = tr
        mixPreview = null
        mixPlayer = null
        draftTake = tr.take
        touchedNow = emptySet()
        rebuild(tr, startAtOut = true)
    }

    fun closeMix() {
        app.preview.stop()
        generation++
        mixing = null
        mixPreview = null
        mixPlayer = null
        touchedNow = emptySet()
    }

    /**
     * Ajuste na tela de teste: guarda no plano. Estilo, ritmo, grave, sync e lembrete mudam o som na hora (a prévia é
     * mixada ao vivo); duração, pontos e Master Tempo leem o áudio de novo, com a cabeça no mesmo lugar relativo à saída.
     */
    fun editMix(changed: Transition) {
        // A transição mudou: o que o DJ marcou no ensaio era sobre a antiga.
        val tr = changed.copy(mark = null)
        val p = mixPreview
        val tempo = tempoFor(tr) ?: app.model.view?.track(tr.fromId)?.bpm
        if (p != null && p.sameStems(tr) && tempo == p.tempo) {
            mixing = tr
            update(tr)
            mixPlayer?.transition = tr
            return
        }
        val wasPlaying = mixPlaying
        val rel = cursorMs - (mixPreview?.leadMs ?: 0)
        if (wasPlaying) cursorMs = app.preview.positionMs()
        closePass()
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
        val key = stemKey(tr, tempo)
        worker.execute {
            val started = System.currentTimeMillis()
            var built = false
            val result = runCatching {
                synchronized(previews) { previews[key] } ?: render(tr, 12_000, 12_000, waves = true, tempo = tempo)?.also { built = true; synchronized(previews) { previews[key] = it } }
                    ?: throw IllegalStateException("Não consegui ler o áudio das duas faixas")
            }
            val took = System.currentTimeMillis() - started
            onMain {
                // Quanto a última montagem levou: a próxima espera mostra uma previsão.
                if (built) lastBuildMs = took
                if (gen != generation) return@onMain
                mixBusy = false
                result.onSuccess { p ->
                    mixPreview = p
                    val now = mixing ?: tr
                    mixPlayer = os.deckbridge.manager.mix.MixPlayer(p, now).also { pl ->
                        pl.take = draftTake
                        pl.loop = if (mixLoop) loopRange(p) else null
                    }
                    seenWraps = 0
                    cursorMs = (if (startAtOut) p.leadMs - (2 * p.barMsA).toInt() else p.leadMs + keepRel).coerceIn(0, p.lengthMs)
                    if (play) playMix()
                }.onFailure { mixError = it.message ?: it.javaClass.simpleName }
            }
        }
    }

    fun mixKey() = mixing?.let { "mix|" + stemKey(it) }

    val mixPlaying get() = app.preview.playingKey != null && app.preview.playingKey == mixKey()

    fun playMix() {
        val pl = mixPlayer ?: return
        val key = mixKey() ?: return
        if (mixPlaying) {
            cursorMs = app.preview.positionMs()
            app.preview.stop()
            closePass()
        } else {
            pl.seek(cursorMs)
            app.preview.playStream(pl, key, "teste de mix")
        }
    }

    fun seekMix(ms: Int) {
        val p = mixPreview ?: return
        closePass()
        cursorMs = ms.coerceIn(0, p.lengthMs)
        if (mixPlaying) app.preview.seekTo(cursorMs) else mixPlayer?.seek(cursorMs)
    }

    /** A cada quadro enquanto toca: a cabeça segue o som e, se o loop voltou ao começo, a passada da mão fecha. */
    fun tick() {
        if (!mixPlaying) return
        cursorMs = app.preview.positionMs()
        val pl = mixPlayer ?: return
        if (pl.wraps != seenWraps) {
            seenWraps = pl.wraps
            closePass(pl.loop?.let { mixPreview?.relBar(it.last) })
        }
    }

    /** Onde se está (compassos desde a saída), para gravar a mão: o que se ouve, não o que já foi mixado. */
    private fun barNow(): Double? {
        val p = mixPreview ?: return null
        return p.relBar(if (mixPlaying) app.preview.positionMs() else cursorMs)
    }

    /**
     * A mão do DJ num controle do mixer: vale na hora (tocando ou parado) e é gravada como a mixagem desta troca, para
     * guardar ou desfazer depois. Parado, marca o valor a partir da cabeça.
     */
    fun setControl(c: os.deckbridge.manager.mix.Ctl, value: Float) {
        val pl = mixPlayer ?: return
        val bar = barNow() ?: return
        pl.pass.set(c, bar, value.coerceIn(0f, 1f))
        touchedNow = touchedNow + c
        if (!mixPlaying) closePass(bar)
    }

    /** Junta a passada da mão à mixagem em ensaio, terminando em [end] (compassos; padrão: onde se está). */
    private fun closePass(end: Double? = barNow()) {
        val pl = mixPlayer ?: return
        if (pl.pass.isEmpty) return
        val pass = pl.pass.close()
        val merged = (draftTake ?: os.deckbridge.manager.mix.MixTake.EMPTY).merge(pass, end ?: pass.values.maxOf { it.last().bar })
        draftTake = merged
        pl.take = merged
    }

    /** O canal [deck] volta ao automático daqui em diante (a mão sai dos controles dele). */
    fun releaseDeck(deck: Int) {
        closePass()
        val bar = barNow() ?: return
        val d = draftTake ?: return
        draftTake = d.release(deck, bar).takeIf { !it.isEmpty }
        mixPlayer?.take = draftTake
    }

    /** Guarda a mixagem em ensaio como a desta troca neste set (e aprende com ela o jeito do DJ). */
    fun keepTake() {
        closePass()
        val tr = mixing ?: return
        val take = draftTake?.takeIf { !it.isEmpty }
        val kept = tr.copy(take = take, mark = null)
        mixing = kept
        update(kept)
        mixPlayer?.transition = kept
        draftTake = take
        touchedNow = emptySet()
        relearn()
        app.did(if (take == null) "Troca no automático" else "Mixagem guardada: esta troca toca assim")
    }

    /** Desfaz o que a mão fez desde a última vez que guardou. */
    fun discardTake() {
        mixPlayer?.pass?.close()
        draftTake = mixing?.take
        mixPlayer?.take = draftTake
        touchedNow = emptySet()
    }

    /** Tira a mão de tudo: a troca volta ao automático (guardar confirma). */
    fun autoTake() {
        mixPlayer?.pass?.close()
        draftTake = null
        mixPlayer?.take = null
        touchedNow = emptySet()
    }

    /** Sync à mão: [ms] positivo adianta a que entra (como empurrar o prato), negativo atrasa. Vale na hora. */
    fun nudge(ms: Int) {
        val tr = mixing ?: return
        editMix(tr.copy(nudgeMs = (tr.nudgeMs + ms).coerceIn(-250, 250)))
    }

    /** A que entra uma batida inteira para a frente (+1) ou para trás (-1): para a grade que caiu na batida errada. */
    fun shiftBeat(beats: Int) {
        val tr = mixing ?: return
        editMix(tr.copy(beatShift = (tr.beatShift + beats).coerceIn(-4, 4)))
    }

    /** Volta o sync à mão para zero (fica o sync fino medido, se ligado). */
    fun resetSync() {
        val tr = mixing ?: return
        editMix(tr.copy(nudgeMs = 0, beatShift = 0))
    }

    /** Liga e desliga a repetição da troca. */
    fun toggleLoop() {
        mixLoop = !mixLoop
        val p = mixPreview ?: return
        mixPlayer?.loop = if (mixLoop) loopRange(p) else null
    }

    /** O trecho repetido: dois compassos antes da saída até dois depois do fim da troca. */
    fun loopRange(p: MixPreview): IntRange =
        (p.leadMs - (2 * p.barMsA).toInt()).coerceAtLeast(0)..(p.leadMs + p.overlapMs + (2 * p.barMsA).toInt()).coerceAtMost(p.lengthMs)

    /**
     * O som da prévia aberta como está agora (o automático, a mixagem guardada e a mão em ensaio) num WAV, para gravar
     * vídeo ou levar para fora. Bloqueante (mixa a prévia inteira).
     */
    val mixFile: File?
        get() {
            val p = mixPreview ?: return null
            val tr = mixing ?: return null
            val out = cacheFile("agora|" + stemKey(tr) + "|" + tr.copy(note = "", take = null) + "|" + draftTake?.encode())
            if (out.isFile) return out
            return publish(out) { it.writeBytes(MixRenderer.wav(p.mixdown(tr, draftTake))) }
        }

    /** O que pede ler o áudio de novo: as faixas, os pontos, a duração, o Master Tempo e o master. */
    private fun stemKey(tr: Transition, tempo: Double? = tempoFor(tr)): String {
        val v = app.model.view
        val sig = listOf(tr.fromId, tr.toId).joinToString { id -> v?.track(id)?.let { "${it.filePath}:${it.bpm}" } ?: "" }
        return "s|${tr.fromId}|${tr.toId}|${tr.outMs}|${tr.inMs}|${tr.bars}|${tr.masterTempo}|$sig|$tempo"
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

    /**
     * A cola do set, em texto: cada linha com as faixas na ordem (tom e BPM), as trocas planejadas entre elas (compassos,
     * estilo, ritmo, grave) e, em cada ramo, de onde sai e onde junta. Para ter na mão (ou no celular) na hora de tocar: o
     * CDJ recebe as linhas como playlists, mas não sabe onde uma vertente sai e junta.
     */
    fun sheet(): String {
        val v = app.model.view ?: return ""
        val root = rootId() ?: return ""
        val layout = canvas() ?: return ""
        fun name(id: Int) = v.track(id)?.let { it.title.ifEmpty { it.fileName } }.orEmpty()
        fun tr(t: Transition): String = listOfNotNull(
            "${t.bars} comp.", t.style.title.lowercase(),
            t.pace.takeIf { it != Transition.Pace.NORMAL }?.title?.lowercase(),
            if (t.style == os.deckbridge.manager.mix.MixStyle.BASS_SWAP && t.swapAt >= 0.99) "grave no fim" else null,
            if (t.style == os.deckbridge.manager.mix.MixStyle.BASS_SWAP && t.swapBars > 0) "grave em ${t.swapBars}" else null,
            when (t.mark) { Transition.Mark.GOOD -> "✓"; Transition.Mark.REDO -> "✗ refazer"; null -> null },
            t.note.takeIf { it.isNotBlank() }?.let { "“$it”" },
        ).joinToString(" · ")
        val out = StringBuilder()
        out.append("SET ${v.playlists.firstOrNull { it.id == root }?.name.orEmpty()} · master ${masterFixed?.let { "%.0f".format(it) } ?: "automático"}\n")
        for (lane in layout.lanes) {
            val plan = lanePlan(lane.playlistId)
            out.append("\n")
            out.append(if (lane.branch == null) "LINHA A\n" else "VERTENTE ${lane.letter} · sai de ${name(lane.split!!.trackId)} · " + (lane.rejoin?.let { "junta em ${name(it.trackId)}" } ?: "outro final") + "\n")
            val ids = listOfNotNull(lane.split?.trackId) + lane.cards.map { it.trackId } + listOfNotNull(lane.rejoin?.trackId)
            val poolAt = lane.cards.indexOfFirst { it.pool }
            lane.cards.forEachIndexed { i, c ->
                val t = v.track(c.trackId) ?: return@forEachIndexed
                if (i == poolAt) out.append("  POOL (a ordem é na hora):\n")
                val before = ids.getOrNull(ids.indexOf(c.trackId) - 1)
                plan.firstOrNull { it.fromId == before && it.toId == c.trackId }?.let { out.append("     ↓ ${tr(it)}\n") }
                val forks = layout.lanes.filter { it.split == c }.joinToString(", ") { "${it.letter}" }
                out.append("  ${i + 1}. ${name(c.trackId)} · ${keyLabel(t.key)} · ${bpmLabel(t.bpm)}" + (if (forks.isNotEmpty()) "  ⑂ vertente $forks" else "") + "\n")
            }
            lane.rejoin?.let { r -> plan.firstOrNull { it.toId == r.trackId }?.let { out.append("     ↓ ${tr(it)} → ${name(r.trackId)}\n") } }
        }
        return out.toString()
    }

    // ---- ao vivo: no dia do set, o quadro vira o mapa de onde estou ------------------------------------------------

    /** O quadro está ao vivo: tocar num cartão diz "esta está tocando", em vez de escolher para editar. */
    var live by mutableStateOf(false)
        private set

    /** A que está tocando (linha e posição) e as que já tocaram (faixas). */
    var liveNow by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var livePlayed by mutableStateOf<Set<Int>>(emptySet())
        private set
    private var liveTrack: Int? = null

    fun startLive() {
        stopRehearsal()
        live = true
        liveNow = null
        liveTrack = null
        livePlayed = emptySet()
        liveOrder = emptyList()
    }

    /** A ordem em que as faixas tocaram de verdade (cada toque em "esta está tocando"), para guardar no fim. */
    var liveOrder by mutableStateOf<List<Int>>(emptyList())
        private set

    /** Ao sair do AO VIVO com duas ou mais tocadas: a ordem esperando o DJ dizer se guarda. */
    var playedToSave by mutableStateOf<List<Int>?>(null)

    fun stopLive() {
        live = false
        liveNow = null
        liveTrack = null
        if (liveOrder.size >= 2 && Features.liveMode) playedToSave = liveOrder
        liveOrder = emptyList()
    }

    /**
     * Guarda o que tocou como uma playlist "Tocado dd/MM · nome do set", na ordem real, e a marca como set (conta em "em N
     * sets" e tira das Esquecidas). Captura só o que serve depois, não tudo (Sellen & Whittaker 2010). Devolve o id.
     */
    fun savePlayed(): Int? {
        val order = playedToSave ?: return null
        playedToSave = null
        val v = app.model.view ?: return null
        val setName = rootId()?.let { r -> v.playlists.firstOrNull { it.id == r }?.name }.orEmpty()
        val name = "Tocado " + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")) + if (setName.isNotEmpty()) " · $setName" else ""
        val id = app.model.nextPlaylistId()
        val n = app.model.submitAll(listOf(
            Edit.CreatePlaylist(id, 0, name, false) to "Guardar \"$name\"",
            Edit.AddToPlaylist(id, order) to "O que tocou",
        ))
        if (n != 2) return null
        runCatching { app.setsDir.mkdirs(); File(app.setsDir, "$id.set").createNewFile() }
        app.did("$name · ${order.size} faixas")
        return id
    }

    /** [c] está tocando agora: a de antes vai para as que já tocaram, e a linha dela passa a ser a escolhida. */
    /** O lembrete da troca de [fromId] para [toId] no plano da linha [pid], se o DJ escreveu um. */
    fun noteOf(pid: Int, fromId: Int, toId: Int): String =
        lanePlan(pid).firstOrNull { it.fromId == fromId && it.toId == toId }?.note.orEmpty()

    fun liveTo(c: os.deckbridge.manager.mix.CanvasCard) {
        if (liveOrder.lastOrNull() != c.trackId) liveOrder = liveOrder + c.trackId
        liveTrack?.takeIf { it != c.trackId }?.let { livePlayed = livePlayed + it }
        livePlayed = livePlayed - c.trackId
        liveTrack = c.trackId
        liveNow = c.playlistId to c.index
        focus(c.playlistId)
    }

    /** Os pontos que o DJ marca na onda quando a análise errou: o começo e o drop da que entra, o outro da que sai. */
    enum class Spot(val cue: String) { INTRO(os.deckbridge.logic.Section.INTRO), DROP(os.deckbridge.logic.Section.DROP), OUTRO(os.deckbridge.logic.Section.OUTRO) }

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
        // O lembrete não muda o som: fora da chave, para não montar a prévia de novo.
        return "t|${tr.copy(note = "")}|$sig|$tempo"
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
        val chapters = ArrayList<Chapter>()
        val v = app.model.view
        fun name(id: Int) = v?.track(id)?.title ?: "?"
        val file = publish(out) { tmp ->
            used = MixRenderer.writeSequence(
                tmp, plan.mapIndexed { i, tr -> { render(tr, 8_000, 8_000, tempo = tempos[i])?.audio } }, cancelled = cancelled, progress = progress,
                starts = { n, ms -> chapters += Chapter(ms, "${name(plan[n].fromId)} → ${name(plan[n].toId)}") },
            )
        }
        runCatching { chaptersFile(out).writeText(Chapter.encode(chapters)) }
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

    /** Os capítulos do set montado, ao lado do WAV. */
    private fun chaptersFile(wav: File) = File(wav.parentFile, wav.name + ".capitulos")

    private fun playSetFile(f: File, key: String) {
        val name = app.model.view?.playlists?.firstOrNull { it.id == (rootId() ?: playlistId) }?.name ?: "Set"
        val chapters = runCatching { Chapter.decode(chaptersFile(f).readText()) }.getOrDefault(emptyList())
        app.preview.playSet(f, key, name, chapters, playlistId)
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
        if (ready.isFile) return playSetFile(ready, key)
        val opened = playlistId
        app.background("Montando o set") { j ->
            val f = renderSet(plan, { j.cancelled }, tempos) { j.text = "${it + 1}/${plan.size}" } ?: return@background
            onMain { if (playlistId == opened && opened != null) playSetFile(f, key) }
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

    /** O fim do ensaio (boas, a refazer), mostrado num resumo até a pessoa fechar ou ir refazer. */
    var rehearsalDone by mutableStateOf<Pair<Int, Int>?>(null)

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
            rehearsalDone = marks.count { it == Transition.Mark.GOOD } to marks.count { it == Transition.Mark.REDO }
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

    // ---- facilitadores: trocar uma faixa, duração alvo, duplicar o set ----------------------------------------------

    /** Faixas para pôr no lugar da posição [index] da linha aberta: combinam com a de antes e a de depois. */
    fun replaceChoices(index: Int): List<TrackItem> {
        val v = app.model.view ?: return emptyList()
        val line = entries()
        val busy = line.toSet() + tree().flatMap { v.entries[it.playlistId].orEmpty() } + (rootId()?.let { v.entries[it].orEmpty() } ?: emptyList()) + hidden
        val prev = (line.getOrNull(index - 1) ?: if (index == 0) branch?.fromTrackId else null)?.let { v.track(it) }
        val next = (line.getOrNull(index + 1) ?: if (index == line.lastIndex) branch?.rejoinTrackId else null)?.let { v.track(it) }
        return os.deckbridge.manager.mix.SetDoctor.replacements(v, prev, next, busy)
    }

    /**
     * Põe [t] no lugar da faixa da posição [index] da linha aberta, na mesma posição (um desfazer volta). Não troca uma faixa
     * de onde sai uma vertente (a vertente sairia de outra música).
     */
    fun replaceAt(index: Int, t: TrackItem): Boolean {
        val pid = playlistId ?: return false
        val line = entries()
        val old = line.getOrNull(index) ?: return false
        if (branchesAt(index).isNotEmpty()) {
            app.notice = os.deckbridge.manager.Banner("Dessa faixa sai uma vertente: troque a vertente de lugar antes", true)
            return false
        }
        val order = line.toMutableList().apply { set(index, t.id) }
        val oldTitle = app.model.view?.track(old)?.title.orEmpty()
        val edits = listOf(
            Edit.RemoveFromPlaylist(pid, listOf(old)) to "Tirar \"$oldTitle\"",
            Edit.AddToPlaylist(pid, listOf(t.id) + if (old in order) listOf(old) else emptyList()) to "Pôr \"${t.title}\"",
            Edit.ReorderPlaylist(pid, order) to "Ordem do set",
        )
        if (app.model.submitAll(edits) != edits.size) return false
        app.did("⇄ $oldTitle → ${t.title}")
        return true
    }

    private fun goalFile(root: Int) = File(app.setsDir, "$root.goal")

    /** Quanto o DJ quer que o set dure (minutos), ou null. Mostrado junto da duração: "≈1:48 / 2:00". */
    var goalMinutes by mutableStateOf<Int?>(null)
        private set

    fun loadGoal() {
        goalMinutes = rootId()?.let { runCatching { goalFile(it).readText().trim().toInt() }.getOrNull() }
    }

    fun setGoal(minutes: Int?) {
        val root = rootId() ?: return
        goalMinutes = minutes
        runCatching {
            app.setsDir.mkdirs()
            if (minutes == null) goalFile(root).delete() else goalFile(root).writeText(minutes.toString())
        }
    }

    /**
     * Uma cópia do set (a linha principal, as trocas ajustadas, o master, a pool e a duração alvo) com outro nome, para
     * testar mudanças sem mexer no original. As vertentes não vão junto. Abre a cópia e devolve o id.
     */
    fun duplicate(): Int? {
        val v = app.model.view ?: return null
        val root = rootId() ?: return null
        val name = v.playlists.firstOrNull { it.id == root }?.name ?: return null
        val taken = v.playlists.map { it.name }.toSet()
        val copyName = (2..99).map { "$name (v$it)" }.firstOrNull { it !in taken } ?: "$name (cópia)"
        val ids = v.entries[root].orEmpty().filter { v.track(it) != null }
        val id = app.model.nextPlaylistId()
        val n = app.model.submitAll(listOf(
            Edit.CreatePlaylist(id, 0, copyName, false) to "Copiar \"$name\"",
            Edit.AddToPlaylist(id, ids) to "Faixas de \"$copyName\"",
        ))
        if (n != 2) return null
        runCatching {
            for (ext in listOf("properties", "master", "pool", "goal")) {
                val from = File(app.setsDir, "$root.$ext")
                if (from.isFile) from.copyTo(File(app.setsDir, "$id.$ext"), overwrite = true)
            }
        }
        val hadBranches = tree().isNotEmpty()
        open(id)
        app.did("$copyName · cópia para testar" + if (hadBranches) " (sem as vertentes)" else "")
        return id
    }

    // ---- show: pronto pro show, arrumar (análise e cues em lote), pedido da pista ------------------------------------

    /** Todas as faixas do set, na ordem do quadro (a linha e as vertentes; o ponto de virada conta uma vez, na linha dele). */
    fun setTrackIds(): List<Int> = canvas()?.lanes?.flatMap { lane -> lane.cards.map { it.trackId } } ?: ids()

    private fun audioFile(t: TrackItem) = File(app.root, t.filePath.replace('\\', '/').trimStart('/'))

    /** A lista "Pronto pro show?" do set aberto (ver [os.deckbridge.manager.smart.ShowCheck]). Lê as grades: fora da tela. */
    fun showItems(): List<os.deckbridge.manager.smart.ShowCheck.Item> {
        val v = app.model.view ?: return emptyList()
        val lanes = canvas()?.lanes.orEmpty()
        val plans = lanes.flatMap { lanePlan(it.playlistId) }.distinctBy { it.fromId to it.toId }
        return os.deckbridge.manager.smart.ShowCheck.check(
            v, setTrackIds(), hasAnalysis = { app.model.hasAnalysis(it) }, audioExists = { audioFile(it).isFile },
            cueNames = { t -> grid(t)?.cues?.map { it.comment }?.toSet().orEmpty() },
            issues = issuePairs().size, unrehearsed = plans.count { it.mark != Transition.Mark.GOOD },
        )
    }

    /**
     * Arruma o que dá sozinho, em segundo plano e com "n/N": analisa as faixas sem análise (grade, ondas e cues) e põe
     * cues de memória "Intro", "Drop" e "Outro" onde faltam, pela análise de estrutura do app. Não apaga nem move cue
     * nenhum do DJ; tudo vai junto para a fila (um desfazer volta). A automação faz e mostra, o DJ confere e desfaz
     * (Parasuraman, Sheridan & Wickens 2000).
     */
    fun prepareForShow(ids: List<Int>) {
        val v = app.model.view ?: return
        val todo = ids.distinct().mapNotNull { v.track(it) }.filter { audioFile(it).isFile }
        if (todo.isEmpty()) return
        app.background("Preparando para o show") { job ->
            val edits = ArrayList<Pair<Edit, String>>()
            var cuesAdded = 0
            todo.forEachIndexed { n, t ->
                if (job.cancelled) return@background
                job.text = "${n + 1}/${todo.size}: ${t.title}"
                val p = runCatching { os.deckbridge.manager.importer.ImportPrep.prepare(audioFile(t)) }.getOrNull() ?: return@forEachIndexed
                val structure = p.suggestedCues.filter { c -> os.deckbridge.manager.smart.ShowCheck.STRUCTURE.any { it.equals(c.comment, ignoreCase = true) } }
                if (!app.model.hasAnalysis(t)) {
                    edits += Edit.AttachAnalysis(t.id, p, p.suggestedCues, t.bpm <= 0.0, t.key.isBlank()) to "Análise de \"${t.title}\""
                    cuesAdded += structure.size
                } else {
                    val have = grid(t)?.cues ?: runCatching { app.model.trackGrid(t).cues }.getOrDefault(emptyList())
                    val missing = structure.filter { c -> have.none { it.comment.equals(c.comment, ignoreCase = true) } }
                        .map { os.deckbridge.manager.anlz.Cue(0, it.timeMs, comment = it.comment) }
                    if (missing.isNotEmpty()) {
                        edits += Edit.SetCues(t.id, have + missing) to "Cues de estrutura em \"${t.title}\""
                        cuesAdded += missing.size
                    }
                }
            }
            app.onMain {
                val n = if (edits.isEmpty()) 0 else app.model.submitAll(edits)
                forgetGrids()
                app.did(if (n == 0) "Nada a arrumar" else "Arrumado: $n faixa(s), $cuesAdded cue(s) de estrutura · confira no MIX")
            }
        }
    }

    /**
     * Pedido da pista: de [from] até [target], as faixas da biblioteca que fazem a ponte (até 3), fora das que já
     * tocaram e das que estão no set. Lista vazia: combina direto; null: não há caminho.
     */
    fun requestRoute(from: TrackItem, target: TrackItem): List<TrackItem>? {
        val v = app.model.view ?: return null
        val busy = livePlayed + (liveTrack?.let { setOf(it) } ?: emptySet()) + hidden
        return os.deckbridge.manager.smart.Request.route(v, from, target, busy)
    }

    /** A faixa que está tocando agora no AO VIVO. */
    fun liveTrackItem(): TrackItem? = liveTrack?.let { app.model.view?.track(it) }

    /**
     * Põe [route] e o pedido [target] na linha da que está tocando, logo depois dela (um desfazer volta). O quadro passa a
     * mostrar o caminho; as trocas novas são automáticas.
     */
    fun insertRoute(route: List<TrackItem>, target: TrackItem): Boolean {
        val (pid, index) = liveNow ?: return false
        val v = app.model.view ?: return false
        val line = v.entries[pid].orEmpty().filter { v.track(it) != null }
        val add = (route + target).map { it.id }.filter { it !in line }
        if (add.isEmpty()) return false
        val at = (index + 1).coerceAtMost(line.size)
        val order = line.take(at) + add + line.drop(at)
        val n = app.model.submitAll(listOf(
            Edit.AddToPlaylist(pid, add) to "Pedido: ${target.title}",
            Edit.ReorderPlaylist(pid, order) to "Caminho até o pedido",
        ))
        if (n != 2) return false
        app.did("Pedido no set: ${add.size} faixa(s) até ${target.title}")
        return true
    }
}
