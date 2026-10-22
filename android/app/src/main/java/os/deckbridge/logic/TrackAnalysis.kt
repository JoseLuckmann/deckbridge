package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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
    val outroSec: Double = 0.0,
)

data class TrackRead(
    val bpm: Double,
    val bpmConfidence: Double,
    val gridErrorMs: Double,
    val gridErrorMaxMs: Double,
    val fullBandErrorMs: Double,
    val gridCoverage: Double,
    val keyName: String,
    val keyConfidence: Double,
    val beats: List<AnlzBeat>,
    val structure: StructurePoints,
    val elapsedMs: Long,
    val phrases: List<PhraseSpan> = emptyList(),
    val loudnessLufs: Double = Double.NaN,
    val energy: Double = 0.0,
)

private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
// Perfil EDMA (Faraldo e outros, música eletrônica). Não é o detector da Essentia.
private val EDMA_MAJOR = doubleArrayOf(1.00, 0.00, 0.22, 0.00, 0.39, 0.16, 0.00, 0.44, 0.00, 0.24, 0.00, 0.18)
private val EDMA_MINOR = doubleArrayOf(1.00, 0.00, 0.21, 0.32, 0.00, 0.22, 0.00, 0.43, 0.18, 0.00, 0.14, 0.20)
private val TEMPERLEY_MAJOR = doubleArrayOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0)
private val TEMPERLEY_MINOR = doubleArrayOf(5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0)
private val MAJOR_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
private val MINOR_NAMES = listOf("Cm", "Dbm", "Dm", "Ebm", "Em", "Fm", "F#m", "Gm", "Abm", "Am", "Bbm", "Bm")
private val PITCH_CLASS = listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

/** BPM, tom, grade e pontos de estrutura. Heurística deste app, não análise de um programa comercial. */
fun analyzeTrack(samples: FloatArray, rate: Int): TrackRead {
    val started = System.nanoTime()
    if (samples.size < rate / 2 || rate < 8000) {
        return TrackRead(0.0, 0.0, -1.0, -1.0, -1.0, 0.0, "—", 0.0, emptyList(), StructurePoints(0.0, 0.0, 0.0, 0.0), 0)
    }
    val structureEnv = structureEnvelope(samples, rate)
    val kick = kickFlux(samples, rate)
    val full = fullBandFlux(samples, rate)
    val peaks = strongPeaks(kick.values, kick.rate)
    val bpmPick = pickBpm(kick.values, kick.rate, peaks)
    val beats = beatGrid(peaks, bpmPick, samples.size.toDouble() / rate)
    val kickError = kickGridError(peaks, beats, bpmPick.bpm)
    val fullError = fullBandGridError(full.values, full.rate, beats)
    val key = detectKey(samples, rate)
    val duration = samples.size.toDouble() / rate
    val sections = detectSections(structureEnv.low, structureEnv.high, structureEnv.windowSec, bpmPick.bpm, duration)
    val loudness = integratedLufs(samples, rate)
    val energy = energyLevel(samples)
    val elapsed = (System.nanoTime() - started) / 1_000_000
    return TrackRead(
        bpmPick.bpm,
        bpmPick.confidence,
        kickError.first,
        kickError.second,
        fullError,
        kickError.third,
        key.first,
        key.second,
        beats,
        sections.points,
        elapsed,
        sections.phrases,
        loudness,
        energy,
    )
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

/** Peso MIREX: 1 certo, 0,5 quinta, 0,3 relativo, 0,2 paralelo, 0 o resto. */
fun mirexWeight(predicted: String, truth: String): Double {
    val pred = parseKey(predicted) ?: return 0.0
    val known = parseKey(truth) ?: return 0.0
    val dist = Math.floorMod(pred.first - known.first, 12)
    val sameMode = pred.second == known.second
    if (dist == 0 && sameMode) return 1.0
    if (dist == 0) return 0.2
    if (sameMode && (dist == 5 || dist == 7)) return 0.5
    val relative = (!sameMode) && ((pred.second && dist == 9) || (!pred.second && dist == 3))
    if (relative) return 0.3
    return 0.0
}

private fun parseKey(raw: String): Pair<Int, Boolean>? {
    val name = normalizeKeyName(raw)
    if (name.isEmpty() || name == "—") return null
    val minor = name.endsWith("m")
    val root = if (minor) name.dropLast(1) else name
    val index = PITCH_CLASS.indexOf(root)
    if (index < 0) return null
    return index to minor
}

private data class BpmPick(val bpm: Double, val confidence: Double, val phase: Double)

private data class Flux(val values: FloatArray, val rate: Double)

private data class StructEnv(val low: DoubleArray, val high: DoubleArray, val windowSec: Double)

private class Biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
    private val nb0 = (b0 / a0).toFloat()
    private val nb1 = (b1 / a0).toFloat()
    private val nb2 = (b2 / a0).toFloat()
    private val na1 = (a1 / a0).toFloat()
    private val na2 = (a2 / a0).toFloat()
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = nb0 * x + z1
        z1 = nb1 * x - na1 * y + z2
        z2 = nb2 * x - na2 * y
        return y
    }
}

