package os.deckbridge.android

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import os.deckbridge.logic.DecodedPcm
import os.deckbridge.logic.HistoryRow
import os.deckbridge.logic.PlayerSettings
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.ReadableVolume
import java.io.File

/**
 * O que o modo DJ pede da plataforma (ADR-0012): o celular (Android) e o computador implementam; as telas de DJ e a
 * lógica dos decks (`DeckPage`) são as mesmas nos dois. O pacote continua `os.deckbridge.android` porque as funções
 * nativas do motor (JNI) têm esse nome.
 *
 * Nada aqui escreve na biblioteca nem no pendrive: só em pastas do próprio app ([filesDir], [cacheDir]) e onde o
 * usuário escolher ao exportar o histórico (o que recusa o pendrive da biblioteca).
 */
interface DjHost {
    /** Como a tela chama o aparelho quando o som sai dele mesmo (sem placa USB): "TABLET", "COMPUTADOR". */
    val deviceName: String get() = "TABLET"

    /** Gravações e arquivos do app. */
    val filesDir: File

    /** Miniaturas e caches. */
    val cacheDir: File

    /** A faixa inteira para o deck (WAV, MP3, FLAC, AAC), ou null se não souber ler. */
    fun decodeAudio(name: String, bytes: ByteArray): DecodedPcm?

    /** Só um trecho, para a pré-escuta. */
    fun decodeSegment(name: String, bytes: ByteArray, startSec: Double, seconds: Double): DecodedPcm?

    /** Grava o master em AAC ao lado do WAV, se a plataforma souber. Devolve como parar; null se não houver AAC. */
    fun startAac(file: File, sampleRate: Int, pull: () -> FloatArray): (() -> Unit)? = null

    fun settings(): Flow<PlayerSettings>

    suspend fun saveSettings(settings: PlayerSettings)

    /** O que está nos decks (para reabrir no mesmo ponto se o app cair). */
    fun saveSession(decks: List<SessionDeck?>)

    fun readSession(): List<SessionDeck?>

    /** O histórico do que tocou (sessões de set). */
    val history: HistoryStore

    /** Miniatura da capa (só lê o volume). */
    fun artwork(volume: ReadableVolume, track: RbTrack): ByteArray?

    /** Um jeito de salvar um texto (histórico em CSV/M3U) onde o usuário escolher; o nome é a sugestão. */
    @Composable
    fun rememberTextExport(onNote: (String) -> Unit): (suggestedName: String, text: String) -> Unit
}

data class SessionDeck(val path: String, val position: Float, val pitch: Float)

interface HistoryStore {
    fun session(now: Long): Long

    fun insert(sessionId: Long, row: HistoryRow)

    fun rows(): List<HistoryRow>
}

/** Limite de uma faixa na memória: 15 minutos (cerca de 330 MB de decodificação estéreo a 48 kHz). */
const val MAX_TRACK_MINUTES = 15

/** Duração do trecho da pré-escuta: toca daí e para sozinho. */
const val PREVIEW_SECONDS = 30.0

/**
 * O botão do MENU que leva ao outro modo do app (rótulo e ação), dado pela plataforma: no celular abre a Biblioteca e
 * encerra o processo do DJ; no computador reabre o programa no modo Biblioteca. Null: sem botão (testes, Pi).
 */
val LocalModeSwitch = androidx.compose.runtime.staticCompositionLocalOf<Pair<String, () -> Unit>?> { null }
