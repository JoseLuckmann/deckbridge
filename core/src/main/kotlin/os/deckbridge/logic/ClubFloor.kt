package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class PhraseSpan(
    val startMs: Int,
    val endMs: Int,
    val kind: String,
    val label: String,
    val mood: String = "",
    val fillMs: Int? = null,
)

/** Cores deste app para os nomes publicados da frase. Não é um asset copiado. */
fun phraseKindColor(kind: String): Int = when (kind) {
    Section.INTRO -> 0x3B6CFF
    "Up" -> 0xFF8A1E
    "Down" -> 0x3DDC6A
    "Chorus" -> 0xE23B4A
    "Bridge" -> 0xB44DFF
    "Verse" -> 0x2EE0FF
    Section.OUTRO -> 0x5C6B7A
    else -> 0x7A4DFF
}

fun phraseFamily(label: String): String = when {
    label.startsWith(Section.INTRO) -> Section.INTRO
    label.startsWith("Up") -> "Up"
    label.startsWith("Down") -> "Down"
    label.startsWith("Chorus") -> "Chorus"
    label.startsWith("Bridge") -> "Bridge"
    label.startsWith("Verse") -> "Verse"
    label.startsWith(Section.OUTRO) -> Section.OUTRO
    else -> label
}

data class SectionRead(
    val points: StructurePoints,
    val phrases: List<PhraseSpan>,
)

/**
 * Fronteiras na grelha de 8 compassos, e de 16 quando o pico cai a menos de 3 compassos dessa linha.
 * O 32 cai nessa regra porque também é múltiplo de 16.
 */
fun snapBarIndex(bar: Double): Int {
    if (bar <= 0.0) return 0
    val coarse = (bar / 16.0).roundToInt() * 16
    if (abs(bar - coarse) <= 3.0) return coarse.coerceAtLeast(0)
    return ((bar / 8.0).roundToInt() * 8).coerceAtLeast(0)
}

/** Reparte cada janela pelos compassos que ela cobre. Uma janela maior que o compasso não deixa buracos. */
fun resampleToBars(low: DoubleArray, high: DoubleArray, windowSec: Double, barSec: Double, bars: Int): Pair<DoubleArray, DoubleArray> {
    val lowBar = DoubleArray(bars)
    val highBar = DoubleArray(bars)
    val weight = DoubleArray(bars)
    if (bars <= 0 || barSec <= 0.0 || windowSec <= 0.0) return lowBar to highBar
    for (index in low.indices) {
        val start = index * windowSec
        val end = start + windowSec
        val highValue = high.getOrElse(index) { 0.0 }
        var bar = (start / barSec).toInt().coerceAtLeast(0)
        while (bar < bars) {
            val barStart = bar * barSec
            if (barStart >= end) break
            val overlap = min(end, barStart + barSec) - max(start, barStart)
            if (overlap > 0.0) {
                lowBar[bar] += low[index] * overlap
                highBar[bar] += highValue * overlap
                weight[bar] += overlap
            }
            bar += 1
        }
    }
    for (bar in 0 until bars) {
        if (weight[bar] > 0.0) {
            lowBar[bar] /= weight[bar]
            highBar[bar] /= weight[bar]
        }
    }
    return lowBar to highBar
}

