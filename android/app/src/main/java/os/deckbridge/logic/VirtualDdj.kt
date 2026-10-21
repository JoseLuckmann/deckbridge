package os.deckbridge.logic

/**
 * Sequências MIDI da DDJ-400 para teste. Os bytes saem do mapa oficial.
 * Não é uma controladora e não substitui a escuta no aparelho.
 */
class VirtualDdj400(private val map: MidiMap) {
    fun tempo(deck: Int, msb: Int, lsb: Int): List<ByteArray> {
        val group = group(deck)
        return listOf(
            packet(map.find(group, "PioneerDDJ400.tempoSliderMSB", "MSB"), msb),
            packet(map.find(group, "PioneerDDJ400.tempoSliderLSB", "LSB"), lsb),
        )
    }

    fun volume(deck: Int, value: Int): ByteArray {
        return packet(map.find(group(deck), "volume", "CHANNELFADER"), value)
    }

    fun crossfader(value: Int): ByteArray {
        return packet(map.find("[Master]", "crossfader", "CROSSFADER"), value)
    }

    fun hotCue(deck: Int, index: Int): ByteArray {
        val number = index + 1
        return packet(map.find(group(deck), "hotcue_${number}_activate", "PAD $number ("), 0x7F)
    }

    fun jogSide(deck: Int, value: Int): ByteArray {
        return packet(map.find(group(deck), "PioneerDDJ400.jogTurn", "JOG DIAL SIDE"), value)
    }

    fun play(deck: Int): ByteArray {
        return packet(map.find(group(deck), "play", "PLAY/PAUSE"), 0x7F)
    }

    private fun group(deck: Int): String = if (deck == 0) "[Channel1]" else "[Channel2]"

    private fun packet(hit: ControlHit, value: Int): ByteArray {
        return byteArrayOf(hit.status.toByte(), hit.midino.toByte(), (value and 0x7F).toByte())
    }
}

fun runMidiSequence(map: MidiMap, packets: List<ByteArray>, tempo: TempoAssembler = TempoAssembler()): List<PerformanceAction> {
    val assembler = MidiAssembler()
    val actions = mutableListOf<PerformanceAction>()
    packets.forEach { bytes ->
        assembler.push(bytes).forEach { message ->
            val hit = map.lookup(message.status, message.data1)
            interpretPerformance(hit, message.data2, tempo)?.let(actions::add)
        }
    }
    return actions
}
