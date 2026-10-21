package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RekordboxTest {
    private val volume = SyntheticRekordbox.volume()
    private val catalog = loadRekordbox(volume)

    @Test
    fun syntheticExportRoundTripsTracksPlaylistsAndAnalysis() {
        assertNull(catalog.error)
        assertEquals(2, catalog.tracks.size)
        val first = catalog.tracks.first { it.id == 1 }
        assertEquals("Click 120", first.title)
        assertEquals("João Sintetico", first.artist)
        assertEquals("Sessao", first.album)
        assertEquals("House", first.genre)
        assertEquals("8A", first.keyName)
        assertEquals(120.0, first.bpm, 0.001)
        assertEquals(8, first.durationSeconds)
        assertEquals("CONTENTS/faixa-120.wav", first.filePath)
        val second = catalog.tracks.first { it.id == 2 }
        assertEquals("4A", second.keyName)
        assertEquals(100.0, second.bpm, 0.001)
        val abertura = catalog.playlists.first { it.name == "Abertura" }
        assertEquals(1, abertura.parentId)
        assertEquals(listOf(1, 2), abertura.trackIds)
        assertTrue(catalog.playlists.first { it.name == "Sets" }.folder)
        val analysis = catalog.analyses.getValue(1)
        assertEquals(500, analysis.mainCueMs)
        assertEquals("Intro", analysis.hotCues.first { it.index == 0 }.comment)
        assertEquals(0, analysis.hotCues.first { it.index == 0 }.timeMs)
        assertEquals(2000, analysis.hotCues.first { it.index == 1 }.timeMs)
        assertTrue(analysis.beats.size >= 8)
        assertEquals(120.0, analysis.beats.first().bpm, 0.001)
        assertTrue(analysis.waveform.isNotEmpty())
        val other = catalog.analyses.getValue(2)
        assertEquals(600, other.mainCueMs)
        assertNotNull(other.memoryCues.firstOrNull { it.loopEndMs == 2400 })
        assertEquals(2, other.hotCues.first { it.index == 2 }.index)
    }

    @Test
    fun oneLibraryIsDetectedAndNotOpened() {
        assertTrue(catalog.oneLibraryPresent)
        assertFalse(catalog.oneLibraryReadable)
        assertTrue(catalog.oneLibraryNote.contains("Não foi aberto"))
        assertTrue(catalog.warnings.any { it.contains("exportExt.pdb") })
        assertTrue(catalog.simulated)
    }

    @Test
    fun wavAndMixerMathStayInsideTheFixture() {
        val pcm = decodeWavMono(volume.read("CONTENTS/faixa-120.wav")!!)
        assertNotNull(pcm)
        assertEquals(48000, pcm!!.sampleRate)
        assertEquals(8 * 48000, pcm.mono.size)
        assertEquals(1.2, syncPitch(100.0, 120.0, 1.0)!!, 0.0001)
        assertNull(syncPitch(0.0, 120.0, 1.0))
        assertEquals(0.4, masterGain(0.8, 0.5, false), 0.0001)
        assertEquals(0.4, masterGain(0.8, 0.5, true), 0.0001)
        val map = MidiMap.loadResource()
        val play = performanceAction(map.lookup(0x90, 0x0B), 0x7F)
        assertEquals(PerformanceAction.Kind.PLAY, play!!.kind)
        assertEquals(0, play.deck)
        assertNull(performanceAction(map.lookup(0x90, 0x0B), 0))
        val cue = performanceAction(map.lookup(0x91, 0x0C), 0x7F)
        assertEquals(PerformanceAction.Kind.CUE, cue!!.kind)
        assertEquals(1, cue.deck)
        val hot = performanceAction(map.lookup(151, 0), 0x7F)
        assertEquals(PerformanceAction.Kind.HOT_CUE, hot!!.kind)
        assertEquals(0, hot.hotIndex)
        assertNull(parseExportPdb(byteArrayOf(1, 2, 3)).database)
    }
}
