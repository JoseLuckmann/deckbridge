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

data class GridEdit(val shiftMs: Double = 0.0, val stretch: Double = 1.0)

fun gridBpm(bpm: Double, edit: GridEdit): Double {
    if (edit.stretch <= 0.0) return bpm
    return bpm / edit.stretch
}

/** Desloca e estica a grade na memória do app. Não altera os bytes de origem. */
fun applyGrid(beats: List<AnlzBeat>, edit: GridEdit): List<AnlzBeat> {
    if (beats.isEmpty() || (edit.shiftMs == 0.0 && edit.stretch == 1.0)) return beats
    val anchor = beats.first().timeMs.toDouble()
    val stretch = edit.stretch.coerceIn(0.5, 1.5)
    return beats.map { beat ->
        val moved = anchor + (beat.timeMs - anchor) * stretch + edit.shiftMs
        val nextBpm = if (stretch > 0.0) beat.bpm / stretch else beat.bpm
        beat.copy(timeMs = moved.roundToInt().coerceAtLeast(0), bpm = nextBpm)
    }
}

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

val BEAT_FX_NAMES = listOf(
    "Low Cut Echo",
    "Echo",
    "Ping Pong",
    "Spiral",
    "Reverb",
    "Trans",
    "Flanger",
    "Phaser",
    "Pitch",
    "Slip Roll",
    "Roll",
    "Helix",
    "Mobius",
    "Filter",
    "Vinyl Brake",
    // Do DJM-V10 (manual, p. 60–61): reverb com brilho e eco de uma repetição.
    "Shimmer",
    "Delay",
)
val BEAT_FX_FRACTIONS = listOf("1/16", "1/8", "1/4", "1/2", "3/4", "1/1", "2/1", "4/1", "8/1")

data class BeatFxState(
    val effect: Int = 0,
    val fraction: Int = 5,
    val channel: Int = 0,
    val on: Boolean = false,
    val level: Int = 64,
    /** FX FREQUENCY (DJM-V10): LOW, MID, HI. A banda apagada sai sem efeito. */
    val bands: List<Boolean> = listOf(true, true, true),
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

enum class ListSort { TITLE, BPM, KEY, TIME }

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
    MATCHING("Matching"),
    SUGGEST("Suggest"),
}

data class BrowsePlace(
    val category: BrowseCategory = BrowseCategory.PLAYLIST,
    val inList: Boolean = false,
    val index: Int = 0,
    val playlistId: Int? = null,
    val groupKey: String? = null,
    val query: String = "",
    val bpmMin: Int = 0,
    val bpmMax: Int = 999,
    val keyFilter: String = "",
    val matchKey: String = "",
    val sort: ListSort? = null,
    val sortDesc: Boolean = false,
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
    val note: String = "",
)

fun browseRows(
    catalog: RbCatalog,
    place: BrowsePlace,
    historyIds: List<Int>,
    master: RbTrack? = null,
    gentle: Boolean = true,
): List<BrowseRow> {
    val filtered = applyLibraryFilter(rawBrowseRows(catalog, place, historyIds, master, gentle), place)
    return sortBrowseRows(filtered, place.sort, place.sortDesc)
}

fun toggleListSort(place: BrowsePlace, sort: ListSort): BrowsePlace {
    return if (place.sort == sort) place.copy(sortDesc = !place.sortDesc) else place.copy(sort = sort, sortDesc = false)
}

fun sortBrowseRows(rows: List<BrowseRow>, sort: ListSort?, desc: Boolean): List<BrowseRow> {
    if (sort == null) return rows
    val indexed = rows.withIndex().sortedWith { left, right ->
        val a = left.value.track
        val b = right.value.track
        if (a == null || b == null) {
            left.index.compareTo(right.index)
        } else {
            val cmp = when (sort) {
                ListSort.TITLE -> a.title.compareTo(b.title, ignoreCase = true)
                ListSort.BPM -> a.bpm.compareTo(b.bpm)
                ListSort.KEY -> a.keyName.compareTo(b.keyName, ignoreCase = true)
                ListSort.TIME -> a.durationSeconds.compareTo(b.durationSeconds)
            }
            val signed = if (desc) -cmp else cmp
            if (signed != 0) signed else left.index.compareTo(right.index)
        }
    }
    return indexed.map { it.value }
}

