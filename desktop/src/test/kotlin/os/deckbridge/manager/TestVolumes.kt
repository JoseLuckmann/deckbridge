package os.deckbridge.manager

import java.io.File
import java.nio.file.Files
import os.deckbridge.logic.SyntheticRekordbox
import os.deckbridge.manager.pdb.PdbType
import os.deckbridge.manager.pdb.RawPdb

object TestVolumes {
    /** Pendrive sintético do app gravado numa pasta temporária (o gerador dos fixtures do app, não um export real). */
    fun synthetic(): File {
        val dir = Files.createTempDirectory("dbm-usb").toFile()
        SyntheticRekordbox.volume().files.forEach { (path, bytes) -> File(dir, path).apply { parentFile.mkdirs() }.writeBytes(bytes) }
        return dir
    }

    fun rawOf(dir: File): RawPdb = RawPdb.parse(File(dir, "PIONEER/rekordbox/export.pdb").readBytes())

    fun trackIds(raw: RawPdb): List<Int> = raw.table(PdbType.TRACKS)!!.rows.map { os.deckbridge.manager.pdb.TrackRow.parse(it)!!.id }
}
