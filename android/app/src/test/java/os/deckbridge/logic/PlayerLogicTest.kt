package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLogicTest {
    private val map = MidiMap.loadResource()

    @Test
    fun faderCenterMatchesTheFourteenBitTempo() {
        assertEquals(1.0, pitchFromFader(0.5), 1e-9)
        assertEquals(pitchFromTempo14(0x00, 0x00, 0.10), pitchFromFader(1.0, 0.10), 1e-6)
        assertEquals(pitchFromTempo14(0x7F, 0x7F, 0.10), pitchFromFader(0.0, 0.10), 1e-6)
        assertEquals("±10", tempoRangeLabel(0.10))
        assertEquals("WIDE", tempoRangeLabel(0.25))
        assertEquals(0.16, nextTempoRange(0.10), 0.0)
    }

    @Test
    fun zoomWindowKeepsThePlayheadCenteredAndMarksPhrases() {
        assertEquals(4.0, windowSeconds(WaveZoom.BARS_2, 120.0, 8.0), 1e-9)
        assertEquals(8.0, windowSeconds(WaveZoom.TRACK, 120.0, 8.0), 1e-9)
        assertEquals(0.5, markerFraction(1.5, 1.5, 4.0, false, 8.0), 1e-9)
        assertEquals(0.30, markerFraction(1.5, 1.5, 4.0, false, 8.0, 0.30), 1e-9)
        assertEquals(1.0, beatFxBeats("1/1"), 0.0)
        assertEquals(0.75, beatFxBeats("3/4"), 0.0)
        assertEquals(2.0, beatFxBeats("2/1"), 0.0)
        assertEquals(0, beatFxAudioKind(0))
        assertEquals(1, beatFxAudioKind(1))
        assertEquals(9, beatFxAudioKind(2))
        assertEquals(2, beatFxAudioKind(3))
        assertTrue(keysCompatible("Am", "Em"))
        assertTrue(keysCompatible("Am", "Am"))
        assertFalse(keysCompatible("Am", "Ebm"))
        assertEquals(MarkerKind.PHRASE, markerKind(0, 1))
        assertEquals(MarkerKind.BAR, markerKind(4, 1))
        assertEquals(MarkerKind.BEAT, markerKind(1, 2))
        assertEquals(WaveZoom.BARS_2, WaveZoom.BARS_4.zoomIn())
        assertEquals(WaveZoom.TRACK, WaveZoom.BARS_32.zoomOut())
    }

    @Test
    fun beatFxAndBrowseUseTheOfficialMapBytes() {
        fun hit(status: Int, note: Int, value: Int) = surfaceAction(map.lookup(status, note), value)
        val on = hit(148, 71, 127)
        assertEquals(PerformanceAction.Kind.BEAT_FX, on!!.kind)
        var fx = reduceBeatFx(BeatFxState(), on)
        assertTrue(fx.on)
        fx = reduceBeatFx(fx, hit(148, 99, 127)!!)
        assertEquals("Delay", fx.name())
        fx = reduceBeatFx(fx, hit(148, 20, 127)!!)
        assertEquals(2, fx.channel)
        assertTrue(fx.hitsDeck(0))
        fx = reduceBeatFx(fx, hit(148, 16, 127)!!)
        assertEquals(0, fx.channel)
        assertTrue(fx.hitsDeck(0))
        assertFalse(fx.hitsDeck(1))
        fx = reduceBeatFx(fx, hit(180, 2, 90)!!)
        assertEquals(90, fx.level)
        val move = hit(182, 64, 127)
        assertEquals(PerformanceAction.Kind.BROWSE_MOVE, move!!.kind)
        assertEquals(-1.0, move.slider!!, 0.0)
        assertEquals(1.0, hit(182, 64, 1)!!.slider!!, 0.0)
    }

    @Test
    fun browseOpensAPlaylistWithoutTouchingTheStick() {
        val catalog = loadRekordbox(SyntheticRekordbox.volume())
        val categories = browseRows(catalog, BrowsePlace(), emptyList())
        assertEquals("Playlist", categories.first().title)
        assertTrue(categories.any { it.title == "History" })
        val lists = browseRows(catalog, BrowsePlace(inList = true), emptyList())
        val index = lists.indexOfFirst { it.title == "Abertura" }
        val opened = pickBrowse(BrowsePlace(inList = true), index, lists[index])
        assertEquals(catalog.playlists.first { it.name == "Abertura" }.id, opened.playlistId)
        val tracks = browseRows(catalog, opened, emptyList())
        assertEquals("Click 120", tracks.first().title)
        assertEquals("8A", tracks.first().keyName)
        val back = backBrowse(opened)
        assertEquals(null, back.playlistId)
    }
}
