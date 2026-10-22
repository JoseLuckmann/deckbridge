package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.sin

private const val PAGE_LEN = 4096

object SyntheticRekordbox {
    private var cached: MemoryVolume? = null

    fun volume(): MemoryVolume {
        cached?.let { return it }
        val built = uncachedVolume()
        cached = built
        return built
    }

    private fun uncachedVolume(): MemoryVolume {
        val click120 = click(bpm = 120.0, seconds = 8, rate = 48000, freq = 440.0)
        val click100 = click(bpm = 100.0, seconds = 8, rate = 48000, freq = 330.0)
        val wav120 = wavMono16(click120, 48000)
        val wav100 = wavMono16(click100, 48000)
        val rendered = patternSongs().map { song ->
            val pcm = renderPattern(song)
            val columns = (song.seconds * song.bpm / 60.0 * 16).toInt().coerceIn(480, 1600)
            val bands = analyzeBands(pcm, 22050, columns)
            RenderedSong(song, wavMono16(pcm, 22050), bands, song.title.lowercase().replace(" ", "-"))
        }
        val files = linkedMapOf(
            "PIONEER/rekordbox/export.pdb" to pdb(wav120.size, wav100.size, rendered),
            "PIONEER/rekordbox/exportLibrary.db" to "NOTASQLITE".toByteArray(Charsets.US_ASCII),
            "PIONEER/rekordbox/exportExt.pdb" to extPdb(),
            "PIONEER/USBANLZ/ANLZ0001.DAT" to anlzDat(
                bpm = 120,
                beats = 16,
                hot = listOf(0 to 1, 2000 to 2),
                memoryMs = 500,
                bands = analyzeBands(click120, 48000, 64),
            ),
            "PIONEER/USBANLZ/ANLZ0001.EXT" to anlzExt(
                hot = listOf(CueSpec(1, 0, "Intro", 255, 40, 40), CueSpec(2, 2000, "Meio", 40, 180, 255)),
                memory = listOf(CueSpec(0, 500, "Memoria", 0, 0, 0)),
            ),
            "PIONEER/USBANLZ/ANLZ0002.DAT" to anlzDat(
                bpm = 100,
                beats = 13,
                hot = listOf(0 to 1, 3000 to 3),
                memoryMs = 600,
                bands = analyzeBands(click100, 48000, 64),
            ),
            "PIONEER/USBANLZ/ANLZ0002.EXT" to anlzExt(
                hot = listOf(CueSpec(1, 0, "A", 255, 200, 0), CueSpec(3, 3000, "C", 0, 200, 80)),
                memory = listOf(CueSpec(0, 600, "Entrada", 0, 0, 0)),
                loop = CueSpec(0, 1200, "Loop", 0, 0, 0) to 2400,
            ),
            "CONTENTS/faixa-120.wav" to wav120,
            "CONTENTS/faixa-100.wav" to wav100,
        )
        rendered.forEachIndexed { index, song ->
            val number = index + 3
            val stem = "ANLZ%04d".format(number)
            val cues = patternCues(song.song)
            val loop = if (index % 3 == 0) {
                val cue = cues[2]
                CueSpec(0, cue.timeMs, cue.comment, cue.red, cue.green, cue.blue) to (cue.timeMs + (60000 / song.song.bpm) * 4)
            } else {
                null
            }
            files["PIONEER/USBANLZ/$stem.DAT"] = anlzDat(
                bpm = song.song.bpm,
                beats = (song.song.seconds * song.song.bpm / 60).coerceAtLeast(4),
                hot = cues.map { it.timeMs to it.hot },
                memoryMs = 200,
                bands = song.bands,
            )
            files["PIONEER/USBANLZ/$stem.EXT"] = anlzExt(
                hot = cues,
                memory = listOf(CueSpec(0, 200, "Memoria", 220, 40, 40)),
                loop = loop,
            )
            files["CONTENTS/${song.slug}.wav"] = song.wav
        }
        return MemoryVolume(files, "pendrive simulado", true)
    }

