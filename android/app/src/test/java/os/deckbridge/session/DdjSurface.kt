package os.deckbridge.session

/**
 * Estado físico da DDJ-400 simulada, montado só com as mensagens MIDI que a "mão" manda (bytes do mapa pinado do
 * Mixxx 2.5.6, sem passar pela interpretação do app). Assim o painel mostra o que o equipamento estaria fazendo e a
 * tela mostra o que o app entendeu; diferença entre os dois é o que a validação procura.
 */
class DdjSurface {
    class Deck {
        /** Fader de tempo, 14 bits; 0 com o fader todo em cima (lado "−" do DDJ-400), 16383 todo embaixo ("+"). */
        var tempo = 8192
        var volume = 0
        var trim = 64
        var hi = 64
        var mid = 64
        var low = 64
        /** Ângulo do prato em graus. O script oficial conta 720 passos por volta (scratchEnable 720). */
        var jogAngle = 0.0
        var jogTouched = false
        var shift = false
        var padMode = PadMode.HOT_CUE
        val pressedAt = HashMap<String, Double>()
        val padAt = DoubleArray(8) { -10.0 }
    }

    enum class PadMode(val label: String) { HOT_CUE("HOT CUE"), BEAT_LOOP("BEAT LOOP"), BEAT_JUMP("BEAT JUMP"), SAMPLER("SAMPLER") }

    val decks = listOf(Deck(), Deck())
    var cross = 64
    val filter = intArrayOf(64, 64)
    var headMix = 64
    var headLevel = 64
    var fxLevel = 0
    /** 0 = CH1, 1 = CH2, 2 = MASTER (chave deslizante, guarda a última posição). */
    var fxChannel = 2
    var browseAngle = 0.0
    val pressedAt = HashMap<String, Double>()
    /** Últimas mensagens recebidas, a mais nova no fim: (tempo, texto). */
    val log = ArrayDeque<Pair<Double, String>>()

    fun lit(at: Double?, now: Double, hold: Double = 0.18): Boolean = at != null && now - at in 0.0..hold

    fun feed(t: Double, status: Int, data1: Int, data2: Int, description: String) {
        val on = data2 > 0
        val type = status and 0xF0
        val channel = status and 0x0F
        if (description.isNotBlank() && (on || type == 0xB0)) {
            val last = log.lastOrNull()
            val text = "%02X %02X %02X  %s".format(status, data1, data2, description)
            // Giros e faders mandam dezenas de mensagens: uma linha por controle.
            if (last != null && last.second.substring(0, 5) == text.substring(0, 5) && type == 0xB0) log.removeLast()
            log.addLast(t to text)
            while (log.size > 4) log.removeFirst()
        }
        when {
            // Botões dos decks (0x90/0x91).
            type == 0x90 && channel <= 1 -> {
                val d = decks[channel]
                when (data1) {
                    0x3F -> d.shift = on
                    0x36, 0x67 -> d.jogTouched = on
                    else -> if (on) d.pressedAt[deckButton(data1)] = t
                }
            }
            type == 0xB0 && channel <= 1 -> {
                val d = decks[channel]
                when (data1) {
                    0x00 -> d.tempo = (data2 shl 7) or (d.tempo and 0x7F)
                    0x20 -> d.tempo = (d.tempo and 0x3F80) or data2
                    0x13 -> d.volume = data2
                    0x04 -> d.trim = data2
                    0x07 -> d.hi = data2
                    0x0B -> d.mid = data2
                    0x0F -> d.low = data2
                    0x21, 0x22, 0x23, 0x29 -> d.jogAngle += (data2 - 64) * 360.0 / 720.0
                }
            }
            // Pads: 0x97/0x98 deck 1, 0x99/0x9A deck 2 (o modo de pad é do próprio hardware; vem da faixa de notas).
            status in 0x97..0x9A && on -> {
                val deck = if (status <= 0x98) 0 else 1
                val d = decks[deck]
                val pad = data1 and 0x07
                d.padMode = when (data1 and 0xF8) {
                    0x00 -> PadMode.HOT_CUE
                    0x60 -> PadMode.BEAT_LOOP
                    0x20 -> PadMode.BEAT_JUMP
                    0x30 -> PadMode.SAMPLER
                    else -> d.padMode
                }
                d.padAt[pad] = t
            }
            status == 0xB6 -> when (data1) {
                0x1F -> cross = data2
                0x17 -> filter[0] = data2
                0x18 -> filter[1] = data2
                0x0C -> headMix = data2
                0x0D -> headLevel = data2
                0x40 -> browseAngle += (if (data2 > 64) data2 - 128 else data2) * 15.0
            }
            status == 0x96 && on -> pressedAt[
                when (data1) {
                    0x46 -> "LOAD1"
                    0x47 -> "LOAD2"
                    else -> "BROWSE"
                },
            ] = t
            status == 0x94 && on -> when (data1) {
                0x10 -> fxChannel = 0
                0x11 -> fxChannel = 1
                0x14 -> fxChannel = 2
                0x4A -> pressedAt["BEAT_LEFT"] = t
                0x4B -> pressedAt["BEAT_RIGHT"] = t
                0x63, 0x64 -> pressedAt["FX_SELECT"] = t
                0x47, 0x43 -> pressedAt["FX_ON"] = t
            }
            status == 0xB4 && data1 == 0x02 -> fxLevel = data2
        }
    }

    private fun deckButton(note: Int): String = when (note) {
        0x0B, 0x47 -> "PLAY"
        0x0C, 0x48 -> "CUE"
        0x58, 0x5C, 0x60 -> "SYNC"
        0x10, 0x4C -> "LOOP_IN"
        0x11, 0x4E -> "LOOP_OUT"
        0x4D, 0x50 -> "RELOOP"
        0x51, 0x3E -> "CALL_LEFT"
        0x53, 0x3D -> "CALL_RIGHT"
        0x54 -> "PFL"
        0x68 -> "CUE"
        else -> "?"
    }
}
