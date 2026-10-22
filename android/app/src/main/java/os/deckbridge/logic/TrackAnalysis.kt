package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class StructurePoints(
    val introSec: Double,
    val buildSec: Double,
    val dropSec: Double,
    val breakSec: Double,
)

data class TrackRead(
    val bpm: Double,
    val bpmConfidence: Double,
    val gridErrorMs: Double,
    val keyName: String,
    val keyConfidence: Double,
    val beats: List<AnlzBeat>,
    val structure: StructurePoints,
    val elapsedMs: Long,
)

private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
private val MAJOR_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
private val MINOR_NAMES = listOf("Cm", "Dbm", "Dm", "Ebm", "Em", "Fm", "F#m", "Gm", "Abm", "Am", "Bbm", "Bm")

/** BPM, tom, grade e pontos de estrutura. Heurística deste app, não análise de um programa comercial. */
fun analyzeTrack(samples: FloatArray, rate: Int): TrackRead {
    val started = System.nanoTime()
    if (samples.size < rate / 2 || rate < 8000) {
        return TrackRead(0.0, 0.0, -1.0, "—", 0.0, emptyList(), StructurePoints(0.0, 0.0, 0.0, 0.0), 0)
    }
    val env = onsetEnvelope(samples, rate)
    val bpmPick = pickBpm(env.values, env.rate)
    val beats = beatGrid(env.values, env.rate, bpmPick.bpm, samples.size.toDouble() / rate)
    val error = gridErrorMs(env.values, env.rate, beats)
    val key = detectKey(samples, rate)
    val structure = structurePoints(env.low, env.high, env.windowSec)
    val elapsed = (System.nanoTime() - started) / 1_000_000
    return TrackRead(bpmPick.bpm, bpmPick.confidence, error, key.first, key.second, beats, structure, elapsed)
}

fun structurePoints(low: DoubleArray, high: DoubleArray, windowSec: Double): StructurePoints {
    if (low.isEmpty() || high.isEmpty() || windowSec <= 0.0) return StructurePoints(0.0, 0.0, 0.0, 0.0)
    val count = min(low.size, high.size)
    var peak = 1e-9
    var dropIndex = 0
    for (index in 0 until count) {
        if (low[index] > peak) {
            peak = low[index]
            dropIndex = index
        }
    }
    val intro = 0
    var build = intro
    var bestRise = 0.0
    for (index in 1..dropIndex) {
        val rise = high[index] - high[index - 1]
        if (rise > bestRise) {
            bestRise = rise
            build = index
        }
    }
    var breakIndex = dropIndex
    var quiet = peak
    val last = max(dropIndex, (count * 0.92).toInt().coerceAtMost(count - 1))
    for (index in dropIndex + 1..last) {
        if (low[index] < quiet) {
            quiet = low[index]
            breakIndex = index
        }
    }
    if (quiet > peak * 0.72) breakIndex = dropIndex
    return StructurePoints(intro * windowSec, build * windowSec, dropIndex * windowSec, breakIndex * windowSec)
}

private data class BpmPick(val bpm: Double, val confidence: Double)

private data class Envelope(val values: FloatArray, val rate: Double, val low: DoubleArray, val high: DoubleArray, val windowSec: Double)

private fun onsetEnvelope(samples: FloatArray, rate: Int): Envelope {
    val hop = max(1, rate / 200)
    val envRate = rate.toDouble() / hop
    val frames = samples.size / hop
    val env = FloatArray(frames)
    var previous = 0.0
    val coef = exp(-2.0 * PI * 180.0 / rate).toFloat()
    val mix = 1f - coef
    var lowState = 0f
    val window = 2.0
    val winFrames = max(1, (envRate * window).roundToInt())
    val windows = max(1, frames / winFrames)
    val low = DoubleArray(windows)
    val high = DoubleArray(windows)
    val lowAcc = DoubleArray(windows)
    val highAcc = DoubleArray(windows)
    val lowCount = IntArray(windows)
    for (index in 0 until frames) {
        val start = index * hop
        val end = min(samples.size, start + hop)
        var energy = 0.0
        var bass = 0.0
        var rest = 0.0
        var cursor = start
        while (cursor < end) {
            val sample = samples[cursor]
            lowState = coef * lowState + mix * sample
            val highBand = sample - lowState
            energy += abs(sample)
            bass += lowState * lowState
            rest += highBand * highBand
            cursor += 1
        }
        val span = (end - start).coerceAtLeast(1)
        energy /= span
        val flux = (energy - previous).coerceAtLeast(0.0)
        previous = energy
        env[index] = flux.toFloat()
        val bin = min(windows - 1, index / winFrames)
        lowAcc[bin] += bass / span
        highAcc[bin] += rest / span
        lowCount[bin] += 1
    }
    for (index in 0 until windows) {
        val n = lowCount[index].coerceAtLeast(1)
        low[index] = sqrt(lowAcc[index] / n)
        high[index] = sqrt(highAcc[index] / n)
    }
    return Envelope(env, envRate, low, high, window)
}

