package os.deckbridge.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import os.deckbridge.logic.parseExportPdb
import os.deckbridge.manager.anlz.AnlzModel
import os.deckbridge.manager.anlz.CueIo
import os.deckbridge.manager.anlz.GridIo
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.PdbType
import os.deckbridge.manager.pdb.RawPdb
import os.deckbridge.manager.pdb.u16
import os.deckbridge.manager.pdb.u32
import os.deckbridge.manager.volume.Verifier

/**
 * Exports REAIS do rekordbox (dados de teste do projeto rekordcrate, MPL-2.0, commit 68cd702): um vazio e um com
 * faixas de demonstração. Ficam fora do repositório; o teste só roda com REAL_EXPORT apontando para
 * `data/complete_export` e escreve o que mediu em REAL_OUT (se definido).
 */
class RealExportTest {
    private val root: File? = System.getenv("REAL_EXPORT")?.let(::File)?.takeIf { it.isDirectory }
    private val report = StringBuilder()

    private fun flat(s: String) = s.replace("\t", " ").replace("\n", " ").replace("\r", " ")

    private fun note(s: String) { report.appendLine(s) }

    private fun finish() {
        System.getenv("REAL_OUT")?.let { File(it).appendText(report.toString()); report.clear() }
    }

    private fun pdb(which: String) = File(root, "$which/PIONEER/rekordbox/export.pdb").readBytes()

    @Test
    fun theRealDatabasesAreReadRewrittenAndVerifiedWithoutLoss() {
        assumeTrue("REAL_EXPORT não definido", root != null)
        try { databases() } finally { finish() }
    }

    private fun databases() {
        for (which in listOf("empty", "demo_tracks")) {
            val bytes = pdb(which)
            val raw = RawPdb.parse(bytes)
            note("== $which: ${bytes.size} bytes, página ${raw.pageLen}, tabelas ${raw.tables.size}, próxima página livre ${raw.nextUnusedPage}, sequência ${raw.sequence}, avisos ${raw.warnings}")
            for (t in raw.tables) note("  tabela ${t.type}: linhas=${t.rows.size} páginas=${t.pages} first=${t.firstPage} last=${t.lastPage} empty=${t.emptyCandidate}")
            assertTrue(raw.warnings.isEmpty(), raw.warnings.toString())
            assertContentEquals(bytes, raw.write(emptyMap()))
            // Reescrever cada tabela com as próprias linhas não perde nenhuma e a conferência passa.
            for (t in raw.tables) {
                val out = raw.write(mapOf(t.type to t.rows))
                val problems = Verifier.check(raw, out, mapOf(t.type to t.rows))
                assertTrue(problems.isEmpty(), "$which tabela ${t.type}: $problems")
            }
            // Compactar e reler dá as mesmas linhas.
            val compact = RawPdb.parse(raw.compact())
            for (t in raw.tables) {
                assertEquals(t.rows.size, compact.table(t.type)!!.rows.size, "$which compactar tabela ${t.type}")
                assertTrue(Verifier.sameRows(t.type, t.rows, compact.table(t.type)!!.rows), "$which compactar tabela ${t.type}")
            }
            // O leitor do app lê o banco real.
            val app = parseExportPdb(bytes).database
            assertTrue(app != null)
            note("  leitor do app: faixas=${app!!.tracks.size} playlists=${app.playlists.size} entradas=${app.entries.size} artistas=${app.artists.size}")
        }
    }

    @Test
    fun theRealAnalysisFilesRoundTripAndTheirCuesAndGridAreRead() {
        assumeTrue("REAL_EXPORT não definido", root != null)
        try { analysis() } finally { finish() }
    }

