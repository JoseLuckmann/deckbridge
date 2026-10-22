package os.deckbridge.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * AAC opcional pelo codificador do sistema. O LAME não é empacotado:
 * a licença do app é GPL-2.0-or-later provisória e o OD-06 continua aberto.
 */
class AacWriter(private val file: File, private val sampleRate: Int, private val pull: () -> FloatArray) {
    @Volatile var running = false
        private set
    @Volatile var error: String? = null
        private set

    fun start(): Thread {
        running = true
        val thread = Thread {
            try {
                encode()
            } catch (thrown: RuntimeException) {
                error = thrown.message ?: "AAC indisponível"
            } finally {
                running = false
            }
        }
        thread.name = "deckbridge-aac"
        thread.start()
        return thread
    }

    fun stop() {
        running = false
    }

    private fun encode() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 192000)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        var ending = false
        try {
            while (true) {
                val inIndex = codec.dequeueInputBuffer(8_000)
                if (inIndex >= 0) {
                    val input = codec.getInputBuffer(inIndex)
                    val frames = if (running) pull() else FloatArray(0)
                    if (input != null && frames.isNotEmpty()) {
                        input.clear()
                        val shorts = java.nio.ByteBuffer.allocate(frames.size * 2).asShortBuffer()
                        frames.forEach { sample -> shorts.put((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
                        shorts.flip()
                        input.asShortBuffer().put(shorts)
                        codec.queueInputBuffer(inIndex, 0, frames.size * 2, pts, 0)
                        pts += frames.size / 2 * 1_000_000L / sampleRate.coerceAtLeast(1)
                    } else if (!running) {
                        codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        ending = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, 0, pts, 0)
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 0)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !started) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (outIndex >= 0) {
                    val output = codec.getOutputBuffer(outIndex)
                    if (started && output != null && info.size > 0 && track >= 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(track, output, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                    if (eos) break
                }
                if (ending && outIndex < 0) break
            }
        } finally {
            codec.stop()
            codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }
}
