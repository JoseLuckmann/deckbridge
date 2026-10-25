package os.deckbridge.manager.volume

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import os.deckbridge.manager.pdb.PdbFormatException
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.RawPdb

const val PDB_RELATIVE = "PIONEER/rekordbox/export.pdb"

class VolumeException(val code: String, message: String) : Exception(message)

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Um volume rekordbox aberto. [sha256] é do `export.pdb` no momento da leitura: a gravação recusa se ele mudou depois. */
class LoadedVolume(val root: File, val raw: RawPdb, val sha256: String) {
    val pdbFile: File get() = File(root, PDB_RELATIVE)
}

object Volumes {
    fun open(root: File): LoadedVolume {
        if (!root.isDirectory) throw VolumeException("E_VOLUME_NOT_FOUND", "A pasta ${root.path} não existe.")
        val pdb = File(root, PDB_RELATIVE)
        if (!pdb.isFile) throw VolumeException("E_RB_EXPORT_NOT_FOUND", "Não achei $PDB_RELATIVE em ${root.path}. Este programa só edita pendrives já exportados pelo rekordbox.")
        val bytes = pdb.readBytes()
        val raw = try {
            RawPdb.parse(bytes)
        } catch (e: PdbFormatException) {
            throw VolumeException(e.code, "Não consegui ler o export.pdb: ${e.message}")
        }
        if (raw.table(0) == null) throw VolumeException("E_PDB_NO_TRACKS", "O export.pdb não tem tabela de faixas.")
        return LoadedVolume(root, raw, sha256(bytes))
    }
}

data class BackupFile(val relative: String, val sha256: String)

data class BackupRecord(val dir: File, val created: String, val volumeRoot: String, val files: List<BackupFile>, val changes: List<String>)

/** Backups locais, fora do pendrive: sobrevivem a um pendrive danificado. */
class Backups(val base: File = File(System.getProperty("user.home"), ".deckbridge/backups")) {
    private fun volumeDir(root: File): File {
        val canonical = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        val name = root.name.ifEmpty { "volume" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(base, "$name-${sha256(canonical.toByteArray()).take(8)}")
    }

    fun create(root: File, relatives: List<String>, changes: List<String>, tag: String = "antes"): BackupRecord {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val dir = File(volumeDir(root), "$stamp-$tag").apply { mkdirs() }
        val files = relatives.map { rel ->
            val source = File(root, rel)
            val target = File(dir, rel).apply { parentFile.mkdirs() }
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            val copied = target.readBytes()
            if (sha256(copied) != sha256(source.readBytes())) throw VolumeException("E_BACKUP_VERIFY", "A cópia de segurança de $rel não confere. Nada foi gravado no pendrive.")
            BackupFile(rel, sha256(copied))
        }
        val props = Properties().apply {
            setProperty("created", stamp)
            setProperty("volumeRoot", runCatching { root.canonicalPath }.getOrDefault(root.absolutePath))
            setProperty("files", files.size.toString())
            files.forEachIndexed { n, f ->
                setProperty("file.$n", f.relative)
                setProperty("sha.$n", f.sha256)
            }
            setProperty("changes", changes.size.toString())
            changes.forEachIndexed { n, c -> setProperty("change.$n", c) }
        }
        File(dir, "manifest.properties").outputStream().use { props.store(it, "DeckBridge Library Manager: copia de seguranca") }
        return BackupRecord(dir, stamp, props.getProperty("volumeRoot"), files, changes)
    }

    fun list(root: File): List<BackupRecord> {
        val dir = volumeDir(root)
        return (dir.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedByDescending { it.name }.mapNotNull { read(it) }
    }

    fun read(dir: File): BackupRecord? {
        val manifest = File(dir, "manifest.properties")
        if (!manifest.isFile) return null
        val p = Properties().apply { manifest.inputStream().use { load(it) } }
        val files = (0 until (p.getProperty("files")?.toIntOrNull() ?: 0)).mapNotNull { n ->
            val rel = p.getProperty("file.$n") ?: return@mapNotNull null
            BackupFile(rel, p.getProperty("sha.$n") ?: "")
        }
        val changes = (0 until (p.getProperty("changes")?.toIntOrNull() ?: 0)).mapNotNull { p.getProperty("change.$it") }
        return BackupRecord(dir, p.getProperty("created") ?: dir.name, p.getProperty("volumeRoot") ?: "", files, changes)
    }

    /** Devolve os arquivos do backup ao volume. Antes, guarda o estado de agora, então restaurar também se desfaz. */
    fun restore(record: BackupRecord, root: File): BackupRecord {
        for (f in record.files) {
            val source = File(record.dir, f.relative)
            if (!source.isFile || sha256(source.readBytes()) != f.sha256) throw VolumeException("E_BACKUP_CORRUPT", "A cópia de segurança de ${f.relative} está corrompida. Nada foi restaurado.")
        }
        val before = create(root, record.files.map { it.relative }, listOf("Estado antes de restaurar ${record.created}"), tag = "antes-de-restaurar")
        for (f in record.files) replaceAtomically(File(root, f.relative), File(record.dir, f.relative).readBytes())
        return before
    }
}

data class ApplyReport(
    val backup: BackupRecord,
    val shaBefore: String,
    val shaAfter: String,
    val changedTables: List<Int>,
    val pagesAdded: Int,
    val bytesBefore: Int,
    val bytesAfter: Int,
)

/** Grava em arquivo temporário ao lado, confere e troca por renomeação. Um erro em qualquer passo devolve o original. */
fun replaceAtomically(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, target.name + ".deckbridge-novo")
    try {
        Files.newByteChannel(temp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(bytes))
            (channel as? java.nio.channels.FileChannel)?.force(true)
        }
        if (sha256(temp.readBytes()) != sha256(bytes)) throw VolumeException("E_WRITE_VERIFY", "O arquivo gravado em ${temp.name} não confere com o que foi enviado.")
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        runCatching { temp.delete() }
    }
}

