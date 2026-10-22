package os.deckbridge.logic

data class HistoryRow(
    val atMillis: Long,
    val deck: Int,
    val title: String,
    val artist: String,
    val bpm: Double,
    val keyName: String,
    val seconds: Int,
)

class OnAirLog(var minSeconds: Int = 10) {
    private val since = LongArray(2) { -1L }
    private val open = BooleanArray(2) { true }

    fun tick(
        deck: Int,
        playing: Boolean,
        faderUp: Boolean,
        nowMs: Long,
        title: String,
        artist: String,
        bpm: Double,
        keyName: String,
    ): HistoryRow? {
        if (deck !in 0..1) return null
        val live = playing && faderUp && title.isNotBlank()
        if (!live) {
            since[deck] = -1L
            open[deck] = true
            return null
        }
        if (since[deck] < 0L) {
            since[deck] = nowMs
            return null
        }
        if (!open[deck]) return null
        if (nowMs - since[deck] < minSeconds * 1000L) return null
        open[deck] = false
        return HistoryRow(nowMs, deck, title, artist, bpm, keyName, minSeconds)
    }
}

fun historyCsv(rows: List<HistoryRow>): String {
    val head = "time_ms,deck,title,artist,bpm,key,seconds"
    val body = rows.joinToString("\n") { row ->
        listOf(
            row.atMillis.toString(),
            (row.deck + 1).toString(),
            csv(row.title),
            csv(row.artist),
            "%.2f".format(row.bpm),
            csv(row.keyName),
            row.seconds.toString(),
        ).joinToString(",")
    }
    return if (body.isEmpty()) head else "$head\n$body"
}

fun historyM3u8(rows: List<HistoryRow>): String {
    val lines = mutableListOf("#EXTM3U")
    rows.forEach { row ->
        lines += "#EXTINF:${row.seconds},${row.artist} - ${row.title}"
        lines += "#EXT-X-DECKBRIDGE:deck=${row.deck + 1},bpm=${"%.2f".format(row.bpm)},key=${row.keyName}"
        lines += "deckbridge://set/${row.atMillis}"
    }
    return lines.joinToString("\n")
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
