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
    private fun model() = ManagerModel(Backups(Files.createTempDirectory("dbm-mm").toFile()))

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
}
