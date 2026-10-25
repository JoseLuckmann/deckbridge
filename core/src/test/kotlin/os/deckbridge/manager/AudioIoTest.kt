package os.deckbridge.manager

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import os.deckbridge.manager.importer.AudioIo

class AudioIoTest {
    private fun le(out: ByteArrayOutputStream, v: Long, bytes: Int) {
        for (i in 0 until bytes) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    /** WAV de seno a 440 Hz, amplitude 0,5, com o formato pedido. */
    private fun wav(rate: Int, channels: Int, bits: Int, float: Boolean, seconds: Int = 3): File {
        val frames = rate * seconds
        val data = ByteArrayOutputStream()
        for (n in 0 until frames) {
            val x = 0.5 * sin(2 * PI * 440.0 * n / rate)
            for (c in 0 until channels) when {
                float -> le(data, java.lang.Float.floatToIntBits(x.toFloat()).toLong(), 4)
                bits == 16 -> le(data, (x * 32767).toLong(), 2)
                bits == 24 -> le(data, (x * 8388607).toLong(), 3)
                else -> le(data, (x * 2147483647.0).toLong(), 4)
            }
        }
        val bytes = data.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); le(out, 36L + bytes.size, 4); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le(out, 16, 4); le(out, if (float) 3 else 1, 2); le(out, channels.toLong(), 2)
        le(out, rate.toLong(), 4); le(out, (rate * channels * bits / 8).toLong(), 4); le(out, (channels * bits / 8).toLong(), 2); le(out, bits.toLong(), 2)
        out.write("data".toByteArray()); le(out, bytes.size.toLong(), 4); out.write(bytes)
        return File(TestVolumes.tempDir("dbm-wav"), "t.wav").apply { writeBytes(out.toByteArray()) }
    }

    @Test
    fun everySupportedWavLayoutDecodesToTheSameSineAtAnalysisRate() {
        val cases = listOf(
            Triple(44100, 2, 16) to false, Triple(48000, 2, 24) to false, Triple(44100, 1, 32) to false,
            Triple(44100, 2, 32) to true, Triple(22050, 1, 16) to false, Triple(96000, 2, 24) to false,
        )
        for ((fmt, float) in cases) {
            val (rate, ch, bits) = fmt
            val m = AudioIo.mono(wav(rate, ch, bits, float))
            val factor = (rate / 22050).coerceAtLeast(1)
            assertEquals(rate / factor, m.rate, "taxa de $rate/$ch/$bits")
            assertEquals(rate * 3 / factor, m.samples.size, "tamanho de $rate/$ch/$bits")
            val rms = sqrt(m.samples.map { it * it.toDouble() }.average())
            assertTrue(rms in 0.33..0.37, "RMS $rms em $rate/$ch/$bits (esperado ~0,354)")
        }
    }

    @Test
    fun probeReadsDurationFromTheWavHeaderAndTheFileNameGivesArtistAndTitle() {
        val f = wav(44100, 2, 16, false, seconds = 5)
        val named = File(f.parentFile, "Nara Vale - Corredor.wav").also { f.renameTo(it) }
        val info = AudioIo.probe(named)
        assertEquals(5, info.durationSec)
        assertEquals(44100, info.sampleRate)
        assertEquals("Nara Vale", info.artist)
        assertEquals("Corredor", info.title)
    }

    @Test
    fun compressedFormatsGoThroughFfmpegWithTagsDurationAndTempo() {
        org.junit.Assume.assumeTrue("ffmpeg/ffprobe ausentes", AudioIo.ffmpeg && AudioIo.ffprobe)
        val dir = TestVolumes.tempDir("dbm-ff")
        val src = File(dir, "origem.wav")
        src.writeBytes(os.deckbridge.logic.encodeWavMono16(os.deckbridge.logic.click(bpm = 124.0, seconds = 30, rate = 22050, freq = 440.0), 22050))
        var tried = 0
        for ((ext, codec) in listOf("mp3" to "libmp3lame", "flac" to "flac", "m4a" to "aac", "ogg" to "libvorbis")) {
            val out = File(dir, "Artista Etiqueta - Título Etiqueta.$ext")
            val enc = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", src.path, "-c:a", codec, "-metadata", "artist=Artista da Etiqueta", "-metadata", "title=Título da Etiqueta", "-metadata", "album=Álbum da Etiqueta", "-metadata", "genre=House", "-metadata", "date=2024", "-metadata", "track=5/12", out.path)
                .redirectErrorStream(true).start()
            enc.inputStream.readBytes()
            if (enc.waitFor() != 0 || !out.isFile) continue // este ffmpeg não tem esse codificador
            tried++
            val p = os.deckbridge.manager.importer.ImportPrep.prepare(out)
            assertEquals("Título da Etiqueta", p.info.title, ext)
            assertEquals("Artista da Etiqueta", p.info.artist, ext)
            assertEquals("Álbum da Etiqueta", p.info.album, ext)
            assertEquals("House", p.info.genre, ext)
            assertEquals(2024, p.info.year, ext)
            assertEquals(5, p.info.trackNumber, ext)
            assertTrue(p.info.durationSec in 29..31, "$ext: duração ${p.info.durationSec}")
            assertEquals(124.0, p.read.bpm, 2.0, "$ext: andamento")
            assertTrue(p.read.beats.size > 40, ext)
        }
        assertTrue(tried >= 2, "o ffmpeg desta máquina só gerou $tried formato(s) de teste")
    }
}
