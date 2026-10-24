package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WavSegmentTest {
    @Test
    fun segmentReadsOnlyTheAskedWindow() {
        // Rampa: a amostra n vale n/rate, então o valor diz de onde o trecho veio.
        val rate = 8000
        val samples = FloatArray(rate * 10) { (it.toFloat() / (rate * 10)) * 0.9f }
        val wav = encodeWavMono16(samples, rate)
        val pcm = decodeWavSegment(wav, startSec = 4.0, seconds = 2.0)
        assertNotNull(pcm)
        assertEquals(rate * 2, pcm!!.frameCount)
        assertEquals(samples[rate * 4], pcm.left[0], 1e-4f)
        assertEquals(samples[rate * 6 - 1], pcm.left[pcm.frameCount - 1], 1e-4f)
        assertEquals(pcm.left[100], pcm.right[100], 0f)
        // Perto do fim, o trecho corta na faixa; depois do fim não há nada.
        assertEquals(rate, decodeWavSegment(wav, 9.0, 5.0)!!.frameCount)
        assertNull(decodeWavSegment(wav, 12.0, 5.0))
    }
}
