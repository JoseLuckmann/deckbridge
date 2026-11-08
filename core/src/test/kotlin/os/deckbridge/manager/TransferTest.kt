package os.deckbridge.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.transfer.ExportException
import os.deckbridge.manager.transfer.FileStorage
import os.deckbridge.manager.transfer.LocalLibrary
import os.deckbridge.manager.transfer.UsbExporter
import os.deckbridge.manager.transfer.UsbImporter
import os.deckbridge.manager.volume.Backups
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.SafeWriter
import os.deckbridge.manager.volume.Volumes
import os.deckbridge.manager.volume.sha256

class TransferTest {
    private val backupBase = TestVolumes.tempDir("dbm-tbk")

    private fun localLibrary() = TestVolumes.fromScratch(backupBase)

    @Test fun `biblioteca nova abre vazia e exporta para um pendrive vazio`() {
        val local = localLibrary()
        val view = PdbEditor(Volumes.open(local).raw, local).view()
        assertEquals(2, view.tracks.size)
        val usb = TestVolumes.tempDir("dbm-usb-vazio")
        val report = UsbExporter(backupBase).export(local, FileStorage(usb, "PENDRIVE"), replaceExisting = false)
        assertEquals(2, report.tracks)
        assertEquals(1, report.playlists)
        assertEquals(null, report.backupDir)
        assertEquals(report.files, report.copied)
        assertEquals(sha256(File(local, PDB_RELATIVE)), sha256(File(usb, PDB_RELATIVE)))
        val onUsb = PdbEditor(Volumes.open(usb).raw, usb).view()
        assertEquals(view.tracks.map { it.title }, onUsb.tracks.map { it.title })
        for (t in onUsb.tracks) {
            assertTrue(File(usb, t.filePath.trimStart('/')).isFile, t.filePath)
            assertTrue(File(usb, t.analyzePath.trimStart('/')).isFile, t.analyzePath)
        }
        assertTrue(File(usb, "PIONEER/rekordbox/exportExt.pdb").isFile)
        assertTrue(usb.walkTopDown().none { it.name.endsWith(".deckbridge-novo") })
    }

    @Test fun `exportar de novo nao copia o que ja esta la e substituir exige confirmacao com backup`() {
        val local = localLibrary()
        val usb = TestVolumes.tempDir("dbm-usb2")
        val exporter = UsbExporter(backupBase)
        exporter.export(local, FileStorage(usb, "PENDRIVE"), replaceExisting = false)
        val again = assertFailsWith<ExportException> { exporter.export(local, FileStorage(usb, "PENDRIVE"), replaceExisting = false) }
        assertEquals("E_TARGET_HAS_LIBRARY", again.code)
        val second = exporter.export(local, FileStorage(usb, "PENDRIVE"), replaceExisting = true)
        assertEquals(0, second.copied)
        assertEquals(second.files, second.skipped)
        val bk = second.backupDir!!
        assertTrue(File(bk, PDB_RELATIVE).isFile)
        assertEquals(sha256(File(usb, PDB_RELATIVE)), sha256(File(bk, PDB_RELATIVE)))
    }

    @Test fun `substituir a biblioteca de um pendrive do rekordbox guarda a antiga e nao apaga audio`() {
        val local = localLibrary()
        val usb = TestVolumes.synthetic()
        val oldPdb = sha256(File(usb, PDB_RELATIVE))
        val oldAudio = usb.walkTopDown().filter { it.isFile && it.extension.lowercase() in listOf("wav", "mp3", "flac") }.map { it.name to sha256(it) }.toList()
        val report = UsbExporter(backupBase).export(local, FileStorage(usb, "PENDRIVE"), replaceExisting = true)
        assertEquals(oldPdb, sha256(File(report.backupDir!!, PDB_RELATIVE)))
        for ((name, sha) in oldAudio) assertEquals(sha, sha256(usb.walkTopDown().first { it.name == name }), name)
        assertTrue(oldAudio.isNotEmpty())
        assertEquals(oldAudio.map { it.first }.sorted(), report.orphanAudio.map { it.substringAfterLast('/') }.sorted())
    }

    @Test fun `audio que falta na biblioteca local aborta antes de tocar no pendrive`() {
        val local = localLibrary()
        val t = PdbEditor(Volumes.open(local).raw, local).view().tracks.first()
        File(local, t.filePath.trimStart('/')).delete()
        val usb = TestVolumes.tempDir("dbm-usb3")
        val e = assertFailsWith<ExportException> { UsbExporter(backupBase).export(local, FileStorage(usb), replaceExisting = false) }
        assertEquals("E_LOCAL_AUDIO_MISSING", e.code)
        assertTrue(usb.listFiles().isNullOrEmpty())
    }

    @Test fun `importar do pendrive so le o pendrive e traz banco, analises e audio`() {
        val local = localLibrary()
        val usb = TestVolumes.tempDir("dbm-usb4")
        UsbExporter(backupBase).export(local, FileStorage(usb, "PENDRIVE"), replaceExisting = false)
        val before = usb.walkTopDown().filter { it.isFile }.associate { it.path to sha256(it) }
        val other = TestVolumes.tempDir("dbm-local2")
        LocalLibrary.ensure(other)
        val r = UsbImporter(backupBase).import(FileStorage(usb), other, includeAudio = true)
        assertEquals(2, r.tracks)
        assertEquals(before, usb.walkTopDown().filter { it.isFile }.associate { it.path to sha256(it) })
        assertEquals(sha256(File(usb, PDB_RELATIVE)), sha256(File(other, PDB_RELATIVE)))
        val v = PdbEditor(Volumes.open(other).raw, other).view()
        for (t in v.tracks) assertTrue(File(other, t.filePath.trimStart('/')).isFile)
        assertTrue(r.backupDir != null)
    }

    @Test fun `ensure nao sobrescreve uma biblioteca existente`() {
        val local = localLibrary()
        val sha = sha256(File(local, PDB_RELATIVE))
        LocalLibrary.ensure(local)
        assertEquals(sha, sha256(File(local, PDB_RELATIVE)))
    }
}
