package os.deckbridge.manager.transfer

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Um lugar onde mora uma árvore `PIONEER/` + `Contents/`: uma pasta do aparelho, um pendrive montado ou, no Android, uma
 * pasta escolhida pelo seletor de documentos. Os caminhos são sempre relativos à raiz, com `/`.
 */
interface Storage {
    val label: String
    fun length(rel: String): Long
    fun exists(rel: String): Boolean = length(rel) >= 0
    fun read(rel: String): ByteArray?
    fun open(rel: String): InputStream?

    /**
     * Grava [rel] sem nunca deixá-lo pela metade: escreve ao lado, confere o tamanho e só então troca pelo antigo.
     * Cria as pastas que faltarem.
     */
    fun write(rel: String, source: InputStream, length: Long)
    fun write(rel: String, bytes: ByteArray) = write(rel, ByteArrayInputStream(bytes), bytes.size.toLong())
    fun freeBytes(): Long?

    /** Caminhos (relativos à raiz) dos arquivos sob [rel], recursivamente. */
    fun listFiles(rel: String): List<String>
}

class StorageException(val code: String, message: String) : Exception(message)

class FileStorage(val root: File, override val label: String = root.name.ifEmpty { root.path }) : Storage {
    private fun file(rel: String): File {
        val clean = rel.trim('/')
        require(clean.split('/').none { it == ".." }) { "caminho fora da raiz: $rel" }
        return File(root, clean)
    }

    override fun length(rel: String): Long = file(rel).let { if (it.isFile) it.length() else -1 }
    override fun read(rel: String): ByteArray? = file(rel).takeIf { it.isFile }?.readBytes()
    override fun open(rel: String): InputStream? = file(rel).takeIf { it.isFile }?.inputStream()?.buffered()

    override fun write(rel: String, source: InputStream, length: Long) {
        val target = file(rel)
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, target.name + ".deckbridge-novo")
        try {
            source.use { input -> temp.outputStream().use { out -> input.copyTo(out); out.flush(); (out as? java.io.FileOutputStream)?.fd?.sync() } }
            if (temp.length() != length) throw StorageException("E_WRITE_VERIFY", "Gravação incompleta de $rel: ${temp.length()} de $length bytes.")
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { temp.delete() }
        }
    }

    override fun freeBytes(): Long? = runCatching { root.usableSpace }.getOrNull()?.takeIf { it > 0 }

    override fun listFiles(rel: String): List<String> {
        val base = file(rel)
        if (!base.exists()) return emptyList()
        return base.walkTopDown().filter { it.isFile && !it.name.endsWith(".deckbridge-novo") }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }.toList()
    }
}
