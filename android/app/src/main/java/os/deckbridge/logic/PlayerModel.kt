package os.deckbridge.logic

import kotlin.math.abs
import kotlin.math.roundToInt

enum class WaveZoom(val bars: Int?) {
    BARS_2(2),
    BARS_4(4),
    BARS_8(8),
    BARS_16(16),
    BARS_32(32),
    TRACK(null),
    ;

    fun label(): String = if (bars == null) "FAIXA" else "$bars"

    fun zoomIn(): WaveZoom = entries[(ordinal - 1).coerceAtLeast(0)]

    fun zoomOut(): WaveZoom = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
}

fun windowSeconds(zoom: WaveZoom, bpm: Double, duration: Double): Double {
    if (zoom == WaveZoom.TRACK) return duration.coerceAtLeast(0.1)
    val beat = if (bpm > 1.0) 60.0 / bpm else 0.5
    return (zoom.bars!! * 4 * beat).coerceAtLeast(0.25)
}

/** Fração 0..1 na janela. No zoom com playhead fixo, o centro da faixa é o playhead. */
fun markerFraction(
    time: Double,
    center: Double,
    window: Double,
    wholeTrack: Boolean,
    duration: Double,
    anchor: Double = 0.5,
): Double {
    if (window <= 0.0) return 0.0
    return if (wholeTrack) {
        if (duration <= 0.0) 0.0 else (time / duration).coerceIn(0.0, 1.0)
    } else {
        val start = center - window * anchor.coerceIn(0.05, 0.95)
        ((time - start) / window).coerceIn(-0.2, 1.2)
    }
}

enum class MarkerKind { BEAT, BAR, PHRASE }

data class GridLine(val seconds: Double, val kind: MarkerKind)

fun gridLines(bpm: Double, duration: Double, beats: List<AnlzBeat>): List<GridLine> {
    val end = duration.coerceAtLeast(0.0)
    if (beats.isEmpty()) {
        if (bpm <= 1.0 || end <= 0.0) return emptyList()
        val interval = 60.0 / bpm
        return beatSeries(0.0, interval, end)
    }
    val sorted = beats.sortedBy { it.timeMs }
    val lines = sorted.mapIndexed { index, beat ->
        GridLine(beat.timeMs / 1000.0, markerKind(index, beat.beatInBar))
    }.toMutableList()
    val intervals = sorted.zipWithNext { a, b -> (b.timeMs - a.timeMs) / 1000.0 }.filter { it in 0.05..4.0 }
    val interval = if (intervals.isNotEmpty()) intervals.sorted()[intervals.size / 2] else if (bpm > 1.0) 60.0 / bpm else return lines
    var t = sorted.first().timeMs / 1000.0 - interval
    var guard = 0
    while (t >= -0.001 && guard < 4000) {
        lines += GridLine(t.coerceAtLeast(0.0), markerKind(guard + 1000, 1))
        t -= interval
        guard += 1
    }
    t = sorted.last().timeMs / 1000.0 + interval
    var index = sorted.size
    guard = 0
    while (t <= end + 0.001 && guard < 4000) {
        lines += GridLine(t, markerKind(index, if (index % 4 == 0) 1 else 2))
        t += interval
        index += 1
        guard += 1
    }
    return lines.filter { it.seconds in 0.0..end + 0.02 }.sortedBy { it.seconds }
}

private fun beatSeries(start: Double, interval: Double, end: Double): List<GridLine> {
    val out = ArrayList<GridLine>()
    var t = start
    var index = 0
    while (t <= end + 0.001 && index < 4000) {
        out += GridLine(t, markerKind(index, if (index % 4 == 0) 1 else (index % 4) + 1))
        t += interval
        index += 1
    }
    return out
}

fun markerKind(index: Int, beatInBar: Int): MarkerKind = when {
    index % 16 == 0 -> MarkerKind.PHRASE
    beatInBar == 1 || index % 4 == 0 -> MarkerKind.BAR
    else -> MarkerKind.BEAT
}

fun beatsUntil(fromSeconds: Double, cueSeconds: Double, bpm: Double): Int? {
    if (bpm <= 1.0) return null
    val beat = 60.0 / bpm
    return ((cueSeconds - fromSeconds) / beat).roundToInt()
}

