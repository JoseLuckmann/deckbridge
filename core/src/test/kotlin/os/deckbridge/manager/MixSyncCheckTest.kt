package os.deckbridge.manager

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.manager.anlz.Beat
import os.deckbridge.manager.importer.AudioIo
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.mix.MixRenderer
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.SegmentReader
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.Stereo
import os.deckbridge.manager.mix.TrackGrid
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.TrackItem

/**
 * Medição com músicas de verdade (fora do repositório): `MIX_CHECK=<pasta com a.wav e b.wav>`. Mede (1) se a grade da
 * análise cai nos bumbos de cada faixa e (2) se, na prévia montada, os bumbos das duas coincidem ao longo da troca.
 * Imprime números; não decide sozinho.
 */
class MixSyncCheckTest {
    // 49 amostras a 44,1 kHz = 1,111… ms exatos (um passo arredondado faria a régua escorregar).
    private val hopSamples = 49
    private val hopMs = hopSamples * 1000.0 / 44100

    /** Força de ataque do grave (bumbo): passa-baixa em ~150 Hz, energia por 2 ms, só subidas. */
    private fun kickOnset(l: FloatArray, r: FloatArray, rate: Int): DoubleArray {
        val a = 1 - exp(-2 * PI * 150.0 / rate)
        var y1 = 0.0
        var y2 = 0.0
        require(rate == 44100)
        val hop = hopSamples
        val n = minOf(l.size, r.size) / hop
        val env = DoubleArray(n)
        for (k in 0 until n) {
            var e = 0.0
            for (i in k * hop until (k + 1) * hop) {
                val x = 0.5 * (l[i] + r[i])
                y1 += a * (x - y1)
                y2 += a * (y1 - y2)
                e += y2 * y2
            }
            env[k] = e
        }
        val out = DoubleArray(n)
        for (k in 1 until n) out[k] = maxOf(0.0, env[k] - env[k - 1])
        return out
    }

    /** Força de ataque de palma/caixa: banda de ~1,5 a 5 kHz. */
    private fun clapOnset(l: FloatArray, r: FloatArray, rate: Int): DoubleArray {
        val ah = 1 - exp(-2 * PI * 1500.0 / rate)
        val al = 1 - exp(-2 * PI * 5000.0 / rate)
        var lowForHp = 0.0
        var lp = 0.0
        val n = minOf(l.size, r.size) / hopSamples
        val env = DoubleArray(n)
        for (k in 0 until n) {
            var e = 0.0
            for (i in k * hopSamples until (k + 1) * hopSamples) {
                val x = 0.5 * (l[i] + r[i])
                lowForHp += ah * (x - lowForHp)
                val hp = x - lowForHp
                lp += al * (hp - lp)
                e += lp * lp
            }
            env[k] = e
        }
        val out = DoubleArray(n)
        for (k in 1 until n) out[k] = maxOf(0.0, env[k] - env[k - 1])
        return out
    }

    /** Soma de ataques perto de cada tempo do compasso (1..4), conforme a grade: mostra onde cai a palma. */
    private fun barProfile(o: DoubleArray, beats: List<Pair<Int, Double>>): DoubleArray {
        val p = DoubleArray(4)
        for ((inBar, ms) in beats) {
            val c = (ms / hopMs).toInt()
            var m = 0.0
            for (k in (c - 30)..(c + 60)) if (k in o.indices) m = maxOf(m, o[k])
            p[(inBar - 1).coerceIn(0, 3)] += m
        }
        val t = p.sum().coerceAtLeast(1e-12)
        return DoubleArray(4) { p[it] / t }
    }

    private fun prof(p: DoubleArray) = p.joinToString("  ") { "%.2f".format(it) }

    /** Onde (ms) está o ataque mais forte perto de [ms], dentro de ±[win] ms. */
    private fun peakNear(o: DoubleArray, ms: Double, win: Double): Double? {
        val c = (ms / hopMs).toInt()
        val w = (win / hopMs).toInt()
        var best = -1
        var bv = 0.0
        for (k in (c - w)..(c + w)) if (k in o.indices && o[k] > bv) { bv = o[k]; best = k }
        return if (best < 0 || bv <= 0) null else best * hopMs
    }

