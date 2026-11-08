package os.deckbridge.logic

/** Uma faixa do set: quando ela passou a ser ouvida na gravação, em ms desde o começo dela. */
data class SetEntry(val startMs: Long, val deck: Int, val trackId: Int, val title: String, val artist: String)

/**
 * Marca as faixas de um set gravado. Uma faixa entra na lista quando o deck dela está no ar (tocando e com
 * fader/crossfader abertos) por `audibleMs` seguidos, e conta desde o instante em que ficou no ar. A mesma faixa
 * não entra de novo dentro de `repeatMs` (volta ao mesmo trecho, loop, fade longo).
 */
class TracklistLogger(private val audibleMs: Long = 8_000L, private val repeatMs: Long = 600_000L) {
    val entries = ArrayList<SetEntry>()
    private val since = longArrayOf(-1L, -1L)
    private val sinceId = intArrayOf(-1, -1)
    private val logged = HashMap<Int, Long>()

    fun reset() {
        entries.clear()
        logged.clear()
        since[0] = -1L
        since[1] = -1L
        sinceId[0] = -1
        sinceId[1] = -1
    }

    /** `nowMs` é o tempo da gravação. Uma chamada por tick (meio segundo basta). */
    fun update(nowMs: Long, audible: List<Boolean>, tracks: List<RbTrack?>) {
        for (deck in 0..1) {
            val track = tracks.getOrNull(deck)
            val on = audible.getOrElse(deck) { false } && track != null
            if (!on) {
                since[deck] = -1L
                sinceId[deck] = -1
                continue
            }
            if (sinceId[deck] != track!!.id) {
                sinceId[deck] = track.id
                since[deck] = nowMs
            }
            if (nowMs - since[deck] < audibleMs) continue
            val last = logged[track.id]
            if (last != null && nowMs - last < repeatMs) continue
            logged[track.id] = nowMs
            entries += SetEntry(since[deck], deck, track.id, track.title, track.artist)
            entries.sortBy { it.startMs }
        }
    }
}

private fun quoted(text: String) = "\"" + text.replace('"', '\'') + "\""

/** mm:ss:ff, com 75 quadros por segundo (CUE). */
fun cueTime(ms: Long): String {
    val totalFrames = ms.coerceAtLeast(0L) * 75L / 1000L
    val frames = totalFrames % 75L
    val seconds = totalFrames / 75L
    return "%02d:%02d:%02d".format(seconds / 60L, seconds % 60L, frames)
}

fun cueSheet(fileName: String, title: String, entries: List<SetEntry>): String = buildString {
    appendLine("PERFORMER ${quoted("DeckBridge")}")
    appendLine("TITLE ${quoted(title)}")
    appendLine("FILE ${quoted(fileName)} WAVE")
    entries.forEachIndexed { index, entry ->
        appendLine("  TRACK %02d AUDIO".format(index + 1))
        appendLine("    TITLE ${quoted(entry.title)}")
        if (entry.artist.isNotBlank()) appendLine("    PERFORMER ${quoted(entry.artist)}")
        appendLine("    INDEX 01 ${cueTime(entry.startMs)}")
    }
}

/** Lista de capítulos: `mm:ss Artista - Título` (a primeira linha em 00:00, como os sites de mix pedem). */
fun tracklistText(entries: List<SetEntry>): String = buildString {
    entries.forEachIndexed { index, entry ->
        val seconds = if (index == 0) 0L else entry.startMs / 1000L
        val name = if (entry.artist.isBlank()) entry.title else "${entry.artist} - ${entry.title}"
        appendLine("%02d:%02d %s".format(seconds / 60L, seconds % 60L, name))
    }
}
