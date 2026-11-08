package os.deckbridge.android

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

/** O anel do SCENE FX: a fatia sob o dedo, pelo ângulo e pela distância do centro. */
class RmxRingTest {
    private val w = 400f
    private val h = 400f

    /** O ponto no meio da fatia [index], no meio da faixa do anel. */
    private fun middleOf(index: Int): Pair<Float, Float> {
        val radius = 200f * 0.81f
        val angle = Math.toRadians((wedgeStart(index) + 22.5f).toDouble())
        return (200f + radius * cos(angle)).toFloat() to (200f + radius * sin(angle)).toFloat()
    }

    @Test fun eachWedgeIsFoundAtItsMiddle() {
        for (index in 0 until 8) {
            val (x, y) = middleOf(index)
            assertEquals("fatia $index", index, sceneAt(x, y, w, h))
        }
    }

    @Test fun buildUpOnTopLeftToRightAndBreakDownBelowLeftToRight() {
        // Em cima, da esquerda para a direita: 0, 1, 2, 3 (as de subida).
        val topX = (0..3).map { middleOf(it).first }
        assertEquals(topX.sorted(), topX)
        assertEquals(true, (0..3).all { middleOf(it).second < 200f })
        // Embaixo, da esquerda para a direita: 4, 5, 6, 7 (as de queda), como no RMX-1000 (HPF ECHO … REVERB DOWN).
        val bottomX = (4..7).map { middleOf(it).first }
        assertEquals(bottomX.sorted(), bottomX)
        assertEquals(true, (4..7).all { middleOf(it).second > 200f })
    }

    @Test fun centerAndOutsideAreNotWedges() {
        assertEquals("o knob do centro", -1, sceneAt(200f, 200f, w, h))
        assertEquals("fora do anel, no canto", -1, sceneAt(5f, 5f, w, h))
        assertEquals("dentro do raio do knob", -1, sceneAt(200f, 200f - 200f * 0.5f, w, h))
    }
}