    private fun analysis() {
        val files = root!!.walkTopDown().filter { it.isFile && it.extension.uppercase() in setOf("DAT", "EXT", "2EX") && "USBANLZ" in it.path }.toList() +
            File(root.parentFile, "anlz/sweep").listFiles().orEmpty().filter { it.extension.uppercase() in setOf("DAT", "EXT", "2EX") }
        assertTrue(files.size >= 9)
        for (f in files) {
            val b = f.readBytes()
            assertContentEquals(b, AnlzModel.parse(b).write(), f.path)
            note("ANLZ ${f.parentFile.name}/${f.name}: seções=${AnlzModel.parse(b).sections.map { it.tag }}")
        }
        for (dir in files.map { it.parentFile }.distinct()) {
            val dat = dir.listFiles()!!.firstOrNull { it.extension.uppercase() == "DAT" }?.let { AnlzModel.parse(it.readBytes()) } ?: continue
            val ext = dir.listFiles()!!.firstOrNull { it.extension.uppercase() == "EXT" }?.let { AnlzModel.parse(it.readBytes()) }
            val cues = CueIo.read(dat, ext)
            val beats = GridIo.read(dat)
            note("  ${dir.name}: batidas=${beats.size} primeira=${beats.firstOrNull()} cues=${cues.cues}")
            // Gravar os mesmos cues não muda nenhuma entrada já existente.
            note("    ordem DAT: ${dat.sections.map { it.tag }} EXT: ${ext?.sections?.map { it.tag }}")
            val (nd, ne) = CueIo.write(dat, ext, cues, cues.cues)
            // O que não é lista de cue fica byte a byte igual, na mesma ordem.
            assertEquals(dat.sections.filter { it.tag != "PCOB" }.map { it.tag to it.bytes.toList() }, nd.sections.filter { it.tag != "PCOB" }.map { it.tag to it.bytes.toList() }, "${dir.name} DAT")
            if (ext != null) assertEquals(ext.sections.filter { it.tag != "PCO2" && it.tag != "PCOB" }.map { it.tag to it.bytes.toList() }, ne.sections.filter { it.tag != "PCO2" && it.tag != "PCOB" }.map { it.tag to it.bytes.toList() }, "${dir.name} EXT")
            // Cabeçalhos das seções de cue reais, para comparar com o que escrevemos.
            dat.all("PCOB").forEach { note("    DAT PCOB real: tipo=${it.bytes.u32be(12)} num=${it.bytes.u16be(18)} memory_count=${it.bytes.u32be(20)} cabeçalho=${it.bytes.copyOfRange(0, 24).joinToString(" ") { b -> "%02x".format(b) }}") }
            ext?.all("PCOB")?.forEach { note("    EXT PCOB real: tipo=${it.bytes.u32be(12)} num=${it.bytes.u16be(18)} memory_count=${it.bytes.u32be(20)}") }
            ext?.all("PCO2")?.forEach { note("    EXT PCO2 real: tipo=${it.bytes.u32be(12)} num=${it.bytes.u16be(16)}") }
        }
    }

    private fun ByteArray.u32be(at: Int) = ((this[at].toInt() and 255) shl 24) or ((this[at + 1].toInt() and 255) shl 16) or ((this[at + 2].toInt() and 255) shl 8) or (this[at + 3].toInt() and 255)
    private fun ByteArray.u16be(at: Int) = ((this[at].toInt() and 255) shl 8) or (this[at + 1].toInt() and 255)

    @Test
    fun theRealLibraryShowsWhichConventionsTheFormatUses() {
        assumeTrue("REAL_EXPORT não definido", root != null)
        try { conventions() } finally { finish() }
    }

    private fun conventions() {
        val raw = RawPdb.parse(pdb("demo_tracks"))
        val view = LibraryView.of { raw.table(it)?.rows ?: emptyList() }
        note("faixas reais:")
        view.tracks.forEach { note("  ${it.id} '${it.title}' artista='${it.artist}' álbum='${it.album}' gênero='${it.genre}' tom='${it.key}' bpm=${it.bpm} nota=${it.rating} cor=${it.colorId} ano=${it.year} arquivo=${it.filePath} análise=${it.analyzePath} bitrate=${it.bitrate} taxa=${it.sampleRate}") }
        view.playlists.forEach { note("  playlist ${it.id} pai=${it.parentId} '${it.name}' pasta=${it.folder} sort=${it.sort}") }
        view.entries.forEach { (p, l) -> note("  entradas playlist $p: $l") }
        raw.table(PdbType.PLAYLIST_ENTRIES)?.rows?.forEach { note("  entrada bruta: index=${it.u32(0)} faixa=${it.u32(4)} playlist=${it.u32(8)}") }
        raw.table(PdbType.ARTISTS)?.rows?.forEachIndexed { n, r -> note("  artista linha $n: subtipo=${r.u16(0).toString(16)} index_shift=${r.u16(2).toString(16)}") }
        raw.table(PdbType.ALBUMS)?.rows?.forEachIndexed { n, r -> note("  álbum linha $n: subtipo=${r.u16(0).toString(16)} index_shift=${r.u16(2).toString(16)}") }
        raw.table(PdbType.TRACKS)?.rows?.forEachIndexed { n, r -> note("  faixa linha $n (${r.size} bytes): subtipo=${r.u16(0).toString(16)} index_shift=${r.u16(2).toString(16)} bitmask=${r.u32(4).toString(16)} u32@20=${r.u32(20)} u16@24=${r.u16(24)} u16@26=${r.u16(26)} u16@86=${r.u16(86)} @90=${r.u16(90)} @92=${r.u16(92)}") }
        raw.tables.forEach { t -> t.pages.forEach { p ->
            val off = p * raw.pageLen
            note("  página $p (tabela ${t.type}): flags=${(raw.bytes.u32(off + 24) ushr 24).toString(16)} seq=${raw.bytes.u32(off + 16)} u@20=${raw.bytes.u32(off + 20)} livre=${raw.bytes.u16(off + 28)} usado=${raw.bytes.u16(off + 30)} txn=${raw.bytes.u16(off + 32)}/${raw.bytes.u16(off + 34)} @36=${raw.bytes.u16(off + 36)} @38=${raw.bytes.u16(off + 38)} próxima=${raw.bytes.u32(off + 12)}")
        } }
    }