    private fun pdb(sizeA: Int, sizeB: Int, music: List<RenderedSong>): ByteArray {
        val pages = mutableListOf(ByteArray(PAGE_LEN))
        data class Chain(val first: Int, val last: Int)
        fun addChain(type: Int, rows: List<ByteArray>, present: List<Boolean>): Chain {
            val chunks = ArrayList<MutableList<Int>>()
            var bucket = mutableListOf<Int>()
            var used = 0
            fun cap(count: Int): Int {
                val groups = if (count <= 0) 1 else (count - 1) / 16 + 1
                return PAGE_LEN - PAGE_HEADER_END - groups * 0x24 - 16
            }
            rows.forEachIndexed { index, row ->
                val pad = if (row.size % 2 == 0) 0 else 1
                if (bucket.isNotEmpty() && used + row.size + pad > cap(bucket.size + 1)) {
                    chunks += bucket
                    bucket = mutableListOf()
                    used = 0
                }
                bucket += index
                used += row.size + pad
            }
            if (bucket.isNotEmpty() || chunks.isEmpty()) chunks += bucket
            val emptyIndex = pages.size
            pages += ByteArray(PAGE_LEN)
            val dataIndexes = chunks.map {
                val index = pages.size
                pages += ByteArray(PAGE_LEN)
                index
            }
            writePageHeader(pages[emptyIndex], emptyIndex, type, dataIndexes.first(), 0, 0)
            chunks.forEachIndexed { chunkIndex, indexes ->
                val dataIndex = dataIndexes[chunkIndex]
                val next = if (chunkIndex == chunks.lastIndex) 0x00FFFFFF else dataIndexes[chunkIndex + 1]
                val data = pages[dataIndex]
                var cursor = PAGE_HEADER_END
                val offsets = IntArray(indexes.size)
                indexes.forEachIndexed { local, rowIndex ->
                    val row = rows[rowIndex]
                    row.copyInto(data, cursor)
                    offsets[local] = cursor - PAGE_HEADER_END
                    cursor += row.size
                    if (cursor % 2 != 0) cursor += 1
                }
                val flags = indexes.map { present[it] }
                writeIndex(data, flags, offsets)
                writePageHeader(data, dataIndex, type, next, indexes.size, flags.count { it })
            }
            return Chain(emptyIndex, dataIndexes.last())
        }
        val tables = mutableListOf<IntArray>()
        fun table(type: Int, rows: List<ByteArray>, present: List<Boolean> = List(rows.size) { true }) {
            val chain = addChain(type, rows, present)
            tables += intArrayOf(type, chain.first, chain.last)
        }
        val artistNames = listOf("João Sintetico", "Casa", "Nara Vale", "Lumen K", "Otis March", "Kite Room")
        val artistIds = mapOf(
            "João Sintetico" to 1,
            "Casa" to 2,
            "Nara Vale" to 3,
            "Lumen K" to 4,
            "Otis March" to 5,
            "Kite Room" to 6,
        )
        val albumNames = listOf("Sessao", "North Line", "Second Shift", "Warm Concrete")
        val keyNames = listOf("8A", "4A") + music.map { it.song.key }.distinct()
        table(2, artistNames.mapIndexed { index, name -> artistRow(index + 1, name) })
        table(3, albumNames.mapIndexed { index, name -> albumRow(index + 1, 1, name) })
        table(1, listOf(inlineNameRow(1, "House", after = 4)))
        table(5, keyNames.mapIndexed { index, name -> inlineNameRow(index + 1, name, after = 8, secondId = index + 1) })
        val trackRows = mutableListOf(
            trackRow(1, "Click 120", "faixa-120.wav", "CONTENTS/faixa-120.wav", "PIONEER/USBANLZ/ANLZ0001.DAT", 12000, 8, 48000, sizeA, 1, 1, 1, 1),
            trackRow(2, "Click 100", "faixa-100.wav", "CONTENTS/faixa-100.wav", "PIONEER/USBANLZ/ANLZ0002.DAT", 10000, 8, 48000, sizeB, 2, 1, 1, 2),
        )
        music.forEachIndexed { index, song ->
            val id = index + 3
            val stem = "ANLZ%04d.DAT".format(id)
            val keyId = keyNames.indexOf(song.song.key) + 1
            val albumId = albumNames.indexOf(song.song.album) + 1
            trackRows += trackRow(
                id,
                song.song.title,
                "${song.slug}.wav",
                "CONTENTS/${song.slug}.wav",
                "PIONEER/USBANLZ/$stem",
                song.song.bpm * 100,
                song.song.seconds,
                22050,
                song.wav.size,
                artistIds.getValue(song.song.artist),
                albumId,
                1,
                keyId,
            )
        }
        trackRows += ByteArray(16) { 0xFF.toByte() }
        table(0, trackRows, List(trackRows.size) { it != trackRows.lastIndex })
        table(
            7,
            listOf(
                playlistRow(1, 0, "Sets", folder = true, sort = 0),
                playlistRow(2, 1, "Abertura", folder = false, sort = 1),
                playlistRow(3, 0, "Todas", folder = false, sort = 2),
                playlistRow(4, 1, "Warmup", folder = false, sort = 3),
                playlistRow(5, 1, "Peak", folder = false, sort = 4),
                playlistRow(6, 1, "Close", folder = false, sort = 5),
            ),
        )
        val entries = mutableListOf(
            entryRow(0, 1, 2),
            entryRow(1, 2, 2),
        )
        val allIds = listOf(1, 2) + music.indices.map { it + 3 }
        allIds.forEachIndexed { position, id -> entries += entryRow(position, id, 3) }
        music.indices.forEach { index ->
            val playlist = when {
                index < 8 -> 4
                index < 16 -> 5
                else -> 6
            }
            val position = when {
                index < 8 -> index
                index < 16 -> index - 8
                else -> index - 16
            }
            entries += entryRow(position, index + 3, playlist)
        }
        table(8, entries)
        val file = ByteArray(pages.size * PAGE_LEN)
        pages.forEachIndexed { index, page -> page.copyInto(file, index * PAGE_LEN) }
        file.putU32le(4, PAGE_LEN)
        file.putU32le(8, tables.size)
        file.putU32le(12, pages.size)
        file.putU32le(20, 1)
        tables.forEachIndexed { index, spec ->
            val at = 28 + index * 16
            file.putU32le(at, spec[0])
            file.putU32le(at + 4, 0)
            file.putU32le(at + 8, spec[1])
            file.putU32le(at + 12, spec[2])
        }
        return file
    }
}

