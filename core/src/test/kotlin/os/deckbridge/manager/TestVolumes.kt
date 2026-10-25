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
}
