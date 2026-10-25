package os.deckbridge.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.TrackPatch
import os.deckbridge.manager.transfer.FileStorage
import os.deckbridge.manager.transfer.LocalLibrary
import os.deckbridge.manager.transfer.UsbMerger
import os.deckbridge.manager.volume.Backups
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.SafeWriter
import os.deckbridge.manager.volume.Volumes
import os.deckbridge.manager.volume.sha256

class MergeTest {
    private val backups = Backups(TestVolumes.tempDir("dbm-merge-bk"))

    @Test fun `juntar traz as faixas novas com analise, marcacoes e playlists e so le o pendrive`() {
        // "Pendrive": biblioteca nascida do gerador com 2 faixas e a playlist "Sexta"; marca nota e cor numa delas.
        val usb = TestVolumes.fromScratch(TestVolumes.tempDir("dbm-merge-usb-bk"))
        run {
            val vol = Volumes.open(usb)
            val e = PdbEditor(vol.raw, usb)
            val t = e.view().tracks.first()
            e.apply(Edit.SetTracks(listOf(t.id), TrackPatch(rating = 4, colorId = 5, comment = "abre")))
            SafeWriter.apply(vol, e, listOf("marcar"), backups)
        }
        val before = usb.walkTopDown().filter { it.isFile && !it.path.contains("deckbridge") }.associate { it.path to sha256(it) }
        val local = TestVolumes.tempDir("dbm-merge-local")
        LocalLibrary.ensure(local)
        val report = UsbMerger(backups, TestVolumes.tempDir("dbm-merge-tmp")).merge(FileStorage(usb), local)
        assertEquals(2, report.added)
        assertEquals(0, report.alreadyThere)
        assertEquals(before, usb.walkTopDown().filter { it.isFile && !it.path.contains("deckbridge") }.associate { it.path to sha256(it) }, "o pendrive não muda")
        val m = ManagerModel(backups)
        assertTrue(m.open(local))
        val v = m.view!!
        assertEquals(2, v.tracks.size)
        val marked = v.tracks.first { it.rating == 4 }
        assertEquals(5, marked.colorId)
        assertEquals("abre", marked.comment)
        for (t in v.tracks) {
            assertTrue(File(local, t.filePath.trimStart('/')).isFile, t.filePath)
            assertTrue(m.hasAnalysis(t), "análise trazida")
            assertTrue(m.trackFiles(t).beats.isNotEmpty(), "grade trazida")
        }
        val sexta = v.playlists.first { it.name == "Sexta" }
        assertEquals(2, v.entries[sexta.id].orEmpty().size)
        // O caminho do áudio dentro do ANLZ aponta para o arquivo local.
        val t = v.tracks.first()
        val dat = os.deckbridge.manager.anlz.AnlzModel.parse(File(local, t.analyzePath.trimStart('/')).readBytes())
        val ppth = dat.first("PPTH")!!.bytes
        assertTrue(String(ppth, Charsets.UTF_16BE).contains(t.fileName), "PPTH aponta para o áudio local")

        // Juntar de novo não duplica nada.
        val again = UsbMerger(backups, TestVolumes.tempDir("dbm-merge-tmp2")).merge(FileStorage(usb), local)
        assertEquals(0, again.added)
        assertEquals(2, again.alreadyThere)
        assertTrue(m.open(local))
        assertEquals(2, m.view!!.tracks.size)
        assertEquals(sha256(File(local, PDB_RELATIVE)), sha256(File(local, PDB_RELATIVE)))
    }

    @Test fun `juntar com a mesma faixa em caminho ocupado guarda ao lado`() {
        val a = TestVolumes.fromScratch(TestVolumes.tempDir("dbm-merge-a"))
        val b = TestVolumes.fromScratch(TestVolumes.tempDir("dbm-merge-b"))
        // Renomeia as faixas de B para não serem duplicatas, mas com os mesmos caminhos de arquivo.
        run {
            val vol = Volumes.open(b)
            val e = PdbEditor(vol.raw, b)
            e.view().tracks.forEach { e.apply(Edit.SetTracks(listOf(it.id), TrackPatch(title = it.title + " (Remix)"))) }
            SafeWriter.apply(vol, e, listOf("renomear"), backups)
        }
        val r = UsbMerger(backups, TestVolumes.tempDir("dbm-merge-tmp3")).merge(FileStorage(b), a)
        assertEquals(2, r.added)
        val m = ManagerModel(backups)
        m.open(a)
        val paths = m.view!!.tracks.map { it.filePath }
        assertEquals(4, paths.size)
        assertEquals(4, paths.toSet().size, "caminhos distintos: $paths")
        assertTrue(m.view!!.tracks.all { File(a, it.filePath.trimStart('/')).isFile })
    }
}
