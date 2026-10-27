package os.deckbridge.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.smart.Request
import os.deckbridge.manager.smart.ShowCheck

class ShowTest {
    private fun t(id: Int, bpm: Double = 124.0, key: String = "8A") =
        TrackItem(id, "T$id", "A", "", "House", key, "", "", bpm, 0, 0, 0, 0, 300, 0, 0, "/Contents/$id.wav", "$id.wav", "")

    private fun view(tracks: List<TrackItem>) = LibraryView(tracks, emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

    @Test fun `a lista do show aponta o que falta e diz ok no resto`() {
        val v = view(listOf(t(1), t(2), t(3), t(4)))
        val items = ShowCheck.check(
            v, listOf(1, 2, 3, 2, 4),
            hasAnalysis = { it.id != 3 },
            audioExists = { it.id != 4 },
            cueNames = { if (it.id == 1) setOf("Intro", "drop", "Outro") else setOf("Intro") },
            issues = 1, unrehearsed = 0,
        ).associateBy { it.kind }
        assertEquals(listOf(4), items.getValue(ShowCheck.Kind.AUDIO).trackIds)
        assertEquals(listOf(3), items.getValue(ShowCheck.Kind.ANALYSIS).trackIds)
        // Sem cues: as que têm áudio e não têm os três nomes (a 1 tem, sem diferenciar maiúscula).
        assertEquals(listOf(2, 3), items.getValue(ShowCheck.Kind.CUES).trackIds)
        assertEquals(listOf(2), items.getValue(ShowCheck.Kind.REPEATED).trackIds)
        assertEquals("1 troca(s) com aviso de tom ou BPM", items.getValue(ShowCheck.Kind.TRANSITIONS).text)
        assertTrue(items.getValue(ShowCheck.Kind.REHEARSED).ok)
        assertEquals("Todas as trocas ensaiadas", items.getValue(ShowCheck.Kind.REHEARSED).text)
    }

    @Test fun `o pedido chega pelo caminho mais curto que combina`() {
        // De 8A (124) até 11A (124): de 8A não dá direto; 9A e 10A fazem a ponte de uma casa por vez.
        val from = t(1, key = "8A")
        val target = t(2, key = "11A")
        val v = view(listOf(from, target, t(3, key = "9A"), t(4, key = "10A"), t(5, key = "3B"), t(6, key = "10A", bpm = 150.0)))
        val route = Request.route(v, from, target, exclude = emptySet())!!
        assertEquals(listOf(3, 4), route.map { it.id })
        // Já combina direto: nada no meio.
        assertEquals(emptyList(), Request.route(v, from, t(7, key = "9A"), exclude = emptySet()))
        // Sem as faixas da ponte, não há caminho.
        assertNull(Request.route(v, from, target, exclude = setOf(3, 4)))
    }
}
