package os.deckbridge.manager.mix

import kotlin.math.abs
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.smart.Smart

/** O desenho do andamento ao longo do set. */
enum class SetShape(val title: String) {
    FLOW("Fluido"),
    RISING("Subindo"),
    PEAK("Sobe e desce"),
}

/**
 * Montar e ordenar sets pelo que faz uma troca soar bem: tom igual ou vizinho na roda e andamento perto (até 6% a faixa
 * que entra acompanha sem soar estranho, com Master Tempo). Sem modelo nem "IA": um custo por troca e uma busca simples.
 */
object SetBuilder {
    /** Custo de ir de [a] para [b] (menor é melhor). [at] é a posição no set, de 0 (início) a 1 (fim). */
    fun cost(a: TrackItem, b: TrackItem, shape: SetShape = SetShape.FLOW, at: Double = 0.5): Double {
        val fit = SetPlanner.fit(a, b)
        val key = when (fit.keyDistance) {
            0 -> 0.0
            1 -> 1.0
            2 -> 4.0
            null -> 3.0
            else -> 8.0 + fit.keyDistance
        }
        val pct = abs(fit.bpmDiffPct)
        var tempo = when {
            a.bpm <= 0 || b.bpm <= 0 -> 3.0
            pct <= 3 -> pct * 0.5
            pct <= 6 -> 1.5 + (pct - 3)
            else -> 10.0 + pct
        }
        // A forma: subindo pede andamento que não cai; sobe-e-desce, subir até o meio e descer depois.
        val rising = when (shape) {
            SetShape.FLOW -> null
            SetShape.RISING -> true
            SetShape.PEAK -> at < 0.6
        }
        if (rising != null && a.bpm > 0 && b.bpm > 0) {
            val wrongWay = if (rising) a.bpm - b.bpm else b.bpm - a.bpm
            // Um recuo de até 1 BPM passa (troca de tom boa vale mais); mais que isso, o andamento manda.
            if (wrongWay > 1.0) tempo += 6.0 + 4 * wrongWay else if (wrongWay > 0.05) tempo += 0.5
        }
        return key + tempo
    }

    private fun total(order: List<TrackItem>, shape: SetShape): Double =
        order.zipWithNext().withIndex().sumOf { (i, p) -> cost(p.first, p.second, shape, i.toDouble() / (order.size - 1).coerceAtLeast(1)) }

    /** Por onde começar quando o DJ não escolheu: o andamento mais baixo (subindo) ou o mais comum do conjunto. */
    private fun opener(tracks: List<TrackItem>, shape: SetShape): TrackItem = when (shape) {
        SetShape.RISING, SetShape.PEAK -> tracks.filter { it.bpm > 0 }.minByOrNull { it.bpm } ?: tracks.first()
        SetShape.FLOW -> {
            val median = tracks.map { it.bpm }.sorted()[tracks.size / 2]
            tracks.minByOrNull { abs(it.bpm - median) - it.rating * 0.01 }!!
        }
    }

    /**
     * Ordem para mixar [ids]: começa em [first] (ou na escolha automática), vai sempre para a próxima de menor custo e
     * depois melhora com trocas de trechos (2-opt) enquanto o custo total cair. Com o mesmo conjunto, o resultado é o mesmo.
     */
    fun order(view: LibraryView, ids: List<Int>, shape: SetShape = SetShape.FLOW, first: Int? = null): List<Int> {
        val tracks = ids.distinct().mapNotNull { view.track(it) }
        if (tracks.size < 3) return tracks.map { it.id } + ids.filter { view.track(it) == null }
        val start = tracks.firstOrNull { it.id == first } ?: opener(tracks, shape)
        val left = tracks.filter { it.id != start.id }.toMutableList()
        val out = mutableListOf(start)
        while (left.isNotEmpty()) {
            val at = out.size.toDouble() / tracks.size
            val next = left.minWithOrNull(compareBy<TrackItem>({ cost(out.last(), it, shape, at) }, { -it.rating }, { it.id }))!!
            out += next
            left -= next
        }
        // Melhora: inverte trechos (2-opt) e muda uma faixa de lugar, enquanto o custo cair; a primeira fica no lugar.
        var best = total(out, shape)
        // Acima de 60 faixas a melhoria fica cara (cresce com o cubo); fica a escolha passo a passo.
        var improved = out.size <= 60
        var guard = 0
        while (improved && guard++ < 50) {
            improved = false
            for (i in 1 until out.size - 1) for (j in i + 1 until out.size) {
                val trial = out.subList(0, i) + out.subList(i, j + 1).reversed() + out.subList(j + 1, out.size)
                val c = total(trial, shape)
                if (c < best - 1e-9) {
                    out.clear(); out += trial; best = c; improved = true
                }
            }
            for (i in 1 until out.size) for (j in 1 until out.size) {
                if (i == j) continue
                val trial = out.toMutableList().also { val x = it.removeAt(i); it.add(j, x) }
                val c = total(trial, shape)
                if (c < best - 1e-9) {
                    out.clear(); out += trial; best = c; improved = true
                }
            }
        }
        return out.map { it.id } + ids.filter { view.track(it) == null }
    }

