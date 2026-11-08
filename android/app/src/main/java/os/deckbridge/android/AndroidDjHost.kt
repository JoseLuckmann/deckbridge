package os.deckbridge.android

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.logic.HistoryRow
import os.deckbridge.logic.PlayerSettings
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.ReadableVolume
import java.io.File

/** O modo DJ no Android: MediaCodec, DataStore, SQLite e o seletor de documentos (ADR-0012). */
class AndroidDjHost(private val context: Context) : DjHost {
    override val filesDir: File get() = context.filesDir
    override val cacheDir: File get() = context.cacheDir

    override fun decodeAudio(name: String, bytes: ByteArray): DecodedPcm? = decodeTrackAudio(context, name, bytes)

    override fun decodeSegment(name: String, bytes: ByteArray, startSec: Double, seconds: Double): DecodedPcm? =
        decodeTrackSegment(context, name, bytes, startSec, seconds)

    override fun startAac(file: File, sampleRate: Int, pull: () -> FloatArray): (() -> Unit) {
        val writer = AacWriter(file, sampleRate, pull)
        writer.start()
        return { writer.stop() }
    }

    override fun settings(): Flow<PlayerSettings> = SettingsStore.flow(context)

    override suspend fun saveSettings(settings: PlayerSettings) = SettingsStore.save(context, settings)

    override fun saveSession(decks: List<SessionDeck?>) =
        SessionStore.save(context, decks.map { deck -> deck?.let { SessionStore.Deck(it.path, it.position, it.pitch) } })

    override fun readSession(): List<SessionDeck?> = SessionStore.read(context).map { deck -> deck?.let { SessionDeck(it.path, it.position, it.pitch) } }

    override val history: HistoryStore by lazy {
        val db = HistoryDb(context)
        object : HistoryStore {
            override fun session(now: Long) = db.session(now)
            override fun insert(sessionId: Long, row: HistoryRow) = db.insert(sessionId, row)
            override fun rows() = db.rows()
        }
    }

    override fun artwork(volume: ReadableVolume, track: RbTrack): ByteArray? = ArtworkStore.thumb(context.cacheDir, volume, track)

    @Composable
    override fun rememberTextExport(onNote: (String) -> Unit): (String, String) -> Unit {
        val pending = remember { arrayOf<String?>(null) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val text = pending[0]
            if (uri != null && text != null) {
                if (exportTouchesLibraryVolume(context, uri)) {
                    // O seletor já criou o arquivo vazio: remove para o pendrive ficar como estava e não grava nada nele.
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    onNote("Exportação recusada: o destino é o pendrive da biblioteca (só leitura). Escolha outro lugar.")
                } else {
                    context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }
        }
        return remember(launcher) {
            { name, text ->
                pending[0] = text
                launcher.launch(name)
            }
        }
    }
}
