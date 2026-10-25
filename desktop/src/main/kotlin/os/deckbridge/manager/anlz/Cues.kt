package os.deckbridge.manager.anlz

/**
 * Um cue de uma faixa. [hot] 0 é cue de memória (ou loop de memória); 1 a 8 são os hot cues A a H.
 * [loopEndMs] não nulo faz dele um loop. [rgb] só existe nos hot cues (cor mostrada no player).
 */
data class Cue(val hot: Int, val timeMs: Int, val loopEndMs: Int? = null, val comment: String = "", val rgb: Int? = null) {
    val isLoop get() = loopEndMs != null
    val isHot get() = hot in 1..8
}

private const val PCPT_LEN = 56

/** Entradas de cue de um par DAT/EXT. Guarda os bytes de cada entrada para reaproveitar as que não mudam. */
class CueSet(val cues: List<Cue>, internal val datRaw: Map<Cue, ByteArray>, internal val extRaw: Map<Cue, ByteArray>)

object CueIo {
    /** Cues como o app os lê: os do EXT se houver, senão os do DAT (mesma regra de `mergeAnalysis`). */
    fun read(dat: AnlzModel?, ext: AnlzModel?): CueSet {
        val datEntries = dat?.all("PCOB")?.flatMap { readPcob(it) }.orEmpty()
        val extEntries = ext?.all("PCO2")?.flatMap { readPco2(it) }.orEmpty()
        val datMap = LinkedHashMap<Cue, ByteArray>()
        datEntries.forEach { (cue, raw) -> datMap.putIfAbsent(cue.copy(comment = "", rgb = null), raw) }
        val extMap = LinkedHashMap<Cue, ByteArray>()
        extEntries.forEach { (cue, raw) -> extMap.putIfAbsent(cue, raw) }
        val cues = (if (extEntries.isNotEmpty()) extEntries.map { it.first } else datEntries.map { it.first }).distinct()
        return CueSet(cues, datMap, extMap)
    }

    private fun readPcob(s: AnlzSection): List<Pair<Cue, ByteArray>> {
        val b = s.bytes
        if (b.size < 24) return emptyList()
        val count = b.be16(18)
        val out = ArrayList<Pair<Cue, ByteArray>>()
        var at = 24
        repeat(count) {
            if (at + PCPT_LEN > b.size) return out
            val len = b.be32(at + 8)
            if (String(b, at, 4, Charsets.US_ASCII) == "PCPT" && len >= PCPT_LEN) {
                val type = b[at + 28].toInt() and 0xFF
                val loop = b.be32(at + 36)
                out += Cue(b.be32(at + 12), b.be32(at + 32), if (type == 2) loop else null) to b.copyOfRange(at, at + len)
            }
            at += if (len >= PCPT_LEN) len else PCPT_LEN
        }
        return out
    }

    private fun readPco2(s: AnlzSection): List<Pair<Cue, ByteArray>> {
        val b = s.bytes
        if (b.size < 20) return emptyList()
        val count = b.be16(16)
        val out = ArrayList<Pair<Cue, ByteArray>>()
        var at = 20
        repeat(count) {
            if (at + 12 > b.size) return out
            val len = b.be32(at + 8)
            if (len < 44 || at + len > b.size) return out
            if (String(b, at, 4, Charsets.US_ASCII) == "PCP2") {
                val type = b[at + 16].toInt() and 0xFF
                val lenComment = b.be32(at + 40)
                var comment = ""
                var rgb: Int? = null
                if (lenComment in 0..4096 && at + 44 + lenComment <= at + len) {
                    comment = String(b, at + 44, lenComment, Charsets.UTF_16BE).trimEnd('\u0000')
                    val colorAt = at + 44 + lenComment
                    if (colorAt + 4 <= at + len) {
                        val r = b[colorAt + 1].toInt() and 0xFF
                        val g = b[colorAt + 2].toInt() and 0xFF
                        val bl = b[colorAt + 3].toInt() and 0xFF
                        if (r != 0 || g != 0 || bl != 0) rgb = (r shl 16) or (g shl 8) or bl
                    }
                }
                out += Cue(b.be32(at + 12), b.be32(at + 20), if (type == 2) b.be32(at + 24) else null, comment, rgb) to b.copyOfRange(at, at + len)
            }
            at += len
        }
        return out
    }