fun detectSections(low: DoubleArray, high: DoubleArray, windowSec: Double, bpm: Double, durationSec: Double): SectionRead {
    val coarse = structurePoints(low, high, windowSec)
    if (bpm <= 1.0 || windowSec <= 0.0 || durationSec <= 1.0 || low.isEmpty()) {
        return SectionRead(coarse, phrasesFromPoints(coarse, durationSec, ""))
    }
    val barSec = 240.0 / bpm
    val bars = max(1, (durationSec / barSec).roundToInt())
    val (lowBar, highBar) = resampleToBars(low, high, windowSec, barSec, bars)
    val smoothLow = DoubleArray(bars) { bar ->
        val from = max(0, bar - 1)
        val to = min(bars - 1, bar + 1)
        var acc = 0.0
        for (index in from..to) acc += lowBar[index]
        acc / (to - from + 1)
    }
    var maxLow = 1e-9
    for (bar in 0 until bars) if (smoothLow[bar] > maxLow) maxLow = smoothLow[bar]
    val novelty = DoubleArray(bars)
    for (bar in 1 until bars) {
        val dLow = lowBar[bar] - lowBar[bar - 1]
        val dHigh = highBar[bar] - highBar[bar - 1]
        val cosine = cosine3(lowBar[bar - 1], highBar[bar - 1], lowBar[bar], highBar[bar])
        novelty[bar] = max(0.0, dLow) + max(0.0, dHigh) + (1.0 - cosine)
    }
    fun nearPeak(raw: Int, rise: Boolean): Int {
        var best = raw
        var score = -1.0
        val from = max(1, raw - 4)
        val to = min(bars - 1, raw + 4)
        for (bar in from..to) {
            val dLow = lowBar[bar] - lowBar[bar - 1]
            val cosine = cosine3(lowBar[bar - 1], highBar[bar - 1], lowBar[bar], highBar[bar])
            val local = if (rise) novelty[bar] else max(0.0, -dLow) + (1.0 - cosine)
            if (local > score) {
                score = local
                best = bar
            }
        }
        return snapBarIndex(best.toDouble()).coerceAtMost(bars)
    }
    val opening = (0 until min(4, bars)).sumOf { smoothLow[it] } / min(4, bars).coerceAtLeast(1)
    val dropRaw = if (opening >= maxLow * 0.62) 0
    else (4 until bars).firstOrNull { smoothLow[it] >= maxLow * 0.62 } ?: (bars * 0.4).toInt()
    val dropBar = if (dropRaw == 0) 0 else nearPeak(dropRaw, true)
    val buildRaw = (1 until dropBar.coerceAtLeast(1)).maxByOrNull { highBar[it] - highBar[it - 1] } ?: 0
    var buildBar = if (buildRaw <= 0) 0 else nearPeak(buildRaw, true)
    if (buildBar >= dropBar) buildBar = snapBarIndex((dropBar - 8).coerceAtLeast(0).toDouble())
    val breakRaw = ((dropBar + 4) until bars).firstOrNull { smoothLow[it] < maxLow * 0.45 }
    var breakBar = if (breakRaw == null) dropBar else nearPeak(breakRaw, false)
    if (breakBar < dropBar) breakBar = dropBar
    val quietReturn = ((breakBar + 4) until bars).firstOrNull { bar ->
        smoothLow[bar] > smoothLow[breakBar.coerceAtMost(bars - 1)] * 1.4 && smoothLow[bar] < maxLow * 0.75
    }
    val finalFall = ((breakBar + 4) until bars).lastOrNull { bar ->
        val before = smoothLow[(bar - 1).coerceAtLeast(0)]
        var ahead = 0.0
        val span = 4
        for (step in 0 until span) ahead += smoothLow[(bar + step).coerceAtMost(bars - 1)]
        ahead /= span
        before > maxLow * 0.7 && ahead < maxLow * 0.55
    }
    val outroRaw = listOfNotNull(quietReturn, finalFall).maxOrNull() ?: (bars - 8).coerceAtLeast(breakBar)
    val outroFalls = finalFall != null && outroRaw == finalFall && (quietReturn == null || finalFall >= quietReturn)
    var outroBar = nearPeak(outroRaw, !outroFalls)
    if (outroBar < breakBar) outroBar = snapBarIndex(breakBar.toDouble())
    val introSec = 0.0
    val buildSec = buildBar * barSec
    val dropSec = dropBar * barSec
    val breakSec = breakBar * barSec
    val outroSec = min(durationSec, outroBar * barSec)
    val points = StructurePoints(introSec, buildSec, dropSec, breakSec, outroSec)
    return SectionRead(points, phrasesFromPoints(points, durationSec, "high"))
}

