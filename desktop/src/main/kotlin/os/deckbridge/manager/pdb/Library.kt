package os.deckbridge.manager.pdb

data class TrackItem(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val key: String,
    val label: String,
    val comment: String,
    val bpm: Double,
    val year: Int,
    val rating: Int,
    val colorId: Int,
    val trackNumber: Int,
    val durationSeconds: Int,
    val bitrate: Int,
    val sampleRate: Int,
    val filePath: String,
    val fileName: String,
    val analyzePath: String,
)

data class PlaylistItem(val id: Int, val parentId: Int, val name: String, val folder: Boolean, val sort: Int)

/** Leitura decodificada das tabelas, para a tela e para as conferências. Não guarda os bytes. */
class LibraryView(
    val tracks: List<TrackItem>,
    val playlists: List<PlaylistItem>,
    /** Faixas de cada playlist, na ordem de `entry_index`. */
    val entries: Map<Int, List<Int>>,
    val colors: Map<Int, String>,
    val artists: Map<Int, String>,
    val albums: Map<Int, String>,
    val genres: Map<Int, String>,
    val keys: Map<Int, String>,
    val labels: Map<Int, String>,
) {
    private val byId = tracks.associateBy { it.id }
    fun track(id: Int): TrackItem? = byId[id]

    fun children(parent: Int): List<PlaylistItem> = playlists.filter { it.parentId == parent }.sortedWith(compareBy({ it.sort }, { it.id }))

    companion object {
        fun of(tables: (Int) -> List<ByteArray>): LibraryView {
            val artists = tables(PdbType.ARTISTS).associate { NameRows.artistId(it) to NameRows.artistName(it) }
            val albums = tables(PdbType.ALBUMS).associate { NameRows.albumId(it) to NameRows.albumName(it) }
            val genres = tables(PdbType.GENRES).associate { NameRows.id(it) to NameRows.simpleName(it) }
            val labels = tables(PdbType.LABELS).associate { NameRows.id(it) to NameRows.simpleName(it) }
            val keys = tables(PdbType.KEYS).associate { NameRows.id(it) to NameRows.keyName(it) }
            val colors = tables(PdbType.COLORS).associate { NameRows.colorId(it) to NameRows.colorName(it) }
            val tracks = tables(PdbType.TRACKS).mapNotNull(TrackRow::parse).map {
                TrackItem(
                    id = it.id,
                    title = it.title,
                    artist = artists[it.artistId].orEmpty(),
                    album = albums[it.albumId].orEmpty(),
                    genre = genres[it.genreId].orEmpty(),
                    key = keys[it.keyId].orEmpty(),
                    label = labels[it.labelId].orEmpty(),
                    comment = it.comment,
                    bpm = it.tempo / 100.0,
                    year = it.year,
                    rating = it.rating,
                    colorId = it.colorId,
                    trackNumber = it.trackNumber,
                    durationSeconds = it.duration,
                    bitrate = it.bitrate,
                    sampleRate = it.sampleRate,
                    filePath = it.filePath,
                    fileName = it.fileName,
                    analyzePath = it.analyzePath,
                )
            }
            val playlists = tables(PdbType.PLAYLIST_TREE).map {
                PlaylistItem(PlaylistRows.id(it), PlaylistRows.parent(it), PlaylistRows.name(it), PlaylistRows.folder(it), PlaylistRows.sort(it))
            }
            val entries = tables(PdbType.PLAYLIST_ENTRIES)
                .groupBy { PlaylistRows.entryPlaylist(it) }
                .mapValues { (_, rows) -> rows.sortedBy { PlaylistRows.entryIndex(it) }.map { PlaylistRows.entryTrack(it) } }
            return LibraryView(tracks, playlists, entries, colors, artists, albums, genres, keys, labels)
        }
    }
}

class EditException(message: String) : Exception(message)

/** Campos de faixa editáveis à mão. Null deixa o campo como está; texto vazio limpa a referência. */
data class TrackPatch(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val key: String? = null,
    val label: String? = null,
    val comment: String? = null,
    val year: Int? = null,
    val rating: Int? = null,
    val colorId: Int? = null,
    val trackNumber: Int? = null,
) {
    val isEmpty get() = this == TrackPatch()
}

