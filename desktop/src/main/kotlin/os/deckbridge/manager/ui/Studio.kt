package os.deckbridge.manager.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayInputStream
import java.io.File
import java.util.prefs.Preferences
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import os.deckbridge.manager.Banner
import os.deckbridge.manager.ManagerModel
import os.deckbridge.manager.importer.AudioIo
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.studio.LocalBack
import os.deckbridge.manager.studio.ManagerTheme
import os.deckbridge.manager.studio.MixScreen
import os.deckbridge.manager.studio.Palette
import os.deckbridge.manager.studio.RbShape
import os.deckbridge.manager.studio.SetDesk
import os.deckbridge.manager.studio.SetMapScreen
import os.deckbridge.manager.studio.SetScreen
import os.deckbridge.manager.studio.StudioHost
import os.deckbridge.manager.studio.StudioJob
import os.deckbridge.manager.studio.StudioPreview

/**
 * O lado do computador das telas de montar set (as mesmas do celular, em `studio/`): onde guardar os planos, como tocar,
 * como rodar em segundo plano e o Esc como "voltar". Com o pendrive aberto, nada é gravado sem o usuário aplicar a fila.
 */
class DesktopStudio(
    override val model: ManagerModel,
    private val prefs: Preferences = Preferences.userRoot().node("deckbridge/biblioteca"),
    /** Onde ficam os planos dos sets de pendrives (um por pendrive). */
    private val setsHome: File = File(System.getProperty("user.home"), ".deckbridge/sets"),
) : StudioHost {
    override val root: File get() = model.volume?.root ?: localLibraryRoot()

    /** Biblioteca local: ao lado dela, como no celular. Pendrive: numa pasta do computador por pendrive (nunca nele). */
    override val setsDir: File get() {
        val r = root.absoluteFile
        if (r == localLibraryRoot().absoluteFile) return File(r.parentFile, "sets")
        val key = Integer.toHexString(r.canonicalPath.hashCode())
        return File(setsHome, key)
    }
    override val cacheDir: File = File(System.getProperty("java.io.tmpdir"), "deckbridge-${System.getProperty("user.name")}").apply { mkdirs() }
    override val preview = DesktopPreview(this)
    override val sets = SetDesk(this)

    private var shown by mutableStateOf<Banner?>(null)
    override var notice: Banner?
        get() = shown
        set(value) {
            shown = value
            noticeUndo = false
        }

    /** O aviso atual veio de [did]: dá para desfazer pelo próprio aviso. */
    var noticeUndo by mutableStateOf(false)
        private set

    /** Ação rápida feita: avisa com DESFAZER (Ctrl+Z), em vez de pedir confirmação antes (dá para voltar atrás). */
    override fun did(text: String?) {
        if (text == null) return
        notice = Banner(text, false)
        noticeUndo = true
    }

    private var seenKeys by mutableStateOf(prefs.get("visto", "").split(',').filter { it.isNotEmpty() }.toSet())
    override fun hint(key: String): Boolean = key !in seenKeys
    override fun seen(key: String) {
        if (key in seenKeys) return
        seenKeys = seenKeys + key
        prefs.put("visto", seenKeys.joinToString(","))
    }

    override var transitionBars by mutableStateOf(prefs.getInt("transicao", 0).takeIf { it > 0 })
        private set

    override fun nextTransitionBars() {
        transitionBars = when (transitionBars) {
            null -> 16
            16 -> 32
            32 -> 64
            else -> null
        }
        prefs.putInt("transicao", transitionBars ?: 0)
    }

    /** Só a biblioteca local grava sozinha; o pendrive espera o usuário aplicar a fila (com cópia de segurança). */
    override fun saveNow(progress: (String) -> Unit): Boolean {
        if (root.absoluteFile != localLibraryRoot().absoluteFile) return false
        model.apply(null, progress) ?: throw IllegalStateException(model.banner?.text ?: "Não consegui salvar")
        return true
    }

    override var setAsList by mutableStateOf(prefs.getBoolean("setLista", false))
        private set

    override fun setListView(on: Boolean) {
        setAsList = on
        prefs.putBoolean("setLista", on)
    }

    /** O trabalho longo em andamento (montar a prévia, o set), mostrado embaixo. */
    var job by mutableStateOf<DesktopJob?>(null)
        private set

    override fun background(title: String, block: (StudioJob) -> Unit) {
        if (job != null) return
        val j = DesktopJob(title)
        job = j
        Thread({
            try {
                block(j)
            } catch (e: Exception) {
                onMain { notice = Banner(os.deckbridge.manager.studio.friendlyError(e), true) }
            } finally {
                onMain { if (job === j) job = null }
            }
        }, "deckbridge-set").apply { isDaemon = true }.start()
    }

    override fun onMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)

    override var triage by mutableStateOf<List<Int>?>(null)
    override var triageAt by mutableStateOf(0)

    /** Marcar uma por vez (as que faltam: sem nota, sem cor), como no celular. */
    fun startTriage() {
        val v = model.view ?: return
        val ids = os.deckbridge.manager.smart.Smart.triageQueue(v)
        if (ids.isEmpty()) return did(if (v.tracks.isEmpty()) "Importe músicas primeiro" else "Tudo marcado ✓")
        triage = ids
        triageAt = 0
    }

    override fun endTriage() {
        triage = null
        preview.stop()
    }

    /** O último texto entregue (para conferir): no computador vai para a área de transferência. */
    var shared: String? = null
        private set

    override fun share(title: String, text: String) {
        shared = text
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
            .onSuccess { did("$title: copiada") }
            .onFailure { notice = Banner("Não consegui copiar: ${it.message ?: it.javaClass.simpleName}", true) }
    }

    // Mouse: arrastar um cartão começa direto (a rolagem é pela roda).
    override val touch: Boolean get() = false

    /** Os "voltar" das telas abertas, a última por cima: o Esc chama a do topo. */
    internal val backs = mutableListOf<() -> Unit>()

    /** Esc: volta na tela de set de cima. Falso se nenhuma está aberta. */
    fun back(): Boolean {
        val top = backs.lastOrNull() ?: return false
        top()
        return true
    }
}

