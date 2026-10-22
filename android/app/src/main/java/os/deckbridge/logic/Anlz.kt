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
    val rgbDetail: ColorWave? = null,
    val rgbPreview: ColorWave? = null,
    val bandDetail: WaveBands? = null,
    val bandPreview: WaveBands? = null,
    val blueDetail: ByteArray = ByteArray(0),
    val bluePreview: ByteArray = ByteArray(0),
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
    var pwv6: WaveBands? = null
    var pwv5: ColorWave? = null
    var pwv4: ColorWave? = null
    var pwv3 = ByteArray(0)
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
            "PWAV", "PWV2" -> if (waveform.isEmpty()) waveform = readWave(bytes, body, bodyEnd)
            "PWV5" -> pwv5 = readPwv5(bytes, cursor, bodyEnd)
            "PWV4" -> pwv4 = readPwv4(bytes, cursor, bodyEnd)
            "PWV3" -> pwv3 = readPwv3(bytes, cursor, bodyEnd)
            "PWV7" -> pwv7 = readPwv7(bytes, cursor, bodyEnd)
            "PWV6" -> pwv6 = readPwv6(bytes, cursor, bodyEnd)
            "PWVC" -> Unit
            "PWB3" -> pwb3 = readBands(bytes, body, bodyEnd)
        }
        cursor += lenTag
    }
    val bands = pwv7 ?: pwb3
    val source = when {
        pwv7 != null -> "PWV7"
        pwv5 != null -> "PWV5"
        pwv3.isNotEmpty() -> "PWV3"
        pwb3 != null -> "PWB3"
        waveform.isNotEmpty() -> "PWAV"
        else -> ""
    }
    val bluePreview = decodePreviewHeights(waveform)
    return AnlzFile(beats, cues, waveform, bands, source, issues, pwv5, pwv4, pwv7, pwv6, pwv3, bluePreview)
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

private fun readPwv5(bytes: ByteArray, at: Int, end: Int): ColorWave? {
    if (at + 24 > end) return null
    val entryBytes = bytes.u32be(at + 12).toInt()
    val count = bytes.u32be(at + 16).toInt()
    if (entryBytes != 2 || count <= 0 || count > 500_000) return null
    val start = at + 24
    if (start + count * 2 > end) return null
    val height = ByteArray(count)
    val rgb = IntArray(count)
    for (index in 0 until count) {
        val word = bytes.u16be(start + index * 2)
        if (word < 0) return null
        val decoded = decodePwv5Word(word)
        height[index] = decoded[0].toByte()
        rgb[index] = (decoded[1] shl 16) or (decoded[2] shl 8) or decoded[3]
    }
    return ColorWave(height, rgb, 150.0, "PWV5")
}

private fun readPwv4(bytes: ByteArray, at: Int, end: Int): ColorWave? {
    if (at + 24 > end) return null
    val entryBytes = bytes.u32be(at + 12).toInt()
    val count = bytes.u32be(at + 16).toInt()
    if (entryBytes != 6 || count <= 0 || count > 4000) return null
    val start = at + 24
    if (start + count * 6 > end) return null
    val height = ByteArray(count)
    val rgb = IntArray(count)
    for (index in 0 until count) {
        val column = start + index * 6
        val low = bytes.u8(column + 3)
        val mid = bytes.u8(column + 4)
        val high = bytes.u8(column + 5)
        if (low < 0 || mid < 0 || high < 0) return null
        height[index] = maxOf(low, mid, high).toByte()
        rgb[index] = blendRgb(low, mid, high)
    }
    return ColorWave(height, rgb, 0.0, "PWV4")
}

private fun readPwv3(bytes: ByteArray, at: Int, end: Int): ByteArray {
    if (at + 24 > end) return ByteArray(0)
    val entryBytes = bytes.u32be(at + 12).toInt()
    val count = bytes.u32be(at + 16).toInt()
    if (entryBytes != 1 || count <= 0 || count > 500_000) return ByteArray(0)
    val start = at + 24
    if (start + count > end) return ByteArray(0)
    return ByteArray(count) { index -> decodeMonoByte(bytes.u8(start + index)).toByte() }
}

