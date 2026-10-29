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
 * Níveis de cada canal num instante da transição, como os controles do mixer: fader (vol), HI, MID e LOW de cada deck
 * (0 corte, 1 meio-dia), FILTER (0,5 desligado) e ECHO (0 ou 1). A prévia é mixada com eles e a tela mostra os mesmos.
 */
class Levels {
    var aVol = 1f; var aLow = 1f; var aMid = 1f; var aHigh = 1f; var aFilter = 0.5f; var aEcho = 0f
    var bVol = 0f; var bLow = 1f; var bMid = 1f; var bHigh = 1f; var bFilter = 0.5f; var bEcho = 0f

    /** O ECHO do CH 1 (o da saída com eco). */
    var echo: Boolean
        get() = aEcho >= 0.5f
        set(v) { aEcho = if (v) 1f else 0f }

    operator fun get(c: Ctl): Float = when (c) {
        Ctl.A_VOL -> aVol; Ctl.A_HIGH -> aHigh; Ctl.A_MID -> aMid; Ctl.A_LOW -> aLow; Ctl.A_FILTER -> aFilter; Ctl.A_ECHO -> aEcho
        Ctl.B_VOL -> bVol; Ctl.B_HIGH -> bHigh; Ctl.B_MID -> bMid; Ctl.B_LOW -> bLow; Ctl.B_FILTER -> bFilter; Ctl.B_ECHO -> bEcho
    }

    operator fun set(c: Ctl, v: Float) {
        when (c) {
            Ctl.A_VOL -> aVol = v; Ctl.A_HIGH -> aHigh = v; Ctl.A_MID -> aMid = v; Ctl.A_LOW -> aLow = v; Ctl.A_FILTER -> aFilter = v; Ctl.A_ECHO -> aEcho = v
            Ctl.B_VOL -> bVol = v; Ctl.B_HIGH -> bHigh = v; Ctl.B_MID -> bMid = v; Ctl.B_LOW -> bLow = v; Ctl.B_FILTER -> bFilter = v; Ctl.B_ECHO -> bEcho = v
        }
    }

    fun copyFrom(o: Levels): Levels { Ctl.entries.forEach { this[it] = o[it] }; return this }
}

/** Uma fonte de áudio tocada ao vivo: quem toca pede quadros e ela os faz na hora (a mixagem com a mão do DJ). */
interface PcmSource {
    val rate: Int

    /** Preenche até [frames] quadros; devolve quantos (0: acabou). Chamado pela thread de áudio. */
    fun read(left: FloatArray, right: FloatArray, frames: Int): Int

    /** Pula para [ms] (vale no próximo [read]). */
    fun seek(ms: Int)

    /** Até onde já foi feito (ms); quem toca desconta o que ainda está no buffer para saber o que se ouve. */
    val renderedMs: Int

    /** Quem toca parou: solta o que a fonte segura (threads de fundo). */
    fun close() {}
}

/**
 * As duas faixas prontas para mixar, em 16 bits: a que sai a partir de [MixPreview.aStartMs] e a que entra a partir de um
 * compasso antes do ponto de entrada ([bEntry] quadros de folga, para o sync à mão poder atrasá-la ou adiantá-la), as duas
 * já no andamento do master. Mixar é barato; ler e esticar é que demora — por isso o estilo, o ritmo, o grave, o sync e
 * a mão do DJ mudam sem montar de novo.
 */
class Stems(val aL: ShortArray, val aR: ShortArray, val bL: ShortArray, val bR: ShortArray, val bEntry: Int) {
    val aFrames get() = minOf(aL.size, aR.size)
    val bFrames get() = minOf(bL.size, bR.size)

