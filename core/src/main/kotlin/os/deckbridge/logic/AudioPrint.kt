package os.deckbridge.logic

import kotlin.math.ln
import kotlin.math.max

/**
 * Impressão digital de áudio para achar a mesma gravação com outro nome, outro formato ou outra taxa de bits, no estilo
 * de Haitsma e Kalker (2002, "A Highly Robust Audio Fingerprinting System", ISMIR): energia em 33 faixas logarítmicas de
 * 300 a 2000 Hz por quadro, e cada um dos 32 bits do quadro é o sinal da diferença de energia entre faixas vizinhas
 * menos a mesma diferença no quadro anterior. Codificar em MP3 de outra taxa, mudar o volume ou cortar silêncio no
 * começo quase não muda os bits; outra música muda metade deles.
 *
 * Guarda só um trecho de [SPAN_SEC] segundos a partir de 30% da faixa (poucos KB por faixa), com quadros de ~186 ms a
 * cada ~11,6 ms (a 22,05 kHz, o passo de Haitsma e Kalker: o desalinhamento entre duas cópias fica pequeno).
 */
class AudioPrint(
    val bits: IntArray,
    val durationSec: Int,
    /** Quais bits são confiáveis: a diferença não é miúda e as faixas não estão no silêncio do quadro. */
    val mask: IntArray = IntArray(bits.size) { -1 },
) {
    fun encode(): String = "v=$VERSION\nd=$durationSec\nb=" + bits.joinToString(",") { Integer.toHexString(it) } +
        "\nm=" + mask.joinToString(",") { Integer.toHexString(it) }

    companion object {
        const val VERSION = 2
        const val SPAN_SEC = 60.0
        private const val RATE = 22050
        private const val FRAME = 4096
        private const val HOP = 256
        private const val BANDS = 33

        /** Quadros por segundo da impressão. */
        val perSecond get() = RATE.toDouble() / HOP

        fun decode(text: String): AudioPrint? = runCatching {
            val m = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (m["v"]?.toIntOrNull() != VERSION) return null
            fun ints(k: String) = m.getValue(k).split(',').filter { it.isNotBlank() }.map { java.lang.Long.parseLong(it, 16).toInt() }.toIntArray()
            AudioPrint(ints("b"), m.getValue("d").toInt(), ints("m"))
        }.getOrNull()

        /** Impressão do áudio mono [samples] a [rate] Hz (qualquer taxa: é levado a 22,05 kHz). */
        fun of(samples: FloatArray, rate: Int): AudioPrint? {
            if (samples.isEmpty() || rate <= 0) return null
            val duration = samples.size.toDouble() / rate
            val from = (duration * 0.3).let { if (duration - it < SPAN_SEC) max(0.0, duration - SPAN_SEC) else it }
            val len = minOf(SPAN_SEC, duration - from)
            // Reamostra o trecho para 22,05 kHz (linear: só importa até 2 kHz).
            val n = (len * RATE).toInt()
            if (n < FRAME * 2) return null
            val x = FloatArray(n) { i ->
                val pos = (from + i.toDouble() / RATE) * rate
                val k = pos.toInt().coerceIn(0, samples.size - 2)
                val f = (pos - k).toFloat()
                samples[k] * (1 - f) + samples[k + 1] * f
            }
            val fft = Fft(FRAME)
            val window = DoubleArray(FRAME) { 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / (FRAME - 1)) }
            val edges = IntArray(BANDS + 1) { b ->
                val hz = 300.0 * Math.exp(ln(2000.0 / 300.0) * b / BANDS)
                (hz * FRAME / RATE).toInt()
            }
            val frames = (n - FRAME) / HOP + 1
            val re = DoubleArray(FRAME)
            val im = DoubleArray(FRAME)
            var prev: DoubleArray? = null
            val out = IntArray(frames)
            val masks = IntArray(frames)
            for (f in 0 until frames) {
                val at = f * HOP
                for (i in 0 until FRAME) { re[i] = x[at + i] * window[i]; im[i] = 0.0 }
                fft.transform(re, im)
                val e = DoubleArray(BANDS) { b ->
                    var s = 0.0
                    for (k in edges[b] until max(edges[b] + 1, edges[b + 1])) s += re[k] * re[k] + im[k] * im[k]
                    s
                }
                var word = 0
                var sure = 0
                val p = prev
                if (p != null) {
                    // Faixas no silêncio do quadro (30 dB abaixo da mais forte) ou com diferença miúda viram ruído com
                    // qualquer codificação: esses bits não contam na comparação (a "confiabilidade" de Haitsma e Kalker).
                    // Dos que passam do silêncio, a metade com a maior diferença (relativa) conta.
                    val floor = 1e-3 * maxOf(e.max(), p.max())
                    val r = DoubleArray(32)
                    for (b in 0 until 32) {
                        val d = (e[b] - e[b + 1]) - (p[b] - p[b + 1])
                        if (d > 0) word = word or (1 shl b)
                        val sum = e[b] + e[b + 1] + p[b] + p[b + 1]
                        r[b] = if (sum > floor) kotlin.math.abs(d) / sum else 0.0
                    }
                    val alive = r.filter { it > 0 }.sorted()
                    if (alive.isNotEmpty()) {
                        val cut = alive[alive.size / 2]
                        for (b in 0 until 32) if (r[b] > 0 && r[b] >= cut) sure = sure or (1 shl b)
                    }
                }
                out[f] = word
                masks[f] = sure
                prev = e
            }
            return AudioPrint(out.copyOfRange(1, out.size), duration.toInt(), masks.copyOfRange(1, masks.size))
        }

        /**
         * A menor taxa de bits diferentes (0 igual, ~0,5 sem relação) entre as duas impressões, procurando o deslocamento
         * em até ±[maxShiftSec] segundos: um bloco de 20 s do meio de [a] contra todos os lugares de [b].
         */
        fun distance(a: AudioPrint, b: AudioPrint, maxShiftSec: Double = 12.0): Double {
            val block = minOf((20 * perSecond).toInt(), a.bits.size / 2, b.bits.size / 2)
            if (block < 40) return 1.0
            val aFrom = (a.bits.size - block) / 2
            val maxShift = (maxShiftSec * perSecond).toInt()
            val center = (b.bits.size - block) / 2
            var best = 1.0
            for (bFrom in maxOf(0, center - maxShift)..minOf(b.bits.size - block, center + maxShift)) {
                var diff = 0
                var count = 0
                for (i in 0 until block) {
                    val both = a.mask[aFrom + i] and b.mask[bFrom + i]
                    diff += Integer.bitCount((a.bits[aFrom + i] xor b.bits[bFrom + i]) and both)
                    count += Integer.bitCount(both)
                }
                // Poucos bits confiáveis em comum (trecho quase parado): não dá para dizer.
                if (count < 2 * block) continue
                val ber = diff.toDouble() / count
                if (ber < best) best = ber
            }
            return best
        }

        /**
         * Abaixo disto de bits diferentes (dos confiáveis) é a mesma gravação. Haitsma e Kalker usam 0,35 com todos os
         * bits; aqui 0,25, mais longe do que duas faixas de batida parecida no mesmo andamento dão (~0,4 nos testes). A
         * mesma gravação recodificada, com silêncio na frente e outro volume, dá ~0,06.
         */
        const val SAME = 0.25
    }
}
