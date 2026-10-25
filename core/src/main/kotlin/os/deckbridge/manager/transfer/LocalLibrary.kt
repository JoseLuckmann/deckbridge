package os.deckbridge.manager.transfer

import java.io.File
import java.time.LocalDate
import os.deckbridge.manager.pdb.ExportFactory
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.replaceAtomically

const val EXT_RELATIVE = "PIONEER/rekordbox/exportExt.pdb"

/**
 * A biblioteca que mora no aparelho (celular ou computador): a mesma árvore de um pendrive do rekordbox, numa pasta
 * própria do app. Editar nela usa o mesmo caminho de gravação segura do pendrive; exportar copia a árvore para um pendrive.
 */
object LocalLibrary {
    fun exists(root: File): Boolean = File(root, PDB_RELATIVE).isFile

    /** Cria a árvore e os dois bancos vazios, se ainda não existirem. Nunca sobrescreve uma biblioteca existente. */
    fun ensure(root: File, today: LocalDate = LocalDate.now()): File {
        File(root, "PIONEER/rekordbox").mkdirs()
        File(root, "PIONEER/USBANLZ").mkdirs()
        File(root, "Contents").mkdirs()
        if (!File(root, PDB_RELATIVE).isFile) replaceAtomically(File(root, PDB_RELATIVE), ExportFactory.exportPdb(today))
        if (!File(root, EXT_RELATIVE).isFile) replaceAtomically(File(root, EXT_RELATIVE), ExportFactory.exportExtPdb())
        return root
    }
}
