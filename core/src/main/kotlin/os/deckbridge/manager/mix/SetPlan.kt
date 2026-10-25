package os.deckbridge.manager.mix

import java.io.File
import java.util.Properties
import kotlin.math.abs
import os.deckbridge.manager.anlz.Beat
import os.deckbridge.manager.anlz.Cue
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.smart.Smart

/** Como uma faixa entra na outra. */
enum class MixStyle(val title: String) {
    BASS_SWAP("Troca de grave"),
    BLEND("Mistura"),
    ECHO("Eco"),
    CUT("Corte"),
}

/**
 * Uma transição pré-programada: a faixa [fromId] começa a sair em [outMs] (num início de compasso), a [toId] entra a
 * partir de [inMs] no mesmo instante, e as duas tocam juntas por [bars] compassos no andamento da que sai.
 */
data class Transition(val fromId: Int, val toId: Int, val outMs: Int, val inMs: Int, val bars: Int = 16, val style: MixStyle = MixStyle.BASS_SWAP)

/** O que se sabe de uma faixa para planejar: grade e cues (do ANLZ) e a duração. */
class TrackGrid(val beats: List<Beat>, val cues: List<Cue>, val durationMs: Int)

/** Quão bem duas faixas se encaixam: distância de tom na roda (0 igual, 1 vizinha) e diferença de andamento. */
data class Fit(val keyDistance: Int?, val bpmDiffPct: Double) {
    val keyOk get() = keyDistance != null && keyDistance <= 1
    val bpmOk get() = abs(bpmDiffPct) <= 6.0
    val good get() = keyOk && bpmOk
}

object SetPlanner {
    fun barMs(bpm: Double): Double = if (bpm > 0) 4 * 60000.0 / bpm else 2000.0

    private fun downbeats(g: TrackGrid?): List<Int> = g?.beats?.filter { it.inBar == 1 }?.map { it.timeMs }.orEmpty()

    /** O início de compasso mais próximo de [ms] (ou o próprio [ms], se a faixa não tem grade). */
    fun snap(g: TrackGrid?, ms: Int): Int = downbeats(g).minByOrNull { abs(it - ms) } ?: ms

    /**
     * Saída automática: o cue "Outro" (da segunda metade) se houver espaço depois dele; senão, [bars] compassos antes do fim (mais dois de
     * folga), sempre num início de compasso.
     */
    fun defaultOut(t: TrackItem, g: TrackGrid?, bars: Int): Int {
        val dur = g?.durationMs?.takeIf { it > 0 } ?: (t.durationSeconds * 1000)
        val len = (bars * barMs(t.bpm)).toInt()
        // Um "Outro" na primeira metade é análise errada (ou faixa curta demais): não serve de saída.
        val outro = g?.cues?.firstOrNull { it.comment.equals("Outro", ignoreCase = true) && it.timeMs >= dur / 2 }?.timeMs
        val wanted = if (outro != null && outro + len <= dur) outro else dur - len - (2 * barMs(t.bpm)).toInt()
        return snap(g, wanted.coerceAtLeast(0))
    }

    /** Entrada automática: o primeiro início de compasso (o começo da faixa, se não há grade). */
    fun defaultIn(g: TrackGrid?): Int = downbeats(g).firstOrNull() ?: 0

    fun fit(a: TrackItem, b: TrackItem): Fit {
        val ka = Smart.camelot(a.key)
        val kb = Smart.camelot(b.key)
        val dist = if (ka != null && kb != null) Smart.harmonicDistance(ka, kb) else null
        val diff = if (a.bpm > 0 && b.bpm > 0) (b.bpm - a.bpm) / a.bpm * 100 else 0.0
        return Fit(dist, diff)
    }

    /** Transições automáticas para a ordem [ids], mantendo as que já existem para o mesmo par. */
    fun plan(view: LibraryView, ids: List<Int>, grids: (TrackItem) -> TrackGrid?, keep: List<Transition> = emptyList(), bars: Int = 16): List<Transition> =
        ids.zipWithNext().mapNotNull { (a, b) ->
            keep.firstOrNull { it.fromId == a && it.toId == b } ?: run {
                val ta = view.track(a) ?: return@mapNotNull null
                val tb = view.track(b) ?: return@mapNotNull null
                Transition(a, b, defaultOut(ta, grids(ta), bars), defaultIn(grids(tb)), bars)
            }
        }

    /**
     * Próximas faixas sugeridas depois de [last]: tom igual ou vizinho e andamento a até 6% primeiro, depois as de nota
     * mais alta. Fora as que já estão no set.
     */
    fun suggestNext(view: LibraryView, last: TrackItem, exclude: Set<Int>, limit: Int = 3): List<TrackItem> =
        view.tracks.filter { it.id !in exclude && it.id != last.id }
            .map { it to fit(last, it) }
            .sortedWith(compareBy<Pair<TrackItem, Fit>>({ !it.second.good }, { it.second.keyDistance ?: 9 }, { abs(it.second.bpmDiffPct) }, { -it.first.rating }))
            .take(limit).map { it.first }

    /** Move [ms] por [bars] compassos (negativo volta), no compasso mais perto, dentro da faixa. */
    fun nudge(t: TrackItem, g: TrackGrid?, ms: Int, bars: Int): Int {
        val dur = g?.durationMs?.takeIf { it > 0 } ?: (t.durationSeconds * 1000)
        return snap(g, (ms + bars * barMs(t.bpm)).toInt().coerceIn(0, dur.coerceAtLeast(0)))
    }

    /**
     * Duração aproximada do set tocado como planejado: cada faixa do ponto de entrada ao de saída, e a última até o fim.
     */
    fun lengthMs(view: LibraryView, ids: List<Int>, transitions: List<Transition>): Int = ids.withIndex().sumOf { (n, id) ->
        val t = view.track(id) ?: return@sumOf 0
        val start = if (n > 0) transitions.firstOrNull { it.fromId == ids[n - 1] && it.toId == id }?.inMs ?: 0 else 0
        val end = if (n < ids.lastIndex) transitions.firstOrNull { it.fromId == id && it.toId == ids[n + 1] }?.outMs ?: (t.durationSeconds * 1000) else t.durationSeconds * 1000
        (end - start).coerceAtLeast(0)
    }

    // ---- guardar o plano (fora do banco do rekordbox, que não tem onde pôr isso) -----------------------------------

    fun save(file: File, transitions: List<Transition>) {
        val p = Properties()
        p.setProperty("count", transitions.size.toString())
        transitions.forEachIndexed { n, t ->
            p.setProperty("$n.from", t.fromId.toString())
            p.setProperty("$n.to", t.toId.toString())
            p.setProperty("$n.out", t.outMs.toString())
            p.setProperty("$n.in", t.inMs.toString())
            p.setProperty("$n.bars", t.bars.toString())
            p.setProperty("$n.style", t.style.name)
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".novo")
        tmp.outputStream().use { p.store(it, "DeckBridge: transicoes do set") }
        tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
    }

    fun load(file: File): List<Transition> {
        if (!file.isFile) return emptyList()
        val p = Properties().apply { file.inputStream().use { load(it) } }
        val n = p.getProperty("count")?.toIntOrNull() ?: return emptyList()
        return (0 until n).mapNotNull { i ->
            runCatching {
                Transition(
                    p.getProperty("$i.from").toInt(), p.getProperty("$i.to").toInt(), p.getProperty("$i.out").toInt(), p.getProperty("$i.in").toInt(),
                    p.getProperty("$i.bars").toInt(), MixStyle.valueOf(p.getProperty("$i.style")),
                )
            }.getOrNull()
        }
    }
}
