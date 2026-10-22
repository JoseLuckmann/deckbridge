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
    val bands: WaveBands? = null,
    val waveSource: String = "",
    val issues: List<String> = emptyList(),
)

fun parseAnlz(bytes: ByteArray): AnlzFile {
    return try {
        parseAnlzBody(bytes)
    } catch (_: RuntimeException) {
        AnlzFile(emptyList(), emptyList(), ByteArray(0), issues = listOf("E_ANLZ_TRUNCATED"))
    }
}

private fun parseAnlzBody(bytes: ByteArray): AnlzFile {
    if (bytes.size < 12 || bytes.copyOfRange(0, 4).decodeToString() != "PMAI") {
        return AnlzFile(emptyList(), emptyList(), ByteArray(0), issues = listOf("E_ANLZ_MAGIC"))
    }
    val header = bytes.u32be(4).toInt()
    if (header < 12 || header > bytes.size) {
        return AnlzFile(emptyList(), emptyList(), ByteArray(0), issues = listOf("E_ANLZ_HEADER"))
    }
    val beats = mutableListOf<AnlzBeat>()
    val cues = mutableListOf<AnlzCue>()
    val issues = mutableListOf<String>()
    var waveform = ByteArray(0)
    var pwv7: WaveBands? = null
    var pwv5: WaveBands? = null
    var pwv3: WaveBands? = null
    var pwb3: WaveBands? = null
    var cursor = header
    while (cursor + 12 <= bytes.size) {
        val tag = bytes.copyOfRange(cursor, cursor + 4).decodeToString()
        val lenTag = bytes.u32be(cursor + 8).toInt()
        if (lenTag < 12 || cursor + lenTag > bytes.size) {
            issues += "E_ANLZ_SECTION"
            break
        }
        val body = cursor + 12
        val bodyEnd = cursor + lenTag
        when (tag) {
            "PQTZ" -> beats += readBeats(bytes, body, bodyEnd)
            "PCOB" -> cues += readCues(bytes, body, bodyEnd)
            "PCO2" -> cues += readCues2(bytes, body, bodyEnd)
            "PWAV", "PWV2" -> waveform = readWave(bytes, body, bodyEnd)
            "PWV5" -> pwv5 = readColorWave(bytes, body, bodyEnd)
            "PWV3" -> pwv3 = readColorWave(bytes, body, bodyEnd)
            "PWV7" -> pwv7 = readPwv7(bytes, body, bodyEnd)
            "PWB3" -> pwb3 = readBands(bytes, body, bodyEnd)
        }
        cursor += lenTag
    }
    val bands = pwv7 ?: pwv5 ?: pwv3 ?: pwb3
    val source = when {
        pwv7 != null -> "PWV7"
        pwv5 != null -> "PWV5"
        pwv3 != null -> "PWV3"
        pwb3 != null -> "PWB3"
        waveform.isNotEmpty() -> "PWAV"
        else -> ""
    }
    return AnlzFile(beats, cues, waveform, bands, source, issues)
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

private fun readBands(bytes: ByteArray, at: Int, end: Int): WaveBands? {
    if (at + 4 > end) return null
    val count = bytes.u32be(at).toInt()
    val start = at + 4
    if (count <= 0 || count > 4000 || start + count * 3 > end) return null
    val low = bytes.copyOfRange(start, start + count)
    val mid = bytes.copyOfRange(start + count, start + count * 2)
    val high = bytes.copyOfRange(start + count * 2, start + count * 3)
    return WaveBands(low, mid, high)
}

private fun readColorWave(bytes: ByteArray, at: Int, end: Int): WaveBands? {
    if (at + 8 > end) return null
    val len = bytes.u32be(at).toInt()
    val start = at + 8
    if (len < 2 || len % 2 != 0 || start + len > end || len > 400000) return null
    val low = ByteArray(len / 2)
    val mid = ByteArray(len / 2)
    val high = ByteArray(len / 2)
    for (index in low.indices) {
        val word = bytes.u16be(start + index * 2)
        if (word < 0) return null
        val height = ((word and 0x1F) * 255 / 31).toByte()
        when ((word shr 5) and 7) {
            0, 1 -> high[index] = height
            2, 3 -> mid[index] = height
            4, 5 -> {
                low[index] = height
                mid[index] = ((height.toInt() and 0xFF) / 2).toByte()
            }
            else -> low[index] = height
        }
    }
    return WaveBands(low, mid, high)
}

private fun readPwv7(bytes: ByteArray, at: Int, end: Int): WaveBands? {
    if (at + 8 > end) return null
    val len = bytes.u32be(at).toInt()
    val start = at + 8
    if (len < 3 || len % 3 != 0 || start + len > end || len > 600000) return null
    val count = len / 3
    val low = ByteArray(count)
    val mid = ByteArray(count)
    val high = ByteArray(count)
    for (index in 0 until count) {
        val atByte = start + index * 3
        low[index] = bytes[atByte]
        mid[index] = bytes[atByte + 1]
        high[index] = bytes[atByte + 2]
    }
    return WaveBands(low, mid, high)
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
    val left = dat?.bands
    val right = ext?.bands
    val bands = when {
        waveRank(dat?.waveSource) >= waveRank(ext?.waveSource) -> left ?: right
        else -> right ?: left
    }
    val source = when (bands) {
        left -> dat?.waveSource.orEmpty()
        right -> ext?.waveSource.orEmpty()
        else -> if (wave.isNotEmpty()) "PWAV" else ""
    }
    val issues = dat?.issues.orEmpty() + ext?.issues.orEmpty()
    return AnlzFile(beats, cues, wave, bands, source, issues)
}

private fun waveRank(source: String?): Int = when (source) {
    "PWV7" -> 4
    "PWV5" -> 3
    "PWV3" -> 2
    "PWB3" -> 1
    else -> 0
}
