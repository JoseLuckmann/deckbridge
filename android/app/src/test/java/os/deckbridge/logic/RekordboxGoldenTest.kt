package os.deckbridge.logic

import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vetores de referência do leitor do pendrive para a porta C++ (`pi/library/rekordbox.h`). Grava os arquivos
 * PIONEER/ do volume sintético (gerado por nós, sem música de terceiros) e um resumo do que o Kotlin lê deles:
 * export.pdb inteiro e a análise juntada (.DAT/.EXT/.2EX) de cada faixa. O C++ tem de escrever o mesmo resumo.
 * Sem DECKBRIDGE_WRITE_GOLDEN=1, confere que o repositório está em dia com o Kotlin.
 */
class RekordboxGoldenTest {
    private val dir = File("../../pi/library/testdata")

    private fun crc(bytes: ByteArray): String = CRC32().apply { update(bytes) }.value.toString(16)

    private fun crcInts(values: IntArray): String {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, v -> out[i * 4] = (v shr 24).toByte(); out[i * 4 + 1] = (v shr 16).toByte(); out[i * 4 + 2] = (v shr 8).toByte(); out[i * 4 + 3] = v.toByte() }
        return crc(out)
    }

    private fun bands(w: WaveBands?): String = if (w == null) "-" else "${w.low.size}:${crc(w.low)}:${crc(w.mid)}:${crc(w.high)}"
    private fun color(w: ColorWave?): String = if (w == null) "-" else "${w.height.size}:${crc(w.height)}:${crcInts(w.rgb)}:${w.source}:${(w.perSecond * 100).toInt()}"

    private fun summary(volume: MemoryVolume): String {
        val out = StringBuilder("# Gerado por RekordboxGoldenTest (Kotlin): o que o app lê do volume em testdata/usb.\n")
        val db = parseExportPdb(volume.read("PIONEER/rekordbox/export.pdb")!!).database!!
        for (t in db.tracks) {
            out.append("T ${t.id}|${t.title}|${t.artistId}|${t.albumId}|${t.genreId}|${t.keyId}|${kotlin.math.round(t.bpm * 100).toLong()}|${t.durationSeconds}|${t.sampleRate}|${t.filePath}|${t.fileName}|${t.analyzePath}|${t.artworkId}\n")
        }
        for ((kind, list) in listOf("artist" to db.artists, "album" to db.albums, "genre" to db.genres, "key" to db.keys)) {
            for (n in list) out.append("N $kind ${n.id}|${n.name}|${n.artistId}\n")
        }
        for (p in db.playlists) out.append("P ${p.id}|${p.parentId}|${p.name}|${p.folder}|${p.sortOrder}\n")
        for (e in db.entries) out.append("E ${e.playlistId}|${e.trackId}|${e.position}\n")
        for (a in db.artworks) out.append("A ${a.id}|${a.path}\n")
        for (t in db.tracks) {
            val path = normalizeRekordboxPath(t.analyzePath)
            if (path.isBlank()) continue
            val stem = if (path.endsWith(".DAT", ignoreCase = true)) path.dropLast(4) else path
            val dat = volume.read(path)?.let(::parseAnlz)
            val ext = volume.read("$stem.EXT")?.let(::parseAnlz)
            val ex2 = volume.read("$stem.2EX")?.let(::parseAnlz)
            val m = mergeAnalysis(dat, ext, ex2)
            out.append("Z ${t.id} source=${m.waveSource} issues=${m.issues.joinToString(",")}\n")
            out.append("  beats ${m.beats.size} ${m.beats.joinToString(",") { "${it.beatInBar}/${kotlin.math.round(it.bpm * 100).toLong()}/${it.timeMs}" }.let { crc(it.toByteArray()) }}\n")
            for (c in m.cues) out.append("  cue ${c.hotIndex ?: "-"}|${c.timeMs}|${c.loopEndMs ?: "-"}|${c.comment}|${c.colorRgb ?: "-"}\n")
            out.append("  wave ${m.waveform.size}:${crc(m.waveform)} blueP ${m.bluePreview.size}:${crc(m.bluePreview)} blueD ${m.blueDetail.size}:${crc(m.blueDetail)}\n")
            out.append("  rgbD ${color(m.rgbDetail)} rgbP ${color(m.rgbPreview)}\n")
            out.append("  bandD ${bands(m.bandDetail)} bandP ${bands(m.bandPreview)} bands ${bands(m.bands)}\n")
            out.append("  mood ${m.phraseMood}\n")
            for (p in m.phrases) out.append("  phrase ${p.startMs}|${p.endMs}|${p.kind}|${p.label}|${p.mood}|${p.fillMs ?: "-"}\n")
        }
        return out.toString()
    }

    @Test
    fun rekordboxVectorsMatchTheKotlinReader() {
        val volume = SyntheticRekordbox.volume()
        val files = volume.files.filterKeys { it.startsWith("PIONEER/") }
        val text = summary(volume)
        assertTrue(text.lines().count { it.startsWith("Z ") } >= 3)
        if (System.getenv("DECKBRIDGE_WRITE_GOLDEN") == "1") {
            File(dir, "usb").deleteRecursively()
            for ((path, bytes) in files) File(dir, "usb/$path").apply { parentFile.mkdirs(); writeBytes(bytes) }
            File(dir, "summary.txt").writeText(text)
        }
        for ((path, bytes) in files) {
            val f = File(dir, "usb/$path")
            assertTrue("falta $f: rode com DECKBRIDGE_WRITE_GOLDEN=1", f.exists())
            assertTrue("$f está velho: regrave com DECKBRIDGE_WRITE_GOLDEN=1", f.readBytes().contentEquals(bytes))
        }
        assertEquals("pi/library/testdata/summary.txt está velho", File(dir, "summary.txt").readText(), text)
    }
}
