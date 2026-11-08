package os.deckbridge.dj

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import os.deckbridge.android.DjHost
import os.deckbridge.android.HistoryStore
import os.deckbridge.android.SessionDeck
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.logic.HistoryRow
import os.deckbridge.logic.PlayerSettings
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.ReadableVolume
import java.io.File
import java.util.Properties

/**
 * O modo DJ no computador (ADR-0012): pastas do app em [home] (por padrão `~/.deckbridge/dj`), áudio pelo ffmpeg,
 * ajustes e sessão em arquivos de propriedades, histórico num CSV. Nada escreve na biblioteca nem no pendrive.
 */
class DesktopDjHost(val home: File = File(System.getProperty("user.home"), ".deckbridge/dj")) : DjHost {
    override val deviceName = "COMPUTADOR"
    override val filesDir: File = home.apply { mkdirs() }
    override val cacheDir: File = File(home, "cache").apply { mkdirs() }

    override fun decodeAudio(name: String, bytes: ByteArray): DecodedPcm? = DesktopAudio.decode(name, bytes)

    override fun decodeSegment(name: String, bytes: ByteArray, startSec: Double, seconds: Double): DecodedPcm? =
        DesktopAudio.decode(name, bytes, startSec, seconds)

    private val settingsFile = File(home, "ajustes.properties")
    private val settingsState = MutableStateFlow(readSettings())

    override fun settings(): Flow<PlayerSettings> = settingsState

    override suspend fun saveSettings(settings: PlayerSettings) {
        settingsState.value = settings
        val props = Properties()
        with(settings) {
            mapOf(
                "tempoRange" to tempoRange, "keyLock" to keyLock, "quantize" to quantize, "quantizeBeats" to quantizeBeats,
                "autoCue" to autoCue, "hotCueAutoLoad" to hotCueAutoLoad, "jogVinyl" to jogVinyl, "masterLevel" to masterLevel,
                "waveColor" to waveColor, "followUsb" to followUsb, "zoomBars" to zoomBars, "playhead" to playhead,
                "bufferFrames" to bufferFrames, "encoderFlip" to encoderFlip, "jogSensitivity" to jogSensitivity,
                "historySeconds" to historySeconds, "waveView" to waveView, "autoGain" to autoGain, "targetLufs" to targetLufs,
                "gentleSuggest" to gentleSuggest, "cuePreset" to cuePreset, "colorFx" to colorFx, "loadLock" to loadLock,
                "chFaderCurve" to chFaderCurve, "crossCurve" to crossCurve,
            ).forEach { (key, value) -> props.setProperty(key, value.toString()) }
        }
        atomicWrite(settingsFile) { out -> props.store(out, "DeckBridge DJ") }
    }

    private fun readSettings(): PlayerSettings {
        if (!settingsFile.isFile) return PlayerSettings()
        val props = Properties().apply { runCatching { settingsFile.inputStream().use { load(it) } } }
        val d = PlayerSettings()
        fun b(key: String, default: Boolean) = props.getProperty(key)?.toBooleanStrictOrNull() ?: default
        fun i(key: String, default: Int) = props.getProperty(key)?.toIntOrNull() ?: default
        fun f(key: String, default: Float) = props.getProperty(key)?.toFloatOrNull() ?: default
        fun x(key: String, default: Double) = props.getProperty(key)?.toDoubleOrNull() ?: default
        fun t(key: String, default: String) = props.getProperty(key) ?: default
        // O AAC fica desligado no computador: a gravação sai em WAV (a codificação AAC é do Android).
        return PlayerSettings(
            tempoRange = x("tempoRange", d.tempoRange), keyLock = b("keyLock", d.keyLock), quantize = b("quantize", d.quantize),
            quantizeBeats = x("quantizeBeats", d.quantizeBeats), autoCue = b("autoCue", d.autoCue),
            hotCueAutoLoad = b("hotCueAutoLoad", d.hotCueAutoLoad), jogVinyl = b("jogVinyl", d.jogVinyl),
            masterLevel = f("masterLevel", d.masterLevel), waveColor = t("waveColor", d.waveColor), followUsb = b("followUsb", d.followUsb),
            zoomBars = i("zoomBars", d.zoomBars), playhead = f("playhead", d.playhead), bufferFrames = i("bufferFrames", d.bufferFrames),
            encoderFlip = b("encoderFlip", d.encoderFlip), jogSensitivity = f("jogSensitivity", d.jogSensitivity),
            historySeconds = i("historySeconds", d.historySeconds), recordAac = false, waveView = t("waveView", d.waveView),
            autoGain = b("autoGain", d.autoGain), targetLufs = x("targetLufs", d.targetLufs), gentleSuggest = b("gentleSuggest", d.gentleSuggest),
            cuePreset = t("cuePreset", d.cuePreset), colorFx = i("colorFx", d.colorFx).coerceIn(0, 5), loadLock = b("loadLock", d.loadLock),
            chFaderCurve = i("chFaderCurve", d.chFaderCurve).coerceIn(0, 2), crossCurve = i("crossCurve", d.crossCurve).coerceIn(0, 2),
        )
    }

