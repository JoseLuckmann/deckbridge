package os.deckbridge.manager.mobile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream
import os.deckbridge.manager.transfer.Storage
import os.deckbridge.manager.transfer.StorageException

/**
 * Um pendrive (ou qualquer pasta) escolhido no seletor de documentos do Android. O Android não dá caminhos de arquivo
 * para pendrives, só endereços de documento; esta classe traduz os caminhos relativos do exportador para eles.
 *
 * A troca de um arquivo existente não é atômica no SAF (não dá para renomear por cima). A ordem é sempre: gravar o novo
 * ao lado, conferir o tamanho, renomear o antigo para um nome de reserva, renomear o novo e só então apagar o reserva.
 * Se qualquer passo falha depois de o antigo ter sido movido, o antigo volta ao nome original.
 */
class SafStorage(private val context: Context, private val tree: Uri, override val label: String) : Storage {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, tree) ?: throw StorageException("E_SAF_ROOT", "Não consegui abrir a pasta escolhida.")

    /** Filhos de cada pasta, por nome em minúsculas (FAT não diferencia caixa). Invalidado a cada gravação. */
    private val cache = HashMap<String, Map<String, DocumentFile>>()

    private fun children(dir: DocumentFile, key: String): Map<String, DocumentFile> =
        cache.getOrPut(key) { dir.listFiles().mapNotNull { f -> f.name?.let { it.lowercase() to f } }.toMap() }

    private fun parts(rel: String) = rel.trim('/').split('/').filter { it.isNotEmpty() }

    private fun find(rel: String): DocumentFile? {
        var dir = root
        var key = ""
        val p = parts(rel)
        for ((n, name) in p.withIndex()) {
            val next = children(dir, key)[name.lowercase()] ?: return null
            if (n == p.lastIndex) return next
            if (!next.isDirectory) return null
            dir = next
            key = "$key/${name.lowercase()}"
        }
        return root
    }

    private fun ensureDir(dirParts: List<String>): DocumentFile {
        var dir = root
        var key = ""
        for (name in dirParts) {
            val existing = children(dir, key)[name.lowercase()]
            dir = if (existing != null) {
                if (!existing.isDirectory) throw StorageException("E_SAF_NOT_DIR", "$name existe no pendrive e não é uma pasta.")
                existing
            } else {
                val made = dir.createDirectory(name) ?: throw StorageException("E_SAF_MKDIR", "Não consegui criar a pasta $name no pendrive.")
                cache.remove(key)
                made
            }
            key = "$key/${name.lowercase()}"
        }
        return dir
    }

    override fun length(rel: String): Long = find(rel)?.takeIf { it.isFile }?.length() ?: -1

    override fun read(rel: String): ByteArray? = open(rel)?.use { it.readBytes() }

    override fun open(rel: String): InputStream? {
        val f = find(rel)?.takeIf { it.isFile } ?: return null
        return context.contentResolver.openInputStream(f.uri)?.buffered()
    }

    override fun write(rel: String, source: InputStream, length: Long) {
        val p = parts(rel)
        val name = p.last()
        val dirKey = p.dropLast(1).joinToString("") { "/${it.lowercase()}" }
        val dir = ensureDir(p.dropLast(1))
        val tempName = "$name.deckbridge-novo"
        val oldName = "$name.deckbridge-antigo"
        // Resto de uma tentativa anterior interrompida.
        children(dir, dirKey)[tempName.lowercase()]?.delete()
        cache.remove(dirKey)
        val temp = dir.createFile("application/octet-stream", tempName) ?: throw StorageException("E_SAF_CREATE", "Não consegui criar $tempName no pendrive.")
        try {
            if (temp.name != tempName) throw StorageException("E_SAF_NAME", "O pendrive renomeou $tempName para ${temp.name}.")
            val out = context.contentResolver.openOutputStream(temp.uri, "wt") ?: throw StorageException("E_SAF_OPEN", "Não consegui abrir $tempName para gravar.")
            source.use { input -> out.use { o -> input.copyTo(o); o.flush() } }
            if (temp.length() != length) throw StorageException("E_WRITE_VERIFY", "Gravação incompleta de $rel: ${temp.length()} de $length bytes.")
        } catch (e: Exception) {
            temp.delete()
            cache.remove(dirKey)
            if (e is StorageException) throw e
            throw StorageException("E_SAF_WRITE", "Falha ao gravar $rel no pendrive: ${e.message}")
        }
        cache.remove(dirKey)
        val existing = children(dir, dirKey)[name.lowercase()]
        var moved: DocumentFile? = null
        try {
            if (existing != null) {
                children(dir, dirKey)[oldName.lowercase()]?.delete()
                if (!existing.renameTo(oldName)) throw StorageException("E_SAF_RENAME", "O pendrive não deixou renomear $name.")
                moved = existing
            }
            if (!temp.renameTo(name)) throw StorageException("E_SAF_RENAME", "O pendrive não deixou renomear $tempName para $name.")
        } catch (e: Exception) {
            // Devolve o antigo ao lugar e descarta o novo.
            runCatching { moved?.renameTo(name) }
            runCatching { temp.delete() }
            cache.remove(dirKey)
            if (e is StorageException) throw e
            throw StorageException("E_SAF_SWAP", "Falha ao trocar $rel: ${e.message}")
        }
        cache.remove(dirKey)
        moved?.delete()
        cache.remove(dirKey)
    }

    /** O seletor de documentos não informa o espaço livre de um pendrive; o exportador segue e trata a falha de gravação. */
    override fun freeBytes(): Long? = null

    override fun listFiles(rel: String): List<String> {
        val start = if (parts(rel).isEmpty()) root else find(rel) ?: return emptyList()
        if (!start.isDirectory) return emptyList()
        val out = ArrayList<String>()
        fun walk(dir: DocumentFile, prefix: String) {
            for (f in dir.listFiles()) {
                val n = f.name ?: continue
                if (n.endsWith(".deckbridge-novo") || n.endsWith(".deckbridge-antigo")) continue
                val path = if (prefix.isEmpty()) n else "$prefix/$n"
                if (f.isDirectory) walk(f, path) else out += path
            }
        }
        walk(start, parts(rel).joinToString("/"))
        return out
    }

    companion object {
        /** Nome amigável do pendrive escolhido (o ID do documento da raiz). */
        fun labelOf(tree: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(tree).substringBefore(':') }.getOrDefault("pendrive").ifEmpty { "pendrive" }
    }
}