class DesktopJob(val title: String) : StudioJob {
    override var text by mutableStateOf("")
    @Volatile override var cancelled = false
}

/**
 * Pré-escuta no computador: a faixa (decodificada pelo mesmo caminho da análise: WAV aqui, o resto pelo ffmpeg) ou uma
 * prévia montada (WAV), pela saída de som padrão. Uma de cada vez; numa thread própria.
 */
class DesktopPreview(private val host: DesktopStudio) : StudioPreview {
    override var playingId by mutableStateOf<Int?>(null)
        private set
    override var playingKey by mutableStateOf<String?>(null)
        private set

    private class Session(val load: (Int) -> AudioInputStream?, val startMs: Int) {
        @Volatile var line: SourceDataLine? = null
        @Volatile var stopped = false
    }

    @Volatile private var session: Session? = null

    override fun positionMs(): Int {
        live?.let { return it.heardMs() }
        val s = session ?: return 0
        return s.startMs + ((s.line?.microsecondPosition ?: 0L) / 1000).toInt()
    }

    /** A prévia do MIX ao vivo: a fonte mixa na hora (com a mão do DJ) e a linha de som toca com buffer curto (~80 ms). */
    private inner class Live(val source: os.deckbridge.manager.mix.PcmSource) : Thread("deckbridge-mix-ao-vivo") {
        @Volatile var stopped = false
        @Volatile var pendingSeek = -1
        @Volatile var line: SourceDataLine? = null

        fun heardMs(): Int {
            pendingSeek.let { if (it >= 0) return it }
            val l = line ?: return source.renderedMs
            val queued = ((l.bufferSize - l.available()) / 4).coerceAtLeast(0)
            return (source.renderedMs - queued * 1000L / source.rate).toInt().coerceAtLeast(0)
        }

        override fun run() {
            val n = 512
            val lb = FloatArray(n)
            val rb = FloatArray(n)
            val bytes = ByteArray(n * 4)
            try {
                val format = AudioFormat(source.rate.toFloat(), 16, 2, true, false)
                val l = AudioSystem.getSourceDataLine(format)
                l.open(format, source.rate * 4 * 80 / 1000)
                line = l
                l.start()
                while (!stopped) {
                    val seek = pendingSeek
                    if (seek >= 0) {
                        source.seek(seek)
                        l.flush()
                        pendingSeek = -1
                    }
                    val got = source.read(lb, rb, n)
                    if (got <= 0) break
                    for (i in 0 until got) {
                        val a = (lb[i].coerceIn(-1f, 1f) * 32767).toInt()
                        val b = (rb[i].coerceIn(-1f, 1f) * 32767).toInt()
                        bytes[i * 4] = a.toByte(); bytes[i * 4 + 1] = (a shr 8).toByte()
                        bytes[i * 4 + 2] = b.toByte(); bytes[i * 4 + 3] = (b shr 8).toByte()
                    }
                    l.write(bytes, 0, got * 4)
                }
                if (!stopped) l.drain()
                l.close()
                host.onMain { if (live === this) stop() }
            } catch (e: Exception) {
                host.onMain {
                    if (live === this) {
                        stop()
                        host.notice = Banner("Não consegui tocar a prévia: ${e.message ?: e.javaClass.simpleName}", true)
                    }
                }
            }
        }

        fun halt() {
            stopped = true
            line?.let { runCatching { it.stop() }; runCatching { it.flush() }; runCatching { it.close() } }
        }
    }

