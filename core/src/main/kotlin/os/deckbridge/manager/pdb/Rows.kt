package os.deckbridge.manager.pdb

/** Strings "device SQL": ASCII curta (tamanho ímpar embutido), ASCII longa (0x40) ou UTF-16LE (0x90). */
object DeviceString {
    private const val SHORT_MAX = 120

    fun encode(text: String): ByteArray {
        val ascii = text.all { it.code in 32..126 }
        if (ascii && text.length <= SHORT_MAX) {
            val kind = ((text.length + 1) shl 1) or 1
            return byteArrayOf(kind.toByte()) + text.toByteArray(Charsets.US_ASCII)
        }
        val payload = text.toByteArray(Charsets.UTF_16LE)
        val declared = payload.size + 4
        require(declared <= 0xFFFF) { "texto longo demais para uma string do banco" }
        return byteArrayOf(0x90.toByte(), declared.toByte(), (declared shr 8).toByte(), 0) + payload
    }

    /** Tamanho total da string que começa em [at], ou null se estiver truncada ou malformada. */
    fun size(bytes: ByteArray, at: Int, limit: Int): Int? {
        if (at < 0 || at >= limit || at >= bytes.size) return null
        val kind = bytes.u8(at)
        val total = when {
            kind == 0x40 || kind == 0x90 -> bytes.u16(at + 1)
            kind % 2 == 1 -> kind shr 1
            else -> return null
        }
        if (total < 1 || (kind == 0x40 || kind == 0x90) && total < 4) return null
        if (at + total > limit || at + total > bytes.size) return null
        return total
    }

    fun decode(bytes: ByteArray, at: Int, limit: Int): String {
        val total = size(bytes, at, limit) ?: return ""
        val kind = bytes.u8(at)
        return when (kind) {
            0x40 -> String(bytes, at + 4, total - 4, Charsets.US_ASCII).trimEnd('\u0000')
            0x90 -> String(bytes, at + 4, total - 4, Charsets.UTF_16LE).trimEnd('\u0000')
            else -> String(bytes, at + 1, total - 1, Charsets.US_ASCII)
        }
    }

    fun decode(blob: ByteArray): String = decode(blob, 0, blob.size)
}

object Rows {
    /**
     * Tamanho que a linha ocupa de fato, pelos seus próprios ponteiros. Null para tabelas cujo formato não conhecemos
     * (colunas, histórico etc.): essas voltam inteiras, até a linha seguinte.
     */
    fun extent(type: Int, bytes: ByteArray, at: Int, limit: Int): Int? {
        fun withString(fixed: Int): Int? = DeviceString.size(bytes, at + fixed, limit)?.let { fixed + it }
        return when (type) {
            PdbType.TRACKS -> TrackRow.extent(bytes, at, limit)
            PdbType.GENRES, PdbType.LABELS, PdbType.HISTORY_PLAYLISTS, PdbType.ARTWORK -> withString(4)
            PdbType.KEYS, PdbType.COLORS -> withString(8)
            PdbType.ARTISTS -> {
                val subtype = bytes.u16(at)
                val ofs = if (subtype and 0x04 != 0) bytes.u16(at + 10) else bytes.u8(at + 9)
                DeviceString.size(bytes, at + ofs, limit)?.let { ofs + it }
            }
            PdbType.ALBUMS -> {
                val subtype = bytes.u16(at)
                val ofs = if (subtype and 0x04 != 0) bytes.u16(at + 22) else bytes.u8(at + 21)
                DeviceString.size(bytes, at + ofs, limit)?.let { ofs + it }
            }
            PdbType.PLAYLIST_TREE -> withString(20)
            PdbType.PLAYLIST_ENTRIES, PdbType.HISTORY_ENTRIES -> 12
            else -> null
        }
    }
}

/** Linha de faixa (tipo 0). 136 bytes fixos, 21 offsets de string em 94 e as strings em seguida. */
class TrackRow private constructor(private val head: ByteArray, private val blobs: Array<ByteArray>) {
    var sampleRate: Int by u32(8)
    var composerId: Int by u32(12)
    var fileSize: Int by u32(16)
    var artworkId: Int by u32(28)
    var keyId: Int by u32(32)
    var originalArtistId: Int by u32(36)
    var labelId: Int by u32(40)
    var remixerId: Int by u32(44)
    var bitrate: Int by u32(48)
    var trackNumber: Int by u32(52)
    var tempo: Int by u32(56)
    var genreId: Int by u32(60)
    var albumId: Int by u32(64)
    var artistId: Int by u32(68)
    var id: Int by u32(72)
    var discNumber: Int by u16(76)
    var playCount: Int by u16(78)
    var year: Int by u16(80)
    var sampleDepth: Int by u16(82)
    var duration: Int by u16(84)
    var colorId: Int by u8(88)
    var rating: Int by u8(89)

