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
 * Monta a prévia de uma transição, offline: [leadMs] da faixa que sai antes do ponto de saída, os compassos juntos no
 * estilo escolhido e [tailMs] da que entra depois. A que entra é tocada no andamento da que sai (mudar a velocidade muda
 * o tom, como um toca-discos sem "master tempo"; é uma prévia), com o ponto de entrada caindo exatamente no ponto de
 * saída, batida com batida.
 */
object MixRenderer {
    const val RATE = 44100

    fun transition(
        a: SegmentReader, bpmA: Double, b: SegmentReader, bpmB: Double, t: Transition, leadMs: Int = 12_000, tailMs: Int = 12_000,
    ): Stereo? {
        val overlapMs = (t.bars * SetPlanner.barMs(bpmA)).toInt().coerceAtLeast(0)
        val lead = min(leadMs, t.outMs)
        val aStart = t.outMs - lead
        val aLen = lead + overlapMs + if (t.style == MixStyle.ECHO) (SetPlanner.barMs(bpmA) * 2).toInt() else 0
        val pa = a.read(aStart, aLen) ?: return null
        // Velocidade da que entra: no andamento da que sai.
        val speed = if (bpmA > 0 && bpmB > 0) bpmA / bpmB else 1.0
        val bOutMs = overlapMs + tailMs
        val pb = b.read(t.inMs, (bOutMs * speed).toInt() + 200) ?: return null
        val sa = resample(pa, 1.0)
        val sb = resample(pb, speed)
        val leadFrames = ms(lead)
        val overlapFrames = ms(overlapMs)
        val total = leadFrames + ms(bOutMs)
        val outL = FloatArray(total)
        val outR = FloatArray(total)
        val lowA = LowPass()
        val lowAR = LowPass()
        val lowB = LowPass()
        val lowBR = LowPass()
        val beatFrames = ms(SetPlanner.barMs(bpmA).toInt() / 4)
        val echoL = FloatArray(max(1, beatFrames))
        val echoR = FloatArray(max(1, beatFrames))
        var echoAt = 0
        for (i in 0 until total) {
            val inA = i < sa.frames
            var al = if (inA) sa.left[i] else 0f
            var ar = if (inA) sa.right[i] else 0f
            val j = i - leadFrames
            var bl = if (j >= 0 && j < sb.frames) sb.left[j] else 0f
            var br = if (j >= 0 && j < sb.frames) sb.right[j] else 0f
            // x: 0 no ponto de saída, 1 no fim dos compassos juntos.
            val x = if (overlapFrames == 0) (if (j >= 0) 1.0 else 0.0) else (j.toDouble() / overlapFrames).coerceIn(0.0, 1.0)
            when (t.style) {
                MixStyle.BLEND -> {
                    val ga = cos(x * PI / 2).toFloat()
                    val gb = sin(x * PI / 2).toFloat()
                    al *= ga; ar *= ga; bl *= gb; br *= gb
                }
                MixStyle.BASS_SWAP -> {
                    // Agudos e médios da que entra sobem na primeira metade; no meio, os graves trocam de uma para outra;
                    // a que sai desce no último quarto.
                    val la = lowA.step(al); val ra = lowAR.step(ar)
                    val lb = lowB.step(bl); val rb = lowBR.step(br)
                    val swap = x >= 0.5
                    val highB = min(1.0, x * 2).toFloat()
                    val fadeA = if (x < 0.75) 1f else cos((x - 0.75) / 0.25 * PI / 2).toFloat()
                    val aLowGain = if (swap) 0f else 1f
                    val bLowGain = if (swap) 1f else 0f
                    if (j < 0) { /* antes da saída: só a que sai, inteira */ } else {
                        al = (la * aLowGain + (al - la)) * fadeA; ar = (ra * aLowGain + (ar - ra)) * fadeA
                        bl = lb * bLowGain + (bl - lb) * highB; br = rb * bLowGain + (br - rb) * highB
                    }
                    if (j >= overlapFrames) { al = 0f; ar = 0f }
                }
                MixStyle.CUT -> {
                    // Troca seca no início do compasso, com 10 ms para não estalar.
                    val g = ((j + ms(10)).toDouble() / ms(10)).coerceIn(0.0, 1.0).toFloat()
                    al *= 1 - g; ar *= 1 - g; bl *= g; br *= g
                }
                MixStyle.ECHO -> {
                    // A que sai para de tocar um compasso depois do ponto de saída e fica só o eco (uma batida, realimentado).
                    val dryOn = j < ms(SetPlanner.barMs(bpmA).toInt())
                    val inL = if (dryOn) al else 0f
                    val inR = if (dryOn) ar else 0f
                    val el = echoL[echoAt]; val er = echoR[echoAt]
                    echoL[echoAt] = inL * 0.5f + el * 0.55f
                    echoR[echoAt] = inR * 0.5f + er * 0.55f
                    echoAt = (echoAt + 1) % echoL.size
                    if (j < 0) { /* antes: só a que sai */ } else {
                        al = inL + el; ar = inR + er
                    }
                    val gb = (x * 4).coerceAtMost(1.0).toFloat()
                    bl *= gb; br *= gb
                }
            }
            outL[i] = soft(al + bl)
            outR[i] = soft(ar + br)
        }
        return Stereo(outL, outR, RATE)
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