    @Volatile private var live: Live? = null

    override fun playStream(source: os.deckbridge.manager.mix.PcmSource, key: String, title: String) {
        if (playingKey == key) return stop()
        stop()
        playingKey = key
        live = Live(source).apply { isDaemon = true; start() }
    }

    override fun toggle(t: TrackItem, fromMs: Int) {
        if (playingId == t.id && fromMs == 0) return stop()
        stop()
        val file = File(host.root, t.filePath.replace('\\', '/').trimStart('/'))
        if (!file.isFile) {
            host.notice = Banner("O áudio de \"${t.title}\" não está nesta biblioteca.", true)
            return
        }
        playingId = t.id
        start(fromMs, t.title) { from -> AudioIo.segment(file, from, TRACK_MS)?.let { pcm(it) } }
    }

    override fun toggleFile(file: File, key: String, title: String, fromMs: Int) {
        if (playingKey == key) return stop()
        stop()
        playingKey = key
        start(fromMs, title) { from ->
            val s = AudioSystem.getAudioInputStream(file)
            val skip = from.toLong() * s.format.frameRate.toLong() / 1000 * s.format.frameSize
            s.skip(skip)
            s
        }
    }

    override fun seekTo(ms: Int) {
        live?.let { it.pendingSeek = ms; return }
        val s = session ?: return
        val id = playingId
        val key = playingKey
        halt(s)
        playingId = id
        playingKey = key
        start(ms, "") { from -> s.load(from) }
    }

    override fun stop() {
        live?.halt()
        live = null
        session?.let { halt(it) }
        session = null
        playingId = null
        playingKey = null
    }

    private fun halt(s: Session) {
        s.stopped = true
        s.line?.let { runCatching { it.stop() }; runCatching { it.flush() }; runCatching { it.close() } }
    }

    private fun start(fromMs: Int, title: String, load: (Int) -> AudioInputStream?) {
        val s = Session(load, fromMs)
        session = s
        Thread({
            try {
                val input = load(fromMs) ?: throw IllegalStateException("sem decodificador para este arquivo")
                input.use {
                    val line = AudioSystem.getSourceDataLine(input.format)
                    line.open(input.format)
                    s.line = line
                    line.start()
                    val buf = ByteArray(16 * 1024)
                    while (!s.stopped) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        line.write(buf, 0, n)
                    }
                    if (!s.stopped) line.drain()
                    line.close()
                }
                host.onMain { if (session === s) stop() }
            } catch (e: Exception) {
                host.onMain {
                    if (session === s) {
                        stop()
                        host.notice = Banner("Não consegui tocar${if (title.isNotEmpty()) " \"$title\"" else ""}: ${e.message ?: e.javaClass.simpleName}", true)
                    }
                }
            }
        }, "deckbridge-ouvir").apply { isDaemon = true }.start()
    }

    private fun pcm(p: os.deckbridge.logic.DecodedPcm): AudioInputStream {
        val out = ByteArray(p.frames * 4)
        for (i in 0 until p.frames) {
            val l = (p.left[i].coerceIn(-1f, 1f) * 32767).toInt()
            val r = (p.right[i].coerceIn(-1f, 1f) * 32767).toInt()
            out[i * 4] = l.toByte(); out[i * 4 + 1] = (l shr 8).toByte()
            out[i * 4 + 2] = r.toByte(); out[i * 4 + 3] = (r shr 8).toByte()
        }
        val format = AudioFormat(p.sampleRate.toFloat(), 16, 2, true, false)
        return AudioInputStream(ByteArrayInputStream(out), format, p.frames.toLong())
    }

    private companion object {
        /** Quanto da faixa decodificar de uma vez para ouvir (o resto, ao pular para lá). */
        const val TRACK_MS = 6 * 60_000
    }
}

