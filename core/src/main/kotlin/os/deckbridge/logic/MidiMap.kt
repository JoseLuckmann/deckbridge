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
    /** Todos os controles do mapa oficial, na ordem do arquivo. */
    val all: List<ControlHit> get() = controls

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

/** Montador de mensagens MIDI. A regra (running status, SysEx, tempo real, system common, note-off) está em C++,
 *  `cpp/controller.h`, a mesma do Pi; esta classe só segura o estado nativo. */
class MidiAssembler {
    private val handle = ControllerCore.newAssembler()

    fun push(bytes: ByteArray): List<MidiMessage> {
        if (bytes.isEmpty()) return emptyList()
        val out = IntArray(bytes.size * 3 + 3)
        val count = ControllerCore.assemblerPush(handle, bytes, out)
        return List(count) { MidiMessage(out[it * 3], out[it * 3 + 1], out[it * 3 + 2]) }
    }
}

fun isPerformancePassthrough(hit: ControlHit): Boolean {
    return hit.mapped && hit.key in setOf("play", "cue_default", "start_play", "reverseroll")
}
