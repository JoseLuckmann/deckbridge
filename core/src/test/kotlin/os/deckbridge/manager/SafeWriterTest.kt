package os.deckbridge.manager

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.RawPdb
import os.deckbridge.manager.pdb.TrackPatch
import os.deckbridge.manager.volume.Backups
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.SafeWriter
import os.deckbridge.manager.volume.Verifier
import os.deckbridge.manager.volume.VolumeException
import os.deckbridge.manager.volume.Volumes
import os.deckbridge.manager.volume.sha256

class SafeWriterTest {
    private val backupDir = TestVolumes.tempDir("dbm-bk")
    private val backups = Backups(backupDir)

    private fun tree(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to sha256(it.readBytes()) }

    private fun edited(vol: os.deckbridge.manager.volume.LoadedVolume): PdbEditor {
        val e = PdbEditor(vol.raw)
        e.apply(Edit.SetTracks(listOf(e.view().tracks[1].id), TrackPatch(title = "Editada", rating = 3)))
        return e
    }

    @Test
    fun applyingWritesTheNewDatabaseKeepsABackupAndTouchesNothingElse() {
        val dir = TestVolumes.synthetic()
        val before = tree(dir)
        val vol = Volumes.open(dir)
        val report = SafeWriter.apply(vol, edited(vol), listOf("Faixa 2: título"), backups)
        val after = tree(dir)
        assertEquals(before.keys, after.keys, "nenhum arquivo foi criado ou apagado (inclusive o temporário)")
        for ((path, sha) in before) if (path != PDB_RELATIVE) assertEquals(sha, after[path], "$path não devia mudar")
        assertTrue(after.getValue(PDB_RELATIVE) != before.getValue(PDB_RELATIVE))
        assertEquals(report.shaAfter, after.getValue(PDB_RELATIVE))
        assertEquals(before.getValue(PDB_RELATIVE), report.backup.files.single().sha256)
        assertEquals(before.getValue(PDB_RELATIVE), sha256(File(report.backup.dir, PDB_RELATIVE).readBytes()))
        assertEquals("Editada", Volumes.open(dir).raw.let { raw -> os.deckbridge.manager.pdb.LibraryView.of { raw.table(it)?.rows ?: emptyList() } }.tracks[1].title)
        assertEquals(listOf("Faixa 2: título"), backups.list(dir).single().changes)
    }

    @Test
    fun restoringBringsBackTheOriginalBytesAndCanItselfBeUndone() {
        val dir = TestVolumes.synthetic()
        val original = sha256(File(dir, PDB_RELATIVE).readBytes())
        val vol = Volumes.open(dir)
        val report = SafeWriter.apply(vol, edited(vol), listOf("x"), backups)
        val undo = backups.restore(report.backup, dir).before
        assertEquals(original, sha256(File(dir, PDB_RELATIVE).readBytes()))
        backups.restore(undo, dir)
        assertEquals(report.shaAfter, sha256(File(dir, PDB_RELATIVE).readBytes()))
    }

