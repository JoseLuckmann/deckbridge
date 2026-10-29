package os.deckbridge.manager.mix

import java.io.File
import java.util.Locale

/**
 * O que um DJ ouve numa faixa, compasso a compasso, presos à grade: onde tem grave, onde tem bateria, onde tem melodia
 * (médio e agudo fortes) e as frases (Intro, Up, Chorus, Down, Outro). É com isso que [MixFinder] procura onde sair, onde
 * entrar, onde virar o grave e quando segurar com loop. Medido do áudio (as mesmas medidas da análise de frases,
 * `barFeatures`), uma vez por faixa e guardado.
 */
class TrackProfile(
    /** Onde começa o compasso 0 (ms da faixa). */
    val originMs: Double,
    val barMs: Double,
    val lowDb: FloatArray,
    val midDb: FloatArray,
    val highDb: FloatArray,
    /** O quanto a batida pulsa no compasso (0–1): bumbo no grave ou chimbal e palmas no agudo. */
    val beat: FloatArray,
    val totalDb: FloatArray,
    /** O grave que fica (mediana do grave em janelas de 20 ms, dB): o baixo, sem o ataque do bumbo. */
    val bassDb: FloatArray,
    /** Frases: início, fim (compassos) e rótulo. */
    val phrases: List<Phrase>,
) {
    data class Phrase(val start: Int, val end: Int, val kind: String) {
        val bars get() = end - start
    }

    val bars get() = lowDb.size

    private fun ref(values: FloatArray): Float {
        val audible = values.indices.filter { totalDb[it] > SILENCE_DB }.map { values[it] }.sorted()
        if (audible.isEmpty()) return -60f
        // O patamar: o nível das partes cheias (o 85º percentil), não o pico de um compasso.
        return audible[(audible.size * 85 / 100).coerceAtMost(audible.lastIndex)]
    }

    private val bassRef by lazy { ref(bassDb) }
    private val midRef by lazy { ref(midDb) }
    private val highRef by lazy { ref(highDb) }
    private val totalRef by lazy { ref(totalDb) }

    private fun ok(bar: Int) = bar in 0 until bars && totalDb[bar] > SILENCE_DB

    /**
     * Tem baixo neste compasso: o grave que fica entre os bumbos no patamar das partes cheias (até 8 dB abaixo). Um bumbo
     * sozinho tem grave forte no ataque, mas entre um e outro o grave cai: não conta como baixo.
     */
    fun bass(bar: Int) = ok(bar) && bassRef > -50f && bassDb[bar] >= bassRef - 8f

    /** Tem bateria (bumbo ou chimbal marcando) neste compasso: dá para casar a batida de ouvido. */
    fun drums(bar: Int) = ok(bar) && beat[bar] >= 0.35f

    /**
     * Tem melodia ou voz forte (médio perto do patamar e agudo presente): duas assim juntas, em tons longe, brigam. O
     * médio das partes cheias leva também o vazamento do baixo e do bumbo, por isso a folga de 6 dB.
     */
    fun melodic(bar: Int) = ok(bar) && midDb[bar] >= midRef - 6f && highDb[bar] >= highRef - 8f

    /** Só bateria (sem grave e sem melodia forte): o trecho que um DJ procura para mixar longo. */
    fun beatsOnly(bar: Int) = drums(bar) && !bass(bar) && !melodic(bar)

    /** Energia do compasso de 0 a 1 contra o patamar da faixa (o nível total). */
    fun energy(bar: Int): Float {
        if (!ok(bar)) return 0f
        return ((totalDb[bar] - (totalRef - 18f)) / 18f).coerceIn(0f, 1f)
    }

    fun phraseAt(bar: Int): Phrase? = phrases.firstOrNull { bar >= it.start && bar < it.end }

    fun msOf(bar: Int): Int = (originMs + bar * barMs).toInt()

    fun barAt(ms: Int): Int = Math.floor((ms - originMs) / barMs).toInt()

    /** Texto do arquivo de cache (uma linha por campo). */
    fun encode(): String = buildString {
        fun arr(a: FloatArray) = a.joinToString(",") { "%.2f".format(Locale.ROOT, it) }
        appendLine("v=$VERSION")
        appendLine("origin=%.3f".format(Locale.ROOT, originMs))
        appendLine("bar=%.4f".format(Locale.ROOT, barMs))
        appendLine("low=" + arr(lowDb))
        appendLine("mid=" + arr(midDb))
        appendLine("high=" + arr(highDb))
        appendLine("beat=" + arr(beat))
        appendLine("total=" + arr(totalDb))
        appendLine("bass=" + arr(bassDb))
        appendLine("phrases=" + phrases.joinToString(";") { "${it.start}:${it.end}:${it.kind}" })
    }

    companion object {
        /** Mude ao mudar as medidas: os perfis guardados com outra versão são refeitos. */
        const val VERSION = 1
        const val SILENCE_DB = -55f

        /** Mede o perfil de uma faixa (mono, qualquer taxa) com a grade dela: [bpm] e onde cai a primeira batida 1. */
        fun of(samples: FloatArray, rate: Int, bpm: Double, firstDownbeatMs: Double): TrackProfile? {
            val f = os.deckbridge.logic.barFeatures(samples, rate, bpm, firstDownbeatMs / 1000.0) ?: return null
            if (f.bars < 2) return null
            val phrases = os.deckbridge.logic.phraseBars(f).map { Phrase(it.first, it.second, it.third) }
            fun fl(d: DoubleArray) = FloatArray(d.size) { d[it].toFloat() }
            val (beat, bass) = pulses(samples, rate, f.originSec, f.barSec, f.bars)
            return TrackProfile(f.originSec * 1000, f.barSec * 1000, fl(f.lowDb), fl(f.midDb), fl(f.highDb), beat, fl(f.totalDb), bass, phrases)
        }

        /**
         * O pulso da batida por compasso, em duas faixas: o grave (bumbo) e o agudo (chimbal, palmas). O pulso é o pico do
         * envelope de 5 ms contra a mediana do compasso. O do grave olha o pico mais alto (98%): o bumbo é curto e, num drop
         * com baixo contínuo, a mediana já é o baixo. Um som contínuo (baixo, acorde) quase não pulsa. Junto, o grave que
         * fica: a mediana do grave em janelas de 20 ms (mais que um ciclo de 50 Hz), que é o baixo e não o bumbo.
         */
        private fun pulses(samples: FloatArray, rate: Int, originSec: Double, barSec: Double, bars: Int): Pair<FloatArray, FloatArray> {
            val hop = maxOf(1, rate / 200)
            val frames = samples.size / hop + 1
            val low = FloatArray(frames)
            val high = FloatArray(frames)
            val aLow = Math.exp(-2.0 * Math.PI * 150.0 / rate).toFloat()
            val aHigh = Math.exp(-2.0 * Math.PI * 3000.0 / rate).toFloat()
            var l1 = 0f; var l2 = 0f; var h1 = 0f; var h2 = 0f
            var accL = 0f; var accH = 0f
            for (i in samples.indices) {
                val x = samples[i]
                l1 += (1f - aLow) * (x - l1); l2 += (1f - aLow) * (l1 - l2)
                h1 += (1f - aHigh) * (x - h1); h2 += (1f - aHigh) * (h1 - h2)
                val hb = x - h2
                accL += l2 * l2; accH += hb * hb
                if (i % hop == hop - 1) { low[i / hop] = accL / hop; high[i / hop] = accH / hop; accL = 0f; accH = 0f }
            }
            val envRate = rate.toDouble() / hop
            fun pulse(env: FloatArray, from: Int, to: Int, pct: Int): Double {
                val slice = env.copyOfRange(from, to).sorted()
                val median = slice[slice.size / 2].toDouble()
                val peak = slice[(slice.size * pct) / 100].toDouble()
                if (peak < 1e-7) return 0.0
                return 10.0 * Math.log10(maxOf(peak, 1e-12) / maxOf(median, 1e-12))
            }
            val beat = FloatArray(bars) { bar ->
                val from = ((originSec + bar * barSec) * envRate).toInt().coerceIn(0, frames)
                val to = ((originSec + (bar + 1) * barSec) * envRate).toInt().coerceIn(from, frames)
                if (to - from < 16) 0f
                else maxOf((pulse(low, from, to, 98) - 4.0) / 8.0, (pulse(high, from, to, 95) - 4.0) / 8.0).coerceIn(0.0, 1.0).toFloat()
            }
            val bass = FloatArray(bars) { bar ->
                val from = ((originSec + bar * barSec) * envRate).toInt().coerceIn(0, frames)
                val to = ((originSec + (bar + 1) * barSec) * envRate).toInt().coerceIn(from, frames)
                // Janelas de 20 ms (4 de 5 ms).
                val w = (from until to - 3 step 4).map { (low[it] + low[it + 1] + low[it + 2] + low[it + 3]) / 4f }.sorted()
                if (w.isEmpty()) -120f else (10.0 * Math.log10(maxOf(w[w.size / 2].toDouble(), 1e-12))).toFloat()
            }
            return beat to bass
        }

        fun decode(text: String): TrackProfile? = runCatching {
            val m = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (m["v"]?.toIntOrNull() != VERSION) return null
            fun arr(k: String) = m.getValue(k).split(',').filter { it.isNotBlank() }.map { it.toFloat() }.toFloatArray()
            val phrases = m["phrases"].orEmpty().split(';').filter { it.isNotBlank() }.map { p ->
                val (s, e, k) = p.split(':', limit = 3)
                Phrase(s.toInt(), e.toInt(), k)
            }
            TrackProfile(m.getValue("origin").toDouble(), m.getValue("bar").toDouble(), arr("low"), arr("mid"), arr("high"), arr("beat"), arr("total"), arr("bass"), phrases)
        }.getOrNull()

        fun load(file: File): TrackProfile? = if (file.isFile) decode(file.readText()) else null

        fun save(file: File, p: TrackProfile) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".novo")
            tmp.writeText(p.encode())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
