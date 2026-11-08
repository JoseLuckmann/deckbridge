package os.deckbridge.manager

import os.deckbridge.manager.pdb.ExportFactory
import os.deckbridge.manager.pdb.RawPdb
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportFactoryTest {
    private val real = System.getenv("REAL_EXPORT")?.let { File("$it/empty/PIONEER/rekordbox") }?.takeIf { it.isDirectory }

    @Test fun `os bancos novos abrem e trazem as tabelas fixas`() {
        val main = RawPdb.parse(ExportFactory.exportPdb(LocalDate.of(2026, 10, 25)))
        assertEquals(20, main.tables.size)
        assertEquals(8, main.table(6)!!.rows.size)
        assertEquals(27, main.table(16)!!.rows.size)
        assertEquals(22, main.table(17)!!.rows.size)
        assertEquals(17, main.table(18)!!.rows.size)
        assertEquals(0, main.table(0)!!.rows.size)
        assertTrue(main.warnings.isEmpty(), main.warnings.toString())
        val ext = RawPdb.parse(ExportFactory.exportExtPdb())
        assertEquals(9, ext.tables.size)
        assertEquals(28, ext.table(3)!!.rows.size)
    }

    @Test fun `igual ao export vazio real fora do que e variavel`() {
        val dir = real ?: return
        // O export real vazio traz a data de criação do histórico 2022-02-02.
        val mine = ExportFactory.exportPdb(LocalDate.of(2022, 2, 2))
        val theirs = File(dir, "export.pdb").readBytes()
        assertEquals(theirs.size, mine.size, "tamanho do export.pdb")
        val diff = (0 until theirs.size / 4096).filter { n -> !mine.copyOfRange(n * 4096, n * 4096 + 4096).contentEquals(theirs.copyOfRange(n * 4096, n * 4096 + 4096)) }
        println("export.pdb páginas diferentes: $diff")
        val myExt = ExportFactory.exportExtPdb()
        val theirExt = File(dir, "exportExt.pdb").readBytes()
        assertEquals(theirExt.size, myExt.size, "tamanho do exportExt.pdb")
        val diffExt = (0 until theirExt.size / 4096).filter { n -> !myExt.copyOfRange(n * 4096, n * 4096 + 4096).contentEquals(theirExt.copyOfRange(n * 4096, n * 4096 + 4096)) }
        println("exportExt.pdb páginas diferentes: $diffExt")
        for (n in diff.filter { it !in 1..13 || it == 14 }.take(8)) {
            val a = mine.copyOfRange(n * 4096, n * 4096 + 4096); val b = theirs.copyOfRange(n * 4096, n * 4096 + 4096)
            println("pág $n: " + (0 until 4096).filter { a[it] != b[it] }.take(24).joinToString { "$it:%02x/%02x".format(a[it], b[it]) })
        }
        assertTrue(diff.isEmpty() && diffExt.isEmpty(), "páginas diferentes: $diff / $diffExt")
        assertContentEquals(theirs, mine)
    }
}
