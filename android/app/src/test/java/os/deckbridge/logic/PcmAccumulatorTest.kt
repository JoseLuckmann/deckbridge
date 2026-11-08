package os.deckbridge.logic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAccumulatorTest {
    private fun shorts(vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.nativeOrder())
        values.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    @Test
    fun stereoMonoAndMultichannelAreSplitAsTheDecoderDelivers() {
        val stereo = PcmAccumulator(48000, 2, 100, 10_000)
        stereo.append(shorts(16384, -16384, 8192, 0), 2)
        val out = stereo.result()!!
        assertEquals(2, out.frameCount)
        assertEquals(0.5f, out.left[0], 1e-4f)
        assertEquals(-0.5f, out.right[0], 1e-4f)
        val mono = PcmAccumulator(48000, 1, 100, 10_000)
        mono.append(shorts(16384, 8192), 2)
        assertEquals(mono.result()!!.left[1], mono.result()!!.right[1], 0f)
        // Seis canais: só os dois primeiros entram, os outros quatro são consumidos.
        val surround = PcmAccumulator(48000, 6, 100, 10_000)
        surround.append(shorts(100, 200, 1, 2, 3, 4, 300, 400, 5, 6, 7, 8), 2)
        val s = surround.result()!!
        assertEquals(2, s.frameCount)
        assertEquals(300 / 32768f, s.left[1], 1e-6f)
        assertEquals(400 / 32768f, s.right[1], 1e-6f)
    }

    @Test
    fun growsWhenTheEstimateIsShortAndNeverKeepsRoomAsFrames() {
        val acc = PcmAccumulator(1000, 1, 10, 100_000)
        val big = ByteBuffer.allocate(40_000).order(ByteOrder.nativeOrder())
        repeat(20_000) { big.putShort(1000) }
        big.flip()
        assertTrue(acc.append(big, 20_000))
        val out = acc.result()!!
        assertEquals(20_000, out.frames)
        assertTrue(out.left.size >= 20_000)
        assertFalse(out.truncated)
    }

    @Test
    fun stopsAtTheCapAndSaysSo() {
        val acc = PcmAccumulator(1000, 1, 10, 500)
        val buffer = ByteBuffer.allocate(2_000).order(ByteOrder.nativeOrder())
        repeat(1_000) { buffer.putShort(1) }
        buffer.flip()
        assertFalse(acc.append(buffer, 1_000))
        val out = acc.result()!!
        assertEquals(500, out.frames)
        assertTrue(out.truncated)
        assertTrue(acc.full)
    }

    @Test
    fun emptyInputGivesNoTrack() {
        assertNull(PcmAccumulator(48000, 2, 100, 1000).result())
    }
}