private fun lowpass(rate: Int, freq: Double): Biquad {
    val w0 = 2.0 * PI * freq / rate
    val cosine = cos(w0)
    val sine = sin(w0)
    val alpha = sine / (2.0 * 0.7071067811865476)
    val b0 = (1.0 - cosine) / 2.0
    val b1 = 1.0 - cosine
    val b2 = (1.0 - cosine) / 2.0
    val a0 = 1.0 + alpha
    val a1 = -2.0 * cosine
    val a2 = 1.0 - alpha
    return Biquad(b0, b1, b2, a0, a1, a2)
}

private fun zeroPhaseLowpass(samples: FloatArray, rate: Int, freq: Double): FloatArray {
    var current = samples
    repeat(2) {
        val forward = FloatArray(current.size)
        val forth = lowpass(rate, freq)
        for (index in current.indices) forward[index] = forth.process(current[index])
        val back = FloatArray(current.size)
        val reverse = lowpass(rate, freq)
        for (index in current.indices.reversed()) back[index] = reverse.process(forward[index])
        current = back
    }
    return current
}

private fun kickFlux(samples: FloatArray, rate: Int): Flux {
    val low = zeroPhaseLowpass(samples, rate, 180.0)
    val rumble = zeroPhaseLowpass(samples, rate, 35.0)
    val band = FloatArray(samples.size) { index -> low[index] - rumble[index] }
    return fluxOf(band, rate)
}

private fun fullBandFlux(samples: FloatArray, rate: Int): Flux {
    val hop = max(1, rate / 200)
    val frames = samples.size / hop
    val env = FloatArray(frames)
    var previous = 0.0
    for (index in 0 until frames) {
        val start = index * hop
        val end = min(samples.size, start + hop)
        var energy = 0.0
        var cursor = start
        while (cursor < end) {
            energy += abs(samples[cursor])
            cursor += 1
        }
        energy /= (end - start).coerceAtLeast(1)
        env[index] = (energy - previous).coerceAtLeast(0.0).toFloat()
        previous = energy
    }
    return Flux(env, rate.toDouble() / hop)
}

private fun fluxOf(band: FloatArray, rate: Int): Flux {
    val hop = max(1, rate / 200)
    val frames = band.size / hop
    val env = FloatArray(frames)
    var previous = 0.0
    for (index in 0 until frames) {
        val start = index * hop
        var energy = 0.0
        var cursor = start
        val end = start + hop
        while (cursor < end && cursor < band.size) {
            val sample = band[cursor]
            energy += sample * sample
            cursor += 1
        }
        energy /= hop
        env[index] = (energy - previous).coerceAtLeast(0.0).toFloat()
        previous = energy
    }
    return Flux(env, rate.toDouble() / hop)
}

private fun structureEnvelope(samples: FloatArray, rate: Int): StructEnv {
    val hop = max(1, rate / 200)
    val envRate = rate.toDouble() / hop
    val frames = samples.size / hop
    val window = 2.0
    val winFrames = max(1, (envRate * window).roundToInt())
    val windows = max(1, frames / winFrames)
    val low = DoubleArray(windows)
    val high = DoubleArray(windows)
    val lowAcc = DoubleArray(windows)
    val highAcc = DoubleArray(windows)
    val count = IntArray(windows)
    val coef = kotlin.math.exp(-2.0 * PI * 180.0 / rate).toFloat()
    val mix = 1f - coef
    var state = 0f
    for (index in 0 until frames) {
        val start = index * hop
        val end = min(samples.size, start + hop)
        var bass = 0.0
        var rest = 0.0
        var cursor = start
        while (cursor < end) {
            val sample = samples[cursor]
            state = coef * state + mix * sample
            val highBand = sample - state
            bass += state * state
            rest += highBand * highBand
            cursor += 1
        }
        val span = (end - start).coerceAtLeast(1)
        val bin = min(windows - 1, index / winFrames)
        lowAcc[bin] += bass / span
        highAcc[bin] += rest / span
        count[bin] += 1
    }
    for (index in 0 until windows) {
        val n = count[index].coerceAtLeast(1)
        low[index] = sqrt(lowAcc[index] / n)
        high[index] = sqrt(highAcc[index] / n)
    }
    return StructEnv(low, high, window)
}

