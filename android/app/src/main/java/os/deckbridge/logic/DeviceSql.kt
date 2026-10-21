package os.deckbridge.logic

/**
 * Leitor DeviceSQL de export.pdb.
 *
 * O layout segue a descrição pública do formato (página fixa, índice de
 * linhas de trás para frente, strings device SQL). O código do Crate Digger,
 * do rekordcrate e do pyrekordbox não foi copiado.
 */
internal const val PAGE_HEADER_END = 40
private const val MAX_PDB_BYTES = 32 * 1024 * 1024
private const val MAX_PAGES = 20000

data class PdbTrack(
    val id: Int,
    val title: String,
    val artistId: Int,
    val albumId: Int,
    val genreId: Int,
    val keyId: Int,
    val bpm: Double,
    val durationSeconds: Int,
    val sampleRate: Int,
    val filePath: String,
    val fileName: String,
    val analyzePath: String,
)

data class PdbName(val id: Int, val name: String, val artistId: Int = 0)

data class PdbPlaylist(
    val id: Int,
    val parentId: Int,
    val name: String,
    val folder: Boolean,
    val sortOrder: Int,
)

data class PdbPlaylistEntry(val playlistId: Int, val trackId: Int, val position: Int)

data class PdbDatabase(
    val tracks: List<PdbTrack>,
    val artists: List<PdbName>,
    val albums: List<PdbName>,
    val genres: List<PdbName>,
    val keys: List<PdbName>,
    val playlists: List<PdbPlaylist>,
    val entries: List<PdbPlaylistEntry>,
)

data class PdbParse(val database: PdbDatabase?, val error: String?)

data class PdbTag(
    val id: Int,
    val name: String,
    val categoryId: Int,
    val position: Int,
    val category: Boolean,
)

data class PdbTagTrack(val trackId: Int, val tagId: Int)

data class PdbExt(val tags: List<PdbTag>, val tagTracks: List<PdbTagTrack>)

fun parseExportPdb(bytes: ByteArray): PdbParse {
    if (bytes.size < 28 || bytes.size > MAX_PDB_BYTES) {
        return PdbParse(null, "E_RB_UNSUPPORTED_VARIANT")
    }
    val pageLen = bytes.u32le(4).toInt()
    val numTables = bytes.u32le(8).toInt()
    if (pageLen < 128 || pageLen > 65536 || numTables < 1 || numTables > 64) {
        return PdbParse(null, "E_RB_UNSUPPORTED_VARIANT")
    }
    if (28L + numTables * 16L > bytes.size) {
        return PdbParse(null, "E_RB_UNSUPPORTED_VARIANT")
    }
    val tracks = mutableListOf<PdbTrack>()
    val artists = mutableListOf<PdbName>()
    val albums = mutableListOf<PdbName>()
    val genres = mutableListOf<PdbName>()
    val keys = mutableListOf<PdbName>()
    val playlists = mutableListOf<PdbPlaylist>()
    val entries = mutableListOf<PdbPlaylistEntry>()
    for (table in 0 until numTables) {
        val base = 28 + table * 16
        val type = bytes.u32le(base).toInt()
        val first = bytes.u32le(base + 8).toInt()
        val last = bytes.u32le(base + 12).toInt()
        readTable(bytes, pageLen, type, first, last) { rowAt, pageEnd ->
            when (type) {
                0 -> readTrack(bytes, rowAt, pageEnd)?.let(tracks::add)
                1 -> readNamed(bytes, rowAt, 4, pageEnd)?.let(genres::add)
                2 -> readArtist(bytes, rowAt, pageEnd)?.let(artists::add)
                3 -> readAlbum(bytes, rowAt, pageEnd)?.let(albums::add)
                5 -> readNamed(bytes, rowAt, 8, pageEnd)?.let(keys::add)
                7 -> readPlaylist(bytes, rowAt, pageEnd)?.let(playlists::add)
                8 -> readEntry(bytes, rowAt)?.let(entries::add)
            }
        }
    }
    return PdbParse(
        PdbDatabase(tracks, artists, albums, genres, keys, playlists, entries),
        null,
    )
}

