package os.deckbridge.manager.mix

import java.io.File
import java.util.Properties
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem

/**
 * Pool: um trecho do set sem ordem — várias faixas que combinam entre si, e o DJ escolhe na hora qual vem. Uma playlist
 * vira pool de uma posição em diante (as primeiras podem ser uma lista fixa: "abro com estas 5, depois a pool"). O que
 * marca a pool fica em `sets/<playlist>.pool`, fora do banco do rekordbox; no pendrive é uma playlist comum.
 */
object SetPool {
    fun load(file: File): Int? {
        if (!file.isFile) return null
        val p = Properties().apply { file.inputStream().use { load(it) } }
        return p.getProperty("from")?.toIntOrNull()?.coerceAtLeast(0)
    }

    fun save(file: File, from: Int?) {
        if (from == null) { file.delete(); return }
        file.parentFile?.mkdirs()
        val p = Properties().apply { setProperty("from", from.toString()) }
        val tmp = File(file.parentFile, file.name + ".novo")
        tmp.outputStream().use { p.store(it, "DeckBridge: de onde a playlist vira pool") }
        tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
    }

    /** As duas combinam (tom e andamento) em algum sentido: numa pool a ordem é decidida na hora. */
    fun links(a: TrackItem, b: TrackItem): Boolean = SetPlanner.fit(a, b).good || SetPlanner.fit(b, a).good

    /** Com quantas outras da pool cada faixa combina: 0 é uma faixa isolada (só entra com troca forçada). */
    fun linkCount(view: LibraryView, ids: List<Int>): Map<Int, Int> {
        val tracks = ids.distinct().mapNotNull { view.track(it) }
        return tracks.associate { t -> t.id to tracks.count { o -> o.id != t.id && links(t, o) } }
    }

    /**
     * O que tocar depois de [now], das faixas de [pool] que ainda não tocaram ([played]): a melhor troca primeiro (tom,
     * andamento e a forma do set), e entre parecidas a de nota mais alta.
     */
    fun next(view: LibraryView, now: TrackItem, pool: List<Int>, played: Set<Int>, shape: SetShape = SetShape.FLOW): List<Pair<TrackItem, Fit>> =
        pool.distinct().filter { it != now.id && it !in played }.mapNotNull { view.track(it) }
            .sortedWith(compareBy<TrackItem>({ SetBuilder.cost(now, it, shape) - it.rating * 0.3 }, { it.id }))
            .map { it to SetPlanner.fit(now, it) }

    /**
     * Enche uma pool: até [count] faixas de [from] (fora de [exclude] e das que já estão em [current]) que combinam com
     * as [anchors] (de onde se entra e para onde se sai) e entre si. Uma por vez, a que mais liga ao que já está.
     */
    fun fill(
        view: LibraryView, anchors: List<TrackItem>, current: List<Int>, exclude: Set<Int>, count: Int, from: List<TrackItem> = view.tracks,
    ): List<Int> {
        val inPool = current.mapNotNull { view.track(it) }.toMutableList()
        val left = from.filter { it.bpm > 0 && it.id !in exclude && inPool.none { p -> p.id == it.id } }.distinctBy { it.id }.toMutableList()
        val added = ArrayList<Int>()
        while (added.size < count && left.isNotEmpty()) {
            fun score(c: TrackItem): Double {
                val toAnchors = anchors.count { links(it, c) }
                val toPool = inPool.count { links(it, c) }
                return toAnchors * 3.0 + toPool + c.rating * 0.3
            }
            // Só entra quem liga às âncoras ou à pool (a não ser para começar uma pool do zero, sem nada para ligar).
            val open = anchors.isEmpty() && inPool.isEmpty()
            val linked = if (open) left else left.filter { c -> anchors.any { links(it, c) } || inPool.any { links(it, c) } }
            val best = linked.maxWithOrNull(compareBy<TrackItem>({ score(it) }, { -it.id })) ?: break
            added += best.id
            inPool += best
            left -= best
        }
        return added
    }

    /**
     * Um ramo em forma de pool a partir de [from] (uma faixa de [line]): [count] faixas que combinam com ela e entre si e —
     * se [rejoin] — a faixa da linha, mais adiante, com que mais delas combinam (é por ela que se volta). Devolve as faixas
     * e onde volta (null: outro final).
     */
    fun branch(
        view: LibraryView, line: List<Int>, from: Int, count: Int, pool: List<TrackItem> = view.tracks, taken: Set<Int> = emptySet(), rejoin: Boolean = true,
        joinAt: Int? = null,
    ): Pair<List<Int>, Int?> {
        val start = view.track(from) ?: return emptyList<Int>() to null
        val busy = line.toSet() + taken
        // Onde juntar foi escolhido: a pool combina com a entrada e com ela.
        val chosen = joinAt?.takeIf { it in line.drop(line.indexOf(from) + 1) }?.let { view.track(it) }
        if (chosen != null) return fill(view, listOf(start, chosen), emptyList(), busy, count, pool) to chosen.id
        if (rejoin) {
            val best = line.drop(line.indexOf(from) + 2).mapNotNull { view.track(it) }.mapIndexedNotNull { i, target ->
                val ids = fill(view, listOf(start, target), emptyList(), busy, count, pool)
                val exits = ids.count { links(view.track(it)!!, target) }
                if (ids.isEmpty() || exits == 0) null else Triple(ids, target.id, ids.size * 2 + exits - i * 0.2)
            }.maxByOrNull { it.third }
            if (best != null) return best.first to best.second
        }
        return fill(view, listOf(start), emptyList(), busy, count, pool) to null
    }
}
