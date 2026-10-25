package os.deckbridge.manager

import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.manager.anlz.Beat
import os.deckbridge.manager.anlz.Cue
import os.deckbridge.manager.mix.MixRenderer
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.SegmentReader
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.Stereo
import os.deckbridge.manager.mix.TrackGrid
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem

class MixTest {
    private fun t(id: Int, bpm: Double = 120.0, key: String = "Am", rating: Int = 0, dur: Int = 200) =
        TrackItem(id, "T$id", "A", "", "House", key, "", "", bpm, 0, rating, 0, 0, dur, 0, 0, "/Contents/$id.wav", "$id.wav", "")

    private fun view(tracks: List<TrackItem>) =
        LibraryView(tracks, emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

    private fun grid(bpm: Double, durMs: Int, firstMs: Int = 0, cues: List<Cue> = emptyList()): TrackGrid {
        val beat = 60000.0 / bpm
        val beats = generateSequence(0) { it + 1 }.map { n -> Beat(n % 4 + 1, (bpm * 100).toInt(), (firstMs + n * beat).toInt()) }
            .takeWhile { it.timeMs < durMs }.toList()
        return TrackGrid(beats, cues, durMs)
    }

    /** Uma batida (clique de 5 ms) a cada tempo, desde o início da faixa, na taxa [rate]. */
    private fun clicks(bpm: Double, rate: Int) = SegmentReader { start, len ->
        val n = (len.toLong() * rate / 1000).toInt()
        val l = FloatArray(n)
        val beatFrames = rate * 60.0 / bpm
        val first = start.toLong() * rate / 1000
        val click = rate * 5 / 1000
        for (i in 0 until n) {
            val pos = (first + i) % beatFrames
            if (pos < click) l[i] = 0.6f
        }
        DecodedPcm(l, l.copyOf(), rate)
    }

    private fun constant(v: Float) = SegmentReader { _, len ->
        val n = len * 44100 / 1000
        DecodedPcm(FloatArray(n) { v }, FloatArray(n) { v }, 44100)
    }

    private fun onsetsMs(s: Stereo, fromMs: Int): List<Double> {
        val out = mutableListOf<Double>()
        var quiet = 1_000_000
        for (i in fromMs * s.rate / 1000 until s.frames) {
            if (abs(s.left[i]) > 0.3f) {
                if (quiet > s.rate / 50) out += i * 1000.0 / s.rate
                quiet = 0
            } else quiet++
        }
        return out
    }

    private fun at(s: Stereo, ms: Int) = s.left[ms * s.rate / 1000]

    @Test fun `a que entra cai na batida da que sai depois de ajustar o andamento`() {
        // A a 120 (batida de 500 ms) e B a 125 em 48 kHz (480 ms); B entra numa batida dela (960 ms).
        val tr = Transition(1, 2, outMs = 60_000, inMs = 960, bars = 4, style = MixStyle.CUT)
        val s = assertNotNull(MixRenderer.transition(clicks(120.0, 44100), 120.0, clicks(125.0, 48000), 125.0, tr, leadMs = 4000, tailMs = 2000))
        assertEquals(14_000.0, s.seconds * 1000, 1.0) // 4 s antes + 4 compassos de 2 s + 2 s depois
        val before = onsetsMs(s, 0).filter { it < 3990 }
        assertEquals(8, before.size)
        before.forEachIndexed { k, ms -> assertEquals(k * 500.0, ms, 1.0) }
        val after = onsetsMs(s, 3990)
        assertTrue(after.size >= 19, "batidas depois da troca: ${after.size}")
        after.forEachIndexed { k, ms -> assertEquals(4000 + k * 500.0, ms, 2.0, "batida $k") }
    }

    @Test fun `mistura com potencia constante`() {
        val tr = Transition(1, 2, 30_000, 0, bars = 4, style = MixStyle.BLEND)
        val s = assertNotNull(MixRenderer.transition(constant(0.4f), 120.0, constant(0.4f), 120.0, tr, leadMs = 2000, tailMs = 2000))
        assertEquals(0.4f, at(s, 1000), 0.01f)
        assertEquals(0.4f * 1.414f, at(s, 2000 + 4000), 0.02f) // meio dos compassos juntos
        assertEquals(0.4f, at(s, 2000 + 8000 + 1000), 0.01f)
    }

    @Test fun `troca de grave passa o grave no meio`() {
        val tr = Transition(1, 2, 30_000, 0, bars = 4, style = MixStyle.BASS_SWAP)
        val s = assertNotNull(MixRenderer.transition(constant(0.3f), 120.0, constant(0.6f), 120.0, tr, leadMs = 2000, tailMs = 2000))
        assertEquals(0.3f, at(s, 1000), 0.01f)
        assertEquals(0.3f, at(s, 2000 + 3000), 0.02f) // antes da troca: só o grave da que sai
        assertEquals(0.6f, at(s, 2000 + 5000), 0.02f) // depois: só o da que entra
        assertEquals(0.6f, at(s, 2000 + 9000), 0.01f)
    }

    @Test fun `eco segura a que sai depois de ela parar`() {
        val tr = Transition(1, 2, 30_000, 0, bars = 4, style = MixStyle.ECHO)
        val s = assertNotNull(MixRenderer.transition(clicks(120.0, 44100), 120.0, constant(0f), 120.0, tr, leadMs = 2000, tailMs = 2000))
        // A seca para 1 compasso (2 s) depois da saída; no compasso seguinte só sobra o eco, mais baixo.
        val echo = onsetsMs(s, 2000 + 2000 + 50).filter { it < 2000 + 4000 }
        assertTrue(echo.isNotEmpty(), "eco audível")
        val peakDry = (0 until 2000 * 44).maxOf { abs(s.left[it]) }
        val peakEcho = ((2000 + 2100) * 44 until (2000 + 4000) * 44).maxOf { abs(s.left[it]) }
        assertTrue(peakEcho < peakDry, "eco $peakEcho < seco $peakDry")
    }

    @Test fun `sequencia e wav`() {
        val a = Stereo(FloatArray(441) { 0.5f }, FloatArray(441) { -0.5f }, 44100)
        val seq = MixRenderer.sequence(listOf(a, a), gapMs = 100)
        assertEquals(441 * 2 + 4410, seq.frames)
        assertEquals(0f, seq.left[441 + 10])
        val wav = MixRenderer.wav(seq)
        assertEquals(44 + seq.frames * 4, wav.size)
        val back = assertNotNull(os.deckbridge.logic.decodeWav(wav))
        assertEquals(44100, back.sampleRate)
        assertEquals(0.5f, back.left[0], 0.001f)
        assertEquals(-0.5f, back.right[0], 0.001f)
    }

    @Test fun `pontos automaticos caem em inicio de compasso`() {
        val g = grid(120.0, 200_000, firstMs = 100)
        assertEquals(100, SetPlanner.defaultIn(g))
        assertEquals(8100, SetPlanner.snap(g, 8500))
        val out = SetPlanner.defaultOut(t(1), g, 16)
        // 200 s - 16 compassos (32 s) - 2 de folga (4 s) = 164 s, no compasso mais perto.
        assertEquals(164_100, out)
        val withOutro = grid(120.0, 200_000, 100, listOf(Cue(-1, 150_100, comment = "Outro")))
        assertEquals(150_100, SetPlanner.defaultOut(t(1), withOutro, 16))
        // Sem espaço depois do Outro para 32 compassos: volta para o automático.
        assertEquals(SetPlanner.snap(withOutro, 200_000 - 64_000 - 4000), SetPlanner.defaultOut(t(1), withOutro, 32))
        assertEquals(0, SetPlanner.defaultIn(null))
        // "Outro" no começo da faixa é ignorado.
        val early = grid(120.0, 200_000, 100, listOf(Cue(-1, 10_100, comment = "Outro")))
        assertEquals(164_100, SetPlanner.defaultOut(t(1), early, 16))
    }

    @Test fun `plano, encaixe e sugestoes`() {
        val tracks = listOf(t(1, 120.0, "Am"), t(2, 124.0, "Em"), t(3, 140.0, "Am"), t(4, 121.0, "C#m", rating = 5), t(5, 122.0, "Am", rating = 3))
        val v = view(tracks)
        val f = SetPlanner.fit(tracks[0], tracks[1])
        assertEquals(1, f.keyDistance)
        assertTrue(f.good)
        assertTrue(!SetPlanner.fit(tracks[0], tracks[2]).bpmOk)
        assertTrue(!SetPlanner.fit(tracks[0], tracks[3]).keyOk)
        val next = SetPlanner.suggestNext(v, tracks[0], exclude = setOf(1, 2))
        assertEquals(listOf(5, 3, 4), next.map { it.id })

        val grids = { tr: TrackItem -> grid(tr.bpm, 200_000) }
        val keep = Transition(2, 3, 1000, 0, 8, MixStyle.CUT)
        val plan = SetPlanner.plan(v, listOf(1, 2, 3), grids, keep = listOf(keep))
        assertEquals(2, plan.size)
        assertEquals(keep, plan[1])
        assertEquals(1 to 2, plan[0].fromId to plan[0].toId)

        val file = Files.createTempDirectory("set").resolve("sets/9.properties").toFile()
        SetPlanner.save(file, plan)
        assertEquals(plan, SetPlanner.load(file))
    }
}

class MixMoreTest {
    private fun t(id: Int, dur: Int = 200) =
        TrackItem(id, "T$id", "A", "", "House", "Am", "", "", 120.0, 0, 0, 0, 0, dur, 0, 0, "/Contents/$id.wav", "$id.wav", "")