    private fun masked(page: ByteArray, index: Boolean): ByteArray {
        val m = page.copyOf()
        for (r in listOf(4..7, 12..15, 16..19) + if (index) listOf(40..47) else emptyList()) for (k in r) m[k] = 0
        return m
    }

    @Test
    fun theNewPagesAreByteIdenticalToWhatRekordboxWroteForTheSameRows() {
        assumeTrue("REAL_EXPORT não definido", root != null)
        val raw = RawPdb.parse(pdb("empty"))
        val compared = ArrayList<String>()
        try {
            for (type in listOf(PdbType.COLORS, PdbType.COLUMNS, 17, 18)) {
                val t = raw.table(type)!!
                val out = raw.write(mapOf(type to t.rows))
                val fresh = RawPdb.parse(out)
                val nt = fresh.table(type)!!
                assertEquals(t.pages.size, nt.pages.size, "tabela $type: mesmo número de páginas")
                for ((n, pageNo) in t.pages.withIndex()) {
                    val real = raw.bytes.copyOfRange(pageNo * raw.pageLen, (pageNo + 1) * raw.pageLen)
                    val mine = out.copyOfRange(nt.pages[n] * raw.pageLen, (nt.pages[n] + 1) * raw.pageLen)
                    val isIndex = n == 0
                    val a = masked(real, isIndex)
                    val b = masked(mine, isIndex)
                    val diff = a.indices.filter { a[it] != b[it] }
                    compared += "tabela $type página ${if (isIndex) "índice" else "dados"}: ${if (diff.isEmpty()) "idêntica" else "difere em ${diff.take(12)}"}"
                    assertTrue(diff.isEmpty(), "tabela $type, ${if (isIndex) "índice" else "dados"}: bytes diferentes em ${diff.take(12)} (real=${diff.take(6).map { a[it] }}, nosso=${diff.take(6).map { b[it] }})")
                }
            }
        } finally {
            note(compared.joinToString("\n"))
            finish()
        }
    }