private fun extPdb(): ByteArray {
    val pages = mutableListOf(ByteArray(PAGE_LEN))
    data class Chain(val first: Int, val last: Int)
    fun addChain(type: Int, rows: List<ByteArray>): Chain {
        val emptyIndex = pages.size
        pages += ByteArray(PAGE_LEN)
        val dataIndex = pages.size
        val data = ByteArray(PAGE_LEN)
        pages += data
        writePageHeader(pages[emptyIndex], emptyIndex, type, dataIndex, 0, 0)
        var cursor = PAGE_HEADER_END
        val offsets = IntArray(rows.size)
        rows.forEachIndexed { index, row ->
            row.copyInto(data, cursor)
            offsets[index] = cursor - PAGE_HEADER_END
            cursor += row.size
            if (cursor % 2 != 0) cursor += 1
        }
        val present = List(rows.size) { true }
        writeIndex(data, present, offsets)
        writePageHeader(data, dataIndex, type, 0x00FFFFFF, present.size, present.size)
        return Chain(emptyIndex, dataIndex)
    }
    val tags = addChain(
        3,
        listOf(
            tagRowNear(id = 1, name = "Mood", categoryId = 0, position = 0, category = true, indexShift = 0x20),
            tagRowNear(id = 100, name = "Abertura", categoryId = 1, position = 0, category = false, indexShift = 0x40),
            tagRowFar(id = 200, name = "Longe", categoryId = 1, position = 1, indexShift = 0x60),
        ),
    )
    val links = addChain(
        4,
        listOf(
            tagTrackRow(1, 100),
            tagTrackRow(2, 200),
        ),
    )
    val tables = listOf(intArrayOf(3, tags.first, tags.last), intArrayOf(4, links.first, links.last))
    val file = ByteArray(pages.size * PAGE_LEN)
    pages.forEachIndexed { index, page -> page.copyInto(file, index * PAGE_LEN) }
    file.putU32le(4, PAGE_LEN)
    file.putU32le(8, tables.size)
    file.putU32le(12, pages.size)
    file.putU32le(20, 1)
    tables.forEachIndexed { index, spec ->
        val at = 28 + index * 16
        file.putU32le(at, spec[0])
        file.putU32le(at + 4, 0)
        file.putU32le(at + 8, spec[1])
        file.putU32le(at + 12, spec[2])
    }
    return file
}

