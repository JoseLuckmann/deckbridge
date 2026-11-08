package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

class WaveDetailTest {
    private val rate = 22050

    /** Cliques curtos a 120 BPM, com um pouco de ruído de fundo. */
    private fun clicks(seconds: Int, bpm: Double = 120.0): Pair<FloatArray, List<Double>> {
        val samples = FloatArray(rate * seconds)
        val random = java.util.Random(3)
        for (i in samples.indices) samples[i] = ((random.nextDouble() * 2 - 1) * 0.01).toFloat()
        val times = ArrayList<Double>()
        var t = 0.5
        while (t < seconds - 0.5) {
            val at = (t * rate).toInt()
            for (k in 0 until 40) samples[at + k] += (if (k % 2 == 0) 0.9f else -0.9f) * (1f - k / 40f)
            times += t
            t += 60.0 / bpm
        }
        return samples to times
    }

    @Test
    fun eachClickLandsInItsOwnColumnAtOneHundredFiftyPerSecond() {
        val (samples, times) = clicks(30)
        val wave = wavesFromPcm(samples, rate).rgbDetail
        assertEquals(150.0, wave.perSecond, 0.0)
        assertEquals(30 * 150, wave.columns)
        var worst = 0.0
        for (t in times) {
            // A coluna mais alta perto do clique deve ser a do clique (no máximo uma coluna, 6,7 ms, de diferença).
            val from = floor((t - 0.05) * 150).toInt()
            val to = floor((t + 0.05) * 150).toInt()
            val peak = (from..to).maxBy { wave.height[it].toInt() and 0xFF }
            worst = maxOf(worst, abs(peak - floor(t * 150)) / 150.0)
            // E o meio do caminho entre dois cliques é bem mais baixo: as batidas aparecem separadas.
            val between = floor((t + 0.25) * 150).toInt()
            if (between < wave.columns) {
                assertTrue("contraste", (wave.height[peak].toInt() and 0xFF) > 3 * (wave.height[between].toInt() and 0xFF))
            }
        }
        assertTrue("pior desvio ${worst * 1000} ms", worst <= 1.0 / 150.0 + 1e-9)
    }

    @Test
    fun longTracksKeepOneHundredFiftyColumnsPerSecond() {
        // Mais de 8 min 53 s: o teto antigo de 80 000 colunas fazia a onda escorregar da grade no fim da faixa.
        val seconds = 600
        val samples = FloatArray(8000 * seconds)
        val wave = wavesFromPcm(samples, 8000).rgbDetail
        assertEquals(seconds * 150, wave.columns)
    }

    @Test
    fun aSpikeSurvivesEveryZoomAndSitsInTheRightPixel() {
        val count = 150 * 60
        val spike = 4321
        val heights = IntArray(count) { 10 }
        heights[spike] = 250
        for (window in doubleArrayOf(1.0, 4.0, 15.0, 60.0)) {
            val start = spike / 150.0 - window * 0.3
            val pixels = 900
            val strip = waveStrip(start, window, pixels, 150.0, count, { heights[it] }, { it })
            val best = strip.heights.indices.maxBy { strip.heights[it] }
            val expected = ((spike + 0.5) / 150.0 - start) / window * pixels
            assertTrue("janela $window: pixel $best, esperado $expected", abs(best - expected) <= maxOf(1.5, pixels / (window * 150.0)))
            assertTrue("janela $window: altura ${strip.heights[best]}", strip.heights[best] > 0.9f)
        }
    }
}