private fun readPwv6(bytes: ByteArray, at: Int, end: Int): WaveBands? {
    if (at + 20 > end) return null
    val entryBytes = bytes.u32be(at + 12).toInt()
    val count = bytes.u32be(at + 16).toInt()
    if (entryBytes != 3 || count <= 0 || count > 4000) return null
    val start = at + 20
    if (start + count * 3 > end) return null
    return bandsMidHighLow(bytes, start, count)
}

private fun readPwv7(bytes: ByteArray, at: Int, end: Int): WaveBands? {
    if (at + 24 > end) return null
    val entryBytes = bytes.u32be(at + 12).toInt()
    val count = bytes.u32be(at + 16).toInt()
    if (entryBytes != 3 || count <= 0 || count > 500_000) return null
    val start = at + 24
    if (start + count * 3 > end) return null
    return bandsMidHighLow(bytes, start, count)
}

private fun bandsMidHighLow(bytes: ByteArray, start: Int, count: Int): WaveBands {
    val low = ByteArray(count)
    val mid = ByteArray(count)
    val high = ByteArray(count)
    for (index in 0 until count) {
        val atByte = start + index * 3
        mid[index] = bytes[atByte]
        high[index] = bytes[atByte + 1]
        low[index] = bytes[atByte + 2]
    }
    return WaveBands(low, mid, high)
}

private fun decodePreviewHeights(raw: ByteArray): ByteArray {
    if (raw.isEmpty()) return ByteArray(0)
    return ByteArray(raw.size) { index -> decodeMonoByte(raw[index].toInt()).toByte() }
}

private fun readWave(bytes: ByteArray, at: Int, end: Int): ByteArray {
    if (at + 8 > end) return ByteArray(0)
    val len = bytes.u32be(at).toInt()
    val start = at + 8
    if (len <= 0 || start + len > end || len > 200000) return ByteArray(0)
    return bytes.copyOfRange(start, start + len)
}

fun mergeAnalysis(dat: AnlzFile?, ext: AnlzFile?, ex2: AnlzFile? = null): AnlzFile {
    val beats = dat?.beats?.ifEmpty { null } ?: ext?.beats?.ifEmpty { null } ?: ex2?.beats.orEmpty()
    val extCues = ext?.cues.orEmpty()
    val cues = if (extCues.isNotEmpty()) extCues else dat?.cues.orEmpty()
    val wave = dat?.waveform?.takeIf { it.isNotEmpty() } ?: ext?.waveform ?: ex2?.waveform ?: ByteArray(0)
    val rgbDetail = ext?.rgbDetail ?: dat?.rgbDetail ?: ex2?.rgbDetail
    val rgbPreview = ext?.rgbPreview ?: dat?.rgbPreview ?: ex2?.rgbPreview
    val bandDetail = ex2?.bandDetail ?: ext?.bandDetail ?: dat?.bandDetail
    val bandPreview = ex2?.bandPreview ?: ext?.bandPreview ?: dat?.bandPreview
    val blueDetail = listOf(ext?.blueDetail, dat?.blueDetail, ex2?.blueDetail).firstOrNull { it?.isNotEmpty() == true } ?: ByteArray(0)
    val bluePreview = decodePreviewHeights(wave)
    val bands = bandDetail ?: dat?.bands ?: ext?.bands ?: ex2?.bands
    val source = when {
        bandDetail != null -> "PWV7"
        rgbDetail != null -> "PWV5"
        blueDetail.isNotEmpty() -> "PWV3"
        bands != null -> "PWB3"
        wave.isNotEmpty() -> "PWAV"
        else -> ""
    }
    val issues = dat?.issues.orEmpty() + ext?.issues.orEmpty() + ex2?.issues.orEmpty()
    return AnlzFile(beats, cues, wave, bands, source, issues, rgbDetail, rgbPreview, bandDetail, bandPreview, blueDetail, bluePreview)
}
