package os.deckbridge.manager.volume

import java.io.File

/** Operação de arquivo no volume. Caminhos são relativos à raiz do pendrive, com `/`. */
sealed interface FileOp {
    /** Cria ou substitui o arquivo com [bytes]. */
    data class Put(val rel: String, val bytes: ByteArray) : FileOp

    /** Copia um arquivo de fora do pendrive para dentro dele. O destino não pode existir. */
    data class CopyIn(val rel: String, val source: File) : FileOp

    /** Renomeia ou move um arquivo dentro do pendrive. O destino não pode existir. */
    data class Move(val from: String, val to: String) : FileOp
}

val AUDIO_EXTENSIONS = setOf("wav", "mp3", "flac", "aif", "aiff", "m4a", "aac", "alac", "ogg")

fun isAudio(rel: String) = rel.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

/**
 * O que as edições fazem nos arquivos, ainda em memória. Leituras veem o efeito das edições anteriores
 * (um ANLZ já editado, um arquivo já renomeado), então as edições se compõem sem tocar no disco.
 */
class FileOverlay(private val root: File?) {
    private val puts = LinkedHashMap<String, ByteArray>()
    private val copies = LinkedHashMap<String, File>()
    private val moves = ArrayList<Pair<String, String>>()

    /** Caminho como está no disco (a caixa das letras pode diferir da do banco, em FAT). Null se não existe. */
    fun onDisk(rel: String): String? {
        val r = root ?: return null
        val parts = rel.trim('/').split('/').filter { it.isNotEmpty() }
        var dir = r
        val real = ArrayList<String>()
        for (p in parts) {
            val exact = File(dir, p)
            val name = if (exact.exists()) p else dir.list()?.firstOrNull { it.equals(p, ignoreCase = true) } ?: return null
            real += name
            dir = File(dir, name)
        }
        return real.joinToString("/")
    }

    private fun key(rel: String) = rel.trim('/').lowercase()

    fun exists(rel: String): Boolean {
        val k = key(rel)
        if (moves.any { key(it.first) == k }) return false
        if (puts.keys.any { key(it) == k } || copies.keys.any { key(it) == k } || moves.any { key(it.second) == k }) return true
        return onDisk(rel) != null
    }

    /** Conteúdo atual (com as edições). Só para arquivos pequenos (ANLZ, bancos). */
    fun read(rel: String): ByteArray? {
        val k = key(rel)
        puts.entries.firstOrNull { key(it.key) == k }?.let { return it.value }
        if (copies.keys.any { key(it) == k }) return null
        val real = onDisk(rel) ?: return null
        return File(root, real).takeIf { it.isFile }?.readBytes()
    }

    /** Caminho que a gravação usará: o do disco se existir (preserva a caixa), senão o pedido. */
    fun target(rel: String): String = puts.keys.firstOrNull { key(it) == key(rel) } ?: onDisk(rel) ?: rel.trim('/')

    fun put(rel: String, bytes: ByteArray) {
        val t = target(rel)
        puts[t] = bytes
    }

    fun copyIn(rel: String, source: File) {
        copies[rel.trim('/')] = source
    }

    fun move(from: String, to: String) {
        val f = key(from)
        val dest = to.trim('/')
        val copied = copies.keys.firstOrNull { key(it) == f }
        val chained = moves.indexOfFirst { key(it.second) == f }
        when {
            // Arquivo que ainda vai ser copiado para dentro, ou já renomeado nesta sessão: só muda o destino final.
            copied != null -> copies[dest] = copies.remove(copied)!!
            chained >= 0 -> moves[chained] = moves[chained].first to dest
            else -> moves += (onDisk(from) ?: from.trim('/')) to dest
        }
        puts.keys.firstOrNull { key(it) == f }?.let { puts[dest] = puts.remove(it)!! }
    }

    val isEmpty get() = puts.isEmpty() && copies.isEmpty() && moves.isEmpty()

    /** Moves, depois cópias, depois gravações: o banco vai por último, no [SafeWriter]. */
    fun ops(): List<FileOp> = moves.map { FileOp.Move(it.first, it.second) } + copies.map { FileOp.CopyIn(it.key, it.value) } + puts.map { FileOp.Put(it.key, it.value) }
}