fun phrasesFromPoints(points: StructurePoints, durationSec: Double, mood: String): List<PhraseSpan> {
    if (durationSec <= 0.0) return emptyList()
    val end = (durationSec * 1000).roundToInt()
    val marks = listOf(
        0 to Section.INTRO,
        (points.buildSec * 1000).roundToInt() to "Up",
        (points.dropSec * 1000).roundToInt() to "Chorus",
        (points.breakSec * 1000).roundToInt() to "Down",
        (points.outroSec * 1000).roundToInt() to "Outro",
    ).map { (time, kind) -> time.coerceIn(0, end) to kind }
        .distinctBy { it.first }
        .sortedBy { it.first }
    if (marks.size < 2) return emptyList()
    return marks.mapIndexed { index, (start, kind) ->
        val stop = if (index + 1 < marks.size) marks[index + 1].first else end
        PhraseSpan(start, stop.coerceAtLeast(start), kind, kind, mood)
    }.filter { it.endMs > it.startMs }
}

fun autoCues(phrases: List<PhraseSpan>, beats: List<AnlzBeat>, preset: String): Pair<List<RbHotCue>, List<RbMemoryCue>> {
    if (phrases.isEmpty()) return emptyList<RbHotCue>() to emptyList()
    fun at(kind: String): Int? = phrases.firstOrNull { it.kind == kind }?.startMs
    val downbeat = beats.firstOrNull { it.beatInBar == 1 }?.timeMs ?: beats.firstOrNull()?.timeMs ?: phrases.first().startMs
    val slots = if (preset == "intro") {
        listOf(0 to (phrases.firstOrNull()?.startMs ?: 0), 1 to at("Up"), 2 to at("Chorus"), 3 to at("Down"))
    } else {
        listOf(0 to downbeat, 1 to at("Chorus"), 2 to at("Down"), 3 to at("Outro"))
    }
    val names = if (preset == "intro") listOf("Intro", "Build", "Drop", "Break") else listOf("Downbeat", "Drop", "Break", "Outro")
    val hot = slots.mapIndexedNotNull { index, (slot, time) ->
        if (time == null) null else RbHotCue(slot, time, names[index], null)
    }
    val memory = phrases.map { phrase ->
        val comment = when (phrase.kind) {
            "Up" -> "Build"
            "Chorus" -> "Drop"
            "Down" -> "Break"
            else -> phrase.label
        }
        RbMemoryCue(phrase.startMs, null, comment)
    }
    return hot to memory
}

fun banksFromCues(cues: List<RbHotCue>): List<List<RbHotCue>> {
    if (cues.isEmpty()) return emptyList()
    val banks = mutableListOf<MutableList<RbHotCue>>()
    var current = mutableListOf<RbHotCue>()
    val seen = mutableSetOf<Int>()
    cues.sortedWith(compareBy({ it.index }, { it.timeMs })).forEach { cue ->
        val slot = ((cue.index % 8) + 8) % 8
        if (slot in seen) {
            banks += current
            current = mutableListOf()
            seen.clear()
        }
        seen += slot
        current += cue.copy(index = slot)
    }
    if (current.isNotEmpty()) banks += current
    return banks
}

fun camelotNumber(name: String): Pair<Int, Char>? {
    val camelot = camelotOf(name) ?: return null
    val number = camelot.dropLast(1).toIntOrNull() ?: return null
    if (number !in 1..12) return null
    return number to camelot.last()
}

fun camelotDelta(from: String, to: String): Int? {
    val left = camelotNumber(from) ?: return null
    val right = camelotNumber(to) ?: return null
    if (left.second != right.second) return null
    var delta = right.first - left.first
    if (delta > 6) delta -= 12
    if (delta < -6) delta += 12
    return delta
}

