package os.deckbridge.manager.mobile

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
class SetDesk(private val app: ManagerApp) {
    var playlistId by mutableStateOf<Int?>(null)
        private set

    /** Transições que o DJ ajustou (as outras são automáticas, calculadas da ordem atual). */
    var saved by mutableStateOf<List<Transition>>(emptyList())
        private set

    private val grids = HashMap<Int, TrackGrid?>()

    private fun file(id: Int) = File(app.root.parentFile, "sets/$id.properties")

    private val branchesFile get() = File(app.root.parentFile, "sets/branches.properties")
    private fun masterFile(id: Int) = File(app.root.parentFile, "sets/$id.master")

    /** Os ramos de todos os sets (as playlists de ramo e de onde saem). */
    var branches by mutableStateOf<List<SetBranch>>(emptyList())
        private set

    /** O master do set aberto: null é automático (segue as faixas), um número é fixo. */
    var masterFixed by mutableStateOf<Double?>(null)
        private set

    fun open(id: Int) {
        synchronized(grids) { grids.clear() }
        branches = SetBranches.load(branchesFile)
        saved = SetPlanner.load(file(id))
        playlistId = id
        masterFixed = SetTempo.loadMode(masterFile(rootId() ?: id))
    }

    /** O ramo aberto, se a playlist aberta é um ramo de outro set. */
    val branch: SetBranch? get() {
        val v = app.model.view ?: return null
        return SetBranches.alive(v, branches).firstOrNull { it.playlistId == playlistId }
    }

    /** O set principal: o próprio, ou aquele de onde o ramo sai. */
    fun rootId(): Int? = branch?.mainId ?: playlistId

    /** Os ramos que saem do set aberto (só do principal). */
    fun branchesHere(): List<SetBranch> {
        val v = app.model.view ?: return emptyList()
        if (branch != null) return emptyList()
        return SetBranches.alive(v, branches).filter { it.mainId == playlistId }
    }

    /** A letra do ramo (B, C…), pela ordem em que foram criados no set. */
    fun letterOf(b: SetBranch): Char {
        val v = app.model.view ?: return '?'
        val mine = SetBranches.alive(v, branches).filter { it.mainId == b.mainId }
        return SetBranches.letter(mine.indexOfFirst { it.playlistId == b.playlistId }.coerceAtLeast(0))
    }

    fun openMain() {
        val main = branch?.mainId ?: return
        app.preview.stop()
        rehearsal = null
        open(main)
    }