private fun readTable(
    bytes: ByteArray,
    pageLen: Int,
    type: Int,
    first: Int,
    last: Int,
    onRow: (Int, Int) -> Unit,
) {
    var index = first
    val seen = HashSet<Int>()
    var guard = 0
    while (guard++ < MAX_PAGES && seen.add(index)) {
        val offset = pageOffset(index, pageLen, bytes.size) ?: break
        val pageType = bytes.u32le(offset + 8).toInt()
        if (pageType != type) break
        val pageEnd = offset + pageLen
        for (row in rowStarts(bytes, offset, pageLen)) onRow(row, pageEnd)
        if (index == last) break
        val next = bytes.u32le(offset + 12).toInt()
        if (next == index) break
        index = next
    }
}

private fun pageOffset(index: Int, pageLen: Int, fileSize: Int): Int? {
    if (index < 0) return null
    val offset = index.toLong() * pageLen.toLong()
    if (offset < 0 || offset + pageLen > fileSize) return null
    return offset.toInt()
}

private fun rowStarts(bytes: ByteArray, pageOffset: Int, pageLen: Int): List<Int> {
    if (pageOffset + PAGE_HEADER_END > bytes.size) return emptyList()
    val packed = bytes.u32le(pageOffset + 24)
    if (packed < 0) return emptyList()
    val numOffsets = (packed and 0x1FFF).toInt()
    val flags = ((packed shr 24) and 0xFF).toInt()
    if (numOffsets <= 0 || (flags and 0x40) != 0) return emptyList()
    val groups = (numOffsets - 1) / 16 + 1
    val found = mutableListOf<Int>()
    for (group in 0 until groups) {
        val base = pageOffset + pageLen - group * 0x24
        val flagsAt = base - 4
        if (flagsAt < pageOffset || flagsAt + 1 >= bytes.size) continue
        val present = bytes.u16le(flagsAt)
        val rows = minOf(16, numOffsets - group * 16)
        for (row in 0 until rows) {
            if ((present shr row) and 1 == 0) continue
            val pos = base - (6 + 2 * row)
            if (pos < pageOffset || pos + 1 >= bytes.size) continue
            val heapOfs = bytes.u16le(pos)
            if (heapOfs < 0) continue
            val start = pageOffset + PAGE_HEADER_END + heapOfs
            if (start in pageOffset until pageOffset + pageLen) found += start
        }
    }
    return found
}

private fun deviceString(bytes: ByteArray, at: Int, limit: Int): String {
    if (at < 0 || at >= bytes.size || at >= limit) return ""
    val kind = bytes.u8(at)
    if (kind == 0x40 || kind == 0x90) {
        if (at + 4 > bytes.size || at + 4 > limit) return ""
        val declared = bytes.u16le(at + 1)
        val textLen = declared - 4
        val start = at + 4
        if (textLen < 0 || start + textLen > bytes.size || start + textLen > limit) return ""
        val slice = bytes.copyOfRange(start, start + textLen)
        return if (kind == 0x90) {
            String(slice, Charsets.UTF_16LE).trimEnd('\u0000')
        } else {
            String(slice, Charsets.US_ASCII).trimEnd('\u0000')
        }
    }
    if (kind % 2 == 0) return ""
    val length = kind shr 1
    val textLen = length - 1
    if (textLen < 0 || at + 1 + textLen > bytes.size || at + 1 + textLen > limit) return ""
    return String(bytes, at + 1, textLen, Charsets.US_ASCII)
}

private fun readTrack(bytes: ByteArray, at: Int, pageEnd: Int): PdbTrack? {
    if (at + 136 > bytes.size) return null
    fun str(slot: Int): String {
        val ofs = bytes.u16le(at + 94 + slot * 2)
        if (ofs < 0) return ""
        return deviceString(bytes, at + ofs, pageEnd)
    }
    val tempo = bytes.u32le(at + 56)
    return PdbTrack(
        id = bytes.u32le(at + 72).toInt(),
        title = str(17),
        artistId = bytes.u32le(at + 68).toInt(),
        albumId = bytes.u32le(at + 64).toInt(),
        genreId = bytes.u32le(at + 60).toInt(),
        keyId = bytes.u32le(at + 32).toInt(),
        bpm = tempo / 100.0,
        durationSeconds = bytes.u16le(at + 84),
        sampleRate = bytes.u32le(at + 8).toInt(),
        filePath = str(20),
        fileName = str(19),
        analyzePath = str(14),
    )
}

