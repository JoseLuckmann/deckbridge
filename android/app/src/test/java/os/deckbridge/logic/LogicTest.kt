package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val map = MidiMap.loadResource()

    @Test
    fun officialBytesKeepTheirNamesAndPlayIsNotRemapped() {
        val load = map.lookup(0x96, 0x46)
        assertEquals("LoadSelectedTrack", load.key)
        assertEquals("[Channel1]", load.group)
        assertTrue(load.mapped)
        val browse = map.lookup(0xB6, 0x40)
        assertEquals("MoveVertical", browse.key)
        val play = map.lookup(0x90, 0x0B)
        assertEquals("play", play.key)
        assertTrue(isPerformancePassthrough(play))
        val unknown = map.lookup(0x90, 0x7E)
        assertFalse(unknown.mapped)
        assertEquals("sem nome no mapa oficial", unknown.description)
    }

    @Test
    fun assemblerReadsNoteAndRunningStatus() {
        val assembler = MidiAssembler()
        val first = assembler.push(byteArrayOf(0x90.toByte(), 0x0B, 0x7F))
        assertEquals(0x90, first.single().status)
        assertEquals(0x0B, first.single().data1)
        val running = assembler.push(byteArrayOf(0x0C, 0x7F))
        assertEquals(0x90, running.single().status)
        assertEquals(0x0C, running.single().data1)
    }

    @Test
    fun missingEngineNumbersStayUnknown() {
        val parsed = parseEngineStats(
            """
            opened=false
            requested_channels=4
            opened_channels=0
            sample_rate=0
            device_id=0
            sharing=UNKNOWN
            performance=UNKNOWN
            fallback_to_two=false
            route=0
            phones_sent=false
            xruns_known=false
            xruns=-1
            latency_known=false
            latency_ms=-1
            error=
            """.trimIndent(),
        )
        assertNull(parsed.xruns)
        assertNull(parsed.latencyMs)
        val report = buildDiagnosticReport(
            identity = DeviceIdentity(sdkInt = 29, ramMb = 4096, cores = 8, model = "SM-P610"),
            audioDevices = emptyList(),
            usbDevices = emptyList(),
            midiPorts = emptyList(),
            recentMidi = emptyList(),
            engine = null,
            batteryPercent = null,
            batteryTemperatureC = "UNKNOWN",
            heard = Heard.NAO_TESTEI,
            selectedDeviceId = null,
        )
        assertTrue(report.contains("Latência UNKNOWN"))
        assertTrue(report.contains("X-runs UNKNOWN"))
        assertFalse(report.contains("comprovado"))
        assertTrue(report.contains("BLOCKED_HARDWARE"))
        assertTrue(report.contains("Não é medição deste app"))
        assertTrue(report.contains("djay"))
        assertTrue(report.contains("soc=UNKNOWN"))
        assertTrue(report.contains("perfil=high"))
        assertTrue(report.contains("ainda não medido"))
    }

    @Test
    fun fourGigabytesAndAndroid10To14StayInsideTheProfile() {
        val profile = classifyCapability(8, 4096)
        assertEquals("high", profile.tier)
        assertEquals("full", profile.waveforms)
        assertTrue((29..34).all { AndroidRange.coversTabS6LiteFamily(it) })
        assertTrue(AndroidRange.covers(29))
        assertTrue(AndroidRange.covers(34))
        assertEquals(26, AndroidRange.MIN_SDK)
        assertFalse(AndroidRange.coversTabS6LiteFamily(26))
        assertFalse(AndroidRange.coversTabS6LiteFamily(35))
    }

    @Test
    fun referenceScreensFitIncludingTheTenInchTablet() {
        REFERENCE_SIZES.forEach { (width, height) ->
            assertTrue(sideBySide(width, height))
            val frames = performanceFrames(width, height, waveforms = true)
            assertEquals(emptyList<String>(), layoutProblems(frames, width, height))
            assertTrue(frames.any { it.name == "bpm_a" })
            assertTrue(frames.any { it.name == "play_a" })
            assertTrue(frames.any { it.name == "waveform_a" })
        }
        val low = performanceFrames(800, 480, waveforms = false)
        assertTrue(low.none { it.name.startsWith("waveform") })
        assertEquals(22, textSp(2000))
        assertEquals(13, textSp(800))
        assertEquals("mp3", localTrackKind("Faixa.MP3"))
        assertEquals("wav", localTrackKind("tom.wav"))
        assertEquals("recusado", localTrackKind("musica.flac"))
    }
}
