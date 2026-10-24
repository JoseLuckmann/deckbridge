package os.deckbridge.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleTest {
    @Test
    fun arrayFollowsTheEngineOrder() {
        // Mesma ordem de MixerFx::setConsole em dsp.h.
        val state = ConsoleState(
            comp = listOf(0.25f, 0.75f),
            isolatorOn = true,
            isolator = listOf(0f, 0.5f, 1f),
            send = listOf(0.3f, 0.6f),
            sendType = 3,
            sendSize = 0.4f,
            sendTime = 0.2f,
            sendTone = 0.7f,
            sendReturn = 0.9f,
            fxBands = listOf(true, false, true),
        )
        val expected = floatArrayOf(1f, 2f, 0.25f, 0.75f, 1f, 0f, 0.5f, 1f, 0.3f, 0.6f, 3f, 0.4f, 0.2f, 0.7f, 0.9f, 1f, 0f, 1f)
        assertArrayEquals(expected, state.toArray(chCurve = 1, crossCurve = 2), 0f)
    }

    @Test
    fun faderCurvesAndIsolatorLabels() {
        for (curve in 0..2) {
            assertEquals(0f, channelFaderGain(0f, curve), 0f)
            assertEquals(1f, channelFaderGain(1f, curve), 0f)
        }
        assertTrue(channelFaderGain(0.5f, 0) < channelFaderGain(0.5f, 2))
        assertTrue(channelFaderGain(0.5f, 2) < channelFaderGain(0.5f, 1))
        assertEquals("CUT", isolatorDbLabel(0f))
        assertEquals("0 dB", isolatorDbLabel(0.5f))
        assertEquals("+9.0 dB", isolatorDbLabel(1f))
    }
}
