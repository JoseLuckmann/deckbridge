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
    /** O ritmo da troca: rápida, média ou lenta (o quanto os agudos, o grave e o volume demoram para passar). */
    val pace: Pace = Pace.NORMAL,
    /** Troca de grave: em que ponto da transição o grave passa de uma para a outra (0,5 = no meio; 1 = no fim, no drop). */
    val swapAt: Double = 0.5,
    /** Troca de grave: 0 vira o grave de uma vez no ponto; N passa aos poucos em N compassos que terminam no ponto. */
    val swapBars: Int = 0,
    /** Lembrete do DJ para a hora da troca ("quando o vocal entrar, tiro o grave"): aparece no MIX, na cola e ao vivo. */
    val note: String = "",
    /**
     * Sync à mão, em ms da prévia, somado ao sync fino: positivo adianta a que entra (como empurrar o prato para a frente),
     * negativo atrasa. Para quando a marcação errou um pouco e o ouvido acha o encaixe.
     */
    val nudgeMs: Int = 0,
    /** A que entra deslocada em batidas inteiras (+1 adianta uma batida): para a grade que caiu na batida errada. */
    val beatShift: Int = 0,
    /** A mixagem do DJ nesta troca (o que ele fez com os controles ao ensaiar); null é o automático. */
    val take: MixTake? = null,
) {
    enum class Mark { GOOD, REDO }

    enum class Pace(val title: String) { FAST("Rápida"), NORMAL("Média"), SLOW("Lenta") }
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
        val outro = g?.cues?.firstOrNull { it.comment.equals(os.deckbridge.logic.Section.OUTRO, ignoreCase = true) && it.timeMs >= dur / 2 }?.timeMs
        val wanted = if (outro != null && outro + len <= dur) outro else dur - len - (2 * barMs(t.bpm)).toInt()
        return snap(g, wanted.coerceAtLeast(0))
    }

    /** Entrada automática: o primeiro início de compasso (o começo da faixa, se não há grade). */
    fun defaultIn(g: TrackGrid?): Int = downbeats(g).firstOrNull() ?: 0

    /** Sem estrutura detectada: eletrônica mixa longo (eram 16, a virada ficava só no fim). */
    const val DEFAULT_BARS = 32

    /** O automático nunca fica mais curto nem mais longo que isso. */
    const val MIN_BARS = 16
    const val MAX_BARS = 64

    /** Frase: as músicas eletrônicas mudam de parte a cada 8 compassos (16, 32…). A saída recua de frase em frase. */
    const val PHRASE_BARS = 8

    /** Sem outro detectado, a saída começa num início de bloco de 16 compassos (como se fraseia eletrônica). */
    const val BLOCK_BARS = 16

    private fun cue(g: TrackGrid?, name: String): Int? = g?.cues?.firstOrNull { it.comment.equals(name, ignoreCase = true) }?.timeMs

    /** Arredonda [bars] para baixo numa frase (múltiplo de 8). */
    private fun phraseBars(bars: Int): Int = (bars / PHRASE_BARS) * PHRASE_BARS

    /** O início de bloco (a cada [block] compassos a partir do primeiro) mais perto de [ms], sem passar dele. */
    fun phraseStart(g: TrackGrid?, ms: Int, block: Int = BLOCK_BARS): Int {
        val d = downbeats(g)
        if (d.isEmpty()) return ms
        val k = d.indices.lastOrNull { d[it] <= ms } ?: return d.first()
        return d[k - k % block]
    }

    /**
     * Por que a transição tem esse tamanho: até o drop da que entra (o drop cai quando a outra acaba de sair), o outro da
     * que sai, o padrão, ou o que o DJ escolheu.
     */
    enum class Basis { DROP, OUTRO, DEFAULT, FIXED }

    /**
     * Transição automática pela estrutura, como se mixa eletrônica: a que entra começa no começo dela (intro) e a troca
     * dura até o drop dela — o drop cai exatamente quando a que sai termina de sair. A que sai começa a sair no Outro
     * dela (recuando de frase em frase se o outro for curto). Sem drop detectado, a troca dura o outro; sem nada,
     * [DEFAULT_BARS]. Sempre entre [MIN_BARS] e [MAX_BARS], em frases; [fixedBars] escolhe a duração à mão.
     */
    fun auto(a: TrackItem, ga: TrackGrid?, b: TrackItem, gb: TrackGrid?, fixedBars: Int? = null): Pair<Transition, Basis> {
        val barA = barMs(a.bpm)
        val barB = barMs(b.bpm)
        val durA = ga?.durationMs?.takeIf { it > 0 } ?: (a.durationSeconds * 1000)
        val durB = gb?.durationMs?.takeIf { it > 0 } ?: (b.durationSeconds * 1000)
        val inB = cue(gb, os.deckbridge.logic.Section.INTRO) ?: defaultIn(gb)
        // O drop da que entra: depois de pelo menos uma frase de intro e antes de 60% da faixa (senão é análise errada).
        val dropB = cue(gb, os.deckbridge.logic.Section.DROP)?.takeIf { it >= inB + PHRASE_BARS * barB && it <= durB * 0.6 }
        val outroA = cue(ga, os.deckbridge.logic.Section.OUTRO)?.takeIf { it >= durA / 2 && it + PHRASE_BARS * barA <= durA }
        // Até o drop, exato (a análise nem sempre põe o drop num múltiplo de 8: arredondar faria ele cair fora do fim da troca).
        val introBars = dropB?.let { Math.round((it - inB) / barB).toInt() }
        val outroBars = outroA?.let { phraseBars(((durA - it) / barA).toInt()) }
        val (wanted, basis) = when {
            fixedBars != null -> fixedBars to Basis.FIXED
            introBars != null -> introBars.coerceIn(MIN_BARS, MAX_BARS) to Basis.DROP
            outroBars != null -> outroBars.coerceIn(MIN_BARS, MAX_BARS) to Basis.OUTRO
            else -> DEFAULT_BARS to Basis.DEFAULT
        }
        // No máximo metade da faixa que sai (a outra metade também recebe a troca de entrada dela): uma faixa curta não
        // vira só transição.
        val firstA = defaultIn(ga)
        val room = ((durA - firstA) / barA / 2).toInt()
        val bars = if (wanted <= room) wanted else phraseBars(room).coerceAtLeast(PHRASE_BARS).coerceAtMost(wanted)
        val len = (bars * barA).toInt()
        // Saída: o outro; se a troca não cabe depois dele, recua de frase em frase. Sem outro, a última frase que cabe.
        // Sem outro: o último bloco de 16 onde a troca cabe; numa faixa curta (o bloco cairia na primeira metade), a frase
        // de 8; e, se nem ela, o compasso.
        val target = durA - len - barA.toInt()
        val noOutro = listOf(phraseStart(ga, target), phraseStart(ga, target, PHRASE_BARS)).firstOrNull { it >= durA / 2 } ?: snap(ga, target)
        val out = fitOut(a, ga, outroA ?: noOutro, bars)
        // Até o drop, o grave troca no drop (o clássico da eletrônica); nos outros casos, no meio.
        val swapAt = if (basis == Basis.DROP && bars == introBars) 1.0 else 0.5
        return Transition(a.id, b.id, out, inB, bars, swapAt = swapAt) to basis
    }

    /** A saída [out] de [a], recuada de frase em frase (8 compassos) até [bars] compassos caberem antes do fim. */
    fun fitOut(a: TrackItem, ga: TrackGrid?, out: Int, bars: Int): Int {
        val barA = barMs(a.bpm)
        val durA = ga?.durationMs?.takeIf { it > 0 } ?: (a.durationSeconds * 1000)
        val firstA = defaultIn(ga)
        val len = (bars * barA).toInt()
        var o = out
        while (o + len > durA && o - PHRASE_BARS * barA >= firstA) o -= (PHRASE_BARS * barA).toInt()
        return snap(ga, o.coerceAtLeast(firstA))
    }

    /**
     * Quantos compassos da entrada [inMs] até o drop de [b] (de 8 a [MAX_BARS]): com essa duração o drop cai
     * quando a outra termina de sair. Null se [b] não tem drop depois da entrada.
     */
    fun barsToDrop(b: TrackItem, gb: TrackGrid?, inMs: Int): Int? {
        val drop = cue(gb, os.deckbridge.logic.Section.DROP)?.takeIf { it > inMs } ?: return null
        val bars = Math.round((drop - inMs) / barMs(b.bpm)).toInt()
        return bars.takeIf { it >= PHRASE_BARS }?.coerceAtMost(MAX_BARS)
    }

    /** Quantos semitons o tom muda tocando a [speed] sem Master Tempo. */
    fun semitones(speed: Double): Double = if (speed > 0) 12 * kotlin.math.ln(speed) / kotlin.math.ln(2.0) else 0.0

    /** O tom (Camelot) depois de subir [semi] semitons: cada semitom anda 7 casas na roda. */
    fun shiftKey(key: Pair<Int, Char>, semi: Int): Pair<Int, Char> = os.deckbridge.logic.camelotShift(key, semi)

    fun fit(a: TrackItem, b: TrackItem): Fit {
        val ka = Smart.camelot(a.key)
        val kb = Smart.camelot(b.key)
        val dist = if (ka != null && kb != null) Smart.harmonicDistance(ka, kb) else null
        val diff = if (a.bpm > 0 && b.bpm > 0) (b.bpm - a.bpm) / a.bpm * 100 else 0.0
        return Fit(dist, diff)
    }

    /**
     * Transições automáticas para a ordem [ids] (pela estrutura, ver [auto]; [bars] fixa a duração), mantendo as que já
     * existem para o mesmo par.
     */
    fun plan(
        view: LibraryView, ids: List<Int>, grids: (TrackItem) -> TrackGrid?, keep: List<Transition> = emptyList(), bars: Int? = null,
        /** O jeito do DJ aprendido das mixagens dele: as trocas novas começam assim. */
        habits: MixHabits? = null,
    ): List<Transition> =
        ids.zipWithNext().mapNotNull { (a, b) ->
            keep.firstOrNull { it.fromId == a && it.toId == b } ?: run {
                val ta = view.track(a) ?: return@mapNotNull null
                val tb = view.track(b) ?: return@mapNotNull null
                val (t, basis) = auto(ta, grids(ta), tb, grids(tb), bars)
                habits?.apply(t, basis) ?: t
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
            p.setProperty("$n.pace", t.pace.name)
            p.setProperty("$n.swap", t.swapAt.toString())
            p.setProperty("$n.swapBars", t.swapBars.toString())
            if (t.note.isNotBlank()) p.setProperty("$n.note", t.note)
            if (t.nudgeMs != 0) p.setProperty("$n.nudge", t.nudgeMs.toString())
            if (t.beatShift != 0) p.setProperty("$n.beats", t.beatShift.toString())
            t.take?.takeIf { !it.isEmpty }?.let { p.setProperty("$n.take", it.encode()) }
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
                    p.getProperty("$i.pace")?.let { m -> runCatching { Transition.Pace.valueOf(m) }.getOrNull() } ?: Transition.Pace.NORMAL,
                    p.getProperty("$i.swap")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.5,
                    p.getProperty("$i.swapBars")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    p.getProperty("$i.note").orEmpty(),
                    p.getProperty("$i.nudge")?.toIntOrNull() ?: 0,
                    p.getProperty("$i.beats")?.toIntOrNull() ?: 0,
                    MixTake.decode(p.getProperty("$i.take")),
                )
            }.getOrNull()
        }
    }
}
