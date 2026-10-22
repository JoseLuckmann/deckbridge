package os.deckbridge.logic

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Ficheiro de áudio solto. Não é uma tag do rekordbox. */
fun isPlainAudioName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".mp3") ||
        lower.endsWith(".wav") ||
        lower.endsWith(".flac") ||
        lower.endsWith(".aiff") ||
        lower.endsWith(".aif") ||
        lower.endsWith(".m4a") ||
        lower.endsWith(".aac")
}

fun plainAudioFiles(root: File, limit: Int = 300): List<File> {
    if (!root.isDirectory) return emptyList()
    val out = ArrayList<File>()
    fun walk(dir: File, depth: Int) {
        if (out.size >= limit || depth > 2) return
        val children = dir.listFiles() ?: return
        children.sortedBy { it.name.lowercase() }.forEach { child ->
            if (out.size >= limit) return@forEach
            if (child.name.startsWith(".")) return@forEach
            if (child.isDirectory) walk(child, depth + 1)
            else if (isPlainAudioName(child.name)) out += child
        }
    }
    walk(root, 0)
    return out
}

private const val PLAIN_ALGO = "0131"

fun analyzePlainPcm(samples: FloatArray, rate: Int): Pair<TrackRead, PcmWaves> {
    return analyzeTrack(samples, rate) to wavesFromPcm(samples, rate)
}

/**
 * Pasta sem export.pdb. Analisa uma faixa de cada vez, grava o resultado
 * numa pasta de cache do app e devolve o catálogo com BPM, tom e onda RGB.
 * O volume não é escrito.
 */
fun loadPlainFolder(
    volume: ReadableVolume,
    cacheDir: File,
    decode: (fileName: String, bytes: ByteArray) -> Pair<FloatArray, Int>?,
    cuePreset: String = "drop",
    onStep: (RbCatalog) -> Unit = {},
): RbCatalog {
    val paths = volume.listAudio()
    if (paths.isEmpty()) {
        return emptyPlain(volume, "E_RB_EXPORT_NOT_FOUND")
    }
    cacheDir.mkdirs()
    val tracks = paths.mapIndexed { index, path ->
        val name = path.substringAfterLast('/')
        RbTrack(
            id = index + 1,
            title = name.substringBeforeLast('.').ifBlank { name },
            artist = "",
            album = "",
            genre = "",
            keyName = "",
            bpm = 0.0,
            durationSeconds = 0,
            sampleRate = 0,
            filePath = path,
            fileName = name,
            analyzePath = "",
        )
    }
    val playlist = RbPlaylist(1, 0, "Ficheiros", false, 0, tracks.map { it.id })
    var catalog = RbCatalog(
        tracks = tracks,
        playlists = listOf(playlist),
        analyses = emptyMap(),
        label = volume.label,
        simulated = volume.simulated,
        oneLibraryPresent = false,
        oneLibraryReadable = false,
        oneLibraryNote = "Pasta de áudio sem export.pdb. A análise é deste app.",
        oneLibraryContentRows = -1,
        trackTags = emptyMap(),
        warnings = emptyList(),
        error = null,
        scanNote = "A analisar 0/${tracks.size}",
    )
    onStep(catalog)
    tracks.forEachIndexed { index, track ->
        val bytes = volume.read(track.filePath)
        val next = if (bytes == null) {
            catalog.copy(warnings = catalog.warnings + "Áudio ausente: ${track.filePath}")
        } else {
            val stamp = sha256(bytes)
            val cached = readPlainCache(File(cacheDir, "$PLAIN_ALGO-$stamp.txt"), cuePreset)
            val applied = if (cached != null) {
                cached
            } else {
                val pcm = decode(track.fileName, bytes)
                if (pcm == null) {
                    null
                } else {
                    val (read, waves) = analyzePlainPcm(pcm.first, pcm.second)
                    val fresh = packed(read, waves, pcm.second, cuePreset)
                    writePlainCache(File(cacheDir, "$PLAIN_ALGO-$stamp.txt"), fresh)
                    fresh
                }
            }
            if (applied == null) {
                catalog.copy(warnings = catalog.warnings + "Não decodifiquei ${track.fileName}")
            } else {
                val updated = track.copy(
                    bpm = applied.bpm,
                    keyName = applied.keyName,
                    durationSeconds = applied.seconds,
                    sampleRate = applied.rate,
                )
                val list = catalog.tracks.toMutableList()
                list[index] = updated
                val analyses = catalog.analyses.toMutableMap()
                analyses[track.id] = applied.analysis
                catalog.copy(tracks = list, analyses = analyses)
            }
        }
        val done = index + 1
        catalog = next.copy(scanNote = if (done == tracks.size) {
            "Pasta de áudio, sem export do rekordbox. $done faixas."
        } else {
            "A analisar $done/${tracks.size} · ${tracks[index].title}"
        })
        onStep(catalog)
    }
    return catalog
}

private data class PackedPlain(
    val bpm: Double,
    val keyName: String,
    val seconds: Int,
    val rate: Int,
    val analysis: RbAnalysis,
)

