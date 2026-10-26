package os.deckbridge.manager.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import os.deckbridge.logic.DecodedPcm

/** Áudio estéreo em memória. */
class Stereo(val left: FloatArray, val right: FloatArray, val rate: Int) {
    val frames get() = min(left.size, right.size)
    val seconds get() = frames.toDouble() / rate
}

/** Lê um trecho de uma faixa: início e duração em ms do tempo da própria faixa. Null se não deu para ler. */
fun interface SegmentReader {
    fun read(startMs: Int, lengthMs: Int): DecodedPcm?
}

/**
 * Níveis de cada canal num instante da transição, como se fossem os controles do mixer: volume (fader), grave (LOW) e o
 * resto (MID+HI) de cada deck, e se o eco está ligado. A prévia é montada com eles e a tela de teste mostra os mesmos.
 */
class Levels {
    var aVol = 1f; var aLow = 1f; var aHigh = 1f
    var bVol = 0f; var bLow = 1f; var bHigh = 1f
    var echo = false
}

/**
 * Uma prévia montada e o que a tela precisa para desenhá-la como dois decks: o tempo de cada faixa em cada instante, as
 * ondas coloridas (já na base de tempo da prévia, a que entra esticada para o andamento da que sai) e as batidas.
 */
class MixPreview(
    val audio: Stereo,
    val transition: Transition,
    val leadMs: Int,
    val overlapMs: Int,
    val aStartMs: Int,
    /** Velocidade da que entra: o master ÷ o andamento dela. */
    val speed: Double,
    val barMsA: Double,
    /** O master da troca (as duas tocam nele) e a velocidade da que sai até ele. */
    val tempo: Double,
    val speedA: Double,
    /**
     * Ajuste fino aplicado à que entra, em ms da prévia (positivo: ela estava atrasada e foi adiantada), medido pelos
     * ataques graves das duas no trecho junto. 0 se desligado ou se não deu para medir com segurança.
     */
    val phaseMs: Double,
    /** O atraso medido entre os bumbos, mesmo quando o ajuste está desligado (null: não deu para medir). */
    val measuredMs: Double?,
    val waveA: os.deckbridge.logic.ColorWave?,
    val waveB: os.deckbridge.logic.ColorWave?,
) {
    val lengthMs get() = (audio.frames * 1000L / audio.rate).toInt()
    /** Onde está a faixa que sai (ms dela) no instante [ms] da prévia. */
    fun aAt(ms: Int) = aStartMs + (ms * speedA).toInt()
    /** Onde está a que entra (ms dela) no instante [ms] da prévia (negativo: ainda não começou). */
    fun bAt(ms: Int) = transition.inMs + ((ms - leadMs + phaseMs) * speed).toInt()
    /** Instante da prévia em que a faixa que sai passa por [trackMs]. */
    fun fromA(trackMs: Int) = ((trackMs - aStartMs) / speedA).toInt()
    fun fromB(trackMs: Int) = leadMs + ((trackMs - transition.inMs) / speed - phaseMs).toInt()
    fun levelsAt(ms: Int, into: Levels = Levels()) = MixRenderer.levels(transition.style, (ms - leadMs).toDouble(), overlapMs.toDouble(), barMsA, into)
}

/**
 * Monta a prévia de uma transição, offline: [leadMs] da faixa que sai antes do ponto de saída, os compassos juntos no
 * estilo escolhido e [tailMs] da que entra depois. A que entra toca no andamento da que sai, com o ponto de entrada
 * caindo exatamente no ponto de saída, batida com batida. Com Master Tempo (padrão) o tom fica; sem, muda junto, como
 * um toca-discos.
 */
object MixRenderer {
    const val RATE = 44100