/**
 * As telas de set por cima da janela (as mesmas do celular), quando uma está aberta: o SET, o mapa e o MIX, com o aviso e
 * o andamento do trabalho em segundo plano embaixo.
 */
@Composable
fun StudioOverlay(studio: DesktopStudio) {
    if (studio.sets.playlistId == null && studio.triage == null) return
    ManagerTheme {
        CompositionLocalProvider(LocalBack provides { onBack ->
            val current by rememberUpdatedState(onBack)
            DisposableEffect(Unit) {
                val h: () -> Unit = { current() }
                studio.backs += h
                onDispose { studio.backs -= h }
            }
        }) {
            Box(Modifier.fillMaxSize().background(Palette.bg)) {
                if (studio.sets.playlistId != null) SetScreen(studio)
                if (studio.sets.playlistId != null && studio.sets.mapOpen) SetMapScreen(studio)
                if (studio.sets.mixing != null) MixScreen(studio)
                if (studio.triage != null) {
                    BackHandlerFor(studio) { studio.endTriage() }
                    os.deckbridge.manager.studio.TriageScreen(studio)
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Trabalho longo: com "n/N" no texto, a porcentagem e a barra cheia até ali (Myers 1985).
                    studio.job?.let { j ->
                        val f = os.deckbridge.manager.studio.jobFraction(j.text)
                        StudioToast("${j.title}${if (j.text.isNotEmpty()) " · ${j.text}" else ""}${f?.let { " · ${(it * 100).toInt()}%" } ?: "…"}", false, fraction = f)
                    }
                    studio.notice?.let { n ->
                        val undo = studio.noticeUndo && studio.model.dirty
                        LaunchedEffect(n) {
                            if (!n.error) {
                                // Com DESFAZER fica mais tempo, para dar tempo de usar.
                                kotlinx.coroutines.delay(if (studio.noticeUndo) 8000 else 3500)
                                if (studio.notice === n) studio.notice = null
                            }
                        }
                        StudioToast(n.text, n.error, onUndo = if (undo) ({ studio.model.undo(); studio.notice = null }) else null) { studio.notice = null }
                    }
                }
            }
        }
    }
}

/** A triagem não tem "voltar" próprio (no celular é o ✕): no computador, Esc fecha. */
@Composable
private fun BackHandlerFor(studio: DesktopStudio, onBack: () -> Unit) = os.deckbridge.manager.studio.BackHandler(onBack)

@Composable
private fun StudioToast(text: String, error: Boolean, fraction: Float? = null, onUndo: (() -> Unit)? = null, onClose: (() -> Unit)? = null) {
    Column(
        Modifier.padding(top = 6.dp).widthIn(max = 640.dp).clip(RbShape).background(if (error) Palette.dangerBg else Palette.surfaceHigh)
            .border(BorderStroke(1.dp, if (error) Palette.danger else Palette.line), RbShape),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            if (onUndo != null) os.deckbridge.manager.studio.TextButton(onClick = onUndo) { Text("DESFAZER  Ctrl+Z", color = Palette.orange, fontSize = 12.sp) }
            if (onClose != null && error) os.deckbridge.manager.studio.TextButton(onClick = onClose) { Text("OK", fontSize = 12.sp) }
        }
        if (fraction != null) androidx.compose.material3.LinearProgressIndicator(
            progress = { fraction }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Palette.blue, trackColor = Palette.line,
        )
    }
}