    private fun plainEntry(cue: Cue): ByteArray {
        val row = ByteArray(PCPT_LEN)
        "PCPT".toByteArray(Charsets.US_ASCII).copyInto(row)
        row.putBe32(4, 16)
        row.putBe32(8, PCPT_LEN)
        row.putBe32(12, cue.hot)
        row.putBe32(16, 1)
        row.putBe32(20, 0x10000)
        row.putBe16(24, 0xFFFF)
        row.putBe16(26, 0xFFFF)
        row[28] = if (cue.isLoop) 2 else 1
        row.putBe32(32, cue.timeMs)
        row.putBe32(36, cue.loopEndMs ?: 0)
        return row
    }

    private fun extendedEntry(cue: Cue): ByteArray {
        val comment = (cue.comment + "\u0000").toByteArray(Charsets.UTF_16BE)
        val len = 48 + comment.size
        val row = ByteArray(len)
        "PCP2".toByteArray(Charsets.US_ASCII).copyInto(row)
        row.putBe32(4, 16)
        row.putBe32(8, len)
        row.putBe32(12, cue.hot)
        row[16] = if (cue.isLoop) 2 else 1
        row.putBe32(20, cue.timeMs)
        row.putBe32(24, cue.loopEndMs ?: 0)
        row.putBe32(40, comment.size)
        comment.copyInto(row, 44)
        val rgb = cue.rgb ?: 0
        val at = 44 + comment.size
        row[at + 1] = (rgb shr 16).toByte()
        row[at + 2] = (rgb shr 8).toByte()
        row[at + 3] = rgb.toByte()
        return row
    }

    private fun pcob(type: Int, entries: List<ByteArray>): AnlzSection {
        val body = ByteArray(12 + entries.sumOf { it.size })
        body.putBe32(0, type)
        body.putBe16(6, entries.size)
        body.putBe32(8, entries.size)
        var at = 12
        entries.forEach { it.copyInto(body, at); at += it.size }
        return section("PCOB", body)
    }

    private fun pco2(type: Int, entries: List<ByteArray>): AnlzSection {
        val body = ByteArray(8 + entries.sumOf { it.size })
        body.putBe32(0, type)
        body.putBe16(4, entries.size)
        var at = 8
        entries.forEach { it.copyInto(body, at); at += it.size }
        return section("PCO2", body)
    }

    /**
     * Grava [cues] no par DAT/EXT. Entradas que já existiam e não mudaram voltam com os mesmos bytes; só as novas ou
     * alteradas são construídas. O DAT guarda memória e hot cues A–C (como o rekordbox); o EXT guarda tudo, com nomes e cores.
     */
    fun write(dat: AnlzModel, ext: AnlzModel?, current: CueSet, cues: List<Cue>): Pair<AnlzModel, AnlzModel> {
        val memory = cues.filter { !it.isHot }.sortedBy { it.timeMs }
        val hot = cues.filter { it.isHot }.sortedBy { it.hot }
        fun datBytes(c: Cue) = current.datRaw[c.copy(comment = "", rgb = null)] ?: plainEntry(c)
        fun extBytes(c: Cue) = current.extRaw[c] ?: extendedEntry(c)
        val newDat = dat.replacing(setOf("PCOB"), listOf(pcob(0, memory.map(::datBytes)), pcob(1, hot.filter { it.hot <= 3 }.map(::datBytes))), after = "PQTZ")
        val freshExt = listOf(pco2(0, memory.map(::extBytes)), pco2(1, hot.map(::extBytes)))
        val newExt = (ext ?: AnlzModel.fresh(emptyList())).replacing(setOf("PCO2"), freshExt)
        return newDat to newExt
    }
}