fun quantizeSeconds(seconds: Double, beats: List<AnlzBeat>, bpm: Double): Double {
    if (beats.isEmpty()) return seconds
    val nearest = beats.minBy { abs(it.timeMs / 1000.0 - seconds) }
    val beat = if (bpm > 1.0) 60.0 / bpm else 0.5
    val at = nearest.timeMs / 1000.0
    return if (abs(at - seconds) <= beat / 2.0) at else seconds
}

fun phaseOffset(positionA: Double, bpmA: Double, positionB: Double, bpmB: Double): Double {
    if (bpmA <= 1.0 || bpmB <= 1.0) return 0.0
    val phaseA = (positionA / (60.0 / bpmA)).mod(1.0)
    val phaseB = (positionB / (60.0 / bpmB)).mod(1.0)
    var delta = phaseA - phaseB
    if (delta > 0.5) delta -= 1.0
    if (delta < -0.5) delta += 1.0
    return delta
}

val BEAT_FX_NAMES = listOf("Echo", "Delay", "Reverb", "Flanger", "Phaser", "Roll", "Trans", "Filter")
val BEAT_FX_FRACTIONS = listOf("1/16", "1/8", "1/4", "1/2", "3/4", "1/1", "2/1", "4/1", "8/1")

data class BeatFxState(
    val effect: Int = 0,
    val fraction: Int = 5,
    val channel: Int = 0,
    val on: Boolean = false,
    val level: Int = 64,
) {
    fun name(): String = BEAT_FX_NAMES[effect.coerceIn(0, BEAT_FX_NAMES.lastIndex)]

    fun fractionLabel(): String = BEAT_FX_FRACTIONS[fraction.coerceIn(0, BEAT_FX_FRACTIONS.lastIndex)]

    fun channelLabel(): String = when (channel) {
        1 -> "2"
        2 -> "MST"
        else -> "1"
    }

    fun hitsDeck(deck: Int): Boolean = on && (channel == 2 || channel == deck)
}

fun reduceBeatFx(state: BeatFxState, action: PerformanceAction): BeatFxState {
    if (action.kind != PerformanceAction.Kind.BEAT_FX) return state
    return when (action.hotIndex) {
        1 -> state.copy(effect = (state.effect + 1) % BEAT_FX_NAMES.size)
        2 -> state.copy(effect = (state.effect - 1 + BEAT_FX_NAMES.size) % BEAT_FX_NAMES.size)
        3 -> state.copy(fraction = (state.fraction - 1).coerceAtLeast(0))
        4 -> state.copy(fraction = (state.fraction + 1).coerceAtMost(BEAT_FX_FRACTIONS.lastIndex))
        5 -> state.copy(on = !state.on)
        6 -> state.copy(on = false)
        7 -> state.copy(channel = action.deck.coerceIn(0, 2))
        8 -> state.copy(level = (action.slider ?: state.level.toDouble()).roundToInt().coerceIn(0, 127))
        else -> state
    }
}

enum class BrowseCategory(val label: String) {
    PLAYLIST("Playlist"),
    TRACK("Track"),
    ARTIST("Artist"),
    ALBUM("Album"),
    BPM("BPM"),
    KEY("Key"),
    TAG("My Tag"),
    HISTORY("History"),
    SEARCH("Search"),
}

data class BrowsePlace(
    val category: BrowseCategory = BrowseCategory.PLAYLIST,
    val inList: Boolean = false,
    val index: Int = 0,
    val playlistId: Int? = null,
    val groupKey: String? = null,
    val query: String = "",
)

data class BrowseRow(
    val title: String,
    val artist: String,
    val bpm: String,
    val keyName: String,
    val time: String,
    val track: RbTrack?,
    val playlistId: Int?,
    val groupKey: String?,
    val folder: Boolean,
)

