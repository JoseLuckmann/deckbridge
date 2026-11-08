package os.deckbridge.logic

/**
 * Primeiro contato com a DDJ-400 real: cada mensagem do mapa oficial que chega é marcada como vista; o que chega e não
 * está no mapa vai para a lista de desconhecidas. Ver tudo marcado quer dizer que o mapa (Mixxx 2.5.6) bate com a
 * controladora; uma desconhecida quer dizer que algum controle manda bytes diferentes e precisa de remapeamento.
 * Entram no checklist só as mensagens sem SHIFT e o byte alto dos controles de 14 bits.
 */
class BringUpChecklist(map: MidiMap) {
    val expected: List<ControlHit> = map.all.filter { hit ->
        !hit.description.contains("SHIFT", ignoreCase = true) && !hit.key.endsWith("LSB") && !(hit.status in 0xB0..0xBF && hit.midino in 32..63 && map.all.any { it.status == hit.status && it.midino == hit.midino - 32 })
    }.distinctBy { key(it.status, it.midino) }
    private val expectedKeys = expected.map { key(it.status, it.midino) }.toSet()
    private val seen = HashSet<Int>()
    private val unknownCounts = LinkedHashMap<Int, Int>()

    /** Registra uma mensagem já traduzida pelo remapeamento. Devolve true se ela era desconhecida. */
    fun observe(status: Int, data1: Int, mapped: Boolean): Boolean {
        val k = key(status, data1)
        if (k in expectedKeys) seen += k
        if (mapped) return false
        unknownCounts[k] = (unknownCounts[k] ?: 0) + 1
        return true
    }

    val seenCount: Int get() = seen.size
    val missing: List<ControlHit> get() = expected.filter { key(it.status, it.midino) !in seen }
    val unknown: List<Triple<Int, Int, Int>> get() = unknownCounts.map { (k, n) -> Triple(k shr 8, k and 0xFF, n) }

    fun forget(status: Int, data1: Int) {
        unknownCounts.remove(key(status, data1))
    }

    fun summary(): String = buildString {
        append("Mapa: ${seenCount}/${expected.size} mensagens vistas")
        if (unknownCounts.isNotEmpty()) append(" · ${unknownCounts.size} desconhecida(s)")
    }

    fun report(remap: MidiRemap): String = buildString {
        appendLine(summary())
        if (unknownCounts.isNotEmpty()) {
            appendLine("Desconhecidas (status:nota ×vezes):")
            unknown.forEach { (st, d1, n) -> appendLine("  %s ×%d".format(midiPair(st, d1), n)) }
        }
        val left = missing
        if (left.isNotEmpty()) {
            appendLine("Faltam ${left.size}:")
            left.take(60).forEach { appendLine("  %s %s".format(midiPair(it.status, it.midino), it.description)) }
        }
        if (!remap.isEmpty()) appendLine("Remapeamento: ${remap.serialize()}")
    }

    private fun key(status: Int, data1: Int) = (status shl 8) or (data1 and 0xFF)
}

fun midiPair(status: Int, data1: Int): String = "0x%02X:0x%02X".format(status, data1)

/**
 * Correção de bytes da controladora sem recompilar: "0x9F:0x10=0x90:0x0B" faz a mensagem 9F/10 chegar ao app como a
 * 90/0B do mapa oficial (o valor não muda). Texto inválido é ignorado item a item.
 */
class MidiRemap private constructor(private val table: Map<Int, Int>) {
    fun apply(status: Int, data1: Int): Pair<Int, Int> {
        val to = table[(status shl 8) or data1] ?: return status to data1
        return (to shr 8) to (to and 0xFF)
    }

    fun with(fromStatus: Int, fromData1: Int, toStatus: Int, toData1: Int): MidiRemap =
        MidiRemap(table + (((fromStatus shl 8) or fromData1) to ((toStatus shl 8) or toData1)))

    fun isEmpty(): Boolean = table.isEmpty()

    fun serialize(): String = table.entries.sortedBy { it.key }.joinToString(";") { (from, to) -> "${midiPair(from shr 8, from and 0xFF)}=${midiPair(to shr 8, to and 0xFF)}" }

    companion object {
        val NONE = MidiRemap(emptyMap())

        fun parse(text: String): MidiRemap {
            val table = HashMap<Int, Int>()
            text.split(';', ',', '\n').forEach { item ->
                val sides = item.trim().split('=')
                if (sides.size != 2) return@forEach
                val from = pair(sides[0]) ?: return@forEach
                val to = pair(sides[1]) ?: return@forEach
                table[from] = to
            }
            return MidiRemap(table)
        }

        private fun pair(text: String): Int? {
            val parts = text.trim().split(':')
            if (parts.size != 2) return null
            val status = parts[0].trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return null
            val data1 = parts[1].trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return null
            if (status !in 0x80..0xEF || data1 !in 0..127) return null
            return (status shl 8) or data1
        }
    }
}
