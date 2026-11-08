package os.deckbridge.manager.transfer

import java.io.File
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.RawPdb
import os.deckbridge.manager.smart.Smart
import os.deckbridge.manager.volume.Backups
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.SafeWriter
import os.deckbridge.manager.volume.Volumes

data class MergeReport(val added: Int, val alreadyThere: Int, val failed: Int, val playlistsTouched: Int)

/**
 * Junta a biblioteca de um pendrive na biblioteca local, sem apagar nada de nenhum lado: cada faixa que ainda não existe
 * aqui (mesmo título, artista e duração) é trazida com áudio, análises, nota, cor e playlists. Só lê o pendrive. Grava
 * em lotes, cada um com cópia de segurança, para não segurar tudo em disco temporário.
 */
class UsbMerger(private val backups: Backups, private val temp: File) {
    fun merge(source: Storage, local: File, progress: (String) -> Unit = {}, cancelled: () -> Boolean = { false }, batch: Int = 20): MergeReport {
        val pdb = source.read(PDB_RELATIVE) ?: throw ExportException("E_SOURCE_NO_LIBRARY", "Não achei $PDB_RELATIVE no pendrive.")
        val theirs = try {
            PdbEditor(RawPdb.parse(pdb), null).view()
        } catch (e: Exception) {
            throw ExportException("E_SOURCE_PDB", "Não consegui ler o export.pdb do pendrive (${e.message}).")
        }
        val playlistsOf = HashMap<Int, MutableList<String>>()
        for (p in theirs.playlists.filter { !it.folder }) for (id in theirs.entries[p.id].orEmpty()) playlistsOf.getOrPut(id) { ArrayList() } += p.name
        var added = 0
        var dups = 0
        var failed = 0
        val touched = HashSet<String>()
        val work = File(temp, "juntar").apply { deleteRecursively(); mkdirs() }
        try {
            var vol = Volumes.open(local)
            var editor = PdbEditor(vol.raw, local)
            var pending = ArrayList<String>()
            fun flush() {
                if (pending.isEmpty()) return
                progress("Gravando")
                SafeWriter.apply(vol, editor, pending, backups)
                vol = Volumes.open(local)
                editor = PdbEditor(vol.raw, local)
                pending = ArrayList()
                work.listFiles()?.forEach { it.deleteRecursively() }
            }
            theirs.tracks.forEachIndexed { n, t ->
                if (cancelled()) throw Cancelled()
                progress("${n + 1}/${theirs.tracks.size} · ${t.title}")
                if (Smart.findDuplicate(editor.view(), t.title, t.artist, t.durationSeconds) != null) {
                    dups++
                    return@forEachIndexed
                }
                val audioRel = t.filePath.replace('\\', '/').trim('/')
                val input = source.open(audioRel)
                if (input == null) {
                    failed++
                    return@forEachIndexed
                }
                val audio = File(work, "${t.id}-${audioRel.substringAfterLast('/')}")
                input.use { i -> audio.outputStream().use { i.copyTo(it) } }
                val anlz = LinkedHashMap<String, ByteArray>()
                val dat = t.analyzePath.replace('\\', '/').trim('/')
                if (dat.isNotEmpty()) for (kind in listOf("DAT", "EXT", "2EX")) {
                    val bytes = source.read(dat.dropLast(3) + kind) ?: source.read(dat.dropLast(3) + kind.lowercase())
                    if (bytes != null) anlz[kind] = bytes
                }
                val lists = playlistsOf[t.id].orEmpty()
                try {
                    editor.apply(Edit.AdoptTrack(t, audio, anlz, lists))
                    pending += "Trazer \"${t.title}\" do pendrive"
                    touched += lists
                    added++
                } catch (e: Exception) {
                    failed++
                }
                if (pending.size >= batch) flush()
            }
            flush()
        } finally {
            work.deleteRecursively()
        }
        return MergeReport(added, dups, failed, touched.size)
    }
}
