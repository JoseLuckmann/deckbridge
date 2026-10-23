package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Andamento por envelope de início multibanda (40 bandas mel, fluxo logarítmico) e autocorrelação com pente de
 * harmônicos de batida. O prior é a distribuição de BPM do treino do corpus GiantSteps (Beatport, EDM), suavizada em
 * 0,04 oitava e com piso de 0,2: dá massa a 140 (dubstep) e 174 (drum and bass), que um prior único em 124 cortava.
 * É dado de gênero deste repositório de teste, não uma lei: música fora do EDM pode precisar de outra tabela.
 * Tabela de 70 a 190 BPM, passo de 1 BPM (docs/testing/tempo-corpus.md).
 */
private val TEMPO_PRIOR = doubleArrayOf(
    0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200,
    0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200,
    0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200,
    0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200,
    0.200, 0.200, 0.200, 0.205, 0.254, 0.311, 0.375, 0.444, 0.517, 0.590, 0.658,
    0.712, 0.744, 0.747, 0.721, 0.674, 0.618, 0.570, 0.545, 0.555, 0.603, 0.683,
    0.782, 0.880, 0.958, 1.000, 0.998, 0.954, 0.877, 0.780, 0.677, 0.579, 0.491,
    0.416, 0.352, 0.298, 0.251, 0.211, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200,
    0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.215, 0.262, 0.321, 0.388, 0.462,
    0.536, 0.605, 0.662, 0.701, 0.717, 0.710, 0.679, 0.629, 0.563, 0.488, 0.411,
    0.336, 0.269, 0.210, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200, 0.200,
)

internal fun tempoPrior(bpm: Double): Double {
    val x = bpm - 70.0
    if (x <= 0.0 || x >= 120.0) return 0.2
    val index = x.toInt()
    val fraction = x - index
    return TEMPO_PRIOR[index] * (1.0 - fraction) + TEMPO_PRIOR[index + 1] * fraction
}

internal class OnsetEnvelope(val values: FloatArray, val rate: Double)

internal class Fft(val size: Int) {
    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { -sin(2.0 * PI * it / size) }
    private val reverse = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (index in 0 until size) table[index] = Integer.reverse(index) ushr (32 - bits)
    }

    /** Transformada no lugar (entrada em re/im, o im de entrada é zero). */
    fun transform(re: DoubleArray, im: DoubleArray) {
        for (index in 0 until size) {
            val other = reverse[index]
            if (other > index) {
                val tr = re[index]; re[index] = re[other]; re[other] = tr
                val ti = im[index]; im[index] = im[other]; im[other] = ti
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += half * 2
            }
            half *= 2
        }
    }
}

private fun melFilters(rate: Int, fftSize: Int, bands: Int, fmin: Double, fmax: Double): Array<DoubleArray> {
    fun hzToMel(f: Double) = 2595.0 * kotlin.math.log10(1.0 + f / 700.0)
    fun melToHz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)
    val points = DoubleArray(bands + 2) { melToHz(hzToMel(fmin) + (hzToMel(fmax) - hzToMel(fmin)) * it / (bands + 1)) }
    val bins = fftSize / 2 + 1
    return Array(bands) { band ->
        val left = points[band]
        val centre = points[band + 1]
        val right = points[band + 2]
        DoubleArray(bins) { k ->
            val freq = k.toDouble() * rate / fftSize
            max(0.0, min((freq - left) / (centre - left), (right - freq) / (right - centre)))
        }
    }
}

