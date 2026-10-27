package os.deckbridge.logic

/**
 * LEDs da DDJ-400: o estado que a tela monta. A regra que vira bytes está em C++ (`cpp/leds.h`), a mesma do Pi.
 * Os bytes vêm do bloco <outputs> do mapa pinado do Mixxx 2.5.6 e do script oficial
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

/** Índice do tamanho de beat loop dos pads (1/4 a 32 batidas, até 6%), ou -1. Regra em C++ (`cpp/leds.h`). */
fun beatLoopIndex(loopBeats: Double): Int = ControllerCore.beatLoopIndex(loopBeats)

/** Estado desejado de cada LED: chave = (status << 8) | nota, valor = velocidade, na ordem de envio. C++ (`cpp/leds.h`). */
fun ledTargets(state: LedState): Map<Int, Int> {
    val flat = ControllerCore.ledTargets(ControllerCore.pack(state))
    val out = LinkedHashMap<Int, Int>()
    for (i in 0 until flat.size / 2) out[flat[i * 2]] = flat[i * 2 + 1]
    return out
}

/** Guarda o que já foi enviado e devolve só as mensagens que mudaram. `resend` zera o cache (religou o cabo). */
class LedDiff {
    private val handle = ControllerCore.newLedDiff()

    fun resend() = ControllerCore.ledResend(handle)

    fun messages(state: LedState): List<ByteArray> {
        val bytes = ControllerCore.ledMessages(handle, ControllerCore.pack(state))
        return List(bytes.size / 3) { bytes.copyOfRange(it * 3, it * 3 + 3) }
    }
}

/**
 * Pads do SAMPLER (banda esquerda) acesos pelo RMX: as cenas 1–4 (que têm pad) enquanto seguradas e o modo do X-PAD
 * ligado (pads 5–8). As cenas 5–8 só existem na tela e não acendem pad nenhum.
 */
fun rmxSamplerPads(rmx: RmxState): Set<Int> = buildSet {
    if (!rmx.panel) return@buildSet
    if (rmx.holding && rmx.scene in 0..3) add(rmx.scene)
    if (rmx.xOn) add((4 + rmx.xMode).coerceIn(4, 7))
}
