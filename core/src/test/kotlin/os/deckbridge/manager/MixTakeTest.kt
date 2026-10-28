package os.deckbridge.manager

import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.manager.mix.Ctl
import os.deckbridge.manager.mix.Lane
import os.deckbridge.manager.mix.MixHabits
import os.deckbridge.manager.mix.MixPlayer
import os.deckbridge.manager.mix.MixRenderer
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.MixTake
import os.deckbridge.manager.mix.Pt
import os.deckbridge.manager.mix.SegmentReader
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.Stereo
import os.deckbridge.manager.mix.TakeRecorder
import os.deckbridge.manager.mix.Transition

/** A mixagem do DJ por cima do automático: gravar, guardar, tocar ao vivo e aprender o jeito dele. */
class MixTakeTest {
    private fun constant(v: Float) = SegmentReader { _, len ->
        val n = len * 44100 / 1000
        DecodedPcm(FloatArray(n) { v }, FloatArray(n) { v }, 44100)
    }

    private fun clicks(bpm: Double) = SegmentReader { start, len ->
        val rate = 44100
        val n = (len.toLong() * rate / 1000).toInt()
        val l = FloatArray(n)
        val beat = rate * 60.0 / bpm
        val first = start.toLong() * rate / 1000
        for (i in 0 until n) {
            val pos = Math.floorMod(first + i, beat.toLong())
            if (pos < rate * 5 / 1000) l[i] = 0.6f
        }
        DecodedPcm(l, l.copyOf(), rate)
    }

    private fun at(s: Stereo, ms: Int) = s.left[ms * s.rate / 1000]

    private fun onsetsMs(s: Stereo, fromMs: Int): List<Double> {
        val out = mutableListOf<Double>()
        var quiet = 1_000_000
        for (i in fromMs * s.rate / 1000 until s.frames) {
            if (abs(s.left[i]) > 0.2f) {
                if (quiet > s.rate / 50) out += i * 1000.0 / s.rate
                quiet = 0
            } else quiet++
        }
        return out
    }

    @Test fun `o controle fica onde a mao deixou e volta ao automatico no NaN`() {
        val l = Lane(listOf(Pt(1.0, 0.2f), Pt(1.25, 0.6f), Pt(4.0, 0.9f), Pt(6.0, Float.NaN)))
        assertNull(l.at(0.5), "antes do primeiro toque vale o automático")
        assertEquals(0.4f, l.at(1.125)!!, 0.001f) // gesto contínuo: anda em linha reta
        assertEquals(0.6f, l.at(3.0)!!, 0.001f) // longe do próximo ponto: fica parado (latch)
        assertEquals(0.9f, l.at(5.9)!!, 0.001f)
        assertNull(l.at(6.5), "depois do NaN, automático")
    }

    @Test fun `uma passada nova substitui so o trecho que a mao tocou`() {
        val old = MixTake(mapOf(Ctl.B_LOW to Lane(listOf(Pt(0.0, 0.1f), Pt(10.0, 0.9f)))))
        val merged = old.merge(mapOf(Ctl.B_LOW to listOf(Pt(4.0, 1f), Pt(4.2, 1f))), end = 6.0)
        assertEquals(0.1f, merged.at(Ctl.B_LOW, 2.0)!!, 0.001f) // antes: o que estava
        assertEquals(1f, merged.at(Ctl.B_LOW, 5.0)!!, 0.001f) // no trecho: a passada nova
        assertEquals(0.1f, merged.at(Ctl.B_LOW, 7.0)!!, 0.001f) // depois do fim da passada: volta ao que havia
        assertEquals(0.9f, merged.at(Ctl.B_LOW, 11.0)!!, 0.001f)
        // Soltar o CH 2 a partir do 8: automático dali.
        assertNull(merged.release(1, 8.0).at(Ctl.B_LOW, 9.0))
        assertNotNull(merged.release(0, 8.0).at(Ctl.B_LOW, 9.0), "soltar o CH 1 não mexe no CH 2")
    }

