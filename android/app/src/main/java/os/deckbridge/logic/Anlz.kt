package os.deckbridge.logic

data class AnlzBeat(val beatInBar: Int, val bpm: Double, val timeMs: Int)

data class AnlzCue(
    val hotIndex: Int?,
    val timeMs: Int,
    val loopEndMs: Int?,
    val comment: String,
    val colorRgb: Int?,
)

data class AnlzFile(
    val beats: List<AnlzBeat>,
    val cues: List<AnlzCue>,
    val waveform: ByteArray,
)

fun parseAnlz(bytes: ByteArray): AnlzFile {
    if (bytes.size < 12 || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "PMAI") {
        return AnlzFile(emptyList(), emptyList(), ByteArray(0))
    }
    val header = bytes.u32be(4).toInt()
    if (header < 12 || header > bytes.size) return AnlzFile(emptyList(), emptyList(), ByteArray(0))
    val beats = mutableListOf<AnlzBeat>()
    val cues = mutableListOf<AnlzCue>()
    var waveform = ByteArray(0)
    var cursor = header
    while (cursor + 12 <= bytes.size) {
        val tag = bytes.copyOfRange(cursor, cursor + 4).toString(Charsets.US_ASCII)
        val lenTag = bytes.u32be(cursor + 8).toInt()
        if (lenTag < 12 || cursor + lenTag > bytes.size) break
        val body = cursor + 12
        val bodyEnd = cursor + lenTag
        when (tag) {
            "PQTZ" -> beats += readBeats(bytes, body, bodyEnd)
            "PCOB" -> cues += readCues(bytes, body, bodyEnd)
            "PCO2" -> cues += readCues2(bytes, body, bodyEnd)
            "PWAV", "PWV2" -> waveform = readWave(bytes, body, bodyEnd)
        }
        cursor += lenTag
    }
    return AnlzFile(beats, cues, waveform)
}

private fun readBeats(bytes: ByteArray, at: Int, end: Int): List<AnlzBeat> {
    if (at + 12 > end) return emptyList()
    val count = bytes.u32be(at + 8).toInt()
    if (count < 0 || count > 100000) return emptyList()
    val out = ArrayList<AnlzBeat>(count.coerceAtMost(4096))
    var cursor = at + 12
    repeat(count) {
        if (cursor + 8 > end) return out
        out += AnlzBeat(
            beatInBar = bytes.u16be(cursor),
            bpm = bytes.u16be(cursor + 2) / 100.0,
            timeMs = bytes.u32be(cursor + 4).toInt(),
        )
        cursor += 8
    }
    return out
}

private fun readCues(bytes: ByteArray, at: Int, end: Int): List<AnlzCue> {
    if (at + 12 > end) return emptyList()
    val count = bytes.u16be(at + 6)
    var cursor = at + 12
    val out = mutableListOf<AnlzCue>()
    repeat(count) {
        if (cursor + 56 > end) return out
        if (bytes.copyOfRange(cursor, cursor + 4).toString(Charsets.US_ASCII) == "PCPT") {
            out += cueFrom(
                hot = bytes.u32be(cursor + 12).toInt(),
                type = bytes.u8(cursor + 28),
                timeMs = bytes.u32be(cursor + 32).toInt(),
                loopMs = bytes.u32be(cursor + 36).toInt(),
                comment = "",
                color = null,
            )
        }
        cursor += 56
    }
    return out
}

private fun readCues2(bytes: ByteArray, at: Int, end: Int): List<AnlzCue> {
    if (at + 8 > end) return emptyList()
    val count = bytes.u16be(at + 4)
    var cursor = at + 8
    val out = mutableListOf<AnlzCue>()
    repeat(count) {
        if (cursor + 12 > end) return out
        val lenEntry = bytes.u32be(cursor + 8).toInt()
        if (lenEntry < 40 || cursor + lenEntry > end) return out
        if (bytes.copyOfRange(cursor, cursor + 4).toString(Charsets.US_ASCII) != "PCP2") {
            cursor += lenEntry
            return@repeat
        }
        val hot = bytes.u32be(cursor + 12).toInt()
        val type = bytes.u8(cursor + 16)
        val timeMs = bytes.u32be(cursor + 20).toInt()
        val loopMs = bytes.u32be(cursor + 24).toInt()
        var comment = ""
        var color: Int? = null
        if (lenEntry > 43 && cursor + 44 <= end) {
            val lenComment = bytes.u32be(cursor + 40).toInt()
            val commentAt = cursor + 44
            if (lenComment in 0..4096 && commentAt + lenComment <= cursor + lenEntry) {
                comment = String(bytes, commentAt, lenComment, Charsets.UTF_16BE).trimEnd('\u0000')
                val colorAt = commentAt + lenComment
                if (lenEntry - lenComment > 47 && colorAt + 4 <= cursor + lenEntry) {
                    val red = bytes.u8(colorAt + 1)
                    val green = bytes.u8(colorAt + 2)
                    val blue = bytes.u8(colorAt + 3)
                    color = (red shl 16) or (green shl 8) or blue
                }
            }
        }
        out += cueFrom(hot, type, timeMs, loopMs, comment, color)
        cursor += lenEntry
    }
    return out
}

private fun cueFrom(hot: Int, type: Int, timeMs: Int, loopMs: Int, comment: String, color: Int?): AnlzCue {
    val loop = if (type == 2 && loopMs >= timeMs) loopMs else null
    val index = if (hot > 0) hot - 1 else null
    return AnlzCue(index, timeMs, loop, comment, color)
}

private fun readWave(bytes: ByteArray, at: Int, end: Int): ByteArray {
    if (at + 8 > end) return ByteArray(0)
    val len = bytes.u32be(at).toInt()
    val start = at + 8
    if (len <= 0 || start + len > end || len > 200000) return ByteArray(0)
    return bytes.copyOfRange(start, start + len)
}

fun mergeAnalysis(dat: AnlzFile?, ext: AnlzFile?): AnlzFile {
    val beats = dat?.beats?.ifEmpty { null } ?: ext?.beats.orEmpty()
    val extCues = ext?.cues.orEmpty()
    val cues = if (extCues.isNotEmpty()) extCues else dat?.cues.orEmpty()
    val wave = dat?.waveform?.takeIf { it.isNotEmpty() } ?: ext?.waveform ?: ByteArray(0)
    return AnlzFile(beats, cues, wave)
}
