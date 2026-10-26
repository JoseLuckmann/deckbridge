package os.deckbridge.manager.mobile

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.ByteOrder
import os.deckbridge.logic.PcmAccumulator
import os.deckbridge.manager.importer.AudioInfo
import os.deckbridge.manager.importer.MonoAudio
import os.deckbridge.manager.importer.PlatformDecoder

/**
 * MP3, FLAC, AAC e M4A pelo MediaCodec do aparelho. Não há ffmpeg no Android. Só roda em aparelho de verdade:
 * os testes de JVM não têm codec, então a decodificação de MP3/FLAC aqui é `BLOCKED_HARDWARE` até testar no S25 Ultra.
 */
class MediaDecoder : PlatformDecoder {
    override fun probe(file: File): AudioInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            fun meta(key: Int) = r.extractMetadata(key)?.trim().orEmpty()
            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: return null
            if (durationMs <= 0) return null
            val name = file.nameWithoutExtension
            val split = name.split(" - ", limit = 2)
            AudioInfo(
                durationSec = (durationMs / 1000).toInt().coerceAtLeast(1),
                sampleRate = runCatching { meta(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE).toInt() }.getOrDefault(0),
                bitrateKbps = (meta(MediaMetadataRetriever.METADATA_KEY_BITRATE).toLongOrNull() ?: 0L).div(1000).toInt(),
                sizeBytes = file.length(),
                title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE).ifEmpty { if (split.size == 2) split[1].trim() else name.trim() },
                artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifEmpty { if (split.size == 2) split[0].trim() else "" },
                album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = meta(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = meta(MediaMetadataRetriever.METADATA_KEY_YEAR).take(4).toIntOrNull() ?: 0,
                trackNumber = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    override fun mono(file: File): MonoAudio? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    track = index
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime).also { decoder = it }
            codec.configure(format, null, null, 0)
            codec.start()
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val cap = rate * 60 * MAX_MINUTES
            val estimate = ((durationUs * rate) / 1_000_000L).toInt().coerceIn(rate * 10, cap)
            // Já em mono ~22 kHz, aos pedaços (a faixa inteira em estéreo não cabe na memória com folga).
            val sink = MonoSink(rate, channels, estimate, cap)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0
            // Um arquivo com defeito pode deixar o decodificador sem entregar nada: não fica preso para sempre.
            val deadline = System.currentTimeMillis() + MAX_DECODE_MS
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) return null
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: return null
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 5_000)
                if (outIndex >= 0) {
                    guard = 0
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.append(buffer, info.size / (2 * channels))
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || sink.full) outputDone = true
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++guard > 200) {
                    outputDone = true
                }
            }
            MonoAudio(sink.result() ?: return null, sink.outRate)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Um trecho estéreo, para a prévia de transição: pula para o quadro-chave antes de [startMs], decodifica, e descarta
     * o que veio antes do ponto pedido. Só roda em aparelho (`BLOCKED_HARDWARE` nos testes de JVM).
     */
    override fun segment(file: File, startMs: Int, lengthMs: Int): os.deckbridge.logic.DecodedPcm? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") } ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val startUs = startMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: return null).also { decoder = it }
            codec.configure(format, null, null, 0)
            codec.start()
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val want = (lengthMs.toLong() * rate / 1000).toInt()
            // Folga para o que vem antes do ponto (do quadro-chave até ele), descartada no fim.
            val cap = want + rate * 4
            val sink = PcmAccumulator(rate, channels, cap, cap)
            var firstUs = -1L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: return null
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 5_000)
                if (outIndex >= 0) {
                    guard = 0
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        if (firstUs < 0) firstUs = info.presentationTimeUs
                        buffer.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.append(buffer, info.size / (2 * channels))
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    val skip = ((startUs - firstUs.coerceAtLeast(0)) * rate / 1_000_000L).coerceAtLeast(0)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || sink.full || (firstUs >= 0 && sink.count >= skip + want)) outputDone = true
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++guard > 200) {
                    outputDone = true
                }
            }
            val pcm = sink.result() ?: return null
            val skip = ((startUs - firstUs.coerceAtLeast(0)) * rate / 1_000_000L).toInt().coerceIn(0, pcm.frameCount)
            val n = (pcm.frameCount - skip).coerceAtMost(want).coerceAtLeast(0)
            os.deckbridge.logic.DecodedPcm(pcm.left.copyOfRange(skip, skip + n), pcm.right.copyOfRange(skip, skip + n), rate)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val MAX_MINUTES = 15

        /** Tempo máximo para decodificar uma faixa (as de 15 minutos levam poucos segundos num celular). */
        const val MAX_DECODE_MS = 120_000L
    }
}
