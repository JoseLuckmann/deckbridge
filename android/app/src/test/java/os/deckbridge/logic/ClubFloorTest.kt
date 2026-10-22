package os.deckbridge.logic

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class ClubFloorTest {
    @Test
    fun sectionsLandOnTheEightBarGrid() {
        val bars = 48
        val low = DoubleArray(bars) { bar ->
            when {
                bar < 8 -> 0.05
                bar < 16 -> 0.16
                bar < 32 -> 0.90
                bar < 40 -> 0.08
                else -> 0.28
            }
        }
        val high = DoubleArray(bars) { bar ->
            when {
                bar < 8 -> 0.04
                bar < 16 -> 0.22 + (bar - 8) * 0.04
                bar < 32 -> 0.30
                bar < 40 -> 0.05
                else -> 0.14
            }
        }
        val read = detectSections(low, high, 2.0, 120.0, 96.0)
        val bar = 2.0
        assertTrue(abs(read.points.buildSec - 16.0) <= bar)
        assertTrue(abs(read.points.dropSec - 32.0) <= bar)
        assertTrue(abs(read.points.breakSec - 64.0) <= bar)
        assertTrue(abs(read.points.outroSec - 80.0) <= bar)
        assertTrue(read.phrases.any { it.kind == "Chorus" })
        assertTrue(read.phrases.any { it.kind == "Outro" })
        val beats = List(bars * 4) { index -> AnlzBeat((index % 4) + 1, 120.0, index * 500) }
        val cues = autoCues(read.phrases, beats, "drop")
        assertEquals("Downbeat", cues.first.first { it.index == 0 }.comment)
        assertEquals("Drop", cues.first.first { it.index == 1 }.comment)
        assertTrue(cues.second.any { it.comment == "Break" })
    }

    @Test
    fun pssiMaskedAndPlainRoundTrip() {
        val plain = pssiTag(masked = false)
        val masked = pssiTag(masked = true)
        val beats = listOf(AnlzBeat(1, 120.0, 0), AnlzBeat(2, 120.0, 500), AnlzBeat(3, 120.0, 1000), AnlzBeat(4, 120.0, 1500))
        val open = parseAnlz(plain)
        val hidden = parseAnlz(masked)
        assertEquals("Intro 1", open.phrases.first().label)
        assertEquals("high", open.phraseMood)
        assertEquals(open.phrases.first().label, hidden.phrases.first().label)
        assertEquals(0, open.phrases.first().startMs)
        assertEquals(1, open.phrases.size)
    }

    @Test
    fun suggestAndMatchingUseCamelotAndHistory() {
        val master = track(1, "Master", "8A", 124.0, "House")
        val next = track(2, "Vizinho", "9A", 125.0, "House")
        val far = track(3, "Longe", "3B", 90.0, "Ambient")
        val played = track(4, "Ja foi", "8A", 124.0, "House")
        val rows = suggestRows(listOf(master, next, far, played), master, { if (it == 2) 6.0 else 5.0 }, setOf(4), { false }, true)
        assertTrue(rows.none { it.track.id == 4 })
        assertTrue(rows.none { it.track.id == 1 })
        assertEquals(2, rows.first().track.id)
        assertTrue(rows.first().reason.contains("8A→9A"))
        assertTrue(rows.first().reason.contains("energia +1"))
        val match = matchingRows(listOf(master, next, far), master)
        assertEquals(listOf(2), match.map { it.id })
    }

    @Test
    fun sineLoudnessSitsNearTheRmsAndPhaseWraps() {
        val rate = 22050
        val amp = 0.1
        val samples = FloatArray(rate * 2) { index ->
            (amp * sin(2.0 * Math.PI * 1000.0 * index / rate)).toFloat()
        }
        val lufs = integratedLufs(samples, rate)
        assertTrue("lufs $lufs", abs(lufs - (-23.7)) < 2.0)
        assertEquals(0.0, trimDb(Double.NaN, -10.0, true), 0.0)
        assertEquals(-6.0, trimDb(-4.0, -10.0, true), 0.0)
        assertEquals(0.0, phaseOffsetBeats(0.0, 120.0, 0.0, 120.0), 0.001)
        assertEquals(1.0, phaseOffsetBeats(0.5, 120.0, 0.0, 120.0), 0.001)
    }

    @Test
    fun jumpIncludesSixtyFour() {
        assertEquals(64.0, beatJumpPadBeats(13), 0.0)
        assertEquals(-64.0, beatJumpPadBeats(12), 0.0)
        assertEquals(8.0, beatJumpPadBeats(7), 0.0)
    }

    private fun track(id: Int, title: String, key: String, bpm: Double, genre: String) = RbTrack(
        id, title, "Artista", "Album", genre, key, bpm, 180, 44100, "a.mp3", "a.mp3", "",
    )

    private fun pssiTag(masked: Boolean): ByteArray {
        val body = ByteArray(14 + 24)
        body[0] = 0
        body[1] = 1
        body[8] = 0
        body[9] = 4
        body[14] = 0
        body[15] = 1
        body[16] = 0
        body[17] = 1
        body[18] = 0
        body[19] = 1
        body[21] = 1
        if (masked) unmaskPssi(body, 1)
        val out = ByteArrayOutputStream()
        out.write("PMAI".toByteArray())
        writeBe32(out, 28)
        writeBe32(out, 28 + 12 + 4 + 2 + body.size)
        repeat(16) { out.write(0) }
        out.write("PSSI".toByteArray())
        writeBe32(out, 18)
        writeBe32(out, 12 + 4 + 2 + body.size)
        writeBe32(out, 24)
        out.write(0)
        out.write(1)
        out.write(body)
        return out.toByteArray()
    }

    private fun writeBe32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
