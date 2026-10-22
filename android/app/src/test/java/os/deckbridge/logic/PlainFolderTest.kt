package os.deckbridge.logic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class PlainFolderTest {
    @Test
    fun folderWithoutExportIsAnalyzedAndCached() {
        val root = File("/tmp/plain-folder-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val rate = 22050
        val bpm = 128.0
        val seconds = 8
        val samples = FloatArray(rate * seconds)
        val interval = rate * 60.0 / bpm
        var cursor = 0.0
        while (cursor < samples.size) {
            val at = cursor.toInt()
            for (offset in 0 until 80) {
                if (at + offset < samples.size) samples[at + offset] = 0.8f
            }
            cursor += interval
        }
        File(root, "Click House.wav").writeBytes(encodeWavMono16(samples, rate))
        val tone = FloatArray(rate * 4) { index ->
            (0.35 * sin(2.0 * Math.PI * 440.0 * index / rate)).toFloat()
        }
        File(root, "Tom A.wav").writeBytes(encodeWavMono16(tone, rate))
        val cache = File("/tmp/plain-folder-cache").apply { deleteRecursively(); mkdirs() }
        var decodes = 0
        val notes = ArrayList<String>()
        val decode = { _: String, bytes: ByteArray ->
            decodes += 1
            val pcm = decodeWavMono(bytes) ?: error("wav")
            pcm.samples to pcm.rate
        }
        val first = loadPlainFolder(FolderVolume(root, "pasta solta"), cache, decode) { notes += it.scanNote }
        assertEquals(null, first.error)
        assertEquals(2, first.tracks.size)
        assertTrue(notes.any { it.startsWith("A analisar") })
        assertTrue(first.scanNote.contains("sem export"))
        val house = first.tracks.first { it.title == "Click House" }
        assertEquals(128.0, house.bpm, 2.0)
        assertTrue(house.keyName.isNotBlank())
        val wave = first.analyses[house.id]
        assertTrue(wave?.rgbPreview != null || wave?.rgbDetail != null)
        assertTrue(wave?.rgbApp == true)
        val used = decodes
        val second = loadPlainFolder(FolderVolume(root, "pasta solta"), cache, decode)
        assertEquals(used, decodes)
        assertEquals(house.bpm, second.tracks.first { it.title == "Click House" }.bpm, 0.01)
        assertEquals("A", second.tracks.first { it.title == "Tom A" }.keyName)
    }

    @Test
    fun minorSpellingFromGiantStepsParses() {
        assertEquals("Gm", normalizeKeyName("G minor"))
        assertEquals("F#m", normalizeKeyName("Gb minor"))
        assertEquals(1.0, mirexWeight("Gm", "G minor"), 0.0)
    }
}