package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

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
        assertEquals("Abertura", catalog.trackTags.getValue(1).single().name)
        assertEquals("Mood", catalog.trackTags.getValue(1).single().category)
        assertEquals("Longe", catalog.trackTags.getValue(2).single().name)
        val loop = activeLoop(other)
        assertEquals(1200, loop!!.first)
        assertEquals(2400, loop.second)
        assertNull(activeLoop(analysis))
        assertTrue(catalog.warnings.none { it.contains("exportExt.pdb") })
    }

    @Test
    fun oneLibraryStaysClosedWhenTheCopyIsNotSqlCipher() {
        assertTrue(catalog.oneLibraryPresent)
        assertFalse(catalog.oneLibraryReadable)
        assertTrue(catalog.oneLibraryNote.contains("SQLCipher"))
        assertTrue(ONE_LIBRARY_PUBLIC_KEY.startsWith("r8gd"))
        assertTrue(catalog.simulated)
    }

    @Test
    fun oneLibraryOpenerFeedsTablesFromALocalCopy() {
        val file = File.createTempFile("onelibrary", ".db")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().execute("create table djmdContent (ID integer)")
            connection.createStatement().execute("insert into djmdContent (ID) values (1), (2)")
        }
        val files = volume.files.toMutableMap()
        files["PIONEER/rekordbox/exportLibrary.db"] = file.readBytes()
        file.delete()
        val opened = loadRekordbox(MemoryVolume(files, "copia local", true)) { bytes ->
            val copy = File.createTempFile("onelibrary-read", ".db")
            copy.writeBytes(bytes)
            DriverManager.getConnection("jdbc:sqlite:${copy.absolutePath}").use { connection ->
                val tables = connection.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                    val names = mutableListOf<String>()
                    while (cursor.next()) names += cursor.getString(1)
                    names
                }
                val count = connection.createStatement().executeQuery("SELECT COUNT(*) FROM djmdContent").use { cursor ->
                    cursor.next()
                    cursor.getInt(1)
                }
                OneLibraryRead(tables, count)
            }.also { copy.delete() }
        }
        assertTrue(opened.oneLibraryReadable)
        assertEquals(2, opened.oneLibraryContentRows)
        assertTrue(opened.oneLibraryNote.contains("somente leitura"))
    }

    @Test
    fun pitchUsesFourteenBitsAndVirtualControllerSequences() {
        assertEquals(1.0, pitchFromTempo14(0x40, 0x00), 0.0001)
        assertEquals(1.10, pitchFromTempo14(0x00, 0x00, 0.10), 0.0001)
        val full = (0x7F shl 7) or 0x7F
        val rate = 1.0 - (full / 8192.0)
        assertEquals(1.0 + rate * 0.10, pitchFromTempo14(0x7F, 0x7F, 0.10), 0.0001)
        assertEquals(0.06, nextTempoRange(0.25), 0.0001)
        val map = MidiMap.loadResource()
        val virtual = VirtualDdj400(map)
        val tempo = TempoAssembler()
        val center = runMidiSequence(map, virtual.tempo(0, 0x40, 0x00), tempo).single()
        assertEquals(PerformanceAction.Kind.PITCH, center.kind)
        assertEquals(1.0, center.slider!!, 0.0001)
        assertNull(runMidiSequence(map, listOf(virtual.tempo(1, 0x00, 0x00).first())).firstOrNull())
        val deck = VirtualDdj400(map)
        val actions = runMidiSequence(
            map,
            listOf(deck.play(0), deck.volume(0, 127), deck.crossfader(0), deck.hotCue(0, 0), deck.jogSide(0, 70), deck.jogSide(0, 64)),
        )
        assertEquals(PerformanceAction.Kind.PLAY, actions[0].kind)
        assertEquals(1.0, actions[1].slider!!, 0.0001)
        assertEquals(PerformanceAction.Kind.CROSSFADER, actions[2].kind)
        assertEquals(0.0, actions[2].slider!!, 0.0001)
        assertEquals(PerformanceAction.Kind.HOT_CUE, actions[3].kind)
        assertEquals(0, actions[3].hotIndex)
        assertEquals(PerformanceAction.Kind.JOG, actions[4].kind)
        assertEquals(6 * JOG_BEND_SCALE, actions[4].slider!!, 0.0001)
        assertEquals(0.0, actions[5].slider!!, 0.0001)
        assertEquals(1220.0, advancePlayhead(2390.0, 30.0, 1200.0, 2400.0), 0.001)
        assertEquals(15.0, advancePlayhead(10.0, 5.0, -1.0, -1.0), 0.001)
    }

    @Test
    fun wavKeepsStereoChannels() {
        val pcm = decodeWav(volume.read("CONTENTS/faixa-120.wav")!!)
        assertNotNull(pcm)
        assertEquals(48000, pcm!!.sampleRate)
        assertEquals(8 * 48000, pcm.frameCount)
        assertFalse(pcm.stereo)
        val stereo = decodeWav(stereoWav(left = 16000, right = -8000))
        assertNotNull(stereo)
        assertTrue(stereo!!.stereo)
        assertEquals(16000 / 32768f, stereo.left[0], 0.0001f)
        assertEquals(-8000 / 32768f, stereo.right[0], 0.0001f)
    }

    @Test
    fun wavAndMixerMathStayInsideTheFixture() {
        val pcm = decodeWav(volume.read("CONTENTS/faixa-120.wav")!!)
        assertNotNull(pcm)
        assertEquals(48000, pcm!!.sampleRate)
        assertEquals(8 * 48000, pcm.frameCount)
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

private fun stereoWav(left: Int, right: Int): ByteArray {
    val data = ByteArray(4)
    data[0] = (left and 0xFF).toByte()
    data[1] = ((left shr 8) and 0xFF).toByte()
    data[2] = (right and 0xFF).toByte()
    data[3] = ((right shr 8) and 0xFF).toByte()
    val header = ByteArray(44)
    fun put(at: Int, text: String) = text.toByteArray(Charsets.US_ASCII).copyInto(header, at)
    fun put32(at: Int, value: Int) {
        header[at] = (value and 0xFF).toByte()
        header[at + 1] = ((value shr 8) and 0xFF).toByte()
        header[at + 2] = ((value shr 16) and 0xFF).toByte()
        header[at + 3] = ((value shr 24) and 0xFF).toByte()
    }
    put(0, "RIFF")
    put32(4, 36 + data.size)
    put(8, "WAVE")
    put(12, "fmt ")
    put32(16, 16)
    header[20] = 1
    header[22] = 2
    put32(24, 48000)
    put32(28, 48000 * 4)
    header[32] = 4
    header[34] = 16
    put(36, "data")
    put32(40, data.size)
    return header + data
}