fun browseRows(catalog: RbCatalog, place: BrowsePlace, historyIds: List<Int>): List<BrowseRow> {
    if (!place.inList) {
        return BrowseCategory.entries.map { category ->
            BrowseRow(category.label, "", "", "", "", null, null, category.name, false)
        }
    }
    val tracks = catalog.tracks
    fun row(track: RbTrack) = BrowseRow(
        title = track.title,
        artist = track.artist,
        bpm = if (track.bpm > 0) "%.1f".format(track.bpm) else "—",
        keyName = track.keyName.ifBlank { "—" },
        time = clockShort(track.durationSeconds),
        track = track,
        playlistId = null,
        groupKey = null,
        folder = false,
    )
    val base = when (place.category) {
        BrowseCategory.PLAYLIST -> {
            if (place.playlistId == null) {
                return catalog.playlists.sortedBy { it.sortOrder }.map { playlist ->
                    BrowseRow(playlist.name, if (playlist.folder) "Pasta" else "${playlist.trackIds.size} faixas", "", "", "", null, playlist.id, null, playlist.folder)
                }
            }
            val ids = catalog.playlists.firstOrNull { it.id == place.playlistId }?.trackIds.orEmpty()
            ids.mapNotNull { id -> tracks.firstOrNull { it.id == id } }.map(::row)
        }
        BrowseCategory.TRACK -> tracks.map(::row)
        BrowseCategory.ARTIST -> grouped(tracks, place.groupKey, { it.artist.ifBlank { "Sem artista" } }, ::row)
        BrowseCategory.ALBUM -> grouped(tracks, place.groupKey, { it.album.ifBlank { "Sem álbum" } }, ::row)
        BrowseCategory.BPM -> grouped(tracks, place.groupKey, { "%.0f".format(it.bpm) }, ::row)
        BrowseCategory.KEY -> grouped(tracks, place.groupKey, { it.keyName.ifBlank { "—" } }, ::row)
        BrowseCategory.TAG -> {
            if (place.groupKey == null) {
                val names = catalog.trackTags.values.flatten().map { it.name }.distinct().sorted()
                return names.map { name -> BrowseRow(name, "Tag", "", "", "", null, null, name, true) }
            }
            tracks.filter { track -> catalog.trackTags[track.id].orEmpty().any { it.name == place.groupKey } }.map(::row)
        }
        BrowseCategory.HISTORY -> historyIds.mapNotNull { id -> tracks.firstOrNull { it.id == id } }.map(::row)
        BrowseCategory.SEARCH -> {
            val q = place.query.trim()
            if (q.isEmpty()) tracks.map(::row)
            else tracks.filter { it.title.contains(q, true) || it.artist.contains(q, true) }.map(::row)
        }
    }
    return base
}

private fun grouped(
    tracks: List<RbTrack>,
    key: String?,
    groupOf: (RbTrack) -> String,
    row: (RbTrack) -> BrowseRow,
): List<BrowseRow> {
    if (key == null) {
        return tracks.groupBy(groupOf).keys.sorted().map { name ->
            BrowseRow(name, "Grupo", "", "", "", null, null, name, true)
        }
    }
    return tracks.filter { groupOf(it) == key }.map(row)
}

fun moveBrowse(place: BrowsePlace, steps: Int, rowCount: Int): BrowsePlace {
    if (rowCount <= 0 || steps == 0) return place
    val next = (place.index + steps).floorMod(rowCount)
    return place.copy(index = next)
}

fun enterBrowse(place: BrowsePlace, row: BrowseRow?): BrowsePlace {
    if (!place.inList) {
        val category = BrowseCategory.entries.getOrNull(place.index) ?: BrowseCategory.PLAYLIST
        return place.copy(category = category, inList = true, index = 0, playlistId = null, groupKey = null)
    }
    if (row == null) return place
    if (row.playlistId != null && place.playlistId == null) return place.copy(playlistId = row.playlistId, index = 0)
    if (row.groupKey != null && row.folder && place.category != BrowseCategory.PLAYLIST) {
        return place.copy(groupKey = row.groupKey, index = 0)
    }
    return place
}

fun pickBrowse(place: BrowsePlace, index: Int, row: BrowseRow?): BrowsePlace {
    val moved = place.copy(index = index)
    val opens = !place.inList || row?.folder == true || (row?.playlistId != null && place.playlistId == null)
    return if (opens) enterBrowse(moved, row) else moved
}

fun openCategory(place: BrowsePlace, category: BrowseCategory): BrowsePlace {
    return place.copy(category = category, inList = true, index = 0, playlistId = null, groupKey = null)
}