    /** Os níveis dos dois canais a [relMs] do ponto de saída (negativo: antes), conforme o estilo. */
    fun levels(style: MixStyle, relMs: Double, overlapMs: Double, barMs: Double, out: Levels): Levels {
        val x = if (overlapMs <= 0) (if (relMs >= 0) 1.0 else 0.0) else (relMs / overlapMs).coerceIn(0.0, 1.0)
        out.aLow = 1f; out.aHigh = 1f; out.bLow = 1f; out.bHigh = 1f; out.echo = false
        if (relMs < 0) {
            out.aVol = 1f; out.bVol = 0f
            if (style == MixStyle.BASS_SWAP) out.bLow = 0f
            return out
        }
        when (style) {
            MixStyle.BLEND -> {
                out.aVol = cos(x * PI / 2).toFloat()
                out.bVol = sin(x * PI / 2).toFloat()
            }
            MixStyle.BASS_SWAP -> {
                // Agudos e médios da que entra sobem na primeira metade; no meio, os graves trocam de uma para outra;
                // a que sai desce no último quarto.
                val swap = x >= 0.5
                out.aLow = if (swap) 0f else 1f
                out.aVol = if (relMs >= overlapMs) 0f else if (x < 0.75) 1f else cos((x - 0.75) / 0.25 * PI / 2).toFloat()
                out.bLow = if (swap) 1f else 0f
                out.bHigh = min(1.0, x * 2).toFloat()
                out.bVol = 1f
            }
            MixStyle.CUT -> {
                // Troca seca no início do compasso, com 10 ms para não estalar.
                val g = ((relMs + 10) / 10).coerceIn(0.0, 1.0).toFloat()
                out.aVol = 1 - g
                out.bVol = g
            }
            MixStyle.ECHO -> {
                // A que sai para um compasso depois do ponto de saída e fica só o eco (uma batida, realimentado).
                out.aVol = if (relMs < barMs) 1f else 0f
                out.echo = true
                out.bVol = (x * 4).coerceAtMost(1.0).toFloat()
            }
        }
        return out
    }

    fun transition(
        a: SegmentReader, bpmA: Double, b: SegmentReader, bpmB: Double, t: Transition, leadMs: Int = 12_000, tailMs: Int = 12_000, waves: Boolean = false,
        /** O master da troca: as duas faixas tocam nele (padrão: o BPM da que sai, que então não muda). */
        tempo: Double = bpmA,
    ): MixPreview? {
        val master = if (tempo > 0) tempo else bpmA
        val barMs = SetPlanner.barMs(master)
        val overlapMs = (t.bars * barMs).toInt().coerceAtLeast(0)
        val speedA = if (bpmA > 0 && master > 0) master / bpmA else 1.0
        // [lead] e o resto em tempo da prévia; a faixa que sai anda [speedA] vezes isso no tempo dela.
        val lead = min(leadMs, (t.outMs / speedA).toInt())
        val aStart = t.outMs - (lead * speedA).toInt()
        val aLen = ((lead + overlapMs + if (t.style == MixStyle.ECHO) (barMs * 2).toInt() else 0) * speedA).toInt() + 300
        val pa = a.read(aStart, aLen) ?: return null
        val speed = if (master > 0 && bpmB > 0) master / bpmB else 1.0
        val bOutMs = overlapMs + tailMs
        val pb = b.read(t.inMs, (bOutMs * speed).toInt() + 300) ?: return null
        val sa = if (t.masterTempo) Stretch.tempo(resample(pa, 1.0), speedA) else resample(pa, speedA)
        val sb = if (t.masterTempo) Stretch.tempo(resample(pb, 1.0), speed) else resample(pb, speed)
        val leadFrames = ms(lead)
        val total = leadFrames + ms(bOutMs)
        // Sync fino: as grades alinham a batida, mas cada análise põe a grade a uma distância um pouco diferente do
        // ataque do bumbo; o que sobra (10–30 ms) embola o grave. Mede pelos ataques graves e corrige, como no jog.
        val measured = phaseLag(sa, sb, leadFrames, ms(overlapMs), ms((2 * barMs).toInt()))
        val shift = if (t.phaseLock && measured != null) (measured * RATE / 1000).toInt() else 0
        val outL = FloatArray(total)
        val outR = FloatArray(total)
        val lowA = LowPass(); val lowAR = LowPass(); val lowB = LowPass(); val lowBR = LowPass()
        val beatFrames = ms((barMs / 4).toInt())
        val echoL = FloatArray(max(1, beatFrames))
        val echoR = FloatArray(max(1, beatFrames))
        var echoAt = 0
        val lv = Levels()
        for (i in 0 until total) {
            val inA = i < sa.frames
            val al = if (inA) sa.left[i] else 0f
            val ar = if (inA) sa.right[i] else 0f
            val j = i - leadFrames
            val jb = j + shift
            val inB = j >= 0 && jb >= 0 && jb < sb.frames
            val bl = if (inB) sb.left[jb] else 0f
            val br = if (inB) sb.right[jb] else 0f
            levels(t.style, j * 1000.0 / RATE, overlapMs.toDouble(), barMs, lv)
            val la = lowA.step(al); val ra = lowAR.step(ar)
            val lb = lowB.step(bl); val rb = lowBR.step(br)
            var oal = (la * lv.aLow + (al - la) * lv.aHigh) * lv.aVol
            var oar = (ra * lv.aLow + (ar - ra) * lv.aHigh) * lv.aVol
            if (lv.echo) {
                val el = echoL[echoAt]; val er = echoR[echoAt]
                echoL[echoAt] = oal * 0.5f + el * 0.55f
                echoR[echoAt] = oar * 0.5f + er * 0.55f
                echoAt = (echoAt + 1) % echoL.size
                if (j >= 0) { oal += el; oar += er }
            }
            val obl = (lb * lv.bLow + (bl - lb) * lv.bHigh) * lv.bVol
            val obr = (rb * lv.bLow + (br - rb) * lv.bHigh) * lv.bVol
            outL[i] = soft(oal + obl)
            outR[i] = soft(oar + obr)
        }
        var waveA: os.deckbridge.logic.ColorWave? = null
        var waveB: os.deckbridge.logic.ColorWave? = null
        if (waves) {
            // Cada deck com a própria onda, inteira (independente do fader), na base de tempo da prévia.
            val monoA = FloatArray(total) { if (it < sa.frames) 0.5f * (sa.left[it] + sa.right[it]) else 0f }
            val monoB = FloatArray(total) { val k = it - leadFrames + shift; if (it >= leadFrames && k >= 0 && k < sb.frames) 0.5f * (sb.left[k] + sb.right[k]) else 0f }
            waveA = os.deckbridge.logic.wavesFromPcm(monoA, RATE).rgbDetail
            waveB = os.deckbridge.logic.wavesFromPcm(monoB, RATE).rgbDetail
        }
        return MixPreview(Stereo(outL, outR, RATE), t, lead, overlapMs, aStart, speed, barMs, master, speedA, shift * 1000.0 / RATE, measured, waveA, waveB)
    }

