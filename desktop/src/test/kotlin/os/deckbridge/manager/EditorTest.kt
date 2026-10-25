package os.deckbridge.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.EditException
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.PdbType
import os.deckbridge.manager.pdb.TrackPatch
import os.deckbridge.manager.volume.Verifier

class EditorTest {
    private val raw = TestVolumes.rawOf(TestVolumes.synthetic())

    private fun editor() = PdbEditor(raw)

    private fun finish(e: PdbEditor): os.deckbridge.manager.pdb.LibraryView {
        val changed = e.changedTables()
        val out = raw.write(changed)
        val problems = Verifier.check(raw, out, changed)
        assertTrue(problems.isEmpty(), problems.toString())
        val fresh = os.deckbridge.manager.pdb.RawPdb.parse(out)
        return os.deckbridge.manager.pdb.LibraryView.of { fresh.table(it)?.rows ?: emptyList() }
    }

    @Test
    fun editingOneTrackChangesOnlyThatTrackAndRelatedNames() {
        val e = editor()
        val before = e.view()
        val target = before.tracks[3]
        e.apply(Edit.SetTracks(listOf(target.id), TrackPatch(title = "Título Novo ção", artist = "Artista Inédito", genre = "Deep House", rating = 4, comment = "ok")))
        val view = finish(e)
        val t = view.track(target.id)!!
        assertEquals("Título Novo ção", t.title)
        assertEquals("Artista Inédito", t.artist)
        assertEquals("Deep House", t.genre)
        assertEquals(4, t.rating)
        assertEquals("ok", t.comment)
        assertEquals(target.album, t.album)
        assertEquals(target.filePath, t.filePath)
        for (other in before.tracks.filter { it.id != target.id }) assertEquals(other, view.track(other.id), "faixa ${other.id} não devia mudar")
        assertEquals(before.playlists, view.playlists)
        assertEquals(before.entries, view.entries)
    }

    @Test
    fun untouchedTableRowsStayByteForByte() {
        val e = editor()
        e.apply(Edit.SetTracks(listOf(raw.table(PdbType.TRACKS)!!.rows.let { os.deckbridge.manager.pdb.TrackRow.parse(it[0])!!.id }), TrackPatch(comment = "x")))
        val changed = e.changedTables()
        assertEquals(setOf(PdbType.TRACKS), changed.keys)
        val original = raw.table(PdbType.TRACKS)!!.rows
        val edited = changed.getValue(PdbType.TRACKS)
        assertEquals(original.size, edited.size)
        for (i in 1 until original.size) assertTrue(original[i].contentEquals(edited[i]), "linha $i foi reescrita sem precisar")
    }

    @Test
    fun anArtistThatLosesItsLastTrackIsRemoved() {
        val e = editor()
        val view = e.view()
        // Um artista que também é artista de álbum continua no banco por causa do álbum; aqui só interessa o que não é.
        val albumArtists = raw.table(PdbType.ALBUMS)!!.rows.map { os.deckbridge.manager.pdb.NameRows.albumArtistId(it) }.mapNotNull { view.artists[it] }.toSet()
        val victim = view.tracks.first { t -> t.artist.isNotEmpty() && t.artist !in albumArtists && view.tracks.count { it.artist == t.artist } == 1 }
        e.apply(Edit.SetTracks(listOf(victim.id), TrackPatch(artist = "Outro")))
        val after = finish(e)
        assertFalse(after.artists.values.contains(victim.artist))
        assertTrue(after.artists.values.contains("Outro"))
    }

    @Test
    fun namesCreatedAndThenAbandonedDoNotLinger() {
        val e = editor()
        val id = e.view().tracks[0].id
        e.apply(Edit.SetTracks(listOf(id), TrackPatch(artist = "Provisório")))
        e.apply(Edit.SetTracks(listOf(id), TrackPatch(artist = "Definitivo")))
        val after = finish(e)
        assertFalse(after.artists.values.contains("Provisório"))
        assertTrue(after.artists.values.contains("Definitivo"))
    }

    @Test
    fun clearingAFieldRemovesTheReference() {
        val e = editor()
        val t = e.view().tracks.first { it.genre.isNotEmpty() }
        e.apply(Edit.SetTracks(listOf(t.id), TrackPatch(genre = "")))
        assertEquals("", finish(e).track(t.id)!!.genre)
    }

    @Test
    fun badValuesAreRefusedBeforeAnythingChanges() {
        val e = editor()
        val id = e.view().tracks[0].id
        assertFailsWith<EditException> { e.apply(Edit.SetTracks(listOf(id), TrackPatch(rating = 9))) }
        assertFailsWith<EditException> { e.apply(Edit.SetTracks(listOf(99999), TrackPatch(comment = "x"))) }
        assertFailsWith<EditException> { e.apply(Edit.SetTracks(listOf(id), TrackPatch(colorId = 77))) }
        assertTrue(e.changedTables().isEmpty())
    }

