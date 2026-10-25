package os.deckbridge.manager.anlz

import java.io.ByteArrayOutputStream

/**
 * Arquivos ANLZ (.DAT, .EXT, .2EX): cabeçalho `PMAI` e uma lista de seções (`PQTZ` grade, `PCOB`/`PCO2` cues, ondas, frases).
 * O layout segue a especificação aberta (Crate Digger, `rekordbox_anlz.ksy`, commit 7c9d535). O editor troca só as seções
 * que o usuário mexe e devolve as outras byte a byte.
 */
class AnlzFormatException(message: String) : Exception(message)

class AnlzSection(val tag: String, val bytes: ByteArray) {
    val headerLen: Int get() = bytes.be32(4)
    val body: ByteArray get() = bytes.copyOfRange(12, bytes.size)
}

class AnlzModel(val header: ByteArray, val sections: List<AnlzSection>) {
    fun all(tag: String) = sections.filter { it.tag == tag }
    fun first(tag: String) = sections.firstOrNull { it.tag == tag }

    fun write(): ByteArray {
        val out = ByteArrayOutputStream()
        val head = header.copyOf()
        val total = head.size + sections.sumOf { it.bytes.size }
        head.putBe32(8, total)
        out.write(head)
        sections.forEach { out.write(it.bytes) }
        return out.toByteArray()
    }

    /** Troca todas as seções de [tags] por [fresh], na posição da primeira que havia (ou depois de [after]/no começo). */
    fun replacing(tags: Set<String>, fresh: List<AnlzSection>, after: String? = null): AnlzModel {
        val at = sections.indexOfFirst { it.tag in tags }
        val rest = sections.filter { it.tag !in tags }
        val insertAt = when {
            at >= 0 -> sections.take(at).count { it.tag !in tags }
            after != null -> rest.indexOfLast { it.tag == after }.let { if (it >= 0) it + 1 else 0 }
            else -> 0
        }
        return AnlzModel(header, rest.take(insertAt) + fresh + rest.drop(insertAt))
    }

    companion object {
        fun parse(bytes: ByteArray): AnlzModel {
            if (bytes.size < 28 || String(bytes, 0, 4, Charsets.US_ASCII) != "PMAI") throw AnlzFormatException("não é um arquivo de análise (PMAI)")
            val headerLen = bytes.be32(4)
            val total = bytes.be32(8)
            if (headerLen < 12 || headerLen > bytes.size) throw AnlzFormatException("cabeçalho de análise inválido")
            if (total != bytes.size) throw AnlzFormatException("o tamanho declarado ($total) não bate com o arquivo (${bytes.size})")
            val sections = ArrayList<AnlzSection>()
            var cursor = headerLen
            while (cursor < bytes.size) {
                if (cursor + 12 > bytes.size) throw AnlzFormatException("seção truncada em $cursor")
                val len = bytes.be32(cursor + 8)
                if (len < 12 || cursor + len > bytes.size) throw AnlzFormatException("seção com tamanho inválido em $cursor")
                sections += AnlzSection(String(bytes, cursor, 4, Charsets.US_ASCII), bytes.copyOfRange(cursor, cursor + len))
                cursor += len
            }
            return AnlzModel(bytes.copyOf(headerLen), sections)
        }

        fun fresh(sections: List<AnlzSection>): AnlzModel {
            val head = ByteArray(28)
            "PMAI".toByteArray(Charsets.US_ASCII).copyInto(head)
            head.putBe32(4, 28)
            return AnlzModel(head, sections)
        }
    }
}

fun section(tag: String, body: ByteArray, headerLen: Int = 12): AnlzSection {
    val bytes = ByteArray(12 + body.size)
    tag.toByteArray(Charsets.US_ASCII).copyInto(bytes)
    bytes.putBe32(4, headerLen)
    bytes.putBe32(8, bytes.size)
    body.copyInto(bytes, 12)
    return AnlzSection(tag, bytes)
}

internal fun ByteArray.be16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)
internal fun ByteArray.be32(at: Int): Int = (be16(at) shl 16) or be16(at + 2)
internal fun ByteArray.putBe16(at: Int, v: Int) {
    this[at] = (v shr 8).toByte()
    this[at + 1] = v.toByte()
}
internal fun ByteArray.putBe32(at: Int, v: Int) {
    putBe16(at, v shr 16)
    putBe16(at + 2, v)
}
