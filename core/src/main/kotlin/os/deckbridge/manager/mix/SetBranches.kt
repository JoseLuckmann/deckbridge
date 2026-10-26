package os.deckbridge.manager.mix

import java.io.File
import java.util.Properties
import os.deckbridge.manager.pdb.LibraryView

/**
 * Um ramo do set: uma linha alternativa que sai da faixa [fromTrackId] do set [mainId] e, se [rejoinTrackId], volta à
 * linha principal nela. As faixas do ramo ficam numa playlist de verdade ([playlistId]), então vão para o pendrive e
 * aparecem no player como qualquer playlist ("Set ⑂ B"); o que liga o ramo ao set fica aqui, fora do banco do rekordbox.
 */
data class SetBranch(val playlistId: Int, val mainId: Int, val fromTrackId: Int, val rejoinTrackId: Int?)

object SetBranches {
    /** A letra do ramo: B, C, D… (A é a linha principal). */
    fun letter(n: Int): Char = 'B' + n

    fun load(file: File): List<SetBranch> {
        if (!file.isFile) return emptyList()
        val p = Properties().apply { file.inputStream().use { load(it) } }
        val n = p.getProperty("count")?.toIntOrNull() ?: return emptyList()
        return (0 until n).mapNotNull { i ->
            runCatching {
                SetBranch(
                    p.getProperty("$i.playlist").toInt(), p.getProperty("$i.main").toInt(), p.getProperty("$i.from").toInt(),
                    p.getProperty("$i.rejoin")?.toIntOrNull(),
                )
            }.getOrNull()
        }
    }

    fun save(file: File, branches: List<SetBranch>) {
        val p = Properties()
        p.setProperty("count", branches.size.toString())
        branches.forEachIndexed { i, b ->
            p.setProperty("$i.playlist", b.playlistId.toString())
            p.setProperty("$i.main", b.mainId.toString())
            p.setProperty("$i.from", b.fromTrackId.toString())
            b.rejoinTrackId?.let { p.setProperty("$i.rejoin", it.toString()) }
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".novo")
        tmp.outputStream().use { p.store(it, "DeckBridge: ramos dos sets") }
        tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
    }

    /** Só os ramos que ainda existem: a playlist do ramo e a do set no banco, e o ponto de virada ainda no set. */
    fun alive(view: LibraryView, branches: List<SetBranch>): List<SetBranch> = branches.filter { b ->
        view.playlists.any { it.id == b.playlistId } && view.playlists.any { it.id == b.mainId } && b.fromTrackId in view.entries[b.mainId].orEmpty()
    }

    /** O set principal de [playlistId]: sobe pelos ramos (um ramo pode sair de outro ramo) até a playlist que não é ramo. */
    fun rootOf(branches: List<SetBranch>, playlistId: Int): Int {
        var id = playlistId
        val seen = HashSet<Int>()
        while (seen.add(id)) id = branches.firstOrNull { it.playlistId == id }?.mainId ?: return id
        return id
    }

    /** Os ramos da árvore do set [root] (de ramos que saem dele ou de outros ramos dele), na ordem em que foram criados. */
    fun tree(branches: List<SetBranch>, root: Int): List<SetBranch> = branches.filter { rootOf(branches, it.playlistId) == root && it.playlistId != root }

    /** A linha que o ramo toca: o ponto de virada, as faixas do ramo e, se volta, a faixa onde volta. */
    fun line(view: LibraryView, b: SetBranch): List<Int> =
        listOf(b.fromTrackId) + view.entries[b.playlistId].orEmpty().filter { view.track(it) != null } + listOfNotNull(b.rejoinTrackId)
}

/**
 * Um caminho inteiro do set: a linha principal ([branch] null) ou o caminho de onde o ramo sai até o ponto de virada, o
 * ramo e, se ele volta, o resto daquele caminho a partir da volta. [split] é a posição do ponto de virada em [ids] e
 * [merge] a da volta (null: o ramo é outro final); entre as duas, o caminho é só deste ramo. [pool]: a posição é de uma
 * pool (faixas sem ordem: o DJ escolhe na hora), não de uma lista.
 */
data class SetRoute(
    val letter: Char, val branch: SetBranch?, val ids: List<Int>, val split: Int?, val merge: Int?,
    val pool: List<Boolean> = ids.map { false },
) {
    /** A faixa na posição [i] é do trecho próprio do ramo (depois da virada e antes da volta). */
    fun own(i: Int): Boolean = split != null && i > split && (merge == null || i < merge)

    /** A troca da posição [i] para a [i]+1 é do ramo (sai da virada ou chega na volta). */
    fun ownStep(i: Int): Boolean = split != null && i >= split && (merge == null || i < merge)

    /** A posição [i] já é a linha de onde saiu de novo, depois da volta. */
    fun afterMerge(i: Int): Boolean = merge != null && i >= merge

    /** De onde sai e onde volta (null: outro final), para mostrar. */
    val rejoinId: Int? get() = merge?.let { ids.getOrNull(it) }

    /** A troca da posição [i] para a [i]+1 é dentro de uma pool (sem ordem, não é uma troca programada). */
    fun insidePool(i: Int): Boolean = pool.getOrElse(i) { false } && pool.getOrElse(i + 1) { false }
}