private fun pickBpm(env: FloatArray, envRate: Double, peaks: DoubleArray): BpmPick {
    if (env.size < 8) return BpmPick(0.0, 0.0, 0.0)
    val minLag = max(1, (envRate * 60.0 / 190.0).roundToInt())
    val maxLag = min(env.size / 2, (envRate * 60.0 / 80.0).roundToInt())
    if (maxLag <= minLag) return BpmPick(0.0, 0.0, 0.0)
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
    val lags = ArrayList<Int>()
    for (lag in (minLag + 1) until maxLag) {
        if (scores[lag] >= scores[lag - 1] && scores[lag] > scores[lag + 1]) lags += lag
    }
    lags.sortByDescending { scores[it] }
    if (lags.isEmpty()) lags += minLag
    val duration = env.size / envRate
    // A cobertura dos picos sozinha prefere 3/4 e 3/2 quando há picos entre as batidas.
    // O pente no envelope e a autocorrelação têm de concordar. O prior é largo (cerca de uma oitava)
    // à volta de 124, para house e drum and bass caberem os dois.
    var chosen = 120.0
    var best = -1.0
    var bpm = 70.0
    while (bpm <= 180.0) {
        val comb = combScore(env, envRate, bpm)
        val periodic = interpolatedScore(scores, envRate, bpm).coerceAtLeast(0.0)
        val score = comb * (periodic + 1e-9) * meterPrior(bpm)
        if (score > best) {
            best = score
            chosen = bpm
        }
        bpm += 1.0
    }
    var local = chosen
    var localBest = best
    var delta = -0.8
    while (delta <= 0.8) {
        val candidate = chosen + delta
        if (candidate in 70.0..180.0) {
            val score = combScore(env, envRate, candidate) * (interpolatedScore(scores, envRate, candidate).coerceAtLeast(0.0) + 1e-9) * meterPrior(candidate)
            if (score > localBest) {
                localBest = score
                local = candidate
            }
        }
        delta += 0.2
    }
    val (phase, _) = coverage(peaks, local, duration, 72)
    val refined = refineTempo(peaks, local, phase, duration)
    val confidence = if (mean <= 1e-12) 0.0 else (scores[lags.first()] / mean).coerceIn(0.0, 99.0)
    return BpmPick(refined.first, confidence, refined.second)
}

private fun interpolatedScore(scores: DoubleArray, envRate: Double, bpm: Double): Double {
    if (bpm < 40.0 || scores.size < 3) return 0.0
    val lag = envRate * 60.0 / bpm
    val at = lag.toInt()
    if (at < 1 || at >= scores.lastIndex) return 0.0
    val frac = lag - at
    return scores[at] * (1.0 - frac) + scores[at + 1] * frac
}

private fun meterPrior(bpm: Double): Double {
    val octaves = kotlin.math.ln(bpm / 124.0) / kotlin.math.ln(2.0)
    return kotlin.math.exp(-0.5 * octaves * octaves / (1.2 * 1.2))
}

/** Corrige o período pela inclinação dos kicks que já caem perto da grade. Um BPM 0,3 acima desloca o fim da faixa em dezenas de ms. */
private fun refineTempo(peaks: DoubleArray, bpm: Double, phase: Double, duration: Double): Pair<Double, Double> {
    val beat = 60.0 / bpm
    var sumX = 0.0
    var sumY = 0.0
    var sumXX = 0.0
    var sumXY = 0.0
    var count = 0
    var time = phase
    var index = 0.0
    while (time < duration && index < 4000) {
        if (peaks.isNotEmpty()) {
            val delta = nearestPeak(peaks, time) - time
            if (abs(delta) <= 0.025) {
                sumX += index
                sumY += delta
                sumXX += index * index
                sumXY += index * delta
                count += 1
            }
        }
        time += beat
        index += 1.0
    }
    if (count < 12) return coverage(peaks, bpm, duration, 120).let { bpm to it.first }
    val denom = count * sumXX - sumX * sumX
    if (abs(denom) < 1e-9) return coverage(peaks, bpm, duration, 120).let { bpm to it.first }
    val slope = (count * sumXY - sumX * sumY) / denom
    val newBeat = beat + slope
    val newBpm = 60.0 / newBeat
    if (newBpm < 70.0 || newBpm > 185.0 || abs(newBpm - bpm) > 3.0) return coverage(peaks, bpm, duration, 120).let { bpm to it.first }
    val (newPhase, _) = coverage(peaks, newBpm, duration, 120)
    return newBpm to newPhase
}