    @Test fun `gravador afina os pontos e recomeca quando a cabeca volta`() {
        val r = TakeRecorder()
        r.set(Ctl.A_VOL, 1.0, 0.8f)
        r.set(Ctl.A_VOL, 1.01, 0.8015f) // quase igual e logo depois: não vira ponto
        r.set(Ctl.A_VOL, 1.1, 0.5f)
        assertEquals(0.5f, r.at(Ctl.A_VOL, 1.2)!!, 0.001f)
        r.set(Ctl.A_VOL, 0.5, 0.3f) // a cabeça voltou: o que vinha depois sai
        val pass = r.close()
        assertEquals(listOf(Pt(0.5, 0.3f)), pass[Ctl.A_VOL])
        assertTrue(r.isEmpty)
    }

    @Test fun `a mixagem e o sync a mao ficam no plano do set`() {
        val take = MixTake(mapOf(Ctl.B_LOW to Lane(listOf(Pt(2.0, 1f))), Ctl.A_FILTER to Lane(listOf(Pt(3.0, 0.8f), Pt(9.0, Float.NaN)))))
        val tr = Transition(1, 2, 60_000, 960, 16, nudgeMs = -12, beatShift = 1, take = take)
        val file = Files.createTempDirectory("set").resolve("x.properties").toFile()
        SetPlanner.save(file, listOf(tr))
        val back = SetPlanner.load(file).single()
        assertEquals(tr, back)
        assertEquals(-12, back.nudgeMs)
        assertNull(back.take!!.at(Ctl.A_FILTER, 10.0))
    }

    @Test fun `a mao do dj muda o som da previa e o ao vivo soa igual ao WAV`() {
        // Troca de grave de 4 compassos (2 s cada) com graves constantes: A 0,3 e B 0,6.
        val tr = Transition(1, 2, 30_000, 0, bars = 4, style = MixStyle.BASS_SWAP)
        val p = assertNotNull(MixRenderer.transition(constant(0.3f), 120.0, constant(0.6f), 120.0, tr, leadMs = 2000, tailMs = 2000))
        assertEquals(0.3f, at(p.audio, 2000 + 1000), 0.02f) // automático: o grave de B fechado antes da troca
        // O DJ abriu o grave da que entra logo no começo e baixou o da que sai.
        val take = MixTake(mapOf(Ctl.B_LOW to Lane(listOf(Pt(0.0, 1f))), Ctl.A_LOW to Lane(listOf(Pt(0.0, 0f)))))
        val mine = p.mixdown(tr, take)
        val bVol = p.levelsAt(2000 + 1000, tr, take).bVol
        assertEquals(0.6f * bVol, at(mine, 2000 + 1000), 0.02f)
        // Ao vivo (em blocos, como a thread de áudio pede) sai a mesma coisa que o WAV.
        val player = MixPlayer(p, tr.copy(take = take)).also { it.take = take }
        val l = FloatArray(mine.frames)
        val r = FloatArray(mine.frames)
        var done = 0
        val chunk = FloatArray(700)
        val chunkR = FloatArray(700)
        while (done < mine.frames) {
            val n = player.read(chunk, chunkR, minOf(700, mine.frames - done))
            if (n == 0) break
            chunk.copyInto(l, done, 0, n); chunkR.copyInto(r, done, 0, n)
            done += n
        }
        assertEquals(mine.frames, done)
        for (i in 0 until mine.frames step 97) assertEquals(mine.left[i], l[i], 1e-5f, "quadro $i")
        assertEquals(0, player.read(chunk, chunkR, 100), "acabou")
        // Repetir um trecho: volta ao começo dele e conta a volta.
        player.seek(2000); player.loop = 2000..3000
        repeat(3) { player.read(chunk, chunkR, 700) }
        assertTrue(player.renderedMs in 2000..3000)
        repeat(80) { player.read(chunk, chunkR, 700) }
        assertTrue(player.wraps >= 1)
    }