/** +1, +2 e +7 no mesmo modo. O +7 é o salto de energia mais longo da roda. */
fun energyBoost(from: String, to: String): Int? {
    val delta = camelotDelta(from, to) ?: return null
    val wrapped = ((delta % 12) + 12) % 12
    return if (wrapped == 1 || wrapped == 2 || wrapped == 7) wrapped else null
}

fun bpmRatio(master: Double, other: Double): Double {
    if (master <= 1.0 || other <= 1.0) return 99.0
    val ratios = listOf(other / master, (other * 2) / master, (other / 2) / master)
    return ratios.minBy { abs(ln(it)) }
}

fun bpmWithin(master: Double, other: Double, fraction: Double = 0.06): Boolean {
    val ratio = bpmRatio(master, other)
    return abs(ratio - 1.0) <= fraction
}

data class RankedTrack(val track: RbTrack, val score: Double, val reason: String)

fun matchingRows(tracks: List<RbTrack>, master: RbTrack?): List<RbTrack> {
    if (master == null || master.bpm <= 1.0 || master.keyName.isBlank()) return emptyList()
    return tracks.filter { track ->
        track.id != master.id && bpmWithin(master.bpm, track.bpm) && keysCompatible(master.keyName, track.keyName)
    }.sortedBy { track ->
        abs(ln(bpmRatio(master.bpm, track.bpm).coerceAtLeast(1e-6))) + camelotDistance(master.keyName, track.keyName) * 0.05
    }
}

fun suggestRows(
    tracks: List<RbTrack>,
    master: RbTrack?,
    energyOf: (Int) -> Double,
    historyIds: Set<Int>,
    sharedPlaylist: (Int) -> Boolean,
    gentle: Boolean,
): List<RankedTrack> {
    if (master == null) return emptyList()
    val masterEnergy = energyOf(master.id)
    val from = camelotOf(master.keyName) ?: master.keyName
    return tracks.mapNotNull { track ->
        if (track.id == master.id || track.id in historyIds) return@mapNotNull null
        val to = camelotOf(track.keyName) ?: track.keyName.ifBlank { "—" }
        val boost = energyBoost(master.keyName, track.keyName)
        val compatible = keysCompatible(master.keyName, track.keyName)
        val harmonic = when {
            boost != null -> 4.0 - boost * 0.15
            compatible -> 3.2
            else -> 0.2
        }
        val ratio = bpmRatio(master.bpm, track.bpm)
        val bpmScore = if (track.bpm <= 1.0 || master.bpm <= 1.0) 0.0 else 2.0 - abs(ln(ratio.coerceAtLeast(1e-6))) * 4.0
        val energy = energyOf(track.id)
        val delta = energy - masterEnergy
        val energyScore = if (gentle) 1.4 - abs(delta - 0.6) else 1.0 + delta * 0.15
        val genre = if (master.genre.isNotBlank() && master.genre.equals(track.genre, true)) 1.2 else 0.0
        val folder = if (sharedPlaylist(track.id)) 0.6 else 0.0
        val score = harmonic + bpmScore + energyScore + genre + folder
        val move = when {
            boost != null -> "energia +$boost"
            camelotOf(master.keyName) == camelotOf(track.keyName) -> "mesmo tom"
            compatible -> "vizinho"
            else -> "tom longe"
        }
        val bpmText = if (track.bpm > 0) "${"%.0f".format(track.bpm)} BPM" else "BPM —"
        RankedTrack(track, score, "$from→$to, $bpmText, $move")
    }.sortedByDescending { it.score }
}

fun camelotDistance(left: String, right: String): Int {
    val a = camelotNumber(left) ?: return 6
    val b = camelotNumber(right) ?: return 6
    return camelotDistance(a, b)
}

/** 0 = mesmo tom, 1 = vizinho na roda (±1 ou troca A/B), maior = mais longe. A medida única de "combina" (ADR-0011). */
fun camelotDistance(a: Pair<Int, Char>, b: Pair<Int, Char>): Int {
    val number = min(abs(a.first - b.first), 12 - abs(a.first - b.first))
    val letter = if (a.second == b.second) 0 else 1
    return number + letter
}

