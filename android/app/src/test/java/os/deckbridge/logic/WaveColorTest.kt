package os.deckbridge.logic

import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveColorTest {
    @Test
    fun pwv5WordKeepsRgbAndHeight() {
        val word = pwv5Word(5, 3, 1, 27)
        assertEquals(0, word and 3)
        val decoded = decodePwv5Word(word)
        assertEquals(scale5(27), decoded[0])
        assertEquals(scale3(5), decoded[1])
        assertEquals(scale3(3), decoded[2])
        assertEquals(scale3(1), decoded[3])
        val parsed = parseAnlz(anlzEnvelope(listOf(Triple("PWV5", 0x18, pwv5Body(intArrayOf(word))))))
        val color = parsed.rgbDetail?.rgb?.first() ?: 0
        assertEquals("PWV5", parsed.rgbDetail?.source)
        assertEquals(scale3(5), (color shr 16) and 0xFF)
        assertEquals(scale3(3), (color shr 8) and 0xFF)
        assertEquals(scale3(1), color and 0xFF)
        assertEquals(scale5(27), parsed.rgbDetail?.height?.first()?.toInt()?.and(0xFF))
    }

    @Test
    fun pwv4BlendsLowMidHighAndPw7KeepsOrder() {
        val low = byteArrayOf(255.toByte(), 0)
        val mid = byteArrayOf(0, 255.toByte())
        val high = byteArrayOf(0, 0)
        val parsed = parseAnlz(anlzEnvelope(listOf(Triple("PWV4", 0x18, pwv4Body(packPwv4(low, mid, high))))))
        val first = parsed.rgbPreview?.rgb?.get(0) ?: 0
        val second = parsed.rgbPreview?.rgb?.get(1) ?: 0
        assertTrue(((first shr 16) and 0xFF) > (first and 0xFF))
        assertTrue(((second shr 8) and 0xFF) > ((second shr 16) and 0xFF))
        val bands = WaveBands(byteArrayOf(200.toByte()), byteArrayOf(10), byteArrayOf(30))
        val seven = parseAnlz(anlzEnvelope(listOf(Triple("PWV7", 0x18, pwv7Body(packPwv7(bands))))))
        assertEquals(200, seven.bandDetail?.low?.first()?.toInt()?.and(0xFF))
        assertEquals(10, seven.bandDetail?.mid?.first()?.toInt()?.and(0xFF))
        assertEquals(30, seven.bandDetail?.high?.first()?.toInt()?.and(0xFF))
        val six = parseAnlz(anlzEnvelope(listOf(Triple("PWV6", 0x14, pwv6Body(packPwv7(bands))))))
        assertEquals(200, six.bandPreview?.low?.first()?.toInt()?.and(0xFF))
    }

    @Test
    fun missingRgbFallsBackToTheAudioAndUsbStaysUnread() {
        val rate = 22050
        val samples = FloatArray(rate) { index -> sin(2.0 * Math.PI * 70.0 * index / rate).toFloat() * 0.8f }
        val wav = wavMono16(samples, rate)
        val before = wav.copyOf()
        val dat = anlzEnvelope(listOf(Triple("PWAV", 0x14, pwavBody(packMono(ByteArray(32) { 40 })))))
        val volume = MemoryVolume(
            mapOf(
                "PIONEER/rekordbox/export.pdb" to largeLibraryPdb(1),
                "PIONEER/USBANLZ/ANLZ0001.DAT" to dat,
                "CONTENTS/odd café [1].wav" to wav,
            ),
            "fallback",
            true,
        )
        val catalog = loadRekordbox(volume)
        val track = catalog.tracks.first { it.title == "Faixa 1" }
        val analysis = catalog.analyses.getValue(track.id)
        assertTrue(analysis.rgbApp)
        assertEquals("analisado no app", waveOrigin(analysis, "RGB"))
        val packed = rgbSample(analysis, "RGB", false, 0.4, 1.0)
        val red = ((packed and 0xFFFFFF) shr 16).toInt()
        val blue = (packed and 0xFF).toInt()
        assertTrue(red > blue)
        assertTrue(wav.contentEquals(before))
        assertEquals(wav.size, volume.read("CONTENTS/odd café [1].wav")?.size)
    }

    @Test
    fun devSettingIsTheOnlyDocumentedColorAndUnknownBytesAreNotGuessed() {
        val rgb = readDevSetting(devSettingBytes(0x03, 0x01))
        assertEquals("RGB", rgb.waveColor)
        assertEquals(0.50f, rgb.playhead)
        val bands = readDevSetting(devSettingBytes(0x04, 0x02))
        assertEquals("THREE_BAND", bands.waveColor)
        assertEquals(0.20f, bands.playhead)
        val blue = readDevSetting(devSettingBytes(0x01, 0x01))
        assertEquals("BLUE", blue.waveColor)
        val unknown = readDevSetting(devSettingBytes(0x02, 0x01))
        assertNull(unknown.waveColor)
        val volume = MemoryVolume(mapOf("PIONEER/MYSETTING.DAT" to devSettingBytes(0x03, 0x01)), "mys", true)
        val missing = readUsbWavePrefs(volume)
        assertNull(missing.waveColor)
        assertTrue(missing.note.contains("MYSETTING"))
        val kept = resolveUsbSettings(PlayerSettings(waveColor = "BLUE", followUsb = false), "RGB", 0.50f)
        assertEquals("BLUE", kept.waveColor)
        val applied = resolveUsbSettings(PlayerSettings(followUsb = true), "THREE_BAND", 0.20f)
        assertEquals("THREE_BAND", applied.waveColor)
        assertEquals(0.20f, applied.playhead)
    }

    @Test
    fun arrangedExportShowsDropBuildAndBreak() {
        val catalog = loadRekordbox(SyntheticRekordbox.volume())
        val track = catalog.tracks.first { it.title == "Quarto Escuro" }
        assertTrue(track.durationSeconds in 180..240)
        val analysis = catalog.analyses.getValue(track.id)
        assertEquals("PWV5", analysis.rgbDetail?.source)
        assertEquals("PWV4", analysis.rgbPreview?.source)
        assertTrue(!analysis.rgbApp)
        val duration = track.durationSeconds.toDouble()
        fun rgb(time: Double): IntArray {
            val packed = rgbSample(analysis, "RGB", false, time, duration)
            val color = (packed and 0xFFFFFF).toInt()
            return rgbParts(color)
        }
        val intro = rgb(10.0)
        val build = rgb(70.0)
        val drop = rgb(100.0)
        val broken = rgb(150.0)
        assertTrue("intro ${intro.toList()}", intro[1] > intro[0] && intro[1] > intro[2])
        assertTrue("build ${build.toList()}", build[2] > build[0] && build[2] + 12 > build[1])
        assertTrue("drop ${drop.toList()}", drop[0] > drop[1] && drop[0] > drop[2])
        assertTrue("break ${broken.toList()}", broken[1] > broken[0] && broken[1] > broken[2])
        assertEquals("RGB", catalog.usbWaveColor)
        assertEquals(0.50f, catalog.usbPlayhead)
    }

    @Test
    fun blueDetailClampsTheLastColumn() {
        val analysis = RbAnalysis(
            emptyList(),
            emptyList(),
            emptyList(),
            0,
            ByteArray(0),
            blueDetail = ByteArray(30_000) { 31 },
        )
        val sample = monoSample(analysis, false, 200.0, 200.0)
        assertEquals(31 / 255f, sample, 0.001f)
    }

    @Test
    fun fuzzOnTheNewTagsDoesNotThrow() {
        val random = kotlin.random.Random(11)
        val valid = anlzEnvelope(
            listOf(
                Triple("PWV5", 0x18, pwv5Body(IntArray(8) { pwv5Word(1, 2, 3, 4) })),
                Triple("PWV4", 0x18, pwv4Body(ByteArray(12))),
                Triple("PWV7", 0x18, pwv7Body(ByteArray(9))),
                Triple("PWV6", 0x14, pwv6Body(ByteArray(9))),
                Triple("PWV3", 0x18, pwv3Body(ByteArray(6))),
                Triple("PWVC", 0x0c, byteArrayOf(0, 1, 2, 3)),
            ),
        )
        repeat(40) {
            val bytes = valid.copyOf()
            bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
            parseAnlz(bytes)
            parseAnlz(ByteArray(random.nextInt(0, 80)) { random.nextInt(256).toByte() })
        }
        val tones = wavesFromPcm(FloatArray(22050) { index -> sin(2.0 * Math.PI * 6000.0 * index / 22050.0).toFloat() }, 22050)
        val high = tones.rgbDetail.rgb[tones.rgbDetail.columns / 2]
        assertTrue((high and 0xFF) > ((high shr 16) and 0xFF) / 2)
    }
}

private fun pwavBody(entries: ByteArray): ByteArray {
    val writer = BeWriter()
    writer.u32(entries.size)
    writer.u32(0x00100000)
    writer.bytes(entries)
    return writer.toByteArray()
}
