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

    /** Triagem: as faixas a marcar, uma por vez, e em qual está. */
    var triage: List<Int>?
    var triageAt: Int
    fun endTriage()

    /** Entrega um texto para fora do app (celular: compartilhar; computador: área de transferência). */
    fun share(title: String, text: String)

    /** Tela de toque (arrastar um cartão começa com um toque longo, para não brigar com a rolagem); no mouse, arrasta direto. */
    val touch: Boolean get() = true

    /**
     * O sistema pede menos movimento (no Android, "remover animações"): as telas trocam sem deslizar. Quem é sensível a
     * movimento (labirintite, enxaqueca) passa mal com animação que não pediu (WCAG 2.3.3).
     */
    val reduceMotion: Boolean get() = false

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

    /** O que se ouve agora (ms): numa fonte ao vivo, o que ela já fez menos o que ainda está no buffer do som. */
    fun positionMs(): Int
    fun seekTo(ms: Int)

    /**
     * Toca uma fonte ao vivo ([source] mixa na hora: a prévia do MIX com a mão do DJ). Tocar a mesma chave de novo para.
     * Pula com [seekTo], como um arquivo.
     */
    fun playStream(source: os.deckbridge.manager.mix.PcmSource, key: String, title: String)
}

/** Como cada app trata "voltar" (o botão do Android, ou Esc no computador). */
val LocalBack = staticCompositionLocalOf<@Composable (onBack: () -> Unit) -> Unit> { {} }

@Composable
fun BackHandler(onBack: () -> Unit) = LocalBack.current(onBack)

/**
 * Quanto do trabalho já foi, a partir do "n/N" que o texto dele mostra ("3/10: Night Signal" → 0,3). Null quando o texto
 * não diz: aí a barra fica correndo sem fim. Com a fração, a barra enche e mostra a porcentagem (Myers 1985).
 */
fun jobFraction(text: String): Float? {
    val m = Regex("(\\d+)\\s*/\\s*(\\d+)").find(text) ?: return null
    val total = m.groupValues[2].toInt()
    if (total <= 0) return null
    return (m.groupValues[1].toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Um erro dito em palavras do DJ, com o que fazer (Nielsen, heurística 9: ajudar a reconhecer, entender e sair do erro).
 * Em vez de "Falhou: ENOSPC" ou o nome da exceção, diz a causa provável e o próximo passo.
 */
fun friendlyError(e: Throwable): String {
    val msg = e.message.orEmpty()
    val low = msg.lowercase()
    return when {
        e is java.io.FileNotFoundException || "no such file" in low || "enoent" in low ->
            "Não achei um arquivo${fileOf(msg)}. Ele foi movido ou apagado? Confira a pasta, ou importe a música de novo."
        "no space" in low || "enospc" in low || "disk full" in low ->
            "Acabou o espaço. Libere espaço no aparelho ou no pendrive e tente de novo; nada foi perdido."
        e is SecurityException || "permission" in low || "eacces" in low || "read-only" in low ->
            "Sem permissão para gravar ali. No pendrive, confira se ele não está travado e escolha a pasta de novo."
        e is OutOfMemoryError -> "Faltou memória para essa faixa. Feche outros apps e tente de novo."
        "unsupported" in low || "unknown format" in low || "invalid format" in low ->
            "Esse formato de áudio não deu para ler. Converta para MP3, WAV, AIFF ou FLAC e importe de novo."
        msg.isNotBlank() -> "Não deu certo: $msg"
        else -> "Não deu certo (${e.javaClass.simpleName}). Tente de novo; se repetir, reinicie o app."
    }
}

private fun fileOf(msg: String): String =
    Regex("""([^/\\(:]+?\.(mp3|wav|aiff?|flac|m4a|ogg|dat|ext|pdb))\b""", RegexOption.IGNORE_CASE).find(msg)?.value?.trim()?.let { ": $it" } ?: ""
