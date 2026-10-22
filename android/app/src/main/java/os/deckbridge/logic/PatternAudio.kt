package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

private val LETTER = intArrayOf(0xFF3B3B, 0x2EE0FF, 0x3DDC6A, 0xB44DFF, 0x3DDC6A, 0xFF8A1E, 0x3B6CFF, 0xFFE14A)

data class PatternSong(
    val title: String,
    val artist: String,
    val album: String,
    val key: String,
    val bpm: Int,
    val seconds: Int,
    val rootHz: Double,
)

fun patternSongs(): List<PatternSong> {
    val titles = listOf(
        "Night Bus", "Glasshouse", "Harbor Line", "Copper Room", "Soft Alarm", "Marble Stairs",
        "Late Signal", "Paper Lantern", "North Pier", "Low Ceiling", "Amber Yard", "Static Garden",
        "Red Thread", "Cold Open", "Second Room", "Afterimage", "Small Hours", "Drift Key",
        "Side Door", "Warm Concrete", "Last Tram", "Blue Circuit",
    )
    val artists = listOf("Nara Vale", "Lumen K", "Otis March", "Kite Room")
    val albums = listOf("North Line", "Second Shift", "Warm Concrete")
    val keys = listOf("Am", "Ebm", "F#m", "Dm", "Gm", "Cm", "Bb", "F", "Em", "Bm", "C", "Abm")
    val bpms = listOf(124, 122, 128, 118, 110, 126, 132, 100, 96, 138, 104, 116, 140, 108, 112, 99, 90, 121, 130, 102, 136, 94)
    val roots = listOf(110.0, 155.6, 92.5, 146.8, 98.0, 130.8, 116.5, 87.3, 82.4, 123.5, 138.6, 103.8, 116.5, 130.8, 123.5, 110.0, 73.4, 138.6, 146.8, 87.3, 155.6, 103.8)
    return titles.mapIndexed { index, title ->
        PatternSong(
            title = title,
            artist = artists[index % artists.size],
            album = albums[index % albums.size],
            key = keys[index % keys.size],
            bpm = bpms[index],
            seconds = if (index < 8) 32 else 18,
            rootHz = roots[index],
        )
    }
}

fun renderPattern(song: PatternSong, rate: Int = 22050): FloatArray {
    val total = song.seconds * rate
    val out = FloatArray(total)
    val beat = 60.0 / song.bpm
    val bar = beat * 4.0
    val bars = song.seconds / bar
    val scale = doubleArrayOf(1.0, 1.122, 1.26, 1.498, 1.682)
    var noise = (song.bpm * 17 + song.title.length * 13).toInt()
    fun rnd(): Float {
        noise = noise * 1103515245 + 12345
        return ((noise ushr 16) and 0x7FFF) / 32767f
    }
    for (index in 0 until total) {
        val t = index.toDouble() / rate
        val barIndex = (t / bar).toInt()
        val place = if (bars <= 0.0) 0.0 else barIndex / bars
        val intoBeat = t % beat
        val step = ((t % bar) / (beat / 2.0)).toInt()
        val kickOn = place < 0.42 || place >= 0.72
        val kick = if (kickOn && intoBeat < 0.09) {
            val env = exp(-intoBeat * 38.0)
            sin(2.0 * PI * (48.0 + 90.0 * exp(-intoBeat * 28.0)) * t) * env * 0.72
        } else {
            0.0
        }
        val hatOn = place < 0.18 || (place in 0.18..0.72) || place >= 0.72
        val hatEnv = if (intoBeat < beat * 0.5 && hatOn) exp(-(intoBeat % (beat * 0.5)) * 90.0) else 0.0
        val hat = (rnd() * 2f - 1f) * hatEnv * (if (step % 2 == 0) 0.12 else 0.07)
        val bassGate = when {
            place < 0.12 -> 0.25
            place in 0.42..0.58 -> 0.15
            else -> 1.0
        }
        val bassNote = scale[(barIndex + step / 2) % scale.size]
        val bass = sin(2.0 * PI * song.rootHz * bassNote * t) * bassGate * 0.28 * (if (intoBeat < beat * 0.7) 1.0 else 0.4)
        val leadOn = place >= 0.18 && place !in 0.58..0.68
        val leadNote = scale[(barIndex * 2 + step) % scale.size] * 2.0
        val leadEnv = if (leadOn && intoBeat < 0.18) exp(-intoBeat * 14.0) else 0.0
        val lead = sin(2.0 * PI * song.rootHz * leadNote * t) * leadEnv * 0.16
        val roll = if (place in 0.62..0.74) {
            val div = beat / 4.0
            val into = t % div
            exp(-into * 50.0) * 0.2 * sin(2.0 * PI * 180.0 * t)
        } else {
            0.0
        }
        out[index] = (kick + hat + bass + lead + roll).toFloat().coerceIn(-1f, 1f)
    }
    return out
}

