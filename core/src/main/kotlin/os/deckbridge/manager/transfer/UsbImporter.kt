package os.deckbridge.manager.transfer

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.RawPdb
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.replaceAtomically
import os.deckbridge.manager.volume.sha256

/**
 * Traz uma biblioteca de um pendrive para a pasta local. Só lê o pendrive. A biblioteca local atual é guardada antes
 * (backup) e substituída: juntar duas bibliotecas não faz parte desta versão.
 */
class UsbImporter(private val backupBase: File) {
    fun import(source: Storage, local: File, includeAudio: Boolean, progress: (String) -> Unit = {}, cancelled: () -> Boolean = { false }): TransferReport {
        val pdb = source.read(PDB_RELATIVE) ?: throw ExportException("E_SOURCE_NO_LIBRARY", "Não achei $PDB_RELATIVE no pendrive.")
        val view = try {
            PdbEditor(RawPdb.parse(pdb), null).view()
        } catch (e: Exception) {
            throw ExportException("E_SOURCE_PDB", "Não consegui ler o export.pdb do pendrive (${e.message}).")
        }
        var backupDir: File? = null
        if (File(local, PDB_RELATIVE).isFile) {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
            val dir = File(backupBase, "local-antes-de-importar/$stamp").apply { mkdirs() }
            for (rel in listOf(PDB_RELATIVE, EXT_RELATIVE)) File(local, rel).takeIf { it.isFile }?.let { File(dir, rel).apply { parentFile.mkdirs() }.writeBytes(it.readBytes()) }
            backupDir = dir
        }
        val wanted = LinkedHashSet<String>()
        for (t in view.tracks) {
            if (includeAudio) t.filePath.replace('\\', '/').trim('/').takeIf { it.isNotEmpty() && source.exists(it) }?.let { wanted += it }
            val dat = t.analyzePath.replace('\\', '/').trim('/')
            if (dat.isNotEmpty()) for (ext in listOf("DAT", "EXT", "2EX")) listOf(dat.dropLast(3) + ext, dat.dropLast(3) + ext.lowercase()).firstOrNull { source.exists(it) }?.let { wanted += it }
        }
        var copied = 0
        var bytes = 0L
        wanted.forEachIndexed { n, rel ->
            if (cancelled()) throw Cancelled()
            val dest = File(local, rel)
            val len = source.length(rel)
            if (dest.isFile && dest.length() == len) return@forEachIndexed
            progress("Trazendo ${n + 1}/${wanted.size}: ${rel.substringAfterLast('/')}")
            dest.parentFile.mkdirs()
            val temp = File(dest.parentFile, dest.name + ".deckbridge-novo")
            try {
                source.open(rel)!!.use { input -> temp.outputStream().use { input.copyTo(it) } }
                if (temp.length() != len) throw ExportException("E_READ_VERIFY", "Leitura incompleta de $rel.")
                java.nio.file.Files.move(temp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                runCatching { temp.delete() }
            }
            copied++
            bytes += len
        }
        source.read(EXT_RELATIVE)?.let { replaceAtomically(File(local, EXT_RELATIVE).apply { parentFile.mkdirs() }, it) }
        File(local, PDB_RELATIVE).parentFile.mkdirs()
        replaceAtomically(File(local, PDB_RELATIVE), pdb)
        check(sha256(File(local, PDB_RELATIVE).readBytes()) == sha256(pdb))
        return TransferReport(wanted.size, copied, wanted.size - copied, bytes, view.tracks.size, view.playlists.count { !it.folder }, backupDir, emptyList())
    }
}
