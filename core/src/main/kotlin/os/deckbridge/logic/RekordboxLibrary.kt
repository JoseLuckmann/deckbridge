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
    val artworkPath: String = "",
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

data class WaveBands(
    val low: ByteArray,
    val mid: ByteArray,
    val high: ByteArray,
    val loud: ByteArray = ByteArray(0),
) {
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
    val rgbDetail: ColorWave? = null,
    val rgbPreview: ColorWave? = null,
    val bandDetail: WaveBands? = null,
    val bandPreview: WaveBands? = null,
    val blueDetail: ByteArray = ByteArray(0),
    val bluePreview: ByteArray = ByteArray(0),
    val rgbApp: Boolean = false,
    val bandApp: Boolean = false,
    val blueApp: Boolean = false,
    val phrases: List<PhraseSpan> = emptyList(),
    val phraseMood: String = "",
    val loudnessLufs: Double = Double.NaN,
    val energy: Double = 0.0,
    val hotCueBanks: List<List<RbHotCue>> = emptyList(),
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
    val usbWaveColor: String? = null,
    val usbPlayhead: Float? = null,
    val usbWaveNote: String = "",
    val scanNote: String = "",
)

interface ReadableVolume {
    val label: String
    val simulated: Boolean
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray?
    fun listAudio(): List<String> = emptyList()
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

    override fun listAudio(): List<String> = files.keys.filter { isPlainAudioName(it.substringAfterLast('/')) }.sorted()
}

/**
 * Política só-leitura do pendrive: o seletor do sistema (exportar lista do set) deixa o usuário escolher qualquer
 * lugar, inclusive o pendrive da biblioteca. Bloqueia quando o destino está no mesmo armazenamento (mesmo provedor e
 * mesmo id de volume antes do ':' no id do documento) de um volume de biblioteca autorizado.
 */
fun exportTargetOnLibraryVolume(targetAuthority: String, targetDocId: String, libraries: List<Pair<String, String>>): Boolean {
    val storage = targetDocId.substringBefore(':', "")
    if (storage.isEmpty()) return false
    return libraries.any { (authority, treeDocId) -> authority == targetAuthority && treeDocId.substringBefore(':', "") == storage }
}

fun artworkCandidates(path: String): List<String> {
    val norm = normalizeRekordboxPath(path)
    if (norm.isBlank()) return emptyList()
    val dot = norm.lastIndexOf('.')
    val hi = if (dot > 0) norm.substring(0, dot) + "_m" + norm.substring(dot) else "${norm}_m"
    return listOf(hi, norm)
}

/** Lê a capa. Prefere o irmão `_m` quando o ficheiro existe. Não escreve no volume. */
fun readArtworkFile(volume: ReadableVolume, path: String): ByteArray? {
    for (candidate in artworkCandidates(path)) {
        val bytes = volume.read(candidate)
        if (bytes != null && bytes.isNotEmpty()) return bytes
    }
    return null
}

fun normalizeRekordboxPath(raw: String): String {
    var path = raw.replace('\\', '/').trim()
    val drive = path.indexOf(":/")
    if (drive in 0..2) path = path.substring(drive + 2)
    while (path.startsWith("/")) path = path.removePrefix("/")
    return path
}

/**
 * `loadRekordboxLight` é o modo do app: o catálogo guarda grade, cues, frases e as prévias, mas não as ondas de detalhe
 * (150 colunas por segundo, ~0,5 MB por faixa de 6 min; 1000 faixas ≈ 0,5 GB de heap). O detalhe da faixa que vai
 * para o deck é relido do ANLZ dela por `loadTrackDetail`.
 */
fun loadRekordboxLight(volume: ReadableVolume, oneLibrary: OneLibraryOpener? = null): RbCatalog = readRekordbox(volume, oneLibrary, detail = false)

fun loadRekordbox(volume: ReadableVolume, oneLibrary: OneLibraryOpener? = null): RbCatalog = readRekordbox(volume, oneLibrary, detail = true)

