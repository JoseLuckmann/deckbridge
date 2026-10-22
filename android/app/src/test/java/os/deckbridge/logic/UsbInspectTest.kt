package os.deckbridge.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UsbInspectTest {
    @Test
    fun syntheticFolderRoundTripIsReadOnly() {
        val volume = SyntheticRekordbox.volume()
        val root = File(System.getProperty("java.io.tmpdir"), "deckbridge-inspect-synthetic")
        root.deleteRecursively()
        volume.files.forEach { (path, bytes) ->
            val file = File(root, path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        val before = HashMap<String, Long>()
        root.walkTopDown().filter { it.isFile }.forEach { before[it.relativeTo(root).path.replace('\\', '/')] = it.lastModified() }
        val catalog = loadRekordbox(FolderVolume(root, "pasta sintética"))
        val text = inspectText(catalog)
        assertEquals(null, catalog.error)
        assertTrue(text.contains("faixas: ${catalog.tracks.size}"))
        assertTrue(text.contains("playlists:"))
        assertTrue(text.contains("PWV5:"))
        assertTrue(text.contains("DEVSETTING cor:"))
        assertTrue(text.contains("Somente leitura"))
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val key = file.relativeTo(root).path.replace('\\', '/')
            assertEquals(before[key], file.lastModified())
        }
        root.deleteRecursively()
    }

    @Test
    fun ourExportIsNotARekordboxFile() {
        val rate = 22050
        val samples = FloatArray(rate) { 0.2f * ((it / 100) % 2) }
        val waves = wavesFromPcm(samples, rate)
        val song = OurSong(
            title = "Faixa Real",
            artist = "Teste",
            album = "Corpus",
            keyName = "Am",
            bpm = 124.0,
            seconds = 1,
            sampleRate = rate,
            fileName = "faixa-real.wav",
            wav = encodeWavMono16(samples, rate),
            waves = waves,
            beats = listOf(AnlzBeat(1, 124.0, 0), AnlzBeat(2, 124.0, 484)),
            hot = listOf(CueSpec(1, 0, "Drop", 255, 80, 20)),
            memory = listOf(CueSpec(0, 0, "Intro", 20, 20, 20)),
            loop = CueSpec(0, 200, "Loop", 0, 0, 0) to 800,
        )
        val volume = ourMusicVolume(listOf(song))
        val catalog = loadRekordbox(volume)
        assertEquals("export deste app", catalog.label)
        assertEquals(true, catalog.simulated)
        assertEquals("Faixa Real", catalog.tracks.single().title)
        val analysis = catalog.analyses.getValue(catalog.tracks.single().id)
        assertTrue(analysis.rgbDetail != null)
        assertTrue(analysis.rgbPreview != null)
        assertTrue(analysis.bandDetail != null || analysis.bandPreview != null)
        assertTrue(analysis.hotCues.any { it.comment == "Drop" })
        assertTrue(analysis.memoryCues.any { it.loopEndMs != null })
        assertTrue(catalog.playlists.any { it.name == "Real" && it.trackIds.size == 1 })
        val text = inspectText(catalog)
        assertTrue(text.contains("PWV5: 1"))
        assertTrue(text.contains("Somente leitura"))
    }

    @Test
    fun externalFolder() {
        val path = System.getProperty("usb.folder").orEmpty()
        if (path.isBlank()) return
        val root = File(path)
        if (!root.isDirectory) return
        val catalog = loadRekordbox(FolderVolume(root, root.absolutePath))
        val text = inspectText(catalog)
        val report = System.getProperty("usb.report").orEmpty().ifBlank { "/tmp/deckbridge-inspect.txt" }
        File(report).writeText(text)
        println(text)
        assertTrue(text.contains("faixas:"))
        assertTrue(text.contains("Somente leitura"))
    }
}
