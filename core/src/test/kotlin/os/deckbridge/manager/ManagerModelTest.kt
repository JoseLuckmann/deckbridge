package os.deckbridge.manager

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch
import os.deckbridge.manager.volume.Backups
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.sha256

class ManagerModelTest {
    private fun model() = ManagerModel(Backups(TestVolumes.tempDir("dbm-mm")))

    @Test
    fun editingQueuesWithoutTouchingTheUsbAndUndoDiscardWork() {
        val dir = TestVolumes.synthetic()
        val before = sha256(java.io.File(dir, PDB_RELATIVE).readBytes())
        val m = model()
        assertTrue(m.open(dir))
        val t = m.view!!.tracks[2]
        assertTrue(m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(title = "Novo")), "x"))
        assertEquals("Novo", m.view!!.track(t.id)!!.title)
        assertTrue(m.dirty)
        assertEquals(setOf(t.id), m.editedTrackIds())
        assertEquals(before, sha256(java.io.File(dir, PDB_RELATIVE).readBytes()), "o pendrive não muda ao editar")
        m.undo()
        assertEquals(t.title, m.view!!.track(t.id)!!.title)
        assertFalse(m.dirty)
        m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = 2)), "y")
        m.discard()
        assertEquals(t.rating, m.view!!.track(t.id)!!.rating)
    }

    @Test
    fun whatIsAskedTogetherIsUndoneTogether() {
        val dir = TestVolumes.synthetic()
        val m = model()
        m.open(dir)
        val (a, b) = m.view!!.tracks
        m.submit(Edit.SetTracks(listOf(a.id), TrackPatch(rating = 1)), "antes")
        // Mover entre linhas, pôr uma ponte: várias mudanças pedidas de uma vez.
        assertEquals(2, m.submitAll(listOf(Edit.SetTracks(listOf(a.id), TrackPatch(rating = 5)) to "um", Edit.SetTracks(listOf(b.id), TrackPatch(rating = 4)) to "dois")))
        m.undo()
        assertEquals(listOf("antes"), m.pending.map { it.text }, "um desfazer tira as duas")
        assertEquals(1, m.view!!.track(a.id)!!.rating)
        assertTrue(m.redo())
        assertEquals(listOf("antes", "um", "dois"), m.pending.map { it.text }, "e um refazer põe as duas, na ordem")
        assertEquals(4, m.view!!.track(b.id)!!.rating)
        m.undo()
        m.undo()
        assertTrue(m.pending.isEmpty(), "o que foi pedido sozinho sai sozinho")
    }

    @Test
    fun aQuietSaveKeepsTheScreenAndWhatWasDoneMeanwhile() {
        val dir = TestVolumes.synthetic()
        val m = model()
        m.open(dir)
        val a = m.view!!.tracks[0]
        val b = m.view!!.tracks[1]
        m.submit(Edit.SetTracks(listOf(a.id), TrackPatch(rating = 5)), "nota")
        m.filter = os.deckbridge.manager.smart.QuickFilter.TOP
        m.query = "x"
        val queued = assertNotNull(m.beginQuiet())
        assertTrue(m.saving)
        m.undo()
        assertEquals(1, m.pending.size, "desfazer espera a gravação")
        // Editando enquanto grava: a mudança nova fica na fila depois.
        m.submit(Edit.SetTracks(listOf(b.id), TrackPatch(rating = 3)), "outra")
        val done = assertNotNull(m.writeQuiet(queued))
        m.finishQuiet(done)
        assertFalse(m.saving)
        assertEquals(listOf("outra"), m.pending.map { it.text }, "só o que foi feito durante a gravação fica na fila")
        assertEquals(5, m.view!!.track(a.id)!!.rating)
        assertEquals(3, m.view!!.track(b.id)!!.rating)
        assertEquals(os.deckbridge.manager.smart.QuickFilter.TOP, m.filter, "a tela não pula")
        assertEquals("x", m.query)
        // Gravado de verdade: reabrir lê a nota.
        val again = model()
        again.open(dir)
        assertEquals(5, again.view!!.track(a.id)!!.rating)
        assertEquals(0, again.view!!.track(b.id)!!.rating.let { if (it == b.rating) 0 else it }, "a de depois ainda não foi gravada")
    }

    @Test
    fun aRefusedEditLeavesTheQueueAndTheViewAsTheyWere() {
        val m = model()
        m.open(TestVolumes.synthetic())
        val t = m.view!!.tracks[0]
        m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(comment = "ok")), "a")
        assertFalse(m.submit(Edit.SetTracks(listOf(t.id, 99999), TrackPatch(title = "meio")), "b"))
        assertEquals(1, m.pending.size)
        assertEquals("ok", m.view!!.track(t.id)!!.comment)
        assertEquals(t.title, m.view!!.track(t.id)!!.title, "nada da edição recusada ficou pela metade")
        assertTrue(m.banner!!.error)
    }

    @Test
    fun applyWritesReopensAndTheBackupRestoresTheOriginal() {
        val dir = TestVolumes.synthetic()
        val original = sha256(java.io.File(dir, PDB_RELATIVE).readBytes())
        val m = model()
        m.open(dir)
        val id = m.nextPlaylistId()
        m.submit(Edit.CreatePlaylist(id, 0, "Set de sábado", false), "criar")
        m.submit(Edit.AddToPlaylist(id, m.view!!.tracks.take(3).map { it.id }), "add")
        val report = m.apply()
        assertNotNull(report)
        assertFalse(m.dirty)
        assertEquals(3, m.view!!.entries[id]!!.size)
        assertTrue(sha256(java.io.File(dir, PDB_RELATIVE).readBytes()) != original)
        val record = m.backupList().single()
        assertTrue(m.restore(record))
        assertEquals(original, sha256(java.io.File(dir, PDB_RELATIVE).readBytes()))
        assertTrue(m.view!!.playlists.none { it.id == id })
    }

    @Test
    fun searchSortSelectionAndPlaylistScopeBehave() {
        val m = model()
        m.open(TestVolumes.synthetic())
        val all = m.rows()
        assertEquals(m.view!!.tracks.size, all.size)
        m.query = all[3].title.take(4).uppercase()
        assertTrue(m.rows().any { it.id == all[3].id })
        m.query = ""
        m.sort = Sort(Column.TITLE, false)
        assertEquals(all.map { it.title.lowercase() }.sortedDescending(), m.rows().map { it.title.lowercase() })
        val rows = m.rows()
        m.click(rows[1].id, ctrl = false, shift = false, visible = rows)
        m.click(rows[4].id, ctrl = false, shift = true, visible = rows)
        assertEquals(rows.subList(1, 5).map { it.id }.toSet(), m.selection)
        m.click(rows[2].id, ctrl = true, shift = false, visible = rows)
        assertFalse(rows[2].id in m.selection)
        val playlist = m.view!!.playlists.first { !it.folder && m.view!!.entries[it.id].orEmpty().isNotEmpty() }
        m.openPlaylist = playlist.id
        assertEquals(m.view!!.entries[playlist.id], m.scopeTrackIds())
        val folder = m.view!!.playlists.first { it.folder }
        m.openPlaylist = folder.id
        assertTrue(m.scopeTrackIds().isNotEmpty())
        assertEquals(m.scopeTrackIds().size, m.scopeTrackIds().distinct().size)
    }

    @Test
    fun dropOnPlaylistAndReorderGoThroughTheQueue() {
        val m = model()
        m.open(TestVolumes.synthetic())
        val v = m.view!!
        val list = v.playlists.first { !it.folder && v.entries[it.id].orEmpty().size in 3 until v.tracks.size }
        val folder = v.playlists.first { it.folder }
        val extra = v.tracks.map { it.id }.first { it !in v.entries[list.id].orEmpty() }
        assertFalse(m.dropOnPlaylist(listOf(extra), folder.id), "pasta não recebe faixas")
        assertTrue(m.banner!!.error)
        assertTrue(m.dropOnPlaylist(listOf(extra), list.id))
        assertFalse(m.dropOnPlaylist(listOf(extra), list.id), "já está lá")
        val entries = m.view!!.entries[list.id]!!
        assertEquals(extra, entries.last())
        assertTrue(m.reorderInPlaylist(list.id, listOf(extra), entries.first()))
        assertEquals(extra, m.view!!.entries[list.id]!!.first())
        assertFalse(m.reorderInPlaylist(list.id, listOf(extra), m.view!!.entries[list.id]!![1]), "mesma posição não enfileira")
        assertEquals(2, m.pending.size, "só o que mudou algo entra na fila")
    }

    @Test
    fun trackFilesShowTheQueuedCueEditsBeforeTheyAreWritten() {
        val dir = TestVolumes.synthetic()
        val m = model()
        m.open(dir)
        val t = m.view!!.tracks.first { m.hasAnalysis(it) }
        val before = m.trackFiles(t)
        assertTrue(before.error == null && before.beats.isNotEmpty())
        m.submit(Edit.SetCues(t.id, listOf(os.deckbridge.manager.anlz.Cue(2, 700, comment = "novo", rgb = 0xff0000))), "cues")
        val after = m.trackFiles(t)
        assertEquals(listOf(2), after.cues.map { it.hot })
        assertEquals(700, after.cues.single().timeMs)
        assertTrue(before.cues.size != 1 || before.cues.single().timeMs != 700)
    }

    @Test
    fun compactShrinksAfterManyApplyCyclesAndKeepsEveryRow() {
        val dir = TestVolumes.synthetic()
        val m = model()
        m.open(dir)
        assertEquals(null, m.compact(), "já compacto: nada a fazer")
        repeat(5) { n ->
            val t = m.view!!.tracks[n]
            m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(comment = "rodada $n")), "r$n")
            assertNotNull(m.apply())
        }
        val before = java.io.File(dir, PDB_RELATIVE).length()
        val tracks = m.view!!.tracks
        m.submit(Edit.SetTracks(listOf(tracks[0].id), TrackPatch(comment = "x")), "x")
        assertEquals(null, m.compact(), "com mudanças na fila não compacta")
        m.discard()
        val report = m.compact()
        assertNotNull(report)
        assertTrue(java.io.File(dir, PDB_RELATIVE).length() < before)
        assertEquals(tracks, m.view!!.tracks)
        assertTrue(m.view!!.entries.isNotEmpty())
    }

    @Test
    fun redoReplaysUndoneChangesAndAnyNewChangeClearsIt() {
        val m = model()
        m.open(TestVolumes.synthetic())
        val t = m.view!!.tracks[0]
        m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = 4)), "a")
        m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = 2)), "b")
        m.undo()
        m.undo()
        assertFalse(m.dirty)
        assertTrue(m.redo())
        assertEquals(4, m.view!!.track(t.id)!!.rating)
        assertTrue(m.redo())
        assertEquals(2, m.view!!.track(t.id)!!.rating)
        assertFalse(m.redo())
        m.undo()
        m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = 5)), "c")
        assertFalse(m.redo(), "mudança nova esvazia o refazer")
    }

    @Test
    fun targetPlaylistAddsAndRemovesWithOneCallAndFiltersNarrowTheList() {
        val m = model()
        m.open(TestVolumes.synthetic())
        val v = m.view!!
        assertEquals(null, m.toggleInTarget(v.tracks[0].id), "sem alvo não faz nada")
        val id = m.nextPlaylistId()
        m.submit(Edit.CreatePlaylist(id, 0, "Alvo", false), "nova")
        m.target = id
        val a = v.tracks[0].id
        assertTrue(m.toggleInTarget(a)!!.startsWith("Adicionada"))
        assertEquals(listOf(a), m.view!!.entries[id])
        assertTrue(m.toggleInTarget(a)!!.startsWith("Tirada"))
        assertEquals(emptyList(), m.view!!.entries[id].orEmpty())
        // Filtro rápido + busca com token: a lista é a interseção.
        m.filter = os.deckbridge.manager.smart.QuickFilter.UNRATED
        val unrated = m.rows()
        assertTrue(unrated.all { it.rating == 0 })
        m.query = "bpm:>0"
        assertTrue(m.rows().all { it.bpm > 0 && it.rating == 0 })
        // Salvar a lista como playlist leva exatamente as faixas mostradas.
        val shown = m.rows().map { it.id }
        val pid = m.saveListAsPlaylist("Lista salva")!!
        assertEquals(shown, m.view!!.entries[pid])
        // Ordenar e apagar vazias.
        m.sortPlaylist(pid, os.deckbridge.manager.smart.Smart.SortMode.BPM_DOWN)
        val bpms = m.view!!.entries[pid]!!.map { m.view!!.track(it)!!.bpm }
        assertEquals(bpms.sortedDescending(), bpms)
        m.submit(Edit.CreatePlaylist(m.nextPlaylistId(), 0, "Vazia", false), "vazia")
        assertEquals(2, m.removeEmptyPlaylists(), "a alvo e a nova estão vazias")
    }

    @Test
    fun anImportedTrackCarriesTheColouredPreviewThePlayerDraws() {
        val root = TestVolumes.fromScratch(TestVolumes.tempDir("dbm-mm-bk"))
        val m = model()
        assertTrue(m.open(root))
        val t = m.view!!.tracks.first()
        val f = m.trackFiles(t)
        assertTrue(f.colors.isNotEmpty(), "sem prévia colorida (PWV4)")
        assertEquals(f.colors.size, f.colorHeights.size)
        assertTrue(f.colorHeights.any { (it.toInt() and 0xFF) > 0 }, "prévia colorida toda zerada")
        assertTrue(f.colors.any { it != 0 }, "cores todas pretas")
    }
}
