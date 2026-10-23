package os.deckbridge.logic

/**
 * LEDs da DDJ-400. Os bytes vêm do bloco <outputs> do mapa pinado do Mixxx 2.5.6 e do script oficial
 * (play 0x0B, cue 0x0C, sync 0x58, CUE de canal 0x54, pads de hot cue 0x00-0x07, pads de loop 0x60-0x67,
 * faixa carregada 0x9F, VU 0xB0/0xB1 0x02, luz do Beat FX 0x94 0x47). Não é captura da controladora.
 */
data class DeckLed(
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val sync: Boolean = false,
    val pfl: Boolean = false,
    /** Hot cues que existem neste deck, índices 0 a 7. */
    val hotCues: Set<Int> = emptySet(),
    /** Índice do tamanho do beat loop ativo em BEAT_LOOP_SIZES, ou -1. */
    val beatLoop: Int = -1,
    /** Pico linear do canal, 0 a 1 (antes do fader). */
    val peak: Float = 0f,
)

data class LedState(
    val decks: List<DeckLed> = listOf(DeckLed(), DeckLed()),
    val beatFxOn: Boolean = false,
    /** Pads de amostra da banda esquerda acesos (0 a 7), usados pelo RMX. */
    val samplerPads: Set<Int> = emptySet(),
)

val BEAT_LOOP_SIZES = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0)

/** Índice do tamanho de beat loop mais próximo (até 6%), ou -1 se o loop não é de um tamanho dos pads. */
fun beatLoopIndex(loopBeats: Double): Int {
    if (loopBeats <= 0.0) return -1
    val index = BEAT_LOOP_SIZES.indices.minByOrNull { kotlin.math.abs(BEAT_LOOP_SIZES[it] - loopBeats) } ?: return -1
    return if (kotlin.math.abs(BEAT_LOOP_SIZES[index] - loopBeats) / BEAT_LOOP_SIZES[index] <= 0.06) index else -1
}

private fun key(status: Int, note: Int): Int = (status shl 8) or note

/** Estado desejado de cada LED: chave = (status << 8) | nota, valor = velocidade. */
fun ledTargets(state: LedState): Map<Int, Int> {
    val out = LinkedHashMap<Int, Int>()
    fun lit(status: Int, note: Int, on: Boolean) {
        out[key(status, note)] = if (on) 0x7F else 0x00
    }
    for (deck in 0..1) {
        val d = state.decks.getOrElse(deck) { DeckLed() }
        lit(0x9F, deck, d.loaded)
        lit(0x90 + deck, 0x0B, d.playing)
        lit(0x90 + deck, 0x0C, d.loaded && !d.playing)
        lit(0x90 + deck, 0x58, d.sync)
        lit(0x90 + deck, 0x54, d.pfl)
        for (pad in 0..7) {
            for (status in intArrayOf(0x97 + 2 * deck, 0x98 + 2 * deck)) {
                lit(status, pad, pad in d.hotCues)
                lit(status, 0x60 + pad, pad == d.beatLoop)
            }
        }
        out[key(0xB0 + deck, 0x02)] = (d.peak * 150f).toInt().coerceIn(0, 127)
    }
    lit(0x94, 0x47, state.beatFxOn)
    lit(0x94, 0x43, state.beatFxOn)
    for (pad in 0..7) lit(0x97, 0x30 + pad, pad in state.samplerPads)
    return out
}

/** Guarda o que já foi enviado e devolve só as mensagens que mudaram. `resend` zera o cache (religou o cabo). */
class LedDiff {
    private val last = HashMap<Int, Int>()

    fun resend() = last.clear()

    fun messages(state: LedState): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for ((k, value) in ledTargets(state)) {
            if (last[k] == value) continue
            last[k] = value
            out += byteArrayOf((k shr 8).toByte(), (k and 0xFF).toByte(), value.toByte())
        }
        return out
    }
}
