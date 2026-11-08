package os.deckbridge.manager.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Master Tempo offline: muda o andamento sem mudar o tom, por WSOLA (sobreposição e soma com busca de correlação), o
 * mesmo método do key lock do motor do player (`Wsola` em `android/app/src/main/cpp/dsp.h`), aqui sobre um trecho
 * inteiro em memória. Grãos de 2048 amostras com salto de saída de 1024 (Hann periódica: soma 1 com 50% de
 * sobreposição); cada grão começa, a até ±5 ms da posição nominal, onde melhor continua o anterior.
 */
object Stretch {
    private const val HOP = 1024
    private const val WIN = 2 * HOP
    private const val SEARCH = 220
    private val window = FloatArray(WIN) { (0.5 - 0.5 * cos(2 * PI * it / WIN)).toFloat() }

    /** [speed] > 1 acelera (sai mais curto), < 1 desacelera. O tom fica. */
    fun tempo(s: Stereo, speed: Double): Stereo {
        if (abs(speed - 1.0) < 1e-4 || s.frames < WIN) return s
        val n = s.frames
        val outN = (n / speed).toInt()
        val mono = FloatArray(n) { 0.5f * (s.left[it] + s.right[it]) }
        val outL = FloatArray(outN + WIN)
        val outR = FloatArray(outN + WIN)
        var prev = -1
        var k = 0
        while (k * HOP < outN) {
            val nominal = (k.toLong() * HOP * speed).toInt()
            var start = nominal
            if (prev >= 0) {
                val natural = prev + HOP
                fun score(d: Int): Double {
                    var dot = 0.0
                    var energy = 1e-9
                    var j = 0
                    while (j < HOP) {
                        val a = natural + j
                        val b = nominal + d + j
                        if (a in 0 until n && b in 0 until n) {
                            val c = mono[b]
                            dot += mono[a] * c
                            energy += c * c
                        }
                        j += 2
                    }
                    // Leve preferência pelo centro: no silêncio, não vaga pela janela.
                    return dot / sqrt(energy) * (1.0 - 0.0004 * abs(d))
                }
                var best = Double.NEGATIVE_INFINITY
                var delta = 0
                var d = -SEARCH
                while (d <= SEARCH) {
                    val v = score(d)
                    if (v > best) { best = v; delta = d }
                    d += 4
                }
                val center = delta
                for (e in maxOf(-SEARCH, center - 3)..minOf(SEARCH, center + 3)) {
                    val v = score(e)
                    if (v > best) { best = v; delta = e }
                }
                start = nominal + delta
            }
            start = start.coerceIn(0, n - 1)
            val at = k * HOP
            for (i in 0 until WIN) {
                val idx = start + i
                if (idx >= n) break
                // Primeiro grão sem a subida da janela, para não amaciar o primeiro ataque.
                val g = if (prev < 0 && i < HOP) 1f else window[i]
                outL[at + i] += s.left[idx] * g
                outR[at + i] += s.right[idx] * g
            }
            prev = start
            k++
        }
        return Stereo(outL.copyOf(outN), outR.copyOf(outN), s.rate)
    }
}
