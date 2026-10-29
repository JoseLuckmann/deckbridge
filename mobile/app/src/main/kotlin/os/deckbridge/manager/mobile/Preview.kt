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
    companion object {
        /** Saltar para uma virada cai este tanto antes dela, para ouvir a troca chegando. */
        const val LEAD_IN_MS = 10_000
    }

    override var playingId by mutableStateOf<Int?>(null)
        private set

    /** Prévia montada (transição ou mix de teste) tocando agora: a chave que quem pediu deu. */
    override var playingKey by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null
    private val audio: AudioManager? = app.context.getSystemService(AudioManager::class.java)

    /** O set tocando como player de música (segundo plano): o nome, os trechos e a chave. Null nas prévias. */
    data class SetPlay(val key: String, val title: String, val chapters: List<Chapter>, val durationMs: Int, val setId: Int? = null)

    var setPlay by mutableStateOf<SetPlay?>(null)
        private set

    /** O set em segundo plano está pausado (as prévias não pausam: param). */
    var paused by mutableStateOf(false)
        private set

    override val inBackground: Boolean get() = setPlay != null

    // Outro app (ligação, música, vídeo) pediu o som: a pré-escuta para, como qualquer player bem-comportado. O set em
    // segundo plano pausa numa perda curta (ligação, aviso) e para numa perda de vez (outro player).
    private val focus: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
        .build()
    private val setFocus: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> stop()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
            }
        }
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
        @Volatile var paused = false
        @Volatile var pendingSeek = -1
        @Volatile private var written = 0L
        val track: AudioTrack = run {
            // 16 bits: todo aparelho toca (float nem sempre) e é o que a escuta precisa.
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(source.rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val min = AudioTrack.getMinBufferSize(source.rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, source.rate * 4 * 80 / 1000))
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
            val inter = ShortArray(n * 2)
            try {
                track.play()
                while (!stopped) {
                    if (paused) {
                        runCatching { track.pause() }
                        while (paused && !stopped) sleep(30)
                        if (stopped) break
                        runCatching { track.play() }
                    }
                    val seek = pendingSeek
                    if (seek >= 0) {
                        source.seek(seek)
                        track.pause(); track.flush(); written = 0; track.play()
                        pendingSeek = -1
                    }
                    val got = source.read(l, r, n)
                    if (got <= 0) break
                    for (i in 0 until got) {
                        inter[2 * i] = (l[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        inter[2 * i + 1] = (r[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                    track.write(inter, 0, got * 2, AudioTrack.WRITE_BLOCKING)
                    written += got
                }
                // `this@Preview.stop()`: dentro da thread, `stop()` sozinho é o Thread.stop() do Java, que lança
                // UnsupportedOperationException na thread da tela (derrubava o app quando a prévia chegava ao fim).
                if (!stopped) app.onMain { if (stream === this) this@Preview.stop() }
            } catch (e: Exception) {
                app.onMain { if (stream === this) { this@Preview.stop(); app.notice = os.deckbridge.manager.Banner("Não consegui tocar a prévia: ${e.message}", true) } }
            }
        }

        fun halt() {
            stopped = true
            runCatching { track.pause(); track.flush() }
            runCatching { track.release() }
            runCatching { source.close() }
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
        val wasSet = setPlay != null
        setPlay = null
        paused = false
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        runCatching { audio?.abandonAudioFocusRequest(focus) }
        runCatching { audio?.abandonAudioFocusRequest(setFocus) }
        if (wasSet) SetPlaybackService.stop(app.context)
    }

    // ---- o set como player de música ---------------------------------------------------------------------------

    /**
     * Toca o set montado como player de música: segue tocando fora da tela do set, com o app em segundo plano e a tela
     * bloqueada (serviço em primeiro plano com notificação e controles na tela de bloqueio).
     */
    override fun playSet(file: File, key: String, title: String, chapters: List<Chapter>, setId: Int?) {
        if (playingKey == key) {
            stop()
            return
        }
        stop()
        if (audio != null && audio.requestAudioFocus(setFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            app.notice = os.deckbridge.manager.Banner("Áudio ocupado", true)
            return
        }
        if (!start(file, title, 0)) return
        playingKey = key
        setPlay = SetPlay(key, title, chapters, runCatching { player?.duration ?: 0 }.getOrDefault(0), setId)
        paused = false
        SetPlaybackService.start(app.context)
    }

    /** O set inteiro, feito na hora ([source]), como player de música em segundo plano. */
    override fun playSetStream(source: os.deckbridge.manager.mix.PcmSource, key: String, title: String, chapters: List<Chapter>, setId: Int?, lengthMs: Int) {
        if (playingKey == key) {
            stop()
            return
        }
        stop()
        if (audio != null && audio.requestAudioFocus(setFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            source.close()
            app.notice = os.deckbridge.manager.Banner("Áudio ocupado", true)
            return
        }
        val run = runCatching { StreamRun(source) }.getOrElse {
            source.close()
            app.notice = os.deckbridge.manager.Banner("Não consegui tocar \"$title\": ${it.message}", true)
            return
        }
        stream = run
        playingKey = key
        setPlay = SetPlay(key, title, chapters, lengthMs, setId)
        paused = false
        run.isDaemon = true
        run.start()
        SetPlaybackService.start(app.context)
    }

    fun pause() {
        if (setPlay == null) return stop()
        val s = stream
        if (s != null) s.paused = true else runCatching { player?.pause() ?: return }
        paused = true
        SetPlaybackService.refresh(app.context)
    }

    fun resume() {
        if (audio != null && audio.requestAudioFocus(setFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        val s = stream
        if (s != null) s.paused = false else runCatching { player?.start() ?: return }
        paused = false
        SetPlaybackService.refresh(app.context)
    }

    fun togglePause() = if (paused) resume() else pause()

    /**
     * Salta de virada em virada: para a próxima (+1) ou volta (−1; logo depois de uma, volta à anterior). Cai uns
     * segundos antes da virada ([LEAD_IN_MS]) para ouvir a troca chegando. Sem viradas marcadas, pula de trecho em trecho.
     */
    fun skip(step: Int) {
        val sp = setPlay ?: return
        val turns = sp.chapters.filter { it.turn }.map { (it.startMs - if (sp.chapters.any { c -> !c.turn }) LEAD_IN_MS else 0).coerceAtLeast(0) }
        if (turns.isEmpty()) return
        val pos = positionMs()
        val target = if (step > 0) turns.firstOrNull { it > pos + 500 } else turns.lastOrNull { it < pos - 3_000 } ?: 0
        if (target == null) return stop()
        seekTo(target)
        SetPlaybackService.refresh(app.context)
    }

    /** "Virada 3/8" na virada, "Faixa 4/9" entre elas (o set inteiro), para a segunda linha do player. */
    fun whereLabel(): String {
        val sp = setPlay ?: return ""
        val pos = positionMs()
        val now = sp.chapters.getOrNull(Chapter.at(sp.chapters, pos)) ?: return sp.title
        val turns = sp.chapters.filter { it.turn }
        val tracks = sp.chapters.filter { !it.turn }
        return if (now.turn) "Virada ${turns.indexOf(now) + 1}/${turns.size} · ${sp.title}" else "Faixa ${tracks.indexOf(now) + 1}/${tracks.size} · ${sp.title}"
    }

    /** O trecho tocando agora ("A → B"), para a notificação e a barra do player. */
    fun nowPlaying(): String {
        val sp = setPlay ?: return ""
        return sp.chapters.getOrNull(Chapter.at(sp.chapters, positionMs()))?.title ?: sp.title
    }
}