private fun packed(read: TrackRead, waves: PcmWaves, rate: Int, cuePreset: String): PackedPlain {
    val seconds = if (waves.rgbDetail.perSecond > 0.0) {
        (waves.rgbDetail.columns / waves.rgbDetail.perSecond).toInt().coerceAtLeast(1)
    } else {
        0
    }
    val cues = autoCues(read.phrases, read.beats, cuePreset)
    val analysis = RbAnalysis(
        beats = read.beats,
        hotCues = cues.first,
        memoryCues = cues.second,
        mainCueMs = cues.second.firstOrNull()?.timeMs ?: 0,
        waveform = waves.bluePreview,
        bands = waves.bandPreview,
        waveSource = "APP",
        rgbDetail = waves.rgbDetail,
        rgbPreview = waves.rgbPreview,
        bandDetail = waves.bandDetail,
        bandPreview = waves.bandPreview,
        blueDetail = waves.blueDetail,
        bluePreview = waves.bluePreview,
        rgbApp = true,
        bandApp = true,
        blueApp = true,
        phrases = read.phrases,
        phraseMood = "high",
        loudnessLufs = read.loudnessLufs,
        energy = read.energy,
        hotCueBanks = banksFromCues(cues.first),
    )
    return PackedPlain(read.bpm, read.keyName, seconds, rate, analysis)
}

private fun emptyPlain(volume: ReadableVolume, error: String): RbCatalog {
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
        warnings = emptyList(),
        error = error,
    )
}

private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

private fun writePlainCache(file: File, packed: PackedPlain) {
    val preview = packed.analysis.rgbPreview
    val lines = ArrayList<String>()
    lines += "plain-cache-2"
    lines += packed.bpm.toString()
    lines += packed.keyName
    lines += packed.seconds.toString()
    lines += packed.analysis.beats.joinToString(",") { it.timeMs.toString() }
    if (preview == null) {
        lines += "0"
        lines += ""
        lines += ""
    } else {
        lines += preview.columns.toString()
        lines += Base64.getEncoder().encodeToString(preview.height)
        val rgb = ByteArray(preview.columns * 3)
        for (index in 0 until preview.columns) {
            val color = preview.rgb[index]
            rgb[index * 3] = ((color shr 16) and 0xFF).toByte()
            rgb[index * 3 + 1] = ((color shr 8) and 0xFF).toByte()
            rgb[index * 3 + 2] = (color and 0xFF).toByte()
        }
        lines += Base64.getEncoder().encodeToString(rgb)
    }
    lines += packed.analysis.loudnessLufs.toString()
    lines += packed.analysis.energy.toString()
    lines += packed.analysis.phrases.joinToString("|") { phrase ->
        "${phrase.startMs},${phrase.endMs},${phrase.kind}"
    }
    file.writeText(lines.joinToString("\n"))
}

private fun readPlainCache(file: File, cuePreset: String): PackedPlain? {
    if (!file.isFile) return null
    val lines = file.readLines()
    if (lines.size < 7 || lines[0] != "plain-cache-2") return null
    val bpm = lines[1].toDoubleOrNull() ?: return null
    val keyName = lines[2]
    val seconds = lines[3].toIntOrNull() ?: return null
    val beats = if (lines[4].isBlank()) {
        emptyList()
    } else {
        lines[4].split(",").mapIndexedNotNull { index, raw ->
            val time = raw.toIntOrNull() ?: return@mapIndexedNotNull null
            AnlzBeat((index % 4) + 1, bpm, time)
        }
    }
    val columns = lines[5].toIntOrNull() ?: return null
    val preview = if (columns <= 0) {
        null
    } else {
        val height = Base64.getDecoder().decode(lines[6])
        val rgbBytes = if (lines.size > 7 && lines[7].isNotBlank()) Base64.getDecoder().decode(lines[7]) else ByteArray(0)
        if (height.size < columns || rgbBytes.size < columns * 3) return null
        val rgb = IntArray(columns) { index ->
            val red = rgbBytes[index * 3].toInt() and 0xFF
            val green = rgbBytes[index * 3 + 1].toInt() and 0xFF
            val blue = rgbBytes[index * 3 + 2].toInt() and 0xFF
            (red shl 16) or (green shl 8) or blue
        }
        ColorWave(height.copyOf(columns), rgb, 0.0, "APP")
    }
    val loudness = lines.getOrNull(8)?.toDoubleOrNull() ?: Double.NaN
    val energy = lines.getOrNull(9)?.toDoubleOrNull() ?: 0.0
    val phrases = lines.getOrNull(10).orEmpty().split("|").mapNotNull { piece ->
        if (piece.isBlank()) return@mapNotNull null
        val parts = piece.split(",")
        if (parts.size < 3) return@mapNotNull null
        val start = parts[0].toIntOrNull() ?: return@mapNotNull null
        val end = parts[1].toIntOrNull() ?: return@mapNotNull null
        PhraseSpan(start, end, parts[2], parts[2], "high")
    }
    val cues = autoCues(phrases, beats, cuePreset)
    val analysis = RbAnalysis(
        beats = beats,
        hotCues = cues.first,
        memoryCues = cues.second,
        mainCueMs = cues.second.firstOrNull()?.timeMs ?: 0,
        waveform = ByteArray(0),
        rgbPreview = preview,
        rgbApp = true,
        waveSource = "APP",
        phrases = phrases,
        phraseMood = "high",
        loudnessLufs = loudness,
        energy = energy,
        hotCueBanks = banksFromCues(cues.first),
    )
    return PackedPlain(bpm, keyName, seconds, 0, analysis)
}
