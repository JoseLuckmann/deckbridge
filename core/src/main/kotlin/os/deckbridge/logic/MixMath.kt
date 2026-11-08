package os.deckbridge.logic

data class PerformanceAction(
    val deck: Int,
    val kind: Kind,
    val hotIndex: Int = -1,
    val slider: Double? = null,
) {
    enum class Kind {
        PLAY, CUE, HOT_CUE, VOLUME, CROSSFADER, PITCH, JOG,
        BROWSE_MOVE, BROWSE_ENTER, BROWSE_BACK, LOAD,
        BEAT_FX, FILTER, QUANTIZE, SYNC, MASTER,
        BEAT_JUMP, BEAT_JUMP_PAGE, LOOP_IN, LOOP_OUT, RELOOP, LOOP_SCALE, BEAT_LOOP, RMX,
        EQ, TRIM, HEAD_MIX, HEAD_GAIN, PFL, START, REVERSE_HOLD, LOOP_ADJUST, MASTER_LEVEL, JOG_TOUCH,
    }
}

fun masterGain(volume: Double, crossfader: Double, deckIsB: Boolean): Double {
    // Curva aditiva (slow fade): no centro os dois canais ficam inteiros; o lado oposto fecha até o fim do curso.
    val side = if (deckIsB) 2.0 * crossfader else 2.0 * (1.0 - crossfader)
    return (volume.coerceIn(0.0, 1.0) * side.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
}

/** Fase de batida, 0 a 1, no ponto da faixa dado a grade (origem em segundos e BPM da grade). */
fun beatPhase(trackSec: Double, originSec: Double, gridBpm: Double): Double {
    if (gridBpm <= 1.0) return 0.0
    val beat = (trackSec - originSec) * gridBpm / 60.0
    return beat - kotlin.math.floor(beat)
}

/**
 * Quanto avançar (positivo) ou recuar (negativo), em segundos da faixa própria, para que a batida dela
 * caia junto com a do outro deck. Vale o caminho mais curto, no máximo meia batida.
 */
fun syncPhaseShiftSeconds(
    ownSec: Double, ownOrigin: Double, ownBpm: Double,
    otherSec: Double, otherOrigin: Double, otherBpm: Double,
): Double {
    if (ownBpm <= 1.0 || otherBpm <= 1.0) return 0.0
    var delta = beatPhase(otherSec, otherOrigin, otherBpm) - beatPhase(ownSec, ownOrigin, ownBpm)
    if (delta > 0.5) delta -= 1.0
    if (delta < -0.5) delta += 1.0
    return delta * 60.0 / ownBpm
}

fun syncPitch(ownBpm: Double, otherBpm: Double, otherPitch: Double): Double? {
    if (ownBpm <= 0.0 || otherBpm <= 0.0) return null
    val target = otherBpm * otherPitch
    if (target <= 0.0) return null
    return (target / ownBpm).coerceIn(0.5, 2.0)
}

/** Mesa, transporte, prato e pitch da DDJ-400. A regra está em C++ (`cpp/controller.h`), a mesma do Pi. */
fun performanceAction(hit: ControlHit, value: Int): PerformanceAction? = ControllerCore.action(0L, 2, hit, value)

/** A faixa inicial do script oficial não é gravada no XML. 10% está na lista oficial (6, 10, 16 e 25%, em
 *  `cpp/controller.h`, com as escalas do prato). A tela usa este valor de partida. */
const val DEFAULT_TEMPO_RANGE = 0.10

fun nextTempoRange(current: Double): Double = ControllerCore.nextTempoRange(current)

/**
 * 14 bits do mapa oficial: full = (MSB << 7) + LSB.
 * rate = 1 - full/0x2000, centro em 0x2000. O multiplicador é 1 + rate * faixa.
 */
fun pitchFromTempo14(msb: Int, lsb: Int, range: Double = DEFAULT_TEMPO_RANGE): Double = ControllerCore.pitchFromTempo14(msb, lsb, range)

/** Pitch de 14 bits (MSB + LSB) e TEMPO RANGE por deck. Estado e regra em C++ (`cpp/controller.h`). */
class TempoAssembler {
    internal val handle = ControllerCore.newTempo()

    fun rangeOf(deck: Int): Double = ControllerCore.tempoRange(handle, deck)

    fun setRange(deck: Int, value: Double) = ControllerCore.tempoSetRange(handle, deck, value)

    fun interpret(hit: ControlHit, value: Int): PerformanceAction? = ControllerCore.action(handle, 1, hit, value)
}

fun interpretPerformance(hit: ControlHit, value: Int, tempo: TempoAssembler): PerformanceAction? = ControllerCore.action(tempo.handle, 0, hit, value)

/**
 * Browse, Beat FX, filtro, quantize, loops, beat jump e pads. O sentido do encoder BROWSE não foi confirmado na
 * controladora: 1..63 avança, 65..127 recua (127 = um passo). Regra em C++ (`cpp/controller.h`).
 */
fun surfaceAction(hit: ControlHit, value: Int): PerformanceAction? = ControllerCore.action(0L, 3, hit, value)

/** Posição 0 = −faixa, 0,5 = centro, 1 = +faixa, pela mesma conta de 14 bits do MIDI. */
fun pitchFromFader(position: Double, range: Double = DEFAULT_TEMPO_RANGE): Double {
    val full = ((1.0 - position.coerceIn(0.0, 1.0)) * 16383.0).let { kotlin.math.round(it).toInt() }.coerceIn(0, 16383)
    return pitchFromTempo14((full shr 7) and 0x7F, full and 0x7F, range)
}

fun faderFromPitch(pitch: Double, range: Double = DEFAULT_TEMPO_RANGE): Double {
    if (range <= 0.0) return 0.5
    val rate = ((pitch - 1.0) / range).coerceIn(-1.0, 1.0)
    val full = (8192.0 * (1.0 - rate)).coerceIn(0.0, 16383.0)
    return (1.0 - full / 16383.0).coerceIn(0.0, 1.0)
}

fun tempoRangeLabel(range: Double): String = when {
    kotlin.math.abs(range - 0.06) < 1e-9 -> "±6"
    kotlin.math.abs(range - 0.10) < 1e-9 -> "±10"
    kotlin.math.abs(range - 0.16) < 1e-9 -> "±16"
    kotlin.math.abs(range - 0.25) < 1e-9 -> "WIDE"
    else -> "±${kotlin.math.round(range * 100).toInt()}"
}

fun jogSeconds(bend: Double): Double {
    return if (kotlin.math.abs(bend) >= 20.0) bend / 800.0 else bend / 160.0
}

/** Avança o playhead. Se o loop tem fim maior que o começo, o trecho recomeça. */
fun advancePlayhead(position: Double, step: Double, loopStart: Double, loopEnd: Double): Double {
    var next = position + step
    if (loopEnd > loopStart) {
        val length = loopEnd - loopStart
        if (next >= loopEnd) {
            var over = (next - loopEnd) % length
            if (over < 0) over += length
            next = loopStart + over
        }
    }
    return next
}

/** Passo do ajuste de loop com o prato: segundos por unidade de giro (o prato manda (valor-64) * 0,8). */
const val LOOP_ADJUST_STEP = 0.0025

/**
 * Move a entrada (which = 1) ou a saída (which = 2) do loop. A entrada fica pelo menos 10 ms antes da saída e a
 * saída pelo menos 10 ms depois da entrada, dentro da faixa. Devolve null se não há loop.
 */
fun adjustLoopPoint(start: Float, end: Float, which: Int, delta: Double, durationSec: Double): Pair<Float, Float>? {
    if (start < 0f || end <= start) return null
    val limit = if (durationSec > 0.0) durationSec.toFloat() else Float.MAX_VALUE
    return when (which) {
        // As faixas nunca ficam vazias: um loop que já passa do fim da faixa (ou com menos de 10 ms) não derruba o app.
        1 -> (start + delta.toFloat()).coerceIn(0f, (end - 0.010f).coerceAtLeast(0f)) to end
        2 -> start to (end + delta.toFloat()).coerceIn(start + 0.010f, limit.coerceAtLeast(start + 0.010f))
        else -> null
    }
}

/** Mostra a última falha de áudio por 15 s para o DJ saber que houve (e quantas desde que abriu). */
class GlitchWatch(private val holdMs: Long = 15_000L) {
    private var last = -1
    private var shownUntil = 0L

    /** `count` é o total do motor; devolve o aviso a mostrar, ou null. */
    fun update(count: Int, nowMs: Long): String? {
        if (last < 0) last = count
        if (count > last) {
            last = count
            shownUntil = nowMs + holdMs
        }
        return if (nowMs < shownUntil) "ÁUDIO: FALHAS ($count)" else null
    }
}

/** Pitch bend do prato fora do modo vinil: soma ao tempo base só enquanto o prato gira (volta quando para). */
fun jogBentPitch(base: Float, bend: Double): Float = (base + bend * 0.002).toFloat().coerceIn(0.5f, 2f)

/** Tempo sem passos do prato para soltar o bend e voltar ao tempo do fader. */
const val JOG_BEND_HOLD_MS = 150L
