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
    FALLING("Descendo"),
}

/**
 * Montar e ordenar sets pelo que faz uma troca soar bem: tom igual ou vizinho na roda e andamento perto (até 6% a faixa
 * que entra acompanha sem soar estranho, com Master Tempo). Sem modelo nem "IA": um custo por troca e uma busca simples.
 */
object SetBuilder {
    private const val BEAM = 12

    /** No ramo, uma troca fora do tom (ou uma volta que não encaixa) só entra se não houver outro jeito. */
    private const val MISS = 25.0

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
        // O master manda: até 10% de esticada é tocar normal; até 16% (o pitch do player) pesa; além disso não dá.
        val pct = abs(fit.bpmDiffPct)
        var tempo = when {
            a.bpm <= 0 || b.bpm <= 0 -> 3.0
            pct <= 3 -> pct * 0.3
            pct <= SetTempo.COMFORT_PCT -> 0.9 + (pct - 3) * 0.3
            pct <= SetTempo.MAX_PCT -> 3.0 + (pct - SetTempo.COMFORT_PCT)
            else -> 20.0 + pct
        }
        // A forma: subindo pede andamento que não cai; sobe-e-desce, subir até o meio e descer depois.
        val rising = when (shape) {
            SetShape.FLOW -> null
            SetShape.RISING -> true
            SetShape.PEAK -> at < 0.6
            SetShape.FALLING -> false
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
        SetShape.FALLING -> tracks.filter { it.bpm > 0 }.maxByOrNull { it.bpm } ?: tracks.first()
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

    /**
     * Um ramo do set: a partir de [from] (uma faixa da linha [line]), [count] faixas de [pool] que não estão na linha nem
     * em [taken], no rumo [shape], e — se [rejoin] — a faixa da linha, mais adiante, em que o ramo melhor volta (a troca
     * do último do ramo para ela é boa). Com [joinAt] (uma faixa da linha depois de [from]), volta nela: o DJ escolheu onde
     * as vertentes se juntam. Devolve as faixas do ramo e onde volta (null: não volta, vira outro final).
     */
    fun branch(
        view: LibraryView, line: List<Int>, from: Int, count: Int, shape: SetShape, pool: List<TrackItem> = view.tracks,
        taken: Set<Int> = emptySet(), rejoin: Boolean = true, joinAt: Int? = null,
    ): Pair<List<Int>, Int?> {
        val start = view.track(from) ?: return emptyList<Int>() to null
        val busy = line.toSet() + taken
        val candidates = pool.filter { it.id !in busy && it.bpm > 0 }

        /**
         * Busca em feixe: guarda os [BEAM] caminhos mais baratos a cada passo (passo a passo puro escolhe uma faixa boa
         * agora que deixa a próxima troca ruim). Com [target], o último passo também paga a troca para ele (o ramo mira a volta).
         */
        fun walk(target: TrackItem?): Pair<List<TrackItem>, Double> {
            val n = count.coerceAtMost(candidates.size)
            if (n <= 0) return emptyList<TrackItem>() to 0.0
            var beam = listOf(emptyList<TrackItem>() to 0.0)
            for (step in 0 until n) {
                val at = (step + 1).toDouble() / (count + 1)
                val lastStep = step == n - 1
                beam = beam.flatMap { (path, total) ->
                    val last = path.lastOrNull() ?: start
                    candidates.filter { it !in path }.map { c ->
                        var s = cost(last, c, shape, at) - c.rating * 0.5 + if (!SetPlanner.fit(last, c).keyOk) MISS else 0.0
                        if (lastStep && target != null) s += cost(c, target, shape, 1.0) + if (!SetPlanner.fit(c, target).good) MISS else 0.0
                        (path + c) to total + s
                    }
                }.sortedBy { it.second }.take(BEAM) // ordenação estável: mesmo conjunto, mesmo ramo
            }
            return beam.first()
        }

        if (!rejoin) {
            val (picked, _) = walk(null)
            if (picked.isEmpty()) return emptyList<Int>() to null
            return between(start, picked, null, shape).map { it.id } to null
        }
        val chosen = joinAt?.takeIf { it in line.drop(line.indexOf(from) + 1) }?.let { view.track(it) }
        if (chosen != null) {
            val (picked, _) = walk(chosen)
            if (picked.isEmpty()) return emptyList<Int>() to chosen.id
            return between(start, picked, chosen, shape).map { it.id } to chosen.id
        }
        // Volta: depois do ponto de virada (pulando ao menos uma da linha). Tenta cada ponto e fica com o ramo mais barato
        // cuja última troca para a linha seja boa; um ponto mais perto ganha no empate.
        val after = line.drop(line.indexOf(from) + 2).mapNotNull { view.track(it) }
        val best = after.mapIndexedNotNull { i, target ->
            val (picked, total) = walk(target)
            if (picked.isEmpty()) return@mapIndexedNotNull null
            val ordered = between(start, picked, target, shape).map { it.id }
            val end = view.track(ordered.last())!!
            if (!SetPlanner.fit(end, target).good) null else Triple(ordered, target.id, total + i * 0.2)
        }.minByOrNull { it.third }
        if (best != null) return best.first to best.second
        // Nenhum ponto de volta encaixa: o ramo vira outro final.
        val (picked, _) = walk(null)
        if (picked.isEmpty()) return emptyList<Int>() to null
        return between(start, picked, null, shape).map { it.id } to null
    }

    /**
     * A melhor ordem de [middle] entre [first] (fixa no começo) e [last] (fixa no fim, se houver). Um ramo tem poucas
     * faixas: até 7 testa todas as ordens; mais que isso, fica a ordem dada.
     */
    private fun between(first: TrackItem, middle: List<TrackItem>, last: TrackItem?, shape: SetShape): List<TrackItem> {
        if (middle.size > 7) return middle
        var best = middle
        var bestCost = Double.MAX_VALUE
        fun visit(prefix: List<TrackItem>, rest: List<TrackItem>) {
            if (rest.isEmpty()) {
                val order = listOf(first) + prefix + listOfNotNull(last)
                val c = total(order, shape) + order.zipWithNext().count { (x, y) -> !SetPlanner.fit(x, y).keyOk } * MISS
                if (c < bestCost - 1e-9) { bestCost = c; best = prefix }
                return
            }
            for (t in rest) visit(prefix + t, rest - t)
        }
        visit(emptyList(), middle)
        return best
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
    /**
     * [masters] é o master de cada posição de [ids] (vazio: julga pelos BPMs das faixas). Esticar até 10% não é aviso; a
     * faixa que precisa passar do pitch do player (16%) é.
     */
    fun check(view: LibraryView, ids: List<Int>, plan: List<Transition>, grids: (TrackItem) -> TrackGrid?, masters: List<Double> = emptyList()): List<SetIssue> {
        val out = ArrayList<SetIssue>()
        for ((n, pair) in ids.zipWithNext().withIndex()) {
            val (a, b) = pair
            val ta = view.track(a) ?: continue
            val tb = view.track(b) ?: continue
            val fit = SetPlanner.fit(ta, tb)
            val ka = Smart.camelot(ta.key)?.let { "${it.first}${it.second}" } ?: ta.key
            val kb = Smart.camelot(tb.key)?.let { "${it.first}${it.second}" } ?: tb.key
            if (fit.keyDistance != null && fit.keyDistance >= 2) out += SetIssue(a, b, SetIssue.Kind.KEY, "Tom $ka→$kb não combina")
            // Na troca, a que entra toca no master de quem sai.
            val master = masters.getOrNull(n) ?: ta.bpm
            val stretch = SetTempo.stretchPct(tb.bpm, master)
            if (tb.bpm > 0 && kotlin.math.abs(stretch) > SetTempo.MAX_PCT) out += SetIssue(a, b, SetIssue.Kind.TEMPO, "Esticar %+.0f%%: passa do pitch".format(stretch))
            if (grids(ta) == null || grids(tb) == null) out += SetIssue(a, b, SetIssue.Kind.NO_GRID, "Sem grade: analise")
            val tr = plan.firstOrNull { it.fromId == a && it.toId == b }
            if (tr != null && grids(ta) != null) {
                val dur = grids(ta)!!.durationMs
                // Com loop na que sai, só o trecho do loop precisa caber: ele se repete até o fim da troca.
                val need = ((if (tr.aLoopBars > 0) tr.aLoopBars else tr.bars) * SetPlanner.barMs(ta.bpm)).toInt()
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

    /**
     * Para trocar uma faixa do set sem desmanchar as trocas em volta: as que combinam com a de antes ([prev]) e com a de
     * depois ([next]) primeiro (como uma ponte); depois as que combinam com uma das duas. Sem vizinho de um lado, só o outro
     * conta.
     */
    fun replacements(view: LibraryView, prev: TrackItem?, next: TrackItem?, exclude: Set<Int>, limit: Int = 6): List<TrackItem> {
        if (prev == null && next == null) return view.tracks.filter { it.id !in exclude }.sortedByDescending { it.rating }.take(limit)
        fun cost(t: TrackItem) = (prev?.let { SetBuilder.cost(it, t) } ?: 0.0) + (next?.let { SetBuilder.cost(t, it) } ?: 0.0) - t.rating * 0.3
        fun good(t: TrackItem) = (prev == null || SetPlanner.fit(prev, t).good) to (next == null || SetPlanner.fit(t, next).good)
        return view.tracks.filter { it.id !in exclude && it.bpm > 0 }
            .map { t -> Triple(t, good(t), cost(t)) }
            .filter { (_, g, _) -> g.first || g.second }
            .sortedWith(compareBy({ !(it.second.first && it.second.second) }, { it.third }, { it.first.id }))
            .take(limit).map { it.first }
    }
}
