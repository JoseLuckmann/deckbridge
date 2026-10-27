package os.deckbridge.logic

import kotlin.math.log10
import kotlin.math.pow

/** O que a mesa de mixagem da controladora está mandando agora. Knobs de 0 a 1 com o centro em 0,5. */
data class MixerState(
    val eq: List<List<Float>> = listOf(listOf(0.5f, 0.5f, 0.5f), listOf(0.5f, 0.5f, 0.5f)),
    val trim: List<Float> = listOf(0.5f, 0.5f),
    val volume: List<Float> = listOf(0.8f, 0.8f),
    val cross: Float = 0.5f,
    val headMix: Float = 0f,
    val headLevel: Float = 0.75f,
    /** Ganho de saída do app, 0..1 sobre −12 a +6 dB (0 dB em 0,667). */
    val masterLevel: Float = DEFAULT_MASTER_LEVEL,
    val filter: List<Int> = listOf(64, 64),
    val colorFx: Int = 5,
    /** Picos já convertidos para 0..1 da escala do medidor: canal 1, canal 2, master E, master D. */
    val levels: List<Float> = listOf(0f, 0f, 0f, 0f),
    /** CUE de canal (pré-escuta nos fones), canal 1 e canal 2. */
    val pfl: List<Boolean> = listOf(false, false),
)

/** Pico linear para a posição do medidor: −48 dBFS embaixo, 0 dBFS em cima. */
fun meterFraction(peak: Float): Float {
    if (peak <= 0f) return 0f
    val db = 20f * log10(peak)
    return ((db + 48f) / 48f).coerceIn(0f, 1f)
}

val EQ_BAND_NAMES = listOf("LOW", "MID", "HI")

/** Mesma curva do motor (`eqGainFromKnob` em dsp.h): kill à esquerda, 1 no centro, +6 dB à direita. */
fun eqGain(knob: Float): Float {
    val k = knob.coerceIn(0f, 1f)
    if (k <= 0.03f) return 0f
    if (k < 0.5f) return ((k - 0.03f) / 0.47f).pow(1.6f)
    return 10f.pow((k - 0.5f) * 2f * 6f / 20f)
}

fun eqDbLabel(knob: Float): String {
    val gain = eqGain(knob)
    if (gain <= 0f) return "KILL"
    val db = 20.0 * log10(gain.toDouble())
    return if (kotlin.math.abs(db) < 0.5) "0 dB" else "%+.0f dB".format(db)
}

/** TRIM, 0..1: −12 dB à esquerda, 0 dB no centro, +12 dB à direita (curva nossa). */
fun trimDbOfKnob(knob: Float): Float = (knob.coerceIn(0f, 1f) - 0.5f) * 24f

fun trimDbLabel(knob: Float): String {
    val db = trimDbOfKnob(knob)
    return if (kotlin.math.abs(db) < 0.5f) "0 dB" else "%+.0f dB".format(db)
}

/** HEADPHONES LEVEL: 0 dB em 0,75, ±12 dB em volta (curva nossa). */
fun headLevelDbLabel(knob: Float): String {
    val k = knob.coerceIn(0f, 1f)
    if (k <= 0.01f) return "−∞"
    val db = (k - 0.75f) * 24f
    return if (kotlin.math.abs(db) < 0.5f) "0 dB" else "%+.0f dB".format(db)
}

fun headMixLabel(mix: Float): String = when {
    mix < 0.08f -> "CUE"
    mix > 0.92f -> "MASTER"
    else -> "%d%%".format((mix * 100).toInt())
}

fun crossfaderLabel(cross: Float): String = when {
    cross < 0.04f -> "CH 1"
    cross > 0.96f -> "CH 2"
    kotlin.math.abs(cross - 0.5f) < 0.03f -> "CENTRO"
    cross < 0.5f -> "◀ CH 1"
    else -> "CH 2 ▶"
}

const val MASTER_MIN_DB = -12f
const val MASTER_MAX_DB = 6f

/** 0 dB: o padrão, para o app não mudar o nível de quem não mexe. */
const val DEFAULT_MASTER_LEVEL = 12f / 18f

fun masterGainDb(level: Float): Float = MASTER_MIN_DB + level.coerceIn(0f, 1f) * (MASTER_MAX_DB - MASTER_MIN_DB)

fun masterGainLinear(level: Float): Float = Math.pow(10.0, masterGainDb(level) / 20.0).toFloat()

fun masterLabel(level: Float): String = "%+.1f dB".format(masterGainDb(level))