    private const val HOP = 49 // 1,111 ms exatos a 44,1 kHz
    private const val MAX_LAG_MS = 60.0

    /** Força de ataque do grave (< ~150 Hz), uma entrada por [HOP] amostras, de [from] a [from] + [count]. */
    private fun lowOnset(s: Stereo, from: Int, count: Int): DoubleArray {
        val a = 1 - exp(-2 * PI * 150.0 / RATE)
        var y1 = 0.0
        var y2 = 0.0
        val n = count / HOP
        val env = DoubleArray(n)
        for (k in 0 until n) {
            var e = 0.0
            for (i in from + k * HOP until from + (k + 1) * HOP) {
                val x = if (i in 0 until s.frames) 0.5 * (s.left[i] + s.right[i]) else 0.0
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

    /**
     * Quanto (ms) os bumbos da que entra estão atrasados em relação aos da que sai no trecho junto (negativo: adiantados),
     * a até ±60 ms. Correlação dos ataques graves em janelas de [windowFrames] (dois compassos); fica a mediana das
     * janelas com pico claro, para uma linha de baixo ou um trecho sem bumbo não puxarem o resultado. Null se não
     * houver janelas suficientes.
     */
    fun phaseLag(sa: Stereo, sb: Stereo, leadFrames: Int, overlapFrames: Int, windowFrames: Int = RATE * 4): Double? {
        if (overlapFrames < RATE * 2) return null
        val maxLag = (MAX_LAG_MS * RATE / 1000 / HOP).toInt() + 1
        val ea = lowOnset(sa, leadFrames, overlapFrames)
        val eb = lowOnset(sb, -maxLag * HOP, overlapFrames + 2 * maxLag * HOP)
        val win = (windowFrames / HOP).coerceAtLeast(64)
        val lags = ArrayList<Double>()
        var start = 0
        while (start + win / 2 <= ea.size) {
            val end = minOf(ea.size, start + win)
            var normA = 0.0
            for (k in start until end) normA += ea[k] * ea[k]
            if (normA > 1e-12) {
                val scores = DoubleArray(2 * maxLag + 1)
                for (c in -maxLag..maxLag) {
                    var dot = 0.0
                    var nb = 0.0
                    for (k in start until end) {
                        val idx = k + c + maxLag
                        if (idx in eb.indices) { dot += ea[k] * eb[idx]; nb += eb[idx] * eb[idx] }
                    }
                    scores[c + maxLag] = if (nb > 1e-12) dot / kotlin.math.sqrt(normA * nb) else 0.0
                }
                val best = scores.indices.maxByOrNull { scores[it] }!!
                val mean = scores.average()
                if (scores[best] >= 0.3 && scores[best] >= mean * 1.8 && best in 1 until scores.lastIndex) {
                    val l = scores[best - 1]; val c0 = scores[best]; val r = scores[best + 1]
                    val den = l - 2 * c0 + r
                    val frac = if (den < 0) (0.5 * (l - r) / den).coerceIn(-0.5, 0.5) else 0.0
                    lags += (best - maxLag + frac) * HOP * 1000.0 / RATE
                }
            }
            start += win
        }
        if (lags.size < 2) return null
        val sorted = lags.sorted()
        val med = sorted[sorted.size / 2]
        return if (kotlin.math.abs(med) >= MAX_LAG_MS - 1) null else med
    }

    /** Várias transições em sequência, com meio segundo de silêncio entre elas: o "mix de teste" do set. */
    fun sequence(parts: List<Stereo>, gapMs: Int = 500): Stereo {
        val gap = ms(gapMs)
        val total = parts.sumOf { it.frames } + gap * (parts.size - 1).coerceAtLeast(0)
        val l = FloatArray(total)
        val r = FloatArray(total)
        var at = 0
        parts.forEachIndexed { n, p ->
            p.left.copyInto(l, at, 0, p.frames)
            p.right.copyInto(r, at, 0, p.frames)
            at += p.frames + if (n < parts.lastIndex) gap else 0
        }
        return Stereo(l, r, RATE)
    }

    /**
     * Grava em [file] (WAV) as partes uma depois da outra, sem segurar todas na memória: cada transição tem dezenas de
     * MB em ponto flutuante. Partes que não deram para montar (null) são puladas. Devolve quantas entraram.
     */
    fun writeSequence(file: java.io.File, parts: List<() -> Stereo?>, gapMs: Int = 500, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Int {
        var frames = 0L
        var used = 0
        java.io.RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write(ByteArray(44))
            val gap = ByteArray(ms(gapMs) * 4)
            for ((n, part) in parts.withIndex()) {
                if (cancelled()) break
                progress(n)
                val s = part() ?: continue
                if (used > 0) { out.write(gap); frames += gap.size / 4 }
                val bytes = wav(s)
                out.write(bytes, 44, bytes.size - 44)
                frames += s.frames
                used++
            }
            val header = wav(Stereo(FloatArray(0), FloatArray(0), RATE))
            val data = (frames * 4).toInt()
            fun put32(at: Int, v: Int) { for (k in 0..3) header[at + k] = (v shr (8 * k)).toByte() }
            put32(4, 36 + data)
            put32(40, data)
            out.seek(0)
            out.write(header)
        }
        return used
    }

    private fun ms(v: Int) = (v.toLong() * RATE / 1000).toInt()

    private fun soft(v: Float) = if (v > -0.9f && v < 0.9f) v else tanh(v.toDouble()).toFloat()

    /** Leva a [RATE] e aplica a velocidade [speed] (interpolação linear). */
    fun resample(p: DecodedPcm, speed: Double): Stereo {
        val step = p.sampleRate.toDouble() / RATE * speed
        val n = ((p.frameCount - 1) / step).toInt().coerceAtLeast(0)
        val l = FloatArray(n)
        val r = FloatArray(n)
        for (i in 0 until n) {
            val pos = i * step
            val k = pos.toInt()
            val f = (pos - k).toFloat()
            l[i] = p.left[k] + (p.left[k + 1] - p.left[k]) * f
            r[i] = p.right[k] + (p.right[k + 1] - p.right[k]) * f
        }
        return Stereo(l, r, RATE)
    }

    /** Filtro passa-baixa de 1 polo em ~200 Hz: separa o grave para a troca de grave. */
    private class LowPass {
        private val a = (1 - exp(-2 * PI * 200.0 / RATE)).toFloat()
        private var y = 0f
        fun step(x: Float): Float {
            y += a * (x - y)
            return y
        }
    }

    /** WAV PCM 16 bits estéreo. */
    fun wav(s: Stereo): ByteArray {
        val frames = s.frames
        val data = frames * 4
        val out = ByteArray(44 + data)
        fun put32(at: Int, v: Int) { for (k in 0..3) out[at + k] = (v shr (8 * k)).toByte() }
        fun put16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(out, 0); put32(4, 36 + data); "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12); put32(16, 16); put16(20, 1); put16(22, 2); put32(24, s.rate); put32(28, s.rate * 4); put16(32, 4); put16(34, 16)
        "data".toByteArray().copyInto(out, 36); put32(40, data)
        for (i in 0 until frames) {
            put16(44 + i * 4, (s.left[i].coerceIn(-1f, 1f) * 32767f).toInt())
            put16(46 + i * 4, (s.right[i].coerceIn(-1f, 1f) * 32767f).toInt())
        }
        return out
    }
}