fun analyzeBands(samples: FloatArray, rate: Int, columns: Int): WaveBands {
    val count = columns.coerceIn(32, 480)
    val low = ByteArray(count)
    val mid = ByteArray(count)
    val high = ByteArray(count)
    if (samples.isEmpty()) return WaveBands(low, mid, high)
    val lowCoef = exp(-2.0 * PI * 180.0 / rate).toFloat()
    val highCoef = exp(-2.0 * PI * 4000.0 / rate).toFloat()
    var lowState = 0f
    var highState = 0f
    val energyL = DoubleArray(count)
    val energyM = DoubleArray(count)
    val energyH = DoubleArray(count)
    val per = (samples.size / count).coerceAtLeast(1)
    var sample = 0
    for (column in 0 until count) {
        var eL = 0.0
        var eM = 0.0
        var eH = 0.0
        val end = if (column == count - 1) samples.size else minOf(samples.size, sample + per)
        var n = 0
        while (sample < end) {
            val x = samples[sample]
            lowState = lowCoef * lowState + (1f - lowCoef) * x
            highState = highCoef * highState + (1f - highCoef) * x
            val highBand = x - highState
            val midBand = x - lowState - highBand
            eL += lowState * lowState
            eM += midBand * midBand
            eH += highBand * highBand
            sample += 1
            n += 1
        }
        if (n > 0) {
            energyL[column] = sqrt(eL / n)
            energyM[column] = sqrt(eM / n)
            energyH[column] = sqrt(eH / n)
        }
    }
    fun write(energy: DoubleArray, into: ByteArray) {
        val peak = energy.maxOrNull()?.takeIf { it > 1e-6 } ?: 1.0
        energy.forEachIndexed { index, value ->
            into[index] = (value / peak * 255.0).toInt().coerceIn(0, 255).toByte()
        }
    }
    write(energyL, low)
    write(energyM, mid)
    write(energyH, high)
    return WaveBands(low, mid, high)
}

fun overviewFromBands(bands: WaveBands): ByteArray {
    val count = bands.columns
    return ByteArray(count) { index ->
        val value = maxOf(
            bands.low[index].toInt() and 0xFF,
            bands.mid[index].toInt() and 0xFF,
            bands.high[index].toInt() and 0xFF,
        )
        value.toByte()
    }
}

fun patternCues(song: PatternSong): List<CueSpec> {
    val beatMs = (60000.0 / song.bpm)
    val barMs = (beatMs * 4).toInt()
    val names = listOf("Intro", "Phase A 1", "Break1", "Build", "Drop", "Groove", "Fill", "Outro")
    val bars = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14)
    return names.mapIndexed { index, name ->
        val time = (bars[index] * barMs).coerceAtMost((song.seconds * 1000) - 50)
        val rgb = LETTER[index]
        CueSpec(index + 1, time.coerceAtLeast(0), name, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }
}

data class CueSpec(val hot: Int, val timeMs: Int, val comment: String, val red: Int, val green: Int, val blue: Int)
