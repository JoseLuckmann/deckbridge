package os.deckbridge.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A DDJ-400 simulada lê os bytes do mapa pinado do Mixxx 2.5.6 sem passar pelo app. */
class DdjSurfaceTest {
    @Test
    fun fadersKnobsAndTempoFollowTheMessages() {
        val ddj = DdjSurface()
        ddj.feed(1.0, 0xB1, 0x13, 127, "CHANNELFADER")
        ddj.feed(1.0, 0xB0, 0x0F, 0, "EQ LOW")
        ddj.feed(1.0, 0xB6, 0x1F, 0, "CROSSFADER")
        ddj.feed(1.0, 0xB6, 0x18, 100, "FILTER CH2")
        ddj.feed(1.0, 0xB4, 0x02, 90, "BEAT FX LEVEL/DEPTH")
        ddj.feed(1.0, 0x94, 0x11, 127, "BEAT FX CH SELECT CH2")
        // Tempo em 14 bits: MSB e LSB.
        ddj.feed(1.0, 0xB0, 0x00, 0x50, "TEMPO")
        ddj.feed(1.0, 0xB0, 0x20, 0x05, "TEMPO")
        assertEquals(127, ddj.decks[1].volume)
        assertEquals(0, ddj.decks[0].low)
        assertEquals(0, ddj.cross)
        assertEquals(100, ddj.filter[1])
        assertEquals(90, ddj.fxLevel)
        assertEquals(1, ddj.fxChannel)
        assertEquals((0x50 shl 7) or 0x05, ddj.decks[0].tempo)
        assertEquals(8192, ddj.decks[1].tempo)
    }

    @Test
    fun jogTouchTurnAndPadModes() {
        val ddj = DdjSurface()
        ddj.feed(2.0, 0x91, 0x36, 127, "JOG DIAL PLATTER (DECK2)")
        assertTrue(ddj.decks[1].jogTouched)
        // 720 passos por volta, como o scratchEnable do script oficial: 4 passos de +3 = 6°.
        repeat(4) { ddj.feed(2.0, 0xB1, 0x22, 67, "JOG DIAL PLATTER Vinyl mode On (DECK2)") }
        assertEquals(6.0, ddj.decks[1].jogAngle, 1e-9)
        ddj.feed(2.1, 0x91, 0x36, 0, "JOG DIAL PLATTER (DECK2)")
        assertFalse(ddj.decks[1].jogTouched)
        ddj.feed(3.0, 0x97, 0x62, 127, "PAD 3 (DECK1) BEAT LOOP MODE")
        assertEquals(DdjSurface.PadMode.BEAT_LOOP, ddj.decks[0].padMode)
        assertTrue(ddj.lit(ddj.decks[0].padAt[2], 3.1, 0.25))
        ddj.feed(4.0, 0x97, 0x31, 127, "PAD 2 (LEFT) SAMPLE MODE")
        assertEquals(DdjSurface.PadMode.SAMPLER, ddj.decks[0].padMode)
        ddj.feed(5.0, 0x91, 0x58, 127, "BEAT SYNC (DECK2)")
        assertTrue(ddj.lit(ddj.decks[1].pressedAt["SYNC"], 5.05))
        assertFalse(ddj.lit(ddj.decks[1].pressedAt["SYNC"], 6.0))
        assertEquals("91 58 7F  BEAT SYNC (DECK2)", ddj.log.last().second)
    }
}