private fun tagRowNear(id: Int, name: String, categoryId: Int, position: Int, category: Boolean, indexShift: Int): ByteArray {
    val text = deviceString(name)
    val row = ByteArray(0x20 + text.size)
    row.putU16le(0, 0x0680)
    row.putU16le(2, indexShift)
    row.putU32le(0x0C, categoryId)
    row.putU32le(0x10, position)
    row.putU32le(0x14, id)
    row.putU32le(0x18, if (category) 1 else 0)
    row.putU8(0x1C, 0x03)
    row.putU8(0x1D, 0x20)
    text.copyInto(row, 0x20)
    return row
}

private fun tagRowFar(id: Int, name: String, categoryId: Int, position: Int, indexShift: Int): ByteArray {
    val text = deviceString(name)
    val nameAt = 0x120
    val row = ByteArray(nameAt + text.size)
    row.putU16le(0, 0x0684)
    row.putU16le(2, indexShift)
    row.putU32le(0x0C, categoryId)
    row.putU32le(0x10, position)
    row.putU32le(0x14, id)
    row.putU16le(0x1E, nameAt)
    text.copyInto(row, nameAt)
    return row
}

private fun tagTrackRow(trackId: Int, tagId: Int): ByteArray {
    val row = ByteArray(16)
    row.putU32le(4, trackId)
    row.putU32le(8, tagId)
    row.putU32le(12, 3)
    return row
}

private fun writePageHeader(page: ByteArray, index: Int, type: Int, next: Int, numOffsets: Int, numRows: Int) {
    page.putU32le(4, index)
    page.putU32le(8, type)
    page.putU32le(12, next)
    page.putU32le(16, 1)
    val packed = numOffsets or (numRows shl 13) or (0x24 shl 24)
    page.putU32le(24, packed)
    page.putU16le(28, page.size - PAGE_HEADER_END)
    page.putU16le(30, 0)
}

private fun writeIndex(page: ByteArray, present: List<Boolean>, offsets: IntArray) {
    val groups = if (present.isEmpty()) 0 else (present.size - 1) / 16 + 1
    for (group in 0 until groups) {
        val base = page.size - group * 0x24
        var mask = 0
        for (row in 0 until 16) {
            val index = group * 16 + row
            if (index >= present.size) break
            if (present[index]) mask = mask or (1 shl row)
            page.putU16le(base - (6 + 2 * row), offsets.getOrElse(index) { 0 })
        }
        page.putU16le(base - 4, mask)
    }
}

private fun deviceString(text: String): ByteArray {
    val ascii = text.all { it.code in 32..126 }
    if (ascii && text.length < 120) {
        val length = text.length + 1
        val kind = (length shl 1) or 1
        return byteArrayOf(kind.toByte()) + text.toByteArray(Charsets.US_ASCII)
    }
    val payload = text.toByteArray(Charsets.UTF_16LE)
    val declared = payload.size + 4
    return byteArrayOf(0x90.toByte(), (declared and 0xFF).toByte(), ((declared shr 8) and 0xFF).toByte(), 0) + payload
}

private fun artistRow(id: Int, name: String): ByteArray {
    val text = deviceString(name)
    val row = ByteArray(10 + text.size)
    row.putU16le(0, 0x60)
    row.putU32le(4, id)
    row.putU8(8, 0x03)
    row.putU8(9, 10)
    text.copyInto(row, 10)
    return row
}

private fun albumRow(id: Int, artistId: Int, name: String): ByteArray {
    val text = deviceString(name)
    val row = ByteArray(22 + text.size)
    row.putU16le(0, 0x80)
    row.putU32le(8, artistId)
    row.putU32le(12, id)
    row.putU8(20, 0x03)
    row.putU8(21, 22)
    text.copyInto(row, 22)
    return row
}

private fun inlineNameRow(id: Int, name: String, after: Int, secondId: Int? = null): ByteArray {
    val text = deviceString(name)
    val row = ByteArray(after + text.size)
    row.putU32le(0, id)
    if (secondId != null) row.putU32le(4, secondId)
    text.copyInto(row, after)
    return row
}

private fun playlistRow(id: Int, parent: Int, name: String, folder: Boolean, sort: Int): ByteArray {
    val text = deviceString(name)
    val row = ByteArray(20 + text.size)
    row.putU32le(0, parent)
    row.putU32le(8, sort)
    row.putU32le(12, id)
    row.putU32le(16, if (folder) 1 else 0)
    text.copyInto(row, 20)
    return row
}

private fun entryRow(position: Int, trackId: Int, playlistId: Int): ByteArray {
    val row = ByteArray(12)
    row.putU32le(0, position)
    row.putU32le(4, trackId)
    row.putU32le(8, playlistId)
    return row
}

