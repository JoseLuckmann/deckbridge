package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShowControlsTest {
    private val map = MidiMap.loadResource()
    private val tempo = TempoAssembler()

    private fun action(status: Int, note: Int, value: Int) = interpretPerformance(map.lookup(status, note), value, tempo)

    @Test
    fun shiftPlayHoldsReverseAndReleases() {
        val press = action(144, 71, 127)
        assertNotNull(press)
        assertEquals(PerformanceAction.Kind.REVERSE_HOLD, press!!.kind)
        assertEquals(0, press.deck)
        assertEquals(1, press.hotIndex)
        val release = action(144, 71, 0)
        assertEquals(PerformanceAction.Kind.REVERSE_HOLD, release!!.kind)
        assertEquals(0, release.hotIndex)
        assertEquals(1, action(145, 71, 127)!!.deck)
    }

    @Test
    fun shiftLoopInAndOutToggleTheJogAdjust() {
        val inAdjust = action(144, 76, 127)!!
        assertEquals(PerformanceAction.Kind.LOOP_ADJUST, inAdjust.kind)
        assertEquals(1, inAdjust.hotIndex)
        val outAdjust = action(145, 78, 127)!!
        assertEquals(PerformanceAction.Kind.LOOP_ADJUST, outAdjust.kind)
        assertEquals(2, outAdjust.hotIndex)
        assertEquals(1, outAdjust.deck)
        // Soltar o botão não alterna de novo.
        assertNull(action(144, 76, 0))
    }

    @Test
    fun loopPointsMoveWithoutCrossingOrLeavingTheTrack() {
        val moved = adjustLoopPoint(10f, 14f, 1, 0.5, 200.0)!!
        assertEquals(10.5f, moved.first, 1e-6f)
        assertEquals(14f, moved.second, 0f)
        val crowded = adjustLoopPoint(10f, 14f, 1, 9.0, 200.0)!!
        assertEquals(13.99f, crowded.first, 1e-4f)
        val outPast = adjustLoopPoint(10f, 14f, 2, -9.0, 200.0)!!
        assertEquals(10.01f, outPast.second, 1e-4f)
        val atEnd = adjustLoopPoint(10f, 14f, 2, 500.0, 20.0)!!
        assertEquals(20f, atEnd.second, 1e-4f)
        assertEquals(0f, adjustLoopPoint(1f, 3f, 1, -50.0, 200.0)!!.first, 0f)
        assertNull(adjustLoopPoint(-1f, -1f, 1, 0.1, 200.0))
        assertNull(adjustLoopPoint(10f, 14f, 0, 0.1, 200.0))
    }

    @Test
    fun glitchWarningAppearsOnNewFailuresAndFadesAfterFifteenSeconds() {
        val watch = GlitchWatch()
        // O que já tinha acontecido antes de ligar o aviso não conta.
        assertNull(watch.update(3, 0L))
        assertNull(watch.update(3, 5_000L))
        assertEquals("ÁUDIO: FALHAS (4)", watch.update(4, 6_000L))
        assertEquals("ÁUDIO: FALHAS (4)", watch.update(4, 20_000L))
        assertNull(watch.update(4, 21_001L))
        assertEquals("ÁUDIO: FALHAS (6)", watch.update(6, 22_000L))
    }

    @Test
    fun jogBendIsOnTopOfTheFaderTempoAndStaysInRange() {
        assertEquals(1.0f, jogBentPitch(1.0f, 0.0), 0f)
        assertEquals(1.0096f, jogBentPitch(1.0f, 4.8), 1e-6f)
        assertEquals(0.9904f, jogBentPitch(1.0f, -4.8), 1e-6f)
        assertEquals(2.0f, jogBentPitch(1.99f, 100.0), 0f)
        assertEquals(0.5f, jogBentPitch(0.51f, -100.0), 0f)
    }

    @Test
    fun loopWrapKeepsTheBeatPhaseWhenTheLoopIsWholeBeats() {
        val beat = 60.0 / 130.0
        // Loop de 2 batidas iniciado numa batida; depois de 10 batidas o playhead está de novo numa batida.
        var position = 40.0
        val start = position
        val end = position + 2 * beat
        repeat(500) { position = advancePlayhead(position, 10 * beat / 500, start, end) }
        val beatsFromStart = (position - start) / beat
        assertEquals(0.0, beatsFromStart - Math.round(beatsFromStart), 1e-6)
    }
}
