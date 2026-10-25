package os.deckbridge.manager

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import os.deckbridge.logic.mergeAnalysis
import os.deckbridge.logic.parseAnlz
import os.deckbridge.manager.anlz.AnlzModel
import os.deckbridge.manager.anlz.Cue
import os.deckbridge.manager.anlz.CueIo
import os.deckbridge.manager.importer.AudioIo
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.EditException
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.PlaylistRows
import os.deckbridge.manager.pdb.RawPdb
import os.deckbridge.manager.volume.Backups
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.SafeWriter
import os.deckbridge.manager.volume.VolumeException
import os.deckbridge.manager.volume.Volumes
import os.deckbridge.manager.volume.sha256

class FileEditsTest {
    private val backups = Backups(TestVolumes.tempDir("dbm-fe"))

    private fun tree(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to sha256(it) }

    private fun view(dir: File): LibraryView {
        val raw = RawPdb.parse(File(dir, PDB_RELATIVE).readBytes())
        return LibraryView.of { raw.table(it)?.rows ?: emptyList() }
    }

    /** Uma faixa do pendrive sintético cujo áudio e cujos ANLZ existem no disco. */
    private fun withFiles(dir: File, v: LibraryView) = v.tracks.first { t ->
        File(dir, t.filePath.trimStart('/')).isFile && File(dir, t.analyzePath.trimStart('/')).isFile
    }

