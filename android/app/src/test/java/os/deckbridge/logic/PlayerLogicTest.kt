package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(2, beatFxAudioKind(2))
        assertEquals(3, beatFxAudioKind(3))
        assertEquals(14, beatFxAudioKind(14))
        assertTrue(keysCompatible("Am", "Em"))
        assertTrue(keysCompatible("Am", "Am"))
        assertTrue(keysCompatible("Am", "8A"))
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
        assertEquals("Echo", fx.name())
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
        val backJump = hit(151, 32, 127)
        assertEquals(PerformanceAction.Kind.BEAT_JUMP, backJump!!.kind)
        assertEquals(0, backJump.hotIndex)
        assertEquals(PerformanceAction.Kind.LOOP_IN, hit(144, 16, 127)!!.kind)
        val four = hit(151, 100, 127)
        assertEquals(PerformanceAction.Kind.BEAT_LOOP, four!!.kind)
        assertEquals(4.0, four.slider!!, 0.0)
        val scene = hit(151, 48, 127)
        assertEquals(PerformanceAction.Kind.RMX, scene!!.kind)
        assertEquals(0, scene.hotIndex)
        assertEquals(1.0, scene.slider!!, 0.0)
        assertEquals(0.0, hit(151, 48, 0)!!.slider!!, 0.0)
        // O segundo banco de pads escolhe o som do drum build (0.14.1): pad 8 é o primeiro som.
        val voice = hit(152, 48, 127)
        assertEquals(PerformanceAction.Kind.RMX, voice!!.kind)
        assertEquals(8, voice.hotIndex)
        val page = hit(152, 39, 127)
        assertEquals(PerformanceAction.Kind.BEAT_JUMP_PAGE, page!!.kind)
        assertEquals(1, page.hotIndex)
    }

    @Test
    fun mixerKnobsUseTheHighByteOfTheFourteenBitPairs() {
        fun act(status: Int, note: Int, value: Int) = interpretPerformance(map.lookup(status, note), value, TempoAssembler())
        val kill = act(176, 15, 0)
        assertEquals(PerformanceAction.Kind.EQ, kill!!.kind)
        assertEquals(0, kill.deck)
        assertEquals(0, kill.hotIndex)
        assertEquals(0.0, kill.slider!!, 0.0)
        val hiB = act(177, 7, 127)
        assertEquals(1, hiB!!.deck)
        assertEquals(2, hiB.hotIndex)
        assertEquals(1.0, hiB.slider!!, 0.0)
        assertEquals(1, act(176, 11, 64)!!.hotIndex)
        // O byte baixo (+32) não pode mexer nos controles: eram 7 bits de ruído sobre o valor do byte alto.
        assertEquals(null, act(176, 47, 90))
        assertEquals(null, act(176, 51, 100))
        assertEquals(null, act(182, 63, 100))
        assertEquals(PerformanceAction.Kind.VOLUME, act(176, 19, 100)!!.kind)
        assertEquals(PerformanceAction.Kind.CROSSFADER, act(182, 31, 100)!!.kind)
        val trim = act(177, 4, 64)
        assertEquals(PerformanceAction.Kind.TRIM, trim!!.kind)
        assertEquals(1, trim.deck)
        assertEquals(PerformanceAction.Kind.HEAD_MIX, act(182, 12, 127)!!.kind)
        assertEquals(PerformanceAction.Kind.HEAD_GAIN, act(182, 13, 127)!!.kind)
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
        val jumped = jumpSeconds(1000.0 / 48000.0, 1.0, 120.0, 48000, true, 1.0, -1.0, -1.0, 32.0)
        assertEquals(0.5, jumped, 1e-9)
        val wrapped = jumpSeconds(1.5, 1.0, 120.0, 48000, true, 1.0, 1.0, 2.0, 32.0)
        assertEquals(1.0, wrapped, 1e-6)
        assertEquals(16.0, stepJumpScale(1.0, true), 0.0)
        assertEquals(16.0, stepJumpScale(16.0, true), 0.0)
        assertEquals(1.0 / 16.0, stepJumpScale(1.0, false), 0.0)
        val back = backBrowse(opened)
        assertEquals(null, back.playlistId)
    }

    @Test
    fun keySyncTempoNeedleEmergencyAndArtworkStayOffTheUsb() {
        assertEquals(0, keySyncShift("Am", "Am"))
        assertEquals(0, keySyncShift("C", "Am"))
        assertEquals(-1, keySyncShift("Ebm", "Am"))
        assertEquals("+4.02%", fineTempoText(4.02f))
        assertEquals("-0.02%", fineTempoText(-0.02f))
        assertEquals(16f, needleSeconds(50f, 100f, 32.0), 0.001f)
        assertEquals(0f, needleSeconds(0f, 0f, 32.0), 0f)
        assertNull(emergencyLoopRange(1.0, 32.0, 120.0, false))
        val near = emergencyLoopRange(30.0, 32.0, 120.0, false)
        assertNotNull(near)
        assertNull(emergencyLoopRange(30.0, 32.0, 120.0, true))
        assertEquals(12, phraseBarsRemaining(9.2, 124.0))
        assertNotNull(beatsUntilCue(9.2, 11.6, 124.0))
        assertEquals("B", cueBankLetter(1))
        assertEquals("A", cueBankLetter(8))
        assertEquals(0, nextCueBank(7))
        assertEquals(3.0, slipAnchorAfter(1.0, 2.0, 1.0), 0.0)
        val volume = SyntheticRekordbox.volume()
        val keys = volume.files.keys.toSet()
        val catalog = loadRekordbox(volume)
        val night = catalog.tracks.first { it.title == "Night Bus" }
        assertEquals(96, pngWidth(readArtworkFile(volume, night.artworkPath) ?: ByteArray(0)))
        val bare = catalog.tracks.first { it.title == "Click 100" }
        assertEquals("", bare.artworkPath)
        assertNull(readArtworkFile(volume, bare.artworkPath))
        val click = catalog.tracks.first { it.title == "Click 120" }
        assertEquals(48, pngWidth(readArtworkFile(volume, click.artworkPath) ?: ByteArray(0)))
        assertEquals(keys, volume.files.keys.toSet())
        val filtered = browseRows(catalog, BrowsePlace(category = BrowseCategory.TRACK, inList = true, matchKey = "Am", bpmMin = 118, bpmMax = 130), emptyList())
        assertTrue(filtered.any { it.title == "Night Bus" })
        assertTrue(filtered.none { it.title == "Glasshouse" })
        assertTrue(filtered.all { it.track == null || keysCompatible("Am", it.keyName) })
    }

    @Test
    fun columnHeaderSortsTrackRows() {
        val catalog = loadRekordbox(SyntheticRekordbox.volume())
        val place = BrowsePlace(category = BrowseCategory.TRACK, inList = true)
        val original = browseRows(catalog, place, emptyList()).map { it.title }
        val byTitle = browseRows(catalog, toggleListSort(place, ListSort.TITLE), emptyList()).map { it.title }
        val flipped = browseRows(catalog, toggleListSort(toggleListSort(place, ListSort.TITLE), ListSort.TITLE), emptyList()).map { it.title }
        assertEquals(original.sortedBy { it.lowercase() }, byTitle)
        assertEquals(byTitle.reversed(), flipped)
        val folders = browseRows(catalog, BrowsePlace(category = BrowseCategory.PLAYLIST, inList = true), emptyList())
        val sortedFolders = sortBrowseRows(folders, ListSort.TITLE, false)
        assertEquals(folders.map { it.title }, sortedFolders.map { it.title })
    }

    @Test
    fun activeLoopWaitsForThePlayhead() {
        assertFalse(arrivedAtActiveLoop(0f, 0f, 0f, 4f))
        assertFalse(arrivedAtActiveLoop(12f, 13f, 11f, 20f))
        assertTrue(arrivedAtActiveLoop(10f, 12f, 11f, 20f))
        assertFalse(arrivedAtActiveLoop(10f, 21f, 11f, 20f))
    }

    @Test
    fun countdownIncludesTheMemoryCue() {
        val analysis = RbAnalysis(
            beats = emptyList(),
            hotCues = listOf(RbHotCue(0, 10_000, "Drop", null)),
            memoryCues = listOf(RbMemoryCue(4_000, 8_000, "Loop")),
            mainCueMs = 0,
            waveform = ByteArray(0),
        )
        assertEquals(4.0, nextCueSeconds(analysis, 1.0)!!, 0.001)
        assertEquals(10.0, nextCueSeconds(analysis, 5.0)!!, 0.001)
        assertNull(nextCueSeconds(analysis, 11.0))
    }

    @Test
    fun gridEditStaysOffTheVolume() {
        val volume = SyntheticRekordbox.volume()
        val before = volume.files.mapValues { it.value.copyOf() }
        val beats = listOf(AnlzBeat(1, 120.0, 0), AnlzBeat(2, 120.0, 500), AnlzBeat(3, 120.0, 1000))
        val edited = applyGrid(beats, GridEdit(shiftMs = 10.0, stretch = 1.001))
        assertEquals(0, beats[0].timeMs)
        assertEquals(10, edited[0].timeMs)
        assertTrue(edited[2].timeMs > 1000)
        assertEquals(120.0 / 1.001, gridBpm(120.0, GridEdit(stretch = 1.001)), 1e-9)
        before.forEach { (path, bytes) -> assertTrue(volume.read(path)!!.contentEquals(bytes)) }
    }
}