private fun trackRow(
    id: Int,
    title: String,
    fileName: String,
    filePath: String,
    analyzePath: String,
    tempo: Int,
    duration: Int,
    sampleRate: Int,
    fileSize: Int,
    artistId: Int,
    albumId: Int,
    genreId: Int,
    keyId: Int,
): ByteArray {
    val strings = MutableList(21) { deviceString("") }
    strings[14] = deviceString(analyzePath)
    strings[17] = deviceString(title)
    strings[19] = deviceString(fileName)
    strings[20] = deviceString(filePath)
    var size = 136
    val offsets = IntArray(21)
    strings.forEachIndexed { index, blob ->
        offsets[index] = size
        size += blob.size
    }
    val row = ByteArray(size)
    row.putU16le(0, 0x24)
    row.putU32le(8, sampleRate)
    row.putU32le(16, fileSize)
    row.putU16le(24, 19048)
    row.putU16le(26, 30967)
    row.putU32le(32, keyId)
    row.putU32le(48, sampleRate * 16)
    row.putU32le(56, tempo)
    row.putU32le(60, genreId)
    row.putU32le(64, albumId)
    row.putU32le(68, artistId)
    row.putU32le(72, id)
    row.putU16le(80, 2026)
    row.putU16le(82, 16)
    row.putU16le(84, duration)
    row.putU16le(86, 41)
    row.putU8(88, 1)
    row.putU16le(90, 1)
    offsets.forEachIndexed { index, ofs -> row.putU16le(94 + index * 2, ofs) }
    var cursor = 136
    strings.forEach { blob ->
        blob.copyInto(row, cursor)
        cursor += blob.size
    }
    return row
}

private data class RenderedSong(val song: PatternSong, val wav: ByteArray, val bands: WaveBands, val slug: String)

private fun anlzDat(bpm: Int, beats: Int, hot: List<Pair<Int, Int>>, memoryMs: Int, bands: WaveBands? = null): ByteArray {
    val beatBody = BeWriter()
    beatBody.u32(0)
    beatBody.u32(0x80000)
    beatBody.u32(beats)
    val interval = 60000 / bpm
    for (index in 0 until beats) {
        beatBody.u16((index % 4) + 1)
        beatBody.u16(bpm * 100)
        beatBody.u32(index * interval)
    }
    val memory = cueSection(0, listOf(plainCue(0, memoryMs)))
    val hots = cueSection(1, hot.map { plainCue(it.second, it.first) })
    val wave = if (bands == null) {
        ByteArray(64) { index -> (80 + (index * 17) % 140).toByte() }
    } else {
        overviewFromBands(bands)
    }
    val waveBody = BeWriter()
    waveBody.u32(wave.size)
    waveBody.u32(0x10000)
    waveBody.bytes(wave)
    val sections = mutableListOf(
        section("PQTZ", beatBody.toByteArray()),
        memory,
        hots,
        section("PWAV", waveBody.toByteArray()),
    )
    if (bands != null) {
        val body = BeWriter()
        body.u32(bands.columns)
        body.bytes(bands.low.copyOf(bands.columns))
        body.bytes(bands.mid.copyOf(bands.columns))
        body.bytes(bands.high.copyOf(bands.columns))
        sections += section("PWB3", body.toByteArray())
    }
    return anlzFile(sections)
}

private fun anlzExt(
    hot: List<CueSpec>,
    memory: List<CueSpec>,
    loop: Pair<CueSpec, Int>? = null,
): ByteArray {
    val memoryEntries = memory.map { extendedCue(it, loopEnd = null) }.toMutableList()
    if (loop != null) memoryEntries += extendedCue(loop.first, loop.second)
    return anlzFile(
        listOf(
            cue2Section(0, memoryEntries),
            cue2Section(1, hot.map { extendedCue(it, null) }),
        ),
    )
}

private fun anlzFile(sections: List<ByteArray>): ByteArray {
    val body = sections.fold(ByteArray(0)) { acc, section -> acc + section }
    val header = BeWriter()
    header.ascii("PMAI")
    header.u32(28)
    header.u32(28 + body.size)
    repeat(16) { header.u8(0) }
    return header.toByteArray() + body
}

private fun section(tag: String, body: ByteArray): ByteArray {
    val head = BeWriter()
    head.ascii(tag)
    head.u32(12)
    head.u32(12 + body.size)
    return head.toByteArray() + body
}

