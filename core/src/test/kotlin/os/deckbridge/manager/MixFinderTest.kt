package os.deckbridge.manager

import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.manager.mix.MixFinder
import os.deckbridge.manager.mix.MixRenderer
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.SegmentReader
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.TrackProfile
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.TrackItem

/**
 * Achar a troca como um DJ: faixas sintéticas com estrutura conhecida (intro só bateria, drop com baixo e acorde, break
 * só acorde, outro só bateria). O perfil tem de ver grave, bateria e melodia onde estão; o localizador tem de sair no
 * outro, entrar na intro, virar o grave num ponto natural e usar loop quando um lado é curto.
 */
class MixFinderTest {
    private val rate = 22050
    private val bpm = 124.0

    /** Uma parte da faixa: quantos compassos e o que toca nela. */
    private data class Part(val bars: Int, val kick: Boolean, val bass: Boolean, val pad: Boolean)

    private val intro = { n: Int -> Part(n, kick = true, bass = false, pad = false) }
    private val drop = { n: Int -> Part(n, kick = true, bass = true, pad = true) }
    private val brk = { n: Int -> Part(n, kick = false, bass = false, pad = true) }
    private val outro = intro

    /** A faixa: bumbo, chimbal, baixo contínuo e um acorde em [root] Hz (o "tom"). */
    private fun track(parts: List<Part>, root: Double = 220.0): FloatArray {
        val barSec = 240.0 / bpm
        val beatSec = 60.0 / bpm
        val bars = parts.sumOf { it.bars }
        val out = FloatArray((bars * barSec * rate).toInt() + rate)
        var noise = 12345
        var bar = 0
        for (p in parts) {
            for (b in 0 until p.bars) {
                val barStart = (bar + b) * barSec
                val from = (barStart * rate).toInt()
                val to = ((barStart + barSec) * rate).toInt().coerceAtMost(out.size)
                for (i in from until to) {
                    val t = i.toDouble() / rate
                    val inBeat = (t - barStart) % beatSec
                    var x = 0.0
                    if (p.kick) {
                        x += 0.9 * exp(-inBeat * 20.0) * sin(2 * PI * (50.0 + 80.0 * exp(-inBeat * 40.0)) * inBeat)
                        noise = noise * 1103515245 + 12345
                        val n = ((noise ushr 16) and 0x7FFF) / 16384.0 - 1.0
                        val off = (inBeat - beatSec / 2).let { if (it < 0) it + beatSec else it }
                        x += 0.12 * n * exp(-off * 60.0)
                    }
                    // Baixo contínuo que abaixa em cada batida para o bumbo passar (o "sidechain" do house).
                    if (p.bass) x += 0.25 * (1 - exp(-inBeat * 10.0)) * sin(2 * PI * 55.0 * t)
                    if (p.pad) {
                        // Acorde com harmônicos agudos (voz/synth): médio e agudo fortes.
                        x += 0.08 * (sin(2 * PI * root * t) + sin(2 * PI * root * 1.26 * t) + sin(2 * PI * root * 1.5 * t))
                        x += 0.03 * (sin(2 * PI * root * 16 * t) + sin(2 * PI * root * 20.2 * t))
                    }
                    out[i] = x.toFloat()
                }
            }
            bar += p.bars
        }
        return out
    }

    private fun item(id: Int, key: String, bars: Int) =
        TrackItem(id, "T$id", "A", "", "House", key, "", "", bpm, 0, 0, 0, 0, (bars * 240.0 / bpm).toInt() + 1, 0, 0, "/Contents/$id.wav", "$id.wav", "")

    private fun profile(samples: FloatArray) = assertNotNull(TrackProfile.of(samples, rate, bpm, 0.0))

    private fun reader(samples: FloatArray) = SegmentReader { start, len ->
        val from = (start.toLong() * rate / 1000).toInt().coerceIn(0, samples.size)
        val to = (from + len.toLong() * rate / 1000).toInt().coerceIn(from, samples.size)
        val s = samples.copyOfRange(from, to)
        DecodedPcm(s, s.copyOf(), rate)
    }

