package os.deckbridge.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.logic.decodeWavMono
import java.io.File
import java.nio.ByteOrder

fun decodeTrackAudio(context: Context, name: String, bytes: ByteArray): DecodedPcm? {
    val lower = name.lowercase()
    if (lower.endsWith(".wav") || (bytes.size > 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF")) {
        return decodeWavMono(bytes)
    }
    if (!lower.endsWith(".mp3")) return null
    return decodeMp3(context, bytes)
}

private fun decodeMp3(context: Context, bytes: ByteArray): DecodedPcm? {
    val file = File.createTempFile("deckbridge-", ".mp3", context.cacheDir)
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
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mono = ArrayList<Float>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone && mono.size < rate * 60 * 10) {
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
                    val frames = info.size / (2 * channels)
                    repeat(frames) {
                        var sum = 0
                        repeat(channels) { sum += buffer.short.toInt() }
                        mono += (sum / channels) / 32768f
                    }
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                outputDone = true
            }
        }
        decoder.stop()
        decoder.release()
        extractor.release()
        if (mono.isEmpty()) null else DecodedPcm(mono.toFloatArray(), rate)
    } catch (_: Exception) {
        null
    } finally {
        file.delete()
    }
}