    /** Quanto de uma faixa costuma tocar num set: tudo menos a troca de 16 compassos e uma folga (aproximado). */
    fun playedMs(t: TrackItem): Int = ((t.durationSeconds * 1000) - (16 * SetPlanner.barMs(t.bpm)).toInt() - 4000).coerceAtLeast(30_000)

    /**
     * Monta um set de cerca de [minutes] minutos a partir de [pool]: escolhe a cada passo a faixa que melhor continua
     * (troca boa, e entre as boas a de nota mais alta), e no fim ordena para mixar. Faixas sem andamento ficam de fora.
     */
    fun build(view: LibraryView, pool: List<TrackItem>, minutes: Int, shape: SetShape = SetShape.FLOW, seed: TrackItem? = null): List<Int> {
        val usable = pool.filter { it.bpm > 0 }.distinctBy { it.id }
        if (usable.isEmpty()) return emptyList()
        val goal = minutes * 60_000L
        val start = seed?.takeIf { s -> usable.any { it.id == s.id } } ?: opener(usable, shape)
        val picked = mutableListOf(start)
        var length = playedMs(start).toLong()
        val left = usable.filter { it.id != start.id }.toMutableList()
        while (length < goal && left.isNotEmpty()) {
            val at = (length.toDouble() / goal).coerceAtMost(1.0)
            // A nota pesa como meio ponto de custo por estrela: entre trocas parecidas, a faixa melhor.
            val next = left.minWithOrNull(compareBy<TrackItem>({ cost(picked.last(), it, shape, at) - it.rating * 0.5 }, { it.id }))!!
            picked += next
            left -= next
            length += playedMs(next)
        }
        return order(view, picked.map { it.id }, shape, first = start.id)
    }
}

/** Um ponto a acertar numa troca do set. */
data class SetIssue(val fromId: Int, val toId: Int, val kind: Kind, val text: String) {
    enum class Kind { KEY, TEMPO, NO_GRID, NO_ROOM }

    /** Tom ou andamento: uma faixa no meio (ponte) resolve. */
    val bridgeable get() = kind == Kind.KEY || kind == Kind.TEMPO
}

/** A checagem do set: o que pode soar mal em cada troca, em texto curto, e faixas que fazem a ponte. */
object SetDoctor {
    fun check(view: LibraryView, ids: List<Int>, plan: List<Transition>, grids: (TrackItem) -> TrackGrid?): List<SetIssue> {
        val out = ArrayList<SetIssue>()
        for ((a, b) in ids.zipWithNext()) {
            val ta = view.track(a) ?: continue
            val tb = view.track(b) ?: continue
            val fit = SetPlanner.fit(ta, tb)
            val ka = Smart.camelot(ta.key)?.let { "${it.first}${it.second}" } ?: ta.key
            val kb = Smart.camelot(tb.key)?.let { "${it.first}${it.second}" } ?: tb.key
            if (fit.keyDistance != null && fit.keyDistance >= 2) out += SetIssue(a, b, SetIssue.Kind.KEY, "Tom $ka→$kb não combina")
            if (!fit.bpmOk) out += SetIssue(a, b, SetIssue.Kind.TEMPO, "Salto de %+.0f%% no BPM".format(fit.bpmDiffPct))
            if (grids(ta) == null || grids(tb) == null) out += SetIssue(a, b, SetIssue.Kind.NO_GRID, "Sem grade: analise")
            val tr = plan.firstOrNull { it.fromId == a && it.toId == b }
            if (tr != null && grids(ta) != null) {
                val dur = grids(ta)!!.durationMs
                val need = (tr.bars * SetPlanner.barMs(ta.bpm)).toInt()
                if (tr.outMs + need > dur) out += SetIssue(a, b, SetIssue.Kind.NO_ROOM, "Saída sem espaço para ${tr.bars} compassos")
            }
        }
        return out
    }

    /** Faixas que combinam com [a] (depois dela) e com [b] (antes dela), fora as do set; as melhores primeiro. */
    fun bridges(view: LibraryView, a: TrackItem, b: TrackItem, exclude: Set<Int>, limit: Int = 3): List<TrackItem> =
        view.tracks.filter { it.id !in exclude && it.bpm > 0 }
            .map { it to SetBuilder.cost(a, it) + SetBuilder.cost(it, b) - it.rating * 0.3 }
            .filter { (t, _) -> SetPlanner.fit(a, t).good && SetPlanner.fit(t, b).good }
            .sortedWith(compareBy({ it.second }, { it.first.id }))
            .take(limit).map { it.first }
}