private fun plainCue(hot: Int, timeMs: Int): ByteArray {
    val row = ByteArray(56)
    "PCPT".toByteArray(Charsets.US_ASCII).copyInto(row)
    row.putBe32(4, 16)
    row.putBe32(8, 56)
    row.putBe32(12, hot)
    row.putBe32(16, 1)
    row.putBe32(20, 0x10000)
    row.putBe16(24, 0xFFFF)
    row.putBe16(26, 0xFFFF)
    row[28] = 1
    row.putBe32(32, timeMs)
    row.putBe32(36, 0)
    return row
}

private fun cueSection(type: Int, cues: List<ByteArray>): ByteArray {
    val body = BeWriter()
    body.u32(type)
    body.u16(0)
    body.u16(cues.size)
    body.u32(cues.size)
    cues.forEach { body.bytes(it) }
    return section("PCOB", body.toByteArray())
}

private fun extendedCue(spec: CueSpec, loopEnd: Int?): ByteArray {
    val comment = (spec.comment + "\u0000").toByteArray(Charsets.UTF_16BE)
    val len = 60 - 12 + comment.size
    val row = ByteArray(len)
    "PCP2".toByteArray(Charsets.US_ASCII).copyInto(row)
    row.putBe32(4, 16)
    row.putBe32(8, len)
    row.putBe32(12, spec.hot)
    row[16] = if (loopEnd != null) 2 else 1
    row.putBe32(20, spec.timeMs)
    row.putBe32(24, loopEnd ?: 0)
    row.putBe32(40, comment.size)
    comment.copyInto(row, 44)
    val colorAt = 44 + comment.size
    row[colorAt] = 0
    row[colorAt + 1] = spec.red.toByte()
    row[colorAt + 2] = spec.green.toByte()
    row[colorAt + 3] = spec.blue.toByte()
    return row
}

private fun cue2Section(type: Int, cues: List<ByteArray>): ByteArray {
    val body = BeWriter()
    body.u32(type)
    body.u16(cues.size)
    body.u16(0)
    cues.forEach { body.bytes(it) }
    return section("PCO2", body.toByteArray())
}

private fun ByteArray.putBe16(at: Int, value: Int) {
    this[at] = ((value shr 8) and 0xFF).toByte()
    this[at + 1] = (value and 0xFF).toByte()
}

private fun ByteArray.putBe32(at: Int, value: Int) {
    this[at] = ((value shr 24) and 0xFF).toByte()
    this[at + 1] = ((value shr 16) and 0xFF).toByte()
    this[at + 2] = ((value shr 8) and 0xFF).toByte()
    this[at + 3] = (value and 0xFF).toByte()
}

internal fun click(bpm: Double, seconds: Int, rate: Int, freq: Double): FloatArray {
    val total = seconds * rate
    val out = FloatArray(total)
    val beat = 60.0 / bpm
    for (index in 0 until total) {
        val t = index.toDouble() / rate
        val into = t % beat
        val env = if (into < 0.02) 1.0 - into / 0.02 else 0.0
        val tick = sin(2.0 * PI * 2000.0 * t) * env * 0.45
        val body = sin(2.0 * PI * freq * t) * 0.12
        out[index] = (tick + body).toFloat()
    }
    return out
}

internal fun wavMono16(samples: FloatArray, rate: Int): ByteArray {
    val dataBytes = samples.size * 2
    val out = ByteArray(44 + dataBytes)
    "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out)
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
        val clamped = sample.coerceIn(-1f, 1f)
        val raw = (clamped * 32767f).toInt()
        out.putU16le(44 + index * 2, raw)
    }
    return out
}

data class VariantCase(val name: String, val detail: String, val ok: Boolean)