    var dateAdded: String by text(10)
    var releaseDate: String by text(11)
    var mixName: String by text(12)
    var analyzePath: String by text(14)
    var analyzeDate: String by text(15)
    var comment: String by text(16)
    var title: String by text(17)
    var fileName: String by text(19)
    var filePath: String by text(20)

    fun encode(): ByteArray {
        // Nos exports reais, toda string longa (0x40 ou 0x90, a dos acentos) começa num deslocamento múltiplo de 4,
        // com zeros antes (conferido em 1.275 strings de 3.886 faixas).
        fun isLong(b: ByteArray) = b.isNotEmpty() && (b[0].toInt() and 0xFF).let { it == 0x40 || it == 0x90 }
        var size = HEAD
        for (b in blobs) size = (if (isLong(b)) align4(size) else size) + b.size
        val row = ByteArray(align4(size))
        head.copyInto(row)
        var cursor = HEAD
        blobs.forEachIndexed { slot, b ->
            if (isLong(b)) cursor = align4(cursor)
            row.putU16(94 + slot * 2, cursor)
            b.copyInto(row, cursor)
            cursor += b.size
        }
        return row
    }

    private fun u32(at: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = head.u32(at)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) = head.putU32(at, value)
    }

    private fun u16(at: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = head.u16(at)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) = head.putU16(at, value.coerceIn(0, 0xFFFF))
    }

    private fun u8(at: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = head.u8(at)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) {
            head[at] = value.coerceIn(0, 255).toByte()
        }
    }

    private fun text(slot: Int) = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = DeviceString.decode(blobs[slot])
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
            blobs[slot] = DeviceString.encode(value)
        }
    }

    companion object {
        const val HEAD = 136
        const val STRINGS = 21

        fun extent(bytes: ByteArray, at: Int, limit: Int): Int? {
            if (at + HEAD > limit || at + HEAD > bytes.size) return null
            var end = HEAD
            for (slot in 0 until STRINGS) {
                val ofs = bytes.u16(at + 94 + slot * 2)
                val size = DeviceString.size(bytes, at + ofs, limit) ?: continue
                if (ofs >= HEAD) end = maxOf(end, ofs + size)
            }
            return end
        }

        /**
         * Linha de faixa nova. Com [template] (uma faixa existente do mesmo banco), herda as constantes que o rekordbox
         * escreve em campos que não conhecemos e zera tudo o que é da faixa. Sem modelo, usa as constantes do formato.
         */
        fun blank(template: ByteArray?): TrackRow {
            val head = ByteArray(HEAD)
            val base = template?.takeIf { it.size >= HEAD }
            if (base != null) {
                base.copyInto(head, 0, 0, HEAD)
                // Campos da faixa: zerados. Os offsets das strings são refeitos em encode().
                for (at in listOf(8, 12, 16, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 68, 72)) head.putU32(at, 0)
                for (at in listOf(76, 78, 80, 82, 84)) head.putU16(at, 0)
                head[88] = 0
                head[89] = 0
            } else {
                head.putU16(0, 0x24)
                head.putU16(24, 19048)
                head.putU16(26, 30967)
                head.putU16(86, 41)
                head.putU16(90, 1)
                head.putU16(92, 2)
            }
            return TrackRow(head, Array(STRINGS) { DeviceString.encode("") })
        }

        fun parse(row: ByteArray): TrackRow? {
            if (row.size < HEAD) return null
            val blobs = Array(STRINGS) { slot ->
                val ofs = row.u16(94 + slot * 2)
                val size = DeviceString.size(row, ofs, row.size)
                if (size == null || ofs < HEAD) DeviceString.encode("") else row.copyOfRange(ofs, ofs + size)
            }
            return TrackRow(row.copyOf(HEAD), blobs)
        }
    }
}

/** Linhas simples de nome. `encode` refaz a linha inteira; os campos que não entendemos vêm da linha original. */
object NameRows {
    /** Gênero, selo e tom: o id vem logo no início da linha. */
    fun id(row: ByteArray): Int = row.u32(0)

