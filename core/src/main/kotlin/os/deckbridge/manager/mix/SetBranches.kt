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

    /** A linha que o ramo toca: o ponto de virada, as faixas do ramo e, se volta, a faixa onde volta. */
    fun line(view: LibraryView, b: SetBranch): List<Int> =
        listOf(b.fromTrackId) + view.entries[b.playlistId].orEmpty().filter { view.track(it) != null } + listOfNotNull(b.rejoinTrackId)
}
