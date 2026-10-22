package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAndVariantsTest {
    @Test
    fun onAirLogsOnlyAfterTheConfiguredTime() {
        val log = OnAirLog(10)
        val sample = OnAirSample(3, "Night Bus", "Nara Vale", 124.0, "Am", 1.0)
        assertNull(log.tick(0, true, true, 1_000, sample))
        assertNull(log.tick(0, true, true, 9_000, sample))
        val done = log.tick(0, true, true, 12_000, sample)
        assertEquals("Night Bus", done?.title)
        assertEquals(0, done?.deck)
        assertNull(log.tick(0, true, true, 20_000, sample))
        log.tick(0, false, true, 21_000, sample)
        assertNull(log.tick(0, true, true, 22_000, sample))
        val second = log.tick(0, true, true, 33_000, sample)
        assertEquals("Night Bus", second?.title)
        assertEquals("Am", second?.keyName)
    }

    @Test
    fun onAirUsesTheLoadedLibraryRow() {
        val catalog = loadRekordbox(SyntheticRekordbox.volume())
        val night = catalog.tracks.first { it.title == "Night Bus" }
        val glass = catalog.tracks.first { it.title == "Glasshouse" }
        val click = catalog.tracks.first { it.title == "Click 120" }
        assertEquals("Nara Vale", night.artist)
        assertEquals(124.0, night.bpm, 0.001)
        assertEquals("Am", night.keyName)
        assertEquals("Lumen K", glass.artist)
        assertEquals(122.0, glass.bpm, 0.001)
        assertEquals("Ebm", glass.keyName)
        assertEquals(120.0, click.bpm, 0.001)
        assertEquals("8A", click.keyName)
        assertEquals("8A", camelotOf("Am"))
        assertEquals("2A", camelotOf("Ebm"))

        val log = OnAirLog(10)
        val nightSample = onAirSample(night, 1.0)
        assertNull(log.tick(0, true, true, 1_000, nightSample))
        val row = log.tick(0, true, true, 12_000, nightSample)
        assertEquals(night.id, row?.trackId)
        assertEquals(night.title, row?.title)
        assertEquals(night.artist, row?.artist)
        assertEquals(night.bpm, row!!.bpm, 0.001)
        assertEquals(night.bpm, row.playedBpm, 0.001)
        assertEquals(night.keyName, row.keyName)

        assertNull(log.tick(1, true, true, 1_000, onAirSample(click, 1.0)))
        assertNull(log.tick(1, true, true, 11_000, onAirSample(glass, 1.04)))
        val second = log.tick(1, true, true, 22_000, onAirSample(glass, 1.04))
        assertEquals(glass.title, second?.title)
        assertEquals(glass.artist, second?.artist)
        assertEquals(122.0, second!!.bpm, 0.001)
        assertEquals(122.0 * 1.04, second.playedBpm, 0.001)
        assertEquals("Ebm", second.keyName)
        assertEquals(glass.id, second.trackId)
        assertFalse(second.bpm == 120.0)
        assertFalse(second.keyName == "4A" || second.keyName == "8A")
    }

    @Test
    fun exportDoesNotPointAtTheUsb() {
        val rows = listOf(HistoryRow(10, 1, "Café \"Live\"", "Casa", 100.0, 104.0, "4A", 12, 2))
        val csv = historyCsv(rows)
        val m3u = historyM3u8(rows)
        assertTrue(csv.contains("Café"))
        assertTrue(csv.contains("\"\"Live\"\""))
        assertTrue(csv.contains("104.00"))
        assertTrue(m3u.startsWith("#EXTM3U"))
        assertTrue(m3u.contains("deckbridge://set/10"))
        assertTrue(m3u.contains("played_bpm=104.00"))
        assertFalse(m3u.contains("CONTENTS/"))
        assertFalse(m3u.contains("PIONEER/"))
        assertFalse(csv.contains("CONTENTS/"))
    }

    @Test
    fun lowStorageWarnsAndAFullDiskDoesNot() {
        assertTrue(storageWarning(8L * 1024 * 1024)!!.contains("Pouco espaço"))
        assertNull(storageWarning(200L * 1024 * 1024))
    }

    @Test
    fun usbVariantsStayFinite() {
        val report = usbVariantReport()
        assertFalse(report.contains("falha"))
        assertTrue(report.contains("PWV7"))
        assertTrue(report.contains("5000 faixas"))
        assertTrue(report.contains("fuzz"))
    }
}
