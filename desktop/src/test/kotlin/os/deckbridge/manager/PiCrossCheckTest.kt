package os.deckbridge.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.EditException
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.TrackPatch

/**
 * O leitor C++ do Pi (`pi/library/rekordbox.h`) é o que lê o pendrive no palco. Este teste compila o resumo dele
 * (`pi/library/pdb_summary.cpp`) e confere que ele vê o que o editor planejou, depois de várias edições aleatórias.
 * Precisa de g++; sem ele o teste é ignorado.
 */
class PiCrossCheckTest {
    private val repo = File("..").canonicalFile

    private fun summaryBinary(): File {
        val out = File(System.getProperty("java.io.tmpdir"), "dbm-pdb-summary")
        val src = File(repo, "pi/library/pdb_summary.cpp")
        if (!out.isFile || out.lastModified() < src.lastModified()) {
            val proc = ProcessBuilder("g++", "-std=c++17", "-O1", "-I", File(repo, "android/app/src/main/cpp").path, "-o", out.path, src.path).redirectErrorStream(true).start()
            val log = proc.inputStream.bufferedReader().readText()
            assumeTrue("g++ não compilou o resumo do Pi: $log", proc.waitFor() == 0)
        }
        return out
    }

    private fun run(bin: File, pdb: File): List<List<String>> {
        val proc = ProcessBuilder(bin.path, pdb.path).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), out)
        return out.lines().filter { it.isNotEmpty() }.map { it.split("\t") }
    }

    @Test
    fun thePiReaderSeesRandomlyEditedDatabasesAsPlanned() {
        assumeTrue("g++ ausente", runCatching { ProcessBuilder("g++", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val bin = summaryBinary()
        val dir = TestVolumes.synthetic()
        val raw = TestVolumes.rawOf(dir)
        val rnd = java.util.Random(77)
        repeat(25) { round ->
            val e = PdbEditor(raw)
            var nextPlaylist = e.view().playlists.maxOf { it.id } + 1
            repeat(12) {
                val v = e.view()
                val edit: Edit = when (rnd.nextInt(5)) {
                    0 -> Edit.SetTracks(listOf(v.tracks[rnd.nextInt(v.tracks.size)].id), TrackPatch(title = "Faixa ${rnd.nextInt(99)} ç", artist = listOf("Zé", "Ana", "Bia", "")[rnd.nextInt(4)], genre = listOf("House", "Techno", "")[rnd.nextInt(3)]))
                    1 -> Edit.CreatePlaylist(nextPlaylist++, 0, "Lista ${rnd.nextInt(100000)}", false)
                    2 -> v.playlists.filter { !it.folder }.let { l -> Edit.AddToPlaylist(l[rnd.nextInt(l.size)].id, v.tracks.shuffled(rnd).take(5).map { it.id }) }
                    3 -> v.playlists.filter { !it.folder }.let { l -> val p = l[rnd.nextInt(l.size)]; Edit.ReorderPlaylist(p.id, (v.entries[p.id] ?: emptyList()).shuffled(rnd)) }
                    else -> Edit.RenamePlaylist(v.playlists[rnd.nextInt(v.playlists.size)].id, "Nome ${rnd.nextInt(100000)}")
                }
                try {
                    e.apply(edit)
                } catch (_: EditException) {
                }
            }
            val file = File(dir, "r$round.pdb")
            file.writeBytes(raw.write(e.changedTables()))
            val lines = run(bin, file)
            val want = e.view()
            val tracks = lines.filter { it[0] == "T" }
            assertEquals(want.tracks.size, tracks.size, "rodada $round")
            for (t in want.tracks) {
                val k = tracks.first { it[1] == t.id.toString() }
                assertEquals(listOf(t.title, t.artist, t.album, t.genre, t.key, t.filePath), k.subList(2, 8), "rodada $round faixa ${t.id}")
            }
            val playlists = lines.filter { it[0] == "P" }
            assertEquals(want.playlists.size, playlists.size)
            for (p in want.playlists) {
                assertEquals(listOf(p.parentId.toString(), p.name, if (p.folder) "1" else "0", p.sort.toString()), playlists.first { it[1] == p.id.toString() }.subList(2, 6))
            }
            val entries = lines.filter { it[0] == "E" }.groupBy { it[1].toInt() }.mapValues { (_, l) -> l.sortedBy { it[2].toInt() }.map { it[3].toInt() } }
            assertEquals(want.entries.filterValues { it.isNotEmpty() }, entries, "rodada $round")
        }
    }

    private fun anlzLines(bin: File, vararg files: File): List<List<String>> {
        val proc = ProcessBuilder(listOf(bin.path, "--anlz") + files.map { it.path }).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), out)
        return out.lines().filter { it.isNotEmpty() }.map { it.split("\t") }
    }

    @Test
    fun thePiReaderSeesTheAnalysisWrittenByCuesGridImportAndReanalysis() {
        assumeTrue("g++ ausente", runCatching { ProcessBuilder("g++", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val bin = summaryBinary()
        val dir = TestVolumes.synthetic()
        val vol = os.deckbridge.manager.volume.Volumes.open(dir)
        val e = PdbEditor(vol.raw, vol.root)
        val t = e.view().tracks.first { File(dir, it.analyzePath.trimStart('/')).isFile && File(dir, it.filePath.trimStart('/')).isFile }
        val cues = listOf(
            os.deckbridge.manager.anlz.Cue(1, 400, comment = "Início", rgb = 0xff3b3b),
            os.deckbridge.manager.anlz.Cue(6, 3000, comment = "Drop é ç", rgb = 0xb44dff),
            os.deckbridge.manager.anlz.Cue(0, 400, comment = "Memória"),
            os.deckbridge.manager.anlz.Cue(0, 1000, loopEndMs = 2000, comment = "Loop"),
        )
        e.apply(Edit.SetCues(t.id, cues))
        e.apply(Edit.SetGrid(t.id, 127.5, 90))
        val src = File(TestVolumes.tempDir("dbm-pi-src"), "Banda - Teste.wav")
        src.writeBytes(os.deckbridge.logic.encodeWavMono16(os.deckbridge.logic.click(bpm = 122.0, seconds = 24, rate = 22050, freq = 300.0), 22050))
        val prepared = os.deckbridge.manager.importer.ImportPrep.prepare(src)
        e.apply(Edit.ImportTrack(prepared, "Teste", "Banda", "Disco", "House", prepared.read.keyName, 2026, 1, prepared.suggestedCues))
        os.deckbridge.manager.volume.SafeWriter.apply(vol, e, listOf("sessão"), os.deckbridge.manager.volume.Backups(TestVolumes.tempDir("dbm-pi-bk")))

        val reopened = os.deckbridge.manager.volume.Volumes.open(dir)
        val view = os.deckbridge.manager.pdb.LibraryView.of { reopened.raw.table(it)?.rows ?: emptyList() }
        val model = ManagerModel(os.deckbridge.manager.volume.Backups(TestVolumes.tempDir("dbm-pi-bk2")))
        model.open(dir)
        for (id in listOf(t.id, view.tracks.maxOf { it.id })) {
            val track = view.track(id)!!
            val rel = track.analyzePath.trimStart('/')
            val stem = File(dir, rel.dropLast(3))
            val lines = anlzLines(bin, File("${stem.path}DAT"), File("${stem.path}EXT"), File("${stem.path}2EX").takeIf { it.isFile } ?: File("${stem.path}EXT"))
            val mine = model.trackFiles(model.view!!.track(id)!!)
            val beats = lines.filter { it[0] == "B" }
            assertEquals(mine.beats.size, beats.size, "faixa $id: batidas")
            mine.beats.forEachIndexed { n, b -> assertEquals(listOf(b.timeMs.toString(), b.inBar.toString(), b.tempo100.toString()), beats[n].drop(1), "faixa $id batida $n") }
            val seen = lines.filter { it[0] == "C" }.map { listOf(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4]) }.sortedWith(compareBy({ it[0] as Int }, { it[1] as Int }))
            val want = mine.cues.map { listOf(it.hot, it.timeMs, it.loopEndMs ?: 0, it.comment) }.sortedWith(compareBy({ it[0] as Int }, { it[1] as Int }))
            assertEquals(want, seen, "faixa $id: cues")
            assertTrue(lines.none { it[0] == "I" }, "faixa $id: ${lines.filter { it[0] == "I" }}")
        }
        // E o banco continua legível pelo Pi com a faixa nova.
        val summary = run(bin, File(dir, "PIONEER/rekordbox/export.pdb"))
        assertTrue(summary.any { it[0] == "T" && it[2] == "Teste" && it[3] == "Banda" && it[4] == "Disco" })
    }
}
