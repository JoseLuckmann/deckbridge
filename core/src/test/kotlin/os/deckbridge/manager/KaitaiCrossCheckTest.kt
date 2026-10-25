package os.deckbridge.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.TrackPatch

/**
 * Terceiro leitor: o parser Python gerado da especificação aberta (Kaitai, Crate Digger 7c9d535) lê o banco que o nosso
 * gravador produziu e tem de ver o mesmo que o plano. Só roda se KAITAI_PARSERS apontar para a pasta criada por
 * `tools/setup_kaitai.sh`. Sem ela o teste é ignorado (e o relatório diz isso), nunca passa em branco.
 */
class KaitaiCrossCheckTest {
    private fun kaitai(file: File): List<List<String>> {
        val parsers = System.getenv("KAITAI_PARSERS")
        assumeTrue("KAITAI_PARSERS não definido: verificação Kaitai ignorada", parsers != null && File(parsers, "rekordbox_pdb.py").isFile)
        val script = File("tools/kaitai_check.py").absolutePath
        val proc = ProcessBuilder("python3", "-I", script, parsers!!, file.path, "--tsv").redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), out)
        return out.lines().filter { it.isNotEmpty() }.map { it.split("\t") }
    }

    @Test
    fun kaitaiSeesTheOriginalAndTheEditedDatabaseAsPlanned() {
        val dir = TestVolumes.synthetic()
        val file = File(dir, "PIONEER/rekordbox/export.pdb")
        val raw = TestVolumes.rawOf(dir)
        val before = kaitai(file)
        assertEquals(raw.table(0)!!.rows.size, before.count { it[0] == "T" })

        val e = PdbEditor(raw)
        val v = e.view()
        val ids = v.tracks.map { it.id }
        e.apply(Edit.SetTracks(listOf(ids[2]), TrackPatch(title = "Título Ç — com acento", artist = "Artista Novo", genre = "Techno", rating = 5, comment = "comentário é")))
        val next = v.playlists.maxOf { it.id } + 1
        e.apply(Edit.CreatePlaylist(next, 0, "Playlist Nova", false))
        e.apply(Edit.AddToPlaylist(next, ids.take(5)))
        e.apply(Edit.ReorderPlaylist(next, ids.take(5).reversed()))
        val out = File(dir, "novo.pdb")
        out.writeBytes(raw.write(e.changedTables()))
        val lines = kaitai(out)

        val want = e.view()
        val tracks = lines.filter { it[0] == "T" }
        assertEquals(want.tracks.size, tracks.size)
        for (t in want.tracks) {
            val k = tracks.first { it[1] == t.id.toString() }
            assertEquals(t.title, k[2], "título da faixa ${t.id}")
            assertEquals(t.artist, k[3], "artista da faixa ${t.id}")
            assertEquals(t.album, k[4], "álbum da faixa ${t.id}")
            assertEquals(t.genre, k[5], "gênero da faixa ${t.id}")
            assertEquals(t.key, k[6], "tom da faixa ${t.id}")
            assertEquals(t.rating.toString(), k[7])
            assertEquals(t.comment, k[8])
            assertEquals(t.filePath, k[9])
        }
        val playlists = lines.filter { it[0] == "P" }
        assertEquals(want.playlists.size, playlists.size)
        for (p in want.playlists) {
            val k = playlists.first { it[1] == p.id.toString() }
            assertEquals(listOf(p.parentId.toString(), p.name, if (p.folder) "1" else "0", p.sort.toString()), k.subList(2, 6))
        }
        val entries = lines.filter { it[0] == "E" }.groupBy { it[1].toInt() }.mapValues { (_, l) -> l.sortedBy { it[2].toInt() }.map { it[3].toInt() } }
        assertEquals(want.entries.filterValues { it.isNotEmpty() }, entries)
        assertTrue(entries.getValue(next) == ids.take(5).reversed())
    }
}