fun phaseOffsetBeats(deckSec: Double, deckBpm: Double, masterSec: Double, masterBpm: Double): Double {
    if (deckBpm <= 1.0 || masterBpm <= 1.0) return 0.0
    val deckBeat = deckSec / (60.0 / deckBpm)
    val masterBeat = masterSec / (60.0 / masterBpm)
    var delta = (deckBeat - masterBeat) % 4.0
    if (delta > 2.0) delta -= 4.0
    if (delta < -2.0) delta += 4.0
    return delta
}

fun barPhase(positionSec: Double, bpm: Double): Double {
    if (bpm <= 1.0) return 0.0
    val bar = 240.0 / bpm
    val frac = (positionSec.coerceAtLeast(0.0) / bar) % 1.0
    return if (frac < 0) frac + 1 else frac
}

fun trimDb(lufs: Double, target: Double, enabled: Boolean): Double {
    if (!enabled || !lufs.isFinite()) return 0.0
    return (target - lufs).coerceIn(-12.0, 12.0)
}

fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

fun phraseNow(phrases: List<PhraseSpan>, positionSec: Double, bpm: Double): String? {
    if (phrases.isEmpty() || bpm <= 1.0) return null
    val ms = (positionSec * 1000).roundToInt()
    val phrase = phrases.lastOrNull { ms >= it.startMs && ms < it.endMs } ?: return null
    val bar = 240.0 / bpm
    val left = ((phrase.endMs / 1000.0 - positionSec) / bar).roundToInt().coerceAtLeast(1)
    return "${phrase.kind.uppercase()} $left"
}

/** LUFS integrado, um canal, com os dois filtros do BS.1770 e as duas portas. */
fun integratedLufs(samples: FloatArray, rate: Int): Double {
    if (samples.size < rate / 2 || rate < 8000) return Double.NaN
    val shelf = highShelf(rate)
    val highpass = highPass(rate)
    val weighted = FloatArray(samples.size)
    for (index in samples.indices) {
        weighted[index] = highpass.process(shelf.process(samples[index]))
    }
    val block = (rate * 0.4).roundToInt().coerceAtLeast(1)
    val hop = (rate * 0.1).roundToInt().coerceAtLeast(1)
    if (weighted.size < block) return Double.NaN
    val powers = ArrayList<Double>()
    var start = 0
    while (start + block <= weighted.size) {
        var acc = 0.0
        for (index in start until start + block) {
            val sample = weighted[index].toDouble()
            acc += sample * sample
        }
        powers += acc / block
        start += hop
    }
    if (powers.isEmpty()) return Double.NaN
    fun loud(power: Double) = -0.691 + 10.0 * log10(power.coerceAtLeast(1e-12))
    val absolute = powers.filter { loud(it) >= -70.0 }
    if (absolute.isEmpty()) return loud(powers.average())
    val ungated = absolute.average()
    val relative = loud(ungated) - 10.0
    val gated = absolute.filter { loud(it) >= relative }
    val mean = if (gated.isEmpty()) ungated else gated.average()
    return loud(mean)
}

fun energyLevel(samples: FloatArray): Double {
    if (samples.isEmpty()) return 0.0
    var acc = 0.0
    for (sample in samples) acc += sample * sample
    val rms = sqrt(acc / samples.size).coerceAtLeast(1e-9)
    val db = 20.0 * log10(rms)
    return ((db + 42.0) / 3.4).coerceIn(0.0, 10.0)
}

private fun cosine3(a0: Double, a1: Double, b0: Double, b1: Double): Double {
    val dot = a0 * b0 + a1 * b1
    val left = sqrt(a0 * a0 + a1 * a1)
    val right = sqrt(b0 * b0 + b1 * b1)
    if (left < 1e-9 || right < 1e-9) return 1.0
    return (dot / (left * right)).coerceIn(-1.0, 1.0)
}

