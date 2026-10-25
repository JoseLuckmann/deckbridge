package os.deckbridge.manager.volume

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import os.deckbridge.manager.anlz.AnlzFormatException
import os.deckbridge.manager.anlz.AnlzModel
import os.deckbridge.manager.pdb.PdbFormatException
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.RawPdb

const val PDB_RELATIVE = "PIONEER/rekordbox/export.pdb"

class VolumeException(val code: String, message: String) : Exception(message)

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input: InputStream ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

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

data class BackupRecord(
    val dir: File,
    val created: String,
    val volumeRoot: String,
    val files: List<BackupFile>,
    val changes: List<String>,
    /** Arquivos que não existiam e a gravação criou (ANLZ, áudio importado). */
    val createdFiles: List<String> = emptyList(),
    /** Renomeações feitas (de, para). */
    val moves: List<Pair<String, String>> = emptyList(),
)

/** O que uma restauração fez além de devolver os arquivos. [keptAudio] são áudios importados que ficaram no pendrive. */
data class RestoreResult(val before: BackupRecord, val keptAudio: List<String>, val movedBack: Int, val removedAnalysis: Int)

/** Backups locais, fora do pendrive: sobrevivem a um pendrive danificado. */
class Backups(val base: File = File(System.getProperty("user.home"), ".deckbridge/backups")) {
    private fun volumeDir(root: File): File {
        val canonical = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        val name = root.name.ifEmpty { "volume" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(base, "$name-${sha256(canonical.toByteArray()).take(8)}")
    }

    fun create(
        root: File,
        relatives: List<String>,
        changes: List<String>,
        tag: String = "antes",
        created: List<String> = emptyList(),
        moves: List<Pair<String, String>> = emptyList(),
    ): BackupRecord {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val dir = File(volumeDir(root), "$stamp-$tag").apply { mkdirs() }
        val files = relatives.map { rel ->
            val source = File(root, rel)
            val target = File(dir, rel).apply { parentFile.mkdirs() }
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            val copied = sha256(target)
            if (copied != sha256(source)) throw VolumeException("E_BACKUP_VERIFY", "A cópia de segurança de $rel não confere. Nada foi gravado no pendrive.")
            BackupFile(rel, copied)
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
            setProperty("createdFiles", created.size.toString())
            created.forEachIndexed { n, c -> setProperty("createdFile.$n", c) }
            setProperty("moves", moves.size.toString())
            moves.forEachIndexed { n, m ->
                setProperty("move.$n.from", m.first)
                setProperty("move.$n.to", m.second)
            }
        }
        File(dir, "manifest.properties").outputStream().use { props.store(it, "DeckBridge Library Manager: copia de seguranca") }
        return BackupRecord(dir, stamp, props.getProperty("volumeRoot"), files, changes, created, moves)
    }

    fun list(root: File): List<BackupRecord> {
        val dir = volumeDir(root)
        return (dir.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedByDescending { it.name }.mapNotNull { read(it) }
    }

    fun read(dir: File): BackupRecord? {
        val manifest = File(dir, "manifest.properties")
        if (!manifest.isFile) return null
        val p = Properties().apply { manifest.inputStream().use { load(it) } }
        fun count(key: String) = p.getProperty(key)?.toIntOrNull() ?: 0
        val files = (0 until count("files")).mapNotNull { n ->
            val rel = p.getProperty("file.$n") ?: return@mapNotNull null
            BackupFile(rel, p.getProperty("sha.$n") ?: "")
        }
        val changes = (0 until count("changes")).mapNotNull { p.getProperty("change.$it") }
        val created = (0 until count("createdFiles")).mapNotNull { p.getProperty("createdFile.$it") }
        val moves = (0 until count("moves")).mapNotNull { n ->
            val from = p.getProperty("move.$n.from") ?: return@mapNotNull null
            val to = p.getProperty("move.$n.to") ?: return@mapNotNull null
            from to to
        }
        return BackupRecord(dir, p.getProperty("created") ?: dir.name, p.getProperty("volumeRoot") ?: "", files, changes, created, moves)
    }

    /**
     * Devolve o volume ao estado do backup: desfaz as renomeações, restaura os arquivos copiados e remove os arquivos de
     * análise que a gravação criou. Áudio importado nunca é apagado: fica no pendrive e é listado em [RestoreResult.keptAudio].
     * Antes, guarda o estado de agora dos arquivos copiados (então a restauração dos bancos também se desfaz).
     */
    fun restore(record: BackupRecord, root: File): RestoreResult {
        for (f in record.files) {
            val source = File(record.dir, f.relative)
            if (!source.isFile || sha256(source) != f.sha256) throw VolumeException("E_BACKUP_CORRUPT", "A cópia de segurança de ${f.relative} está corrompida. Nada foi restaurado.")
        }
        val present = record.files.map { it.relative }.filter { File(root, it).isFile }
        val before = create(root, present, listOf("Estado antes de restaurar ${record.created}"), tag = "antes-de-restaurar")
        var back = 0
        for ((from, to) in record.moves.reversed()) {
            val now = File(root, to)
            val orig = File(root, from)
            if (now.isFile && (!orig.exists() || from.equals(to, ignoreCase = true))) {
                orig.parentFile.mkdirs()
                Files.move(now.toPath(), orig.toPath(), StandardCopyOption.REPLACE_EXISTING)
                back++
            }
        }
        for (f in record.files) replaceAtomically(File(root, f.relative), File(record.dir, f.relative).readBytes())
        var removed = 0
        val kept = ArrayList<String>()
        for (rel in record.createdFiles) {
            val f = File(root, rel)
            if (!f.isFile) continue
            if (isAudio(rel)) kept += rel else if (f.delete()) removed++
        }
        return RestoreResult(before, kept, back, removed)
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
    val filesCreated: Int = 0,
    val filesReplaced: Int = 0,
    val filesMoved: Int = 0,
    val audioCopiedBytes: Long = 0,
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
    private fun inside(root: File, rel: String): Boolean {
        if (rel.isBlank() || rel.startsWith("/") || rel.split('/').any { it == ".." || it.isEmpty() }) return false
        val base = root.canonicalFile
        return File(base, rel).canonicalFile.path.startsWith(base.path + File.separator)
    }

    private fun existsCI(root: File, rel: String): Boolean {
        var dir = root
        for (p in rel.split('/')) {
            val name = if (File(dir, p).exists()) p else dir.list()?.firstOrNull { it.equals(p, ignoreCase = true) } ?: return false
            dir = File(dir, name)
        }
        return true
    }

    /**
     * Nomes com acento só podem ser criados se o Java do sistema usa uma codificação que os representa. Em Linux com
     * locale "C" (ASCII) isso falha no meio da gravação; aqui falha antes, com a instrução certa.
     */
    fun encodingProblems(ops: List<FileOp>, jnuEncoding: String? = System.getProperty("sun.jnu.encoding")): List<String> {
        val charset = runCatching { java.nio.charset.Charset.forName(jnuEncoding ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        val encoder = charset.newEncoder()
        val paths = ops.flatMap { op ->
            when (op) {
                is FileOp.Put -> listOf(op.rel)
                is FileOp.CopyIn -> listOf(op.rel)
                is FileOp.Move -> listOf(op.from, op.to)
            }
        }
        val bad = paths.filter { !encoder.canEncode(it) }.distinct()
        return if (bad.isEmpty()) emptyList()
        else listOf("o sistema (codificação ${charset.name()}) não aceita acentos em nomes de arquivo, como em \"${bad.first()}\". Abra o programa com LANG=C.UTF-8 (ou use um sistema com locale UTF-8)")
    }

    /** Confere cada operação de arquivo contra o disco antes de qualquer gravação. */
    fun checkOps(target: File, ops: List<FileOp>): List<String> {
        val problems = ArrayList(encodingProblems(ops))
        var need = 0L
        for (op in ops) when (op) {
            is FileOp.Put -> {
                if (!inside(target, op.rel)) problems += "caminho fora do pendrive: ${op.rel}"
                else if (op.rel.substringAfterLast('.').uppercase() in setOf("DAT", "EXT", "2EX")) {
                    try {
                        if (!AnlzModel.parse(op.bytes).write().contentEquals(op.bytes)) problems += "a análise ${op.rel} não faz ida e volta idêntica"
                    } catch (e: AnlzFormatException) {
                        problems += "a análise ${op.rel} é inválida: ${e.message}"
                    }
                }
                need += op.bytes.size
            }
            is FileOp.CopyIn -> {
                if (!inside(target, op.rel)) problems += "caminho fora do pendrive: ${op.rel}"
                else if (existsCI(target, op.rel)) problems += "já existe um arquivo em ${op.rel}"
                if (!op.source.isFile || !op.source.canRead()) problems += "não consigo ler ${op.source.path}"
                need += op.source.length()
            }
            is FileOp.Move -> {
                if (!inside(target, op.from) || !inside(target, op.to)) problems += "caminho fora do pendrive: ${op.from} → ${op.to}"
                else {
                    if (!File(target, op.from).isFile && !existsCI(target, op.from)) problems += "o arquivo ${op.from} não está no pendrive"
                    if (existsCI(target, op.to) && !op.from.equals(op.to, ignoreCase = true)) problems += "já existe um arquivo em ${op.to}"
                }
            }
        }
        val free = target.usableSpace
        if (need > 0 && free in 1 until need + (8L shl 20)) problems += "pouco espaço livre no pendrive (${free / (1 shl 20)} MB livres, ${need / (1 shl 20)} MB necessários)"
        return problems
    }

    private class Done(val undo: () -> Unit)

    /**
     * Aplica as edições do [editor] ao volume [target] (por padrão, o próprio volume aberto): primeiro as renomeações, as
     * cópias de áudio e os arquivos de análise; o `export.pdb` por último. Qualquer falha desfaz tudo em ordem inversa.
     * [changes] é o texto das mudanças, guardado no manifesto do backup.
     */
    fun apply(source: LoadedVolume, editor: PdbEditor, changes: List<String>, backups: Backups, target: File = source.root, hook: (String) -> Unit = {}): ApplyReport {
        val changed = editor.changedTables()
        val ops = editor.fileOps()
        if (changed.isEmpty() && ops.isEmpty()) throw VolumeException("E_NOTHING_TO_DO", "Não há mudanças para aplicar.")
        val pdb = File(target, PDB_RELATIVE)
        if (!pdb.isFile) throw VolumeException("E_RB_EXPORT_NOT_FOUND", "O destino não tem $PDB_RELATIVE.")
        val store = runCatching { Files.getFileStore(pdb.toPath()) }.getOrNull()
        if (store != null && store.isReadOnly) throw VolumeException("E_VOLUME_READ_ONLY", "O volume está montado somente leitura. Remonte com escrita ou use outra cópia.")
        if (!pdb.canWrite() || !pdb.parentFile.canWrite()) throw VolumeException("E_VOLUME_NOT_WRITABLE", "Sem permissão de escrita em ${pdb.parentFile.path}.")
        val current = pdb.readBytes()
        val currentSha = sha256(current)
        if (currentSha != source.sha256) throw VolumeException("E_VOLUME_CHANGED", "O export.pdb mudou desde que foi aberto (outro programa gravou nele?). Reabra o pendrive e refaça as mudanças.")
        hook("montando o banco novo")
        val newBytes = if (changed.isEmpty()) current else source.raw.write(changed)
        hook("conferindo")
        val problems = (if (changed.isEmpty()) emptyList() else Verifier.check(source.raw, newBytes, changed)) + checkOps(target, ops)
        if (problems.isNotEmpty()) throw VolumeException("E_VERIFY", "A conferência falhou e nada foi gravado:\n" + problems.joinToString("\n") { "• $it" })

        val puts = ops.filterIsInstance<FileOp.Put>()
        val replaced = puts.map { it.rel }.filter { File(target, it).isFile }
        val createdPuts = puts.map { it.rel }.filter { !File(target, it).isFile }
        val copies = ops.filterIsInstance<FileOp.CopyIn>()
        val moves = ops.filterIsInstance<FileOp.Move>()
        hook("copiando o backup")
        val backup = backups.create(
            target,
            listOf(PDB_RELATIVE) + replaced,
            changes,
            created = createdPuts + copies.map { it.rel },
            moves = moves.map { it.from to it.to },
        )

        val done = ArrayList<Done>()
        var copiedBytes = 0L
        try {
            for (op in moves) {
                hook("renomeando ${op.from.substringAfterLast('/')}")
                val from = File(target, existingCase(target, op.from))
                val to = File(target, op.to).apply { parentFile.mkdirs() }
                try {
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(from.toPath(), to.toPath())
                }
                done += Done { Files.move(to.toPath(), from.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            }
            for (op in copies) {
                hook("copiando ${op.source.name}")
                val dest = File(target, op.rel).apply { parentFile.mkdirs() }
                val expected = sha256(op.source)
                val temp = File(dest.parentFile, dest.name + ".deckbridge-novo")
                try {
                    Files.copy(op.source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    if (sha256(temp) != expected) throw VolumeException("E_WRITE_VERIFY", "A cópia de ${op.source.name} para o pendrive não confere.")
                    Files.move(temp.toPath(), dest.toPath())
                } finally {
                    runCatching { temp.delete() }
                }
                copiedBytes += dest.length()
                done += Done { dest.delete() }
            }
            for (op in puts) {
                val dest = File(target, op.rel).apply { parentFile.mkdirs() }
                val before = if (dest.isFile) dest.readBytes() else null
                replaceAtomically(dest, op.bytes)
                done += Done { if (before != null) replaceAtomically(dest, before) else dest.delete() }
                if (sha256(dest.readBytes()) != sha256(op.bytes)) throw VolumeException("E_WRITE_VERIFY", "${op.rel} gravado não confere.")
            }
            hook("gravando o banco")
            if (changed.isNotEmpty()) {
                replaceAtomically(pdb, newBytes)
                done += Done { replaceAtomically(pdb, current) }
                val reread = pdb.readBytes()
                if (sha256(reread) != sha256(newBytes)) throw VolumeException("E_WRITE_VERIFY", "O export.pdb gravado não confere.")
                Verifier.check(source.raw, reread, changed).firstOrNull()?.let { throw VolumeException("E_VERIFY", it) }
            }
        } catch (e: Exception) {
            done.asReversed().forEach { runCatching { it.undo() } }
            runCatching { replaceAtomically(pdb, current) }
            throw if (e is VolumeException) e else VolumeException("E_WRITE", "Falha ao gravar; tudo foi desfeito e o pendrive voltou ao estado anterior: ${e.message}")
        }
        return ApplyReport(
            backup, currentSha, sha256(newBytes), changed.keys.sorted(), (newBytes.size - current.size) / source.raw.pageLen, current.size, newBytes.size,
            filesCreated = createdPuts.size + copies.size, filesReplaced = replaced.size, filesMoved = moves.size, audioCopiedBytes = copiedBytes,
        )
    }

    private fun existingCase(root: File, rel: String): String {
        var dir = root
        val out = ArrayList<String>()
        for (p in rel.split('/')) {
            val name = if (File(dir, p).exists()) p else dir.list()?.firstOrNull { it.equals(p, ignoreCase = true) } ?: p
            out += name
            dir = File(dir, name)
        }
        return out.joinToString("/")
    }
}