    private val sessionFile = File(home, "sessao.properties")

    override fun saveSession(decks: List<SessionDeck?>) {
        val props = Properties()
        decks.forEachIndexed { index, deck ->
            if (deck != null) {
                props.setProperty("path$index", deck.path)
                props.setProperty("position$index", deck.position.toString())
                props.setProperty("pitch$index", deck.pitch.toString())
            }
        }
        atomicWrite(sessionFile) { out -> props.store(out, null) }
    }

    override fun readSession(): List<SessionDeck?> {
        val props = Properties().apply { if (sessionFile.isFile) runCatching { sessionFile.inputStream().use { load(it) } } }
        return (0..1).map { index ->
            val path = props.getProperty("path$index") ?: return@map null
            SessionDeck(path, props.getProperty("position$index")?.toFloatOrNull() ?: 0f, props.getProperty("pitch$index")?.toFloatOrNull() ?: 1f)
        }
    }

    override val history: HistoryStore = CsvHistory(File(home, "historico.csv"))

    override fun artwork(volume: ReadableVolume, track: RbTrack): ByteArray? {
        if (track.artworkPath.isBlank()) return null
        // Sem reduzir: o computador tem memória; a capa original é pequena (o rekordbox guarda 80 px e 240 px).
        return os.deckbridge.logic.readArtworkFile(volume, track.artworkPath)
    }

    @Composable
    override fun rememberTextExport(onNote: (String) -> Unit): (String, String) -> Unit = remember {
        { name, text ->
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Exportar histórico", java.awt.FileDialog.SAVE)
            dialog.file = name
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) {
                val target = File(dir, file)
                if (File(target.parentFile, "PIONEER/rekordbox/export.pdb").isFile || target.path.contains("/PIONEER/")) {
                    onNote("Exportação recusada: o destino é um pendrive do rekordbox (só leitura). Escolha outro lugar.")
                } else {
                    target.writeText(text, Charsets.UTF_8)
                    onNote("Histórico salvo em ${target.path}.")
                }
            }
        }
    }

    private fun atomicWrite(file: File, write: (java.io.OutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use(write)
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}

/** Histórico num CSV local, uma linha por faixa que esteve no ar. */
class CsvHistory(private val file: File) : HistoryStore {
    override fun session(now: Long): Long = now

    @Synchronized
    override fun insert(sessionId: Long, row: HistoryRow) {
        file.parentFile?.mkdirs()
        val fields = listOf(sessionId, row.atMillis, row.deck, row.title, row.artist, row.bpm, row.playedBpm, row.keyName, row.seconds, row.trackId)
        file.appendText(fields.joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') } + "\n")
    }

    @Synchronized
    override fun rows(): List<HistoryRow> {
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 10) return@mapNotNull null
            runCatching { HistoryRow(p[1].toLong(), p[2].toInt(), p[3], p[4], p[5].toDouble(), p[6].toDouble(), p[7], p[8].toInt(), p[9].toInt()) }.getOrNull()
        }.reversed() // o mais recente primeiro, como no celular
    }
}