private fun pickBpm(env: FloatArray, envRate: Double): BpmPick {
    if (env.size < 8) return BpmPick(0.0, 0.0)
    val minLag = max(1, (envRate * 60.0 / 180.0).roundToInt())
    val maxLag = min(env.size / 2, (envRate * 60.0 / 80.0).roundToInt())
    if (maxLag <= minLag) return BpmPick(0.0, 0.0)
    val scores = DoubleArray(maxLag + 1)
    var mean = 0.0
    var counted = 0
    for (lag in minLag..maxLag) {
        var acc = 0.0
        val limit = env.size - lag
        var index = 0
        while (index < limit) {
            acc += env[index] * env[index + lag]
            index += 1
        }
        val score = acc / limit.coerceAtLeast(1)
        scores[lag] = score
        mean += score
        counted += 1
    }
    mean /= counted.coerceAtLeast(1)
    var bestLag = minLag
    var best = scores[minLag]
    for (lag in minLag..maxLag) {
        if (scores[lag] > best) {
            best = scores[lag]
            bestLag = lag
        }
    }
    var bandLag = -1
    var bandScore = -1.0
    for (lag in minLag..maxLag) {
        val bpm = envRate * 60.0 / lag
        if (bpm in 115.0..145.0 && scores[lag] >= best * 0.72 && scores[lag] > bandScore) {
            bandLag = lag
            bandScore = scores[lag]
        }
    }
    val chosen = if (bandLag > 0) bandLag else bestLag
    val chosenScore = if (bandLag > 0) bandScore else best
    val refined = parabolicLag(scores, chosen)
    val bpm = envRate * 60.0 / refined
    val confidence = if (mean <= 1e-12) 0.0 else (chosenScore / mean).coerceIn(0.0, 99.0)
    return BpmPick(bpm, confidence)
}

private fun parabolicLag(scores: DoubleArray, lag: Int): Double {
    if (lag <= 0 || lag >= scores.lastIndex) return lag.toDouble()
    val left = scores[lag - 1]
    val mid = scores[lag]
    val right = scores[lag + 1]
    val denom = left - 2.0 * mid + right
    if (abs(denom) < 1e-12) return lag.toDouble()
    val delta = (0.5 * (left - right) / denom).coerceIn(-0.5, 0.5)
    return lag + delta
}

private fun beatGrid(env: FloatArray, envRate: Double, bpm: Double, durationSec: Double): List<AnlzBeat> {
    if (bpm < 40.0 || env.isEmpty()) return emptyList()
    val period = envRate * 60.0 / bpm
    if (period < 1.0) return emptyList()
    val peaks = onsetPeaks(env, envRate)
    val steps = 72
    var bestPhase = 0.0
    var bestError = Double.POSITIVE_INFINITY
    val beatSec = 60.0 / bpm
    for (step in 0 until steps) {
        val phaseSec = beatSec * step / steps
        val error = if (peaks.size >= 4) medianDistance(peaks, phaseSec, beatSec) else phaseScore(env, envRate, phaseSec * envRate, period)
        val better = if (peaks.size >= 4) error < bestError else error > bestError
        if (step == 0 || better) {
            bestError = error
            bestPhase = phaseSec
        }
    }
    val out = ArrayList<AnlzBeat>()
    var time = bestPhase
    var index = 0
    while (time <= durationSec + 0.001 && index < 4000) {
        out += AnlzBeat((index % 4) + 1, bpm, (time * 1000.0).roundToInt().coerceAtLeast(0))
        time += 60.0 / bpm
        index += 1
    }
    return out
}

private fun onsetPeaks(env: FloatArray, envRate: Double): List<Double> {
    if (env.size < 8) return emptyList()
    var mean = 0.0
    for (value in env) mean += value
    mean /= env.size
    val gate = mean * 1.8
    val peaks = ArrayList<Double>()
    for (index in 1 until env.lastIndex) {
        val value = env[index]
        if (value < gate || value < env[index - 1] || value < env[index + 1]) continue
        peaks += index / envRate
    }
    return peaks
}