    @Test
    fun cuesAndGridAreWrittenToTheUsbAndReadBack() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        val e = PdbEditor(vol.raw, vol.root)
        val t = withFiles(dir, e.view())
        val before = tree(dir)
        val datRel = t.analyzePath.trimStart('/')
        val cues = listOf(Cue(1, 500, comment = "Início", rgb = 0xff3b3b), Cue(4, 4000, comment = "Drop", rgb = 0x3ddc6a), Cue(0, 500, comment = "Memória"), Cue(0, 2000, loopEndMs = 3000, comment = "Loop"))
        e.apply(Edit.SetCues(t.id, cues))
        e.apply(Edit.SetGrid(t.id, 126.0, 120))
        SafeWriter.apply(vol, e, listOf("cues e grade"), backups)
        val after = tree(dir)
        // Só o banco e os ANLZ dessa faixa mudaram. Nenhum áudio.
        val touched = after.filter { (k, v) -> before[k] != v }.keys
        assertTrue(touched.all { it == PDB_RELATIVE || it.startsWith(datRel.dropLast(3).substringBeforeLast('/')) && it.substringAfterLast('.').uppercase() in setOf("DAT", "EXT") }, touched.toString())
        assertTrue(touched.contains(datRel))
        val dat = AnlzModel.parse(File(dir, datRel).readBytes())
        val extFile = File(dir, datRel.dropLast(3) + "EXT")
        val merged = mergeAnalysis(parseAnlz(File(dir, datRel).readBytes()), parseAnlz(extFile.readBytes()))
        assertEquals(126.0, merged.beats.first().bpm, 1e-9)
        assertEquals(120, merged.beats.first().timeMs)
        assertEquals(cues.size, merged.cues.size)
        assertEquals(12600, view(dir).track(t.id)!!.bpm.times(100).toInt())
        assertEquals(cues.sortedBy { it.hot }.map { it.hot }.toSet(), CueIo.read(dat, AnlzModel.parse(extFile.readBytes())).cues.map { it.hot }.toSet())
    }

    @Test
    fun invalidCueAndGridRequestsAreRefused() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        val e = PdbEditor(vol.raw, vol.root)
        val t = withFiles(dir, e.view())
        assertFailsWith<EditException> { e.apply(Edit.SetCues(t.id, listOf(Cue(1, 0), Cue(1, 100)))) }
        assertFailsWith<EditException> { e.apply(Edit.SetCues(t.id, listOf(Cue(0, 5000, loopEndMs = 4000)))) }
        assertFailsWith<EditException> { e.apply(Edit.SetCues(t.id, listOf(Cue(1, 99_999_999)))) }
        assertFailsWith<EditException> { e.apply(Edit.SetGrid(t.id, 5.0, 0)) }
        assertFailsWith<EditException> { e.apply(Edit.SetGrid(t.id, 120.0, -1)) }
        assertTrue(e.fileOps().isEmpty() && e.changedTables().isEmpty())
    }

    @Test
    fun renamingMovesTheAudioUpdatesTheDatabaseAndTheAnalysisPathAndRestoreUndoesIt() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        val e = PdbEditor(vol.raw, vol.root)
        val t = withFiles(dir, e.view())
        val oldRel = t.filePath.trimStart('/')
        val newRel = oldRel.substringBefore('/') + "/Artista Novo/Álbum Novo/Nome Novo." + oldRel.substringAfterLast('.')
        val audio = File(dir, oldRel).readBytes()
        e.apply(Edit.RenameFiles(listOf(Edit.FileRename(t.id, newRel))))
        val report = SafeWriter.apply(vol, e, listOf("renomear"), backups)
        assertEquals(1, report.filesMoved)
        assertFalse(File(dir, oldRel).exists())
        assertContentEquals(audio, File(dir, newRel).readBytes())
        val row = view(dir).track(t.id)!!
        assertEquals(newRel.substringAfterLast('/'), row.fileName)
        assertTrue(row.filePath.endsWith(newRel))
        assertTrue(File(dir, row.filePath.trimStart('/')).isFile, "o banco aponta para um arquivo que existe")
        // Restaurar volta o nome e o banco.
        val result = backups.restore(report.backup, dir)
        assertEquals(1, result.movedBack)
        assertContentEquals(audio, File(dir, oldRel).readBytes())
        assertTrue(File(dir, view(dir).track(t.id)!!.filePath.trimStart('/')).isFile)
    }

    @Test
    fun badRenamesAreRefused() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        val e = PdbEditor(vol.raw, vol.root)
        val tracks = e.view().tracks.filter { File(dir, it.filePath.trimStart('/')).isFile }
        val a = tracks[0]
        val b = tracks[1]
        val top = a.filePath.trimStart('/').substringBefore('/')
        val ext = a.filePath.substringAfterLast('.')
        fun rename(path: String, id: Int = a.id) = e.apply(Edit.RenameFiles(listOf(Edit.FileRename(id, path))))
        assertFailsWith<EditException> { rename("$top/../fora.$ext") }
        assertFailsWith<EditException> { rename("$top/a:b.$ext") }
        assertFailsWith<EditException> { rename("$top/novo.mp9") }
        assertFailsWith<EditException> { rename("Outra/novo.$ext") }
        assertFailsWith<EditException> { rename(b.filePath.trimStart('/')) }
        assertFailsWith<EditException> { e.apply(Edit.RenameFiles(listOf(Edit.FileRename(a.id, "$top/igual.$ext"), Edit.FileRename(b.id, "$top/igual.$ext")))) }
    }

    @Test
    fun removingTracksCleansPlaylistsNamesAndKeepsAudio() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        val e = PdbEditor(vol.raw, vol.root)
        val v0 = e.view()
        val victims = v0.tracks.filter { t -> v0.entries.values.any { t.id in it } }.take(3).map { it.id }
        val audio = victims.map { v0.track(it)!!.filePath.trimStart('/') }.filter { File(dir, it).isFile }
        e.apply(Edit.RemoveTracks(victims))
        SafeWriter.apply(vol, e, listOf("remover"), backups)
        val v = view(dir)
        assertTrue(v.tracks.none { it.id in victims })
        assertTrue(v.entries.values.none { list -> list.any { it in victims } })
        // Posições das playlists continuam contíguas.
        val raw = RawPdb.parse(File(dir, PDB_RELATIVE).readBytes())
        raw.table(8)!!.rows.groupBy { PlaylistRows.entryPlaylist(it) }.forEach { (p, rows) ->
            val idx = rows.map { PlaylistRows.entryIndex(it) }.sorted()
            assertEquals(List(idx.size) { idx.first() + it }, idx, "playlist $p")
        }
        assertTrue(audio.all { File(dir, it).isFile }, "o áudio nunca é apagado")
        assertEquals(v0.tracks.size - victims.size, v.tracks.size)
    }

    @Test
    fun anOperationThatFailsHalfWayUndoesEverything() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        val e = PdbEditor(vol.raw, vol.root)
        val t = withFiles(dir, e.view())
        e.apply(Edit.SetCues(t.id, listOf(Cue(1, 500, comment = "x"))))
        e.apply(Edit.SetTracks(listOf(t.id), os.deckbridge.manager.pdb.TrackPatch(comment = "mudou")))
        val before = tree(dir)
        // Sabota o destino de uma gravação: o ANLZ passa a ser uma pasta no meio da operação.
        val ext = File(dir, t.analyzePath.trimStart('/').dropLast(3) + "EXT")
        ext.delete()
        ext.mkdirs()
        val broken = tree(dir)
        assertFailsWith<VolumeException> { SafeWriter.apply(vol, e, listOf("falha"), backups) }
        assertEquals(broken, tree(dir), "nada ficou pela metade")
        assertEquals(before.getValue(PDB_RELATIVE), sha256(File(dir, PDB_RELATIVE)))
    }

    @Test
    fun importingATrackCopiesAudioBuildsAnalysisAndAddsTheRow() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        // Áudio de teste sintético (click a 124 BPM), WAV mono de 22,05 kHz, fora do pendrive.
        val src = File(TestVolumes.tempDir("dbm-src"), "Artista Teste - Faixa Nova.wav")
        val samples = os.deckbridge.logic.click(bpm = 124.0, seconds = 24, rate = 22050, freq = 440.0)
        src.writeBytes(os.deckbridge.logic.encodeWavMono16(samples, 22050))
        val prepared = ImportPrep.prepare(src)
        assertEquals("Faixa Nova", prepared.info.title)
        assertEquals("Artista Teste", prepared.info.artist)
        assertEquals(124.0, prepared.read.bpm, 1.5)
        val e = PdbEditor(vol.raw, vol.root)
        val before = tree(dir)
        val countBefore = e.view().tracks.size
        e.apply(Edit.ImportTrack(prepared, prepared.info.title, prepared.info.artist, "Álbum de Teste", "House", prepared.read.keyName, 2026, 3, prepared.suggestedCues))
        val report = SafeWriter.apply(vol, e, listOf("importar"), backups)
        assertEquals(4, report.filesCreated)
        val v = view(dir)
        assertEquals(countBefore + 1, v.tracks.size)
        val t = v.tracks.maxBy { it.id }
        assertEquals("Faixa Nova", t.title)
        assertEquals("Artista Teste", t.artist)
        assertEquals("Álbum de Teste", t.album)
        assertEquals("House", t.genre)
        val audio = File(dir, t.filePath.trimStart('/'))
        assertContentEquals(src.readBytes(), audio.readBytes())
        val dat = File(dir, t.analyzePath.trimStart('/'))
        val merged = mergeAnalysis(parseAnlz(dat.readBytes()), parseAnlz(File(dir, t.analyzePath.trimStart('/').dropLast(3) + "EXT").readBytes()), parseAnlz(File(dir, t.analyzePath.trimStart('/').dropLast(3) + "2EX").readBytes()))
        assertTrue(merged.beats.size > 30 && merged.issues.isEmpty() && merged.rgbDetail != null)
        assertEquals(prepared.suggestedCues.size, merged.cues.size)
        // O que já estava no pendrive não mudou, exceto o banco.
        for ((k, sha) in before) if (k != PDB_RELATIVE) assertEquals(sha, sha256(File(dir, k)), k)
        // Importar duas vezes o mesmo arquivo não sobrescreve: o nome ganha um número.
        val vol2 = Volumes.open(dir)
        val e2 = PdbEditor(vol2.raw, vol2.root)
        e2.apply(Edit.ImportTrack(prepared, prepared.info.title, prepared.info.artist, "Álbum de Teste", "House", "", 0, 0, emptyList()))
        SafeWriter.apply(vol2, e2, listOf("de novo"), backups)
        val again = view(dir).tracks.maxBy { it.id }
        assertTrue(again.filePath != t.filePath && File(dir, again.filePath.trimStart('/')).isFile)
        // Restaurar a primeira importação remove o ANLZ criado e mantém o áudio.
        val result = backups.restore(report.backup, dir)
        assertEquals(1, result.keptAudio.size)
        assertTrue(audio.isFile)
        assertFalse(dat.exists())
        assertEquals(countBefore, view(dir).tracks.size)
    }

    @Test
    fun compressedAudioNeedsFfmpegAndSaysSo() {
        val src = File(TestVolumes.tempDir("dbm-src"), "x.mp3").apply { writeBytes(ByteArray(2000) { 1 }) }
        val failure = runCatching { ImportPrep.prepare(src) }.exceptionOrNull()
        assertTrue(failure is os.deckbridge.manager.importer.ImportException)
        if (!AudioIo.ffmpeg) assertTrue(failure!!.message!!.contains("ffmpeg"))
    }
}

class EncodingCheckTest {
    @Test
    fun accentedNamesAreRefusedBeforeWritingWhenTheSystemCannotEncodeThem() {
        val ops = listOf(os.deckbridge.manager.volume.FileOp.Move("Contents/a.wav", "Contents/Álbum/a.wav"))
        assertTrue(SafeWriter.encodingProblems(ops, "US-ASCII").single().contains("LANG=C.UTF-8"))
        assertTrue(SafeWriter.encodingProblems(ops, "UTF-8").isEmpty())
        assertTrue(SafeWriter.encodingProblems(listOf(os.deckbridge.manager.volume.FileOp.Put("a/b.DAT", ByteArray(1))), "US-ASCII").isEmpty())
    }
}