/** O andamento de um caminho: quando cada faixa começa (no master, em ms), o master em cada uma e o tamanho total. */
data class RouteTempo(val startMs: List<Long>, val masters: List<Double>, val lengthMs: Long) {
    val first get() = masters.firstOrNull() ?: 0.0
    val peak get() = masters.maxOrNull() ?: 0.0
    val last get() = masters.lastOrNull() ?: 0.0
}

object SetRoutes {
    /**
     * A linha principal ([main], na ordem) e um caminho por ramo vivo da árvore ([branches], na ordem de criação; um ramo
     * pode sair de outro ramo). [poolFrom]: de que posição da playlist em diante ela é pool (null: é toda lista).
     */
    fun routes(
        view: LibraryView, main: List<Int>, branches: List<SetBranch>, poolFrom: (Int) -> Int? = { null }, mainId: Int? = null,
    ): List<SetRoute> {
        val root = mainId ?: branches.firstOrNull()?.let { SetBranches.rootOf(branches, it.playlistId) }
        fun pooled(playlist: Int?, ids: List<Int>): List<Boolean> {
            val k = playlist?.let(poolFrom) ?: return ids.map { false }
            return ids.indices.map { it >= k }
        }
        val a = SetRoute('A', null, main, null, null, pooled(root, main))
        val byPlaylist = HashMap<Int, SetRoute>()
        root?.let { byPlaylist[it] = a }
        val out = mutableListOf(a)
        // Um ramo precisa do caminho de onde sai: vai resolvendo enquanto houver ramo cujo pai já tem caminho.
        val left = branches.withIndex().toMutableList()
        var progress = true
        while (left.isNotEmpty() && progress) {
            progress = false
            val it = left.iterator()
            while (it.hasNext()) {
                val (n, b) = it.next()
                val parent = byPlaylist[b.mainId] ?: continue
                it.remove()
                progress = true
                // O ponto de virada é uma faixa do trecho próprio do pai (na linha principal, qualquer uma).
                val at = parent.ids.indices.firstOrNull { i -> parent.ids[i] == b.fromTrackId && (parent.branch == null || parent.own(i)) } ?: continue
                val mid = view.entries[b.playlistId].orEmpty().filter { id -> view.track(id) != null }
                val back = b.rejoinTrackId?.let { r -> (at + 1 until parent.ids.size).firstOrNull { i -> parent.ids[i] == r } }
                val ids = parent.ids.take(at + 1) + mid + (if (back != null) parent.ids.drop(back) else emptyList())
                val pool = parent.pool.take(at + 1) + pooled(b.playlistId, mid) + (if (back != null) parent.pool.drop(back) else emptyList())
                val r = SetRoute(SetBranches.letter(n), b, ids, at, back?.let { at + 1 + mid.size }, pool)
                byPlaylist[b.playlistId] = r
                out += r
            }
        }
        return out
    }

    /**
     * Quando cada faixa de [ids] começa e o tamanho do caminho, tocando no master ([masters]): o trecho de cada faixa
     * (da entrada à saída das [transitions]) dura menos se o master está acima do BPM dela.
     */
    fun tempo(view: LibraryView, ids: List<Int>, transitions: List<Transition>, masters: List<Double>): RouteTempo {
        val starts = ArrayList<Long>(ids.size)
        var at = 0L
        ids.forEachIndexed { n, id ->
            starts += at
            val t = view.track(id) ?: return@forEachIndexed
            // O mesmo trecho que SetPlanner.lengthMs conta: da entrada (vindo da anterior) à saída (para a próxima).
            val from = if (n > 0) transitions.firstOrNull { it.fromId == ids[n - 1] && it.toId == id }?.inMs ?: 0 else 0
            val to = if (n < ids.lastIndex) transitions.firstOrNull { it.fromId == id && it.toId == ids[n + 1] }?.outMs ?: (t.durationSeconds * 1000) else t.durationSeconds * 1000
            val speed = masters.getOrNull(n)?.takeIf { it > 0 && t.bpm > 0 }?.let { it / t.bpm } ?: 1.0
            at += ((to - from).coerceAtLeast(0) / speed).toLong()
        }
        return RouteTempo(starts, masters, at)
    }
}
