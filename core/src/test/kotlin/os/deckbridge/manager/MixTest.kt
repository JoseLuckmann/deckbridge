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
        val tr = Transition(1, 2, outMs = 60_000, inMs = 960, bars = 4, style = MixStyle.CUT, masterTempo = false)
        val s = assertNotNull(MixRenderer.transition(clicks(120.0, 44100), 120.0, clicks(125.0, 48000), 125.0, tr, leadMs = 4000, tailMs = 2000)).audio
        assertEquals(14_000.0, s.seconds * 1000, 1.0) // 4 s antes + 4 compassos de 2 s + 2 s depois
        val before = onsetsMs(s, 0).filter { it < 3990 }
        assertEquals(8, before.size)
        before.forEachIndexed { k, ms -> assertEquals(k * 500.0, ms, 1.0) }
        val after = onsetsMs(s, 3990)
        assertTrue(after.size >= 19, "batidas depois da troca: ${after.size}")
        after.forEachIndexed { k, ms -> assertEquals(4000 + k * 500.0, ms, 2.0, "batida $k") }
    }

    @Test fun `com master tempo as batidas tambem caem e o tom fica`() {
        val tr = Transition(1, 2, outMs = 60_000, inMs = 960, bars = 4, style = MixStyle.CUT, masterTempo = true)
        val p = assertNotNull(MixRenderer.transition(clicks(120.0, 44100), 120.0, clicks(125.0, 48000), 125.0, tr, leadMs = 4000, tailMs = 2000, waves = true))
        val s = p.audio
        assertEquals(14_000.0, s.seconds * 1000, 2.0)
        val after = onsetsMs(s, 3990)
        assertTrue(after.size >= 19, "batidas depois da troca: ${after.size}")
        // WSOLA escolhe cada grão a até ±5 ms: a batida pode andar um pouco, mas não acumula.
        after.forEachIndexed { k, ms -> assertEquals(4000 + k * 500.0, ms, 8.0, "batida $k") }
        // Ondas dos dois decks na base de tempo da prévia (150 colunas por segundo).
        assertEquals(14.0 * 150, assertNotNull(p.waveA).columns.toDouble(), 3.0)
        // Com o sync fino (aqui só uns ms, do WSOLA), o mapeamento de tempo de B o inclui.
        assertTrue(kotlin.math.abs(p.phaseMs) < 6, "sync fino ${p.phaseMs}")
        assertTrue(kotlin.math.abs(p.fromB(tr.inMs) - p.leadMs) <= 6)
        assertTrue(kotlin.math.abs(p.bAt(p.leadMs + 500) - (tr.inMs + 480)) <= 6) // 500 ms da prévia = 480 ms da faixa de 125 BPM
    }

    @Test fun `sync fino corrige a grade da que entra 20 ms fora do bumbo`() {
        // A grade de B diz que a batida está em 960 ms, mas o bumbo está 20 ms depois (como uma análise imprecisa).
        val late = SegmentReader { start, len -> clicks(125.0, 48000).read(start - 20, len) }
        val base = Transition(1, 2, outMs = 60_000, inMs = 960, bars = 8, style = MixStyle.CUT, masterTempo = false)
        val fixed = assertNotNull(MixRenderer.transition(clicks(120.0, 44100), 120.0, late, 125.0, base, leadMs = 4000, tailMs = 2000))
        assertEquals(19.2, assertNotNull(fixed.measuredMs), 2.0) // 20 ms da faixa B = 19,2 ms no andamento da A
        assertEquals(fixed.measuredMs!!, fixed.phaseMs, 0.05)
        onsetsMs(fixed.audio, 3990).forEachIndexed { k, ms -> assertEquals(4000 + k * 500.0, ms, 2.0, "batida $k com sync fino") }
        val raw = assertNotNull(MixRenderer.transition(clicks(120.0, 44100), 120.0, late, 125.0, base.copy(phaseLock = false), leadMs = 4000, tailMs = 2000))
        assertEquals(0.0, raw.phaseMs)
        assertEquals(19.2, assertNotNull(raw.measuredMs), 2.0)
        assertEquals(4019.2, onsetsMs(raw.audio, 3990).first(), 2.0)
        // A tela leva o tempo da faixa B em conta: a batida de B (980 ms no áudio) cai no ponto de saída.
        assertTrue(kotlin.math.abs(fixed.leadMs - fixed.fromB(980)) <= 2)
    }

    @Test fun `as duas tocam no master do set`() {
        // A de 120 e B de 125 tocadas a 128: batidas a cada 468,75 ms antes e depois da troca.
        val tr = Transition(1, 2, outMs = 60_000, inMs = 960, bars = 4, style = MixStyle.CUT, masterTempo = false, phaseLock = false)
        val p = assertNotNull(MixRenderer.transition(clicks(120.0, 44100), 120.0, clicks(125.0, 48000), 125.0, tr, leadMs = 4000, tailMs = 2000, tempo = 128.0))
        assertEquals(128.0, p.tempo)
        val beat = 60000.0 / 128
        val before = onsetsMs(p.audio, 0).filter { it < p.leadMs - 10 }
        assertTrue(before.size >= 7)
        before.zipWithNext().forEach { (x, y) -> assertEquals(beat, y - x, 1.5) }
        val after = onsetsMs(p.audio, p.leadMs - 10)
        after.forEachIndexed { k, ms -> assertEquals(p.leadMs + k * beat, ms, 2.0, "batida $k") }
        // A posição na faixa que sai anda mais rápido que a prévia (120 → 128).
        assertTrue(kotlin.math.abs(p.aAt(p.leadMs) - tr.outMs) <= 2)
        assertTrue(kotlin.math.abs(p.aAt(p.leadMs + 1000) - (tr.outMs + 1067)) <= 3)
    }

    @Test fun `stretch muda o andamento e mantem a frequencia`() {
        val n = 44100 * 3
        val sine = FloatArray(n) { kotlin.math.sin(2 * Math.PI * 440.0 * it / 44100).toFloat() * 0.5f }
        val out = os.deckbridge.manager.mix.Stretch.tempo(Stereo(sine, sine.copyOf(), 44100), 0.9)
        assertEquals(n / 0.9, out.frames.toDouble(), 2.0)
        // Frequência por cruzamentos de zero no meio (longe das pontas).
        var crossings = 0
        val from = 44100 / 2
        val to = out.frames - 44100 / 2
        for (i in from + 1 until to) if (out.left[i - 1] < 0 && out.left[i] >= 0) crossings++
        val hz = crossings / ((to - from) / 44100.0)
        assertEquals(440.0, hz, 4.0)
        // Sem master tempo, a 0,9 o tom desce: ~396 Hz.
        val plain = MixRenderer.resample(os.deckbridge.logic.DecodedPcm(sine, sine.copyOf(), 44100), 0.9)
        var c2 = 0
        for (i in from + 1 until plain.frames - from) if (plain.left[i - 1] < 0 && plain.left[i] >= 0) c2++
        assertEquals(396.0, c2 / ((plain.frames - 2 * from) / 44100.0), 4.0)
    }

    @Test fun `ritmo e ponto da troca de grave`() {
        val lv = os.deckbridge.manager.mix.Levels()
        val bar = 2000.0
        val overlap = 32 * bar
        fun at(bars: Double, pace: Transition.Pace, swap: Double = 0.5, style: MixStyle = MixStyle.BASS_SWAP) =
            MixRenderer.levels(style, bars * bar, overlap, bar, lv, pace, swap)
        // Grave no fim (no drop): até o último compasso a que sai ainda tem grave; no fim, troca.
        at(31.5, Transition.Pace.NORMAL, swap = 1.0)
        assertEquals(1f, lv.aLow); assertEquals(0f, lv.bLow)
        at(32.0, Transition.Pace.NORMAL, swap = 1.0)
        assertEquals(1f, lv.bLow)
        // Grave em 1/4 cai num início de compasso (o 8º).
        at(7.9, Transition.Pace.NORMAL, swap = 0.25)
        assertEquals(0f, lv.bLow)
        at(8.0, Transition.Pace.NORMAL, swap = 0.25)
        assertEquals(1f, lv.bLow)
        // Lenta: o grave passa aos poucos nos 8 compassos antes da troca.
        at(12.0, Transition.Pace.SLOW)
        assertEquals(0.5f, lv.bLow, 0.01f)
        assertEquals(0.5f, lv.aLow, 0.01f)
        // Rápida: agudos da que entra já cheios num quarto do caminho até a troca; a que sai só some no último oitavo.
        at(4.0, Transition.Pace.FAST)
        assertEquals(1f, lv.bHigh)
        at(26.0, Transition.Pace.FAST)
        assertEquals(1f, lv.aVol)
        at(26.0, Transition.Pace.SLOW)
        assertTrue(lv.aVol < 1f, "lenta: a que sai já está descendo")
        // Mistura rápida cruza no meio; lenta deixa as duas cheias no meio.
        at(8.0, Transition.Pace.FAST, style = MixStyle.BLEND)
        assertEquals(1f, lv.aVol); assertEquals(0f, lv.bVol)
        at(16.0, Transition.Pace.SLOW, style = MixStyle.BLEND)
        assertEquals(1f, lv.aVol, 0.001f); assertEquals(1f, lv.bVol, 0.001f)
        // Guardado no plano.
        val tr = Transition(1, 2, 1000, 0, 32, MixStyle.BASS_SWAP, pace = Transition.Pace.SLOW, swapAt = 1.0)
        val file = Files.createTempDirectory("set").resolve("x.properties").toFile()
        SetPlanner.save(file, listOf(tr))
        assertEquals(listOf(tr), SetPlanner.load(file))
        // Até o drop, o automático troca o grave no drop.
        val a = t(1, 128.0, dur = 360)
        val gb = grid(128.0, 360_000, 0, listOf(Cue(-1, (32 * 1875.0).toInt(), comment = "Drop")))
        assertEquals(1.0, SetPlanner.auto(a, grid(128.0, 360_000), t(2, 128.0, dur = 360), gb).first.swapAt)
        assertEquals(0.5, SetPlanner.auto(a, grid(128.0, 360_000), t(2, 128.0, dur = 360), grid(128.0, 360_000)).first.swapAt)
    }

    @Test fun `niveis do mixer por estilo`() {
        val lv = os.deckbridge.manager.mix.Levels()
        MixRenderer.levels(MixStyle.BASS_SWAP, 1000.0, 8000.0, 2000.0, lv)
        assertEquals(1f, lv.aLow); assertEquals(0f, lv.bLow); assertEquals(0.25f, lv.bHigh, 0.01f)
        MixRenderer.levels(MixStyle.BASS_SWAP, 5000.0, 8000.0, 2000.0, lv)
        assertEquals(0f, lv.aLow); assertEquals(1f, lv.bLow)
        MixRenderer.levels(MixStyle.CUT, -100.0, 8000.0, 2000.0, lv)
        assertEquals(1f, lv.aVol); assertEquals(0f, lv.bVol)
        MixRenderer.levels(MixStyle.ECHO, 3000.0, 8000.0, 2000.0, lv)
        assertEquals(0f, lv.aVol); assertTrue(lv.echo)
        // Tom sem master tempo: +4% ≈ +0,7 semitom → arredonda para 1 → 8A vira 3A.
        assertEquals(1, Math.round(SetPlanner.semitones(1.04)).toInt())
        assertEquals(3 to 'A', SetPlanner.shiftKey(8 to 'A', 1))
        assertEquals(11 to 'B', SetPlanner.shiftKey(6 to 'B', -1))
    }

    @Test fun `mistura com potencia constante`() {
        val tr = Transition(1, 2, 30_000, 0, bars = 4, style = MixStyle.BLEND)
        val s = assertNotNull(MixRenderer.transition(constant(0.4f), 120.0, constant(0.4f), 120.0, tr, leadMs = 2000, tailMs = 2000)).audio
        assertEquals(0.4f, at(s, 1000), 0.01f)
        assertEquals(0.4f * 1.414f, at(s, 2000 + 4000), 0.02f) // meio dos compassos juntos
        assertEquals(0.4f, at(s, 2000 + 8000 + 1000), 0.01f)
    }

    @Test fun `troca de grave passa o grave no meio`() {
        val tr = Transition(1, 2, 30_000, 0, bars = 4, style = MixStyle.BASS_SWAP)
        val s = assertNotNull(MixRenderer.transition(constant(0.3f), 120.0, constant(0.6f), 120.0, tr, leadMs = 2000, tailMs = 2000)).audio
        assertEquals(0.3f, at(s, 1000), 0.01f)
        assertEquals(0.3f, at(s, 2000 + 3000), 0.02f) // antes da troca: só o grave da que sai
        assertEquals(0.6f, at(s, 2000 + 5000), 0.02f) // depois: só o da que entra
        assertEquals(0.6f, at(s, 2000 + 9000), 0.01f)
    }

    @Test fun `eco segura a que sai depois de ela parar`() {
        val tr = Transition(1, 2, 30_000, 0, bars = 4, style = MixStyle.ECHO)
        val s = assertNotNull(MixRenderer.transition(clicks(120.0, 44100), 120.0, constant(0f), 120.0, tr, leadMs = 2000, tailMs = 2000)).audio
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

    @Test fun `transicao automatica pela estrutura - intro sobre outro com o drop no fim`() {
        // 128 BPM: compasso de 1875 ms. Faixas de 6 min.
        val bar = 1875.0
        val a = t(1, 128.0, dur = 360)
        val b = t(2, 128.0, dur = 360)
        fun at(bars: Int) = (bars * bar).toInt()
        // A: outro nos últimos 32 compassos. B: drop depois de 32 compassos de intro.
        val ga = grid(128.0, 360_000, 0, listOf(Cue(-1, at(160), comment = "Outro")))
        val gb = grid(128.0, 360_000, 0, listOf(Cue(-1, at(32), comment = "Drop")))
        val (tr, basis) = SetPlanner.auto(a, ga, b, gb)
        assertEquals(SetPlanner.Basis.DROP, basis)
        assertEquals(32, tr.bars, "dura a intro da que entra")
        assertEquals(at(160), tr.outMs, "sai no outro")
        assertEquals(0, tr.inMs, "entra no começo")
        // O drop de B cai quando a troca termina (A acabou de sair).
        assertEquals(at(32), tr.inMs + at(tr.bars))

        // Intro longa (64) e outro curto (16): a troca é de 64; a saída recua de frase em frase até caber.
        val shortOutro = grid(128.0, 360_000, 0, listOf(Cue(-1, at(176), comment = "Outro")))
        val longIntro = grid(128.0, 360_000, 0, listOf(Cue(-1, at(64), comment = "Drop")))
        val (long, _) = SetPlanner.auto(a, shortOutro, b, longIntro)
        assertEquals(64, long.bars)
        assertTrue(long.outMs + at(64) <= 360_000, "cabe na faixa")
        assertEquals(0, (Math.round(long.outMs / bar) % 8).toInt(), "sai num início de frase")

        // Sem estrutura: 32 compassos (eletrônica mixa longo), numa frase que cabe com folga.
        val (plain, why) = SetPlanner.auto(a, grid(128.0, 360_000), b, grid(128.0, 360_000))
        assertEquals(SetPlanner.Basis.DEFAULT, why)
        assertEquals(SetPlanner.DEFAULT_BARS, plain.bars)
        assertEquals(0, (Math.round(plain.outMs / bar) % 8).toInt())
        assertTrue(plain.outMs + at(32) < 360_000)
        // Só o outro: a troca dura o outro.
        val (byOutro, why2) = SetPlanner.auto(a, ga, b, grid(128.0, 360_000))
        assertEquals(SetPlanner.Basis.OUTRO, why2)
        assertEquals(32, byOutro.bars, "o outro de A vai do compasso 160 ao fim (192)")
        // Duração escolhida à mão vale.
        assertEquals(16, SetPlanner.auto(a, ga, b, gb, fixedBars = 16).first.bars)
        // Intro curta demais (8) não fica abaixo do mínimo.
        val shortIntro = grid(128.0, 360_000, 0, listOf(Cue(-1, at(8), comment = "Drop")))
        assertEquals(SetPlanner.MIN_BARS, SetPlanner.auto(a, ga, b, shortIntro).first.bars)
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
