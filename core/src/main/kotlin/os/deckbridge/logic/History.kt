package os.deckbridge.logic

data class OnAirSample(
    val trackId: Int,
    val title: String,
    val artist: String,
    val bpm: Double,
    val keyName: String,
    val pitch: Double,
)

data class HistoryRow(
    val atMillis: Long,
    val deck: Int,
    val title: String,
    val artist: String,
    val bpm: Double,
    val playedBpm: Double,
    val keyName: String,
    val seconds: Int,
    val trackId: Int = -1,
)

/** Copies the library row. Pitch only changes the BPM at play time. */
fun onAirSample(track: RbTrack, pitch: Double): OnAirSample = OnAirSample(
    trackId = track.id,
    title = track.title,
    artist = track.artist,
    bpm = track.bpm,
    keyName = track.keyName,
    pitch = pitch,
)

fun historyRow(sample: OnAirSample, atMillis: Long, deck: Int, seconds: Int): HistoryRow = HistoryRow(
    atMillis = atMillis,
    deck = deck,
    title = sample.title,
    artist = sample.artist,
    bpm = sample.bpm,
    playedBpm = sample.bpm * sample.pitch,
    keyName = sample.keyName,
    seconds = seconds,
    trackId = sample.trackId,
)

class OnAirLog(var minSeconds: Int = 10) {
    private val since = LongArray(2) { -1L }
    private val trackIds = IntArray(2) { Int.MIN_VALUE }
    private val open = BooleanArray(2) { true }
    private val title = arrayOf("", "")
    private val artist = arrayOf("", "")
    private val bpm = DoubleArray(2)
    private val keyName = arrayOf("", "")

    fun tick(
        deck: Int,
        playing: Boolean,
        faderUp: Boolean,
        nowMs: Long,
        sample: OnAirSample?,
    ): HistoryRow? {
        if (deck !in 0..1) return null
        if (sample == null || !playing || !faderUp || sample.title.isBlank()) {
            since[deck] = -1L
            trackIds[deck] = Int.MIN_VALUE
            open[deck] = true
            return null
        }
        if (since[deck] < 0L || trackIds[deck] != sample.trackId) {
            since[deck] = nowMs
            trackIds[deck] = sample.trackId
            title[deck] = sample.title
            artist[deck] = sample.artist
            bpm[deck] = sample.bpm
            keyName[deck] = sample.keyName
            open[deck] = true
            return null
        }
        if (!open[deck]) return null
        if (nowMs - since[deck] < minSeconds * 1000L) return null
        open[deck] = false
        return HistoryRow(
            atMillis = nowMs,
            deck = deck,
            title = title[deck],
            artist = artist[deck],
            bpm = bpm[deck],
            playedBpm = bpm[deck] * sample.pitch,
            keyName = keyName[deck],
            seconds = minSeconds,
            trackId = trackIds[deck],
        )
    }
}

fun historyCsv(rows: List<HistoryRow>): String {
    val head = "time_ms,deck,title,artist,bpm,played_bpm,key,seconds,track_id"
    val body = rows.joinToString("\n") { row ->
        listOf(
            row.atMillis.toString(),
            (row.deck + 1).toString(),
            csv(row.title),
            csv(row.artist),
            "%.2f".format(row.bpm),
            "%.2f".format(row.playedBpm),
            csv(row.keyName),
            row.seconds.toString(),
            row.trackId.toString(),
        ).joinToString(",")
    }
    return if (body.isEmpty()) head else "$head\n$body"
}

fun historyM3u8(rows: List<HistoryRow>): String {
    val lines = mutableListOf("#EXTM3U")
    rows.forEach { row ->
        lines += "#EXTINF:${row.seconds},${row.artist} - ${row.title}"
        lines += "#EXT-X-DECKBRIDGE:deck=${row.deck + 1},bpm=${"%.2f".format(row.bpm)},played_bpm=${"%.2f".format(row.playedBpm)},key=${row.keyName}"
        lines += "deckbridge://set/${row.atMillis}"
    }
    return lines.joinToString("\n")
}

fun sessionSpanSeconds(rows: List<HistoryRow>): Int {
    if (rows.isEmpty()) return 0
    val start = rows.minOf { it.atMillis }
    val end = rows.maxOf { it.atMillis }
    return ((end - start) / 1000L).toInt().coerceAtLeast(0)
}

fun storageWarning(freeBytes: Long, floor: Long = 64L * 1024L * 1024L): String? {
    if (freeBytes < 0L) return null
    if (freeBytes >= floor) return null
    return "Pouco espaço livre (${freeBytes / (1024 * 1024)} MB). A gravação não começou."
}

private fun csv(text: String): String {
    val escaped = text.replace("\"", "\"\"")
    return "\"$escaped\""
}
