package os.deckbridge.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.logic.decodeWav
import java.io.File
import java.nio.ByteOrder

fun decodeTrackPcm(context: Context, name: String, bytes: ByteArray): Pair<FloatArray, Int>? {
    val pcm = decodeTrackAudio(context, name, bytes) ?: return null
    val frames = pcm.frameCount
    if (frames <= 0) return null
    val mono = FloatArray(frames) { index -> (pcm.left[index] + pcm.right[index]) * 0.5f }
    return mono to pcm.sampleRate
}

fun decodeTrackAudio(context: Context, name: String, bytes: ByteArray): DecodedPcm? {
    val lower = name.lowercase()
    if (lower.endsWith(".wav") || (bytes.size > 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF")) {
        return decodeWav(bytes)
    }
    val suffix = when {
        lower.endsWith(".mp3") -> ".mp3"
        lower.endsWith(".flac") -> ".flac"
        lower.endsWith(".aac") -> ".aac"
        lower.endsWith(".m4a") || lower.endsWith(".mp4") -> ".m4a"
        else -> return null
    }
    return decodeCompressed(context, bytes, suffix)
}


/** Só um trecho da faixa, para a pré-escuta: WAV pela fatia de bytes; MP3/FLAC/AAC com seek e limite de amostras. */
fun decodeTrackSegment(context: Context, name: String, bytes: ByteArray, startSec: Double, seconds: Double): DecodedPcm? {
    val lower = name.lowercase()
    if (lower.endsWith(".wav") || (bytes.size > 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF")) {
        return os.deckbridge.logic.decodeWavSegment(bytes, startSec, seconds)
    }
    val suffix = when {
        lower.endsWith(".mp3") -> ".mp3"
        lower.endsWith(".flac") -> ".flac"
        lower.endsWith(".aac") -> ".aac"
        lower.endsWith(".m4a") || lower.endsWith(".mp4") -> ".m4a"
        else -> return null
    }
    return decodeCompressed(context, bytes, suffix, (startSec * 1_000_000).toLong().coerceAtLeast(0L), seconds)
}


private fun decodeCompressed(context: Context, bytes: ByteArray, suffix: String, startUs: Long = 0L, seconds: Double = 0.0): DecodedPcm? {
    val file = File.createTempFile("deckbridge-", suffix, context.cacheDir)
    return try {
        file.writeBytes(bytes)
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                track = index
                format = candidate
                break
            }
        }
        if (track < 0 || format == null) return null
        extractor.selectTrack(track)
        if (startUs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        // Buffers primitivos, pré-dimensionados pela duração do arquivo. Um ArrayList<Float> por amostra
        // custava ~700 MB de objetos numa faixa de 6 min e estourava a memória.
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val cap = if (seconds > 0.0) (rate * seconds).toInt().coerceAtLeast(rate) else rate * 60 * MAX_TRACK_MINUTES
        val estimate = if (seconds > 0.0) cap else ((durationUs * rate) / 1_000_000L).toInt().coerceIn(rate * 10, cap)
        val sink = os.deckbridge.logic.PcmAccumulator(rate, channels, estimate, cap)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(5_000)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex) ?: return null
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(info, 5_000)
            if (outIndex >= 0) {
                val buffer = decoder.getOutputBuffer(outIndex)
                if (buffer != null && info.size > 0) {
                    buffer.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    sink.append(buffer, info.size / (2 * channels))
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                if (sink.full) outputDone = true
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                outputDone = true
            }
        }
        decoder.stop()
        decoder.release()
        extractor.release()
        sink.result()
    } catch (_: Exception) {
        null
    } finally {
        file.delete()
    }
}
