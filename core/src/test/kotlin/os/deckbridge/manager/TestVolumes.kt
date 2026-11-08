package os.deckbridge.manager

import java.io.File
import java.nio.file.Files
import os.deckbridge.logic.SyntheticRekordbox
import os.deckbridge.manager.pdb.PdbType
import os.deckbridge.manager.pdb.RawPdb

object TestVolumes {
    private val created = java.util.Collections.synchronizedList(mutableListOf<File>())

    init {
        // Cada teste cria pendrives de vários MB em /tmp; sem isto eles se acumulam a cada execução.
        Runtime.getRuntime().addShutdownHook(Thread { synchronized(created) { created.forEach { runCatching { it.deleteRecursively() } } } })
    }

    /** Pasta temporária apagada quando a JVM de teste termina. */
    fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { created += it }

    /** Pendrive sintético do app gravado numa pasta temporária (o gerador dos fixtures do app, não um export real). */
    fun synthetic(): File {
        val dir = tempDir("dbm-usb")
        SyntheticRekordbox.volume().files.forEach { (path, bytes) -> File(dir, path).apply { parentFile.mkdirs() }.writeBytes(bytes) }
        return dir
    }

    fun rawOf(dir: File): RawPdb = RawPdb.parse(File(dir, "PIONEER/rekordbox/export.pdb").readBytes())

    fun trackIds(raw: RawPdb): List<Int> = raw.table(PdbType.TRACKS)!!.rows.map { os.deckbridge.manager.pdb.TrackRow.parse(it)!!.id }

    private fun wav(dir: File, name: String, bpm: Double): File {
        val f = File(dir, name)
        f.writeBytes(os.deckbridge.logic.encodeWavMono16(os.deckbridge.logic.click(bpm = bpm, seconds = 24, rate = 22050, freq = 440.0), 22050))
        return f
    }

    /** Biblioteca local nascida do gerador: duas faixas importadas e uma playlist. */
    fun fromScratch(backupBase: File): File {
        val root = TestVolumes.tempDir("dbm-local")
        os.deckbridge.manager.transfer.LocalLibrary.ensure(root)
        val vol = os.deckbridge.manager.volume.Volumes.open(root)
        val src = TestVolumes.tempDir("dbm-src")
        val editor = os.deckbridge.manager.pdb.PdbEditor(vol.raw, vol.root)
        for ((name, bpm) in listOf("Artista Um - Primeira.wav" to 124.0, "Artista Dois - Segunda.wav" to 128.0)) {
            val p = os.deckbridge.manager.importer.ImportPrep.prepare(wav(src, name, bpm))
            editor.apply(os.deckbridge.manager.pdb.Edit.ImportTrack(p, p.info.title, p.info.artist, "Álbum", "House", p.read.keyName, 2026, 1, p.suggestedCues))
        }
        val ids = editor.view().tracks.map { it.id }
        editor.apply(os.deckbridge.manager.pdb.Edit.CreatePlaylist(1, 0, "Sexta", false))
        editor.apply(os.deckbridge.manager.pdb.Edit.AddToPlaylist(1, ids))
        os.deckbridge.manager.volume.SafeWriter.apply(vol, editor, listOf("criar"), os.deckbridge.manager.volume.Backups(backupBase))
        return root
    }

}
