package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueSheetTest {
    private fun track(id: Int, title: String, artist: String = "") =
        RbTrack(id, title, artist, "", "", "Am", 128.0, 300, 44100, "", "", "")

    @Test
    fun aTrackEntersAfterBeingOnAirLongEnoughAndCountsFromWhenItCameIn() {
        val log = TracklistLogger(audibleMs = 8_000L)
        val a = track(1, "Um", "Artista A")
        val b = track(2, "Dois")
        var t = 0L
        while (t <= 40_000L) {
            val onB = t >= 20_000L
            log.update(t, listOf(true, onB), listOf(a, b))
            t += 500L
        }
        assertEquals(listOf("Um", "Dois"), log.entries.map { it.title })
        assertEquals(0L, log.entries[0].startMs)
        assertEquals(20_000L, log.entries[1].startMs)
    }

    @Test
    fun aShortBlipAndARepeatAreNotLogged() {
        val log = TracklistLogger(audibleMs = 8_000L, repeatMs = 600_000L)
        val a = track(1, "Um")
        // Cinco segundos no ar: não entra.
        for (t in 0L..5_000L step 500L) log.update(t, listOf(true, false), listOf(a, null))
        for (t in 5_500L..10_000L step 500L) log.update(t, listOf(false, false), listOf(a, null))
        assertTrue(log.entries.isEmpty())
        // Entra uma vez, sai e volta dentro de dez minutos: continua uma entrada.
        for (t in 20_000L..40_000L step 500L) log.update(t, listOf(true, false), listOf(a, null))
        for (t in 40_500L..50_000L step 500L) log.update(t, listOf(false, false), listOf(a, null))
        for (t in 60_000L..80_000L step 500L) log.update(t, listOf(true, false), listOf(a, null))
        assertEquals(1, log.entries.size)
    }

    @Test
    fun cueSheetAndChapterListHaveTheExpectedFormat() {
        val entries = listOf(
            SetEntry(0L, 0, 1, "Um", "Artista \"A\""),
            SetEntry(185_500L, 1, 2, "Dois", ""),
        )
        assertEquals("00:00:00", cueTime(0L))
        assertEquals("03:05:37", cueTime(185_500L))
        val cue = cueSheet("set-1.wav", "Mix", entries)
        assertTrue(cue.contains("FILE \"set-1.wav\" WAVE"))
        assertTrue(cue.contains("  TRACK 02 AUDIO"))
        assertTrue(cue.contains("    INDEX 01 03:05:37"))
        assertTrue(cue.contains("PERFORMER \"Artista 'A'\""))
        assertEquals("00:00 Artista \"A\" - Um\n03:05 Dois\n", tracklistText(entries))
    }
}
