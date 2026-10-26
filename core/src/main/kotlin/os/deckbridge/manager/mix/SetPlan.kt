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
data class Transition(
    val fromId: Int, val toId: Int, val outMs: Int, val inMs: Int, val bars: Int = 16, val style: MixStyle = MixStyle.BASS_SWAP,
    /** Master Tempo: a que entra muda de andamento sem mudar de tom. */
    val masterTempo: Boolean = true,
    /** Sync fino: corrige o resto de desalinhamento entre os bumbos que as grades deixam. */
    val phaseLock: Boolean = true,
    /** O que o DJ achou ao ensaiar: boa, refazer, ou ainda não ouviu. */
    val mark: Mark? = null,
) {
    enum class Mark { GOOD, REDO }
}

/** O que se sabe de uma faixa para planejar: grade e cues (do ANLZ) e a duração. */
class TrackGrid(val beats: List<Beat>, val cues: List<Cue>, val durationMs: Int)

/** Quão bem duas faixas se encaixam: distância de tom na roda (0 igual, 1 vizinha) e diferença de andamento. */
data class Fit(val keyDistance: Int?, val bpmDiffPct: Double) {
    val keyOk get() = keyDistance != null && keyDistance <= 1
    /** O master manda: esticar uma faixa até 10% (116 tocada em 128) é tocar normal, com Master Tempo. */
    val bpmOk get() = abs(bpmDiffPct) <= SetTempo.COMFORT_PCT
    val good get() = keyOk && bpmOk
}

/**
 * O master do set: o BPM em que cada faixa toca. Quem manda é o master, não o BPM da faixa — tocar uma de 116 a 128 é
 * normal. O que não pode é o master dar saltos: no automático ele segue as faixas, mas anda no máximo [STEP_PCT] por
 * troca; fixo, fica num valor só. Cada faixa estica (ou encolhe) do BPM dela até o master.
 */
object SetTempo {
    /** Até aqui esticar é tocar normal. */
    const val COMFORT_PCT = 10.0

    /** O limite do pitch do player (±16%): passou disso, a faixa não chega no master. */
    const val MAX_PCT = 16.0

    /** Quanto o master pode andar numa troca sem soar brusco. */
    const val STEP_PCT = 2.5

    /** Quanto a faixa estica para tocar em [master] (positivo: mais rápida que o BPM dela). */
    fun stretchPct(native: Double, master: Double): Double = if (native > 0) (master / native - 1) * 100 else 0.0

    /**
     * O master de cada posição: [fixed] em todas, ou seguindo os BPMs ([natives]) a passos de até [STEP_PCT], a partir
     * de [start] (o master de onde a linha começa, num ramo) ou do BPM da primeira.
     */
    fun plan(natives: List<Double>, fixed: Double? = null, start: Double? = null): List<Double> {
        if (natives.isEmpty()) return emptyList()
        if (fixed != null) return natives.map { fixed }
        val out = ArrayList<Double>(natives.size)
        // Sem ponto de partida, o master começa no BPM da primeira faixa (com andamento).
        var m = start ?: natives.firstOrNull { it > 0 } ?: 120.0
        for (n in natives) {
            if (n > 0) {
                val step = m * STEP_PCT / 100
                m += (n - m).coerceIn(-step, step)
            }
            out += m
        }
        return out
    }

    /** "auto" ou o BPM fixo, do arquivo do set. */
    fun loadMode(file: File): Double? = runCatching { file.readText().trim().toDoubleOrNull() }.getOrNull()

    fun saveMode(file: File, fixed: Double?) {
        file.parentFile?.mkdirs()
        file.writeText(fixed?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "auto")
    }
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

    /** Quantos semitons o tom muda tocando a [speed] sem Master Tempo. */
    fun semitones(speed: Double): Double = if (speed > 0) 12 * kotlin.math.ln(speed) / kotlin.math.ln(2.0) else 0.0

    /** O tom (Camelot) depois de subir [semi] semitons: cada semitom anda 7 casas na roda. */
    fun shiftKey(key: Pair<Int, Char>, semi: Int): Pair<Int, Char> = (Math.floorMod(key.first - 1 + 7 * semi, 12) + 1) to key.second

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
            p.setProperty("$n.mt", t.masterTempo.toString())
            p.setProperty("$n.lock", t.phaseLock.toString())
            t.mark?.let { p.setProperty("$n.mark", it.name) }
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
                    p.getProperty("$i.bars").toInt(), MixStyle.valueOf(p.getProperty("$i.style")), p.getProperty("$i.mt")?.toBooleanStrictOrNull() ?: true,
                    p.getProperty("$i.lock")?.toBooleanStrictOrNull() ?: true,
                    p.getProperty("$i.mark")?.let { m -> runCatching { Transition.Mark.valueOf(m) }.getOrNull() },
                )
            }.getOrNull()
        }
    }
}