sealed interface Edit {
    data class SetTracks(val trackIds: List<Int>, val patch: TrackPatch) : Edit
    data class CreatePlaylist(val id: Int, val parentId: Int, val name: String, val folder: Boolean) : Edit
    data class RenamePlaylist(val id: Int, val name: String) : Edit
    data class DeletePlaylist(val id: Int) : Edit
    data class MovePlaylist(val id: Int, val newParentId: Int, val index: Int) : Edit
    data class AddToPlaylist(val id: Int, val trackIds: List<Int>) : Edit
    data class RemoveFromPlaylist(val id: Int, val trackIds: List<Int>) : Edit
    data class ReorderPlaylist(val id: Int, val order: List<Int>) : Edit
}

/**
 * Aplica edições às linhas em memória. O arquivo original nunca é alterado: [changedTables] diz quais tabelas ficaram
 * diferentes e com que linhas, para [RawPdb.write] montar o banco novo.
 */
class PdbEditor(private val raw: RawPdb) {
    private val rows = HashMap<Int, MutableList<ByteArray>>()

    /** Linhas de nome criadas nesta sessão (por tipo): se ficarem sem uso, saem também. */
    private val created = HashMap<Int, MutableSet<Int>>()

    private fun rowsOf(type: Int): MutableList<ByteArray> = rows.getOrPut(type) { (raw.table(type)?.rows ?: emptyList()).toMutableList() }

    private fun requireTable(type: Int, what: String) {
        if (raw.table(type) == null) throw EditException("O banco não tem a tabela de $what; não dá para editar isso neste pendrive.")
    }

    fun view(): LibraryView = LibraryView.of { rows[it] ?: raw.table(it)?.rows ?: emptyList() }

    fun changedTables(): Map<Int, List<ByteArray>> {
        val out = LinkedHashMap<Int, List<ByteArray>>()
        for ((type, list) in rows) {
            val original = raw.table(type)?.rows ?: emptyList()
            val same = original.size == list.size && original.indices.all { original[it].contentEquals(list[it]) }
            if (!same) out[type] = list.toList()
        }
        return out
    }

    fun apply(edit: Edit) {
        when (edit) {
            is Edit.SetTracks -> setTracks(edit)
            is Edit.CreatePlaylist -> createPlaylist(edit)
            is Edit.RenamePlaylist -> renamePlaylist(edit)
            is Edit.DeletePlaylist -> deletePlaylist(edit.id)
            is Edit.MovePlaylist -> movePlaylist(edit)
            is Edit.AddToPlaylist -> addToPlaylist(edit)
            is Edit.RemoveFromPlaylist -> removeFromPlaylist(edit)
            is Edit.ReorderPlaylist -> reorderPlaylist(edit)
        }
    }

    // ---- faixas -----------------------------------------------------------------------------------------------

    private fun trackIndex(id: Int): Int {
        val list = rowsOf(PdbType.TRACKS)
        val at = list.indexOfFirst { it.u32(72) == id }
        if (at < 0) throw EditException("A faixa $id não está no banco.")
        return at
    }

    private fun setTracks(edit: Edit.SetTracks) {
        val patch = edit.patch
        if (patch.isEmpty) return
        patch.rating?.let { if (it !in 0..5) throw EditException("A nota vai de 0 a 5.") }
        patch.year?.let { if (it !in 0..9999) throw EditException("Ano fora do intervalo.") }
        patch.trackNumber?.let { if (it < 0) throw EditException("Número da faixa negativo.") }
        patch.colorId?.let { id ->
            if (id != 0 && rowsOf(PdbType.COLORS).none { NameRows.colorId(it) == id }) throw EditException("A cor $id não existe neste banco.")
        }
        val before = HashSet<Int>()
        val list = rowsOf(PdbType.TRACKS)
        for (id in edit.trackIds.distinct()) {
            val at = trackIndex(id)
            val row = TrackRow.parse(list[at]) ?: throw EditException("A linha da faixa $id está malformada.")
            before += listOf(row.artistId, row.albumId, row.genreId, row.keyId, row.labelId)
            patch.title?.let { row.title = it }
            patch.comment?.let { row.comment = it }
            patch.year?.let { row.year = it }
            patch.rating?.let { row.rating = it }
            patch.colorId?.let { row.colorId = it }
            patch.trackNumber?.let { row.trackNumber = it }
            patch.artist?.let { row.artistId = nameId(PdbType.ARTISTS, it.trim(), 0) }
            patch.genre?.let { row.genreId = nameId(PdbType.GENRES, it.trim(), 0) }
            patch.key?.let { row.keyId = nameId(PdbType.KEYS, it.trim(), 0) }
            patch.label?.let { row.labelId = nameId(PdbType.LABELS, it.trim(), 0) }
            patch.album?.let { row.albumId = nameId(PdbType.ALBUMS, it.trim(), row.artistId) }
            list[at] = row.encode()
        }
        dropOrphans()
    }

