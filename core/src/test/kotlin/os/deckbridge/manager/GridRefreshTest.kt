package os.deckbridge.manager

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import os.deckbridge.manager.organize.GridRefresh
import os.deckbridge.manager.organize.Rule
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.volume.Backups

class GridRefreshTest {
    @Test fun refazerGradesSoTrocaOQueMudouEMantemOsCompassos() {
        val root = TestVolumes.fromScratch(TestVolumes.tempDir("dbm-gr-bk"))
        val m = ManagerModel(Backups(TestVolumes.tempDir("dbm-gr")))
        assertTrue(m.open(root))
        val t = m.view!!.tracks.first()
        val original = m.trackFiles(t).beats
        val cues = m.trackFiles(t).cues

        // Recém-importada com o mesmo analisador: nada a refazer.
        val (fresh, bpm) = assertNotNull(GridRefresh.analyze(root, t))
        assertNull(GridRefresh.change(t, original, fresh, bpm).edit, "grade igual não vira edição")

        // O DJ (ou uma análise antiga) deixou a grade 15 ms depois e com o compasso começando na segunda batida.
        assertTrue(m.submit(Edit.SetGrid(t.id, t.bpm, original[1].timeMs + 15), "grade à mão"))
        assertNotNull(m.apply(null))
        val moved = m.trackFiles(t).beats
        assertEquals(1, moved.first().inBar)

        val c = GridRefresh.change(m.view!!.track(t.id)!!, moved, fresh, bpm)
        val e = assertNotNull(c.edit)
        assertTrue(c.keptBars)
        assertEquals(-15.0, c.shiftMs, 1.5)
        val s = assertNotNull(GridRefresh.suggestion(c))
        assertEquals(Rule.GRID, s.rule)
        assertTrue(s.detail.contains("-15 ms") && s.detail.contains("compassos mantidos"), s.detail)

        assertTrue(m.submit(e, "Refazer grade"))
        assertNotNull(m.apply(null))
        val after = m.trackFiles(t).beats
        // Posições de volta ao clique; o tempo 1 continua onde o DJ pôs (na antiga segunda batida).
        val at = after.minByOrNull { abs(it.timeMs - original[1].timeMs) }!!
        assertTrue(abs(at.timeMs - original[1].timeMs) <= 1, "batida em ${at.timeMs}, esperado ${original[1].timeMs}")
        assertEquals(1, at.inBar)
        assertEquals(cues, m.trackFiles(t).cues, "cues intactos")
        // E agora está igual: refazer de novo não muda nada.
        assertNull(GridRefresh.change(m.view!!.track(t.id)!!, after, GridRefresh.change(m.view!!.track(t.id)!!, after, fresh, bpm).edit?.beats ?: after, bpm).edit)
    }

    @Test fun gradeInvalidaERecusada() {
        val root = TestVolumes.fromScratch(TestVolumes.tempDir("dbm-gr2-bk"))
        val m = ManagerModel(Backups(TestVolumes.tempDir("dbm-gr2")))
        assertTrue(m.open(root))
        val t = m.view!!.tracks.first()
        val b = os.deckbridge.manager.anlz.Beat(1, 12400, 500)
        assertTrue(!m.submit(Edit.ReplaceGrid(t.id, listOf(b, b.copy(timeMs = 400)), 124.0), "fora de ordem"))
        assertTrue(!m.submit(Edit.ReplaceGrid(t.id, emptyList(), 124.0), "vazia"))
    }
}
