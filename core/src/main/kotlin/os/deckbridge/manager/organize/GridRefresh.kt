package os.deckbridge.manager.organize

import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import os.deckbridge.logic.analyzeTrack
import os.deckbridge.manager.anlz.Beat
import os.deckbridge.manager.importer.AudioIo
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.volume.FileOverlay

/** O que refazer a grade de uma faixa mudaria. [edit] é null quando a grade atual já está igual à nova. */
class GridChange(
    val track: TrackItem,
    val bpmBefore: Double,
    val bpmAfter: Double,
    /** Quanto a grade anda (mediana, ms; positivo = para depois). */
    val shiftMs: Double,
    /** O tempo 1 de cada compasso ficou o da grade atual (só a posição fina mudou). */
    val keptBars: Boolean,
    val edit: Edit.ReplaceGrid?,
)

/**
 * Refazer grades com o analisador atual, sem mexer em mais nada da análise (cues, ondas, tom ficam). O áudio é só lido.
 *
 * Quando a grade nova só desloca a atual (menos de um quarto de batida), cada compasso continua começando onde começava
 * (o tempo 1 é o da grade atual, que o DJ pode ter acertado à mão); assim as frases e os cues em início de compasso
 * continuam batendo. Se a grade nova for outra coisa (andamento ou fase muito diferentes), fica a contagem da análise.
 */
object GridRefresh {
    /** Diferenças abaixo disto contam como "já estava certa". */
    private const val SAME_MS = 1.0
    private const val SAME_BPM = 0.005

    /** Lê o áudio de [track] em [root] e analisa só o necessário para a grade. Pesado: rode fora da interface. */
    fun analyze(root: File, track: TrackItem): Pair<List<Beat>, Double>? {
        val rel = track.filePath.replace('\\', '/').trimStart('/')
        val real = FileOverlay(root).onDisk(rel) ?: return null
        val mono = runCatching { AudioIo.mono(File(root, real)) }.getOrNull() ?: return null
        if (mono.samples.size < mono.rate * 2) return null
        val read = analyzeTrack(mono.samples, mono.rate)
        if (read.beats.size < 8 || read.bpm <= 0.0) return null
        return read.beats.map { Beat(it.beatInBar, (it.bpm * 100).roundToInt().coerceIn(1, 65535), it.timeMs) } to read.bpm
    }

    /** Compara a grade nova [fresh] com a atual [current] e monta a troca (mantendo o tempo 1 quando dá). */
    fun change(track: TrackItem, current: List<Beat>, fresh: List<Beat>, freshBpm: Double): GridChange {
        val period = 60000.0 / freshBpm
        // Para cada batida nova, a atual mais perto (as duas listas estão em ordem).
        val matched = ArrayList<Pair<Int, Beat>>()
        var j = 0
        for ((k, b) in fresh.withIndex()) {
            while (j + 1 < current.size && abs(current[j + 1].timeMs - b.timeMs) <= abs(current[j].timeMs - b.timeMs)) j++
            val near = current.getOrNull(j) ?: continue
            if (abs(near.timeMs - b.timeMs) < period / 4) matched += k to near
        }
        val keep = current.isNotEmpty() && matched.size >= fresh.size / 2
        val beats = if (keep) {
            // A contagem que mais concorda com a atual: tempo no compasso = (k + o) mod 4 + 1.
            val votes = IntArray(4)
            for ((k, near) in matched) votes[Math.floorMod(near.inBar - 1 - k, 4)]++
            val o = votes.indices.maxByOrNull { votes[it] }!!
            fresh.mapIndexed { k, b -> b.copy(inBar = (k + o) % 4 + 1) }
        } else fresh
        val shifts = matched.map { (k, near) -> (fresh[k].timeMs - near.timeMs).toDouble() }.sorted()
        val shift = if (shifts.isEmpty()) Double.NaN else shifts[shifts.size / 2]
        val worst = matched.maxOfOrNull { (k, near) -> abs(fresh[k].timeMs - near.timeMs).toDouble() } ?: Double.MAX_VALUE
        val bpmBefore = current.firstOrNull()?.tempo100?.div(100.0) ?: track.bpm
        // Igual: todas as batidas casam a até 1 ms (a última pode sobrar de um lado), mesmo andamento, mesma contagem.
        val same = keep && worst <= SAME_MS && abs(fresh.size - current.size) <= 1 && matched.size >= minOf(fresh.size, current.size) - 1 &&
            abs(freshBpm - track.bpm) < SAME_BPM && matched.all { (k, near) -> beats[k].inBar == near.inBar }
        return GridChange(track, bpmBefore, freshBpm, shift, keep, if (same) null else Edit.ReplaceGrid(track.id, beats, freshBpm))
    }

    /** Sugestão para a revisão (desktop) a partir de uma troca. */
    fun suggestion(c: GridChange): Suggestion? {
        val e = c.edit ?: return null
        val parts = buildList {
            if (abs(c.bpmAfter - c.bpmBefore) >= SAME_BPM) add("%.2f → %.2f BPM".format(c.bpmBefore, c.bpmAfter))
            if (!c.shiftMs.isNaN() && abs(c.shiftMs) >= SAME_MS) add("%+.0f ms".format(c.shiftMs))
            add(if (c.keptBars) "compassos mantidos" else "contagem de compassos nova")
        }
        return Suggestion(Rule.GRID, listOf(c.track.id), "\"${c.track.title}\"", parts.joinToString(", "), e)
    }
}