fun applyLibraryFilter(rows: List<BrowseRow>, place: BrowsePlace): List<BrowseRow> {
    val narrow = place.bpmMin > 0 || place.bpmMax < 999
    if (place.matchKey.isBlank() && !narrow) return rows
    return rows.filter { row ->
        val track = row.track ?: return@filter true
        val bpmOk = !narrow || track.bpm <= 0.0 || (track.bpm >= place.bpmMin && track.bpm <= place.bpmMax)
        val keyOk = place.matchKey.isBlank() || keysCompatible(place.matchKey, track.keyName)
        bpmOk && keyOk
    }
}

private fun rawBrowseRows(
    catalog: RbCatalog,
    place: BrowsePlace,
    historyIds: List<Int>,
    master: RbTrack?,
    gentle: Boolean,
): List<BrowseRow> {
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
            filterTracks(tracks, place.query, place.bpmMin, place.bpmMax, place.keyFilter).map(::row)
        }
        BrowseCategory.MATCHING -> {
            if (master == null) return listOf(BrowseRow("Sem master no deck", "Carregue uma faixa", "", "", "", null, null, null, false))
            matchingRows(tracks, master).map(::row)
        }
        BrowseCategory.SUGGEST -> {
            if (master == null) return listOf(BrowseRow("Sem master no deck", "Carregue uma faixa", "", "", "", null, null, null, false))
            val playlists = catalog.playlists
            suggestRows(
                tracks,
                master,
                { id -> catalog.analyses[id]?.energy ?: 0.0 },
                historyIds.toSet(),
                { id -> playlists.any { playlist -> master.id in playlist.trackIds && id in playlist.trackIds } },
                gentle,
            ).map { ranked ->
                row(ranked.track).copy(note = ranked.reason)
            }
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
    val keyShift: Int = 0,
    val slip: Boolean = false,
    /** 0 = nenhum, 1 = o prato move a entrada do loop, 2 = a saída (SHIFT+LOOP IN/OUT da DDJ-400). */
    val loopAdjust: Int = 0,
    val cueBank: Int = 0,
    val emergency: Boolean = false,
    val filter: Int = 64,
    val onAir: Boolean = true,
    val loopStart: Float = -1f,
    val loopEnd: Float = -1f,
    val memoryStart: Float = -1f,
    val memoryEnd: Float = -1f,
    val jumpScale: Double = 1.0,
    val lastJump: Double = 0.0,
    /** Valor do BEAT JUMP (as setas ◄ ► do leitor saltam este número de batidas). Padrão 16, como no leitor. */
    val jumpBeats: Int = 16,
    val reversed: Boolean = false,
    val activeLoopArmed: Boolean = false,
    val gridShiftMs: Double = 0.0,
    val gridStretch: Double = 1.0,
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

/** O índice é o efeito no motor. Slip Roll, Roll e Vinyl Brake mexem no transporte; o resto é inserção. */
fun beatFxAudioKind(effect: Int): Int = effect.coerceIn(0, BEAT_FX_NAMES.lastIndex)

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

// Camelot de cada nota: maior (B) e menor (A), com sustenido e bemol. A tabela única do app inteiro (performance,
// biblioteca, computador): quem precisa de tom usa [camelotOf] / [camelotNumber] / [camelotDistance] (ADR-0011).
private val MAJOR_CAMELOT = mapOf("b" to 1, "cb" to 1, "f#" to 2, "gb" to 2, "db" to 3, "c#" to 3, "ab" to 4, "g#" to 4, "eb" to 5, "d#" to 5, "bb" to 6, "a#" to 6, "f" to 7, "e#" to 7, "c" to 8, "b#" to 8, "g" to 9, "d" to 10, "a" to 11, "e" to 12, "fb" to 12)
private val MINOR_CAMELOT = mapOf("g#" to 1, "ab" to 1, "d#" to 2, "eb" to 2, "bb" to 3, "a#" to 3, "f" to 4, "e#" to 4, "c" to 5, "b#" to 5, "g" to 6, "d" to 7, "a" to 8, "e" to 9, "fb" to 9, "b" to 10, "cb" to 10, "f#" to 11, "gb" to 11, "c#" to 12, "db" to 12)

/** Camelot da notação musical ("Am", "F#m", "Bbmin", "C♯", "8a"). Uma chave que já é Camelot volta igual. Null se não entender. */
fun camelotOf(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    val camelot = Regex("(1[0-2]|0?[1-9])([ABab])").matchEntire(trimmed)
    if (camelot != null) return camelot.groupValues[1].toInt().toString() + camelot.groupValues[2].uppercase()
    val m = Regex("([A-Ga-g][#b♯♭]?)\\s*(m|min|minor|maj|major)?", RegexOption.IGNORE_CASE).matchEntire(trimmed) ?: return null
    val note = m.groupValues[1].lowercase().replace('♯', '#').replace('♭', 'b')
    val minor = m.groupValues[2].lowercase().let { it == "m" || it.startsWith("min") }
    val n = (if (minor) MINOR_CAMELOT[note] else MAJOR_CAMELOT[note]) ?: return null
    return "$n" + if (minor) "A" else "B"
}

private val ROOT_PITCH = intArrayOf(
    8, 3, 10, 5, 0, 7, 2, 9, 4, 11, 6, 1,
    4, 11, 6, 1, 8, 3, 10, 5, 0, 7, 2, 9,
)

fun rootPitch(name: String): Int? {
    val camelot = camelotOf(name) ?: return null
    val number = camelot.dropLast(1).toIntOrNull() ?: return null
    if (number !in 1..12) return null
    val index = if (camelot.last() == 'A') number - 1 else 12 + (number % 12)
    if (index !in ROOT_PITCH.indices) return null
    return ROOT_PITCH[index]
}

fun signedSemitone(from: Int, to: Int): Int {
    var delta = (to - from) % 12
    if (delta > 6) delta -= 12
    if (delta < -6) delta += 12
    return delta
}

fun relatedCamelot(name: String): List<String> {
    val camelot = camelotOf(name) ?: return emptyList()
    val number = camelot.dropLast(1).toIntOrNull() ?: return emptyList()
    if (number !in 1..12) return emptyList()
    val letter = camelot.last()
    val other = if (letter == 'A') 'B' else 'A'
    fun wrap(n: Int): Int {
        val m = (n - 1) % 12
        return (if (m < 0) m + 12 else m) + 1
    }
    return listOf(
        "$number$letter",
        "${wrap(number - 1)}$letter",
        "${wrap(number + 1)}$letter",
        "$number$other",
        "${wrap(number - 1)}$other",
        "${wrap(number + 1)}$other",
    )
}

/** O tom (Camelot) depois de subir [semi] semitons (tocar mais rápido sem Master Tempo): cada semitom anda 7 casas. */
fun camelotShift(key: Pair<Int, Char>, semi: Int): Pair<Int, Char> = (Math.floorMod(key.first - 1 + 7 * semi, 12) + 1) to key.second

/** Semitons para o tom do master, entre os seis vizinhos descritos no manual do leitor. */
fun keySyncShift(playingKey: String, masterKey: String): Int {
    val from = rootPitch(playingKey) ?: return 0
    val targets = relatedCamelot(masterKey).mapNotNull(::rootPitch)
    if (targets.isEmpty()) return 0
    return targets.minBy { kotlin.math.abs(signedSemitone(from, it)) }.let { signedSemitone(from, it) }
}

fun fineTempoText(percent: Float): String {
    val sign = if (percent >= 0f) "+" else ""
    return "$sign${"%.2f".format(percent)}%"
}

fun nextCueSeconds(analysis: RbAnalysis?, positionSec: Double): Double? {
    if (analysis == null) return null
    var nearest = Double.POSITIVE_INFINITY
    analysis.hotCues.forEach { cue ->
        val time = cue.timeMs / 1000.0
        if (time > positionSec + 0.04 && time < nearest) nearest = time
    }
    analysis.memoryCues.forEach { cue ->
        val time = cue.timeMs / 1000.0
        if (time > positionSec + 0.04 && time < nearest) nearest = time
    }
    return if (nearest.isFinite()) nearest else null
}

/** O loop gravado entra quando a cabeça cruza o início, não no instante da carga. */
fun arrivedAtActiveLoop(previous: Float, position: Float, memoryStart: Float, memoryEnd: Float): Boolean {
    if (!(memoryEnd > memoryStart)) return false
    return previous < memoryStart && position >= memoryStart && position < memoryEnd
}

fun beatsUntilCue(positionSec: Double, cueSec: Double, bpm: Double): Double? {
    if (bpm <= 1.0 || cueSec <= positionSec + 0.04) return null
    return (cueSec - positionSec) / (60.0 / bpm)
}

fun phraseBarsRemaining(positionSec: Double, bpm: Double, phraseBars: Int = 16): Int {
    if (bpm <= 1.0 || phraseBars <= 0) return 0
    val bar = 240.0 / bpm
    val index = (positionSec.coerceAtLeast(0.0) / bar).toInt()
    return phraseBars - (index % phraseBars)
}

fun emergencyLoopRange(positionSec: Double, durationSec: Double, bpm: Double, loopActive: Boolean): Pair<Float, Float>? {
    if (loopActive || bpm <= 1.0 || durationSec <= 1.0) return null
    val beat = 60.0 / bpm
    val remain = durationSec - positionSec
    if (remain <= 0.0 || remain > beat * 8.0) return null
    val start = (durationSec - beat * 4.0).coerceAtLeast(0.0)
    if (durationSec - start < 0.05) return null
    return start.toFloat() to durationSec.toFloat()
}

fun needleSeconds(xPx: Float, widthPx: Float, durationSec: Double): Float {
    if (widthPx <= 0f || durationSec <= 0.0) return 0f
    return (xPx / widthPx * durationSec).toFloat().coerceIn(0f, durationSec.toFloat())
}

fun cueBankLetter(bank: Int): String {
    val index = ((bank % 8) + 8) % 8
    return ('A' + index).toString()
}

fun nextCueBank(bank: Int): Int = ((bank + 1) % 8 + 8) % 8

fun slipAnchorAfter(originSec: Double, elapsedSec: Double, pitch: Double): Double = originSec + elapsedSec * pitch

fun keysCompatible(focus: String, other: String): Boolean {
    val leftName = camelotOf(focus) ?: return false
    val rightName = camelotOf(other) ?: return false
    if (leftName.equals(rightName, ignoreCase = true)) return true
    val camelot = Regex("(\\d+)([ABab])")
    val left = camelot.matchEntire(leftName) ?: return false
    val right = camelot.matchEntire(rightName) ?: return false
    val a = left.groupValues[1].toInt()
    val b = right.groupValues[1].toInt()
    if (a !in 1..12 || b !in 1..12) return false
    val diff = kotlin.math.min(kotlin.math.abs(a - b), 12 - kotlin.math.abs(a - b))
    return diff <= 1
}

fun loopLengthLabel(startMs: Int, endMs: Int, bpm: Double): String {
    val seconds = (endMs - startMs) / 1000.0
    if (bpm <= 1.0) return "%.1fs".format(seconds)
    val beats = seconds / (60.0 / bpm)
    val rounded = (beats * 4.0).roundToInt() / 4.0
    return if (abs(rounded - rounded.toInt()) < 0.01) "${rounded.toInt()} beats" else "${"%.2f".format(rounded)} beats"
}

fun filterTracks(tracks: List<RbTrack>, query: String, bpmMin: Int, bpmMax: Int, keyFilter: String): List<RbTrack> {
    val q = query.trim()
    return tracks.filter { track ->
        val text = q.isEmpty() || track.title.contains(q, true) || track.artist.contains(q, true)
        val bpm = track.bpm <= 0.0 || (track.bpm >= bpmMin && track.bpm <= bpmMax)
        val key = keyFilter.isBlank() || track.keyName.equals(keyFilter, true)
        text && bpm && key
    }
}

fun beatJumpPadBeats(pad: Int): Double = when (pad) {
    0 -> -1.0
    1 -> 1.0
    2 -> -2.0
    3 -> 2.0
    4 -> -4.0
    5 -> 4.0
    6 -> -8.0
    7 -> 8.0
    8 -> -16.0
    9 -> 16.0
    10 -> -32.0
    11 -> 32.0
    12 -> -64.0
    else -> 64.0
}

/** Um passo ×16 ou ÷16, com o limite do pad 2 do script oficial. */
fun stepJumpScale(scale: Double, increase: Boolean): Double {
    val pad2 = scale
    return if (increase) {
        if (pad2 * 16.0 > 16.0) scale else scale * 16.0
    } else {
        if (pad2 / 16.0 < 1.0 / 16.0) scale else scale / 16.0
    }
}

fun jumpSeconds(
    positionSec: Double,
    beats: Double,
    bpm: Double,
    sampleRate: Int,
    quantize: Boolean,
    quantizeBeats: Double = 1.0,
    loopStartSec: Double,
    loopEndSec: Double,
    durationSec: Double,
): Double {
    val rate = sampleRate.coerceAtLeast(1).toDouble()
    val beat = rate * 60.0 / bpm.coerceAtLeast(1.0)
    val grid = beat * quantizeBeats.coerceAtLeast(0.0625)
    var origin = positionSec * rate
    if (quantize && grid > 0.0) origin = kotlin.math.round(origin / grid) * grid
    var target = origin + beats * beat
    if (loopEndSec > loopStartSec) {
        val start = loopStartSec * rate
        val length = (loopEndSec - loopStartSec) * rate
        var over = (target - start) % length
        if (over < 0) over += length
        target = start + over
    }
    val frames = durationSec * rate
    if (target < 0.0) target = 0.0
    if (frames > 1.0 && target > frames - 1.0) target = frames - 1.0
    return target / rate
}

fun scaleLoop(start: Float, end: Float, factor: Double): Pair<Float, Float>? {
    if (end <= start || factor <= 0.0) return null
    val length = ((end - start) * factor).toFloat().coerceAtLeast(0.001f)
    return start to start + length
}

fun toggleBeatLoop(start: Float, end: Float, position: Float, beats: Double, bpm: Double): Pair<Float, Float>? {
    if (bpm <= 1.0) return null
    val length = (beats * 60.0 / bpm).toFloat()
    val active = end > start && abs((end - start) - length) < 0.02f
    if (active) return null
    return position.coerceAtLeast(0f) to position + length
}

data class PlayerSettings(
    val tempoRange: Double = DEFAULT_TEMPO_RANGE,
    val keyLock: Boolean = false,
    val quantize: Boolean = true,
    val quantizeBeats: Double = 1.0,
    val autoCue: Boolean = true,
    val hotCueAutoLoad: Boolean = true,
    val jogVinyl: Boolean = true,
    val masterLevel: Float = DEFAULT_MASTER_LEVEL,
    val waveColor: String = "RGB",
    val followUsb: Boolean = true,
    val zoomBars: Int = 8,
    val playhead: Float = 0.30f,
    val bufferFrames: Int = 256,
    val encoderFlip: Boolean = false,
    val jogSensitivity: Float = 1f,
    val historySeconds: Int = 10,
    val recordAac: Boolean = false,
    val waveView: String = "WAVE",
    val autoGain: Boolean = true,
    val targetLufs: Double = -10.0,
    val gentleSuggest: Boolean = true,
    val cuePreset: String = "drop",
    val colorFx: Int = 5,
    /** Bloqueia carregar uma faixa num deck que está tocando, como o leitor de club faz por padrão. */
    val loadLock: Boolean = true,
    /** Curva do fader de canal: 0 topo, 1 gradual, 2 linear (padrão, o comportamento de antes). */
    val chFaderCurve: Int = 2,
    /** Curva do crossfader: 0 lenta (padrão), 1 média, 2 corte. */
    val crossCurve: Int = 0,
)

/** Ordem do motor: SPACE, DUB ECHO, SWEEP, NOISE, CRUSH, FILTER. */
val COLOR_FX_NAMES = listOf("SPACE", "DUB ECHO", "SWEEP", "NOISE", "CRUSH", "FILTER")

fun zoomFromBars(bars: Int): WaveZoom = WaveZoom.entries.firstOrNull { it.bars == bars } ?: WaveZoom.BARS_8

fun waveModeIndex(name: String): Int = when (name) {
    "RGB" -> 0
    "BLUE" -> 2
    else -> 1
}

val SCENE_NAMES = listOf(
    "Echo UP",
    "Noise UP",
    "Spiral UP",
    "Reverb UP",
    "Reverb DOWN",
    "Echo DOWN",
    "Tape STOP",
    "Dub ECHO",
)

val XPAD_NAMES = listOf("Roll", "Trans", "Drum", "Hi-pass")
val XBEAT_LABELS = listOf("1/16", "1/8", "1/4", "1/2", "1")
val XBEATS = listOf(0.0625, 0.125, 0.25, 0.5, 1.0)
val SCENE_BARS = listOf(1, 2, 4, 8, 16)

data class RmxState(
    val panel: Boolean = false,
    val full: Boolean = false,
    val scene: Int = 0,
    val holding: Boolean = false,
    val intensity: Float = 0f,
    val bars: Int = 4,
    val xMode: Int = 0,
    val xBeat: Int = 2,
    val xOn: Boolean = false,
    val release: Int = -1,
    val low: List<Float> = listOf(1f, 1f),
    val mid: List<Float> = listOf(1f, 1f),
    val high: List<Float> = listOf(1f, 1f),
    val gate: Boolean = false,
    val channel: Int = 0,
    val drumBuild: Float = 0f,
    val drumVoice: Int = 3,
    val drumBars: Int = 8,
    val drumGap: Boolean = true,
    val drumPitch: Boolean = true,
    /** HOLD (como o do RMX-1000): soltar o pad não solta o efeito; a subida e a rolagem seguem até desligar o HOLD. */
    val latch: Boolean = false,
    /** O dedo está no X-PAD (segurando): o roll acelera enquanto estiver. */
    val xHeld: Boolean = false,
    /** O X-PAD ficou ligado pelo HOLD depois de soltar o dedo (desligar o HOLD desliga o X-PAD). */
    val xLatched: Boolean = false,
    /** O TEMPO do X-PAD antes de acelerar (volta a ele ao soltar). */
    val xBase: Int = 2,
    /** O DJ pegou o knob: a subida e a aceleração automáticas param e vale o que o knob diz. */
    val manual: Boolean = false,
    /** Quando o pad do X-PAD foi apertado (pad da controladora: quem decide se foi toque ou segurar é a hora de soltar). */
    val xPressAt: Long = 0L,
    /** O pad apertado já estava ligado antes do toque. */
    val xWasOn: Boolean = false,
) {
    fun sceneName(): String = SCENE_NAMES[scene.coerceIn(0, SCENE_NAMES.lastIndex)]
}

/** Quanto o dedo precisa ficar no X-PAD para valer como "segurar" (e não um toque que liga e desliga). */
const val RMX_HOLD_MS = 300L

/**
 * Pad de cena apertado ou solto. Com HOLD ligado, soltar não solta a cena: a subida segue até o máximo e fica lá até o
 * HOLD desligar (ou outra cena ser apertada).
 */
fun rmxScenePress(state: RmxState, scene: Int, down: Boolean): RmxState = when {
    down -> state.copy(scene = scene, holding = true, panel = true, manual = false)
    state.latch -> state
    else -> state.copy(holding = false)
}

/**
 * Dedo no pad do X-PAD ([mode]: Roll, Trans, Drum, Hi-pass): liga na hora e guarda o TEMPO para o roll acelerar enquanto
 * segura. No DRUM ligar arma a rolagem de bateria como sempre.
 */
fun rmxXPadDown(state: RmxState, mode: Int): RmxState = state.copy(
    xMode = mode, xOn = true, xHeld = true, xLatched = false, panel = true, manual = false,
    xBase = if (state.xHeld) state.xBase else state.xBeat,
    drumBuild = if (mode == 2 && state.drumBuild < 0.2f) 1f else state.drumBuild,
)

/**
 * Dedo solto do pad. [heldMs] é quanto ficou apertado e [wasOn] se o pad já estava ligado antes do toque.
 * - Toque rápido: liga e desliga, como antes (um segundo toque desliga).
 * - Segurar e soltar: o X-PAD vale só enquanto o dedo está (e o TEMPO volta ao de antes), a não ser com HOLD ligado, que
 *   mantém a rolagem andando.
 */
fun rmxXPadUp(state: RmxState, mode: Int, heldMs: Long, wasOn: Boolean): RmxState {
    val off = state.copy(xOn = false, xHeld = false, xLatched = false, xBeat = state.xBase, manual = false)
    return when {
        heldMs < RMX_HOLD_MS && wasOn && state.xMode == mode -> off
        heldMs < RMX_HOLD_MS -> state.copy(xHeld = false, xBeat = state.xBase)
        state.latch -> state.copy(xHeld = false, xLatched = true)
        else -> off
    }
}

/** Liga ou desliga o HOLD; ao desligar, solta o que ele segurava (a cena e o X-PAD que ficaram ligados por ele). */
fun rmxToggleHold(state: RmxState): RmxState {
    if (!state.latch) return state.copy(latch = true)
    var next = state.copy(latch = false, holding = false, manual = false)
    if (state.xLatched) next = next.copy(xOn = false, xLatched = false, xBeat = state.xBase)
    return next
}

/**
 * O roll acelera enquanto o dedo está no X-PAD (ou o HOLD o mantém): a cada compasso o TEMPO desce um passo
 * (1/2 → 1/4 → 1/8 → 1/16), com os golpes saindo no ritmo da música. No DRUM não: a bateria já sobe sozinha por compassos.
 */
fun rmxRiseStep(state: RmxState): RmxState =
    if (rmxRiseLeft(state) > 0) state.copy(xBeat = state.xBeat - 1) else state

/** Quanto falta para o roll chegar ao mais rápido (1/16): quantos passos de [rmxRiseStep]. Com o knob na mão, nenhum. */
fun rmxRiseLeft(state: RmxState): Int = if (state.xOn && state.xMode != 2 && (state.xHeld || state.xLatched) && !state.manual) state.xBeat else 0

/** O roll do X-PAD está tocando (segurado ou mantido pelo HOLD) e não é o DRUM: o knob manda na velocidade dele. */
fun rmxRolling(state: RmxState): Boolean = state.xOn && state.xMode != 2 && (state.xHeld || state.xLatched)

/**
 * O knob do RMX (o anel grande, 0 a 1): com o roll tocando, a VELOCIDADE dele (do 1/2 ao 1/16); senão, a INTENSIDADE da
 * cena que está segura. Mexer assume o controle: a subida e a aceleração automáticas param ([RmxState.manual]).
 */
fun rmxKnob(state: RmxState, value: Float): RmxState {
    val v = value.coerceIn(0f, 1f)
    return when {
        rmxRolling(state) -> state.copy(xBeat = 3 - (v * 3.999f).toInt().coerceIn(0, 3), manual = true)
        state.holding || state.latch -> state.copy(intensity = v, manual = true)
        else -> state
    }
}

/** O que o anel mostra: a velocidade do roll (0 no 1/2, 1 no 1/16) ou a intensidade da cena. */
fun rmxKnobValue(state: RmxState): Float = if (rmxRolling(state)) (3 - state.xBeat.coerceIn(0, 3)) / 3f else state.intensity

/** O texto no centro do anel: a fração do roll ("1/8") ou a intensidade em %. */
fun rmxKnobLabel(state: RmxState): String =
    if (rmxRolling(state)) XBEAT_LABELS[state.xBeat.coerceIn(0, XBEAT_LABELS.lastIndex)] else "${(state.intensity * 100).toInt()}"

fun reduceRmxPad(state: RmxState, action: PerformanceAction, nowMs: Long = System.currentTimeMillis()): RmxState {
    if (action.kind != PerformanceAction.Kind.RMX) return state
    val pad = action.hotIndex
    val down = (action.slider ?: 0.0) > 0.0
    return when {
        pad in 0..3 && down -> rmxScenePress(state, pad, true)
        pad in 0..3 && !down -> if (state.latch) state else state.copy(holding = false, intensity = 0f)
        // Os pads do X-PAD da controladora: tocar liga e desliga; segurar toca enquanto aperta (e acelera); com HOLD, segue.
        pad in 4..7 && down -> rmxXPadDown(state, pad - 4).copy(xPressAt = nowMs, xWasOn = state.xOn && state.xMode == pad - 4)
        pad in 4..7 && !down -> if (state.xHeld && state.xMode == pad - 4) rmxXPadUp(state, pad - 4, nowMs - state.xPressAt, state.xWasOn) else state
        pad in 8..11 && down -> state.copy(
            drumVoice = pad - 8,
            xMode = 2,
            xOn = true,
            panel = true,
            drumBuild = if (state.drumBuild < 0.2f) 1f else state.drumBuild,
        )
        pad == 12 && down -> state.copy(drumBars = 4, xMode = 2, xOn = true, panel = true)
        pad == 13 && down -> state.copy(drumBars = 8, xMode = 2, xOn = true, panel = true)
        pad == 14 && down -> state.copy(drumBars = 16, xMode = 2, xOn = true, panel = true)
        pad == 15 && down -> state.copy(drumGap = !state.drumGap, xMode = 2, xOn = true, panel = true)
        else -> state
    }
}

val DRUM_VOICES = listOf("KICK", "CLAP", "SNARE", "KICK+CLAP")

fun drumPatternLabel(state: RmxState): String {
    val voice = DRUM_VOICES[state.drumVoice.coerceIn(0, DRUM_VOICES.lastIndex)]
    val gap = if (state.drumGap) " · GAP" else ""
    val pitch = if (state.drumPitch) " · PITCH" else ""
    return "$voice · ${state.drumBars}c 1/4→1/32$gap$pitch"
}

fun rmxPayload(state: RmxState, bpm: Float, gridDeck: Int = state.channel.coerceIn(0, 1)): FloatArray = floatArrayOf(
    if (state.panel) 1f else 0f,
    state.scene.toFloat(),
    if (state.holding) 1f else 0f,
    state.intensity,
    state.bars.toFloat(),
    state.xMode.toFloat(),
    XBEATS[state.xBeat.coerceIn(0, XBEATS.lastIndex)].toFloat(),
    if (state.xOn) 1f else 0f,
    state.release.toFloat(),
    state.low.getOrElse(0) { 1f },
    state.mid.getOrElse(0) { 1f },
    state.high.getOrElse(0) { 1f },
    state.low.getOrElse(1) { 1f },
    state.mid.getOrElse(1) { 1f },
    state.high.getOrElse(1) { 1f },
    if (state.gate) 1f else 0f,
    state.channel.toFloat(),
    bpm,
    if (state.xOn && state.xMode == 2) state.drumBuild.coerceIn(0.05f, 1f) else state.drumBuild.coerceIn(0f, 1f),
    state.drumVoice.coerceIn(0, 3).toFloat(),
    state.drumBars.coerceIn(4, 16).toFloat(),
    if (state.drumGap) 1f else 0f,
    if (state.drumPitch) 1f else 0f,
    gridDeck.coerceIn(0, 1).toFloat(),
)

fun drumBuildDivision(build: Float): String = when {
    build < 0.04f -> "GRADE"
    build < 0.25f -> "1/4"
    build < 0.50f -> "1/8"
    build < 0.75f -> "1/16"
    else -> "1/32"
}

fun mergedTagNames(catalog: Map<Int, List<RbTag>>, added: Map<Int, Set<String>>, removed: Map<Int, Set<String>>): Map<Int, List<String>> {
    val ids = catalog.keys + added.keys + removed.keys
    return ids.associateWith { id ->
        val base = catalog[id].orEmpty().map { it.name }.toMutableSet()
        base += added[id].orEmpty()
        base -= removed[id].orEmpty()
        base.filter { it.isNotBlank() }.sorted()
    }.filterValues { it.isNotEmpty() }
}

/** Valores do BEAT JUMP em ordem; tocar na caixa passa para o próximo e volta ao primeiro depois do último. */
val JUMP_SIZES = listOf(1, 2, 4, 8, 16, 32, 64)

fun nextJumpSize(current: Int): Int {
    val index = JUMP_SIZES.indexOf(current)
    return if (index < 0) 16 else JUMP_SIZES[(index + 1) % JUMP_SIZES.size]
}