/** Fração das batidas do metrônomo que caem a até 18 ms de um pico grave, e a fase que maximiza isso. */
private fun coverage(peaks: DoubleArray, bpm: Double, duration: Double, steps: Int): Pair<Double, Double> {
    if (bpm < 40.0 || duration <= 0.0) return 0.0 to 0.0
    val beat = 60.0 / bpm
    var bestPhase = 0.0
    var bestHits = -1
    var bestTotal = 1
    for (step in 0 until steps) {
        val phase = beat * step / steps
        var hits = 0
        var total = 0
        var time = phase
        while (time < duration && total < 4000) {
            if (peaks.isNotEmpty() && abs(nearestPeak(peaks, time) - time) <= 0.018) hits += 1
            total += 1
            time += beat
        }
        if (hits > bestHits) {
            bestHits = hits
            bestTotal = total.coerceAtLeast(1)
            bestPhase = phase
        }
    }
    return bestPhase to bestHits.toDouble() / bestTotal
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

private fun combScore(env: FloatArray, envRate: Double, bpm: Double): Double {
    if (bpm < 40.0) return 0.0
    val beat = 60.0 / bpm
    var best = 0.0
    for (step in 0 until 36) {
        val phase = beat * step / 36.0
        val score = phaseSum(env, envRate, phase, beat)
        if (score > best) best = score
    }
    return best
}

private fun phaseSum(env: FloatArray, envRate: Double, phase: Double, beat: Double): Double {
    var cursor = phase
    var sum = 0.0
    var hits = 0
    while (cursor < (env.size - 1) / envRate) {
        val index = cursor * envRate
        val at = index.toInt().coerceIn(0, env.lastIndex - 1)
        val frac = index - at
        sum += env[at] * (1.0 - frac) + env[at + 1] * frac
        hits += 1
        cursor += beat
    }
    return if (hits == 0) 0.0 else sum / hits
}

private fun strongPeaks(env: FloatArray, envRate: Double): DoubleArray {
    if (env.size < 4) return DoubleArray(0)
    val gate = percentile(env, 0.84)
    val found = ArrayList<Double>()
    for (index in 1 until env.lastIndex) {
        val value = env[index]
        if (value < gate || value < env[index - 1] || value < env[index + 1]) continue
        val left = env[index - 1].toDouble()
        val mid = value.toDouble()
        val right = env[index + 1].toDouble()
        val denom = left - 2.0 * mid + right
        val delta = if (abs(denom) < 1e-12) 0.0 else (0.5 * (left - right) / denom).coerceIn(-0.5, 0.5)
        found += (index + delta + 0.5) / envRate
    }
    return found.toDoubleArray()
}

private fun nearestPeak(peaks: DoubleArray, time: Double): Double {
    if (peaks.isEmpty()) return time
    var low = 0
    var high = peaks.size - 1
    while (low < high) {
        val mid = (low + high) ushr 1
        if (peaks[mid] < time) low = mid + 1 else high = mid
    }
    var best = peaks[low]
    if (low > 0 && abs(peaks[low - 1] - time) < abs(best - time)) best = peaks[low - 1]
    return best
}

private fun beatGrid(peaks: DoubleArray, pick: BpmPick, durationSec: Double): List<AnlzBeat> {
    if (pick.bpm < 40.0) return emptyList()
    // O período fica o do autocorr. Seguir o snap (mesmo um de 16 ms) esticava o
    // período e a fase atravessava a batida: a mediana contra o kick ia para ~1/4.
    val period = 60.0 / pick.bpm
    val times = ArrayList<Double>()
    var cursor = pick.phase
    var guard = 0
    while (cursor <= durationSec + 0.001 && guard < 8000) {
        val near = if (peaks.isEmpty()) cursor else nearestPeak(peaks, cursor)
        val used = if (abs(near - cursor) <= 0.020) near else cursor
        if (used >= -0.001) times += used.coerceAtLeast(0.0)
        cursor += period
        guard += 1
    }
    return times.mapIndexed { index, time ->
        AnlzBeat((index % 4) + 1, pick.bpm, (time * 1000.0).roundToInt().coerceAtLeast(0))
    }
}

private fun snapFlux(env: FloatArray, envRate: Double, time: Double, windowSec: Double, gate: Double): Double? {
    val center = time * envRate
    val radius = (windowSec * envRate).roundToInt().coerceAtLeast(1)
    val start = (center.toInt() - radius).coerceAtLeast(1)
    val end = (center.toInt() + radius).coerceAtMost(env.lastIndex - 1)
    if (end <= start) return null
    var best = start
    var bestVal = env[start]
    for (index in start..end) {
        if (env[index] > bestVal) {
            bestVal = env[index]
            best = index
        }
    }
    if (bestVal < gate) return null
    val left = env[best - 1].toDouble()
    val mid = env[best].toDouble()
    val right = env[best + 1].toDouble()
    val denom = left - 2.0 * mid + right
    val delta = if (abs(denom) < 1e-12) 0.0 else (0.5 * (left - right) / denom).coerceIn(-0.5, 0.5)
    return (best + delta + 0.5) / envRate
}

private fun kickGridError(peaks: DoubleArray, beats: List<AnlzBeat>, bpm: Double): Triple<Double, Double, Double> {
    if (beats.size < 4 || peaks.isEmpty() || bpm < 40.0) return Triple(-1.0, -1.0, 0.0)
    val errors = ArrayList<Double>()
    var covered = 0
    for (beat in beats) {
        val time = beat.timeMs / 1000.0
        val distance = abs(nearestPeak(peaks, time) - time)
        // Fora de 20 ms o pico mais perto é outra figura (contratempo, fill).
        // Não entra na mediana: a cobertura conta essas batidas à parte.
        if (distance <= 0.020) {
            covered += 1
            errors += distance * 1000.0
        }
    }
    if (errors.size < 4) return Triple(-1.0, -1.0, covered.toDouble() / beats.size)
    errors.sort()
    return Triple(errors[errors.size / 2], errors.last(), covered.toDouble() / beats.size)
}

private fun fullBandGridError(env: FloatArray, envRate: Double, beats: List<AnlzBeat>): Double {
    if (beats.size < 4 || env.size < 8) return -1.0
    var mean = 0.0
    for (value in env) mean += value
    mean /= env.size
    val gate = mean * 1.8
    val errors = ArrayList<Double>()
    for (index in 1 until env.lastIndex) {
        val value = env[index]
        if (value < gate || value < env[index - 1] || value < env[index + 1]) continue
        val seconds = (index + 0.5) / envRate
        var nearest = beats[0].timeMs / 1000.0
        var best = abs(nearest - seconds)
        for (beat in beats) {
            val time = beat.timeMs / 1000.0
            val distance = abs(time - seconds)
            if (distance < best) {
                best = distance
                nearest = time
            }
        }
        errors += abs(nearest - seconds) * 1000.0
    }
    if (errors.size < 4) return -1.0
    errors.sort()
    return errors[errors.size / 2]
}

private fun percentile(values: FloatArray, fraction: Double): Double {
    if (values.isEmpty()) return 0.0
    val copy = values.copyOf()
    copy.sort()
    val index = ((copy.size - 1) * fraction).roundToInt().coerceIn(0, copy.lastIndex)
    return copy[index].toDouble()
}

private fun detectKey(samples: FloatArray, rate: Int): Pair<String, Double> {
    val frame = 4096
    if (samples.size < frame * 2) return "—" to 0.0
    val hop = frame
    val count = (samples.size - frame) / hop
    if (count < 4) return "—" to 0.0
    val rms = DoubleArray(count)
    for (index in 0 until count) {
        val start = index * hop
        var energy = 0.0
        for (cursor in start until start + frame) energy += samples[cursor] * samples[cursor]
        rms[index] = energy / frame
    }
    val sorted = rms.copyOf()
    sorted.sort()
    val gate = sorted[sorted.size / 2]
    val loud = ArrayList<Int>()
    for (index in 0 until count) {
        if (rms[index] >= gate) loud += index
    }
    val step = max(1, loud.size / 220)
    // Um seno puro não tem terça. O perfil menor ganha no cosseno porque a tónica pesa mais.
    // Sem terça maior nem menor, o nome é o maior dessa nota.
    run {
        val chroma = DoubleArray(12)
        var used = 0
        var cursor = 0
        while (cursor < loud.size && used < 40) {
            addChroma(samples, loud[cursor] * hop, frame, rate, chroma, 0.0)
            used += 1
            cursor += step
        }
        val energy = chroma.sum()
        if (energy > 1e-6) {
            val peak = chroma.max()
            if (peak / energy >= 0.5) {
                val name = MAJOR_NAMES[chroma.indices.maxBy { chroma[it] }]
                return name to (peak / energy).coerceIn(0.0, 1.0)
            }
        }
    }
    var bestName = "C"
    var bestMargin = -1.0
    var bestScore = 0.0
    var cents = -30
    while (cents <= 30) {
        val chroma = DoubleArray(12)
        var used = 0
        var cursor = 0
        while (cursor < loud.size) {
            addChroma(samples, loud[cursor] * hop, frame, rate, chroma, cents / 100.0)
            used += 1
            cursor += step
        }
        if (used >= 4) {
            val profiles = listOf(
                matchProfiles(chroma, EDMA_MAJOR, EDMA_MINOR),
                matchProfiles(chroma, MAJOR, MINOR),
                matchProfiles(chroma, TEMPERLEY_MAJOR, TEMPERLEY_MINOR),
            )
            val pick = profiles.maxBy { it.third }
            if (pick.third > bestMargin) {
                bestMargin = pick.third
                bestName = pick.first
                bestScore = pick.second
            }
        }
        cents += 10
    }
    return bestName to bestScore.coerceIn(0.0, 1.0)
}

private fun addChroma(samples: FloatArray, offset: Int, frame: Int, rate: Int, chroma: DoubleArray, semitoneShift: Double) {
    val window = DoubleArray(frame) { index ->
        0.5 - 0.5 * cos(2.0 * PI * index / (frame - 1))
    }
    for (pc in 0 until 12) {
        var mag = 0.0
        for (octave in 2..6) {
            val midi = 12 * octave + pc
            val freq = 440.0 * 2.0.pow((midi - 69 + semitoneShift) / 12.0)
            if (freq < 40.0 || freq > rate * 0.45) continue
            val weight = if (octave <= 3) 1.7 else 1.0
            mag += weight * goertzel(samples, offset, frame, rate, freq, window)
        }
        chroma[pc] += mag
    }
}

private fun goertzel(samples: FloatArray, offset: Int, n: Int, rate: Int, freq: Double, window: DoubleArray): Double {
    val coeff = 2.0 * cos(2.0 * PI * freq / rate)
    var s1 = 0.0
    var s2 = 0.0
    for (index in 0 until n) {
        val s0 = samples[offset + index] * window[index] + coeff * s1 - s2
        s2 = s1
        s1 = s0
    }
    val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
    return kotlin.math.ln(1.0 + sqrt(power.coerceAtLeast(0.0)))
}

private fun matchProfiles(chroma: DoubleArray, major: DoubleArray, minor: DoubleArray): Triple<String, Double, Double> {
    val energy = chroma.sum()
    if (energy < 1e-6) return Triple("—", 0.0, -1.0)
    var bestName = "C"
    var best = -2.0
    var second = -2.0
    for (shift in 0 until 12) {
        val majorScore = correlate(chroma, major, shift)
        val minorScore = correlate(chroma, minor, shift)
        for ((score, name) in listOf(majorScore to MAJOR_NAMES[shift], minorScore to MINOR_NAMES[shift])) {
            if (score > best) {
                second = best
                best = score
                bestName = name
            } else if (score > second) {
                second = score
            }
        }
    }
    return Triple(bestName, best, best - second)
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
    var text = raw.trim()
    text = text.replace(Regex("(?i)\\s+minor$"), "m")
    text = text.replace(Regex("(?i)\\s+major$"), "")
    text = text.replace(" ", "")
    text = text.replace(Regex("(?i)minor$"), "m")
    text = text.replace(Regex("(?i)major$"), "")
    val trimmed = text.replace("maj", "").replace("min", "m")
    if (trimmed.isEmpty()) return ""
    return trimmed
        .replace("D#m", "Ebm")
        .replace("D#", "Eb")
        .replace("A#m", "Bbm")
        .replace("A#", "Bb")
        .replace("G#m", "Abm")
        .replace("G#", "Ab")
        .replace("C#m", "Dbm")
        .replace("C#", "Db")
        .replace("Gbm", "F#m")
        .replace("Gb", "F#")
        .replace("Cbm", "Bm")
        .replace("Cb", "B")
}