    /** Artista: depois do subtipo e do `index_shift`. */
    fun artistId(row: ByteArray): Int = row.u32(4)

    fun artistName(row: ByteArray): String {
        val ofs = if (row.u16(0) and 0x04 != 0) row.u16(10) else row.u8(9)
        return DeviceString.decode(row, ofs, row.size)
    }

    private fun isLong(text: ByteArray) = (text[0].toInt() and 0xFF).let { it == 0x40 || it == 0x90 }

    /**
     * Artista novo, ou renomeado a partir de [like]. `indexShift` é 0x20 × a posição da linha na página (o gravador
     * de páginas o refaz). Nome curto (ASCII) começa no byte 10; nome longo (com acento, UTF-16) no 12, alinhado.
     */
    fun artist(id: Int, name: String, indexShift: Int, like: ByteArray? = null): ByteArray {
        val text = DeviceString.encode(name)
        val at = if (isLong(text)) 12 else 10
        val row = ByteArray(align4(at + text.size))
        row.putU16(0, 0x60)
        row.putU16(2, indexShift)
        row.putU32(4, id)
        row[8] = (like?.u8(8) ?: 0x03).toByte()
        row[9] = at.toByte()
        text.copyInto(row, at)
        return row
    }

    fun albumId(row: ByteArray): Int = row.u32(12)

    fun albumArtistId(row: ByteArray): Int = row.u32(8)

    fun albumName(row: ByteArray): String {
        val ofs = if (row.u16(0) and 0x04 != 0) row.u16(22) else row.u8(21)
        return DeviceString.decode(row, ofs, row.size)
    }

    fun album(id: Int, artistId: Int, name: String, indexShift: Int, like: ByteArray? = null): ByteArray {
        val text = DeviceString.encode(name)
        val at = if (isLong(text)) 24 else 22
        val row = ByteArray(align4(at + text.size))
        row.putU16(0, 0x80)
        row.putU16(2, indexShift)
        if (like != null && like.size >= 20) {
            row.putU32(4, like.u32(4))
            row.putU32(16, like.u32(16))
        }
        row.putU32(8, artistId)
        row.putU32(12, id)
        row[20] = (like?.u8(20) ?: 0x03).toByte()
        row[21] = at.toByte()
        text.copyInto(row, at)
        return row
    }

    /** Gênero (4) e selo (4): id e nome. */
    fun simpleName(row: ByteArray): String = DeviceString.decode(row, 4, row.size)

    fun simple(id: Int, name: String): ByteArray {
        val text = DeviceString.encode(name)
        val row = ByteArray(align4(4 + text.size))
        row.putU32(0, id)
        text.copyInto(row, 4)
        return row
    }

    fun keyName(row: ByteArray): String = DeviceString.decode(row, 8, row.size)

    fun key(id: Int, name: String): ByteArray {
        val text = DeviceString.encode(name)
        val row = ByteArray(align4(8 + text.size))
        row.putU32(0, id)
        row.putU32(4, id)
        text.copyInto(row, 8)
        return row
    }

    fun colorName(row: ByteArray): String = DeviceString.decode(row, 8, row.size)

    fun colorId(row: ByteArray): Int = row.u16(5)
}

object PlaylistRows {
    fun parent(row: ByteArray) = row.u32(0)
    fun sort(row: ByteArray) = row.u32(8)
    fun id(row: ByteArray) = row.u32(12)
    fun folder(row: ByteArray) = row.u32(16) != 0
    fun name(row: ByteArray) = DeviceString.decode(row, 20, row.size)

    fun tree(id: Int, parent: Int, name: String, folder: Boolean, sort: Int, like: ByteArray? = null): ByteArray {
        val text = DeviceString.encode(name)
        val row = ByteArray(align4(20 + text.size))
        row.putU32(0, parent)
        if (like != null) like.copyInto(row, 4, 4, 8)
        row.putU32(8, sort)
        row.putU32(12, id)
        row.putU32(16, if (folder) 1 else 0)
        text.copyInto(row, 20)
        return row
    }

    fun entryIndex(row: ByteArray) = row.u32(0)
    fun entryTrack(row: ByteArray) = row.u32(4)
    fun entryPlaylist(row: ByteArray) = row.u32(8)

    fun entry(index: Int, trackId: Int, playlistId: Int): ByteArray {
        val row = ByteArray(12)
        row.putU32(0, index)
        row.putU32(4, trackId)
        row.putU32(8, playlistId)
        return row
    }
}