    /** Id do nome nessa tabela, criando a linha se for novo. Vazio vira 0 (sem referência). */
    private fun nameId(type: Int, name: String, artistForAlbum: Int): Int {
        if (name.isEmpty()) return 0
        val what = when (type) {
            PdbType.ARTISTS -> "artistas"
            PdbType.ALBUMS -> "álbuns"
            PdbType.GENRES -> "gêneros"
            PdbType.KEYS -> "tons"
            else -> "selos"
        }
        requireTable(type, what)
        val list = rowsOf(type)
        fun idOf(row: ByteArray) = when (type) {
            PdbType.ALBUMS -> NameRows.albumId(row)
            PdbType.ARTISTS -> NameRows.artistId(row)
            else -> NameRows.id(row)
        }
        fun nameOf(row: ByteArray) = when (type) {
            PdbType.ARTISTS -> NameRows.artistName(row)
            PdbType.ALBUMS -> NameRows.albumName(row)
            PdbType.KEYS -> NameRows.keyName(row)
            else -> NameRows.simpleName(row)
        }
        list.firstOrNull { nameOf(it) == name }?.let { return idOf(it) }
        val id = (list.maxOfOrNull { idOf(it) } ?: 0) + 1
        created.getOrPut(type) { HashSet() } += id
        list += when (type) {
            PdbType.ARTISTS -> NameRows.artist(id, name, 0x20 * list.size)
            PdbType.ALBUMS -> NameRows.album(id, artistForAlbum, name, 0x20 * list.size)
            PdbType.KEYS -> NameRows.key(id, name)
            else -> NameRows.simple(id, name)
        }
        return id
    }

    /**
     * Remove nomes que ficaram sem uso por causa das edições (artista, álbum, gênero, tom, selo). Só mexe em linhas que o
     * banco original referenciava: o que já estava solto antes continua como estava.
     */
    private fun dropOrphans() {
        val tracks = rowsOf(PdbType.TRACKS).mapNotNull(TrackRow::parse)
        fun orphan(type: Int, id: (ByteArray) -> Int, used: Set<Int>) {
            if (raw.table(type) == null) return
            val removable = originalUsage(type) + (created[type] ?: emptySet())
            val list = rowsOf(type)
            val removed = list.filter { id(it) !in used && id(it) in removable }
            if (removed.isNotEmpty()) list.removeAll { r -> removed.any { it === r } }
        }
        orphan(PdbType.ALBUMS, NameRows::albumId, tracks.map { it.albumId }.toSet())
        val albumArtists = rowsOf(PdbType.ALBUMS).map { NameRows.albumArtistId(it) }
        val artistsUsed = HashSet<Int>().apply {
            tracks.forEach { addAll(listOf(it.artistId, it.composerId, it.remixerId, it.originalArtistId)) }
            addAll(albumArtists)
        }
        orphan(PdbType.ARTISTS, NameRows::artistId, artistsUsed)
        orphan(PdbType.GENRES, NameRows::id, tracks.map { it.genreId }.toSet())
        orphan(PdbType.KEYS, NameRows::id, tracks.map { it.keyId }.toSet())
        orphan(PdbType.LABELS, NameRows::id, tracks.map { it.labelId }.toSet())
    }

    private val usageCache = HashMap<Int, Set<Int>>()

    private fun originalUsage(type: Int): Set<Int> = usageCache.getOrPut(type) {
        val tracks = (raw.table(PdbType.TRACKS)?.rows ?: emptyList()).mapNotNull(TrackRow::parse)
        when (type) {
            PdbType.ALBUMS -> tracks.map { it.albumId }.toSet()
            PdbType.ARTISTS -> HashSet<Int>().apply {
                tracks.forEach { addAll(listOf(it.artistId, it.composerId, it.remixerId, it.originalArtistId)) }
                (raw.table(PdbType.ALBUMS)?.rows ?: emptyList()).forEach { add(NameRows.albumArtistId(it)) }
            }
            PdbType.GENRES -> tracks.map { it.genreId }.toSet()
            PdbType.KEYS -> tracks.map { it.keyId }.toSet()
            PdbType.LABELS -> tracks.map { it.labelId }.toSet()
            else -> emptySet()
        }
    }

