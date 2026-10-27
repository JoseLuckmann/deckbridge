package os.deckbridge.manager.mix

import os.deckbridge.manager.pdb.LibraryView

/** Uma faixa no quadro do set: de que linha (playlist) e posição nela, e onde fica (fileira e coluna). */
data class CanvasCard(val playlistId: Int, val index: Int, val trackId: Int, val row: Int, val col: Int, val pool: Boolean)

/**
 * Uma troca no quadro: de um cartão para o outro. [playlistId] é a linha que guarda a troca (num ramo, a saída do ponto de
 * virada e a chegada na volta são do ramo). [pool]: toca uma pool (sem troca programada: a ordem é escolhida na hora).
 */
data class CanvasLink(val from: CanvasCard, val to: CanvasCard, val playlistId: Int, val pool: Boolean)

/**
 * Uma linha do quadro: a principal (fileira 0) ou um ramo, numa fileira só dele, começando na coluna depois do ponto de
 * virada. [endCol] é onde fica o "＋" (o fim da linha); [split] e [rejoin] são os cartões da linha de onde sai e onde volta.
 */
data class CanvasLane(
    val playlistId: Int, val letter: Char, val branch: SetBranch?, val row: Int, val cards: List<CanvasCard>, val endCol: Int,
    val split: CanvasCard?, val rejoin: CanvasCard?,
)

class SetCanvasLayout(val lanes: List<CanvasLane>, val links: List<CanvasLink>) {
    val rows get() = lanes.size
    val cols get() = (lanes.maxOfOrNull { it.endCol } ?: 0) + 1
}

/**
 * O set como um quadro: cada linha numa fileira, as faixas em colunas na ordem, e cada ramo começando embaixo, logo
 * depois da faixa de onde sai (e ligando de volta na faixa onde volta). Assim dividir a linha do tempo é ver onde ela se
 * abre, sem abrir outra tela.
 */
object SetCanvas {
    fun layout(view: LibraryView, rootId: Int, branches: List<SetBranch>, poolFrom: (Int) -> Int? = { null }): SetCanvasLayout {
        // A faixa onde um ramo volta fica depois do fim dele: o quadro é uma linha do tempo, e o ramo corre ao lado do
        // trecho que ele troca. Empurrar uma faixa empurra as seguintes (e os ramos que saem delas); repete até assentar.
        val minCol = HashMap<Pair<Int, Int>, Int>()
        var result = place(view, rootId, branches, poolFrom, minCol)
        repeat(branches.size * 4 + 4) {
            var moved = false
            for (lane in result.lanes) {
                val r = lane.rejoin ?: continue
                val need = lane.endCol + 1
                if (r.col < need) {
                    minCol[r.playlistId to r.index] = need
                    moved = true
                }
            }
            if (!moved) return result
            result = place(view, rootId, branches, poolFrom, minCol)
        }
        return result
    }

    private fun place(
        view: LibraryView, rootId: Int, branches: List<SetBranch>, poolFrom: (Int) -> Int?, minCol: Map<Pair<Int, Int>, Int>,
    ): SetCanvasLayout {
        fun entries(id: Int) = view.entries[id].orEmpty().filter { view.track(it) != null }
        fun cards(id: Int, row: Int, startCol: Int): List<CanvasCard> {
            val k = poolFrom(id)
            var col = startCol - 1
            return entries(id).mapIndexed { i, t ->
                col = maxOf(col + 1, minCol[id to i] ?: 0)
                CanvasCard(id, i, t, row, col, k != null && i >= k)
            }
        }
        val links = mutableListOf<CanvasLink>()
        fun chain(id: Int, cs: List<CanvasCard>) = cs.zipWithNext().forEach { (a, b) -> links += CanvasLink(a, b, id, a.pool || b.pool) }

        val mainCards = cards(rootId, 0, 0)
        val lanes = mutableListOf(CanvasLane(rootId, 'A', null, 0, mainCards, (mainCards.lastOrNull()?.col ?: -1) + 1, null, null))
        chain(rootId, mainCards)
        val byPlaylist = hashMapOf(rootId to lanes[0])
        // Um ramo precisa da linha de onde sai: vai resolvendo enquanto houver ramo cujo pai já está no quadro.
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
                val split = parent.cards.firstOrNull { c -> c.trackId == b.fromTrackId } ?: continue
                val row = lanes.size
                val own = cards(b.playlistId, row, split.col + 1)
                // A volta: na linha de onde saiu, mais adiante; se não está nela, na linha de onde aquela saiu (e assim por diante).
                val rejoin = b.rejoinTrackId?.let { r ->
                    var lane: CanvasLane? = parent
                    var after = split.col
                    var found: CanvasCard? = null
                    while (lane != null && found == null) {
                        found = lane.cards.firstOrNull { c -> c.trackId == r && c.col > after }
                        if (found == null) {
                            after = lane.split?.col ?: -1
                            lane = lane.branch?.let { byPlaylist[it.mainId] }
                        }
                    }
                    found
                }
                val lane = CanvasLane(b.playlistId, SetBranches.letter(n), b, row, own, (own.lastOrNull()?.col ?: split.col) + 1, split, rejoin)
                lanes += lane
                byPlaylist[b.playlistId] = lane
                val path = listOf(split) + own + listOfNotNull(rejoin)
                // A saída do ponto de virada e a chegada na volta são trocas do ramo; o ponto de virada não é pool do ramo.
                path.zipWithNext().forEach { (a, c) ->
                    val pool = (a.pool && a.playlistId == b.playlistId) || (c.pool && c.playlistId == b.playlistId) ||
                        (poolFrom(b.playlistId) != null && c == rejoin)
                    links += CanvasLink(a, c, b.playlistId, pool)
                }
            }
        }
        return SetCanvasLayout(lanes, links)
    }
}
