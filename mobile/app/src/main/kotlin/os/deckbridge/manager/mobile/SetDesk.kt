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

    fun open(id: Int) {
        synchronized(grids) { grids.clear() }
        saved = SetPlanner.load(file(id))
        playlistId = id
    }

    fun close() {
        app.preview.stop()
        playlistId = null
    }

    fun ids(): List<Int> {
        val v = app.model.view ?: return emptyList()
        return v.entries[playlistId ?: return emptyList()].orEmpty().filter { v.track(it) != null }
    }

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
        return MixRenderer.transition(reader(a), a.bpm, reader(b), b.bpm, tr, lead, tail, waves)
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
    fun editMix(tr: Transition) {
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
        return "t|$tr|$sig"
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
        app.background("Montando prévia") { j ->
            val f = renderTransition(tr)
            if (!j.cancelled) onMain { app.preview.toggleFile(f, key, "transição") }
        }
    }

    /** As transições do set uma depois da outra, para ouvir o set de ponta a ponta. */
    fun playAll(plan: List<Transition>) {
        if (plan.isEmpty()) return
        val key = setKey(plan)
        if (app.preview.playingKey == key) return app.preview.stop()
        val ready = cacheFile(key)
        if (ready.isFile) return app.preview.toggleFile(ready, key, "set")
        app.background("Montando o set") { j ->
            val f = renderSet(plan, { j.cancelled }) { j.text = "${it + 1}/${plan.size}" } ?: return@background
            onMain { app.preview.toggleFile(f, key, "set") }
        }
    }
}
