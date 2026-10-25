package os.deckbridge.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import os.deckbridge.manager.pdb.NameRows
import os.deckbridge.manager.pdb.PdbType
import os.deckbridge.manager.pdb.PlaylistRows
import os.deckbridge.manager.pdb.RawPdb
import os.deckbridge.manager.pdb.TrackRow
import os.deckbridge.manager.pdb.u16
import os.deckbridge.manager.pdb.u32

/** Os codificadores de linha deste programa contra as linhas REAIS do rekordbox (biblioteca grande, fora do repositório). */
class EncoderVsRealTest {
    private val heavy: File? = System.getenv("REAL_EXPORT")?.let { File(it).parentFile }?.let { File(it, "pdb/num_rows/export.pdb") }?.takeIf { it.isFile }

    private class Tally(val name: String) {
        var total = 0
        var same = 0
        val examples = ArrayList<String>()
        /** Compara até o fim real da linha ([end] = tamanho pelos ponteiros), sem o lixo de preenchimento das páginas reais. */
        fun add(realRow: ByteArray, mineRow: ByteArray, ignore: Set<Int> = emptySet(), end: (ByteArray) -> Int = { it.size }) {
            total++
            val real = realRow.copyOf(minOf(realRow.size, align(end(realRow))))
            val mine = mineRow.copyOf(minOf(mineRow.size, align(end(mineRow))))
            val n = maxOf(real.size, mine.size)
            val diff = (0 until n).filter { it !in ignore && (real.getOrElse(it) { 0 }) != (mine.getOrElse(it) { 0 }) }
            if (diff.isEmpty() && real.size == mine.size) same++
            else if (examples.size < 3) examples += "tamanhos ${real.size}/${mine.size}, bytes diferentes ${diff.take(8)} real=${real.take(48).joinToString(" ") { "%02x".format(it) }} | meu=${mine.take(48).joinToString(" ") { "%02x".format(it) }}"
        }
        private fun align(n: Int) = (n + 3) and 3.inv()

        override fun toString() = "$name: $same de $total linhas idênticas" + examples.joinToString("") { "\n    $it" }
    }

    @Test
    fun myRowEncodersReproduceTheRealRows() {
        assumeTrue("REAL_EXPORT não definido", heavy != null)
        val raw = RawPdb.parse(heavy!!.readBytes())
        val report = ArrayList<Tally>()
        run {
            val t = Tally("artistas")
            for (r in raw.table(PdbType.ARTISTS)!!.rows) t.add(r, NameRows.artist(NameRows.artistId(r), NameRows.artistName(r), r.u16(2), r), setOf(2, 3)) { os.deckbridge.manager.pdb.Rows.extent(PdbType.ARTISTS, it, 0, it.size) ?: it.size }
            report += t
        }
        run {
            val t = Tally("álbuns")
            for (r in raw.table(PdbType.ALBUMS)!!.rows) t.add(r, NameRows.album(NameRows.albumId(r), NameRows.albumArtistId(r), NameRows.albumName(r), r.u16(2), r), setOf(2, 3)) { os.deckbridge.manager.pdb.Rows.extent(PdbType.ALBUMS, it, 0, it.size) ?: it.size }
            report += t
        }
        run {
            val t = Tally("gêneros")
            for (r in raw.table(PdbType.GENRES)!!.rows) t.add(r, NameRows.simple(NameRows.id(r), NameRows.simpleName(r)))
            report += t
        }
        run {
            val t = Tally("selos")
            for (r in raw.table(PdbType.LABELS)!!.rows) t.add(r, NameRows.simple(NameRows.id(r), NameRows.simpleName(r)))
            report += t
        }
        run {
            val t = Tally("tons")
            for (r in raw.table(PdbType.KEYS)!!.rows) t.add(r, NameRows.key(NameRows.id(r), NameRows.keyName(r)))
            report += t
        }
        run {
            val t = Tally("playlists (árvore)")
            for (r in raw.table(PdbType.PLAYLIST_TREE)!!.rows) t.add(r, PlaylistRows.tree(PlaylistRows.id(r), PlaylistRows.parent(r), PlaylistRows.name(r), PlaylistRows.folder(r), PlaylistRows.sort(r), r)) { os.deckbridge.manager.pdb.Rows.extent(PdbType.PLAYLIST_TREE, it, 0, it.size) ?: it.size }
            report += t
        }
        run {
            val t = Tally("itens de playlist")
            for (r in raw.table(PdbType.PLAYLIST_ENTRIES)!!.rows) t.add(r, PlaylistRows.entry(PlaylistRows.entryIndex(r), PlaylistRows.entryTrack(r), PlaylistRows.entryPlaylist(r)))
            report += t
        }
        run {
            val t = Tally("faixas (recodificar sem mudar nada)")
            for (r in raw.table(PdbType.TRACKS)!!.rows) t.add(r, TrackRow.parse(r)!!.encode(), setOf()) { TrackRow.extent(it, 0, it.size) ?: it.size }
            report += t
        }
        val text = report.joinToString("\n")
        System.getenv("REAL_OUT")?.let { File(it).appendText("\n== codificadores contra linhas reais ==\n$text\n") }
        assertTrue(report.isNotEmpty())
    }
}
