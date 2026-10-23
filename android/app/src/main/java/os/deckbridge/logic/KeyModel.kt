package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sqrt

/**
 * Tom por picos espectrais (STFT de 8192 amostras a 22050 Hz, salto de 2048, picos com interpolação parabólica,
 * 3 compartimentos por semitom, ajuste de afinação) e um classificador linear treinado no corpus GiantSteps:
 * os mesmos pesos valem para as 12 tônicas (transposição compartilhada), uma para maior e uma para menor, sobre o
 * cromagrama completo e o cromagrama grave (picos abaixo de 260 Hz). A faixa é dividida em 4 trechos e os
 * log-probabilidades somam. Pesos treinados só no treino; o holdout fica de fora (docs/testing/tempo-corpus.md).
 */
private val KEY_W = arrayOf(
    doubleArrayOf(2.67966, -1.77074, 1.04083, -1.40739, 1.49691, -0.17993, -1.24161, 2.24656, -1.63708, -0.37838, -1.56932, 0.07090, 4.44604, -1.78972, -0.31864, -1.57868, 0.18901, 0.60452, -1.99571, 2.85868, -1.05093, 0.19828, -2.06395, -1.41172),
    doubleArrayOf(6.32860, -1.47998, 1.80871, 2.45269, -1.77357, -1.93980, -3.44630, 5.09039, -2.60808, -4.08869, 1.96158, -1.65595, 9.06599, -2.52137, -1.81544, 2.27071, -0.02846, -0.87921, -1.65856, 3.50710, -0.40728, -3.55373, 0.12711, -2.19404),
)
private val KEY_B = doubleArrayOf(0.01972334, -0.01972334)

private const val KEY_FFT = 8192
private const val KEY_HOP = 2048
private const val KEY_SEGMENTS = 4

private fun keyName(index: Int): String = if (index < 12) MAJOR_NAMES[index] else MINOR_NAMES[index - 12]

