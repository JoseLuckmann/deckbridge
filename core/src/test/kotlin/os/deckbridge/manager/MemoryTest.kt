package os.deckbridge.manager

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import os.deckbridge.manager.importer.AudioIo
import os.deckbridge.manager.mix.MixRenderer
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.SegmentReader
import os.deckbridge.manager.mix.Transition

/**
 * A memória da prévia do MIX não depende do tamanho do arquivo: antes, cada trecho lia o WAV inteiro (um Extended Mix
 * tem 80–150 MB) e, com a análise da troca e o set tocando ao mesmo tempo, o app caía sem memória no aparelho.
 */
class MemoryTest {
    /** WAV estéreo 16 bits 44,1 kHz com bumbo a 124 BPM, escrito direto no disco (sem montar tudo na memória). */
    private fun wav(seconds: Int): File {
        val f = File.createTempFile("memoria", ".wav").apply { deleteOnExit() }
        val rate = 44100
        val frames = rate * seconds
        java.io.BufferedOutputStream(f.outputStream(), 1 shl 16).use { o ->
            fun le(v: Int, n: Int) { for (i in 0 until n) o.write((v shr (8 * i)) and 0xFF) }
            o.write("RIFF".toByteArray()); le(36 + frames * 4, 4); o.write("WAVEfmt ".toByteArray())
            le(16, 4); le(1, 2); le(2, 2); le(rate, 4); le(rate * 4, 4); le(4, 2); le(16, 2)
            o.write("data".toByteArray()); le(frames * 4, 4)
            val beat = rate * 60 / 124
            for (n in 0 until frames) {
                val t = (n % beat).toDouble() / rate
                val x = (0.6 * Math.exp(-t * 25) * sin(2 * PI * 55 * t) + 0.1 * sin(2 * PI * 440.0 * n / rate))
                val v = (x * 32767).toInt()
                le(v, 2); le(v, 2)
            }
        }
        return f
    }

    private fun allocated(block: () -> Unit): Long {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = mx.getThreadAllocatedBytes(id)
        block()
        return mx.getThreadAllocatedBytes(id) - before
    }

    @Test fun `a previa do mix nao depende do tamanho do arquivo`() {
        val tr = Transition(1, 2, outMs = 40_000, inMs = 2_000, bars = 16, style = MixStyle.BASS_SWAP)
        fun preview(f: File) = allocated {
            val r = SegmentReader { s, len -> AudioIo.segment(f, s, len) }
            assertNotNull(MixRenderer.transition(r, 124.0, r, 124.0, tr, waves = true))
        }
        val short = wav(70)   // ~12 MB
        val long = wav(420)   // ~74 MB, um Extended Mix de 7 minutos
        preview(short) // aquece
        val a = preview(short)
        val b = preview(long)
        // O mesmo trabalho (os mesmos trechos): a diferença entre as duas é ruído, não o arquivo de 74 MB lido duas vezes.
        assertTrue(kotlin.math.abs(b - a) < 8_000_000, "curta $a bytes, longa $b bytes")
    }
}
