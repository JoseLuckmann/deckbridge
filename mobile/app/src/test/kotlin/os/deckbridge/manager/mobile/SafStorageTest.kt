package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import os.deckbridge.manager.transfer.StorageException
import os.deckbridge.manager.transfer.UsbExporter
import os.deckbridge.manager.transfer.UsbImporter
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.sha256

/** O caminho do pendrive no Android: seletor de documentos (SAF). Aqui contra um provedor de brinquedo sobre uma pasta. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafStorageTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var usb: File

    @Before fun setUp() {
        usb = File(ctx.cacheDir, "usb-${System.nanoTime()}").apply { mkdirs() }
        FakeDocumentsProvider.base = usb
        FakeDocumentsProvider.failRenameTo = null
        val info = ProviderInfo().apply {
            authority = FakeDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
        }
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(info)
    }

    private fun storage() = SafStorage(ctx, DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, "root"), "TESTE")

    @Test fun writesNewAndReplacesExistingFilesWithoutLeavingTemps() {
        val s = storage()
        s.write("PIONEER/rekordbox/export.pdb", ByteArray(5000) { 1 })
        assertEquals(5000, s.length("PIONEER/rekordbox/export.pdb"))
        s.write("PIONEER/rekordbox/export.pdb", ByteArray(7000) { 2 })
        assertContentEquals(ByteArray(7000) { 2 }, File(usb, "PIONEER/rekordbox/export.pdb").readBytes())
        assertContentEquals(ByteArray(7000) { 2 }, s.read("pioneer/REKORDBOX/export.pdb"))
        assertEquals(listOf("export.pdb"), File(usb, "PIONEER/rekordbox").list()!!.toList())
        assertEquals(-1, s.length("PIONEER/nada"))
        assertEquals(listOf("PIONEER/rekordbox/export.pdb"), s.listFiles(""))
    }

    @Test fun aRefusedRenameKeepsTheOldFileAndCleansUp() {
        val s = storage()
        s.write("a/b.bin", ByteArray(10) { 7 })
        FakeDocumentsProvider.failRenameTo = "b.bin"
        assertFailsWith<StorageException> { s.write("a/b.bin", ByteArray(20) { 9 }) }
        assertContentEquals(ByteArray(10) { 7 }, File(usb, "a/b.bin").readBytes())
        assertEquals(listOf("b.bin"), File(usb, "a").list()!!.toList())
    }

    @Test fun exportsAWholeLibraryAndImportsItBack() {
        val app = ManagerApp(ctx, File(ctx.cacheDir, "lib-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 12) }
        val s = storage()
        val exporter = UsbExporter(app.backupsDir)
        assertEquals(null, exporter.inspect(s))
        val report = exporter.export(app.root, s, replaceExisting = false)
        assertEquals(10, report.tracks)
        assertEquals(sha256(File(app.root, PDB_RELATIVE)), sha256(File(usb, PDB_RELATIVE)))
        assertEquals(10, exporter.inspect(s)!!.tracks)
        for (rel in exporter.requiredFiles(app.root).first) assertContentEquals(File(app.root, rel).readBytes(), File(usb, rel).readBytes(), rel)
        assertTrue(usb.walkTopDown().none { it.name.contains(".deckbridge-") })
        // Segunda exportação: nada a copiar.
        val again = exporter.export(app.root, s, replaceExisting = true)
        assertEquals(0, again.copied)
        // E trazer de volta para outra pasta.
        val other = File(ctx.cacheDir, "outra-${System.nanoTime()}").apply { mkdirs() }
        val back = UsbImporter(app.backupsDir).import(s, other, includeAudio = true)
        assertEquals(10, back.tracks)
        assertEquals(sha256(File(usb, PDB_RELATIVE)), sha256(File(other, PDB_RELATIVE)))
    }

    @Test fun mergesALibraryFromThePendriveIntoAnEmptyOneThroughSaf() {
        val app = ManagerApp(ctx, File(ctx.cacheDir, "lib-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 12) }
        val s = storage()
        UsbExporter(app.backupsDir).export(app.root, s, replaceExisting = false)
        val other = ManagerApp(ctx, File(ctx.cacheDir, "outra-${System.nanoTime()}/biblioteca/dados")).also { it.start() }
        val r = os.deckbridge.manager.transfer.UsbMerger(other.model.backups, ctx.cacheDir).merge(s, other.root)
        assertEquals(10, r.added)
        other.model.open(other.root)
        assertEquals(10, other.model.view!!.tracks.size)
        assertEquals(setOf("Sexta à noite", "Aquecimento"), other.model.view!!.playlists.map { it.name }.toSet())
        assertEquals(0, os.deckbridge.manager.transfer.UsbMerger(other.model.backups, ctx.cacheDir).merge(s, other.root).added)
    }
}
