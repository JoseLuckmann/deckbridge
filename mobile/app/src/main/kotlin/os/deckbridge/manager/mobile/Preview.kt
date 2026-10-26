package os.deckbridge.manager.mobile

import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
class Preview(private val app: ManagerApp) {
    var playingId by mutableStateOf<Int?>(null)
        private set

    /** Prévia montada (transição ou mix de teste) tocando agora: a chave que quem pediu deu. */
    var playingKey by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null
    private val audio: AudioManager? = app.context.getSystemService(AudioManager::class.java)

    // Outro app (ligação, música, vídeo) pediu o som: a pré-escuta para, como qualquer player bem-comportado.
    private val focus: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
        .build()

    val isPlaying: Boolean get() = player != null

    /** Posição atual em ms (0 se parado), para a cabeça andar sobre a onda. */
    fun positionMs(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    fun toggle(t: TrackItem, fromMs: Int = 0) {
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
    fun toggleFile(file: File, key: String, title: String, fromMs: Int = 0) {
        if (playingKey == key) {
            stop()
            return
        }
        stop()
        if (start(file, title, fromMs)) playingKey = key
    }

    fun seekTo(ms: Int) {
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
    fun stop() {
        val p = player
        player = null
        playingId = null
        playingKey = null
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        runCatching { audio?.abandonAudioFocusRequest(focus) }
    }
}