    private fun median(v: List<Double>) = v.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }

    private fun item(id: Int, p: os.deckbridge.manager.importer.PreparedTrack) =
        TrackItem(id, p.source.nameWithoutExtension, "", "", "", p.read.keyName, "", "", p.read.bpm, 0, 0, 0, 0, p.info.durationSec, 0, 0, p.source.path, p.source.name, "")

    @Test fun measure() {
        val dir = System.getenv("MIX_CHECK")?.let(::File)
        assumeTrue("sem MIX_CHECK", dir?.isDirectory == true)
        val fa = File(dir, "a.wav")
        val fb = File(dir, "b.wav")
        val pa = ImportPrep.prepare(fa)
        val pb = ImportPrep.prepare(fb)
        val beatsA = pa.read.beats.map { Beat(it.beatInBar, (it.bpm * 100).toInt(), it.timeMs) }
        val beatsB = pb.read.beats.map { Beat(it.beatInBar, (it.bpm * 100).toInt(), it.timeMs) }
        val ga = TrackGrid(beatsA, emptyList(), pa.info.durationSec * 1000)
        val gb = TrackGrid(beatsB, emptyList(), pb.info.durationSec * 1000)
        val a = item(1, pa)
        val b = item(2, pb)
        println("A bpm=%.3f gridErr=%.1fms max=%.1f cobertura=%.2f primeira=%d".format(pa.read.bpm, pa.read.gridErrorMs, pa.read.gridErrorMaxMs, pa.read.gridCoverage, beatsA.first().timeMs))
        println("B bpm=%.3f gridErr=%.1fms max=%.1f cobertura=%.2f primeira=%d".format(pb.read.bpm, pb.read.gridErrorMs, pb.read.gridErrorMaxMs, pb.read.gridCoverage, beatsB.first().timeMs))
        // Espaçamento real da grade (se a grade é regular, é 60000/bpm).
        fun spacing(bs: List<Beat>) = (bs.last().timeMs - bs.first().timeMs).toDouble() / (bs.size - 1)
        println("espacamento A=%.3f (esperado %.3f)  B=%.3f (esperado %.3f)".format(spacing(beatsA), 60000 / pa.read.bpm, spacing(beatsB), 60000 / pb.read.bpm))

        // (1) A grade contra os bumbos da própria faixa, por trechos de 32 batidas.
        fun gridVsKicks(name: String, f: File, bs: List<Beat>, fromMs: Int, toMs: Int) {
            val pcm = AudioIo.segment(f, fromMs, toMs - fromMs)!!
            val o = kickOnset(pcm.left, pcm.right, pcm.sampleRate)
            val inside = bs.filter { it.timeMs in (fromMs + 100) until (toMs - 100) }
            inside.chunked(32).forEach { ch ->
                val offs = ch.mapNotNull { bt -> peakNear(o, (bt.timeMs - fromMs).toDouble(), 60.0)?.let { it - (bt.timeMs - fromMs) } }
                println("$name grade x bumbo  t=%6.1fs  desvio mediano=%+6.1f ms  (n=%d)".format(ch.first().timeMs / 1000.0, median(offs), offs.size))
            }
        }
        // (1b) Fase dobrada: em janelas de 16 batidas, a força de ataque somada no período da grade; o pico diz onde o
        // bumbo cai em relação à grade (negativo = antes da batida da grade). Robusto a bumbos faltando.
        fun folded(name: String, f: File, bs: List<Beat>, bpm: Double, every: Int) {
            val pcm = AudioIo.segment(f, 0, 20 * 60_000)!!
            val o = kickOnset(pcm.left, pcm.right, pcm.sampleRate)
            val period = 60000.0 / bpm
            val bins = (period / hopMs).toInt()
            for (start in bs.indices step every) {
                val ch = bs.subList(start, minOf(bs.size, start + 16))
                if (ch.size < 8) break
                val h = DoubleArray(bins)
                for (bt in ch) for (k in 0 until bins) {
                    val idx = ((bt.timeMs - period / 2) / hopMs).toInt() + k
                    if (idx in o.indices) h[k] += o[idx]
                }
                val peak = h.indices.maxByOrNull { h[it] }!!
                val total = h.sum().coerceAtLeast(1e-12)
                println("$name fase t=%6.1fs  bumbo em %+6.1f ms da grade  (nitidez %.2f)".format(ch.first().timeMs / 1000.0, peak * hopMs - period / 2, h[peak] / total * bins))
            }
        }
        // (1d) Ataque fino do bumbo contra a grade, faixa inteira, por trechos de 64 batidas.
        fun fineVsGrid(name: String, f: File, bs: List<Beat>) {
            val pcm = AudioIo.segment(f, 0, 20 * 60_000)!!
            val st = Stereo(pcm.left.copyOf(pcm.frameCount), pcm.right.copyOf(pcm.frameCount), pcm.sampleRate)
            val a1 = 1 - exp(-2 * PI * 120.0 / 44100)
            var y1 = 0.0; var y2 = 0.0
            val env = DoubleArray(st.frames)
            for (i in 0 until st.frames) { val x = 0.5 * (st.left[i] + st.right[i]); y1 += a1 * (x - y1); y2 += a1 * (y1 - y2); env[i] = abs(y2) }
            val w = 132
            val sm = DoubleArray(env.size); var acc = 0.0
            for (i in env.indices) { acc += env[i]; if (i >= w) acc -= env[i - w]; sm[i] = acc / w }
            bs.chunked(64).forEach { ch ->
                val d = ch.mapNotNull { bt ->
                    val from = ((bt.timeMs - 60) * 44.1).toInt().coerceAtLeast(0)
                    val to = ((bt.timeMs + 90) * 44.1).toInt().coerceAtMost(sm.size)
                    if (to - from < 100) return@mapNotNull null
                    var pk = from
                    for (i in from until to) if (sm[i] > sm[pk]) pk = i
                    val half = sm[pk] / 2
                    var i = pk
                    while (i > from && sm[i] > half) i--
                    if (i == from) null else i / 44.1 - bt.timeMs
                }
                // Período real: reta pelos ataques (com desvio < 40 ms da mediana) contra o índice da batida.
                val med = median(d)
                val pts = ch.zip(ch.indices.toList()).mapNotNull { (bt, k) -> d.getOrNull(ch.indexOf(bt))?.takeIf { abs(it - med) < 40 }?.let { k.toDouble() to bt.timeMs + it } }
                val n = pts.size.toDouble()
                val mx = pts.sumOf { it.first } / n; val my = pts.sumOf { it.second } / n
                val slope = pts.sumOf { (it.first - mx) * (it.second - my) } / pts.sumOf { (it.first - mx) * (it.first - mx) }.coerceAtLeast(1e-9)
                println("$name ataque fino x grade t=%6.1fs  mediana=%+6.1f ms  período real=%.2f ms (%.2f BPM, n=%d)".format(ch.first().timeMs / 1000.0, med, slope, 60000 / slope, pts.size))
            }
        }
        fineVsGrid("A", fa, beatsA)
        fineVsGrid("B", fb, beatsB)
        // (1c) Palmas por tempo do compasso, na faixa inteira.
        for ((name, f, bs) in listOf(Triple("A", fa, beatsA), Triple("B", fb, beatsB))) {
            val pcm = AudioIo.segment(f, 0, 20 * 60_000)!!
            val o = clapOnset(pcm.left, pcm.right, pcm.sampleRate)
            val k = kickOnset(pcm.left, pcm.right, pcm.sampleRate)
            bs.chunked(64).forEach { ch ->
                val list = ch.map { it.inBar to it.timeMs.toDouble() }
                println("$name compasso t=%6.1fs  palma por tempo 1..4: %s   bumbo: %s".format(ch.first().timeMs / 1000.0, prof(barProfile(o, list)), prof(barProfile(k, list))))
            }
        }
        folded("A", fa, beatsA, pa.read.bpm, 64)
        folded("B", fb, beatsB, pb.read.bpm, 32)
        val out = SetPlanner.defaultOut(a, ga, 16)
        val inn = SetPlanner.defaultIn(gb)
        println("saida=%d entrada=%d".format(out, inn))
        gridVsKicks("A", fa, beatsA, out - 20_000, out + 40_000)
        gridVsKicks("B", fb, beatsB, 0, 60_000)

        // (2) Na prévia: stems separados (o outro deck em silêncio), bumbo de B perto de cada batida de A.
        val silent = SegmentReader { _, len -> val n = len * 441 / 10; DecodedPcm(FloatArray(n), FloatArray(n), 44100) }
        val ra = SegmentReader { s, l -> AudioIo.segment(fa, s, l) }
        val rb = SegmentReader { s, l -> AudioIo.segment(fb, s, l) }
        for (mt in listOf(true, false)) {
            val tr = Transition(1, 2, out, inn, 16, MixStyle.BLEND, mt)
            val onlyA = MixRenderer.transition(ra, a.bpm, silent, b.bpm, tr)!!
            val both = MixRenderer.transition(ra, a.bpm, rb, b.bpm, tr)!!
            val rawB = MixRenderer.transition(silent, a.bpm, rb, b.bpm, tr)!!
            // O mesmo ajuste que a prévia completa aplicou, na stem de B.
            val sh = (both.phaseMs * 44.1).toInt()
            val onlyB = object { val audio = Stereo(FloatArray(rawB.audio.frames) { rawB.audio.left.getOrElse(it + sh) { 0f } }, FloatArray(rawB.audio.frames) { rawB.audio.right.getOrElse(it + sh) { 0f } }, 44100) }
            println("  prévia completa: sync fino medido=%s aplicado=%.1f ms".format(both.measuredMs?.let { "%+.1f".format(it) }, both.phaseMs))
            val oa = kickOnset(onlyA.audio.left, onlyA.audio.right, 44100)
            val ob = kickOnset(onlyB.audio.left, onlyB.audio.right, 44100)
            val p = onlyA
            val beatsInOverlap = beatsA.map { p.fromA(it.timeMs) }.filter { it in p.leadMs until p.leadMs + p.overlapMs }
            println("MT=$mt velocidade=%.5f  batidas de A no trecho junto=%d  sync fino medido=%s aplicado=%.1f ms".format(p.speed, beatsInOverlap.size, p.measuredMs?.let { "%+.1f".format(it) }, p.phaseMs))
            beatsInOverlap.chunked(8).forEach { ch ->
                val d = ch.mapNotNull { t ->
                    val ka = peakNear(oa, t.toDouble(), 60.0) ?: return@mapNotNull null
                    val kb = peakNear(ob, t.toDouble(), 120.0) ?: return@mapNotNull null
                    kb - ka
                }
                val gridB = ch.mapNotNull { t -> peakNear(ob, t.toDouble(), 120.0)?.let { it - t } }
                println("  t=%5.1fs  bumbo B - bumbo A mediano=%+6.1f ms  (B - batida A da grade=%+6.1f)  n=%d".format((ch.first() - p.leadMs) / 1000.0, median(d), median(gridB), d.size))
            }
            // Ataque fino: amplitude do grave (envoltória, 1,1 ms) cruzando metade do pico local, batida a batida.
            fun attack(st: Stereo, ms: Double): Double? {
                val a1 = 1 - exp(-2 * PI * 120.0 / 44100)
                val from = ((ms - 120) * 44.1).toInt().coerceAtLeast(0)
                val to = ((ms + 120) * 44.1).toInt().coerceAtMost(st.frames)
                if (to - from < 100) return null
                var y1 = 0.0; var y2 = 0.0
                val env = DoubleArray(to - from)
                for (i in from until to) { val x = 0.5 * (st.left[i] + st.right[i]); y1 += a1 * (x - y1); y2 += a1 * (y1 - y2); env[i - from] = abs(y2) }
                // suaviza a envoltória em 3 ms
                val sm = DoubleArray(env.size); var acc = 0.0; val w = 132
                for (i in env.indices) { acc += env[i]; if (i >= w) acc -= env[i - w]; sm[i] = acc / w }
                val peakAt = sm.indices.maxByOrNull { sm[it] } ?: return null
                val half = sm[peakAt] / 2
                var i = peakAt
                while (i > 0 && sm[i] > half) i--
                return (from + i) / 44.1
            }
            val diffs = beatsInOverlap.mapNotNull { t -> val x = attack(onlyA.audio, t.toDouble()); val y = attack(onlyB.audio, t.toDouble()); if (x != null && y != null) y - x else null }
            val sd = diffs.sorted()
            println("  ataque do bumbo B - A, batida a batida (ms): p10=%+.1f mediana=%+.1f p90=%+.1f  n=%d".format(sd[sd.size / 10], sd[sd.size / 2], sd[sd.size * 9 / 10], sd.size))
            println("  sequência: " + diffs.joinToString(" ") { "%+.0f".format(it) })
            val ca = clapOnset(onlyA.audio.left, onlyA.audio.right, 44100)
            val cb = clapOnset(onlyB.audio.left, onlyB.audio.right, 44100)
            val inA = beatsA.map { it.inBar to p.fromA(it.timeMs).toDouble() }.filter { it.second >= p.leadMs && it.second < p.leadMs + p.overlapMs }
            println("  trecho junto, pelos tempos do compasso da A: palma A %s | palma B %s".format(prof(barProfile(ca, inA)), prof(barProfile(cb, inA))))
            val inB = beatsB.map { it.inBar to p.fromB(it.timeMs).toDouble() }.filter { it.second >= p.leadMs && it.second < p.leadMs + p.overlapMs }
            println("  trecho junto, compasso de B marcado como 1 cai no tempo da A: %s".format(inB.filter { it.first == 1 }.take(4).map { (_, ms) -> inA.minByOrNull { abs(it.second - ms) }?.first }))
            if (mt) File(dir, "stemA.wav").writeBytes(MixRenderer.wav(onlyA.audio)).also { File(dir, "stemB.wav").writeBytes(MixRenderer.wav(onlyB.audio)) }
        }
    }

    @Test fun beforeAfter() {
        val dir = System.getenv("MIX_CHECK")?.let(::File)
        assumeTrue("sem MIX_CHECK", dir?.isDirectory == true)
        val fa = File(dir, "a.wav")
        val fb = File(dir, "b.wav")
        val pa = ImportPrep.prepare(fa)
        val pb = ImportPrep.prepare(fb)
        val ga = TrackGrid(pa.read.beats.map { Beat(it.beatInBar, (it.bpm * 100).toInt(), it.timeMs) }, emptyList(), pa.info.durationSec * 1000)
        val gb = TrackGrid(pb.read.beats.map { Beat(it.beatInBar, (it.bpm * 100).toInt(), it.timeMs) }, emptyList(), pb.info.durationSec * 1000)
        val a = item(1, pa)
        val b = item(2, pb)
        val ra = SegmentReader { s, l -> AudioIo.segment(fa, s, l) }
        val rb = SegmentReader { s, l -> AudioIo.segment(fb, s, l) }
        val tr = Transition(1, 2, SetPlanner.defaultOut(a, ga, 16), SetPlanner.defaultIn(gb), 16, MixStyle.BASS_SWAP, true)
        for ((name, lock) in listOf("antes" to false, "depois" to true)) {
            val p = MixRenderer.transition(ra, a.bpm, rb, b.bpm, tr.copy(phaseLock = lock), leadMs = 8000, tailMs = 8000)!!
            println("$name: medido=%s aplicado=%.1f ms".format(p.measuredMs?.let { "%+.1f".format(it) }, p.phaseMs))
            File(dir, "$name.wav").writeBytes(MixRenderer.wav(p.audio))
        }
    }

    @Suppress("unused")
    private fun Stereo.mono() = FloatArray(frames) { 0.5f * (left[it] + right[it]) }
}
