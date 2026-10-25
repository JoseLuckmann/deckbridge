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
const val NO_NEXT_PAGE = 0x00FFFFFF
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
    /**
     * Artistas e álbuns: true se o arquivo mostra `index_shift` = 0x20 × posição da linha na página em todas as linhas.
     * Só então o gravador recalcula esse campo ao remontar a tabela; senão, as linhas passam como vieram.
     */
    val positionalShift: Boolean = false,
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
     * As demais ficam como estão. O número de sequência do banco sobe um, e as páginas novas levam o valor anterior,
     * como descreve a especificação.
     */
    fun write(replaced: Map<Int, List<ByteArray>>): ByteArray {
        if (replaced.isEmpty()) return bytes.copyOf()
        for (type in replaced.keys) if (table(type) == null) throw PdbFormatException("E_PDB_TABLE_MISSING", "o banco não tem a tabela $type")
        val extra = ArrayList<ByteArray>()
        var nextIndex = pageCount
        val entries = HashMap<Int, IntArray>() // tipo → (primeira, última)
        for (table in tables) {
            val rows = replaced[table.type] ?: continue
            val head = headPage(table, nextIndex)
            val firstIndex = nextIndex
            extra += head
            nextIndex++
            val chunks = paginate(rows)
            val dataIndexes = IntArray(chunks.size) { nextIndex + it }
            nextIndex += chunks.size
            head.putU32(12, dataIndexes.first())
            chunks.forEachIndexed { n, chunk ->
                val next = if (n == chunks.lastIndex) NO_NEXT_PAGE else dataIndexes[n + 1]
                extra += dataPage(dataIndexes[n], table.type, next, sequence, chunk, table.positionalShift)
            }
            entries[table.type] = intArrayOf(firstIndex, dataIndexes.last())
        }
        val out = ByteArray(bytes.size + extra.size * pageLen)
        bytes.copyInto(out)
        extra.forEachIndexed { n, page -> page.copyInto(out, bytes.size + n * pageLen) }
        out.putU32(12, nextIndex)
        out.putU32(20, sequence + 1)
        tables.forEachIndexed { index, table ->
            val e = entries[table.type] ?: return@forEachIndexed
            val at = 28 + index * 16
            out.putU32(at + 8, e[0])
            out.putU32(at + 12, e[1])
        }
        return out
    }

    /** Cabeça da cadeia: a original, se estava sem linhas (padrão do rekordbox), ou uma página vazia. */
    private fun headPage(table: RawTable, index: Int): ByteArray {
        val page = ByteArray(pageLen)
        val first = table.firstPage
        val off = first.toLong() * pageLen
        val original = if (first >= 0 && off + pageLen <= bytes.size) bytes.copyOfRange(off.toInt(), off.toInt() + pageLen) else null
        if (original != null && original.u32(8) == table.type && hasNoRows(original)) {
            original.copyInto(page)
        } else {
            page.putU32(8, table.type)
            page.putU32(16, sequence)
            page.putU32(24, FLAG_DATA_PAGE shl 24)
            page.putU16(28, pageLen - PAGE_HEADER_END)
        }
        page.putU32(4, index)
        return page
    }

    private fun hasNoRows(page: ByteArray): Boolean {
        val packed = page.u32(24)
        val numRows = (packed shr 13) and 0x7FF
        val flags = (packed shr 24) and 0xFF
        return numRows == 0 || (flags and 0x40) != 0
    }

    private fun paginate(rows: List<ByteArray>): List<List<ByteArray>> {
        val chunks = ArrayList<List<ByteArray>>()
        var bucket = ArrayList<ByteArray>()
        var used = 0
        fun capacity(count: Int): Int {
            val groups = if (count <= 0) 1 else (count - 1) / 16 + 1
            return pageLen - PAGE_HEADER_END - groups * GROUP_BYTES
        }
        for (row in rows) {
            val size = align4(row.size)
            if (size > capacity(1)) throw PdbFormatException("E_PDB_ROW_TOO_BIG", "uma linha de ${row.size} bytes não cabe numa página de $pageLen")
            if (bucket.isNotEmpty() && used + size > capacity(bucket.size + 1)) {
                chunks += bucket
                bucket = ArrayList()
                used = 0
            }
            bucket += row
            used += size
        }
        if (bucket.isNotEmpty() || chunks.isEmpty()) chunks += bucket
        return chunks
    }

    private fun dataPage(index: Int, type: Int, next: Int, sequence: Int, rows: List<ByteArray>, positional: Boolean): ByteArray {
        val page = ByteArray(pageLen)
        var cursor = PAGE_HEADER_END
        val offsets = IntArray(rows.size)
        rows.forEachIndexed { n, row ->
            row.copyInto(page, cursor)
            if (positional) page.putU16(cursor + 2, 0x20 * n)
            offsets[n] = cursor - PAGE_HEADER_END
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
            page.putU16(base - 4, mask)
        }
        val used = cursor - PAGE_HEADER_END
        page.putU32(4, index)
        page.putU32(8, type)
        page.putU32(12, next)
        page.putU32(16, sequence)
        page.putU32(24, rows.size or (rows.size shl 13) or (FLAG_DATA_PAGE shl 24))
        page.putU16(28, pageLen - PAGE_HEADER_END - groups * GROUP_BYTES - used)
        page.putU16(30, used)
        return page
    }

    /** Arquivo só com as páginas em uso (cabeçalho + as cadeias de cada tabela), sem as órfãs de edições anteriores. */
    fun compact(): ByteArray {
        val grown = write(tables.associate { it.type to it.rows })
        val reparsed = parse(grown)
        val keep = ArrayList<Int>()
        keep += 0
        val remap = HashMap<Int, Int>()
        remap[0] = 0
        for (table in reparsed.tables) for (p in table.pages) {
            remap[p] = keep.size
            keep += p
        }
        val out = ByteArray(keep.size * pageLen)
        keep.forEachIndexed { n, old -> grown.copyInto(out, n * pageLen, old * pageLen, old * pageLen + pageLen) }
        keep.forEachIndexed { n, old ->
            if (old == 0) return@forEachIndexed
            val base = n * pageLen
            out.putU32(base + 4, n)
            out.putU32(base + 12, remap[out.u32(base + 12)] ?: NO_NEXT_PAGE)
        }
        out.putU32(12, keep.size)
        reparsed.tables.forEachIndexed { index, table ->
            val at = 28 + index * 16
            out.putU32(at + 8, remap.getValue(table.firstPage))
            out.putU32(at + 12, remap.getValue(table.lastPage))
        }
        return out
    }

    companion object {
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
            val shift = ShiftCheck()
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
                readPageRows(bytes, page, pageLen, type, rows, shift)
                if (index == last) break
                val next = bytes.u32(page + 12)
                if (next == index || next == NO_NEXT_PAGE) break
                index = next
            }
            if (pages.lastOrNull() != last) warnings += "tabela $type: a cadeia não chega à última página ($last)"
            return RawTable(type, empty, first, last, rows, pages, shift.any && shift.ok)
        }

        private class ShiftCheck {
            var any = false
            var ok = true
        }

        private fun readPageRows(bytes: ByteArray, page: Int, pageLen: Int, type: Int, out: MutableList<ByteArray>, shift: ShiftCheck) {
            val packed = bytes.u32(page + 24)
            val numOffsets = packed and 0x1FFF
            val flags = (packed shr 24) and 0xFF
            if (numOffsets == 0 || (flags and 0x40) != 0) return
            val groups = (numOffsets - 1) / 16 + 1
            val heapLimit = pageLen - groups * GROUP_BYTES - PAGE_HEADER_END
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
                // Até a próxima linha, quando há outra: preserva o enchimento do original. A última linha da página
                // leva só o tamanho que ela mesma declara, sem a sobra livre que vem depois.
                val size = when {
                    typed == null -> (next ?: heapEnd) - start
                    next != null -> maxOf(typed, next - start)
                    else -> align4(typed)
                }.coerceAtMost(heapEnd - start)
                if (size <= 0) continue
                if (type == PdbType.ARTISTS || type == PdbType.ALBUMS) {
                    shift.any = true
                    if (bytes.u16(heap + start + 2) != 0x20 * n) shift.ok = false
                }
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