    @Test
    fun aVolumeChangedByAnotherProgramIsRefusedAndLeftAlone() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        val editor = edited(vol)
        val file = File(dir, PDB_RELATIVE)
        val other = file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        file.writeBytes(other)
        val e = assertFailsWith<VolumeException> { SafeWriter.apply(vol, editor, listOf("x"), backups) }
        assertEquals("E_VOLUME_CHANGED", e.code)
        assertContentEquals(other, file.readBytes())
        assertTrue(backups.list(dir).isEmpty(), "sem backup quando nada foi gravado")
    }

    @Test
    fun nothingToDoIsRefusedWithoutABackup() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        assertEquals("E_NOTHING_TO_DO", assertFailsWith<VolumeException> { SafeWriter.apply(vol, PdbEditor(vol.raw), emptyList(), backups) }.code)
        assertTrue(backups.list(dir).isEmpty())
    }

    @Test
    fun appliesToACopyOfTheVolumeAndLeavesTheSourceUntouched() {
        val source = TestVolumes.synthetic()
        val copy = TestVolumes.synthetic()
        val before = tree(source)
        val vol = Volumes.open(source)
        SafeWriter.apply(vol, edited(vol), listOf("copia"), backups, target = copy)
        assertEquals(before, tree(source))
        assertTrue(sha256(File(copy, PDB_RELATIVE).readBytes()) != before.getValue(PDB_RELATIVE))
    }

    @Test
    fun aCorruptBackupIsNotRestored() {
        val dir = TestVolumes.synthetic()
        val vol = Volumes.open(dir)
        val report = SafeWriter.apply(vol, edited(vol), listOf("x"), backups)
        val bad = File(report.backup.dir, PDB_RELATIVE)
        bad.writeBytes(bad.readBytes().also { it[100] = (it[100] + 1).toByte() })
        val afterApply = File(dir, PDB_RELATIVE).readBytes()
        assertEquals("E_BACKUP_CORRUPT", assertFailsWith<VolumeException> { backups.restore(report.backup, dir) }.code)
        assertContentEquals(afterApply, File(dir, PDB_RELATIVE).readBytes())
    }

    @Test
    fun notAnExportedVolumeIsRefused() {
        val empty = TestVolumes.tempDir("dbm-empty")
        assertEquals("E_RB_EXPORT_NOT_FOUND", assertFailsWith<VolumeException> { Volumes.open(empty) }.code)
        File(empty, PDB_RELATIVE).apply { parentFile.mkdirs() }.writeBytes(ByteArray(100) { 7 })
        assertFailsWith<VolumeException> { Volumes.open(empty) }
    }

    @Test
    fun theVerifierCatchesTamperedOutput() {
        val dir = TestVolumes.synthetic()
        val raw = TestVolumes.rawOf(dir)
        val e = PdbEditor(raw)
        e.apply(Edit.SetTracks(listOf(e.view().tracks[0].id), TrackPatch(title = "x")))
        val changed = e.changedTables()
        val good = raw.write(changed)
        assertTrue(Verifier.check(raw, good, changed).isEmpty())

        val alteredOldPage = good.copyOf().also { it[raw.pageLen * 2 + 50] = (it[raw.pageLen * 2 + 50] + 1).toByte() }
        assertTrue(Verifier.check(raw, alteredOldPage, changed).isNotEmpty(), "página antiga alterada")

        val parsed = RawPdb.parse(good)
        val newTracksPage = parsed.table(0)!!.pages.last() * raw.pageLen
        val brokenRow = good.copyOf().also { it[newTracksPage + 60] = (it[newTracksPage + 60] + 1).toByte() }
        assertTrue(Verifier.check(raw, brokenRow, changed).isNotEmpty(), "linha nova alterada")

        assertTrue(Verifier.check(raw, good.copyOf(good.size - raw.pageLen), changed).isNotEmpty(), "arquivo truncado")
        assertTrue(Verifier.check(raw, ByteArray(10), changed).isNotEmpty())
    }

    @Test
    fun repeatedApplyCyclesStayValidAndCompactingShrinksTheFile() {
        val dir = TestVolumes.synthetic()
        repeat(6) { n ->
            val vol = Volumes.open(dir)
            val e = PdbEditor(vol.raw)
            e.apply(Edit.SetTracks(listOf(e.view().tracks[n % 5].id), TrackPatch(comment = "volta $n")))
            SafeWriter.apply(vol, e, listOf("v$n"), backups)
        }
        val vol = Volumes.open(dir)
        assertTrue(vol.raw.warnings.isEmpty(), vol.raw.warnings.toString())
        val compact = vol.raw.compact()
        assertTrue(compact.size < vol.raw.bytes.size)
        val a = RawPdb.parse(compact)
        for (t in vol.raw.tables) assertEquals(t.rows.size, a.table(t.type)!!.rows.size)
        assertFalse(backups.list(dir).isEmpty())
        assertEquals(6, backups.list(dir).size)
    }
}
