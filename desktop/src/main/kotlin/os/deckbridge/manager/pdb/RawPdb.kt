package os.deckbridge.manager.pdb

/**
 * export.pdb como bytes. O layout segue a descrição pública do formato (Crate Digger, `rekordbox_pdb.ksy`, commit 7c9d535,
 * EPL-2.0/MPL-2.0/LGPL-3.0): páginas fixas, tabelas em lista encadeada, índice de linhas de trás para frente, grupos de 16
 * com máscara de presença. Nenhum código dele foi copiado.
 *
 * Duas ideias guiam o gravador:
 *  - as linhas são lidas como bytes e voltam como bytes. Só o que o usuário edita é recodificado, então campos que o rekordbox
 *    escreveu e que não conhecemos passam intactos;
 *  - uma tabela que mudou vira páginas novas no fim do arquivo e o cabeçalho aponta para elas. As tabelas que não mudaram
 *    continuam byte a byte iguais, na mesma posição. As páginas antigas ficam órfãs (ver [RawPdb.compact]).
 */
const val PAGE_HEADER_END = 40
const val GROUP_BYTES = 0x24
const val FLAG_DATA_PAGE = 0x24
/** Fim de cadeia nos exports reais; os fixtures antigos usavam 0x00FFFFFF, que o leitor também aceita. */
const val NO_NEXT_PAGE = 0x03FFFFFF
private const val LEGACY_NO_NEXT_PAGE = 0x00FFFFFF
private const val MAX_PAGES = 200_000
private const val MAX_FILE_BYTES = 256L * 1024 * 1024

class PdbFormatException(val code: String, message: String) : Exception(message)

/** Tipos de tabela do export.pdb. */
object PdbType {
    const val TRACKS = 0
    const val GENRES = 1
    const val ARTISTS = 2
    const val ALBUMS = 3
    const val LABELS = 4
    const val KEYS = 5
    const val COLORS = 6
    const val PLAYLIST_TREE = 7
    const val PLAYLIST_ENTRIES = 8
    const val HISTORY_PLAYLISTS = 11
    const val HISTORY_ENTRIES = 12
    const val ARTWORK = 13
    const val COLUMNS = 16
    const val HISTORY = 19
}

class RawTable(
    val type: Int,
    val emptyCandidate: Int,
    val firstPage: Int,
    val lastPage: Int,
    /** Linhas presentes, na ordem das páginas da tabela. Linhas apagadas (bit de presença zerado) não entram. */
    val rows: List<ByteArray>,
    /** Páginas visitadas (cabeça e dados), na ordem da cadeia. */
    val pages: List<Int>,
)