private fun readRekordbox(volume: ReadableVolume, oneLibrary: OneLibraryOpener?, detail: Boolean): RbCatalog {
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
    val artPaths = db.artworks.associate { it.id to normalizeRekordboxPath(it.path) }
    var missingArt = 0
    val tracks = db.tracks.map { row ->
        val art = if (row.artworkId == 0) "" else artPaths[row.artworkId].orEmpty()
        if (row.artworkId != 0 && art.isBlank() && missingArt < 8) {
            missingArt += 1
            warnings += "capa id ${row.artworkId} sem linha na tabela de artwork"
        }
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
            artworkPath = art,
        )
    }
    val byPlaylist = db.entries.groupBy { it.playlistId }
    val playlists = db.playlists.sortedWith(compareBy({ it.parentId }, { it.sortOrder }, { it.id })).map { row ->
        val ids = byPlaylist[row.id].orEmpty().sortedBy { it.position }.map { it.trackId }
        RbPlaylist(row.id, row.parentId, row.name, row.folder, row.sortOrder, ids)
    }
    val analyses = tracks.associate { track ->
        val full = analysisFor(volume, track, warnings)
        track.id to if (detail) full else full.withoutDetail()
    }
    val usbWave = readUsbWavePrefs(volume)
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
        usbWaveColor = usbWave.waveColor,
        usbPlayhead = usbWave.playhead,
        usbWaveNote = usbWave.note,
    )
}

fun activeLoop(analysis: RbAnalysis?): Pair<Int, Int>? {
    val cue = analysis?.memoryCues?.firstOrNull { it.loopEndMs != null && it.loopEndMs > it.timeMs } ?: return null
    return cue.timeMs to cue.loopEndMs!!
}