    // ---- playlists --------------------------------------------------------------------------------------------

    private fun playlistAt(id: Int): Int {
        requireTable(PdbType.PLAYLIST_TREE, "playlists")
        val at = rowsOf(PdbType.PLAYLIST_TREE).indexOfFirst { PlaylistRows.id(it) == id }
        if (at < 0) throw EditException("A playlist $id não existe.")
        return at
    }

    private fun createPlaylist(edit: Edit.CreatePlaylist) {
        requireTable(PdbType.PLAYLIST_TREE, "playlists")
        val name = edit.name.trim()
        if (name.isEmpty()) throw EditException("A playlist precisa de um nome.")
        val tree = rowsOf(PdbType.PLAYLIST_TREE)
        if (tree.any { PlaylistRows.id(it) == edit.id }) throw EditException("O id ${edit.id} já está em uso.")
        if (edit.parentId != 0) {
            val parent = tree[playlistAt(edit.parentId)]
            if (!PlaylistRows.folder(parent)) throw EditException("Só uma pasta pode conter playlists.")
        }
        val siblings = tree.filter { PlaylistRows.parent(it) == edit.parentId }
        if (siblings.any { PlaylistRows.name(it) == name }) throw EditException("Já existe \"$name\" nessa pasta.")
        val sort = (siblings.maxOfOrNull { PlaylistRows.sort(it) } ?: -1) + 1
        tree += PlaylistRows.tree(edit.id, edit.parentId, name, edit.folder, sort, tree.firstOrNull())
    }

    private fun renamePlaylist(edit: Edit.RenamePlaylist) {
        val name = edit.name.trim()
        if (name.isEmpty()) throw EditException("A playlist precisa de um nome.")
        val tree = rowsOf(PdbType.PLAYLIST_TREE)
        val at = playlistAt(edit.id)
        val row = tree[at]
        if (tree.any { it !== row && PlaylistRows.parent(it) == PlaylistRows.parent(row) && PlaylistRows.name(it) == name }) {
            throw EditException("Já existe \"$name\" nessa pasta.")
        }
        tree[at] = PlaylistRows.tree(edit.id, PlaylistRows.parent(row), name, PlaylistRows.folder(row), PlaylistRows.sort(row), row)
    }

    private fun deletePlaylist(id: Int) {
        val tree = rowsOf(PdbType.PLAYLIST_TREE)
        playlistAt(id)
        val doomed = HashSet<Int>()
        fun collect(p: Int) {
            if (!doomed.add(p)) return
            tree.filter { PlaylistRows.parent(it) == p }.forEach { collect(PlaylistRows.id(it)) }
        }
        collect(id)
        tree.removeAll { PlaylistRows.id(it) in doomed }
        if (raw.table(PdbType.PLAYLIST_ENTRIES) != null) rowsOf(PdbType.PLAYLIST_ENTRIES).removeAll { PlaylistRows.entryPlaylist(it) in doomed }
    }

    private fun movePlaylist(edit: Edit.MovePlaylist) {
        val tree = rowsOf(PdbType.PLAYLIST_TREE)
        val at = playlistAt(edit.id)
        val moving = tree[at]
        if (edit.newParentId != 0) {
            val parent = tree[playlistAt(edit.newParentId)]
            if (!PlaylistRows.folder(parent)) throw EditException("Só uma pasta pode conter playlists.")
            var cursor = edit.newParentId
            val seen = HashSet<Int>()
            while (cursor != 0 && seen.add(cursor)) {
                if (cursor == edit.id) throw EditException("Uma pasta não pode ser movida para dentro dela mesma.")
                cursor = tree.firstOrNull { PlaylistRows.id(it) == cursor }?.let(PlaylistRows::parent) ?: 0
            }
        }
        val name = PlaylistRows.name(moving)
        if (tree.any { it !== moving && PlaylistRows.parent(it) == edit.newParentId && PlaylistRows.name(it) == name }) {
            throw EditException("Já existe \"$name\" na pasta de destino.")
        }
        val oldParent = PlaylistRows.parent(moving)
        val order = tree.filter { PlaylistRows.parent(it) == edit.newParentId && it !== moving }
            .sortedWith(compareBy({ PlaylistRows.sort(it) }, { PlaylistRows.id(it) })).toMutableList()
        val moved = PlaylistRows.tree(edit.id, edit.newParentId, name, PlaylistRows.folder(moving), 0, moving)
        order.add(edit.index.coerceIn(0, order.size), moved)
        renumber(tree, order)
        if (oldParent != edit.newParentId) {
            val rest = tree.filter { PlaylistRows.parent(it) == oldParent }.sortedWith(compareBy({ PlaylistRows.sort(it) }, { PlaylistRows.id(it) }))
            renumber(tree, rest)
        }
    }