class RawPdb(
    val bytes: ByteArray,
    val pageLen: Int,
    val nextUnusedPage: Int,
    val sequence: Int,
    val tables: List<RawTable>,
    /** Avisos de leitura que não impedem a edição (por exemplo, uma tabela com cadeia truncada). */
    val warnings: List<String>,
) {
    fun table(type: Int): RawTable? = tables.firstOrNull { it.type == type }

    val pageCount: Int get() = bytes.size / pageLen

    /**
     * Devolve o arquivo novo, com as tabelas de [replaced] (tipo → linhas) reescritas em páginas novas no fim.
     * As demais ficam como estão, na mesma posição. O número de sequência do banco sobe um, e as páginas novas levam o
     * valor anterior, como descreve a especificação.
     *
     * As páginas novas seguem o que exports reais do rekordbox mostram: uma página de índice (`0x64`) sem entradas,
     * páginas de dados `0x24`, os campos de transação preenchidos, e a "página vazia" da tabela é um número reservado
     * depois do fim do arquivo, contado em `next_unused_page`. Tabela que fica sem linhas vira índice + página zerada.
     */
    fun write(replaced: Map<Int, List<ByteArray>>): ByteArray {
        if (replaced.isEmpty()) return bytes.copyOf()
        for (type in replaced.keys) if (table(type) == null) throw PdbFormatException("E_PDB_TABLE_MISSING", "o banco não tem a tabela $type")
        val laid = ArrayList<Laid>()
        var next = pageCount
        for (table in tables) {
            val rows = replaced[table.type] ?: continue
            val head = next++
            val chunks = if (rows.isEmpty()) emptyList() else Pages.paginate(rows, pageLen)
            val first = if (chunks.isEmpty()) -1 else next
            val dataIndexes = IntArray(chunks.size) { next + it }
            next += chunks.size
            if (chunks.isEmpty()) next++ // página zerada logo depois do índice, como nas tabelas vazias reais
            laid += Laid(table.type, head, dataIndexes, chunks, rows.isNotEmpty())
        }
        var reserved = next
        val entries = HashMap<Int, IntArray>() // tipo → (empty_candidate, primeira, última)
        val pages = ArrayList<ByteArray>()
        for (l in laid) {
            val candidate = if (l.hasRows) reserved++ else l.head + 1
            pages += Pages.indexPage(pageLen, l.head, l.type, if (l.hasRows) l.dataIndexes.first() else NO_NEXT_PAGE, sequence)
            if (l.hasRows) {
                l.chunks.forEachIndexed { n, chunk ->
                    val nextPage = if (n == l.chunks.lastIndex) candidate else l.dataIndexes[n + 1]
                    pages += Pages.dataPage(pageLen, l.dataIndexes[n], l.type, nextPage, sequence, chunk)
                }
            } else {
                pages += ByteArray(pageLen)
            }
            entries[l.type] = intArrayOf(candidate, l.head, if (l.hasRows) l.dataIndexes.last() else l.head)
        }
        val out = ByteArray(bytes.size + pages.size * pageLen)
        bytes.copyInto(out)
        pages.forEachIndexed { n, page -> page.copyInto(out, bytes.size + n * pageLen) }
        out.putU32(12, reserved)
        out.putU32(20, sequence + 1)
        tables.forEachIndexed { index, table ->
            val e = entries[table.type] ?: return@forEachIndexed
            val at = 28 + index * 16
            out.putU32(at + 4, e[0])
            out.putU32(at + 8, e[1])
            out.putU32(at + 12, e[2])
        }
        return out
    }

    private class Laid(val type: Int, val head: Int, val dataIndexes: IntArray, val chunks: List<List<ByteArray>>, val hasRows: Boolean)

    /** Arquivo só com as páginas em uso, no mesmo desenho de um export novo (sem as páginas órfãs de edições anteriores). */
    fun compact(): ByteArray = build(pageLen, tables.map { it.type to it.rows }, bytes.u32(16), sequence + 1, bytes.copyOf(pageLen))

    companion object {
        /**
         * Monta um arquivo inteiro do zero: página 0 (cabeçalho), depois, para cada tabela na ordem dada, o índice e as
         * páginas de dados (ou o índice e uma página zerada, se não há linhas) e, por fim, os números reservados de
         * "página vazia", que não existem fisicamente. [header0] fornece os bytes da página 0 que não conhecemos.
         */
        fun build(pageLen: Int, tables: List<Pair<Int, List<ByteArray>>>, unknown16: Int, sequence: Int, header0: ByteArray = ByteArray(pageLen)): ByteArray {
            val pages = ArrayList<ByteArray>()
            pages += header0.copyOf(pageLen)
            class T(val type: Int, val head: Int, val data: IntArray, val chunks: List<List<ByteArray>>)
            val laid = ArrayList<T>()
            var next = 1
            for ((type, rows) in tables) {
                val head = next++
                val chunks = if (rows.isEmpty()) emptyList() else Pages.paginate(rows, pageLen)
                val data = IntArray(chunks.size) { next + it }
                next += chunks.size
                if (chunks.isEmpty()) next++
                laid += T(type, head, data, chunks)
            }
            var reserved = next
            val dir = ArrayList<IntArray>()
            for (t in laid) {
                val has = t.chunks.isNotEmpty()
                val candidate = if (has) reserved++ else t.head + 1
                pages += Pages.indexPage(pageLen, t.head, t.type, if (has) t.data.first() else NO_NEXT_PAGE, 1)
                if (has) t.chunks.forEachIndexed { n, chunk -> pages += Pages.dataPage(pageLen, t.data[n], t.type, if (n == t.chunks.lastIndex) candidate else t.data[n + 1], sequence, chunk) }
                else pages += ByteArray(pageLen)
                dir += intArrayOf(t.type, candidate, t.head, if (has) t.data.last() else t.head)
            }
            val out = ByteArray(pages.size * pageLen)
            pages.forEachIndexed { n, page -> page.copyInto(out, n * pageLen) }
            out.fill(0, 0, 28 + dir.size * 16)
            out.putU32(4, pageLen)
            out.putU32(8, dir.size)
            out.putU32(12, reserved)
            out.putU32(16, unknown16)
            out.putU32(20, sequence)
            dir.forEachIndexed { n, d -> d.forEachIndexed { k, v -> out.putU32(28 + n * 16 + k * 4, v) } }
            return out
        }

        fun parse(bytes: ByteArray): RawPdb {
            if (bytes.size < 28 || bytes.size > MAX_FILE_BYTES) throw PdbFormatException("E_PDB_SIZE", "tamanho de export.pdb fora do esperado: ${bytes.size}")
            val pageLen = bytes.u32(4)
            val numTables = bytes.u32(8)
            if (pageLen < 512 || pageLen > 65536 || pageLen % 4 != 0 || numTables < 1 || numTables > 64) {
                throw PdbFormatException("E_PDB_HEADER", "cabeçalho de export.pdb inesperado (página $pageLen, tabelas $numTables)")
            }
            if (28L + numTables * 16L > pageLen || bytes.size % pageLen != 0) throw PdbFormatException("E_PDB_HEADER", "export.pdb não é múltiplo da página")
            val warnings = ArrayList<String>()
            val tables = ArrayList<RawTable>()
            for (t in 0 until numTables) {
                val at = 28 + t * 16
                tables += readTable(bytes, pageLen, bytes.u32(at), bytes.u32(at + 4), bytes.u32(at + 8), bytes.u32(at + 12), warnings)
            }
            return RawPdb(bytes, pageLen, bytes.u32(12), bytes.u32(20), tables, warnings)
        }

        private fun readTable(bytes: ByteArray, pageLen: Int, type: Int, empty: Int, first: Int, last: Int, warnings: MutableList<String>): RawTable {
            val rows = ArrayList<ByteArray>()
            val pages = ArrayList<Int>()
            val seen = HashSet<Int>()
            var index = first
            var guard = 0
            while (guard++ < MAX_PAGES && seen.add(index)) {
                val off = index.toLong() * pageLen
                if (index < 0 || off + pageLen > bytes.size) {
                    warnings += "tabela $type: página $index fora do arquivo"
                    break
                }
                val page = off.toInt()
                if (bytes.u32(page + 8) != type) {
                    warnings += "tabela $type: página $index é de outro tipo"
                    break
                }
                pages += index
                readPageRows(bytes, page, pageLen, type, rows)
                if (index == last) break
                val next = bytes.u32(page + 12)
                if (next == index || next == NO_NEXT_PAGE || next == LEGACY_NO_NEXT_PAGE) break
                index = next
            }
            if (pages.lastOrNull() != last) warnings += "tabela $type: a cadeia não chega à última página ($last)"
            return RawTable(type, empty, first, last, rows, pages)
        }

        private fun readPageRows(bytes: ByteArray, page: Int, pageLen: Int, type: Int, out: MutableList<ByteArray>) {
            val packed = bytes.u32(page + 24)
            val numOffsets = packed and 0x1FFF
            val flags = (packed shr 24) and 0xFF
            if (numOffsets == 0 || (flags and 0x40) != 0) return
            val groups = (numOffsets - 1) / 16 + 1
            // Os grupos de 16 posições se enchem do fim da página para trás; só o último (parcial) é menor que 36 bytes:
            // o índice inteiro ocupa 2 bytes por posição mais 4 por grupo (o mesmo que o campo `free` das páginas reais).
            val heapLimit = pageLen - (2 * numOffsets + 4 * groups) - PAGE_HEADER_END
            if (heapLimit <= 0) return
            val offsets = IntArray(numOffsets)
            val present = BooleanArray(numOffsets)
            for (n in 0 until numOffsets) {
                val base = page + pageLen - (n / 16) * GROUP_BYTES
                offsets[n] = bytes.u16(base - (6 + 2 * (n % 16)))
                present[n] = (bytes.u16(base - 4) shr (n % 16)) and 1 == 1
            }
            val used = bytes.u16(page + 30)
            val heapEnd = if (used in 1..heapLimit) used else heapLimit
            val boundaries = offsets.filter { it in 0 until heapEnd }.distinct().sorted()
            for (n in 0 until numOffsets) {
                if (!present[n]) continue
                val start = offsets[n]
                if (start !in 0 until heapEnd) continue
                val heap = page + PAGE_HEADER_END
                val next = boundaries.firstOrNull { it > start }
                val typed = Rows.extent(type, bytes, heap + start, heap + heapLimit)
                // Quando os próprios ponteiros da linha dizem onde ela acaba, vale isso (alinhado em 4): as páginas reais
                // têm sobras de heap entre as linhas (de edições antigas) que não pertencem a nenhuma linha. Só as
                // tabelas de formato desconhecido (colunas, histórico) usam a distância até a linha seguinte.
                // O campo `used` das páginas reais editadas pelo rekordbox nem sempre cobre o fim da última linha (visto em artistas e
                // gêneros reais), então, quando o tamanho é conhecido, o limite é o início do índice da página.
                val size = if (typed != null) align4(typed).coerceAtMost(heapLimit - start) else ((next ?: heapEnd) - start).coerceAtMost(heapEnd - start)
                if (size <= 0) continue
                out += bytes.copyOfRange(heap + start, heap + start + size)
            }
        }
    }
}