private class LoudBiquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
    private val nb0 = (b0 / a0).toFloat()
    private val nb1 = (b1 / a0).toFloat()
    private val nb2 = (b2 / a0).toFloat()
    private val na1 = (a1 / a0).toFloat()
    private val na2 = (a2 / a0).toFloat()
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = nb0 * x + z1
        z1 = nb1 * x - na1 * y + z2
        z2 = nb2 * x - na2 * y
        return y
    }
}

private fun highShelf(rate: Int): LoudBiquad {
    val db = 3.999843853973347
    val f0 = 1681.974450955533
    val q = 0.7071752369554196
    val a = 10.0.pow(db / 40.0)
    val w0 = 2.0 * PI * f0 / rate
    val alpha = sin(w0) / (2.0 * q)
    val cos0 = cos(w0)
    val b0 = a * ((a + 1) + (a - 1) * cos0 + 2 * sqrt(a) * alpha)
    val b1 = -2 * a * ((a - 1) + (a + 1) * cos0)
    val b2 = a * ((a + 1) + (a - 1) * cos0 - 2 * sqrt(a) * alpha)
    val a0 = (a + 1) - (a - 1) * cos0 + 2 * sqrt(a) * alpha
    val a1 = 2 * ((a - 1) - (a + 1) * cos0)
    val a2 = (a + 1) - (a - 1) * cos0 - 2 * sqrt(a) * alpha
    return LoudBiquad(b0, b1, b2, a0, a1, a2)
}

private fun highPass(rate: Int): LoudBiquad {
    val f0 = 38.13547087602444
    val q = 0.5003270373238773
    val w0 = 2.0 * PI * f0 / rate
    val alpha = sin(w0) / (2.0 * q)
    val cos0 = cos(w0)
    val b0 = (1 + cos0) / 2.0
    val b1 = -(1 + cos0)
    val b2 = (1 + cos0) / 2.0
    val a0 = 1 + alpha
    val a1 = -2 * cos0
    val a2 = 1 - alpha
    return LoudBiquad(b0, b1, b2, a0, a1, a2)
}

internal fun pssiLabel(mood: Int, kind: Int, k1: Int, k2: Int, k3: Int): String {
    if (mood == 1) {
        return when (kind) {
            1 -> if (k1 == 0) "Intro 2" else "Intro 1"
            2 -> when {
                k2 == 0 && k3 == 0 -> "Up 1"
                k2 == 0 && k3 == 1 -> "Up 2"
                else -> "Up 3"
            }
            3 -> "Down"
            5 -> if (k1 == 0) "Chorus 2" else "Chorus 1"
            6 -> if (k1 == 0) "Outro 2" else "Outro 1"
            else -> "Up"
        }
    }
    val low = listOf("Intro", "Verse 1", "Verse 1", "Verse 1", "Verse 2", "Verse 2", "Verse 2", "Bridge", "Chorus", "Outro")
    val mid = listOf("Intro", "Verse 1", "Verse 2", "Verse 3", "Verse 4", "Verse 5", "Verse 6", "Bridge", "Chorus", "Outro")
    val table = if (mood == 3) low else mid
    return table.getOrElse(kind - 1) { "Verse" }
}

internal fun unmaskPssi(body: ByteArray, count: Int) {
    val base = intArrayOf(
        0xCB, 0xE1, 0xEE, 0xFA, 0xE5, 0xEE, 0xAD, 0xEE, 0xE9,
        0xD2, 0xE9, 0xEB, 0xE1, 0xE9, 0xF3, 0xE8, 0xE9, 0xF4, 0xE1,
    )
    for (index in body.indices) {
        val mask = (base[index % base.size] + (count and 0xFF)) and 0xFF
        body[index] = (body[index].toInt() xor mask).toByte()
    }
}
