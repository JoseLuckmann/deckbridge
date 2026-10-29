package os.deckbridge.manager

import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.manager.mix.Ctl
import os.deckbridge.manager.mix.Lane
import os.deckbridge.manager.mix.MixFx
import os.deckbridge.manager.mix.MixRenderer
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.MixTake
import os.deckbridge.manager.mix.Pt
import os.deckbridge.manager.mix.SegmentReader
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.Stereo
import os.deckbridge.manager.mix.Transition

/** Os efeitos do botão FX no MIX: cada um muda o som; eco e reverb deixam rastro; o roll repete meia batida. */
class MixFxTest {
    /** Um som que muda (nota que sobe) na que sai; a que entra em silêncio. */
    private val tone = SegmentReader { start, len ->
        val n = len * 44100 / 1000
        val l = FloatArray(n) { i -> val t = (start * 44.1 + i) / 44100.0; (0.4 * sin(2 * PI * (220 + 60 * t) * t)).toFloat() }
        DecodedPcm(l, l.copyOf(), 44100)
    }
    private val silence = SegmentReader { _, len -> val n = len * 44100 / 1000; DecodedPcm(FloatArray(n), FloatArray(n), 44100) }

    private fun rms(s: Stereo, fromMs: Int, toMs: Int): Double {
        val a = fromMs * s.rate / 1000
        val b = toMs * s.rate / 1000
        return sqrt((a until b).sumOf { (s.left[it] * s.left[it]).toDouble() } / (b - a))
    }

    @Test fun `cada efeito muda o som e so eco e reverb deixam rastro`() {
        // 120 BPM: compasso de 2 s; a prévia começa 2 s antes do ponto de saída. FX ligado de 1 s a 2 s; no ponto de saída
        // o FX desliga e o fader do CH 1 fecha.
        val tr = Transition(1, 2, 30_000, 0, bars = 4, style = MixStyle.BLEND)
        val p = assertNotNull(MixRenderer.transition(tone, 120.0, silence, 120.0, tr, leadMs = 2000, tailMs = 2000))
        val take = MixTake(mapOf(Ctl.A_ECHO to Lane(listOf(Pt(-0.5, 1f), Pt(-0.01, 1f), Pt(0.0, 0f))), Ctl.A_VOL to Lane(listOf(Pt(-1.0, 1f), Pt(-0.01, 1f), Pt(0.0, 0f)))))
        val dry = p.mixdown(tr, MixTake(mapOf(Ctl.A_VOL to Lane(listOf(Pt(-1.0, 1f), Pt(-0.01, 1f), Pt(0.0, 0f))))))
        for (fx in MixFx.entries) {
            val wet = p.mixdown(tr.copy(fx = fx), take)
            // Com o FX ligado o som é outro.
            val diff = sqrt((44100 * 3 / 2 until 44100 * 19 / 10).sumOf { ((wet.left[it] - dry.left[it]) * (wet.left[it] - dry.left[it])).toDouble() } / (44100 * 0.4))
            assertTrue(diff > 0.02, "$fx muda o som: $diff")
            // Canal fechado e FX desligado: eco e reverb seguem soando um pouco; flanger e roll somem com o canal.
            val tail = rms(wet, 2100, 2400)
            if (fx.tail) assertTrue(tail > 0.005, "$fx deixa rastro: $tail") else assertTrue(tail < 0.002, "$fx some com o canal: $tail")
        }
        // ROLL: depois de gravar meia batida (250 ms), repete: o som em t e em t + 250 ms é o mesmo.
        val roll = p.mixdown(tr.copy(fx = MixFx.ROLL), take)
        for (ms in listOf(1300, 1420, 1610)) assertEquals(roll.left[ms * 441 / 10], roll.left[(ms + 250) * 441 / 10], 0.02f, "roll em $ms ms")
    }

    @Test fun `o LEVEL do efeito muda quanto dele entra`() {
        val tr0 = Transition(1, 2, 30_000, 0, bars = 4, style = MixStyle.BLEND)
        val p = assertNotNull(MixRenderer.transition(tone, 120.0, silence, 120.0, tr0, leadMs = 2000, tailMs = 2000))
        val take = MixTake(mapOf(Ctl.A_ECHO to Lane(listOf(Pt(-0.5, 1f), Pt(-0.01, 1f), Pt(0.0, 0f))), Ctl.A_VOL to Lane(listOf(Pt(-1.0, 1f), Pt(-0.01, 1f), Pt(0.0, 0f)))))
        val dry = p.mixdown(tr0, MixTake(mapOf(Ctl.A_VOL to Lane(listOf(Pt(-1.0, 1f), Pt(-0.01, 1f), Pt(0.0, 0f))))))
        fun diff(fx: MixFx, level: Float): Double {
            val wet = p.mixdown(tr0.copy(fx = fx, fxLevel = level), take)
            return sqrt((44100 * 3 / 2 until 44100 * 19 / 10).sumOf { ((wet.left[it] - dry.left[it]) * (wet.left[it] - dry.left[it])).toDouble() } / (44100 * 0.4))
        }
        for (fx in listOf(MixFx.REVERB, MixFx.FLANGER, MixFx.ECHO)) {
            val low = diff(fx, 0.1f)
            val high = diff(fx, 1f)
            assertTrue(high > low * 1.5, "$fx: com o LEVEL em cima entra mais efeito que embaixo ($low x $high)")
        }
        // Sem LEVEL, o flanger e o reverb não mudam o som.
        assertTrue(diff(MixFx.FLANGER, 0f) < 0.005 && diff(MixFx.REVERB, 0f) < 0.005)
    }

    @Test fun `o efeito escolhido fica na troca guardada`() {
        val tr = Transition(1, 2, 60_000, 960, 16, fx = MixFx.FLANGER, fxLevel = 0.35f)
        val file = Files.createTempDirectory("set").resolve("x.properties").toFile()
        SetPlanner.save(file, listOf(tr))
        assertEquals(MixFx.FLANGER, SetPlanner.load(file).single().fx)
        assertEquals(0.35f, SetPlanner.load(file).single().fxLevel)
        // E o passo a passo diz o efeito.
        val steps = os.deckbridge.manager.mix.MixGuide.steps(tr.copy(style = MixStyle.ECHO, fx = MixFx.REVERB))
        assertTrue(steps.any { "REVERB" in it.text }, steps.toString())
    }
}