fun align4(n: Int): Int = (n + 3) and 3.inv()

internal fun ByteArray.u8(at: Int): Int = if (at < 0 || at >= size) 0 else this[at].toInt() and 0xFF
internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
internal fun ByteArray.u32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)
internal fun ByteArray.putU16(at: Int, v: Int) {
    this[at] = v.toByte()
    this[at + 1] = (v shr 8).toByte()
}
internal fun ByteArray.putU32(at: Int, v: Int) {
    putU16(at, v)
    putU16(at + 2, v shr 16)
}

/** Montagem de páginas no desenho dos exports reais. */
internal object Pages {
    private const val EMPTY_INDEX_ENTRY = 0x1FFFFFF8
    private val SHIFTED_TYPES = setOf(PdbType.TRACKS, PdbType.ARTISTS, PdbType.ALBUMS)

    fun capacity(pageLen: Int, count: Int): Int {
        val groups = if (count <= 0) 1 else (count - 1) / 16 + 1
        return pageLen - PAGE_HEADER_END - groups * GROUP_BYTES
    }

    fun paginate(rows: List<ByteArray>, pageLen: Int): List<List<ByteArray>> {
        val chunks = ArrayList<List<ByteArray>>()
        var bucket = ArrayList<ByteArray>()
        var used = 0
        for (row in rows) {
            val size = align4(row.size)
            if (size > capacity(pageLen, 1)) throw PdbFormatException("E_PDB_ROW_TOO_BIG", "uma linha de ${row.size} bytes não cabe numa página de $pageLen")
            if (bucket.isNotEmpty() && used + size > capacity(pageLen, bucket.size + 1)) {
                chunks += bucket
                bucket = ArrayList()
                used = 0
            }
            bucket += row
            used += size
        }
        if (bucket.isNotEmpty()) chunks += bucket
        return chunks
    }

