package os.deckbridge.manager.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import os.deckbridge.manager.importer.AudioIo
import os.deckbridge.manager.mix.MixRenderer
import os.deckbridge.manager.mix.SegmentReader
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.Stereo
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

    private fun render(tr: Transition, lead: Int, tail: Int): Stereo? {
        val v = app.model.view ?: return null
        val a = v.track(tr.fromId) ?: return null
        val b = v.track(tr.toId) ?: return null
        return MixRenderer.transition(reader(a), a.bpm, reader(b), b.bpm, tr, lead, tail)
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
        val s = render(tr, 12_000, 12_000) ?: throw IllegalStateException("não consegui ler o áudio das duas faixas")
        return publish(out) { it.writeBytes(MixRenderer.wav(s)) }
    }

    /** Todas as transições em sequência (8 s antes e 8 s depois de cada). Null se cancelado. Bloqueante. */
    fun renderSet(plan: List<Transition>, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): File? {
        val out = cacheFile(setKey(plan))
        if (out.isFile) return out
        var used = 0
        val file = publish(out) { tmp -> used = MixRenderer.writeSequence(tmp, plan.map { tr -> { render(tr, 8_000, 8_000) } }, cancelled = cancelled, progress = progress) }
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
