package os.deckbridge.logic

data class RbTrack(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val keyName: String,
    val bpm: Double,
    val durationSeconds: Int,
    val sampleRate: Int,
    val filePath: String,
    val fileName: String,
    val analyzePath: String,
)

data class RbPlaylist(
    val id: Int,
    val parentId: Int,
    val name: String,
    val folder: Boolean,
    val sortOrder: Int,
    val trackIds: List<Int>,
)

data class RbHotCue(val index: Int, val timeMs: Int, val comment: String, val colorRgb: Int?)

data class RbMemoryCue(val timeMs: Int, val loopEndMs: Int?, val comment: String)

data class WaveBands(val low: ByteArray, val mid: ByteArray, val high: ByteArray) {
    val columns: Int get() = minOf(low.size, mid.size, high.size)
}

data class RbAnalysis(
    val beats: List<AnlzBeat>,
    val hotCues: List<RbHotCue>,
    val memoryCues: List<RbMemoryCue>,
    val mainCueMs: Int,
    val waveform: ByteArray,
    val bands: WaveBands? = null,
    val waveSource: String = "",
)

data class RbTag(val name: String, val category: String)

data class RbCatalog(
    val tracks: List<RbTrack>,
    val playlists: List<RbPlaylist>,
    val analyses: Map<Int, RbAnalysis>,
    val label: String,
    val simulated: Boolean,
    val oneLibraryPresent: Boolean,
    val oneLibraryReadable: Boolean,
    val oneLibraryNote: String,
    val oneLibraryContentRows: Int,
    val trackTags: Map<Int, List<RbTag>>,
    val warnings: List<String>,
    val error: String?,
)

interface ReadableVolume {
    val label: String
    val simulated: Boolean
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray?
}

data class MemoryVolume(
    val files: Map<String, ByteArray>,
    override val label: String,
    override val simulated: Boolean,
) : ReadableVolume {
    override fun exists(path: String): Boolean = read(path) != null

    override fun read(path: String): ByteArray? {
        files[path]?.let { return it }
        val key = files.keys.firstOrNull { it.equals(path, ignoreCase = true) } ?: return null
        return files[key]
    }
}

fun normalizeRekordboxPath(raw: String): String {
    var path = raw.replace('\\', '/').trim()
    val drive = path.indexOf(":/")
    if (drive in 0..2) path = path.substring(drive + 2)
    while (path.startsWith("/")) path = path.removePrefix("/")
    return path
}

fun loadRekordbox(volume: ReadableVolume, oneLibrary: OneLibraryOpener? = null): RbCatalog {
    val warnings = mutableListOf<String>()
    val pdbPath = listOf(
        "PIONEER/rekordbox/export.pdb",
        "rekordbox/export.pdb",
        "export.pdb",
    ).firstOrNull { volume.exists(it) }
    if (pdbPath == null) {
        return emptyCatalog(volume, "E_RB_EXPORT_NOT_FOUND", warnings)
    }
    val parsed = parseExportPdb(volume.read(pdbPath) ?: ByteArray(0))
    val db = parsed.database
    if (db == null) {
        return emptyCatalog(volume, parsed.error ?: "E_RB_UNSUPPORTED_VARIANT", warnings)
    }
    val extBytes = volume.read(sibling(pdbPath, "exportExt.pdb"))
    val ext = extBytes?.let(::parseExportExt)
    val trackTags = tagsByTrack(ext)
    if (extBytes != null && ext == null) {
        warnings += "exportExt.pdb visto e não interpretado nesta versão"
    }
    val onePath = sibling(pdbPath, "exportLibrary.db")
    val oneBytes = volume.read(onePath)
    val onePresent = oneBytes != null
    val opened = if (oneBytes != null && oneLibrary != null) {
        runCatching { oneLibrary.open(oneBytes) }.getOrNull()
    } else {
        null
    }
    val oneNote = when {
        !onePresent -> "exportLibrary.db ausente"
        opened != null -> {
            "exportLibrary.db aberto numa cópia local, somente leitura. ${opened.tables.size} tabelas. " +
                "Linhas em djmdContent: ${opened.contentRows}. Chave pública documentada, a mesma em toda exportação."
        }
        oneLibraryLooksLikePlainSqlite(oneBytes!!) -> {
            "exportLibrary.db é SQLite sem cifra, mas esta leitura não devolveu tabelas."
        }
        else -> {
            "exportLibrary.db encontrado. A documentação pública (pyrekordbox, MIT) descreve uma chave SQLCipher " +
                "compartilhada, sem depender de licença. Esta cópia não abriu. Nada foi gravado no pendrive."
        }
    }
    val artists = db.artists.associate { it.id to it.name }
    val albums = db.albums.associate { it.id to it.name }
    val genres = db.genres.associate { it.id to it.name }
    val keys = db.keys.associate { it.id to it.name }
    val tracks = db.tracks.map { row ->
        RbTrack(
            id = row.id,
            title = row.title.ifBlank { row.fileName },
            artist = artists[row.artistId].orEmpty(),
            album = albums[row.albumId].orEmpty(),
            genre = genres[row.genreId].orEmpty(),
            keyName = keys[row.keyId].orEmpty(),
            bpm = row.bpm,
            durationSeconds = row.durationSeconds,
            sampleRate = row.sampleRate,
            filePath = normalizeRekordboxPath(row.filePath),
            fileName = row.fileName,
            analyzePath = normalizeRekordboxPath(row.analyzePath),
        )
    }
    val byPlaylist = db.entries.groupBy { it.playlistId }
    val playlists = db.playlists.sortedWith(compareBy({ it.parentId }, { it.sortOrder }, { it.id })).map { row ->
        val ids = byPlaylist[row.id].orEmpty().sortedBy { it.position }.map { it.trackId }
        RbPlaylist(row.id, row.parentId, row.name, row.folder, row.sortOrder, ids)
    }
    val analyses = tracks.associate { track ->
        track.id to analysisFor(volume, track.analyzePath, warnings)
    }
    return RbCatalog(
        tracks = tracks,
        playlists = playlists,
        analyses = analyses,
        label = volume.label,
        simulated = volume.simulated,
        oneLibraryPresent = onePresent,
        oneLibraryReadable = opened != null,
        oneLibraryNote = oneNote,
        oneLibraryContentRows = opened?.contentRows ?: -1,
        trackTags = trackTags,
        warnings = warnings,
        error = null,
    )
}