    /** Dá sort 0..n-1 à ordem dada, substituindo as linhas na tabela (inclusive a que acabou de ser criada). */
    private fun renumber(tree: MutableList<ByteArray>, order: List<ByteArray>) {
        order.forEachIndexed { sort, row ->
            val id = PlaylistRows.id(row)
            val fresh = PlaylistRows.tree(id, PlaylistRows.parent(row), PlaylistRows.name(row), PlaylistRows.folder(row), sort, row)
            val at = tree.indexOfFirst { PlaylistRows.id(it) == id }
            if (at >= 0) tree[at] = fresh else tree += fresh
        }
    }

    private fun entriesOf(playlistId: Int): List<ByteArray> =
        rowsOf(PdbType.PLAYLIST_ENTRIES).filter { PlaylistRows.entryPlaylist(it) == playlistId }.sortedBy { PlaylistRows.entryIndex(it) }

    private fun requireTrackPlaylist(id: Int): Int {
        requireTable(PdbType.PLAYLIST_ENTRIES, "itens de playlist")
        val row = rowsOf(PdbType.PLAYLIST_TREE)[playlistAt(id)]
        if (PlaylistRows.folder(row)) throw EditException("Uma pasta não guarda faixas.")
        return id
    }

    /** O primeiro `entry_index` que o banco usa (0 ou 1): playlists novas seguem o mesmo. */
    private fun entryBase(): Int = rowsOf(PdbType.PLAYLIST_ENTRIES).minOfOrNull { PlaylistRows.entryIndex(it) }?.coerceAtMost(1) ?: 1

    private fun replaceEntries(playlistId: Int, trackIds: List<Int>) {
        val table = rowsOf(PdbType.PLAYLIST_ENTRIES)
        val base = entryBase()
        val firstAt = table.indexOfFirst { PlaylistRows.entryPlaylist(it) == playlistId }
        table.removeAll { PlaylistRows.entryPlaylist(it) == playlistId }
        val fresh = trackIds.mapIndexed { n, track -> PlaylistRows.entry(base + n, track, playlistId) }
        table.addAll(if (firstAt < 0) table.size else firstAt.coerceAtMost(table.size), fresh)
    }

    private fun requireTracks(ids: List<Int>) {
        val known = rowsOf(PdbType.TRACKS).map { it.u32(72) }.toSet()
        ids.firstOrNull { it !in known }?.let { throw EditException("A faixa $it não está no banco.") }
    }

    private fun addToPlaylist(edit: Edit.AddToPlaylist) {
        requireTrackPlaylist(edit.id)
        requireTracks(edit.trackIds)
        val current = entriesOf(edit.id).map { PlaylistRows.entryTrack(it) }
        val add = edit.trackIds.distinct().filter { it !in current }
        if (add.isEmpty()) return
        replaceEntries(edit.id, current + add)
    }

    private fun removeFromPlaylist(edit: Edit.RemoveFromPlaylist) {
        requireTrackPlaylist(edit.id)
        val gone = edit.trackIds.toSet()
        val current = entriesOf(edit.id).map { PlaylistRows.entryTrack(it) }
        if (current.none { it in gone }) return
        replaceEntries(edit.id, current.filter { it !in gone })
    }

    private fun reorderPlaylist(edit: Edit.ReorderPlaylist) {
        requireTrackPlaylist(edit.id)
        val current = entriesOf(edit.id).map { PlaylistRows.entryTrack(it) }
        if (edit.order.sorted() != current.sorted()) throw EditException("A nova ordem precisa ter exatamente as mesmas faixas da playlist.")
        if (edit.order == current) return
        replaceEntries(edit.id, edit.order)
    }
}