private fun medianDistance(peaks: List<Double>, phaseSec: Double, beatSec: Double): Double {
    if (peaks.isEmpty() || beatSec <= 0.0) return 1e9
    val errors = ArrayList<Double>(peaks.size)
    for (peak in peaks) {
        val beat = ((peak - phaseSec) / beatSec).roundToInt()
        val grid = phaseSec + beat * beatSec
        errors += abs(grid - peak) * 1000.0
    }
    errors.sort()
    return errors[errors.size / 2]
}

private fun phaseScore(env: FloatArray, envRate: Double, phaseSample: Double, period: Double): Double {
    var score = 0.0
    var cursor = phaseSample
    while (cursor < env.size) {
        val index = cursor.toInt()
        if (index in env.indices) score += env[index]
        cursor += period
    }
    return score
}

private fun gridErrorMs(env: FloatArray, envRate: Double, beats: List<AnlzBeat>): Double {
    if (beats.size < 4 || env.size < 8) return -1.0
    var mean = 0.0
    for (value in env) mean += value
    mean /= env.size
    val gate = mean * 1.8
    val errors = ArrayList<Double>()
    for (index in 1 until env.lastIndex) {
        val value = env[index]
        if (value < gate || value < env[index - 1] || value < env[index + 1]) continue
        val seconds = index / envRate
        val nearest = beats.minBy { abs(it.timeMs / 1000.0 - seconds) }
        errors += abs(nearest.timeMs / 1000.0 - seconds) * 1000.0
    }
    if (errors.size < 4) return -1.0
    errors.sort()
    return errors[errors.size / 2]
}

private fun detectKey(samples: FloatArray, rate: Int): Pair<String, Double> {
    val frame = 4096
    if (samples.size < frame) return "—" to 0.0
    val chroma = DoubleArray(12)
    fun eat(from: Int, until: Int, cap: Int) {
        var start = from
        var frames = 0
        while (start + frame < until && frames < cap) {
            addChroma(samples, start, frame, rate, chroma)
            start += frame * 2
            frames += 1
        }
    }
    eat(0, min(samples.size, rate * 24), 40)
    val mid = (samples.size * 0.45).toInt()
    eat(mid, min(samples.size, mid + rate * 12), 24)
    return matchProfile(chroma)
}

private fun addChroma(samples: FloatArray, offset: Int, frame: Int, rate: Int, chroma: DoubleArray) {
    for (pc in 0 until 12) {
        var mag = 0.0
        for (octave in 2..6) {
            val midi = 12 * octave + pc
            val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
            if (freq < 50.0 || freq > rate * 0.45) continue
            mag += goertzel(samples, offset, frame, rate, freq)
        }
        chroma[pc] += mag
    }
}

private fun goertzel(samples: FloatArray, offset: Int, n: Int, rate: Int, freq: Double): Double {
    val coeff = 2.0 * cos(2.0 * PI * freq / rate)
    var s1 = 0.0
    var s2 = 0.0
    for (index in 0 until n) {
        val s0 = samples[offset + index] + coeff * s1 - s2
        s2 = s1
        s1 = s0
    }
    val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
    return sqrt(power.coerceAtLeast(0.0))
}

private fun matchProfile(chroma: DoubleArray): Pair<String, Double> {
    val energy = chroma.sum()
    if (energy < 1e-6) return "—" to 0.0
    var bestName = "C"
    var best = -2.0
    for (shift in 0 until 12) {
        val major = correlate(chroma, MAJOR, shift)
        val minor = correlate(chroma, MINOR, shift)
        if (major > best) {
            best = major
            bestName = MAJOR_NAMES[shift]
        }
        if (minor > best) {
            best = minor
            bestName = MINOR_NAMES[shift]
        }
    }
    return bestName to best.coerceIn(0.0, 1.0)
}

private fun correlate(chroma: DoubleArray, profile: DoubleArray, shift: Int): Double {
    var dot = 0.0
    var a = 0.0
    var b = 0.0
    for (index in 0 until 12) {
        val left = chroma[(index + shift) % 12]
        val right = profile[index]
        dot += left * right
        a += left * left
        b += right * right
    }
    if (a <= 1e-12 || b <= 1e-12) return 0.0
    return dot / sqrt(a * b)
}

fun normalizeKeyName(raw: String): String {
    val trimmed = raw.trim().replace(" ", "").replace("maj", "").replace("min", "m")
    if (trimmed.isEmpty()) return ""
    val flat = trimmed
        .replace("D#m", "Ebm")
        .replace("D#", "Eb")
        .replace("A#m", "Bbm")
        .replace("A#", "Bb")
        .replace("G#m", "Abm")
        .replace("G#", "Ab")
        .replace("C#m", "Dbm")
        .replace("C#", "Db")
    return if (flat.endsWith("m") || flat.endsWith("M") && flat.length > 1 && flat.last() == 'm') flat else flat
}