    /** Página de índice (`0x64`) sem entradas: o que os exports reais têm nas tabelas sem páginas indexadas. */
    fun indexPage(pageLen: Int, index: Int, type: Int, nextPage: Int, sequence: Int): ByteArray {
        val p = ByteArray(pageLen)
        p.putU32(4, index)
        p.putU32(8, type)
        p.putU32(12, nextPage)
        p.putU32(16, sequence)
        p.putU32(24, 0x64 shl 24)
        p.putU16(32, 0x1FFF)
        p.putU16(34, 0x1FFF)
        p.putU16(36, (pageLen - 80) / 4)
        p.putU16(38, 0)
        p.putU32(40, index)
        p.putU32(44, nextPage)
        p.putU32(48, 0x03FFFFFF)
        p.putU16(56, 0)
        p.putU16(58, 0x1FFF)
        var at = 60
        while (at + 4 <= pageLen - 20) {
            p.putU32(at, EMPTY_INDEX_ENTRY)
            at += 4
        }
        return p
    }

    fun dataPage(pageLen: Int, index: Int, type: Int, nextPage: Int, sequence: Int, rows: List<ByteArray>): ByteArray {
        val page = ByteArray(pageLen)
        var cursor = PAGE_HEADER_END
        val offsets = IntArray(rows.size)
        rows.forEachIndexed { slot, row ->
            row.copyInto(page, cursor)
            // `index_shift` vale sempre 0x20 × a posição da linha na página (conferido em 8.300 linhas reais).
            if (type in SHIFTED_TYPES) page.putU16(cursor + 2, 0x20 * slot)
            offsets[slot] = cursor - PAGE_HEADER_END
            cursor += align4(row.size)
        }
        val groups = if (rows.isEmpty()) 0 else (rows.size - 1) / 16 + 1
        for (g in 0 until groups) {
            val base = pageLen - g * GROUP_BYTES
            var mask = 0
            for (r in 0 until 16) {
                val n = g * 16 + r
                if (n >= rows.size) break
                mask = mask or (1 shl r)
                page.putU16(base - (6 + 2 * r), offsets[n])
            }
            page.putU16(base - 4, mask) // linhas presentes
            page.putU16(base - 2, mask) // linhas tocadas na última transação
        }
        val used = cursor - PAGE_HEADER_END
        page.putU32(4, index)
        page.putU32(8, type)
        page.putU32(12, nextPage)
        page.putU32(16, sequence)
        page.putU32(24, rows.size or (rows.size shl 13) or (FLAG_DATA_PAGE shl 24))
        page.putU16(28, pageLen - PAGE_HEADER_END - used - (2 * rows.size + 4 * groups))
        page.putU16(30, used)
        page.putU16(32, rows.size)
        return page
    }
}
