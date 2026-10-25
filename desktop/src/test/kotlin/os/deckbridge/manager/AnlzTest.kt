package os.deckbridge.manager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import os.deckbridge.logic.analyzeTrack
import os.deckbridge.logic.click
import os.deckbridge.logic.mergeAnalysis
import os.deckbridge.logic.parseAnlz
import os.deckbridge.logic.wavesFromPcm
import os.deckbridge.manager.anlz.AnlzBuild
import os.deckbridge.manager.anlz.AnlzModel
import os.deckbridge.manager.anlz.Cue
import os.deckbridge.manager.anlz.CueIo
import os.deckbridge.manager.anlz.GridIo

class AnlzTest {
    private val usb = TestVolumes.synthetic()
    private fun file(name: String) = File(usb, "PIONEER/USBANLZ/$name")

    private fun kaitai(bytes: ByteArray, ext: String): List<List<String>> {
        val parsers = System.getenv("KAITAI_PARSERS")
        assumeTrue("KAITAI_PARSERS não definido", parsers != null && File(parsers, "rekordbox_anlz.py").isFile)
        val tmp = File.createTempFile("anlz", ".$ext").apply { writeBytes(bytes); deleteOnExit() }
        val proc = ProcessBuilder("python3", "-I", File("tools/kaitai_anlz_check.py").absolutePath, parsers!!, tmp.path).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), out)
        return out.lines().filter { it.isNotEmpty() }.map { it.split("\t") }
    }

    @Test
    fun parseAndWriteAreByteIdenticalForEveryFileOfTheVolume() {
        val files = File(usb, "PIONEER/USBANLZ").listFiles()!!.filter { it.isFile }
        assertTrue(files.size >= 6)
        for (f in files) assertContentEquals(f.readBytes(), AnlzModel.parse(f.readBytes()).write(), f.name)
    }

    @Test
    fun editingCuesChangesOnlyCueSectionsAndKeepsUntouchedEntries() {
        val datBytes = file("ANLZ0003.DAT").readBytes()
        val extBytes = file("ANLZ0003.EXT").readBytes()
        val dat = AnlzModel.parse(datBytes)
        val ext = AnlzModel.parse(extBytes)
        val before = CueIo.read(dat, ext)
        assertTrue(before.cues.any { it.hot == 3 } && before.cues.any { it.isLoop })
        val target = before.cues.filter { it.hot != 2 }.map { if (it.hot == 5) it.copy(timeMs = 16000, comment = "Drop (ajustado)") else it } +
            Cue(7, 1234, comment = "Novo", rgb = 0x2ee0ff) + Cue(0, 9000, comment = "Memória nova") + Cue(0, 4000, loopEndMs = 5000, comment = "Loop novo")
        val (newDat, newExt) = CueIo.write(dat, ext, before, target)
        val d = newDat.write()
        val e = newExt.write()

        // O que não é cue fica byte a byte igual.
        for (tag in ext.sections.map { it.tag }.distinct().filter { it != "PCO2" }) assertContentEquals(ext.first(tag)!!.bytes, newExt.first(tag)!!.bytes, tag)
        for (tag in dat.sections.map { it.tag }.distinct().filter { it != "PCOB" }) assertContentEquals(dat.first(tag)!!.bytes, newDat.first(tag)!!.bytes, tag)
        assertEquals(datBytes.size.let { AnlzModel.parse(d).sections.sumOf { s -> s.bytes.size } + 28 }, d.size)

        // Entradas que não mudaram voltam com os mesmos bytes.
        val intro = before.extRaw.entries.first { it.key.hot == 1 }.value
        assertTrue(newExt.all("PCO2").flatMap { s -> s.bytes.toList().chunked(1) }.isNotEmpty())
        assertTrue(indexOf(e, intro) >= 0, "a entrada do hot cue A voltou idêntica")

        // O leitor do app vê o que foi pedido.
        val merged = mergeAnalysis(parseAnlz(d), parseAnlz(e)).cues
        assertEquals(target.size, merged.size)
        for (c in target) {
            val m = merged.first { it.hotIndex == (if (c.hot == 0) null else c.hot - 1) && it.timeMs == c.timeMs }
            assertEquals(c.loopEndMs, m.loopEndMs)
            assertEquals(c.comment, m.comment)
        }
        assertTrue(merged.none { it.hotIndex == 1 }, "o hot cue B foi apagado")

        // O parser Kaitai vê a mesma coisa nos dois arquivos.
        val x = kaitai(e, "EXT").filter { it[0] == "X" }
        assertEquals(target.size, x.size)
        assertTrue(x.any { it[1] == "hot_cues" && it[2] == "5" && it[3] == "16000" && it[5] == "Drop (ajustado)" })
        assertTrue(x.any { it[1] == "hot_cues" && it[2] == "7" && it[6] == "2ee0ff" })
        assertTrue(x.any { it[1] == "memory_cues" && it[7] == "loop" && it[3] == "4000" && it[4] == "5000" })
        val k = kaitai(d, "DAT").filter { it[0] == "K" }
        assertTrue(k.filter { it[1] == "hot_cues" }.all { it[2].toInt() in 1..3 })
        assertEquals(target.count { it.hot == 0 }, k.count { it[1] == "memory_cues" })
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun cuesWrittenToAFileWithoutExtCreateIt() {
        val dat = AnlzModel.parse(file("ANLZ0001.DAT").readBytes())
        val (d, e) = CueIo.write(dat, null, CueIo.read(dat, null), listOf(Cue(1, 500, comment = "A", rgb = 0xff0000), Cue(0, 900)))
        val merged = mergeAnalysis(parseAnlz(d.write()), parseAnlz(e.write())).cues
        assertEquals(2, merged.size)
    }

    @Test
    fun aRegularGridIsWrittenReadBackAndSeenByKaitai() {
        val dat = AnlzModel.parse(file("ANLZ0003.DAT").readBytes())
        val beats = GridIo.regular(128.0, 250, 20_000)
        val out = GridIo.write(dat, beats)
        for (tag in dat.sections.map { it.tag }.distinct().filter { it != "PQTZ" }) assertContentEquals(dat.first(tag)!!.bytes, out.first(tag)!!.bytes, tag)
        val bytes = out.write()
        val app = parseAnlz(bytes).beats
        assertEquals(beats.size, app.size)
        assertEquals(1, app.first().beatInBar)
        assertEquals(250, app.first().timeMs)
        assertEquals(128.0, app.first().bpm, 1e-9)
        assertEquals(beats, GridIo.read(AnlzModel.parse(bytes)))
        val b = kaitai(bytes, "DAT").filter { it[0] == "B" }
        assertEquals(beats.size, b.size)
        assertEquals(listOf("1", "12800", "250"), b.first().drop(1))
        assertEquals(beats.last().timeMs.toString(), b.last()[3])
        assertTrue(beats.zipWithNext().all { (a, c) -> c.timeMs - a.timeMs in 468..470 })
    }

    @Test
    fun aFullSetForAnAnalysedTrackIsReadByTheAppAndByKaitai() {
        val rate = 22050
        val samples = click(bpm = 124.0, seconds = 20, rate = rate, freq = 440.0)
        val read = analyzeTrack(samples, rate)
        val waves = wavesFromPcm(samples, rate)
        val cues = listOf(Cue(1, 1000, comment = "Início", rgb = 0xff3b3b), Cue(2, 8000, comment = "Drop", rgb = 0x3ddc6a), Cue(0, 1000, comment = "Memória"))
        val set = AnlzBuild.fullSet("/CONTENTS/Artista/Álbum/faixa.wav", read, waves, 20_000, cues)
        for ((name, bytes) in listOf("DAT" to set.dat, "EXT" to set.ext, "2EX" to set.ex2)) assertContentEquals(bytes, AnlzModel.parse(bytes).write(), name)
        val merged = mergeAnalysis(parseAnlz(set.dat), parseAnlz(set.ext), parseAnlz(set.ex2))
        assertTrue(merged.beats.size > 30)
        assertEquals(124.0, merged.beats.first().bpm, 1.5)
        assertEquals(3, merged.cues.size)
        assertTrue(merged.rgbDetail != null && merged.bandDetail != null && merged.waveform.isNotEmpty())
        assertTrue(merged.issues.isEmpty(), merged.issues.toString())
        val s = kaitai(set.dat, "DAT")
        assertTrue(s.any { it[0] == "S" && it[1] == "path" } && s.any { it[0] == "S" && it[1] == "beat_grid" } && s.any { it[0] == "S" && it[1] == "wave_preview" })
        assertEquals(merged.beats.size, s.count { it[0] == "B" })
        assertTrue(kaitai(set.ext, "EXT").count { it[0] == "X" } == 3)
        assertTrue(kaitai(set.ex2, "2EX").any { it[0] == "S" && it[1] == "wave_3band_scroll" })
    }
}