internal fun detectKeyPeaks(samples: FloatArray, rate: Int): Pair<String, Double> {
    if (samples.size < KEY_FFT + KEY_HOP * 8 || rate < 8000) return "—" to 0.0
    val frames = (samples.size - KEY_FFT) / KEY_HOP + 1
    val fft = Fft(KEY_FFT)
    val window = DoubleArray(KEY_FFT) { 0.5 - 0.5 * cos(2.0 * PI * it / (KEY_FFT - 1)) }
    val re = DoubleArray(KEY_FFT)
    val im = DoubleArray(KEY_FFT)
    val mag = DoubleArray(KEY_FFT / 2 + 1)
    val low = Math.ceil(55.0 * KEY_FFT / rate).toInt()
    val high = Math.ceil(3500.0 * KEY_FFT / rate).toInt().coerceAtMost(KEY_FFT / 2 - 1)
    val all = Array(frames) { DoubleArray(36) }
    val bass = Array(frames) { DoubleArray(36) }
    val loud = DoubleArray(frames)
    val peakBin = IntArray(high - low + 2)
    for (frame in 0 until frames) {
        val start = frame * KEY_HOP
        for (index in 0 until KEY_FFT) {
            re[index] = samples[start + index] * window[index]
            im[index] = 0.0
        }
        fft.transform(re, im)
        var power = 0.0
        for (k in mag.indices) {
            val p = re[k] * re[k] + im[k] * im[k]
            power += p
            mag[k] = sqrt(p)
        }
        loud[frame] = power
        var peaks = 0
        var strongest = 0.0
        for (k in low until high + 1) {
            if (k - 1 < 0 || k + 1 >= mag.size) continue
            if (mag[k] > mag[k - 1] && mag[k] >= mag[k + 1]) {
                peakBin[peaks++] = k
                if (mag[k] > strongest) strongest = mag[k]
            }
        }
        val floor = strongest * 0.02
        for (index in 0 until peaks) {
            val k = peakBin[index]
            if (mag[k] <= floor) continue
            val a = ln(mag[k - 1] + 1e-9)
            val b = ln(mag[k] + 1e-9)
            val c = ln(mag[k + 1] + 1e-9)
            val shift = ((a - c) / (2.0 * (a - 2.0 * b + c) - 1e-12)).coerceIn(-0.5, 0.5)
            val freq = (k + shift) * rate / KEY_FFT
            val position = (12.0 * log2(freq / 440.0) + 69.0) * 3.0
            val whole = Math.floor(position)
            val fraction = position - whole
            val bin = Math.floorMod(whole.toInt(), 36)
            val next = (bin + 1) % 36
            val weight = sqrt(mag[k])
            all[frame][bin] += weight * (1.0 - fraction)
            all[frame][next] += weight * fraction
            if (freq < 260.0) {
                bass[frame][bin] += weight * (1.0 - fraction)
                bass[frame][next] += weight * fraction
            }
        }
    }
    val sortedLoud = loud.sortedArray()
    val median = if (frames % 2 == 1) sortedLoud[frames / 2] else 0.5 * (sortedLoud[frames / 2 - 1] + sortedLoud[frames / 2])
    val keep = BooleanArray(frames) { loud[it] >= median }
    // Afinação: a fase (0, 1 ou 2 compartimentos) com mais energia, sobre os quadros fortes.
    val whole = DoubleArray(36)
    for (frame in 0 until frames) if (keep[frame]) for (i in 0 until 36) whole[i] += all[frame][i]
    val folded = DoubleArray(3)
    for (i in 0 until 36) folded[i % 3] += whole[i]
    var tuning = folded.indices.maxBy { folded[it] }
    if (tuning == 2) tuning = -1
    fun fold(source: DoubleArray): DoubleArray {
        val out = DoubleArray(12) { k -> (-1..1).sumOf { source[Math.floorMod(3 * k + tuning + it, 36)] } }
        val total = out.sum() + 1e-9
        return DoubleArray(12) { sqrt(out[it] / total) }
    }
    // Um seno puro não tem terça: sem terça, o nome é o da nota mais forte.
    val overall = fold(whole)
    val overallSquares = DoubleArray(12) { overall[it] * overall[it] }
    if (overallSquares.max() >= 0.5) {
        return MAJOR_NAMES[overallSquares.indices.maxBy { overallSquares[it] }] to overallSquares.max().coerceIn(0.0, 1.0)
    }
    val votes = DoubleArray(24)
    val probs = Array(KEY_SEGMENTS) { DoubleArray(24) }
    val edges = IntArray(KEY_SEGMENTS + 1) { (frames.toLong() * it / KEY_SEGMENTS).toInt() }
    for (segment in 0 until KEY_SEGMENTS) {
        val from = edges[segment]
        val to = edges[segment + 1]
        if (to <= from) continue
        var any = false
        for (frame in from until to) if (keep[frame]) any = true
        val sumAll = DoubleArray(36)
        val sumBass = DoubleArray(36)
        for (frame in from until to) {
            if (any && !keep[frame]) continue
            for (i in 0 until 36) {
                sumAll[i] += all[frame][i]
                sumBass[i] += bass[frame][i]
            }
        }
        val features = fold(sumAll) + fold(sumBass)
        val scores = DoubleArray(24)
        for (k in 0 until 12) {
            var major = KEY_B[0]
            var minor = KEY_B[1]
            for (i in 0 until 24) {
                val rotated = features[(i / 12) * 12 + (i % 12 + k) % 12]
                major += KEY_W[0][i] * rotated
                minor += KEY_W[1][i] * rotated
            }
            scores[k] = major
            scores[12 + k] = minor
        }
        val top = scores.max()
        var sum = 0.0
        for (i in 0 until 24) sum += exp(scores[i] - top)
        val logSum = top + ln(sum)
        for (i in 0 until 24) {
            val logProb = scores[i] - logSum
            votes[i] += logProb
            probs[segment][i] = exp(logProb)
        }
    }
    val winner = votes.indices.maxBy { votes[it] }
    val confidence = probs.map { it[winner] }.average().coerceIn(0.0, 1.0)
    return keyName(winner) to confidence
}
