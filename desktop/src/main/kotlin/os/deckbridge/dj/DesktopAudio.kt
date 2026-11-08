package os.deckbridge.dj

import os.deckbridge.logic.DecodedPcm
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Decodificação das faixas para o deck no computador: WAV pelo núcleo (o mesmo do celular), o resto pelo `ffmpeg` do
 * sistema (como a importação da Biblioteca no computador; nada é embutido). Estéreo a 48 kHz, o formato do motor.
 */
object DesktopAudio {
    const val RATE = 48000

    /** Limite de uma faixa na memória: o mesmo do celular. */
    const val MAX_MINUTES = os.deckbridge.android.MAX_TRACK_MINUTES

    fun decode(name: String, bytes: ByteArray, startSec: Double = 0.0, seconds: Double = 0.0): DecodedPcm? {
        val wav = name.lowercase().endsWith(".wav") || (bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        if (wav) {
            return if (seconds > 0) os.deckbridge.logic.decodeWavSegment(bytes, startSec, seconds) else os.deckbridge.logic.decodeWav(bytes)
        }
        if (!os.deckbridge.manager.importer.AudioIo.ffmpeg) return null
        // O ffmpeg lê de um arquivo: a cópia vai para o temporário do sistema e sai logo depois.
        val suffix = "." + name.substringAfterLast('.', "mp3").take(5)
        val tmp = File.createTempFile("deckbridge-dj-", suffix)
        return try {
            tmp.writeBytes(bytes)
            ffmpeg(tmp, startSec, if (seconds > 0) seconds else MAX_MINUTES * 60.0)
        } finally {
            tmp.delete()
        }
    }

    private fun ffmpeg(file: File, startSec: Double, seconds: Double): DecodedPcm? {
        val args = mutableListOf("ffmpeg", "-v", "error")
        if (startSec > 0) args += listOf("-ss", "%.3f".format(java.util.Locale.ROOT, startSec))
        args += listOf("-t", "%.3f".format(java.util.Locale.ROOT, seconds), "-i", file.path, "-vn", "-ac", "2", "-ar", "$RATE", "-f", "f32le", "pipe:1")
        val process = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val raw = process.inputStream.readBytes()
        if (!process.waitFor(300, TimeUnit.SECONDS) || process.exitValue() != 0 || raw.size < 8) return null
        val buffer = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val frames = raw.size / 8
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (i in 0 until frames) {
            left[i] = buffer.get(i * 2)
            right[i] = buffer.get(i * 2 + 1)
        }
        return DecodedPcm(left, right, RATE, truncated = frames >= MAX_MINUTES * 60 * RATE)
    }
}
