package os.deckbridge.logic

data class ControlHit(
    val status: Int,
    val midino: Int,
    val group: String,
    val key: String,
    val description: String,
    val mapped: Boolean,
)

data class MidiMessage(val status: Int, val data1: Int, val data2: Int)

class MidiMap(private val controls: List<ControlHit>) {
    fun find(group: String, key: String, descriptionContains: String): ControlHit {
        return controls.first { it.group == group && it.key == key && it.description.contains(descriptionContains) }
    }

    /** O byte alto de um controle de 14 bits (midino < 32); o baixo vem 32 notas acima. */
    fun findMsb(group: String, key: String, descriptionContains: String): ControlHit {
        return controls.first { it.group == group && it.key == key && it.description.contains(descriptionContains) && it.midino < 32 }
    }

    fun lookup(status: Int, data1: Int): ControlHit {
        val found = controls.find { it.status == status && it.midino == data1 }
        if (found != null) return found
        return ControlHit(status, data1, "", "", "sem nome no mapa oficial", false)
    }

    companion object {
        fun fromJson(text: String): MidiMap {
            val controls = mutableListOf<ControlHit>()
            var depth = 0
            var start = -1
            text.forEachIndexed { index, char ->
                when (char) {
                    '{' -> {
                        if (depth == 1) start = index
                        depth += 1
                    }
                    '}' -> {
                        if (depth == 2 && start >= 0) {
                            val obj = text.substring(start, index + 1)
                            if (obj.contains("\"status\"")) {
                                controls += ControlHit(
                                    status = jsonInt(obj, "status"),
                                    midino = jsonInt(obj, "midino"),
                                    group = jsonString(obj, "group"),
                                    key = jsonString(obj, "key"),
                                    description = jsonString(obj, "description"),
                                    mapped = true,
                                )
                            }
                            start = -1
                        }
                        depth -= 1
                    }
                }
            }
            return MidiMap(controls)
        }

        private fun jsonString(obj: String, key: String): String {
            val marker = "\"$key\": \""
            val from = obj.indexOf(marker)
            if (from < 0) return ""
            val start = from + marker.length
            return obj.substring(start, obj.indexOf('"', start))
        }

        private fun jsonInt(obj: String, key: String): Int {
            val marker = "\"$key\": "
            val from = obj.indexOf(marker)
            if (from < 0) return -1
            val start = from + marker.length
            val raw = obj.substring(start).takeWhile { it.isDigit() }
            return raw.toInt()
        }

        fun loadResource(): MidiMap {
            val stream = MidiMap::class.java.classLoader?.getResourceAsStream("ddj400-map.json")
                ?: error("mapa DDJ-400 ausente")
            return fromJson(stream.bufferedReader().readText())
        }
    }
}

class MidiAssembler {
    private var runningStatus: Int? = null
    private val pending = ArrayList<Int>()
    private var inSysEx = false

    fun push(bytes: ByteArray): List<MidiMessage> {
        val messages = mutableListOf<MidiMessage>()
        bytes.forEach { raw ->
            val value = raw.toInt() and 0xff
            if (inSysEx) {
                if (value == 0xF7) inSysEx = false
                return@forEach
            }
            if (value == 0xF0) {
                inSysEx = true
                pending.clear()
                return@forEach
            }
            if (value >= 0xF8) return@forEach
            // System common (0xF1..0xF7): cancela o running status e os dados que vierem depois são ignorados.
            if (value >= 0xF1) {
                runningStatus = null
                pending.clear()
                return@forEach
            }
            if (value >= 0x80) {
                runningStatus = value
                pending.clear()
                pending += value
                return@forEach
            }
            val status = runningStatus ?: return@forEach
            if (pending.isEmpty()) pending += status
            pending += value
            val needed = if (status in 0xC0..0xDF) 2 else 3
            if (pending.size >= needed) {
                val data2 = if (needed == 3) pending[2] else 0
                messages += MidiMessage(pending[0], pending[1], data2)
                pending.clear()
            }
        }
        return messages
    }
}

fun isPerformancePassthrough(hit: ControlHit): Boolean {
    return hit.mapped && hit.key in setOf("play", "cue_default", "start_play", "reverseroll")
}