fun activeLoop(analysis: RbAnalysis?): Pair<Int, Int>? {
    val cue = analysis?.memoryCues?.firstOrNull { it.loopEndMs != null && it.loopEndMs > it.timeMs } ?: return null
    return cue.timeMs to cue.loopEndMs!!
}

private fun tagsByTrack(ext: PdbExt?): Map<Int, List<RbTag>> {
    if (ext == null) return emptyMap()
    val categories = ext.tags.filter { it.category }.associate { it.id to it.name }
    val names = ext.tags.filter { !it.category }.associate { it.id to it }
    val grouped = mutableMapOf<Int, MutableList<RbTag>>()
    ext.tagTracks.forEach { link ->
        val tag = names[link.tagId] ?: return@forEach
        val bucket = grouped.getOrPut(link.trackId) { mutableListOf() }
        bucket += RbTag(tag.name, categories[tag.categoryId].orEmpty())
    }
    return grouped
}

private fun analysisFor(volume: ReadableVolume, path: String, warnings: MutableList<String>): RbAnalysis {
    if (path.isBlank()) return RbAnalysis(emptyList(), emptyList(), emptyList(), 0, ByteArray(0))
    val datPath = path
    val stem = if (path.endsWith(".DAT", ignoreCase = true)) path.dropLast(4) else path
    val datBytes = volume.read(datPath)
    if (datBytes == null && warnings.size < 40) warnings += "ANLZ ausente: $datPath"
    val extBytes = volume.read("$stem.EXT") ?: volume.read("$stem.2EX")
    val dat = datBytes?.let(::parseAnlz)
    val ext = extBytes?.let(::parseAnlz)
    val merged = mergeAnalysis(dat, ext)
    if (merged.issues.isNotEmpty() && warnings.size < 40) warnings += merged.issues.first()
    val hot = merged.cues.filter { it.hotIndex != null }.map {
        RbHotCue(it.hotIndex!!, it.timeMs, it.comment, it.colorRgb)
    }.sortedBy { it.index }
    val memory = merged.cues.filter { it.hotIndex == null }.sortedBy { it.timeMs }.map {
        RbMemoryCue(it.timeMs, it.loopEndMs, it.comment)
    }
    val main = memory.firstOrNull { it.loopEndMs == null }?.timeMs ?: 0
    return RbAnalysis(merged.beats, hot, memory, main, merged.waveform, merged.bands, merged.waveSource)
}

private fun sibling(pdbPath: String, name: String): String {
    val slash = pdbPath.lastIndexOf('/')
    return if (slash < 0) name else pdbPath.substring(0, slash + 1) + name
}

private fun emptyCatalog(volume: ReadableVolume, error: String, warnings: List<String>): RbCatalog {
    return RbCatalog(
        tracks = emptyList(),
        playlists = emptyList(),
        analyses = emptyMap(),
        label = volume.label,
        simulated = volume.simulated,
        oneLibraryPresent = false,
        oneLibraryReadable = false,
        oneLibraryNote = "",
        oneLibraryContentRows = -1,
        trackTags = emptyMap(),
        warnings = warnings,
        error = error,
    )
}

data class DecodedPcm(val left: FloatArray, val right: FloatArray, val sampleRate: Int) {
    val frameCount: Int get() = minOf(left.size, right.size)
    val stereo: Boolean get() = !left.contentEquals(right)
}

fun decodeWav(bytes: ByteArray): DecodedPcm? {
    if (bytes.size < 44) return null
    if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF") return null
    if (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WAVE") return null
    var channels = 0
    var rate = 0
    var bits = 0
    var dataAt = -1
    var dataLen = 0
    var cursor = 12
    while (cursor + 8 <= bytes.size) {
        val id = bytes.copyOfRange(cursor, cursor + 4).toString(Charsets.US_ASCII)
        val size = bytes.u32le(cursor + 4).toInt()
        if (size < 0 || cursor + 8 + size > bytes.size) return null
        val body = cursor + 8
        if (id == "fmt " && size >= 16) {
            val format = bytes.u16le(body)
            if (format != 1) return null
            channels = bytes.u16le(body + 2)
            rate = bytes.u32le(body + 4).toInt()
            bits = bytes.u16le(body + 14)
        } else if (id == "data") {
            dataAt = body
            dataLen = size
        }
        cursor += 8 + size + (size and 1)
    }
    if (channels !in 1..2 || rate <= 0 || bits != 16 || dataAt < 0) return null
    val frames = dataLen / (channels * 2)
    if (frames <= 0 || frames > 48000 * 60 * 10) return null
    val left = FloatArray(frames)
    val right = FloatArray(frames)
    var sample = dataAt
    for (frame in 0 until frames) {
        val first = bytes.u16le(sample).toShort().toInt() / 32768f
        sample += 2
        if (channels == 1) {
            left[frame] = first
            right[frame] = first
        } else {
            left[frame] = first
            right[frame] = bytes.u16le(sample).toShort().toInt() / 32768f
            sample += 2
        }
    }
    return DecodedPcm(left, right, rate)
}