object SafeWriter {
    /**
     * Aplica as edições do [editor] ao `export.pdb` do volume [target] (por padrão, o próprio volume aberto).
     * [changes] é o texto das mudanças, guardado no manifesto do backup.
     */
    fun apply(source: LoadedVolume, editor: PdbEditor, changes: List<String>, backups: Backups, target: File = source.root, hook: (String) -> Unit = {}): ApplyReport {
        val changed = editor.changedTables()
        if (changed.isEmpty()) throw VolumeException("E_NOTHING_TO_DO", "Não há mudanças para aplicar.")
        val pdb = File(target, PDB_RELATIVE)
        if (!pdb.isFile) throw VolumeException("E_RB_EXPORT_NOT_FOUND", "O destino não tem $PDB_RELATIVE.")
        val store = runCatching { Files.getFileStore(pdb.toPath()) }.getOrNull()
        if (store != null && store.isReadOnly) throw VolumeException("E_VOLUME_READ_ONLY", "O volume está montado somente leitura. Remonte com escrita ou use outra cópia.")
        if (!pdb.canWrite() || !pdb.parentFile.canWrite()) throw VolumeException("E_VOLUME_NOT_WRITABLE", "Sem permissão de escrita em ${pdb.parentFile.path}.")
        val current = pdb.readBytes()
        val currentSha = sha256(current)
        if (currentSha != source.sha256) throw VolumeException("E_VOLUME_CHANGED", "O export.pdb mudou desde que foi aberto (outro programa gravou nele?). Reabra o pendrive e refaça as mudanças.")
        hook("montando o banco novo")
        val newBytes = source.raw.write(changed)
        hook("conferindo o banco novo")
        val problems = Verifier.check(source.raw, newBytes, changed)
        if (problems.isNotEmpty()) throw VolumeException("E_VERIFY", "O banco novo não passou na conferência e nada foi gravado:\n" + problems.joinToString("\n") { "• $it" })
        hook("copiando o backup")
        val backup = backups.create(target, listOf(PDB_RELATIVE), changes)
        hook("gravando")
        try {
            replaceAtomically(pdb, newBytes)
            val reread = pdb.readBytes()
            if (sha256(reread) != sha256(newBytes)) throw VolumeException("E_WRITE_VERIFY", "O export.pdb gravado não confere.")
            Verifier.check(source.raw, reread, changed).firstOrNull()?.let { throw VolumeException("E_VERIFY", it) }
        } catch (e: Exception) {
            runCatching { replaceAtomically(pdb, current) }
            throw if (e is VolumeException) e else VolumeException("E_WRITE", "Falha ao gravar; o banco original foi restaurado: ${e.message}")
        }
        return ApplyReport(backup, currentSha, sha256(newBytes), changed.keys.sorted(), (newBytes.size - current.size) / source.raw.pageLen, current.size, newBytes.size)
    }
}