    companion object {
        fun pack(f: FloatArray, n: Int = f.size) = ShortArray(n) { (f[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    }
}

/**
 * Uma prévia montada: as duas faixas prontas ([stems]) e o que a tela precisa para desenhá-la como dois decks — o tempo de
 * cada faixa em cada instante, as ondas coloridas (já na base de tempo da prévia, a que entra esticada para o andamento da
 * que sai) e as batidas. [transition] é a troca com que foi montada; o estilo, o ritmo, o sync e a mixagem do DJ podem
 * mudar depois sem montar de novo (ver [sameStems]).
 */
class MixPreview(
    val stems: Stems,
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
    /** O atraso medido entre os bumbos (ms da prévia; positivo: a que entra está atrasada). Null: não deu para medir. */
    val measuredMs: Double?,
    val waveA: os.deckbridge.logic.ColorWave?,
    /** A onda da que entra sem nenhum sync (a tela a desloca de [shiftMs]). */
    val waveB: os.deckbridge.logic.ColorWave?,
    val lengthMs: Int,
) {
    val beatMs get() = barMsA / 4

    /**
     * Quanto a que entra é adiantada (ms da prévia): o sync fino medido (se ligado), o sync à mão e as batidas inteiras.
     */
    fun shiftMs(tr: Transition = transition): Double =
        (if (tr.phaseLock) measuredMs ?: 0.0 else 0.0) + tr.nudgeMs + tr.beatShift * beatMs

    /** O ajuste aplicado na troca com que foi montada. */
    val phaseMs get() = shiftMs(transition)

    /** A mixdown da troca com que foi montada (o WAV do mix de teste). */
    val audio: Stereo by lazy { mixdown(transition) }

    /** Compassos desde o ponto de saída no instante [ms] da prévia (negativo: antes). */
    fun relBar(ms: Int) = (ms - leadMs) / barMsA

    /** O loop da que sai na base de tempo da prévia (ms; 0 sem loop). */
    val aLoopMs get() = transition.aLoopBars * barMsA
    /** O loop da que entra na base de tempo da prévia (ms; 0 sem loop) e quantas vezes toca. */
    val bLoopMs get() = if (transition.bLoopTimes > 0) transition.bLoopBars * barMsA else 0.0
    val bLoopTimes get() = transition.bLoopTimes

    /** Onde está a faixa que sai (ms dela) no instante [ms] da prévia (no loop, volta ao começo dele). */
    fun aAt(ms: Int): Int {
        if (aLoopMs > 0 && ms >= leadMs) return transition.outMs + (((ms - leadMs) % aLoopMs) * speedA).toInt()
        return aStartMs + (ms * speedA).toInt()
    }

    /** Onde está a que entra (ms dela) no instante [ms] da prévia (antes do ponto de entrada: ainda não começou). */
    fun bAt(ms: Int, tr: Transition = transition): Int {
        val t = ms - leadMs + shiftMs(tr)
        if (bLoopMs > 0 && t >= 0) {
            val loops = bLoopMs * bLoopTimes
            return if (t < loops) transition.inMs + ((t % bLoopMs) * speed).toInt() else transition.inMs + ((t - bLoopMs * (bLoopTimes - 1)) * speed).toInt()
        }
        return transition.inMs + (t * speed).toInt()
    }

    /** Instante da prévia em que a faixa que sai passa por [trackMs] (a primeira vez, se estiver no loop). */
    fun fromA(trackMs: Int) = ((trackMs - aStartMs) / speedA).toInt()
    fun fromB(trackMs: Int, tr: Transition = transition): Int {
        var t = (trackMs - transition.inMs) / speed
        if (bLoopMs > 0 && t >= bLoopMs) t += bLoopMs * (bLoopTimes - 1)
        return leadMs + (t - shiftMs(tr)).toInt()
    }

    /** Todos os instantes da prévia em que a que sai passa por [trackMs] (no loop ela passa várias vezes). */
    fun timesA(trackMs: Int): List<Int> {
        if (aLoopMs <= 0 || trackMs < transition.outMs) return if (aLoopMs > 0 && trackMs >= transition.outMs) emptyList() else listOf(fromA(trackMs))
        val off = (trackMs - transition.outMs) / speedA
        if (off >= aLoopMs) return emptyList()
        return generateSequence(0) { it + 1 }.map { (leadMs + off + it * aLoopMs).toInt() }.takeWhile { it <= lengthMs }.toList()
    }

    fun timesB(trackMs: Int, tr: Transition = transition): List<Int> {
        val off = (trackMs - transition.inMs) / speed
        if (bLoopMs <= 0 || off < 0 || off >= bLoopMs) return listOf(fromB(trackMs, tr))
        return (0 until bLoopTimes).map { (leadMs + off + it * bLoopMs - shiftMs(tr)).toInt() }
    }

    /** Os níveis em [ms]: o automático de [tr], por cima a mixagem guardada ([take]) e, por cima, a mão agora ([pass]). */
    fun levelsAt(ms: Int, tr: Transition = transition, take: MixTake? = tr.take, pass: TakeRecorder? = null, into: Levels = Levels()): Levels =
        MixControls.levels(tr, (ms - leadMs).toDouble(), overlapMs.toDouble(), barMsA, take, pass, into)

    /** Muda só o que não exige ler e esticar o áudio de novo? (estilo, ritmo, grave, sync, lembrete e a mixagem do DJ) */
    fun sameStems(tr: Transition) = tr.fromId == transition.fromId && tr.toId == transition.toId && tr.outMs == transition.outMs &&
        tr.inMs == transition.inMs && tr.bars == transition.bars && tr.masterTempo == transition.masterTempo &&
        tr.aLoopBars == transition.aLoopBars && tr.bLoopBars == transition.bLoopBars && tr.bLoopTimes == transition.bLoopTimes

    /** Mixa a prévia inteira com [tr] e [take] (offline): o mesmo som do ensaio ao vivo. */
    fun mixdown(tr: Transition = transition, take: MixTake? = tr.take): Stereo {
        val total = (lengthMs.toLong() * MixRenderer.RATE / 1000).toInt()
        val l = FloatArray(total)
        val r = FloatArray(total)
        val dsp = MixDsp(this)
        var at = 0
        while (at < total) {
            val n = minOf(MixDsp.BLOCK * 64, total - at)
            dsp.process(at, n, l, r, at, tr, take, null)
            at += n
        }
        return Stereo(l, r, MixRenderer.RATE)
    }
}

/** Os níveis automáticos de uma troca e a mão do DJ por cima. */
object MixControls {
    fun levels(tr: Transition, relMs: Double, overlapMs: Double, barMs: Double, take: MixTake?, pass: TakeRecorder?, out: Levels): Levels {
        MixRenderer.levels(tr.style, relMs, overlapMs, barMs, out, tr.pace, tr.swapAt, tr.swapBars)
        if (take == null && pass == null) return out
        val bar = relMs / barMs
        for (c in Ctl.entries) {
            val v = pass?.at(c, bar) ?: take?.at(c, bar) ?: continue
            out[c] = v
        }
        return out
    }
}

/**
 * A mixagem, amostra a amostra, para a prévia ao vivo e para o WAV: em cada canal um EQ de três bandas que soma de volta
 * ao original quando está tudo no meio-dia (grave abaixo de ~200 Hz, médio até ~2,5 kHz, agudo acima), o fader, o FILTER
 * (passa-baixa para a esquerda, passa-alta para a direita, como o COLOR FX da DDJ-400) e o ECHO de uma batida (as contas
 * do motor, [EchoSpec]). Os níveis andam em linha reta dentro de blocos de ~0,4 ms (sem estalo quando a mão mexe). Não é thread-safe: uma instância por quem toca.
 */
class MixDsp(private val p: MixPreview) {
    private inner class Chan {
        val lowL = OnePole(200.0); val lowR = OnePole(200.0)
        val midL = OnePole(2500.0); val midR = OnePole(2500.0)
        val svfL = Svf(); val svfR = Svf()
        val echoL = FloatArray(maxOf(1, (p.beatMs * MixRenderer.RATE / 1000).toInt()))
        val echoR = FloatArray(echoL.size)
        var echoAt = 0
        // Os outros efeitos do botão FX (alocados uma vez: nada é criado enquanto toca).
        val verbL = Verb(0); val verbR = Verb(23)
        val flangeL = FloatArray(FLANGE_MAX); val flangeR = FloatArray(FLANGE_MAX)
        var flangeAt = 0
        val rollL = FloatArray(maxOf(1, echoL.size / 2)); val rollR = FloatArray(rollL.size)
        var rollRec = -1 // -1: sem roll; < tamanho: gravando; senão tocando
        var rollAt = 0
        // Ganhos da amostra atual (andam em linha reta do começo ao fim de cada bloco).
        var vol = 0f; var low = 0f; var mid = 0f; var high = 0f; var echo = 0f
    }

    private val a = Chan()
    private val b = Chan()
    private val head = Levels()
    private val tail = Levels()
    private var lastFrame = Int.MIN_VALUE

    /**
     * Mixa [count] quadros a partir do quadro [from] da prévia em [outL]/[outR] (a partir de [offset]). [tr] dá o
     * automático e o sync; [take] e [pass], a mão do DJ. Um salto (quadro diferente do seguinte ao último) limpa o eco e os
     * filtros, como um player que pula.
     */
    fun process(from: Int, count: Int, outL: FloatArray, outR: FloatArray, offset: Int, tr: Transition, take: MixTake?, pass: TakeRecorder?) {
        if (from != lastFrame) reset()
        val rate = MixRenderer.RATE
        val leadFrames = (p.leadMs.toLong() * rate / 1000).toInt()
        val shift = (p.shiftMs(tr) * rate / 1000).toInt()
        val st = p.stems
        val echoFb = EchoSpec.feedback(EchoSpec.PREVIEW_AMOUNT)
        val echoWet = EchoSpec.wet(EchoSpec.PREVIEW_AMOUNT)
        fun levelsAt(frame: Int, into: Levels) =
            MixControls.levels(tr, (frame - leadFrames) * 1000.0 / rate, p.overlapMs.toDouble(), p.barMsA, take, pass, into)
        var i = 0
        while (i < count) {
            val f0 = from + i
            // Blocos fixos na linha do tempo (múltiplos de BLOCK), para o ao vivo e o WAV soarem igual pedindo em pedaços
            // de qualquer tamanho. Os níveis no começo e no fim do bloco; no meio, em linha reta (sem atraso nem estalo).
            val start = f0 - Math.floorMod(f0, BLOCK)
            val n = minOf(start + BLOCK - f0, count - i)
            levelsAt(start, head)
            levelsAt(start + BLOCK, tail)
            a.svfL.tune(head.aFilter); a.svfR.tune(head.aFilter)
            b.svfL.tune(head.bFilter); b.svfR.tune(head.bFilter)
            for (q in 0 until n) {
                val fr = f0 + q
                val w = (fr - start).toFloat() / BLOCK
                a.vol = mix(head.aVol, tail.aVol, w); a.low = mix(head.aLow, tail.aLow, w); a.mid = mix(head.aMid, tail.aMid, w)
                a.high = mix(head.aHigh, tail.aHigh, w); a.echo = mix(head.aEcho, tail.aEcho, w)
                b.vol = mix(head.bVol, tail.bVol, w); b.low = mix(head.bLow, tail.bLow, w); b.mid = mix(head.bMid, tail.bMid, w)
                b.high = mix(head.bHigh, tail.bHigh, w); b.echo = mix(head.bEcho, tail.bEcho, w)
                val al = if (fr in 0 until st.aFrames) st.aL[fr] / 32768f else 0f
                val ar = if (fr in 0 until st.aFrames) st.aR[fr] / 32768f else 0f
                // A que entra: o ponto de entrada (com o sync) cai no ponto de saída. Antes dele há um compasso de folga, que
                // só se ouve se a mão do DJ abrir o fader do CH 2 antes da hora.
                val bi = st.bEntry + fr - leadFrames + shift
                val playB = bi in 0 until st.bFrames
                val bl = if (playB) st.bL[bi] / 32768f else 0f
                val br = if (playB) st.bR[bi] / 32768f else 0f
                var yaL = a.svfL.step(eq(a, a.lowL, a.midL, al) * a.vol)
                var yaR = a.svfR.step(eq(a, a.lowR, a.midR, ar) * a.vol)
                var ybL = b.svfL.step(eq(b, b.lowL, b.midL, bl) * b.vol)
                var ybR = b.svfR.step(eq(b, b.lowR, b.midR, br) * b.vol)
                when (tr.fx) {
                    MixFx.ECHO -> {
                        yaL += echo(a, 0, yaL, echoFb, echoWet); yaR += echo(a, 1, yaR, echoFb, echoWet)
                        ybL += echo(b, 0, ybL, echoFb, echoWet); ybR += echo(b, 1, ybR, echoFb, echoWet)
                    }
                    MixFx.REVERB -> {
                        yaL += a.verbL.step(yaL * a.echo) * VERB_WET; yaR += a.verbR.step(yaR * a.echo) * VERB_WET
                        ybL += b.verbL.step(ybL * b.echo) * VERB_WET; ybR += b.verbR.step(ybR * b.echo) * VERB_WET
                    }
                    MixFx.FLANGER -> {
                        val lfo = (0.5 + 0.5 * sin(2 * PI * fr / (p.barMsA * rate / 1000))).toFloat()
                        yaL = flange(a, a.flangeL, yaL, lfo); yaR = flange(a, a.flangeR, yaR, 1f - lfo)
                        ybL = flange(b, b.flangeL, ybL, lfo); ybR = flange(b, b.flangeR, ybR, 1f - lfo)
                        a.flangeAt = (a.flangeAt + 1) % FLANGE_MAX; b.flangeAt = (b.flangeAt + 1) % FLANGE_MAX
                    }
                    MixFx.ROLL -> {
                        roll(a, yaL, yaR); yaL = lr[0]; yaR = lr[1]
                        roll(b, ybL, ybR); ybL = lr[0]; ybR = lr[1]
                    }
                }
                a.echoAt = (a.echoAt + 1) % a.echoL.size
                b.echoAt = (b.echoAt + 1) % b.echoL.size
                outL[offset + i + q] = soft(yaL + ybL)
                outR[offset + i + q] = soft(yaR + ybR)
            }
            i += n
        }
        lastFrame = from + count
    }

    private fun mix(x: Float, y: Float, w: Float) = x + (y - x) * w

    private fun eq(c: Chan, lowF: OnePole, midF: OnePole, x: Float): Float {
        val lo = lowF.step(x)
        val lm = midF.step(x)
        return lo * c.low + (lm - lo) * c.mid + (x - lm) * c.high
    }

    /** O eco de uma batida: entra o que sai do canal enquanto o ECHO está ligado; o rastro continua depois, decaindo. */
    private fun echo(c: Chan, side: Int, dry: Float, fb: Float, wet: Float): Float {
        val buf = if (side == 0) c.echoL else c.echoR
        val old = buf[c.echoAt]
        buf[c.echoAt] = dry * c.echo + old * fb
        return old * wet
    }

    /**
     * FLANGER: atraso curto (1 a 7 ms) que vai e volta uma vez por compasso, com realimentação; [lfo] 0..1 (lados opostos
     * no estéreo). O botão FX mistura do seco ao efeito.
     */
    private fun flange(c: Chan, buf: FloatArray, dry: Float, lfo: Float): Float {
        val delay = (MixRenderer.RATE * (0.001f + 0.006f * lfo)).coerceIn(1f, FLANGE_MAX - 2f)
        val pos = c.flangeAt - delay + FLANGE_MAX
        val i = pos.toInt()
        val f = pos - i
        val d = buf[i % FLANGE_MAX] * (1 - f) + buf[(i + 1) % FLANGE_MAX] * f
        buf[c.flangeAt] = dry + d * 0.55f
        return mix(dry, 0.6f * dry + 0.7f * d, c.echo)
    }

    /**
     * ROLL: ao ligar, grava meia batida e repete essa meia batida enquanto ligado (o roll do CDJ); desligou, volta ao som.
     * Um quadro: [Chan.rollRec] conta o que já gravou; cheio, [Chan.rollAt] anda no laço. O resultado fica em [lr].
     */
    private fun roll(c: Chan, l: Float, r: Float) {
        lr[0] = l; lr[1] = r
        if (c.echo < 0.5f) { c.rollRec = -1; return }
        if (c.rollRec < 0) { c.rollRec = 0; c.rollAt = 0 }
        if (c.rollRec < c.rollL.size) {
            c.rollL[c.rollRec] = l; c.rollR[c.rollRec] = r; c.rollRec++
        } else {
            lr[0] = mix(l, c.rollL[c.rollAt], c.echo); lr[1] = mix(r, c.rollR[c.rollAt], c.echo)
            c.rollAt = (c.rollAt + 1) % c.rollL.size
        }
    }

    /** O par esquerdo/direito que [roll] devolve (sem criar nada por quadro). */
    private val lr = FloatArray(2)

    fun reset() {
        for (c in listOf(a, b)) {
            c.lowL.y = 0f; c.lowR.y = 0f; c.midL.y = 0f; c.midR.y = 0f
            c.svfL.clear(); c.svfR.clear()
            c.echoL.fill(0f); c.echoR.fill(0f)
            c.echoAt = 0
            c.verbL.clear(); c.verbR.clear()
            c.flangeL.fill(0f); c.flangeR.fill(0f); c.flangeAt = 0
            c.rollRec = -1; c.rollAt = 0
        }
    }

    /**
     * REVERB: o de Schroeder/Moorer (4 pentes com amortecimento em paralelo e 2 passa-tudo em série), nas medidas do
     * Freeverb a 44,1 kHz; [spread] desloca o lado direito. Entra o que sai do canal com o FX ligado; a cauda continua.
     */
    class Verb(spread: Int) {
        private val combs = intArrayOf(1116, 1188, 1277, 1356).map { FloatArray(it + spread) }
        private val combAt = IntArray(4)
        private val combLow = FloatArray(4)
        private val alls = intArrayOf(556, 441).map { FloatArray(it + spread) }
        private val allAt = IntArray(2)

        fun step(x: Float): Float {
            var out = 0f
            for (k in 0 until 4) {
                val buf = combs[k]
                val y = buf[combAt[k]]
                combLow[k] = y * 0.8f + combLow[k] * 0.2f
                buf[combAt[k]] = x * 0.3f + combLow[k] * 0.88f
                combAt[k] = (combAt[k] + 1) % buf.size
                out += y
            }
            for (k in 0 until 2) {
                val buf = alls[k]
                val b = buf[allAt[k]]
                buf[allAt[k]] = out + b * 0.5f
                out = b - out
                allAt[k] = (allAt[k] + 1) % buf.size
            }
            return out
        }

        fun clear() {
            combs.forEach { it.fill(0f) }; alls.forEach { it.fill(0f) }; combLow.fill(0f)
        }
    }

    private fun soft(v: Float) = if (v > -0.9f && v < 0.9f) v else tanh(v.toDouble()).toFloat()

    /** Passa-baixa de um polo. */
    class OnePole(hz: Double) {
        private val k = (1 - exp(-2 * PI * hz / MixRenderer.RATE)).toFloat()
        var y = 0f
        fun step(x: Float): Float { y += k * (x - y); return y }
    }

    /**
     * O FILTER: filtro de estado variável (Chamberlin) de 12 dB/oitava. [tune] com 0,5 desliga; abaixo é passa-baixa (de
     * ~7 kHz a 150 Hz), acima passa-alta (de 20 Hz a 4 kHz), em escala exponencial como um botão de filtro.
     */
    class Svf {
        private var low = 0f
        private var band = 0f
        private var f = 0f
        private var mode = 0 // 0 desligado, -1 passa-baixa, 1 passa-alta
        private val q = 1.3f

        fun tune(v: Float) {
            val d = v - 0.5f
            if (kotlin.math.abs(d) < DEAD) { mode = 0; return }
            val x = ((kotlin.math.abs(d) - DEAD) / (0.5f - DEAD)).coerceIn(0f, 1f).toDouble()
            val hz = if (d < 0) 7000.0 * Math.pow(150.0 / 7000.0, x) else 20.0 * Math.pow(4000.0 / 20.0, x)
            mode = if (d < 0) -1 else 1
            f = (2 * sin(PI * hz / MixRenderer.RATE)).toFloat()
        }

        fun step(x: Float): Float {
            if (mode == 0) { low = 0f; band = 0f; return x }
            low += f * band
            val high = x - low - q * band
            band += f * high
            return if (mode < 0) low else high
        }

        fun clear() { low = 0f; band = 0f }

        private companion object { const val DEAD = 0.03f }
    }

    companion object {
        /**
         * Quadros por bloco de níveis (~0,36 ms): curto o bastante para um corte seco (fader de uma vez no compasso) não
         * vazar o começo de um bumbo.
         */
        const val BLOCK = 16

        /** Atraso máximo do FLANGER (quadros, ~11 ms). */
        const val FLANGE_MAX = 512

        /** Quanto do REVERB volta para a mixagem. */
        const val VERB_WET = 0.5f
    }
}

/**
 * A prévia tocada ao vivo: mixa na hora com a troca de agora ([transition], que a tela pode mudar enquanto toca), a
 * mixagem do DJ ([take]) e a mão dele agora ([pass]). [loop] repete um trecho (ms), para ensaiar a mesma troca de novo.
 */
class MixPlayer(val preview: MixPreview, @Volatile var transition: Transition = preview.transition) : PcmSource {
    override val rate = MixRenderer.RATE
    @Volatile var take: MixTake? = transition.take
    val pass = TakeRecorder()
    @Volatile var loop: IntRange? = null

    /** Quantas vezes o loop voltou ao começo (a tela fecha a passada a cada volta). */
    @Volatile var wraps = 0
        private set

    private val dsp = MixDsp(preview)
    private val total = (preview.lengthMs.toLong() * MixRenderer.RATE / 1000).toInt()
    @Volatile private var frame = 0
    @Volatile private var pending = -1

    override val renderedMs get() = (frame.toLong() * 1000 / MixRenderer.RATE).toInt()

    override fun seek(ms: Int) { pending = ms.coerceIn(0, preview.lengthMs) }

    override fun read(left: FloatArray, right: FloatArray, frames: Int): Int {
        pending.let { if (it >= 0) { frame = (it.toLong() * MixRenderer.RATE / 1000).toInt(); pending = -1 } }
        var done = 0
        while (done < frames) {
            // Um loop vazio (fim antes do começo) seria uma volta sem fim na thread do áudio: vale como sem loop.
            val lp = loop?.takeIf { minOf(total.toLong(), it.last.toLong() * MixRenderer.RATE / 1000) > it.first.toLong() * MixRenderer.RATE / 1000 }
            val end = if (lp != null) minOf(total, (lp.last.toLong() * MixRenderer.RATE / 1000).toInt()) else total
            if (frame >= end) {
                if (lp == null) break
                frame = (lp.first.toLong() * MixRenderer.RATE / 1000).toInt()
                wraps++
                continue
            }
            val n = minOf(frames - done, end - frame)
            dsp.process(frame, n, left, right, done, transition, take, pass)
            frame += n
            done += n
        }
        return done
    }
}

/**
 * Um passo da virada, para o DJ fazer no mixer: de [fromBar] a [toBar] compassos depois do ponto de saída (iguais: é um
 * gesto de uma vez, no começo daquele compasso).
 */
data class MixStep(val fromBar: Double, val toBar: Double, val text: String) {
    val instant get() = toBar <= fromBar
}

/**
 * Os passos da virada [tr] no mixer, na ordem — o mesmo que a prévia faz com os níveis ([MixRenderer.levels]), dito em
 * gestos: subir o fader, abrir os agudos e médios, trocar o grave, baixar o fader. [bars] é quantos compassos a troca dura.
 */
object MixGuide {
    fun steps(tr: Transition, bars: Int = tr.bars): List<MixStep> {
        val n = bars.coerceAtLeast(1).toDouble()
        fun frac(f: Double) = f * n
        return when (tr.style) {
            MixStyle.BASS_SWAP -> {
                // Os mesmos pontos de [MixRenderer.swapShape]: o fader do CH 2 sobe com o LOW fechado, o CH 1 abre espaço
                // tirando agudo e médio, o grave troca num início de compasso e o fader do CH 1 desce no fim.
                val shape = MixRenderer.swapShape(n, tr.pace, tr.swapAt)
                val s = shape.swap * n
                val swap = if (tr.swapBars > 0) {
                    val g = tr.swapBars.toDouble().coerceAtMost(n)
                    val from = (s - g).coerceAtLeast(0.0)
                    MixStep(from, (from + g).coerceAtMost(n), "Troca o grave aos poucos: LOW do CH 1 desce enquanto o do CH 2 sobe")
                } else MixStep(s, s, "Troca o grave: corta o LOW do CH 1 e abre o do CH 2 de uma vez")
                listOf(
                    MixStep(0.0, 0.0, "Entra o CH 2: LOW fechado, HI e MID no meio-dia, fader embaixo"),
                    MixStep(0.0, shape.rise * n, "Sobe o fader do CH 2 (os botões ficam onde estão)"),
                    MixStep(0.0, s, "Abaixa o HI do CH 1 e um pouco do MID: abre espaço para a que entra"),
                    swap,
                    MixStep(shape.fadeFrom * n, n, "Baixa o fader do CH 1 até fechar"),
                ).sortedBy { it.fromBar }
            }
            MixStyle.BLEND -> when (tr.pace) {
                Transition.Pace.NORMAL -> listOf(MixStep(0.0, n, "Cruza os faders devagar: CH 2 sobe enquanto o CH 1 desce"))
                Transition.Pace.FAST -> listOf(MixStep(frac(0.375), frac(0.625), "Cruza os faders rápido: CH 2 sobe, CH 1 desce"))
                Transition.Pace.SLOW -> listOf(
                    MixStep(0.0, frac(0.5), "Sobe o fader do CH 2 (as duas juntas no meio)"),
                    MixStep(frac(0.5), n, "Baixa o fader do CH 1"),
                )
            }
            MixStyle.ECHO -> {
                val rise = when (tr.pace) {
                    Transition.Pace.FAST -> 0.125
                    Transition.Pace.NORMAL -> 0.25
                    Transition.Pace.SLOW -> 0.5
                }
                listOf(
                    MixStep(0.0, 0.0, "Liga o ${tr.fx.label} no CH 1"),
                    MixStep(0.0, frac(rise), "Sobe o fader do CH 2"),
                    MixStep(1.0, 1.0, if (tr.fx.tail) "Fecha o CH 1: fica só o ${tr.fx.label.lowercase()}" else "Fecha o CH 1"),
                ).sortedBy { it.fromBar }
            }
            MixStyle.CUT -> listOf(MixStep(0.0, 0.0, "Corte seco: troca de canal no início do compasso"))
            MixStyle.FILTER -> {
                val shape = MixRenderer.swapShape(n, tr.pace, tr.swapAt)
                listOf(
                    MixStep(0.0, 0.0, "Entra o CH 2: LOW fechado, fader embaixo"),
                    MixStep(0.0, shape.rise * n, "Sobe o fader do CH 2"),
                    MixStep(0.0, n, "Gira o FILTER do CH 1 para a direita (passa-alta) até o fim"),
                    MixStep(shape.swap * n, shape.swap * n, "Troca o grave: corta o LOW do CH 1 e abre o do CH 2"),
                    MixStep(shape.fadeFrom * n, n, "Baixa o fader do CH 1 até fechar"),
                ).sortedBy { it.fromBar }
            }
        }.let { base -> (loopSteps(tr) + base).sortedBy { it.fromBar } }
    }

    /** Os loops da troca, como gestos: ligar no ponto e soltar depois das voltas. */
    private fun loopSteps(tr: Transition): List<MixStep> = listOfNotNull(
        if (tr.aLoopBars > 0) MixStep(0.0, 0.0, "LOOP de ${tr.aLoopBars} compassos no CH 1 no ponto de saída (segura até o fim)") else null,
        if (tr.bLoopBars > 0 && tr.bLoopTimes > 1) MixStep(0.0, (tr.bLoopBars * tr.bLoopTimes).toDouble(), "LOOP de ${tr.bLoopBars} no CH 2: ${tr.bLoopTimes} voltas e solta") else null,
    )

    /** Os passos acontecendo em [bar] (os gestos de uma vez valem por um compasso) e o próximo que ainda vem. */
    fun at(steps: List<MixStep>, bar: Double): Pair<List<MixStep>, MixStep?> {
        val now = steps.filter { if (it.instant) bar >= it.fromBar && bar < it.fromBar + 1 else bar >= it.fromBar && bar < it.toBar }
        val next = steps.filter { it.fromBar > bar && it !in now }.minByOrNull { it.fromBar }
        return now to next
    }
}

object MixRenderer {
    const val RATE = 44100

    /**
     * A forma da troca de grave, em frações da troca: até onde o fader do CH 2 sobe ([rise]), onde o grave passa ([swap],
     * num início de compasso) e de onde o fader do CH 1 desce até fechar ([fadeFrom]). O CH 2 sobe pelo fader com o LOW
     * fechado e HI/MID no meio-dia (como se faz na controladora: o botão fica, o fader sobe); a saída nunca dura menos que
     * [MIN_FADE_BARS] compassos (antes, numa troca curta, sumia em um ou dois e soava apressada).
     */
    data class SwapShape(val rise: Double, val swap: Double, val fadeFrom: Double)

    /** O fader da que sai nunca desce em menos que isso (compassos), se a troca tiver espaço. */
    const val MIN_FADE_BARS = 4.0

    fun swapShape(bars: Double, pace: Transition.Pace, swapAt: Double): SwapShape {
        val n = bars.coerceAtLeast(1.0)
        val s = (Math.round(swapAt.coerceIn(0.0, 1.0) * n).coerceAtLeast(1) / n).coerceAtMost(1.0)
        val riseShare = when (pace) {
            Transition.Pace.FAST -> 0.25
            Transition.Pace.NORMAL -> 0.5
            Transition.Pace.SLOW -> 0.9
        }
        val rise = (s * riseShare).coerceAtLeast(minOf(s, 1 / n))
        val fadeShare = when (pace) {
            Transition.Pace.FAST -> 0.125
            Transition.Pace.NORMAL -> 0.25
            Transition.Pace.SLOW -> 0.5
        }
        val fade = maxOf(fadeShare, minOf(MIN_FADE_BARS / n, 1.0))
        // A que sai só desce depois de o grave passar (senão a pista perde o grave antes da hora); com o grave no fim, no fim.
        val fadeFrom = if (s < 1.0) maxOf(1 - fade, s) else 1 - fade
        return SwapShape(rise, s, fadeFrom)
    }

    /**
     * Os níveis automáticos dos dois canais a [relMs] do ponto de saída (negativo: antes), conforme o estilo, o ritmo
     * ([pace]) e, na troca de grave, o ponto em que o grave passa ([swapAt], 0 a 1, num início de compasso) e em quantos
     * compassos ([swapBars]: 0 de uma vez, N aos poucos terminando no ponto).
     */
    fun levels(
        style: MixStyle, relMs: Double, overlapMs: Double, barMs: Double, out: Levels,
        pace: Transition.Pace = Transition.Pace.NORMAL, swapAt: Double = 0.5, swapBars: Int = 0,
    ): Levels {
        val x = if (overlapMs <= 0) (if (relMs >= 0) 1.0 else 0.0) else (relMs / overlapMs).coerceIn(0.0, 1.0)
        out.aLow = 1f; out.aMid = 1f; out.aHigh = 1f; out.bLow = 1f; out.bMid = 1f; out.bHigh = 1f
        out.aFilter = 0.5f; out.bFilter = 0.5f; out.aEcho = 0f; out.bEcho = 0f
        // Corte seco: troca nos 10 ms antes do início do compasso, para não estalar (e o compasso já começa só com a outra).
        if (style == MixStyle.CUT && relMs >= -10) {
            val g = ((relMs + 10) / 10).coerceIn(0.0, 1.0).toFloat()
            out.aVol = 1 - g
            out.bVol = g
            return out
        }
        if (relMs < 0) {
            out.aVol = 1f; out.bVol = 0f
            if (style == MixStyle.BASS_SWAP || style == MixStyle.FILTER) out.bLow = 0f
            return out
        }
        /** 0 antes de [from], 1 depois de [to], subindo em linha reta entre os dois. */
        fun ramp(from: Double, to: Double) = if (to <= from) (if (x >= to) 1.0 else 0.0) else ((x - from) / (to - from)).coerceIn(0.0, 1.0)
        when (style) {
            MixStyle.BLEND -> {
                // Média: cruza a troca inteira. Rápida: cruza só num trecho curto no meio. Lenta: as duas juntas no meio
                // (a que entra sobe na primeira metade, a que sai só desce na segunda).
                val (bIn, aOut) = when (pace) {
                    Transition.Pace.NORMAL -> ramp(0.0, 1.0) to ramp(0.0, 1.0)
                    Transition.Pace.FAST -> ramp(0.375, 0.625) to ramp(0.375, 0.625)
                    Transition.Pace.SLOW -> ramp(0.0, 0.5) to ramp(0.5, 1.0)
                }
                out.aVol = cos(aOut * PI / 2).toFloat()
                out.bVol = sin(bIn * PI / 2).toFloat()
            }
            MixStyle.BASS_SWAP -> {
                val bars = if (overlapMs > 0) overlapMs / barMs else 1.0
                val sh = swapShape(bars, pace, swapAt)
                val s = if (overlapMs <= 0) 0.5 else sh.swap
                // A que entra: LOW fechado até a troca, HI e MID no meio-dia; quem sobe é o fader (potência constante).
                out.bVol = sin(ramp(0.0, sh.rise) * PI / 2).toFloat()
                // O grave: de uma vez no início do compasso, ou aos poucos em [swapBars] compassos que terminam no ponto (sem
                // espaço antes do ponto, começa na saída e termina mais adiante).
                val swap = if (swapBars <= 0 || overlapMs <= 0) {
                    if (x >= s) 1.0 else 0.0
                } else {
                    val g = (swapBars / bars).coerceAtMost(1.0)
                    val from = (s - g).coerceAtLeast(0.0)
                    ramp(from, (from + g).coerceAtMost(1.0))
                }
                out.aLow = (1 - swap).toFloat()
                out.bLow = swap.toFloat()
                // A que sai abre espaço: o HI desce até a metade e o MID um quarto enquanto a outra sobe, e mais um pouco
                // depois da troca (os agudos e médios das duas juntos embolam; o grave nunca fica duplo).
                val before = ramp(0.0, s)
                val after = ramp(s, 1.0)
                out.aHigh = ((1 - 0.5 * before) * (1 - 0.5 * after)).toFloat()
                out.aMid = ((1 - 0.25 * before) * (1 - 0.3 * after)).toFloat()
                out.aVol = if (relMs >= overlapMs) 0f else cos(ramp(sh.fadeFrom, 1.0) * PI / 2).toFloat()
            }
            MixStyle.FILTER -> {
                // Como a troca de grave, mas a que sai some num passa-alta que abre a troca inteira (do grave para cima): a
                // melodia dela afina e sai do caminho da outra.
                val bars = if (overlapMs > 0) overlapMs / barMs else 1.0
                val sh = swapShape(bars, pace, swapAt)
                out.bVol = sin(ramp(0.0, sh.rise) * PI / 2).toFloat()
                val swap = if (x >= sh.swap) 1.0 else 0.0
                out.aLow = (1 - swap).toFloat()
                out.bLow = swap.toFloat()
                out.aFilter = (0.5 + 0.45 * ramp(0.0, 1.0)).toFloat()
                out.aVol = if (relMs >= overlapMs) 0f else cos(ramp(sh.fadeFrom, 1.0) * PI / 2).toFloat()
            }
            MixStyle.CUT -> {
                // Troca seca no início do compasso, com 10 ms para não estalar.
                val g = ((relMs + 10) / 10).coerceIn(0.0, 1.0).toFloat()
                out.aVol = 1 - g
                out.bVol = g
            }
            MixStyle.ECHO -> {
                // A que sai para um compasso depois do ponto de saída e fica só o eco (uma batida, realimentado); a que
                // entra sobe no primeiro quarto (rápida: oitavo; lenta: metade).
                out.aVol = if (relMs < barMs) 1f else 0f
                out.echo = true
                val rise = when (pace) {
                    Transition.Pace.FAST -> 0.125
                    Transition.Pace.NORMAL -> 0.25
                    Transition.Pace.SLOW -> 0.5
                }
                out.bVol = ramp(0.0, rise).toFloat()
            }
        }
        return out
    }

    /**
     * Monta a prévia de uma transição: [leadMs] da faixa que sai antes do ponto de saída, os compassos juntos e [tailMs] da
     * que entra depois. A que entra toca no andamento do master, com o ponto de entrada caindo exatamente no ponto de
     * saída, batida com batida. Com Master Tempo (padrão) o tom fica; sem, muda junto, como um toca-discos. Lê e estica
     * as duas uma vez; a mixagem é feita depois ([MixPreview.mixdown] ou ao vivo, [MixPlayer]).
     */
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
        // Dois compassos a mais da que sai: o eco e a mão do DJ podem segurá-la depois da troca.
        // Com loop, só o trecho do loop (e um pouco depois, para emendar sem estalo).
        val aLoopTrack = if (t.aLoopBars > 0) (t.aLoopBars * SetPlanner.barMs(if (bpmA > 0) bpmA else master)).toInt() else 0
        val aLen = if (aLoopTrack > 0) (lead * speedA).toInt() + aLoopTrack + 600
        else ((lead + overlapMs + (barMs * 2).toInt()) * speedA).toInt() + 300
        // Cada passo em função própria e `var` reaproveitada: o trecho lido, o esticado e o com loop não ficam vivos
        // juntos (no app de depuração as variáveis locais seguram a memória até o fim da função).
        var sa = prepared(a, aStart, aLen, speedA, t.masterTempo) ?: return null
        val speed = if (master > 0 && bpmB > 0) master / bpmB else 1.0
        val bOutMs = overlapMs + tailMs
        // Um compasso da que entra antes do ponto de entrada e um depois do fim: folga para o sync à mão (até uma batida
        // a mais ou a menos e uns ms) sem ler de novo.
        val barB = SetPlanner.barMs(if (bpmB > 0) bpmB else master)
        val pre = min(barB.toInt(), t.inMs)
        var sb = prepared(b, t.inMs - pre, ((bOutMs + barMs) * speed).toInt() + pre + 300, speed, t.masterTempo) ?: return null
        val bEntry = ms((pre / speed).toInt())
        val leadFrames = ms(lead)
        val total = leadFrames + ms(bOutMs)
        // Os loops já entram nas faixas prontas (como um player em loop): a que sai repete o trecho depois do ponto de
        // saída até o fim; a que entra repete o começo dela as vezes pedidas e segue.
        if (t.aLoopBars > 0) sa = loop(sa, leadFrames, ms((t.aLoopBars * barMs).toInt()), 0, total + ms((2 * barMs).toInt()))
        if (t.bLoopBars > 0 && t.bLoopTimes > 1) sb = loop(sb, bEntry, ms((t.bLoopBars * barMs).toInt()), t.bLoopTimes, 0)
        // Sync fino: as grades alinham a batida, mas cada análise põe a grade a uma distância um pouco diferente do
        // ataque do bumbo; o que sobra (10–30 ms) embola o grave. Mede pelos ataques graves; a mixagem corrige, como no jog.
        val measured = phaseLag(sa, sb, leadFrames, ms(overlapMs), ms((2 * barMs).toInt()), bFrom = bEntry)
        var waveA: os.deckbridge.logic.ColorWave? = null
        var waveB: os.deckbridge.logic.ColorWave? = null
        if (waves) {
            // Cada deck com a própria onda, inteira (independente do fader), na base de tempo da prévia; a que entra sem sync.
            waveA = deckWave(sa, total, 0, 0)
            waveB = deckWave(sb, total, leadFrames, bEntry)
        }
        val stems = Stems(Stems.pack(sa.left, sa.frames), Stems.pack(sa.right, sa.frames), Stems.pack(sb.left, sb.frames), Stems.pack(sb.right, sb.frames), bEntry)
        return MixPreview(stems, t, lead, overlapMs, aStart, speed, barMs, master, speedA, measured, waveA, waveB, (total.toLong() * 1000 / RATE).toInt())
    }

    /** Lê um trecho e põe no andamento da prévia (esticando com Master Tempo, ou como toca-discos). */
    private fun prepared(r: SegmentReader, startMs: Int, lengthMs: Int, speed: Double, masterTempo: Boolean): Stereo? {
        val pcm = r.read(startMs, lengthMs) ?: return null
        return if (masterTempo) Stretch.tempo(resample(pcm, 1.0), speed) else resample(pcm, speed)
    }

    /** A onda de um deck na base de tempo da prévia: [s] começa no quadro [at] da prévia, a partir do quadro [from] dele. */
    private fun deckWave(s: Stereo, total: Int, at: Int, from: Int): os.deckbridge.logic.ColorWave {
        val mono = FloatArray(total) { val k = it - at + from; if (it >= at && k >= 0 && k < s.frames) 0.5f * (s.left[k] + s.right[k]) else 0f }
        return os.deckbridge.logic.wavesFromPcm(mono, RATE).rgbDetail
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
    fun phaseLag(sa: Stereo, sb: Stereo, leadFrames: Int, overlapFrames: Int, windowFrames: Int = RATE * 4, bFrom: Int = 0): Double? {
        if (overlapFrames < RATE * 2) return null
        val maxLag = (MAX_LAG_MS * RATE / 1000 / HOP).toInt() + 1
        val ea = lowOnset(sa, leadFrames, overlapFrames)
        val eb = lowOnset(sb, bFrom - maxLag * HOP, overlapFrames + 2 * maxLag * HOP)
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
    fun writeSequence(
        file: java.io.File, parts: List<() -> Stereo?>, gapMs: Int = 500, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {},
        /** Onde cada parte começa no arquivo (ms), com o índice dela: os capítulos do player. */
        starts: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
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
                starts(n, (frames * 1000 / RATE).toInt())
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

    /**
     * [s] com o trecho de [len] quadros a partir de [at] repetido: [times] vezes e depois o resto da faixa, ou (com
     * [times] 0) até completar [total] quadros. Cada emenda cruza em ~3 ms com o que viria depois do trecho, para não estalar.
     */
    fun loop(s: Stereo, at: Int, len: Int, times: Int, total: Int): Stereo {
        if (len <= 0 || at < 0 || at + len > s.frames) return s
        val n = if (times > 0) s.frames + len * (times - 1) else maxOf(total, at + len)
        val l = FloatArray(n)
        val r = FloatArray(n)
        val fade = minOf(128, len / 4)
        var o = 0
        fun copy(from: Int, count: Int) {
            val c = minOf(count, n - o, s.frames - from).coerceAtLeast(0)
            System.arraycopy(s.left, from, l, o, c)
            System.arraycopy(s.right, from, r, o, c)
            o += c
        }
        copy(0, at)
        var turn = 0
        while (o < n && (times == 0 || turn < times)) {
            val start = o
            copy(at, len)
            // A emenda: o começo desta volta cruza com o que viria depois do fim da volta anterior.
            if (turn > 0) for (k in 0 until fade) {
                val i = start + k
                if (i >= n) break
                val tail = at + len + k
                val w = k.toFloat() / fade
                val tl = if (tail < s.frames) s.left[tail] else 0f
                val tr = if (tail < s.frames) s.right[tail] else 0f
                l[i] = l[i] * w + tl * (1 - w)
                r[i] = r[i] * w + tr * (1 - w)
            }
            turn++
        }
        if (times > 0 && o < n) copy(at + len, n - o)
        return Stereo(l, r, RATE)
    }

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

/**
 * O eco da prévia do MIX segue as contas do ECHO do motor de performance (`echoFamily` em
 * `android/app/src/main/cpp/dsp.h`): realimentação 0,16 + 0,5 × quantidade (no máximo 0,82), mistura 0,1 + 0,58 ×
 * quantidade, atraso de 1 tempo. A prévia é offline e em Kotlin (o motor é C++ e o computador não o carrega); o
 * `EchoSpecTest` lê o dsp.h e falha se um lado mudar sem o outro. Ver ADR-0011.
 */
object EchoSpec {
    /**
     * A quantidade (0–1) do eco na prévia: alta, como numa saída com eco (no meio do botão o eco do motor some antes do
     * compasso seguinte, e a saída com eco é justamente segurar a que sai depois de ela parar).
     */
    const val PREVIEW_AMOUNT = 0.8f
    fun feedback(amount: Float) = minOf(0.82f, 0.16f + 0.5f * amount)
    fun wet(amount: Float) = 0.1f + 0.58f * amount
}