    @Test
    fun playlistsCreateRenameFillReorderMoveAndDelete() {
        val e = editor()
        val v0 = e.view()
        val nextId = v0.playlists.maxOf { it.id } + 1
        e.apply(Edit.CreatePlaylist(nextId, 0, "Meu Set", folder = false))
        val ids = v0.tracks.map { it.id }.take(4)
        e.apply(Edit.AddToPlaylist(nextId, ids))
        e.apply(Edit.AddToPlaylist(nextId, listOf(ids[0])))
        assertEquals(ids, e.view().entries[nextId])
        e.apply(Edit.ReorderPlaylist(nextId, ids.reversed()))
        e.apply(Edit.RemoveFromPlaylist(nextId, listOf(ids[0])))
        e.apply(Edit.RenamePlaylist(nextId, "Meu Set 2"))
        val folderId = nextId + 1
        e.apply(Edit.CreatePlaylist(folderId, 0, "Pasta nova", folder = true))
        e.apply(Edit.MovePlaylist(nextId, folderId, 0))
        val view = finish(e)
        assertEquals(ids.reversed().filter { it != ids[0] }, view.entries[nextId])
        val moved = view.playlists.first { it.id == nextId }
        assertEquals("Meu Set 2", moved.name)
        assertEquals(folderId, moved.parentId)
        assertEquals(v0.playlists.size + 2, view.playlists.size)

        val e2 = editor()
        val withEntries = v0.entries.entries.first { it.value.isNotEmpty() }.key
        e2.apply(Edit.DeletePlaylist(withEntries))
        val after = finish(e2)
        assertTrue(after.playlists.none { it.id == withEntries })
        assertFalse(after.entries.containsKey(withEntries))
    }

    @Test
    fun foldersRefuseTracksAndCycles() {
        val e = editor()
        val folder = e.view().playlists.first { it.folder }
        assertFailsWith<EditException> { e.apply(Edit.AddToPlaylist(folder.id, listOf(e.view().tracks[0].id))) }
        val child = e.view().playlists.first { it.parentId == folder.id && !it.folder }
        assertFailsWith<EditException> { e.apply(Edit.CreatePlaylist(900, child.id, "x", false)) }
        assertFailsWith<EditException> { e.apply(Edit.MovePlaylist(folder.id, folder.id, 0)) }
        assertFailsWith<EditException> { e.apply(Edit.CreatePlaylist(901, 0, "  ", false)) }
        assertFailsWith<EditException> { e.apply(Edit.CreatePlaylist(902, folder.id, e.view().children(folder.id)[0].name, false)) }
    }

    @Test
    fun randomEditSequencesAlwaysPassTheVerifier() {
        val rnd = java.util.Random(20261025)
        repeat(60) { round ->
            val e = editor()
            var nextPlaylist = e.view().playlists.maxOf { it.id } + 1
            repeat(25) {
                val v = e.view()
                val edit: Edit = when (rnd.nextInt(8)) {
                    0 -> Edit.SetTracks(listOf(v.tracks[rnd.nextInt(v.tracks.size)].id), TrackPatch(title = "T${rnd.nextInt(50)} é", artist = listOf("A", "B", "Zé", "")[rnd.nextInt(4)]))
                    1 -> Edit.SetTracks(v.tracks.shuffled(rnd).take(3).map { it.id }, TrackPatch(album = "Álbum ${rnd.nextInt(4)}", rating = rnd.nextInt(6)))
                    2 -> Edit.CreatePlaylist(nextPlaylist++, v.playlists.filter { it.folder }.map { it.id }.plus(0).let { it[rnd.nextInt(it.size)] }, "P${rnd.nextInt(100000)}", rnd.nextInt(4) == 0)
                    3 -> v.playlists.filter { !it.folder }.let { l -> if (l.isEmpty()) Edit.RenamePlaylist(v.playlists[0].id, "R${rnd.nextInt(1000)}") else Edit.AddToPlaylist(l[rnd.nextInt(l.size)].id, v.tracks.shuffled(rnd).take(4).map { it.id }) }
                    4 -> v.playlists.filter { !it.folder }.let { l -> val p = l[rnd.nextInt(l.size)]; Edit.RemoveFromPlaylist(p.id, (v.entries[p.id] ?: emptyList()).take(2)) }
                    5 -> v.playlists.filter { !it.folder }.let { l -> val p = l[rnd.nextInt(l.size)]; Edit.ReorderPlaylist(p.id, (v.entries[p.id] ?: emptyList()).shuffled(rnd)) }
                    6 -> v.playlists.let { l -> Edit.MovePlaylist(l[rnd.nextInt(l.size)].id, v.playlists.filter { it.folder }.map { it.id }.plus(0).let { it[rnd.nextInt(it.size)] }, rnd.nextInt(4)) }
                    else -> v.playlists.let { l -> Edit.RenamePlaylist(l[rnd.nextInt(l.size)].id, "N${rnd.nextInt(100000)}") }
                }
                try {
                    e.apply(edit)
                } catch (_: EditException) {
                    // Recusas legítimas (nome repetido, ciclo) não derrubam a sequência.
                }
            }
            if (e.changedTables().isNotEmpty()) finish(e)
        }
    }
}
