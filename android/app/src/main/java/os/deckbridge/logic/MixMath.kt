package os.deckbridge.logic

data class PerformanceAction(
    val deck: Int,
    val kind: Kind,
    val hotIndex: Int = -1,
    val slider: Double? = null,
) {
    enum class Kind { PLAY, CUE, HOT_CUE, VOLUME, CROSSFADER, PITCH, JOG }
}

fun masterGain(volume: Double, crossfader: Double, deckIsB: Boolean): Double {
    val side = if (deckIsB) crossfader else 1.0 - crossfader
    return (volume.coerceIn(0.0, 1.0) * side.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
}

fun syncPitch(ownBpm: Double, otherBpm: Double, otherPitch: Double): Double? {
    if (ownBpm <= 0.0 || otherBpm <= 0.0) return null
    val target = otherBpm * otherPitch
    if (target <= 0.0) return null
    return (target / ownBpm).coerceIn(0.5, 2.0)
}

fun performanceAction(hit: ControlHit, value: Int): PerformanceAction? {
    if (!hit.mapped) return null
    val deck = when (hit.group) {
        "[Channel1]" -> 0
        "[Channel2]" -> 1
        else -> -1
    }
    val slider = (value.coerceIn(0, 127) / 127.0)
    return when {
        hit.key == "play" && value > 0 && deck >= 0 -> PerformanceAction(deck, PerformanceAction.Kind.PLAY)
        hit.key == "cue_default" && value > 0 && deck >= 0 -> PerformanceAction(deck, PerformanceAction.Kind.CUE)
        hit.key.startsWith("hotcue_") && hit.key.endsWith("_activate") && value > 0 && deck >= 0 -> {
            val index = hit.key.removePrefix("hotcue_").substringBefore("_").toIntOrNull()?.minus(1) ?: return null
            PerformanceAction(deck, PerformanceAction.Kind.HOT_CUE, index)
        }
        hit.key == "volume" && deck >= 0 -> PerformanceAction(deck, PerformanceAction.Kind.VOLUME, slider = slider)
        hit.key == "crossfader" -> PerformanceAction(-1, PerformanceAction.Kind.CROSSFADER, slider = slider)
        hit.key == "PioneerDDJ400.jogTurn" && deck >= 0 -> {
            PerformanceAction(deck, PerformanceAction.Kind.JOG, slider = (value - 64) * JOG_BEND_SCALE)
        }
        hit.key == "PioneerDDJ400.jogSearch" && deck >= 0 -> {
            PerformanceAction(deck, PerformanceAction.Kind.JOG, slider = (value - 64) * JOG_SEEK_SCALE)
        }
        else -> null
    }
}

/** Faixas do script oficial da DDJ-400: 6%, 10%, 16% e 25%. */
val TEMPO_RANGES = doubleArrayOf(0.06, 0.10, 0.16, 0.25)

/** A faixa inicial do script oficial não é gravada no XML. 10% está na lista oficial. */
const val DEFAULT_TEMPO_RANGE = 0.10

/** Centro do prato em 64, igual ao script oficial. */
const val JOG_BEND_SCALE = 0.8

/** SHIFT+prato, igual ao script oficial. */
const val JOG_SEEK_SCALE = 150.0

fun nextTempoRange(current: Double): Double {
    val index = TEMPO_RANGES.indexOfFirst { kotlin.math.abs(it - current) < 1e-9 }
    return if (index < 0) TEMPO_RANGES[0] else TEMPO_RANGES[(index + 1) % TEMPO_RANGES.size]
}

/**
 * 14 bits do mapa oficial: full = (MSB << 7) + LSB.
 * rate = 1 - full/0x2000, centro em 0x2000. O multiplicador é 1 + rate * faixa.
 */
fun pitchFromTempo14(msb: Int, lsb: Int, range: Double = DEFAULT_TEMPO_RANGE): Double {
    val full = ((msb and 0x7F) shl 7) or (lsb and 0x7F)
    val rate = 1.0 - (full / 0x2000.toDouble())
    return (1.0 + rate * range).coerceIn(0.5, 2.0)
}

class TempoAssembler {
    private val msb = IntArray(2) { -1 }
    private val lsb = IntArray(2) { -1 }
    private val range = DoubleArray(2) { DEFAULT_TEMPO_RANGE }

    fun rangeOf(deck: Int): Double = if (deck in 0..1) range[deck] else DEFAULT_TEMPO_RANGE

    fun interpret(hit: ControlHit, value: Int): PerformanceAction? {
        if (!hit.mapped) return null
        val deck = when (hit.group) {
            "[Channel1]" -> 0
            "[Channel2]" -> 1
            else -> return null
        }
        return when {
            hit.key.endsWith("tempoSliderMSB") -> {
                msb[deck] = value and 0x7F
                null
            }
            hit.key.endsWith("tempoSliderLSB") -> {
                lsb[deck] = value and 0x7F
                pitchAction(deck)
            }
            hit.key.endsWith("cycleTempoRange") && value > 0 -> {
                range[deck] = nextTempoRange(range[deck])
                pitchAction(deck)
            }
            else -> null
        }
    }

    private fun pitchAction(deck: Int): PerformanceAction? {
        if (msb[deck] < 0 || lsb[deck] < 0) return null
        return PerformanceAction(deck, PerformanceAction.Kind.PITCH, slider = pitchFromTempo14(msb[deck], lsb[deck], range[deck]))
    }
}

fun interpretPerformance(hit: ControlHit, value: Int, tempo: TempoAssembler): PerformanceAction? {
    return tempo.interpret(hit, value) ?: performanceAction(hit, value)
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
