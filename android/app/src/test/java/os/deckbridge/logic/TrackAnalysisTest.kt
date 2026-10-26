package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class TrackAnalysisTest {
    @Test
    fun clickTrackLocksThePublishedBpm() {
        val rate = 22050
        val bpm = 120.0
        val seconds = 16
        val samples = FloatArray(rate * seconds)
        val interval = rate * 60.0 / bpm
        var cursor = 0.0
        while (cursor < samples.size) {
            val at = cursor.toInt()
            val burst = (rate * 0.004).toInt()
            for (offset in 0 until burst) {
                if (at + offset < samples.size) samples[at + offset] = 0.9f
            }
            cursor += interval
        }
        val read = analyzeTrack(samples, rate)
        assertEquals(120.0, read.bpm, 1.5)
        assertTrue(read.beats.size > 20)
        assertTrue("erro ${read.gridErrorMs}", read.gridErrorMs in 0.0..30.0)
    }

    @Test
    fun gridLocksToTheKickWhenTheHatIsOnTheOffbeat() {
        val rate = 22050
        val bpm = 120.0
        val seconds = 16
        val samples = FloatArray(rate * seconds)
        val interval = rate * 60.0 / bpm
        var beat = interval
        while (beat + interval < samples.size) {
            val at = beat.toInt()
            val burst = (rate * 0.045).toInt()
            for (offset in 0 until burst) {
                val index = at + offset
                if (index >= samples.size) break
                val env = 1.0 - offset.toDouble() / burst
                samples[index] = (env * sin(2.0 * PI * 70.0 * index / rate) * 0.8).toFloat()
            }
            val hat = (beat + interval / 2.0).toInt()
            for (offset in 0 until 6) {
                val index = hat + offset
                if (index < samples.size) samples[index] = if (offset % 2 == 0) 0.95f else -0.95f
            }
            beat += interval
        }
        val read = analyzeTrack(samples, rate)
        val kickErrors = read.beats.map { marker ->
            val time = marker.timeMs / 1000.0
            val nearest = (time / 0.5).roundToInt() * 0.5
            abs(time - nearest) * 1000.0
        }.filter { it < 200.0 }
        val median = kickErrors.sorted()[kickErrors.size / 2]
        val max = kickErrors.maxOrNull() ?: 999.0
        assertTrue("mediana $median max $max bpm ${read.bpm}", median < 10.0 && max < 20.0)
    }

    @Test
    fun gridLocksToTheKickWhenAnOffbeatBassIsLouder() {
        val rate = 24000
        val bpm = 128.0
        val samples = FloatArray(rate * 60)
        val beat = rate * 60.0 / bpm
        val random = java.util.Random(7)
        var index = 0
        while (index * beat + beat < samples.size) {
            val kick = (index * beat).toInt() + (0.012 * rate).toInt()
            for (offset in 0 until (0.18 * rate).toInt()) {
                val at = kick + offset
                if (at >= samples.size) break
                val t = offset.toDouble() / rate
                val body = sin(2.0 * PI * (52.0 + 90.0 * kotlin.math.exp(-t * 40.0)) * t) * kotlin.math.exp(-t * 18.0) * 0.35
                val click = if (offset < 70) (random.nextDouble() * 2.0 - 1.0) * 0.45 * (1.0 - offset / 70.0) else 0.0
                samples[at] += (body + click).toFloat()
            }
            // Baixo no contratempo, mais longo e mais forte que o bumbo, e chapéu aberto no mesmo lugar.
            val off = ((index + 0.5) * beat).toInt() + (0.012 * rate).toInt()
            for (offset in 0 until (0.22 * rate).toInt()) {
                val at = off + offset
                if (at >= samples.size) break
                val t = offset.toDouble() / rate
                samples[at] += (sin(2.0 * PI * 58.0 * t) * kotlin.math.exp(-t * 6.0) * 0.90).toFloat()
                if (offset < (0.09 * rate).toInt()) samples[at] += ((random.nextDouble() * 2.0 - 1.0) * 0.12 * (1.0 - offset / (0.09 * rate))).toFloat()
            }
            index += 1
        }
        val read = analyzeTrack(samples, rate)
        val period = 60.0 / read.bpm
        val first = read.beats.first().timeMs / 1000.0
        val kickPhase = 0.012
        var delta = (first - kickPhase) % period
        if (delta > period / 2) delta -= period
        if (delta < -period / 2) delta += period
        assertTrue("bpm ${read.bpm} primeira batida $first erro ${delta * 1000.0} ms", abs(delta) < 0.020)
    }

    /** Bumbo com clique e corpo, a [bpm], o clique começando em [firstSec] + k·período. */
    private fun kickTrack(rate: Int, bpm: Double, seconds: Int, firstSec: Double): FloatArray {
        val samples = FloatArray(rate * seconds)
        val period = 60.0 / bpm
        val random = java.util.Random(3)
        var k = 0
        while (firstSec + k * period < seconds - 0.3) {
            val at = ((firstSec + k * period) * rate).toInt()
            for (offset in 0 until (0.2 * rate).toInt()) {
                val t = offset.toDouble() / rate
                val body = sin(2.0 * PI * (50.0 + 80.0 * kotlin.math.exp(-t * 40.0)) * t) * kotlin.math.exp(-t * 15.0) * 0.6
                val click = if (offset < (0.004 * rate).toInt()) (random.nextDouble() * 2.0 - 1.0) * 0.5 else 0.0
                samples[at + offset] += (body + click).toFloat()
            }
            k += 1
        }
        return samples
    }

    @Test
    fun aGridOffTheClickIsMovedOntoItAndMadeRegular() {
        // Grade de partida 14 ms depois do clique e com período um pouco errado (como a de uma faixa em que a batida
        // era recolada nos picos do fluxo): a passada final põe no início do clique e acerta o andamento.
        val rate = 22050
        val bpm = 126.0
        val first = 0.300
        val samples = kickTrack(rate, bpm, 90, first)
        val wrongPeriod = 60.0 / 125.99
        val start = List(180) { k -> AnlzBeat(k % 4 + 1, 125.99, ((first + 0.014 + k * wrongPeriod) * 1000).roundToInt()) }
        val (beats, newBpm) = alignGridToClick(samples, rate, start, 125.99)!!
        assertEquals(126.0, newBpm, 0.005)
        val errors = beats.map { b -> val k = ((b.timeMs / 1000.0 - first) / (60.0 / bpm)).roundToInt(); b.timeMs / 1000.0 - (first + k * 60.0 / bpm) }
        val worst = errors.maxOf { abs(it) } * 1000
        assertTrue("pior batida a $worst ms do clique", worst < 2.5)
        assertEquals(start.map { it.beatInBar }.take(beats.size), beats.map { it.beatInBar })
    }

    @Test
    fun withoutAClearKickTheGridIsLeftAlone() {
        val rate = 22050
        val samples = FloatArray(rate * 60) { (0.3 * sin(2.0 * PI * 220.0 * it / rate)).toFloat() }
        val start = List(120) { k -> AnlzBeat(k % 4 + 1, 120.0, k * 500) }
        assertEquals(null, alignGridToClick(samples, rate, start, 120.0))
    }

    @Test
    fun theWholeAnalysisPutsTwoDifferentKicksOnTheirClicks() {
        // Duas faixas com bumbos diferentes: a grade das duas tem de cair no clique, para o sync alinhar os bumbos.
        val rate = 22050
        for ((bpm, first) in listOf(126.0 to 0.137, 128.0 to 0.412)) {
            val read = analyzeTrack(kickTrack(rate, bpm, 60, first), rate)
            val period = 60.0 / read.bpm
            val offsets = read.beats.drop(4).map { b ->
                val k = ((b.timeMs / 1000.0 - first) / period).roundToInt()
                (b.timeMs / 1000.0 - (first + k * period)) * 1000
            }.sorted()
            val median = offsets[offsets.size / 2]
            assertTrue("$bpm BPM: mediana $median ms do clique", abs(median) < 2.5)
        }
    }

    @Test
    fun sineAt440IsA() {
        val rate = 22050
        val samples = FloatArray(rate * 2) { index ->
            (0.4 * sin(2.0 * PI * 440.0 * index / rate)).toFloat()
        }
        val read = analyzeTrack(samples, rate)
        assertEquals("A", read.keyName)
        assertTrue(read.keyConfidence > 0.2)
    }

    @Test
    fun structureFollowsBassEnergy() {
        val low = doubleArrayOf(0.05, 0.08, 0.20, 0.90, 0.95, 0.22, 0.12, 0.40)
        val high = doubleArrayOf(0.04, 0.10, 0.80, 0.30, 0.28, 0.20, 0.10, 0.08)
        val points = structurePoints(low, high, 2.0)
        assertEquals(0.0, points.introSec, 0.01)
        assertEquals(4.0, points.buildSec, 0.01)
        assertEquals(8.0, points.dropSec, 0.01)
        assertEquals(12.0, points.breakSec, 0.01)
    }
}