    // A que sai: intro 16, drop 32, break 16, drop 32, outro 32.
    private val partsA = listOf(intro(16), drop(32), brk(16), drop(32), outro(32))
    // A que entra: intro 32 (só bateria), drop 32, break 16, drop 32, outro 16.
    private val partsB = listOf(intro(32), drop(32), brk(16), drop(32), outro(16))

    @Test fun `o perfil ve grave, bateria e melodia onde estao`() {
        val p = profile(track(partsA))
        assertEquals(128, p.bars, "${p.bars} compassos")
        // Intro: bateria sem grave e sem melodia; drop: tudo; break: melodia sem bateria nem grave.
        for (bar in 2 until 14) assertTrue(p.beatsOnly(bar), "intro $bar")
        for (bar in 18 until 46) { assertTrue(p.bass(bar), "grave no drop $bar"); assertTrue(p.drums(bar)); assertTrue(p.melodic(bar), "melodia $bar") }
        for (bar in 50 until 62) { assertTrue(!p.bass(bar) && !p.drums(bar) && p.melodic(bar), "break $bar") }
        for (bar in 98 until 126) assertTrue(p.beatsOnly(bar), "outro $bar")
        val kinds = p.phrases.map { it.kind }.fold(emptyList<String>()) { acc, k -> if (acc.lastOrNull() == k) acc else acc + k }
        assertEquals(listOf("Intro", "Chorus", "Down", "Chorus", "Outro"), kinds, p.phrases.toString())
        assertEquals(96, p.phrases.first { it.kind == "Outro" }.start)
        // Guardado e lido de volta igual.
        val f = Files.createTempDirectory("perfil").resolve("1.txt").toFile()
        TrackProfile.save(f, p)
        val back = assertNotNull(TrackProfile.load(f))
        assertEquals(p.phrases, back.phrases)
        assertEquals(p.bass(20), back.bass(20))
        assertEquals(p.barMs, back.barMs, 0.01)
    }

    @Test fun `sai no outro, entra na intro e o drop da que entra cai quando a outra acaba`() {
        val pa = profile(track(partsA))
        val pb = profile(track(partsB))
        val plans = MixFinder.find(item(1, "8A", 128), pa, item(2, "8A", 128), pb)
        assertTrue(plans.isNotEmpty())
        val best = plans.first()
        val t = best.transition
        assertEquals(MixStyle.BASS_SWAP, t.style, best.toString())
        val out = pa.barAt(t.outMs + 5)
        val inn = pb.barAt(t.inMs + 5)
        assertEquals(96, out, "sai no começo do Outro: $best")
        // Intro de 32 só bateria sobre o outro de 32: a mixagem longa clássica, e o drop de B cai no fim.
        val landing = inn + t.bars - t.bLoopExtra
        assertEquals(32, landing, "o drop da que entra cai no fim da troca: $best")
        assertTrue(t.bars >= 16, "troca longa: ${t.bars}")
        assertTrue(best.reasons.any { "drop" in it }, best.reasons.toString())
        assertTrue(t.why.isNotEmpty())
        // O grave vira no drop de B (o fim) ou onde ninguém tem grave: nunca dois graves juntos.
        assertEquals(1.0, t.swapAt, 0.01, best.toString())
        // Variedade: as outras opções não são a mesma troca.
        assertEquals(plans.size, plans.map { listOf(it.transition.style, it.transition.bars / 8, it.transition.aLoopBars, it.transition.bLoopBars) }.distinct().size)
    }

    @Test fun `outro curto e intro longa pede loop na que sai`() {
        // A acaba com só 8 compassos de outro; B tem 32 de intro antes do drop.
        val shortOut = listOf(intro(16), drop(32), brk(16), drop(32), outro(8))
        val pa = profile(track(shortOut))
        val pb = profile(track(partsB))
        val plans = MixFinder.find(item(1, "8A", 104), pa, item(2, "8A", 128), pb, limit = 6)
        val looped = plans.firstOrNull { it.transition.aLoopBars > 0 || it.transition.bLoopBars > 0 }
        assertNotNull(looped, plans.joinToString("\n"))
        assertTrue(looped.reasons.any { "loop" in it }, looped.reasons.toString())
        // A melhor não deixa a que sai acabar antes do drop da que entra: ou loop, ou entra mais perto do drop.
        val best = plans.first().transition
        val outEnd = pa.barAt(best.outMs + 5) + (if (best.aLoopBars > 0) best.bars else best.bars)
        assertTrue(outEnd <= pa.bars || best.aLoopBars > 0, plans.first().toString())
    }

