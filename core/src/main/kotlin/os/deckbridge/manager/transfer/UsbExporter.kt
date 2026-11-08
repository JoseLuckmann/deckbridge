package os.deckbridge.manager.transfer

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.RawPdb
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.Volumes
import os.deckbridge.manager.volume.sha256

class ExportException(val code: String, message: String) : Exception(message)

/** O que já existe no destino antes de exportar, para a tela avisar antes de substituir. */
data class TargetLibrary(val tracks: Int, val playlists: Int, val pdbBytes: Int)

data class TransferReport(
    val files: Int,
    val copied: Int,
    val skipped: Int,
    val bytesCopied: Long,
    val tracks: Int,
    val playlists: Int,
    val backupDir: File?,
    val orphanAudio: List<String>,
)

class Cancelled : Exception("Cancelado.")

/**
 * Exporta a biblioteca local para um pendrive. Ordem: conferir a origem, ver o destino, backup local dos bancos do
 * destino, copiar áudio e análises (sem apagar nada), gravar `exportExt.pdb` e, por último, `export.pdb`, e reler tudo.
 * Se a conferência final falha e havia banco antigo, ele volta.
 */
class UsbExporter(private val backupBase: File) {
    fun inspect(target: Storage): TargetLibrary? {
        val bytes = target.read(PDB_RELATIVE) ?: return null
        return runCatching {
            val raw = RawPdb.parse(bytes)
            val view = PdbEditor(raw, null).view()
            TargetLibrary(view.tracks.size, view.playlists.count { !it.folder }, bytes.size)
        }.getOrElse { throw ExportException("E_TARGET_PDB", "O pendrive tem um export.pdb que não consegui ler (${it.message}). Nada foi alterado.") }
    }

    /** Arquivos que o banco local referencia: áudio e as análises (`.DAT`, `.EXT`, `.2EX`) que existirem. */
    fun requiredFiles(local: File): Pair<List<String>, List<String>> {
        val vol = Volumes.open(local)
        val view = PdbEditor(vol.raw, local).view()
        val need = LinkedHashSet<String>()
        val missing = ArrayList<String>()
        for (t in view.tracks) {
            val audio = t.filePath.replace('\\', '/').trim('/')
            if (audio.isNotEmpty()) {
                if (File(local, audio).isFile) need += audio else missing += "${t.artist} - ${t.title} ($audio)"
            }
            val dat = t.analyzePath.replace('\\', '/').trim('/')
            if (dat.isNotEmpty()) {
                val stem = dat.dropLast(3)
                for (ext in listOf("DAT", "EXT", "2EX")) {
                    val candidates = listOf(stem + ext, stem + ext.lowercase())
                    candidates.firstOrNull { File(local, it).isFile }?.let { need += it }
                }
            }
        }
        return need.toList() to missing
    }

    fun export(
        local: File,
        target: Storage,
        replaceExisting: Boolean,
        progress: (String) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): TransferReport {
        val vol = Volumes.open(local)
        val view = PdbEditor(vol.raw, local).view()
        val (need, missing) = requiredFiles(local)
        if (missing.isNotEmpty()) {
            throw ExportException("E_LOCAL_AUDIO_MISSING", "Faltam ${missing.size} arquivo(s) de áudio na biblioteca local, por exemplo ${missing.first()}. Nada foi enviado.")
        }
        val existing = inspect(target)
        if (existing != null && !replaceExisting) {
            throw ExportException("E_TARGET_HAS_LIBRARY", "O pendrive já tem uma biblioteca (${existing.tracks} faixas, ${existing.playlists} playlists). Exportar vai substituí-la.")
        }
        // Espaço: só o que ainda não está lá com o mesmo tamanho.
        val todo = need.filter { target.length(it) != File(local, it).length() }
        val bytes = todo.sumOf { File(local, it).length() } + File(local, PDB_RELATIVE).length() + 1_000_000
        target.freeBytes()?.let { free ->
            if (free < bytes) throw ExportException("E_NO_SPACE", "O pendrive não tem espaço: faltam ${(bytes - free) / 1_000_000} MB. Nada foi enviado.")
        }
        // Backup local dos bancos do destino, antes de qualquer gravação.
        var backupDir: File? = null
        val oldPdb = target.read(PDB_RELATIVE)
        if (oldPdb != null) {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
            val dir = File(backupBase, "pendrive-${target.label.replace(Regex("[^A-Za-z0-9._-]"), "_")}/$stamp-antes-de-exportar").apply { mkdirs() }
            for (rel in listOf(PDB_RELATIVE, EXT_RELATIVE)) {
                val b = target.read(rel) ?: continue
                File(dir, rel).apply { parentFile.mkdirs() }.writeBytes(b)
                if (sha256(File(dir, rel).readBytes()) != sha256(b)) throw ExportException("E_BACKUP_VERIFY", "A cópia de segurança de $rel não confere. Nada foi gravado no pendrive.")
            }
            backupDir = dir
        }
        var copied = 0
        var copiedBytes = 0L
        need.forEachIndexed { n, rel ->
            if (cancelled()) throw Cancelled()
            val src = File(local, rel)
            if (target.length(rel) == src.length()) return@forEachIndexed
            progress("Copiando ${n + 1}/${need.size}: ${rel.substringAfterLast('/')}")
            src.inputStream().buffered().use { target.write(rel, it, src.length()) }
            copied++
            copiedBytes += src.length()
        }
        if (cancelled()) throw Cancelled()
        progress("Gravando o banco de dados")
        File(local, EXT_RELATIVE).takeIf { it.isFile }?.let { target.write(EXT_RELATIVE, it.readBytes()) }
        val newPdb = File(local, PDB_RELATIVE).readBytes()
        target.write(PDB_RELATIVE, newPdb)
        // Conferência final, relendo do pendrive.
        progress("Conferindo o que foi gravado")
        try {
            val back = target.read(PDB_RELATIVE) ?: throw ExportException("E_VERIFY", "Não consegui reler o export.pdb do pendrive.")
            if (sha256(back) != sha256(newPdb)) throw ExportException("E_VERIFY", "O export.pdb do pendrive não confere com o enviado.")
            val readBack = PdbEditor(RawPdb.parse(back), null).view()
            if (readBack.tracks.size != view.tracks.size) throw ExportException("E_VERIFY", "O pendrive tem ${readBack.tracks.size} faixas, eram ${view.tracks.size}.")
            for (rel in need) if (target.length(rel) != File(local, rel).length()) throw ExportException("E_VERIFY", "O arquivo $rel do pendrive não confere em tamanho.")
        } catch (e: Exception) {
            if (oldPdb != null) runCatching { target.write(PDB_RELATIVE, oldPdb) }
            throw if (e is ExportException) e else ExportException("E_VERIFY", "A conferência falhou (${e.message}). O banco antigo do pendrive foi devolvido.")
        }
        val referenced = need.map { it.lowercase() }.toSet()
        val orphans = target.listFiles("").filter { os.deckbridge.manager.volume.isAudio(it) && !it.startsWith("PIONEER/") && it.lowercase() !in referenced }
        return TransferReport(need.size, copied, need.size - copied, copiedBytes, view.tracks.size, view.playlists.count { !it.folder }, backupDir, orphans)
    }
}
