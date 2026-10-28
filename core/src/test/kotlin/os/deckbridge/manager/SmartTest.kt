package os.deckbridge.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.smart.Query
import os.deckbridge.manager.smart.QuickFilter
import os.deckbridge.manager.smart.Smart

class SmartTest {
    private fun t(id: Int, title: String = "T$id", artist: String = "A", bpm: Double = 120.0, key: String = "Am", rating: Int = 0, genre: String = "House", color: Int = 0) =
        TrackItem(id, title, artist, "", genre, key, "", "", bpm, 0, rating, color, 0, 200, 0, 0, "/Contents/$id.wav", "$id.wav", "")

    private fun view(tracks: List<TrackItem>, lists: Map<Int, List<Int>> = emptyMap()) = os.deckbridge.manager.pdb.LibraryView(
        tracks, lists.keys.map { os.deckbridge.manager.pdb.PlaylistItem(it, 0, "P$it", false, it) }, lists, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
    )

    @Test fun `busca entende filtros escritos e termos livres`() {
        val q = Smart.parseQuery("night bpm:120-128 tom:8A nota:4 cor:verde genero:house")
        assertEquals(listOf("night"), q.terms)
        assertEquals(120.0..128.0, q.bpm)
        assertEquals("8a", q.key)
        assertEquals(4, q.minRating)
        assertEquals(5, q.color)
        assertEquals("house", q.genre)
        assertTrue(Smart.parseQuery("").isEmpty)
        // Filtro que não entendemos vira termo livre, não some.
        assertEquals(listOf("bpm:xx", "foo:bar"), Smart.parseQuery("bpm:xx foo:bar").terms)
        assertEquals(124.0..999.0, Smart.parseQuery("bpm:>124").bpm)
    }

    @Test fun `filtro de tom casa nomes e camelot`() {
        val am = t(1, key = "Am")
        assertTrue(Smart.matches(Smart.parseQuery("tom:8A"), am))
        assertTrue(Smart.matches(Smart.parseQuery("tom:am"), am))
        assertTrue(!Smart.matches(Smart.parseQuery("tom:9A"), am))
        assertEquals(8 to 'A', Smart.camelot("Am"))
        assertEquals(8 to 'B', Smart.camelot("C"))
        assertEquals(2 to 'B', Smart.camelot("F#"))
        assertEquals(3 to 'A', Smart.camelot("Bbm"))
        assertNull(Smart.camelot("—"))
        assertEquals(1, Smart.harmonicDistance(8 to 'A', 9 to 'A'))
        assertEquals(1, Smart.harmonicDistance(8 to 'A', 8 to 'B'))
        assertEquals(1, Smart.harmonicDistance(12 to 'A', 1 to 'A'))
    }

    @Test fun `filtros rapidos e contagens`() {
        val v = view(listOf(t(1, rating = 5), t(2), t(3, genre = ""), t(4, artist = "")), mapOf(1 to listOf(1)))
        val has = { x: TrackItem -> x.id != 2 }
        val c = Smart.counts(v, has)
        assertEquals(4, c[QuickFilter.ALL])
        assertEquals(1, c[QuickFilter.UNANALYZED])
        assertEquals(3, c[QuickFilter.UNRATED])
        assertEquals(3, c[QuickFilter.NO_PLAYLIST])
        assertEquals(1, c[QuickFilter.NO_GENRE])
        assertEquals(1, c[QuickFilter.NO_ARTIST])
        assertEquals(1, c[QuickFilter.TOP])
        assertEquals(listOf(2), Smart.apply(QuickFilter.UNANALYZED, v, has, v.tracks).map { it.id })
    }

    @Test fun `ordenacao de playlist inclusive a harmonica`() {
        val tracks = listOf(t(1, bpm = 128.0, key = "Am"), t(2, bpm = 120.0, key = "C"), t(3, bpm = 124.0, key = "Em"), t(4, bpm = 126.0, key = "F#m"))
        val v = view(tracks)
        val ids = listOf(1, 2, 3, 4)
        assertEquals(listOf(2, 3, 4, 1), Smart.sorted(v, ids, Smart.SortMode.BPM_UP))
        assertEquals(listOf(1, 4, 3, 2), Smart.sorted(v, ids, Smart.SortMode.BPM_DOWN))
        val h = Smart.sorted(v, ids, Smart.SortMode.HARMONIC)
        assertEquals(ids.toSet(), h.toSet())
        assertEquals(2, h.first())
        // C (8B) → Am (8A) é vizinho: a segunda deve ser a Am mesmo com BPM maior que a Em.
        assertEquals(1, h[1])
        // Ids que não existem no banco continuam no fim, em vez de sumir.
        assertEquals(listOf(2, 3, 4, 1, 99), Smart.sorted(v, listOf(1, 2, 3, 4, 99), Smart.SortMode.BPM_UP))
    }

    @Test fun `jornada e pre-checagem`() {
        val empty = view(emptyList())
        val steps0 = Smart.journey(empty, { true }, false)
        assertTrue(steps0.none { it.done })
        val v = view(listOf(t(1, rating = 3), t(2, rating = 4)), mapOf(1 to listOf(1, 2), 2 to emptyList()))
        val steps = Smart.journey(v, { true }, true)
        assertTrue(steps.all { it.done }, steps.toString())
        val issues = Smart.preflight(v, { it.id == 1 }, { it.id != 2 })
        assertEquals(3, issues.size)
        assertTrue(issues.first { it.blocking }.text.contains("sem o arquivo"))
        assertTrue(Smart.preflight(empty, { true }, { true }).any { it.blocking })
    }

    @Test fun `fila de triagem so tem as sem nota`() {
        val v = view(listOf(t(1, rating = 5), t(2), t(3, rating = 1), t(4)))
        assertEquals(listOf(2, 4), Smart.triageQueue(v))
        // Toda marcada: nada a marcar (a triagem não abre). Rever é outra fila, por escolha.
        val all = view(listOf(t(1, rating = 5), t(3, rating = 1)))
        assertEquals(emptyList(), Smart.triageQueue(all))
        assertEquals(listOf(1, 3), Smart.reviewQueue(all))
    }

    @Test fun `duplicata por titulo, artista e duracao`() {
        val v = view(listOf(t(1, title = "Night Signal", artist = "Aurora Drive").copy(durationSeconds = 300)))
        assertEquals(1, Smart.findDuplicate(v, "  night   SIGNAL ", "aurora drive", 301)?.id)
        assertNull(Smart.findDuplicate(v, "Night Signal", "Aurora Drive", 310), "duração muito diferente: outra versão")
        assertNull(Smart.findDuplicate(v, "Night Signal", "Outro Artista", 300))
        assertNull(Smart.findDuplicate(v, "", "", 300))
    }
}