    @Test fun `tons longe nao juntam duas melodias`() {
        // A com acorde no meio de tudo até o fim (outro com melodia); B em outro tom.
        val melodicOut = listOf(intro(16), drop(32), brk(16), drop(32), Part(32, kick = true, bass = false, pad = true))
        val melodicIn = listOf(Part(32, kick = true, bass = false, pad = true), drop(32), brk(16), drop(32), outro(16))
        val pa = profile(track(melodicOut, root = 220.0))
        val pb = profile(track(melodicIn, root = 311.1))
        val clash = MixFinder.find(item(1, "8A", 128), pa, item(2, "3A", 128), pb)
        val best = clash.first()
        // Ou curta (eco/corte/filtro) ou longa sem melodia junta: nunca 16+ compassos de duas melodias.
        if (best.transition.style == MixStyle.BASS_SWAP) {
            val out = pa.barAt(best.transition.outMs + 5)
            val inn = pb.barAt(best.transition.inMs + 5)
            val together = (0 until best.transition.bars).count { pa.melodic(out + it) && pb.melodic(inn + it) }
            assertTrue(together <= 4, "$together compassos de duas melodias: $best")
        }
        // Com o mesmo tom, a longa com melodias juntas pontua mais que com tons longe.
        val same = MixFinder.find(item(1, "8A", 128), pa, item(2, "8A", 128), pb)
        assertTrue(same.first().score >= best.score, "${same.first()} vs $best")
    }

    @Test fun `o loop da que sai repete o trecho na grade e a previa sabe onde ela esta`() {
        val samples = track(partsA)
        val barMs = SetPlanner.barMs(bpm)
        val outMs = (96 * barMs).toInt()
        val tr = Transition(1, 2, outMs, 0, bars = 16, style = MixStyle.BASS_SWAP, masterTempo = false, phaseLock = false, aLoopBars = 4)
        val p = assertNotNull(MixRenderer.transition(reader(samples), bpm, reader(track(partsB)), bpm, tr, leadMs = 4000, tailMs = 2000))
        // 6 compassos depois da saída, a que sai está no 2º compasso do loop de 4.
        val at = p.leadMs + (6 * p.barMsA).toInt()
        assertEquals(outMs + 2 * barMs, p.aAt(at).toDouble(), 3.0)
        // E passa pelo começo do loop a cada 4 compassos.
        val times = p.timesA(outMs)
        assertTrue(times.size >= 4, times.toString())
        times.zipWithNext().forEach { (x, y) -> assertEquals(4 * p.barMsA, (y - x).toDouble(), 2.0) }
        // B com loop de 4 compassos 2×: o drop dela cai 4 compassos mais tarde.
        val tb = tr.copy(aLoopBars = 0, bLoopBars = 4, bLoopTimes = 2)
        val pb = assertNotNull(MixRenderer.transition(reader(samples), bpm, reader(track(partsB)), bpm, tb, leadMs = 4000, tailMs = 2000))
        assertEquals(pb.leadMs + 4 * pb.barMsA, pb.fromB((4 * barMs).toInt()).toDouble(), 3.0)
        assertEquals(2, pb.timesB((barMs).toInt()).size)
        assertEquals((2 * barMs).toInt().toDouble(), pb.bAt(pb.leadMs + (6 * pb.barMsA).toInt()).toDouble(), 3.0)
        // Os loops mudam o áudio: montar de novo.
        assertTrue(!pb.sameStems(tr))
    }

    @Test fun `loop e motivo ficam no plano do set`() {
        val tr = Transition(1, 2, 60_000, 960, 32, aLoopBars = 8, bLoopBars = 4, bLoopTimes = 2, why = listOf("sai no Outro", "loop de 8"))
        val file = Files.createTempDirectory("set").resolve("x.properties").toFile()
        SetPlanner.save(file, listOf(tr))
        assertEquals(tr, SetPlanner.load(file).single())
    }
}