    fun close() {
        app.preview.stop()
        rehearsal = null
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

    /** O master em cada posição de [ids]. Num ramo, começa no master que o set principal tem no ponto de virada. */
    fun masters(ids: List<Int> = ids()): List<Double> {
        val v = app.model.view ?: return emptyList()
        val natives = ids.map { v.track(it)?.bpm ?: 0.0 }
        val b = branch ?: return SetTempo.plan(natives, masterFixed)
        val mainIds = v.entries[b.mainId].orEmpty()
        val mainMasters = SetTempo.plan(mainIds.map { v.track(it)?.bpm ?: 0.0 }, masterFixed)
        val start = mainMasters.getOrNull(mainIds.indexOf(b.fromTrackId)) ?: natives.firstOrNull() ?: 120.0
        return listOf(start) + SetTempo.plan(natives.drop(1), masterFixed, start)
    }

    /** O master da troca [tr] na linha aberta (as duas faixas tocam nele). */
    fun tempoFor(tr: Transition, ids: List<Int> = ids()): Double? = masters(ids).getOrNull(ids.indexOf(tr.fromId))

    fun setMaster(fixed: Double?) {
        val root = rootId() ?: return
        masterFixed = fixed
        runCatching { SetTempo.saveMode(masterFile(root), fixed) }
        app.did(if (fixed == null) "Master automático" else "Master %.1f".format(fixed))
    }

    /**
     * Cria um ramo a partir de [fromTrackId]: [count] faixas no rumo [shape] e, se [rejoin], a volta à linha. Vira uma
     * playlist "Set ⑂ B" (vai para o pendrive). Abre o SET do ramo e devolve o id da playlist, ou null.
     */
    fun createBranch(fromTrackId: Int, count: Int, shape: SetShape, rejoin: Boolean): Int? {
        val v = app.model.view ?: return null
        val main = playlistId ?: return null
        if (branch != null) return null
        val line = entries()
        val mine = SetBranches.alive(v, branches).filter { it.mainId == main }
        val taken = mine.flatMap { v.entries[it.playlistId].orEmpty() }.toSet()
        val (ids, back) = SetBuilder.branch(v, line, fromTrackId, count, shape, taken = taken, rejoin = rejoin)
        if (ids.isEmpty()) {
            app.notice = os.deckbridge.manager.Banner("Nenhuma faixa fora do set combina daqui", true)
            return null
        }
        val letter = SetBranches.letter(mine.size)
        val name = "${v.playlists.first { it.id == main }.name} - ramo $letter"
        val id = app.model.nextPlaylistId()
        val n = app.model.submitAll(listOf(
            Edit.CreatePlaylist(id, 0, name, false) to "Ramo \"$name\"",
            Edit.AddToPlaylist(id, ids) to "Faixas do ramo \"$name\"",
        ))
        if (n != 2) return null
        branches = SetBranches.load(branchesFile) + SetBranch(id, main, fromTrackId, back)
        runCatching { SetBranches.save(branchesFile, branches) }
        app.did("Ramo $letter · ${ids.size} faixas" + if (back != null) " · volta na linha" else "")
        open(id)
        return id
    }

    /** As grades mudaram (refazer grades): lê de novo na próxima vez. */
    fun forgetGrids() = synchronized(grids) { grids.clear() }

    fun grid(t: TrackItem): TrackGrid? = synchronized(grids) {
        grids.getOrPut(t.id) {
            val f = runCatching { app.model.trackFiles(t) }.getOrNull()
            if (f == null || f.beats.isEmpty()) null else TrackGrid(f.beats, f.cues, f.durationMs)
        }
    }

    fun plan(ids: List<Int> = ids()): List<Transition> {
        val v = app.model.view ?: return emptyList()
        return SetPlanner.plan(v, ids, ::grid, keep = saved)
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
        // Um ramo que volta para a linha já tem fim; não há "próxima".
        if (branch?.rejoinTrackId != null) return emptyList()
        val last = ids.lastOrNull()?.let { v.track(it) } ?: return emptyList()
        return SetPlanner.suggestNext(v, last, ids.toSet())
    }

    fun append(t: TrackItem) {
        val id = playlistId ?: return
        if (app.model.submit(Edit.AddToPlaylist(id, listOf(t.id)), "Pôr \"${t.title}\" no set")) app.did("+ ${t.title}")
    }

    private fun reader(t: TrackItem) = SegmentReader { start, len -> AudioIo.segment(File(app.root, t.filePath.replace('\\', '/').trimStart('/')), start, len) }

    private fun render(tr: Transition, lead: Int, tail: Int, waves: Boolean = false): MixPreview? {
        val v = app.model.view ?: return null
        val a = v.track(tr.fromId) ?: return null
        val b = v.track(tr.toId) ?: return null
        return MixRenderer.transition(reader(a), a.bpm, reader(b), b.bpm, tr, lead, tail, waves, tempo = tempoFor(tr) ?: a.bpm)
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
        val key = keyOf(tr)
        worker.execute {
            val result = runCatching {
                val p = synchronized(previews) { previews[key] } ?: render(tr, 12_000, 12_000, waves = true)?.also { synchronized(previews) { previews[key] = it } }
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
            app.model.apply(null) { j.text = it } ?: throw IllegalStateException(app.model.banner?.text ?: "Não consegui salvar a grade")
            onMain {
                synchronized(grids) { grids.remove(b.id) }
                editMix(fixed)
                app.did("Grade de ${b.title}: %+d ms".format(delta))
            }
        }
    }

    private fun cacheFile(key: String) = File(app.context.cacheDir, "mix/${key.hashCode().toUInt()}.wav")

    private fun keyOf(tr: Transition): String {
        val v = app.model.view
        val sig = listOf(tr.fromId, tr.toId).joinToString { id -> v?.track(id)?.let { "${it.filePath}:${it.bpm}" } ?: "" }
        return "t|$tr|$sig|${tempoFor(tr)}"
    }

    fun keyFor(tr: Transition) = keyOf(tr)

    /** Monta a prévia de [tr] num WAV (ou devolve a que já está montada). Bloqueante: chamar fora da thread da tela. */
    fun renderTransition(tr: Transition): File {
        val out = cacheFile(keyOf(tr))
        if (out.isFile) return out
        val s = render(tr, 12_000, 12_000)?.audio ?: throw IllegalStateException("não consegui ler o áudio das duas faixas")
        return publish(out) { it.writeBytes(MixRenderer.wav(s)) }
    }

    /** Todas as transições em sequência (8 s antes e 8 s depois de cada). Null se cancelado. Bloqueante. */
    fun renderSet(plan: List<Transition>, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): File? {
        val out = cacheFile(setKey(plan))
        if (out.isFile) return out
        var used = 0
        val file = publish(out) { tmp -> used = MixRenderer.writeSequence(tmp, plan.map { tr -> { render(tr, 8_000, 8_000)?.audio } }, cancelled = cancelled, progress = progress) }
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

    fun setKey(plan: List<Transition>) = "all|" + plan.joinToString(";") { keyOf(it) }

    private fun onMain(block: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(block)

    /** Monta (se ainda não tem) e toca a prévia de uma transição; tocar de novo para. */
    fun play(tr: Transition) {
        val key = keyOf(tr)
        if (app.preview.playingKey == key) return app.preview.stop()
        val ready = cacheFile(key)
        if (ready.isFile) return app.preview.toggleFile(ready, key, "transição")
        val opened = playlistId
        app.background("Montando prévia") { j ->
            val f = renderTransition(tr)
            // Saiu do SET (ou abriu outro) enquanto montava: não começa a tocar numa tela que já não está aberta.
            if (!j.cancelled) onMain { if (playlistId == opened && opened != null) app.preview.toggleFile(f, key, "transição") }
        }
    }

    /** As transições do set uma depois da outra, para ouvir o set de ponta a ponta. */
    fun playAll(plan: List<Transition>) {
        if (plan.isEmpty()) return
        val key = setKey(plan)
        if (app.preview.playingKey == key) return app.preview.stop()
        val ready = cacheFile(key)
        if (ready.isFile) return app.preview.toggleFile(ready, key, "set")
        val opened = playlistId
        app.background("Montando o set") { j ->
            val f = renderSet(plan, { j.cancelled }) { j.text = "${it + 1}/${plan.size}" } ?: return@background
            onMain { if (playlistId == opened && opened != null) app.preview.toggleFile(f, key, "set") }
        }
    }

    // ---- montar, ordenar, checar e ensaiar ----------------------------------------------------------------------

    fun issues(ids: List<Int>, plan: List<Transition>): List<os.deckbridge.manager.mix.SetIssue> {
        val v = app.model.view ?: return emptyList()
        return os.deckbridge.manager.mix.SetDoctor.check(v, ids, plan, ::grid, masters(ids))
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
