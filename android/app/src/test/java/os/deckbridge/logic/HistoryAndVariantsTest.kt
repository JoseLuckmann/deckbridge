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
        val early = log.tick(0, true, true, 1_000, "Night Bus", "Nara", 120.0, "8A")
        val mid = log.tick(0, true, true, 9_000, "Night Bus", "Nara", 120.0, "8A")
        val done = log.tick(0, true, true, 12_000, "Night Bus", "Nara", 120.0, "8A")
        val again = log.tick(0, true, true, 20_000, "Night Bus", "Nara", 120.0, "8A")
        assertNull(early)
        assertNull(mid)
        assertEquals("Night Bus", done?.title)
        assertEquals(0, done?.deck)
        assertNull(again)
        log.tick(0, false, true, 21_000, "Night Bus", "Nara", 120.0, "8A")
        assertNull(log.tick(0, true, true, 22_000, "Night Bus", "Nara", 120.0, "8A"))
        val second = log.tick(0, true, true, 33_000, "Night Bus", "Nara", 120.0, "8A")
        assertEquals("Night Bus", second?.title)
    }

    @Test
    fun exportDoesNotPointAtTheUsb() {
        val rows = listOf(HistoryRow(10, 1, "Café \"Live\"", "Casa", 100.0, "4A", 12))
        val csv = historyCsv(rows)
        val m3u = historyM3u8(rows)
        assertTrue(csv.contains("Café"))
        assertTrue(csv.contains("\"\"Live\"\""))
        assertTrue(m3u.startsWith("#EXTM3U"))
        assertTrue(m3u.contains("deckbridge://set/10"))
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
