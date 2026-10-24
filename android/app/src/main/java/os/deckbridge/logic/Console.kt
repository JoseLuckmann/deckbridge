package os.deckbridge.logic

/**
 * Controles de mixer que a DDJ-400 não tem e que o app oferece na tela, no estilo do DJM-V10: COMP por canal,
 * isolador do master, Send/Return interno e FX FREQUENCY do Beat FX. As curvas dos faders ficam nas preferências.
 * Tudo vai ao motor num vetor (`toArray`, mesma ordem de `MixerFx::setConsole` em dsp.h).
 */
data class ConsoleState(
    /** COMP de cada canal, 0..1 (0 = som original). */
    val comp: List<Float> = listOf(0f, 0f),
    val isolatorOn: Boolean = false,
    /** LOW, MID, HI do isolador do master, 0..1 (0 corta, 0,5 = 0 dB, 1 = +9 dB). */
    val isolator: List<Float> = listOf(0.5f, 0.5f, 0.5f),
    /** SEND de cada canal, 0..1. */
    val send: List<Float> = listOf(0f, 0f),
    /** Efeito do Send: -1 desligado, 0 SHORT DELAY, 1 LONG DELAY, 2 DUB ECHO, 3 REVERB. */
    val sendType: Int = -1,
    val sendSize: Float = 0.5f,
    val sendTime: Float = 0.5f,
    /** TONE do Send, 0..1 com o centro sem filtro. */
    val sendTone: Float = 0.5f,
    /** MASTER MIX LEVEL: volume do retorno no master. */
    val sendReturn: Float = 0.7f,
    /** FX FREQUENCY do Beat FX: LOW, MID, HI. Todos acesos = o efeito na faixa inteira. */
    val fxBands: List<Boolean> = listOf(true, true, true),
)

/** Curvas do fader de canal, na ordem do manual do DJM-V10 (p. 30). */
val CH_FADER_CURVES = listOf("TOPO", "GRADUAL", "LINEAR")

/** Curvas do crossfader: lenta (os dois inteiros no centro), média e corte. */
val CROSS_CURVES = listOf("LENTA", "MÉDIA", "CORTE")

val SEND_TYPES = listOf("SHORT DELAY", "LONG DELAY", "DUB ECHO", "REVERB")

fun ConsoleState.toArray(chCurve: Int, crossCurve: Int): FloatArray = floatArrayOf(
    chCurve.coerceIn(0, 2).toFloat(),
    crossCurve.coerceIn(0, 2).toFloat(),
    comp.getOrElse(0) { 0f },
    comp.getOrElse(1) { 0f },
    if (isolatorOn) 1f else 0f,
    isolator.getOrElse(0) { 0.5f },
    isolator.getOrElse(1) { 0.5f },
    isolator.getOrElse(2) { 0.5f },
    send.getOrElse(0) { 0f },
    send.getOrElse(1) { 0f },
    sendType.coerceIn(-1, 3).toFloat(),
    sendSize,
    sendTime,
    sendTone,
    sendReturn,
    if (fxBands.getOrElse(0) { true }) 1f else 0f,
    if (fxBands.getOrElse(1) { true }) 1f else 0f,
    if (fxBands.getOrElse(2) { true }) 1f else 0f,
)

/** Ganho do fader de canal pela curva (espelho de `channelFaderGain` em dsp.h, para a tela e os testes). */
fun channelFaderGain(fader: Float, curve: Int): Float {
    val v = fader.coerceIn(0f, 1f)
    return when (curve) {
        0 -> v * v * v
        1 -> 1f - (1f - v) * (1f - v)
        else -> v
    }
}

/** Texto do knob do isolador: CUT, 0 dB, +9 dB (mesma curva de `isolatorGainFromKnob`). */
fun isolatorDbLabel(knob: Float): String {
    val k = knob.coerceIn(0f, 1f)
    if (k <= 0.03f) return "CUT"
    val gain = if (k < 0.5f) Math.pow(((k - 0.03f) / 0.47f).toDouble(), 1.6) else Math.pow(10.0, (k - 0.5) * 2.0 * 9.0 / 20.0)
    val db = 20.0 * kotlin.math.log10(gain)
    return if (kotlin.math.abs(db) < 0.25) "0 dB" else "%+.1f dB".format(db)
}