fun backBrowse(place: BrowsePlace): BrowsePlace {
    if (place.playlistId != null || place.groupKey != null) {
        return place.copy(playlistId = null, groupKey = null, index = 0)
    }
    if (place.inList) return place.copy(inList = false, index = place.category.ordinal)
    return place
}

private fun clockShort(seconds: Int): String {
    val whole = seconds.coerceAtLeast(0)
    return "%d:%02d".format(whole / 60, whole % 60)
}

private fun Int.floorMod(n: Int): Int {
    val m = this % n
    return if (m < 0) m + n else m
}

data class DeckFace(
    val playing: Boolean = false,
    val position: Float = 0f,
    val pitch: Float = 1f,
    val range: Double = DEFAULT_TEMPO_RANGE,
    val fader: Float = 0.5f,
    val remain: Boolean = true,
    val sync: Boolean = false,
    val keyLock: Boolean = false,
    val filter: Int = 64,
    val onAir: Boolean = true,
)

val LETTER_COLORS = intArrayOf(
    0xFF3B3B,
    0x2EE0FF,
    0x3DDC6A,
    0xB44DFF,
    0x3DDC6A,
    0xFF8A1E,
    0x3B6CFF,
    0xFFE14A,
)

val PHRASE_COLORS = intArrayOf(0x7A4DFF, 0x3DDC6A, 0xFF8A1E, 0xE23B4A)

fun phraseColor(barIndex: Int): Int = PHRASE_COLORS.let { colors ->
    val block = if (barIndex < 0) 0 else barIndex / 4
    colors[block % colors.size]
}

fun barsFromSeconds(seconds: Double, bpm: Double): Double {
    if (bpm <= 1.0) return 0.0
    return seconds / (240.0 / bpm)
}

/** 0 eco, 1 delay, 2 flanger. Os outros nomes não entram no motor. */
fun beatFxAudioKind(effect: Int): Int = when (BEAT_FX_NAMES.getOrElse(effect) { "" }) {
    "Echo" -> 0
    "Delay" -> 1
    "Flanger" -> 2
    else -> 9
}

/** Fração do Beat FX em batidas. 1/1 é uma batida, não um compasso. */
fun beatFxBeats(fractionLabel: String): Double = when (fractionLabel) {
    "1/16" -> 0.0625
    "1/8" -> 0.125
    "1/4" -> 0.25
    "1/2" -> 0.5
    "3/4" -> 0.75
    "1", "1/1" -> 1.0
    "2", "2/1" -> 2.0
    "4/1" -> 4.0
    "8/1" -> 8.0
    else -> 1.0
}

private val MUSICAL_KEYS = listOf("Abm", "Ebm", "Bbm", "Fm", "Cm", "Gm", "Dm", "Am", "Em", "Bm", "F#m", "C#m", "E", "B", "F#", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A")

fun keysCompatible(focus: String, other: String): Boolean {
    if (focus.isBlank() || other.isBlank()) return false
    if (focus.equals(other, ignoreCase = true)) return true
    val camelot = Regex("(\\d+)([ABab])")
    val left = camelot.matchEntire(focus.trim())
    val right = camelot.matchEntire(other.trim())
    if (left != null && right != null) {
        val a = left.groupValues[1].toInt()
        val b = right.groupValues[1].toInt()
        val diff = kotlin.math.min(kotlin.math.abs(a - b), 12 - kotlin.math.abs(a - b))
        return diff <= 1
    }
    val ia = MUSICAL_KEYS.indexOfFirst { it.equals(focus, true) }
    val ib = MUSICAL_KEYS.indexOfFirst { it.equals(other, true) }
    if (ia < 0 || ib < 0) return false
    val da = ia % 12
    val db = ib % 12
    val diff = kotlin.math.min(kotlin.math.abs(da - db), 12 - kotlin.math.abs(da - db))
    return diff <= 1
}

fun loopLengthLabel(startMs: Int, endMs: Int, bpm: Double): String {
    val seconds = (endMs - startMs) / 1000.0
    if (bpm <= 1.0) return "%.1fs".format(seconds)
    val beats = seconds / (60.0 / bpm)
    val rounded = (beats * 4.0).roundToInt() / 4.0
    return if (abs(rounded - rounded.toInt()) < 0.01) "${rounded.toInt()} beats" else "${"%.2f".format(rounded)} beats"
}
