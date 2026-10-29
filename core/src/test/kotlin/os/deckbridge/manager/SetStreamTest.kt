package os.deckbridge.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.manager.mix.MixRenderer
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.SegmentReader
import os.deckbridge.manager.mix.SetStream
import os.deckbridge.manager.mix.Transition

/** O set inteiro tocado: faixa, virada, faixa — na ordem, no tempo certo, e pulando para uma virada. */
class SetStreamTest {
    /** Uma faixa "constante" (o valor diz de quem é o som), de [seconds] segundos, a 22,05 kHz. */
    private fun level(v: Float, seconds: Int) = SegmentReader { start, len ->
        val from = start.coerceAtMost(seconds * 1000)
        val n = (minOf(len, seconds * 1000 - from).coerceAtLeast(0).toLong() * 22050 / 1000).toInt()
        DecodedPcm(FloatArray(n) { v }, FloatArray(n) { v }, 22050)
    }

    private fun stream(): SetStream {
        val a = SetStream.Track("A", level(0.3f, 40), 120.0, 40_000)
        val b = SetStream.Track("B", level(0.6f, 40), 120.0, 40_000)
        // Corte seco de 4 compassos (8 s a 120) saindo de A aos 20 s e entrando em B do começo.
        val tr = Transition(1, 2, 20_000, 0, bars = 4, style = MixStyle.CUT, masterTempo = false, phaseLock = false)
        return SetStream(listOf(a, b), listOf(tr)) { i -> MixRenderer.transition(level(0.3f, 40), 120.0, level(0.6f, 40), 120.0, tr, leadMs = 0, tailMs = 0) }
    }

    private fun readAll(s: SetStream): FloatArray {
        val out = ArrayList<Float>()
        val l = FloatArray(4096)
        val r = FloatArray(4096)
        while (true) {
            val n = s.read(l, r, 4096)
            if (n <= 0) break
            for (i in 0 until n) out += l[i]
        }
        return out.toFloatArray()
    }

    private fun at(x: FloatArray, ms: Int) = x[(ms.toLong() * MixRenderer.RATE / 1000).toInt()]

    @Test fun `toca a faixa, a virada e a seguinte de onde a virada deixou`() {
        val s = stream()
        // A 0–20 s, virada 20–28 s, B de 8 s (onde a virada deixou) até 40 s: 32 s de B.
        assertEquals(listOf(0 to "A", 20_000 to "A → B", 28_000 to "B"), s.chapters())
        assertEquals(60_000, s.lengthMs)
        val x = readAll(s)
        assertEquals(60.0, x.size / MixRenderer.RATE.toDouble(), 0.2)
        assertEquals(0.3f, at(x, 10_000), 0.01f)
        assertEquals(0.6f, at(x, 25_000), 0.02f) // corte seco: já é a B
        assertEquals(0.6f, at(x, 50_000), 0.01f)
        s.close()
    }

    @Test fun `pular para perto de uma virada`() {
        val s = stream()
        s.seek(18_000)
        val l = FloatArray(MixRenderer.RATE)
        val r = FloatArray(MixRenderer.RATE)
        assertEquals(l.size, s.read(l, r, l.size))
        assertEquals(0.3f, l[l.size / 2], 0.01f, "ainda a A antes da virada")
        assertTrue(s.renderedMs in 18_900..19_100, "${s.renderedMs}")
        s.seek(45_000)
        s.read(l, r, l.size)
        assertEquals(0.6f, l[100], 0.01f)
        s.close()
    }
}