private fun packPdb(pageLen: Int, tables: List<Triple<Int, List<ByteArray>, List<Boolean>>>): ByteArray {
    val pages = mutableListOf(ByteArray(pageLen))
    val specs = mutableListOf<IntArray>()
    for ((type, rows, present) in tables) {
        val chunks = ArrayList<MutableList<Int>>()
        var bucket = mutableListOf<Int>()
        var used = 0
        fun cap(count: Int): Int {
            val groups = if (count <= 0) 1 else (count - 1) / 16 + 1
            return pageLen - PAGE_HEADER_END - groups * 0x24 - 8
        }
        rows.forEachIndexed { index, row ->
            val pad = if (row.size % 2 == 0) 0 else 1
            if (row.size + pad > cap(1)) return ByteArray(0)
            if (bucket.isNotEmpty() && used + row.size + pad > cap(bucket.size + 1)) {
                chunks += bucket
                bucket = mutableListOf()
                used = 0
            }
            bucket += index
            used += row.size + pad
        }
        if (bucket.isNotEmpty() || chunks.isEmpty()) chunks += bucket
        val emptyIndex = pages.size
        pages += ByteArray(pageLen)
        val dataIndexes = chunks.map {
            val index = pages.size
            pages += ByteArray(pageLen)
            index
        }
        writePageHeader(pages[emptyIndex], emptyIndex, type, dataIndexes.first(), 0, 0)
        chunks.forEachIndexed { chunkIndex, indexes ->
            val dataIndex = dataIndexes[chunkIndex]
            val next = if (chunkIndex == chunks.lastIndex) 0x00FFFFFF else dataIndexes[chunkIndex + 1]
            val data = pages[dataIndex]
            var cursor = PAGE_HEADER_END
            val offsets = IntArray(indexes.size)
            indexes.forEachIndexed { local, rowIndex ->
                val row = rows[rowIndex]
                row.copyInto(data, cursor)
                offsets[local] = cursor - PAGE_HEADER_END
                cursor += row.size
                if (cursor % 2 != 0) cursor += 1
            }
            writeIndex(data, indexes.map { present[it] }, offsets)
            writePageHeader(data, dataIndex, type, next, indexes.size, indexes.count { present[it] })
        }
        specs += intArrayOf(type, emptyIndex, dataIndexes.last())
    }
    val file = ByteArray(pages.size * pageLen)
    pages.forEachIndexed { index, page -> page.copyInto(file, index * pageLen) }
    file.putU32le(4, pageLen)
    file.putU32le(8, specs.size)
    file.putU32le(12, pages.size)
    file.putU32le(20, 1)
    specs.forEachIndexed { index, spec ->
        val at = 28 + index * 16
        file.putU32le(at, spec[0])
        file.putU32le(at + 4, 0)
        file.putU32le(at + 8, spec[1])
        file.putU32le(at + 12, spec[2])
    }
    return file
}

fun largeLibraryPdb(count: Int, pageLen: Int = 4096): ByteArray {
    val artists = listOf(artistRow(1, "Casa"))
    val tracks = ArrayList<ByteArray>(count + 1)
    val present = ArrayList<Boolean>(count + 1)
    for (id in 1..count) {
        val title = if (id == 7) "Faixa café — São Paulo #$id" else "Faixa $id"
        tracks += trackRow(
            id,
            title,
            "faixa-$id.wav",
            "CONTENTS/odd café [$id].wav",
            "PIONEER/USBANLZ/ANLZ%04d.DAT".format(id),
            12000,
            18,
            44100,
            1000 + id,
            1,
            1,
            1,
            1,
        )
        present += true
    }
    tracks += ByteArray(8) { 0xFF.toByte() }
    present += false
    return packPdb(
        pageLen,
        listOf(
            Triple(2, artists, List(artists.size) { true }),
            Triple(0, tracks, present),
        ),
    )
}

private fun colorSection(tag: String, columns: Int, color: Int): ByteArray {
    val body = BeWriter()
    body.u32(columns * 2)
    body.u32(0x10000)
    repeat(columns) { index ->
        val height = (8 + index * 3) and 0x1F
        val word = height or ((color and 7) shl 5)
        body.u16(word)
    }
    return section(tag, body.toByteArray())
}

private fun pwv7Section(columns: Int, low: Int, mid: Int, high: Int): ByteArray {
    val body = BeWriter()
    body.u32(columns * 3)
    body.u32(0x10000)
    repeat(columns) {
        body.u8(low)
        body.u8(mid)
        body.u8(high)
    }
    return section("PWV7", body.toByteArray())
}

