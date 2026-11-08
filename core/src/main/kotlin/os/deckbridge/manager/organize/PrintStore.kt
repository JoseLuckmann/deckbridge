package os.deckbridge.manager.organize

import java.io.File
import os.deckbridge.logic.AudioPrint
import os.deckbridge.manager.importer.AudioIo
import os.deckbridge.manager.pdb.TrackItem

/**
 * As impressões digitais das faixas da biblioteca, guardadas em [dir] (fora do pendrive) e medidas na primeira vez que
 * alguém pede (lê o áudio inteiro: chamar fora da thread da tela). Muda o arquivo da faixa, mede de novo.
 */
class PrintStore(private val dir: File, private val audio: (TrackItem) -> File) {
    private val memory = HashMap<String, AudioPrint?>()

    private fun name(t: TrackItem) = "${t.id}-${Integer.toHexString(t.filePath.hashCode())}.txt"

    /** A guardada, sem ler áudio (null se ainda não foi medida). */
    fun cached(t: TrackItem): AudioPrint? {
        val n = name(t)
        synchronized(memory) { if (memory.containsKey(n)) return memory[n] }
        val f = File(dir, n)
        val p = if (f.isFile) AudioPrint.decode(f.readText()) else null
        if (p != null) synchronized(memory) { memory[n] = p }
        return p
    }

    /** A guardada ou medida agora. Null se o áudio não pôde ser lido. */
    fun get(t: TrackItem): AudioPrint? {
        cached(t)?.let { return it }
        val n = name(t)
        synchronized(memory) { if (memory.containsKey(n)) return memory[n] }
        val p = runCatching { AudioIo.mono(audio(t)).let { AudioPrint.of(it.samples, it.rate) } }.getOrNull()
        synchronized(memory) { memory[n] = p }
        if (p != null) runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$n.novo")
            tmp.writeText(p.encode())
            if (!tmp.renameTo(File(dir, n))) { File(dir, n).delete(); tmp.renameTo(File(dir, n)) }
        }
        return p
    }

    /** Já medida? (para dizer quanto falta ouvir.) */
    fun has(t: TrackItem) = cached(t) != null

    /**
     * A faixa da biblioteca que tem o mesmo áudio que [print] (de uma faixa de [durationSec] segundos chegando), entre as
     * de duração até [toleranceSec] diferente; null se nenhuma.
     */
    fun sameAs(print: AudioPrint, durationSec: Int, tracks: List<TrackItem>, toleranceSec: Int = 8): TrackItem? =
        tracks.filter { kotlin.math.abs(it.durationSeconds - durationSec) <= toleranceSec }
            .firstOrNull { t -> get(t)?.let { AudioPrint.distance(print, it) < AudioPrint.SAME } == true }
}