/** As My Tags de cada faixa (id da faixa → nome e coluna), do `exportExt.pdb` lido. Também a biblioteca do aparelho usa. */
fun tagsByTrack(ext: PdbExt?): Map<Int, List<RbTag>> {
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

/** Análise completa (com as ondas de detalhe) de uma faixa do pendrive, lida de novo do ANLZ dela. */
fun loadTrackDetail(volume: ReadableVolume, track: RbTrack): RbAnalysis = analysisFor(volume, track, ArrayList())

/** Mesma análise sem as ondas de detalhe; `bands` cai para a prévia quando era o próprio detalhe. */
fun RbAnalysis.withoutDetail(): RbAnalysis = copy(
    rgbDetail = null,
    bandDetail = null,
    blueDetail = ByteArray(0),
    bands = if (bands != null && bands === bandDetail) bandPreview else bands,
)

private fun analysisFor(volume: ReadableVolume, track: RbTrack, warnings: MutableList<String>): RbAnalysis {
    val path = track.analyzePath
    if (path.isBlank()) return RbAnalysis(emptyList(), emptyList(), emptyList(), 0, ByteArray(0))
    val stem = if (path.endsWith(".DAT", ignoreCase = true)) path.dropLast(4) else path
    val datBytes = volume.read(path)
    if (datBytes == null && warnings.size < 40) warnings += "ANLZ ausente: $path"
    val dat = datBytes?.let(::parseAnlz)
    val ext = volume.read("$stem.EXT")?.let(::parseAnlz)
    val ex2 = volume.read("$stem.2EX")?.let(::parseAnlz)
    val merged = mergeAnalysis(dat, ext, ex2)
    if (merged.issues.isNotEmpty() && warnings.size < 40) warnings += merged.issues.first()
    val filled = fillMissingWave(volume, track, merged)
    val hot = filled.merged.cues.filter { it.hotIndex != null }.map {
        RbHotCue(it.hotIndex!!, it.timeMs, it.comment, it.colorRgb)
    }.sortedBy { it.index }
    val memory = filled.merged.cues.filter { it.hotIndex == null }.sortedBy { it.timeMs }.map {
        RbMemoryCue(it.timeMs, it.loopEndMs, it.comment)
    }
    val main = memory.firstOrNull { it.loopEndMs == null }?.timeMs ?: 0
    val file = filled.merged
    return RbAnalysis(
        file.beats,
        hot,
        memory,
        main,
        file.waveform,
        file.bands,
        file.waveSource,
        file.rgbDetail,
        file.rgbPreview,
        file.bandDetail,
        file.bandPreview,
        file.blueDetail,
        file.bluePreview,
        rgbApp = filled.rgbApp,
        bandApp = filled.bandApp,
        blueApp = filled.blueApp,
        phrases = file.phrases,
        phraseMood = file.phraseMood,
        hotCueBanks = banksFromCues(hot),
    )
}

private data class FilledWave(val merged: AnlzFile, val rgbApp: Boolean, val bandApp: Boolean, val blueApp: Boolean)

private fun fillMissingWave(volume: ReadableVolume, track: RbTrack, merged: AnlzFile): FilledWave {
    val needRgb = merged.rgbDetail == null && merged.rgbPreview == null
    val needBand = merged.bandDetail == null && merged.bandPreview == null && merged.bands == null
    val needBlue = merged.blueDetail.isEmpty() && merged.bluePreview.isEmpty() && merged.waveform.isEmpty()
    if (!needRgb && !needBand && !needBlue) return FilledWave(merged, false, false, false)
    val pcm = volume.read(track.filePath)?.let(::decodeWavMono) ?: return FilledWave(merged, false, false, false)
    val built = wavesFromPcm(pcm.samples, pcm.rate)
    return FilledWave(
        merged.copy(
            rgbDetail = merged.rgbDetail ?: built.rgbDetail,
            rgbPreview = merged.rgbPreview ?: built.rgbPreview,
            bandDetail = merged.bandDetail ?: if (needBand) built.bandDetail else merged.bandDetail,
            bandPreview = merged.bandPreview ?: if (needBand) built.bandPreview else merged.bandPreview,
            bands = merged.bands ?: if (needBand) built.bandDetail else merged.bands,
            blueDetail = if (merged.blueDetail.isEmpty() && needBlue) built.blueDetail else merged.blueDetail,
            bluePreview = if (merged.bluePreview.isEmpty() && needBlue) built.bluePreview else merged.bluePreview,
            waveform = if (merged.waveform.isEmpty() && needBlue) built.bluePreview else merged.waveform,
            waveSource = if (merged.waveSource.isEmpty()) "APP" else merged.waveSource,
        ),
        rgbApp = needRgb,
        bandApp = needBand,
        blueApp = needBlue,
    )
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

data class DecodedPcm(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Int,
    /** Quadros válidos; os vetores podem ter folga no fim (evita uma cópia de dezenas de MB para aparar). */
    val frames: Int = minOf(left.size, right.size),
    /** A faixa passou do limite de 15 minutos e foi cortada. */
    val truncated: Boolean = false,
) {
    val frameCount: Int get() = minOf(frames, minOf(left.size, right.size))
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

/**
 * Só um trecho de um WAV PCM de 16 bits: `seconds` a partir de `startSec` (o fim da faixa corta antes). Para a
 * pré-escuta no fone: decodificar a faixa inteira custaria centenas de MB com os dois decks já carregados.
 */
fun decodeWavSegment(bytes: ByteArray, startSec: Double, seconds: Double): DecodedPcm? {
    if (bytes.size < 44 || seconds <= 0.0) return null
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
            if (bytes.u16le(body) != 1) return null
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
    val total = dataLen / (channels * 2)
    val first = (startSec.coerceAtLeast(0.0) * rate).toInt().coerceIn(0, total)
    val frames = minOf((seconds * rate).toInt(), total - first)
    if (frames <= 0) return null
    val left = FloatArray(frames)
    val right = FloatArray(frames)
    var sample = dataAt + first * channels * 2
    for (frame in 0 until frames) {
        val a = bytes.u16le(sample).toShort().toInt() / 32768f
        sample += 2
        left[frame] = a
        if (channels == 1) {
            right[frame] = a
        } else {
            right[frame] = bytes.u16le(sample).toShort().toInt() / 32768f
            sample += 2
        }
    }
    return DecodedPcm(left, right, rate)
}

fun encodeWavMono16(samples: FloatArray, rate: Int): ByteArray {
    val dataBytes = samples.size * 2
    val out = ByteArray(44 + dataBytes)
    "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
    out.putU32le(4, 36 + dataBytes)
    "WAVE".toByteArray(Charsets.US_ASCII).copyInto(out, 8)
    "fmt ".toByteArray(Charsets.US_ASCII).copyInto(out, 12)
    out.putU32le(16, 16)
    out.putU16le(20, 1)
    out.putU16le(22, 1)
    out.putU32le(24, rate)
    out.putU32le(28, rate * 2)
    out.putU16le(32, 2)
    out.putU16le(34, 16)
    "data".toByteArray(Charsets.US_ASCII).copyInto(out, 36)
    out.putU32le(40, dataBytes)
    samples.forEachIndexed { index, sample ->
        val raw = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
        out.putU16le(44 + index * 2, raw)
    }
    return out
}
