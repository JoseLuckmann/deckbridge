package os.deckbridge.manager

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import os.deckbridge.manager.ui.DragInfo
import os.deckbridge.manager.ui.UiState
import os.deckbridge.manager.ui.formatMs
import os.deckbridge.manager.ui.parseMs
import os.deckbridge.manager.ui.startImport
import os.deckbridge.manager.volume.Backups

class UiLogicTest {
    private fun model() = ManagerModel(Backups(TestVolumes.tempDir("dbm-ui")))

    @Test
    fun cueTimesParseAndFormatBothWays() {
        assertEquals(61_500, parseMs("1:01.5"))
        assertEquals(61_500, parseMs("1:01,500"))
        assertEquals(12_340, parseMs("12.34"))
        assertEquals(900, parseMs("900ms"))
        assertEquals(0, parseMs("0"))
        assertNull(parseMs("")); assertNull(parseMs("abc")); assertNull(parseMs("1:75")); assertNull(parseMs("1:2:3")); assertNull(parseMs("-3"))
        for (ms in listOf(0, 1, 999, 1000, 61_001, 599_999)) assertEquals(ms, parseMs(formatMs(ms)))
    }

    @Test
    fun droppingOnASidebarRowAddsTheDraggedTracks() {
        val m = model()
        m.open(TestVolumes.synthetic())
        val v = m.view!!
        val list = v.playlists.first { !it.folder && v.entries[it.id].orEmpty().size in 1 until v.tracks.size }
        val extra = v.tracks.map { it.id }.filter { it !in v.entries[list.id].orEmpty() }.take(2)
        val ui = UiState()
        ui.rects["pl:${list.id}"] = Rect(0f, 100f, 250f, 130f)
        ui.drag = DragInfo(extra, "2 faixas", Offset(100f, 115f))
        ui.updateHover()
        assertEquals(list.id, ui.hoverPlaylist)
        ui.finishDrag(m, null)
        assertNull(ui.drag)
        assertNull(ui.hoverPlaylist)
        assertEquals(v.entries[list.id].orEmpty() + extra, m.view!!.entries[list.id])
        // Soltar fora de qualquer linha não faz nada.
        ui.drag = DragInfo(extra, "x", Offset(900f, 900f))
        ui.finishDrag(m, null)
        assertEquals(1, m.pending.size)
    }

    @Test
    fun droppingOnARowOfTheOpenPlaylistReordersByHalf() {
        val m = model()
        m.open(TestVolumes.synthetic())
        val v = m.view!!
        val list = v.playlists.first { !it.folder && v.entries[it.id].orEmpty().size in 4 until v.tracks.size }
        val ids = v.entries[list.id]!!
        m.openPlaylist = list.id
        m.sort = Sort(null)
        val ui = UiState()
        ids.forEachIndexed { n, id -> ui.rects["row:$id"] = Rect(260f, 200f + n * 28f, 1000f, 228f + n * 28f) }
        // Último item solto na metade de cima da primeira linha: vai para o começo.
        ui.drag = DragInfo(listOf(ids.last()), "x", Offset(500f, 204f))
        ui.finishDrag(m, m.playlist(list.id))
        assertEquals(listOf(ids.last()) + ids.dropLast(1), m.view!!.entries[list.id])
        // Primeiro item solto na metade de baixo da segunda linha: fica depois dela.
        val now = m.view!!.entries[list.id]!!
        ui.rects.clear()
        now.forEachIndexed { n, id -> ui.rects["row:$id"] = Rect(260f, 200f + n * 28f, 1000f, 228f + n * 28f) }
        ui.drag = DragInfo(listOf(now[0]), "x", Offset(500f, 200f + 28f + 24f))
        ui.finishDrag(m, m.playlist(list.id))
        assertEquals(listOf(now[1], now[0]) + now.drop(2), m.view!!.entries[list.id])
        // Com a lista ordenada por coluna, soltar não reordena.
        m.sort = Sort(Column.TITLE)
        val before = m.pending.size
        ui.drag = DragInfo(listOf(now[0]), "x", Offset(500f, 204f))
        ui.finishDrag(m, m.playlist(list.id))
        assertEquals(before, m.pending.size)
    }

    @Test
    fun importingWithoutAVolumeOrWithoutAudioExplainsInsteadOfFailing() {
        val m = model()
        UiState().startImportOf(listOf(File("/tmp/nada.wav")), m)
        assertTrue(m.banner!!.error && m.banner!!.text.contains("Abra um pendrive"))
        m.open(TestVolumes.synthetic())
        val ui = UiState()
        ui.startImportOf(listOf(File("/tmp/leia-me.txt")), m)
        assertTrue(m.banner!!.text.contains("Nenhum arquivo de áudio"))
        assertNull(ui.importRows)
    }

    @Test
    fun theImportRowBecomesReadyInTheBackgroundAndFolderDropsAreExpanded() {
        val dir = TestVolumes.tempDir("dbm-imp")
        File(dir, "sub").mkdirs()
        val wav = os.deckbridge.logic.encodeWavMono16(os.deckbridge.logic.click(bpm = 128.0, seconds = 12, rate = 22050, freq = 330.0), 22050)
        File(dir, "sub/Banda - Um.wav").writeBytes(wav)
        File(dir, "nota.txt").writeText("x")
        val m = model()
        m.open(TestVolumes.synthetic())
        val ui = UiState()
        ui.startImportOf(listOf(dir), m)
        val rows = ui.importRows!!
        assertEquals(1, rows.size, "a pasta foi aberta e o .txt ficou de fora")
        val end = System.currentTimeMillis() + 60_000
        while (rows[0].prepared == null && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(rows[0].prepared != null, rows[0].status)
        assertEquals("Um", rows[0].title)
        assertEquals("Banda", rows[0].artist)
        assertTrue(rows[0].status.contains("BPM"))
        // O mesmo arquivo lido por startImport direto também funciona.
        assertEquals(1, startImport(listOf(File(dir, "sub/Banda - Um.wav"))).size)
    }
}