private fun readArtist(bytes: ByteArray, at: Int, pageEnd: Int): PdbName? {
    if (at + 10 > bytes.size) return null
    val subtype = bytes.u16le(at)
    val nameAt = if (subtype and 0x04 != 0) bytes.u16le(at + 0x0A) else bytes.u8(at + 9)
    if (nameAt < 0) return null
    return PdbName(bytes.u32le(at + 4).toInt(), deviceString(bytes, at + nameAt, pageEnd))
}

private fun readAlbum(bytes: ByteArray, at: Int, pageEnd: Int): PdbName? {
    if (at + 22 > bytes.size) return null
    val subtype = bytes.u16le(at)
    val nameAt = if (subtype and 0x04 != 0) bytes.u16le(at + 0x16) else bytes.u8(at + 21)
    if (nameAt < 0) return null
    return PdbName(
        id = bytes.u32le(at + 12).toInt(),
        name = deviceString(bytes, at + nameAt, pageEnd),
        artistId = bytes.u32le(at + 8).toInt(),
    )
}

private fun readNamed(bytes: ByteArray, at: Int, stringAt: Int, pageEnd: Int): PdbName? {
    if (at + stringAt >= bytes.size) return null
    return PdbName(bytes.u32le(at).toInt(), deviceString(bytes, at + stringAt, pageEnd))
}

private fun readPlaylist(bytes: ByteArray, at: Int, pageEnd: Int): PdbPlaylist? {
    if (at + 20 >= bytes.size) return null
    return PdbPlaylist(
        id = bytes.u32le(at + 12).toInt(),
        parentId = bytes.u32le(at).toInt(),
        name = deviceString(bytes, at + 20, pageEnd),
        folder = bytes.u32le(at + 16) != 0L,
        sortOrder = bytes.u32le(at + 8).toInt(),
    )
}

fun parseExportExt(bytes: ByteArray): PdbExt? {
    if (bytes.size < 28 || bytes.size > MAX_PDB_BYTES) return null
    val pageLen = bytes.u32le(4).toInt()
    val numTables = bytes.u32le(8).toInt()
    if (pageLen < 128 || pageLen > 65536 || numTables < 1 || numTables > 64) return null
    if (28L + numTables * 16L > bytes.size) return null
    val tags = mutableListOf<PdbTag>()
    val tagTracks = mutableListOf<PdbTagTrack>()
    for (table in 0 until numTables) {
        val base = 28 + table * 16
        val type = bytes.u32le(base).toInt()
        val first = bytes.u32le(base + 8).toInt()
        val last = bytes.u32le(base + 12).toInt()
        readTable(bytes, pageLen, type, first, last) { rowAt, pageEnd ->
            when (type) {
                3 -> readTag(bytes, rowAt, pageEnd)?.let(tags::add)
                4 -> readTagTrack(bytes, rowAt)?.let(tagTracks::add)
            }
        }
    }
    return PdbExt(tags, tagTracks)
}

private fun readTag(bytes: ByteArray, at: Int, pageEnd: Int): PdbTag? {
    if (at + 0x20 > bytes.size) return null
    val subtype = bytes.u16le(at)
    val nameAt = if (subtype and 0x04 != 0) bytes.u16le(at + 0x1E) else bytes.u8(at + 0x1D)
    if (nameAt < 0) return null
    val name = deviceString(bytes, at + nameAt, pageEnd)
    if (name.isBlank()) return null
    return PdbTag(
        id = bytes.u32le(at + 0x14).toInt(),
        name = name,
        categoryId = bytes.u32le(at + 0x0C).toInt(),
        position = bytes.u32le(at + 0x10).toInt(),
        category = bytes.u32le(at + 0x18) != 0L,
    )
}

private fun readTagTrack(bytes: ByteArray, at: Int): PdbTagTrack? {
    if (at + 12 > bytes.size) return null
    return PdbTagTrack(trackId = bytes.u32le(at + 4).toInt(), tagId = bytes.u32le(at + 8).toInt())
}

private fun readEntry(bytes: ByteArray, at: Int): PdbPlaylistEntry? {
    if (at + 12 > bytes.size) return null
    return PdbPlaylistEntry(
        playlistId = bytes.u32le(at + 8).toInt(),
        trackId = bytes.u32le(at + 4).toInt(),
        position = bytes.u32le(at).toInt(),
    )
}
