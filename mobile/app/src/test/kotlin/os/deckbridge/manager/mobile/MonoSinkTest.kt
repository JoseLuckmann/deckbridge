package os.deckbridge.manager.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonoSinkTest {
    private fun shorts(vararg values: Int): ByteBuffer {
        val b = ByteBuffer.allocate(values.size * 2).order(ByteOrder.nativeOrder())
        values.forEach { b.putShort(it.toShort()) }
        b.flip()
        return b
    }

    @Test fun stereo44kBecomesMono22kAveragingChannelsAndPairs() {
        val sink = MonoSink(44100, 2, 100, 1_000_000)
        assertEquals(2, sink.factor)
        assertEquals(22050, sink.outRate)
        // Quadros (L,R): (0.5, 0.5), (0.5, -0.5) → médias 0.5 e 0 → um quadro de saída 0.25.
        sink.append(shorts(16384, 16384, 16384, -16384), 2)
        val out = sink.result()!!
        assertEquals(1, out.size)
        assertEquals(0.25f, out[0], 1e-4f)
    }

    @Test fun monoAt22kPassesThroughGrowsAndStopsAtTheCap() {
        val sink = MonoSink(22050, 1, 10, 3000)
        assertNull(sink.result())
        val big = ByteBuffer.allocate(5000 * 2).order(ByteOrder.nativeOrder())
        repeat(5000) { big.putShort(8192) }
        big.flip()
        sink.append(big, 5000)
        val out = sink.result()!!
        assertEquals(3000, out.size, "para no limite")
        assertTrue(sink.full)
        assertEquals(0.25f, out[2999], 1e-4f)
    }

    @Test fun sixChannelsUseTheFirstTwo() {
        val sink = MonoSink(22050, 6, 10, 100)
        sink.append(shorts(16384, 0, 9999, 9999, 9999, 9999), 1)
        assertEquals(0.25f, sink.result()!![0], 1e-4f)
    }
}