    @Test fun `gravar em sequencia sem segurar tudo`() {
        val a = Stereo(FloatArray(441) { 0.25f }, FloatArray(441) { 0.25f }, 44100)
        val file = Files.createTempFile("mix", ".wav").toFile()
        val n = MixRenderer.writeSequence(file, listOf({ a }, { null }, { a }), gapMs = 10)
        assertEquals(2, n)
        val back = assertNotNull(os.deckbridge.logic.decodeWav(file.readBytes()))
        assertEquals(441 * 2 + 441, back.frameCount)
        assertEquals(0.25f, back.left[0], 0.001f)
        assertEquals(0f, back.left[500], 0.001f)
    }

    @Test fun `empurrar ponto e duracao do set`() {
        val beats = (0 until 400).map { Beat(it % 4 + 1, 12000, it * 500) }
        val g = TrackGrid(beats, emptyList(), 200_000)
        assertEquals(12_000, SetPlanner.nudge(t(1), g, 10_000, 1))
        assertEquals(8_000, SetPlanner.nudge(t(1), g, 10_000, -1))
        assertEquals(0, SetPlanner.nudge(t(1), g, 1_000, -4))
        val v = LibraryView(listOf(t(1), t(2), t(3)), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val tr = listOf(Transition(1, 2, 150_000, 10_000), Transition(2, 3, 160_000, 0))
        assertEquals(150_000 + 150_000 + 200_000, SetPlanner.lengthMs(v, listOf(1, 2, 3), tr))
    }
}
