package os.deckbridge.manager.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File
import os.deckbridge.manager.Banner
import os.deckbridge.manager.ManagerModel
import os.deckbridge.manager.pdb.TrackItem

/**
 * As telas de montar set (quadro, lista, MIX, mapa) são as mesmas no celular e no computador: este código é compilado pelos
 * dois apps. O que muda de um para o outro (onde guardar, como tocar, como rodar em segundo plano, voltar) vem do app por
 * esta interface.
 */
interface StudioHost {
    val model: ManagerModel

    /** A pasta da biblioteca aberta (onde estão as músicas, como num pendrive). */
    val root: File

    /** Onde ficam os planos dos sets (transições, ramos, master, pool): fora do pendrive. */
    val setsDir: File

    /** Onde ficam as prévias montadas (podem ser apagadas a qualquer hora). */
    val cacheDir: File
    val preview: StudioPreview
    val sets: SetDesk

    /** O aviso de baixo (some sozinho; um erro fica até o OK). */
    var notice: Banner?

    /** Avisa o que uma ação rápida fez (com "Desfazer" quando o app tem). */
    fun did(text: String?)

    /** Destacar o controle [key] para quem é novo (até ser usado uma vez). */
    fun hint(key: String): Boolean
    fun seen(key: String)

    /** Duração das transições automáticas: null é pela estrutura, ou fixa em compassos. */
    val transitionBars: Int?

    /** Passa para a próxima duração: Auto → 16 → 32 → 64 → Auto. */
    fun nextTransitionBars()

    /**
     * Grava agora o que está na fila (fora da thread da tela). Falso quando o app deixa a gravação para o usuário mandar
     * (no computador, com o pendrive aberto: grava só quando ele aplica). Erro de gravação é exceção.
     */
    fun saveNow(progress: (String) -> Unit): Boolean

    /** O SET em lista em vez do quadro. */
    val setAsList: Boolean
    fun setListView(on: Boolean)

    /** Roda [block] fora da thread da tela, com o andamento visível; erros viram aviso. */
    fun background(title: String, block: (StudioJob) -> Unit)

    /** Entrega um texto para fora do app (celular: compartilhar; computador: área de transferência). */
    fun share(title: String, text: String)

    /** Tela de toque (arrastar um cartão começa com um toque longo, para não brigar com a rolagem); no mouse, arrasta direto. */
    val touch: Boolean get() = true

    /** Roda [block] na thread da tela. */
    fun onMain(block: () -> Unit)
}

/** Um trabalho longo em andamento: o texto que a tela mostra e se foi cancelado. */
interface StudioJob {
    var text: String
    val cancelled: Boolean
}

/** A pré-escuta: uma faixa da biblioteca ou uma prévia montada (WAV), uma de cada vez. */
interface StudioPreview {
    val playingId: Int?

    /** A chave da prévia montada tocando agora (a que quem pediu deu). */
    val playingKey: String?
    fun toggle(t: TrackItem, fromMs: Int = 0)
    fun toggleFile(file: File, key: String, title: String, fromMs: Int = 0)
    fun stop()
    fun positionMs(): Int
    fun seekTo(ms: Int)
}

/** Como cada app trata "voltar" (o botão do Android, ou Esc no computador). */
val LocalBack = staticCompositionLocalOf<@Composable (onBack: () -> Unit) -> Unit> { {} }

@Composable
fun BackHandler(onBack: () -> Unit) = LocalBack.current(onBack)