fun waveformFixture(kind: String): ByteArray {
    val beats = BeWriter()
    beats.u32(0)
    beats.u32(0x80000)
    beats.u32(4)
    repeat(4) { index ->
        beats.u16(index + 1)
        beats.u16(12000)
        beats.u32(index * 500)
    }
    val sections = mutableListOf(section("PQTZ", beats.toByteArray()))
    when (kind) {
        "PWV7" -> {
            sections += pwv7Section(8, 200, 10, 20)
            sections += run {
                val body = BeWriter()
                body.u32(8)
                body.bytes(ByteArray(24) { 1 })
                section("PWB3", body.toByteArray())
            }
        }
        "PWV5" -> sections += colorSection("PWV5", 8, 7)
        "PWV3" -> sections += colorSection("PWV3", 8, 1)
        "PCO2" -> sections += anlzExt(
            hot = listOf(CueSpec(1, 100, "Comentário", 10, 20, 30)),
            memory = listOf(CueSpec(0, 50, "", 0, 0, 0)),
            loop = CueSpec(0, 400, "Loop", 1, 2, 3) to 900,
        ).let { ByteArray(0) }
        else -> sections += section("ZZZZ", byteArrayOf(1, 2, 3, 4))
    }
    return if (kind == "PCO2") {
        anlzExt(
            hot = listOf(CueSpec(1, 100, "Comentário", 10, 20, 30)),
            memory = listOf(CueSpec(0, 50, "", 0, 0, 0)),
            loop = CueSpec(0, 400, "Loop", 1, 2, 3) to 900,
        )
    } else {
        anlzFile(sections)
    }
}

fun usbVariantReport(): String {
    val lines = mutableListOf<String>()
    fun line(name: String, ok: Boolean, detail: String) {
        lines += "${if (ok) "ok" else "falha"}  $name  $detail"
    }
    for (page in listOf(2048, 4096, 8192)) {
        val parsed = parseExportPdb(largeLibraryPdb(3, page))
        val titles = parsed.database?.tracks?.map { it.title }.orEmpty()
        line("página $page", titles.size == 3 && titles.contains("Faixa 1"), "faixas ${titles.size}")
    }
    val bigStarted = System.nanoTime()
    val big = parseExportPdb(largeLibraryPdb(5000))
    val millis = (System.nanoTime() - bigStarted) / 1_000_000
    val odd = big.database?.tracks?.firstOrNull { it.id == 7 }
    line("5000 faixas", (big.database?.tracks?.size ?: 0) == 5000 && millis < 2000 && odd?.filePath?.contains("café") == true, "${millis} ms na JVM")
    val seven = parseAnlz(waveformFixture("PWV7"))
    line("PWV7 acima de PWB3", seven.waveSource == "PWV7" && (seven.bands?.low?.first()?.toInt() ?: 0) and 0xFF == 200, seven.waveSource)
    val five = parseAnlz(waveformFixture("PWV5"))
    line("PWV5 cor", five.waveSource == "PWV5" && (five.bands?.low?.first()?.toInt() ?: 0) and 0xFF > 0, five.waveSource)
    val three = parseAnlz(waveformFixture("PWV3"))
    line("PWV3 cor", three.waveSource == "PWV3", three.waveSource)
    val cues = parseAnlz(waveformFixture("PCO2"))
    line("PCO2 comentário e loop", cues.cues.any { it.comment.contains("Coment") } && cues.cues.any { it.loopEndMs != null }, "cues ${cues.cues.size}")
    val junk = parseAnlz(waveformFixture("lixo"))
    line("tag desconhecida", junk.issues.isEmpty(), "segue")
    var thrown = false
    val random = kotlin.random.Random(9)
    repeat(80) {
        val size = random.nextInt(0, 3000)
        val bytes = ByteArray(size) { random.nextInt(256).toByte() }
        try {
            parseExportPdb(bytes)
            parseAnlz(bytes)
            parseExportExt(bytes)
        } catch (_: RuntimeException) {
            thrown = true
        }
    }
    val valid = largeLibraryPdb(4)
    repeat(40) { index ->
        valid[index * 17 % valid.size] = random.nextInt(256).toByte()
        try {
            parseExportPdb(valid)
        } catch (_: RuntimeException) {
            thrown = true
        }
    }
    line("fuzz PDB/ANLZ", !thrown, if (thrown) "exceção" else "sem exceção")
    val missing = MemoryVolume(
        mapOf("PIONEER/rekordbox/export.pdb" to largeLibraryPdb(2)),
        "variante",
        true,
    )
    val catalog = loadRekordbox(missing, null)
    line("ANLZ ausente", catalog.warnings.any { it.contains("ANLZ ausente") } && catalog.error == null, catalog.warnings.firstOrNull() ?: "sem aviso")
    return lines.joinToString("\n")
}
