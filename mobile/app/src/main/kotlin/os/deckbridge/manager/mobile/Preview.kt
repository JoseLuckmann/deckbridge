package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import os.deckbridge.manager.pdb.TrackItem

/**
 * Pré-escuta pelo player do Android, a partir do arquivo na biblioteca local. Ouvir antes de marcar é o que faz a triagem
 * valer a pena. Só decide em aparelho de verdade: nos testes de JVM não há codec (`BLOCKED_HARDWARE`).
 */
class Preview(private val app: ManagerApp) : StudioPreview {
    override var playingId by mutableStateOf<Int?>(null)
        private set

    /** Prévia montada (transição ou mix de teste) tocando agora: a chave que quem pediu deu. */
    override var playingKey by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null
    private val audio: AudioManager? = app.context.getSystemService(AudioManager::class.java)

    // Outro app (ligação, música, vídeo) pediu o som: a pré-escuta para, como qualquer player bem-comportado.
    private val focus: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
        .build()

    val isPlaying: Boolean get() = player != null || stream != null

    /** Posição atual em ms (0 se parado), para a cabeça andar sobre a onda. */
    override fun positionMs(): Int = stream?.heardMs() ?: runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    /**
     * A prévia do MIX ao vivo: uma thread pede quadros à fonte (que mixa na hora, com a mão do DJ) e os escreve num
     * AudioTrack em modo de fluxo, com um buffer curto (~80 ms) para a mão soar logo.
     */
    private inner class StreamRun(val source: os.deckbridge.manager.mix.PcmSource) : Thread("deckbridge-mix-ao-vivo") {
        @Volatile var stopped = false
        @Volatile var pendingSeek = -1
        @Volatile private var written = 0L
        val track: AudioTrack = run {
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(source.rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val min = AudioTrack.getMinBufferSize(source.rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, source.rate * 8 * 80 / 1000))
                .build()
        }

        /** O que se ouve: o que a fonte já fez menos o que ainda está no buffer do AudioTrack. */
        fun heardMs(): Int {
            pendingSeek.let { if (it >= 0) return it }
            val head = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
            val queued = (written - head).coerceIn(0L, source.rate.toLong())
            return (source.renderedMs - queued * 1000 / source.rate).toInt().coerceAtLeast(0)
        }

        override fun run() {
            val n = 512
            val l = FloatArray(n)
            val r = FloatArray(n)
            val inter = FloatArray(n * 2)
            try {
                track.play()
                while (!stopped) {
                    val seek = pendingSeek
                    if (seek >= 0) {
                        source.seek(seek)
                        track.pause(); track.flush(); written = 0; track.play()
                        pendingSeek = -1
                    }
                    val got = source.read(l, r, n)
                    if (got <= 0) break
                    for (i in 0 until got) { inter[2 * i] = l[i]; inter[2 * i + 1] = r[i] }
                    track.write(inter, 0, got * 2, AudioTrack.WRITE_BLOCKING)
                    written += got
                }
                if (!stopped) app.onMain { if (stream === this) stop() }
            } catch (e: Exception) {
                app.onMain { if (stream === this) { stop(); app.notice = os.deckbridge.manager.Banner("Não consegui tocar a prévia: ${e.message}", true) } }
            }
        }

        fun halt() {
            stopped = true
            runCatching { track.pause(); track.flush() }
            runCatching { track.release() }
        }
    }

    private var stream: StreamRun? = null

    override fun playStream(source: os.deckbridge.manager.mix.PcmSource, key: String, title: String) {
        if (playingKey == key) {
            stop()
            return
        }
        stop()
        if (audio != null && audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            app.notice = os.deckbridge.manager.Banner("Áudio ocupado", true)
            return
        }
        val run = runCatching { StreamRun(source) }.getOrElse {
            app.notice = os.deckbridge.manager.Banner("Não consegui tocar \"$title\": ${it.message}", true)
            return
        }
        stream = run
        playingKey = key
        run.isDaemon = true
        run.start()
    }

    override fun toggle(t: TrackItem, fromMs: Int) {
        if (playingId == t.id && fromMs == 0) {
            stop()
            return
        }
        stop()
        val file = File(app.root, t.filePath.replace('\\', '/').trimStart('/'))
        if (!file.isFile) {
            app.notice = os.deckbridge.manager.Banner("O áudio de \"${t.title}\" não está no aparelho.", true)
            return
        }
        if (start(file, t.title, fromMs)) playingId = t.id
    }

    /** Toca um WAV montado (prévia de transição ou mix de teste); tocar a mesma chave de novo para. */
    override fun toggleFile(file: File, key: String, title: String, fromMs: Int) {
        if (playingKey == key) {
            stop()
            return
        }
        stop()
        if (start(file, title, fromMs)) playingKey = key
    }

    override fun seekTo(ms: Int) {
        stream?.let { it.pendingSeek = ms; return }
        runCatching { player?.seekTo(ms) }
    }

    private fun start(file: File, title: String, fromMs: Int): Boolean {
        if (audio != null && audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            app.notice = os.deckbridge.manager.Banner("Áudio ocupado", true)
            return false
        }
        return try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            p.prepare()
            if (fromMs > 0) p.seekTo(fromMs)
            p.start()
            player = p
            true
        } catch (e: Exception) {
            stop()
            app.notice = os.deckbridge.manager.Banner("Não consegui tocar \"$title\": ${e.message}", true)
            false
        }
    }

    /** Para e solta o player e o foco de áudio. Seguro de chamar a qualquer momento, quantas vezes for. */
    override fun stop() {
        val p = player
        player = null
        stream?.halt()
        stream = null
        playingId = null
        playingKey = null
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        runCatching { audio?.abandonAudioFocusRequest(focus) }
    }
}
