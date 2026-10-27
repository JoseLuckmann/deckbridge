package os.deckbridge.manager

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.PlaylistItem
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.smart.Keep
import os.deckbridge.manager.smart.QuickFilter
import os.deckbridge.manager.smart.Smart

class KeepTest {
    private val today = LocalDate.of(2026, 10, 27)

    private fun t(id: Int, rating: Int = 0, added: String = "2026-10-27", bpm: Double = 124.0, key: String = "8A") =
        TrackItem(id, "T$id", "A", "", "House", key, "", "", bpm, 0, rating, 0, 0, 300, 0, 0, "/Contents/$id.wav", "$id.wav", "", dateAdded = added)

    private fun view(tracks: List<TrackItem>, lists: Map<Int, List<Int>>) = LibraryView(
        tracks, lists.keys.map { PlaylistItem(it, 0, "P$it", false, it) }, lists, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
    )

    @Test fun `novas sao as sem decisao e vao primeiro na triagem`() {
        // 1: nova. 2: tem nota. 3: está numa playlist. 4: nova. 5: sem nota mas em playlist.
        val v = view(listOf(t(1), t(2, rating = 3), t(3), t(4), t(5)), mapOf(10 to listOf(3, 5)))
        assertEquals(listOf(1, 4), Smart.apply(QuickFilter.NEW, v, { true }, v.tracks).map { it.id })
        // Primeiro a caixa de entrada; depois as outras sem nota; por último as marcadas.
        assertEquals(listOf(1, 4, 3, 5, 2), Smart.triageQueue(v))
    }

    @Test fun `esquecidas sao boas antigas fora dos sets`() {
        val v = view(
            listOf(
                t(1, rating = 5, added = "2026-08-01"), // boa, antiga, fora dos sets: esquecida
                t(2, rating = 5, added = "2026-10-20"), // boa mas nova
                t(3, rating = 4, added = "2026-01-01"), // boa e antiga, mas está no set
                t(4, rating = 3, added = "2026-01-01"), // antiga, mas não é das boas
                t(5, rating = 4, added = ""), // sem data: conta como antiga
            ),
            mapOf(20 to listOf(3), 21 to listOf(1)),
        )
        val sets = Keep.inSets(v, setOf(20))
        assertEquals(setOf(3), sets)
        assertEquals(listOf(1, 5), Smart.apply(QuickFilter.FORGOTTEN, v, { true }, v.tracks, sets, today).map { it.id })
        assertEquals(2, Smart.counts(v, { true }, sets, today)[QuickFilter.FORGOTTEN])
    }

    @Test fun `onde esta lista sets primeiro e conta os sets`() {
        val v = view(listOf(t(1)), mapOf(30 to listOf(1), 31 to listOf(1), 32 to listOf(1), 33 to emptyList()))
        val places = Keep.places(v, 1, setOf(31, 32))
        assertEquals(listOf(31 to true, 32 to true, 30 to false), places.map { it.playlistId to it.set })
        assertEquals(2, Keep.setCount(v, 1, setOf(31, 32, 33)))
    }

    @Test fun `o motivo da sugestao diz tom, andamento, nota e uso`() {
        val a = t(1, bpm = 124.0, key = "8A")
        assertEquals("mesmo tom · mesmo BPM · ★5 · nunca em set", Keep.why(a, t(2, rating = 5, bpm = 124.0, key = "8A"), 0))
        assertEquals("tom vizinho · +1,6% BPM · em 2 sets", Keep.why(a, t(3, bpm = 126.0, key = "9A"), 2))
        assertEquals("tom longe · -3,2% BPM · em 1 set", Keep.why(a, t(4, bpm = 120.0, key = "2B"), 1))
        assertEquals("★4 · nunca em set", Keep.why(null, t(5, rating = 4), 0))
    }

    @Test fun `a nota da troca e guardada com o plano`() {
        val f = File(Files.createTempDirectory("keep").toFile(), "1.properties")
        val tr = Transition(1, 2, 200_000, 0, note = "quando o vocal entrar, tiro o grave")
        SetPlanner.save(f, listOf(tr, Transition(2, 3, 100_000, 0)))
        val back = SetPlanner.load(f)
        assertEquals("quando o vocal entrar, tiro o grave", back[0].note)
        assertEquals("", back[1].note)
    }

    @Test fun `o DJ escolhe o rumo da sugestao`() {
        val last = t(1, bpm = 124.0, key = "8A")
        val v = view(
            listOf(last, t(2, bpm = 126.0, key = "8A"), t(3, bpm = 121.0, key = "8A"), t(4, bpm = 124.0, key = "9A", rating = 4), t(5, bpm = 140.0, key = "8A"), t(6, bpm = 124.0, key = "3B", rating = 5)),
            mapOf(40 to listOf(4)),
        )
        val inSets = Keep.inSets(v, setOf(40))
        fun ids(s: os.deckbridge.manager.smart.Steer) = os.deckbridge.manager.smart.Suggest.next(v, last, setOf(1), s, inSets).map { it.id }
        assertEquals(listOf(2), ids(os.deckbridge.manager.smart.Steer.UP))
        assertEquals(listOf(3), ids(os.deckbridge.manager.smart.Steer.DOWN))
        // Descobrir não traz a que já está num set (4); as de nota 3+ primeiro.
        val d = ids(os.deckbridge.manager.smart.Steer.DISCOVER)
        assertEquals(6, d.first())
        assertEquals(false, 4 in d)
    }
}
