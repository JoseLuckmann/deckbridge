package os.deckbridge.manager.smart

import os.deckbridge.manager.mix.SetBuilder
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem

/**
 * "Pronto pro show?": a lista curta do que costuma dar errado na cabine, conferida antes de sair de casa. Como os
 * checklists que reduziram complicações em cirurgia (Haynes et al. 2009) e os da aviação (Degani & Wiener 1997): poucos
 * itens, só os críticos, cada um com o que fazer. Detalhes e fontes em `docs/ux-show.md`.
 */
object ShowCheck {
    enum class Kind(val ok: String, val bad: String) {
        AUDIO("Áudio de todas no aparelho", "sem o arquivo de áudio"),
        ANALYSIS("Todas com grade (análise)", "sem análise: sem grade no player"),
        CUES("Todas com Intro, Drop e Outro marcados", "sem cues de Intro/Drop/Outro"),
        REPEATED("Nenhuma repetida no caminho", "repetida no set"),
        TRANSITIONS("Trocas sem aviso de tom ou BPM", "troca(s) com aviso de tom ou BPM"),
        REHEARSED("Todas as trocas ensaiadas", "troca(s) ainda não ensaiada(s) ou a refazer"),
    }

    /** O que corrige um item, se o app sabe corrigir sozinho. */
    enum class Fix { ANALYZE, MARK_CUES, OPEN_ISSUES, REHEARSE }

    /** Um item da lista: o que é, quais faixas (ou trocas) e quantas; [count] 0 é ✓. */
    data class Item(val kind: Kind, val count: Int, val trackIds: List<Int>, val fix: Fix?) {
        val ok get() = count == 0
        val text get() = if (ok) kind.ok else "$count ${kind.bad}"
    }

    /** As marcas de estrutura que a montagem automática procura nas faixas. */
    val STRUCTURE = listOf("Intro", "Drop", "Outro")

    /**
     * Confere as faixas [ids] do set (todas as linhas, na ordem do caminho). [cueNames] dá os nomes dos cues da faixa;
     * [issues] e [unrehearsed] são as trocas com aviso e as ainda não ensaiadas (ou marcadas para refazer).
     */
    fun check(
        view: LibraryView, ids: List<Int>, hasAnalysis: (TrackItem) -> Boolean, audioExists: (TrackItem) -> Boolean,
        cueNames: (TrackItem) -> Set<String>, issues: Int, unrehearsed: Int,
    ): List<Item> {
        val tracks = ids.distinct().mapNotNull { view.track(it) }
        val noAudio = tracks.filter { !audioExists(it) }.map { it.id }
        val noGrid = tracks.filter { it.id !in noAudio && !hasAnalysis(it) }.map { it.id }
        val noCues = tracks.filter { t -> t.id !in noAudio && STRUCTURE.any { name -> cueNames(t).none { it.equals(name, ignoreCase = true) } } }.map { it.id }
        val repeated = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
        return listOf(
            Item(Kind.AUDIO, noAudio.size, noAudio, null),
            Item(Kind.ANALYSIS, noGrid.size, noGrid, Fix.ANALYZE),
            Item(Kind.CUES, noCues.size, noCues, Fix.MARK_CUES),
            Item(Kind.REPEATED, repeated.size, repeated, null),
            Item(Kind.TRANSITIONS, issues, emptyList(), Fix.OPEN_ISSUES),
            Item(Kind.REHEARSED, unrehearsed, emptyList(), Fix.REHEARSE),
        )
    }
}

/**
 * Pedido da pista: de onde o set está até a música pedida, passando por até [MAX_HOPS] faixas da biblioteca que fazem a
 * ponte de tom e andamento aos poucos. É o problema de gerar uma lista entre uma música de partida e uma de chegada
 * (Flexer, Schnitzer, Gasser & Widmer 2008); a ordem importa nas mixagens de DJ (Kell & Tzanetakis 2013). Aqui, o menor
 * caminho (Dijkstra) num grafo em que só há aresta quando a troca combina (tom vizinho e BPM dentro do conforto).
 */
object Request {
    const val MAX_HOPS = 3

    /**
     * As faixas para pôr entre [from] e [target] (sem as duas), com no máximo [maxHops]; vazia se [target] já combina
     * direto com [from]; null se não há caminho com as faixas disponíveis (fora de [exclude]).
     */
    fun route(view: LibraryView, from: TrackItem, target: TrackItem, exclude: Set<Int>, maxHops: Int = MAX_HOPS): List<TrackItem>? {
        if (SetPlanner.fit(from, target).good) return emptyList()
        val nodes = view.tracks.filter { it.id != from.id && it.id != target.id && it.id !in exclude && it.bpm > 0 }
        // Camadas: best[k][i] é o menor custo de chegar no nó i usando k+1 faixas de ponte. Pequeno o bastante (k ≤ 3).
        val n = nodes.size
        var cost = DoubleArray(n) { i -> if (SetPlanner.fit(from, nodes[i]).good) SetBuilder.cost(from, nodes[i]) else Double.MAX_VALUE }
        val prev = mutableListOf(IntArray(n) { -1 })
        var bestEnd: Pair<Int, Int>? = null
        var bestCost = Double.MAX_VALUE
        for (layer in 0 until maxHops) {
            for (i in 0 until n) {
                if (cost[i] == Double.MAX_VALUE) continue
                if (SetPlanner.fit(nodes[i], target).good) {
                    val c = cost[i] + SetBuilder.cost(nodes[i], target)
                    if (c < bestCost) { bestCost = c; bestEnd = layer to i }
                }
            }
            // Menos faixas no meio é melhor: se já chega com esta camada, não procura caminhos mais longos.
            if (bestEnd != null || layer == maxHops - 1) break
            val next = DoubleArray(n) { Double.MAX_VALUE }
            val from2 = IntArray(n) { -1 }
            for (i in 0 until n) {
                if (cost[i] == Double.MAX_VALUE) continue
                for (j in 0 until n) {
                    if (i == j || !SetPlanner.fit(nodes[i], nodes[j]).good) continue
                    val c = cost[i] + SetBuilder.cost(nodes[i], nodes[j])
                    if (c < next[j]) { next[j] = c; from2[j] = i }
                }
            }
            cost = next
            prev += from2
        }
        val (layer, end) = bestEnd ?: return null
        val path = ArrayList<TrackItem>()
        var i = end
        for (k in layer downTo 0) {
            path += nodes[i]
            if (k > 0) i = prev[k][i]
        }
        return path.reversed()
    }
}
