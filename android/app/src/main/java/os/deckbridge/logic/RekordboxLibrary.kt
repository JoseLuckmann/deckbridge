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

data class RbAnalysis(
    val beats: List<AnlzBeat>,
    val hotCues: List<RbHotCue>,
    val memoryCues: List<RbMemoryCue>,
    val mainCueMs: Int,
    val waveform: ByteArray,
)

data class RbCatalog(
    val tracks: List<RbTrack>,
    val playlists: List<RbPlaylist>,
    val analyses: Map<Int, RbAnalysis>,
    val label: String,
    val simulated: Boolean,
    val oneLibraryPresent: Boolean,
    val oneLibraryReadable: Boolean,
    val oneLibraryNote: String,
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

fun loadRekordbox(volume: ReadableVolume): RbCatalog {
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
    if (volume.exists(sibling(pdbPath, "exportExt.pdb"))) {
        warnings += "exportExt.pdb visto e não interpretado nesta versão"
    }
    val oneLibrary = volume.exists(sibling(pdbPath, "exportLibrary.db"))
    val oneNote = if (oneLibrary) {
        "exportLibrary.db encontrado. Não foi aberto: pode ser SQLCipher e abrir o arquivo criaria WAL. Somente leitura não inclui essa base nesta versão."
    } else {
        "exportLibrary.db ausente"
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
        track.id to analysisFor(volume, track.analyzePath)
    }
    return RbCatalog(
        tracks = tracks,
        playlists = playlists,
        analyses = analyses,
        label = volume.label,
        simulated = volume.simulated,
        oneLibraryPresent = oneLibrary,
        oneLibraryReadable = false,
        oneLibraryNote = oneNote,
        warnings = warnings,
        error = null,
    )
}

private fun analysisFor(volume: ReadableVolume, path: String): RbAnalysis {
    if (path.isBlank()) return RbAnalysis(emptyList(), emptyList(), emptyList(), 0, ByteArray(0))
    val datPath = path
    val extPath = when {
        path.endsWith(".DAT", ignoreCase = true) -> path.dropLast(4) + ".EXT"
        else -> "$path.EXT"
    }
    val dat = volume.read(datPath)?.let(::parseAnlz)
    val ext = volume.read(extPath)?.let(::parseAnlz)
    val merged = mergeAnalysis(dat, ext)
    val hot = merged.cues.filter { it.hotIndex != null }.map {
        RbHotCue(it.hotIndex!!, it.timeMs, it.comment, it.colorRgb)
    }.sortedBy { it.index }
    val memory = merged.cues.filter { it.hotIndex == null }.sortedBy { it.timeMs }.map {
        RbMemoryCue(it.timeMs, it.loopEndMs, it.comment)
    }
    val main = memory.firstOrNull { it.loopEndMs == null }?.timeMs ?: 0
    return RbAnalysis(merged.beats, hot, memory, main, merged.waveform)
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
        warnings = warnings,
        error = error,
    )
}

data class DecodedPcm(val mono: FloatArray, val sampleRate: Int)

fun decodeWavMono(bytes: ByteArray): DecodedPcm? {
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
    val mono = FloatArray(frames)
    var sample = dataAt
    for (frame in 0 until frames) {
        var sum = 0
        for (channel in 0 until channels) {
            val raw = bytes.u16le(sample).toShort().toInt()
            sum += raw
            sample += 2
        }
        mono[frame] = (sum / channels) / 32768f
    }
    return DecodedPcm(mono, rate)
}