/** Fluxo logarítmico multibanda somado, sem a média local de 0,5 s (só o que passa da média conta). */
internal fun onsetEnvelope(samples: FloatArray, rate: Int): OnsetEnvelope? {
    if (samples.size < rate * 4 || rate < 8000) return null
    val fftSize = if (rate <= 32000) 1024 else 2048
    val hop = (rate / 86.1328125).roundToInt().coerceAtLeast(64)
    val frames = (samples.size - fftSize) / hop + 1
    if (frames < 64) return null
    val bands = 40
    val filters = melFilters(rate, fftSize, bands, 30.0, min(10000.0, rate * 0.45))
    val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1)) }
    val fft = Fft(fftSize)
    val re = DoubleArray(fftSize)
    val im = DoubleArray(fftSize)
    val power = DoubleArray(fftSize / 2 + 1)
    val energy = Array(frames) { DoubleArray(bands) }
    var total = 0.0
    for (frame in 0 until frames) {
        val start = frame * hop
        for (index in 0 until fftSize) {
            re[index] = samples[start + index] * window[index]
            im[index] = 0.0
        }
        fft.transform(re, im)
        for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
        for (band in 0 until bands) {
            var sum = 0.0
            val weights = filters[band]
            for (k in power.indices) sum += power[k] * weights[k]
            energy[frame][band] = sum
            total += sum
        }
    }
    val mean = total / (frames * bands) + 1e-9
    val raw = FloatArray(frames - 1)
    var previous = DoubleArray(bands) { ln1p(1000.0 * energy[0][it] / mean) }
    for (frame in 1 until frames) {
        var flux = 0.0
        val current = DoubleArray(bands)
        for (band in 0 until bands) {
            current[band] = ln1p(1000.0 * energy[frame][band] / mean)
            val rise = current[band] - previous[band]
            if (rise > 0.0) flux += rise
        }
        raw[frame - 1] = flux.toFloat()
        previous = current
    }
    val rateEnv = rate.toDouble() / hop
    // Média local de 0,5 s (janela centrada, zeros fora): o resto, retificado, é o envelope.
    val k = max(1, (rateEnv * 0.5).toInt())
    val prefix = DoubleArray(raw.size + 1)
    for (index in raw.indices) prefix[index + 1] = prefix[index] + raw[index]
    val out = FloatArray(raw.size)
    val shift = (k - 1) / 2
    for (index in raw.indices) {
        val hi = min(raw.size - 1, index + shift)
        val lo = max(0, index + shift - k + 1)
        val mean0 = (prefix[hi + 1] - prefix[lo]) / k
        out[index] = max(0.0, raw[index] - mean0).toFloat()
    }
    return OnsetEnvelope(out, rateEnv)
}

private fun envelopeAcf(values: FloatArray, maxLag: Int): DoubleArray {
    var mean = 0.0
    for (v in values) mean += v
    mean /= values.size
    val centred = DoubleArray(values.size) { values[it] - mean }
    val out = DoubleArray(maxLag + 1)
    for (lag in 0..maxLag) {
        var acc = 0.0
        for (index in 0 until values.size - lag) acc += centred[index] * centred[index + lag]
        out[lag] = acc
    }
    val zero = out[0] + 1e-9
    for (lag in out.indices) out[lag] /= zero
    return out
}

private val HARMONICS = intArrayOf(1, 2, 3, 4)
private val HARMONIC_WEIGHTS = doubleArrayOf(1.0, 0.6, 0.4, 0.3)
private val SUBDIVISIONS = listOf(0.5 to 1.5, 0.25 to 0.5)

private fun combScore(acf: DoubleArray, rateEnv: Double, bpm: Double): Double {
    val lag = rateEnv * 60.0 / bpm
    var sum = 0.0
    for (index in HARMONICS.indices) {
        val x = lag * HARMONICS[index]
        val j = x.toInt()
        if (j + 1 >= acf.size) continue
        sum += HARMONIC_WEIGHTS[index] * (acf[j] * (1.0 - (x - j)) + acf[j + 1] * (x - j))
    }
    // Meia batida e um quarto de batida: um andamento verdadeiro tem energia nas colcheias e nas semicolcheias; um
    // 4/3 ou 3/4 falso cai fora da grade rítmica. Pesos escolhidos no treino (1,5 e 0,5).
    for ((fraction, weight) in SUBDIVISIONS) {
        val x = lag * fraction
        val j = x.toInt()
        if (j + 1 >= acf.size || j < 1) continue
        sum += weight * (acf[j] * (1.0 - (x - j)) + acf[j + 1] * (x - j))
    }
    return sum
}

/** BPM pelo envelope multibanda: pente de 60 a 200 BPM (passo de 0,5) vezes o prior, depois refino de ±1 BPM a 0,05. */
internal fun onsetTempo(samples: FloatArray, rate: Int): Double {
    val envelope = onsetEnvelope(samples, rate) ?: return 0.0
    val maxLag = (envelope.rate * 60.0 / 50.0 * 4.0).toInt() + 4
    if (envelope.values.size < maxLag * 2) return 0.0
    val acf = envelopeAcf(envelope.values, maxLag)
    var best = 0.0
    var bestScore = -1.0
    var bpm = 60.0
    while (bpm <= 200.0001) {
        val score = combScore(acf, envelope.rate, bpm) * tempoPrior(bpm)
        if (score > bestScore) {
            bestScore = score
            best = bpm
        }
        bpm += 0.5
    }
    var refined = best
    var refinedScore = -1.0
    var candidate = best - 1.0
    while (candidate <= best + 1.0001) {
        val score = combScore(acf, envelope.rate, candidate)
        if (score > refinedScore) {
            refinedScore = score
            refined = candidate
        }
        candidate += 0.05
    }
    return refined
}