    @Test fun `o FILTER passa alta tira o grave da que sai`() {
        val tr = Transition(1, 2, 30_000, 0, bars = 4, style = MixStyle.BASS_SWAP)
        val p = assertNotNull(MixRenderer.transition(constant(0.3f), 120.0, constant(0f), 120.0, tr, leadMs = 2000, tailMs = 2000))
        val take = MixTake(mapOf(Ctl.A_FILTER to Lane(listOf(Pt(-0.5, 1f)))))
        val s = p.mixdown(tr, take)
        assertEquals(0.3f, at(s, 500), 0.02f) // antes do filtro: o grave inteiro
        assertTrue(abs(at(s, 1800)) < 0.02f, "com o passa-alta todo aberto o grave constante some: ${at(s, 1800)}")
    }

    @Test fun `sync a mao atrasa ou adianta a que entra sem ler de novo`() {
        // A e B a 120, cliques em cada tempo; corte seco no ponto: depois dele, só os cliques de B.
        val tr = Transition(1, 2, 60_000, 1000, bars = 4, style = MixStyle.CUT, masterTempo = false, phaseLock = false)
        val p = assertNotNull(MixRenderer.transition(clicks(120.0), 120.0, clicks(120.0), 120.0, tr, leadMs = 4000, tailMs = 2000))
        assertEquals(4000.0, onsetsMs(p.audio, 3990).first(), 2.0)
        assertTrue(p.sameStems(tr.copy(nudgeMs = -20, style = MixStyle.BLEND, beatShift = 1)), "sync e estilo não exigem montar de novo")
        assertFalse(p.sameStems(tr.copy(bars = 8)))
        // 20 ms atrasada: cada clique de B 20 ms depois.
        val late = p.mixdown(tr.copy(nudgeMs = -20))
        assertEquals(4020.0, onsetsMs(late, 3990).first(), 2.0)
        assertEquals(4000 + 20.0, p.fromB(1000, tr.copy(nudgeMs = -20)).toDouble(), 2.0)
        // Uma batida adiantada: a batida seguinte de B cai no ponto (os cliques continuam no tempo).
        val ahead = p.mixdown(tr.copy(beatShift = 1))
        onsetsMs(ahead, 3990).take(4).forEachIndexed { k, ms -> assertEquals(4000 + k * 500.0, ms, 2.0) }
        assertEquals(1500, p.bAt(4000, tr.copy(beatShift = 1)), "o ponto de saída já está na batida seguinte de B")
    }

    @Test fun `o jeito do dj vira o comeco das trocas novas`() {
        // Três mixagens em que o DJ passou o grave no 8 de 32 e começou a tirar a que sai no 16 (metade final: lenta).
        fun mine(id: Int) = Transition(id, id + 1, 0, 0, 32, MixStyle.BASS_SWAP, take = MixTake(mapOf(
            Ctl.B_LOW to Lane(listOf(Pt(0.0, 0f), Pt(8.0, 1f))),
            Ctl.A_VOL to Lane(listOf(Pt(0.0, 1f), Pt(16.0, 0.9f), Pt(32.0, 0f))),
        )))
        val two = MixHabits.learn(listOf(mine(1), mine(3)))
        assertFalse(two.ready, "com duas ainda não")
        val h = MixHabits.learn(listOf(mine(1), mine(3), mine(5), Transition(9, 10, 0, 0, 32)))
        assertTrue(h.ready)
        assertEquals(0.25, h.swapAt)
        assertEquals(Transition.Pace.SLOW, h.pace)
        val auto = Transition(20, 21, 0, 0, 32, MixStyle.BASS_SWAP)
        val applied = h.apply(auto, SetPlanner.Basis.DEFAULT)
        assertEquals(0.25, applied.swapAt)
        assertEquals(Transition.Pace.SLOW, applied.pace)
        // Até o drop o grave continua no drop.
        assertEquals(1.0, h.apply(auto.copy(swapAt = 1.0), SetPlanner.Basis.DROP).swapAt)
        val f = Files.createTempDirectory("h").resolve("habitos.properties").toFile()
        MixHabits.save(f, h)
        assertEquals(h, MixHabits.load(f))
    }
}
