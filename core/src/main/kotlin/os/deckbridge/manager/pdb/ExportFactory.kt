package os.deckbridge.manager.pdb

import java.time.LocalDate

/**
 * Cria do zero os dois bancos de um pendrive (ou de uma biblioteca local) no formato do rekordbox: `export.pdb` com as
 * 20 tabelas e `exportExt.pdb` com as 9. Só as tabelas de constantes vêm preenchidas; faixas, artistas, álbuns e playlists
 * entram depois pelas edições do [PdbEditor], como em qualquer biblioteca existente.
 */
object ExportFactory {
    private val mainTypes = (0..19).toList()
    private const val EXT_TABLES = 9

    fun exportPdb(today: LocalDate = LocalDate.now()): ByteArray {
        val rows = HashMap<Int, List<ByteArray>>()
        rows[PdbType.COLORS] = ExportTemplate.colors()
        rows[16] = ExportTemplate.columns()
        rows[17] = ExportTemplate.menu17()
        rows[18] = ExportTemplate.menu18()
        rows[PdbType.HISTORY] = listOf(ExportTemplate.history(today.toString()))
        return RawPdb.build(PAGE_LEN, mainTypes.map { it to (rows[it] ?: emptyList()) }, unknown16 = 5, sequence = 9, dataSequences = mapOf(6 to 2, 16 to 3, 17 to 4, 18 to 5, 19 to 1))
    }

    fun exportExtPdb(): ByteArray {
        val rows = HashMap<Int, List<ByteArray>>()
        rows[3] = ExportTemplate.tags()
        rows[7] = listOf(ExportTemplate.extLiteral())
        return RawPdb.build(PAGE_LEN, (0 until EXT_TABLES).map { it to (rows[it] ?: emptyList()) }, unknown16 = 5, sequence = 6, dataSequences = mapOf(3 to 2, 7 to 1))
    }

    private const val PAGE_LEN = 4096
}
