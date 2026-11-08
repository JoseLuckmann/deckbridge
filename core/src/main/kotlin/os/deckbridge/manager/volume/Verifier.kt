package os.deckbridge.manager.volume

import os.deckbridge.logic.parseExportPdb
import os.deckbridge.manager.pdb.NameRows
import os.deckbridge.manager.pdb.PdbFormatException
import os.deckbridge.manager.pdb.PdbType
import os.deckbridge.manager.pdb.PlaylistRows
import os.deckbridge.manager.pdb.RawPdb
import os.deckbridge.manager.pdb.TrackRow

/**
 * Confere o banco novo antes de ele ir para o pendrive. Devolve a lista de problemas; vazia quer dizer que passou.
 * Os leitores usados aqui são os nossos (o cru deste módulo e o do app); o terceiro, gerado da especificação aberta,
 * roda nos testes (`KaitaiCrossCheck`), não aqui.
 */
object Verifier {
    fun check(original: RawPdb, newBytes: ByteArray, expected: Map<Int, List<ByteArray>>): List<String> {
        val problems = ArrayList<String>()
        val fresh = try {
            RawPdb.parse(newBytes)
        } catch (e: PdbFormatException) {
            return listOf("o banco novo não é lido pelo leitor cru: ${e.message}")
        }
        if (fresh.pageLen != original.pageLen) problems += "o tamanho de página mudou"
        if (newBytes.size < original.bytes.size) problems += "o banco novo é menor que o original"
        else if (!original.bytes.copyOfRange(original.pageLen, original.bytes.size).contentEquals(newBytes.copyOfRange(original.pageLen, original.bytes.size))) {
            problems += "uma página do original foi alterada (só o cabeçalho pode mudar)"
        }
        if (fresh.tables.map { it.type } != original.tables.map { it.type }) problems += "a lista de tabelas mudou"
        val newWarnings = fresh.warnings.filter { it !in original.warnings }
        problems += newWarnings.map { "aviso novo de leitura: $it" }
        for (table in fresh.tables) {
            val before = original.table(table.type) ?: continue
            val want = expected[table.type]
            if (want == null) {
                if (table.firstPage != before.firstPage || table.lastPage != before.lastPage) problems += "a tabela ${table.type} não devia ter mudado"
                if (!sameRows(table.type, before.rows, table.rows)) problems += "as linhas da tabela ${table.type} mudaram sem ter sido pedido"
            } else if (!sameRows(table.type, want, table.rows)) {
                problems += "a tabela ${table.type} gravada difere do plano (${table.rows.size} linhas lidas, ${want.size} planejadas)"
            }
        }
        val old = parseExportPdb(newBytes)
        val db = old.database
        if (db == null) problems += "o leitor do app recusou o banco novo: ${old.error}"
        else {
            val wantTracks = (expected[PdbType.TRACKS] ?: original.table(PdbType.TRACKS)?.rows ?: emptyList()).size
            if (db.tracks.size != wantTracks) problems += "o leitor do app viu ${db.tracks.size} faixas, o plano tem $wantTracks"
        }
        val before = integrity { original.table(it)?.rows ?: emptyList() }
        val after = integrity { fresh.table(it)?.rows ?: emptyList() }
        problems += (after - before).map { "referência quebrada: $it" }
        return problems
    }

    private val SHIFTED = setOf(0, 2, 3)

    fun sameRows(type: Int, a: List<ByteArray>, b: List<ByteArray>): Boolean = a.size == b.size && a.indices.all { sameRow(type, a[it], b[it]) }

    /**
     * Como [sameRow], mas nas tabelas de faixas, artistas e álbuns ignora o `index_shift` (bytes 2 e 3), que vale
     * 0x20 × a posição da linha na página e por isso muda quando a tabela é remontada.
     */
    fun sameRow(type: Int, a: ByteArray, b: ByteArray): Boolean {
        if (type !in SHIFTED || a.size < 4 || b.size < 4) return sameRow(a, b)
        val x = a.copyOf().also { it[2] = 0; it[3] = 0 }
        val y = b.copyOf().also { it[2] = 0; it[3] = 0 }
        return sameRow(x, y)
    }

    /** Iguais a menos de zeros de enchimento no fim (a página alinha as linhas em 4 bytes). */
    fun sameRow(a: ByteArray, b: ByteArray): Boolean {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = if (i < a.size) a[i] else 0
            val y = if (i < b.size) b[i] else 0
            if (x != y) return false
        }
        return true
    }

    /** Referências entre tabelas. Devolve descrições; a conferência só reclama das que não existiam antes. */
    fun integrity(tables: (Int) -> List<ByteArray>): Set<String> {
        val out = LinkedHashSet<String>()
        val artists = tables(PdbType.ARTISTS).map { NameRows.artistId(it) }.toSet()
        val albums = tables(PdbType.ALBUMS).map { NameRows.albumId(it) }.toSet()
        val genres = tables(PdbType.GENRES).map { NameRows.id(it) }.toSet()
        val keys = tables(PdbType.KEYS).map { NameRows.id(it) }.toSet()
        val labels = tables(PdbType.LABELS).map { NameRows.id(it) }.toSet()
        val trackRows = tables(PdbType.TRACKS).mapNotNull(TrackRow::parse)
        val ids = HashSet<Int>()
        for (t in trackRows) {
            if (!ids.add(t.id)) out += "faixa ${t.id} repetida"
            fun ref(label: String, id: Int, set: Set<Int>, present: Boolean) {
                if (present && id != 0 && id !in set) out += "faixa ${t.id}: $label $id não existe"
            }
            ref("artista", t.artistId, artists, true)
            ref("álbum", t.albumId, albums, true)
            ref("gênero", t.genreId, genres, true)
            ref("tom", t.keyId, keys, true)
            ref("selo", t.labelId, labels, true)
        }
        val tree = tables(PdbType.PLAYLIST_TREE)
        val playlistIds = HashSet<Int>()
        for (p in tree) if (!playlistIds.add(PlaylistRows.id(p))) out += "playlist ${PlaylistRows.id(p)} repetida"
        for (p in tree) {
            val parent = PlaylistRows.parent(p)
            if (parent != 0 && parent !in playlistIds) out += "playlist ${PlaylistRows.id(p)}: pasta $parent não existe"
            var cursor = parent
            val seen = HashSet<Int>()
            while (cursor != 0 && seen.add(cursor)) cursor = tree.firstOrNull { PlaylistRows.id(it) == cursor }?.let(PlaylistRows::parent) ?: 0
            if (cursor != 0) out += "playlist ${PlaylistRows.id(p)}: ciclo de pastas"
        }
        for (e in tables(PdbType.PLAYLIST_ENTRIES)) {
            if (PlaylistRows.entryTrack(e) !in ids) out += "item da playlist ${PlaylistRows.entryPlaylist(e)}: faixa ${PlaylistRows.entryTrack(e)} não existe"
            if (PlaylistRows.entryPlaylist(e) !in playlistIds) out += "item de uma playlist ${PlaylistRows.entryPlaylist(e)} que não existe"
        }
        return out
    }
}