    @Test
    fun theLargeRealLibraryIsReadEditedAndSeenIdenticallyByThreeReaders() {
        val heavy = root?.parentFile?.let { File(it, "pdb/num_rows/export.pdb") }?.takeIf { it.isFile }
        assumeTrue("export grande ausente", heavy != null)
        val bytes = heavy!!.readBytes()
        val raw = RawPdb.parse(bytes)
        note("grande: ${bytes.size / raw.pageLen} páginas, faixas=${raw.table(0)!!.rows.size} artistas=${raw.table(2)!!.rows.size} álbuns=${raw.table(3)!!.rows.size} playlists=${raw.table(7)!!.rows.size} entradas=${raw.table(8)!!.rows.size} avisos=${raw.warnings}")
        assertTrue(raw.warnings.isEmpty(), raw.warnings.toString())
        assertContentEquals(bytes, raw.write(emptyMap()))
        val dir = TestVolumes.tempDir("dbm-heavy")
        val editor = os.deckbridge.manager.pdb.PdbEditor(raw)
        val view = editor.view()
        val first = view.tracks.take(40).map { it.id }
        editor.apply(os.deckbridge.manager.pdb.Edit.SetTracks(first, os.deckbridge.manager.pdb.TrackPatch(comment = "editada", rating = 4, genre = "Teste Genre", artist = "Artista Teste")))
        val pl = (view.playlists.maxOfOrNull { it.id } ?: 0) + 1
        editor.apply(os.deckbridge.manager.pdb.Edit.CreatePlaylist(pl, 0, "Lista de Teste", false))
        editor.apply(os.deckbridge.manager.pdb.Edit.AddToPlaylist(pl, view.tracks.drop(100).take(60).map { it.id }))
        val changed = editor.changedTables()
        val out = raw.write(changed)
        val problems = Verifier.check(raw, out, changed)
        note("grande: tabelas alteradas=${changed.keys.sorted()} tamanho ${bytes.size} -> ${out.size}; problemas=$problems")
        if (problems.isNotEmpty()) {
            val fresh = RawPdb.parse(out)
            for ((type, want) in changed) {
                val got = fresh.table(type)!!.rows
                val at = want.indices.firstOrNull { !Verifier.sameRow(type, want[it], got[it]) } ?: continue
                val a = want[at]; val b = got[at]
                val d = (0 until maxOf(a.size, b.size)).filter { (a.getOrElse(it) { 0 }) != (b.getOrElse(it) { 0 }) }
                note("  tabela $type linha $at: tamanhos ${a.size}/${b.size}, bytes diferentes em ${d.take(10)}; plano=${a.take(40).joinToString(" ") { "%02x".format(it) }} | lido=${b.take(40).joinToString(" ") { "%02x".format(it) }}")
            }
        }
        finish()
        assertTrue(problems.isEmpty(), problems.toString())
        val file = File(dir, "export.pdb").also { it.writeBytes(out) }
        val want = editor.view()
        // Kaitai
        val parsers = System.getenv("KAITAI_PARSERS")
        if (parsers != null && File(parsers, "rekordbox_pdb.py").isFile) {
            val proc = ProcessBuilder("python3", "-I", File("tools/kaitai_check.py").absolutePath, parsers, file.path, "--tsv").redirectErrorStream(true).start()
            val lines = proc.inputStream.bufferedReader().readText().lines().filter { it.isNotEmpty() }.map { it.split("\t") }
            assertEquals(0, proc.waitFor())
            val tracks = lines.filter { it[0] == "T" }
            assertEquals(want.tracks.size, tracks.size, "Kaitai: faixas")
            val byId = tracks.associateBy { it[1].toInt() }
            for (t in want.tracks) {
                val k = byId.getValue(t.id)
                assertEquals(flat(t.title), k[2]); assertEquals(flat(t.artist), k[3]); assertEquals(flat(t.genre), k[5]); assertEquals(flat(t.comment), k[8])
            }
            assertEquals(want.playlists.size, lines.count { it[0] == "P" })
            note("grande: Kaitai confere ${tracks.size} faixas e ${want.playlists.size} playlists")
        }
        // Pi
        val g = ProcessBuilder("g++", "-std=c++17", "-O1", "-I", File("..", "android/app/src/main/cpp").path, "-o", File(dir, "sum").path, File("..", "pi/library/pdb_summary.cpp").path).redirectErrorStream(true).start()
        if (g.also { it.inputStream.readBytes() }.waitFor() == 0) {
            val proc = ProcessBuilder(File(dir, "sum").path, file.path).redirectErrorStream(true).start()
            val lines = proc.inputStream.bufferedReader().readText().lines().filter { it.isNotEmpty() }.map { it.split("\t") }
            assertEquals(0, proc.waitFor())
            val tracks = lines.filter { it[0] == "T" }.associateBy { it[1].toInt() }
            assertEquals(want.tracks.size, tracks.size, "Pi: faixas")
            for (t in want.tracks) assertEquals(listOf(flat(t.title), flat(t.artist), flat(t.album), flat(t.genre)), tracks.getValue(t.id).subList(2, 6), "Pi faixa ${t.id}")
            val entries = lines.filter { it[0] == "E" }.groupBy { it[1].toInt() }.mapValues { (_, l) -> l.sortedBy { it[2].toInt() }.map { it[3].toInt() } }
            assertEquals(want.entries.filterValues { it.isNotEmpty() }, entries, "Pi: entradas")
            note("grande: o leitor do Pi confere ${tracks.size} faixas e ${entries.size} playlists com entradas")
        }
        finish()
    }
}
