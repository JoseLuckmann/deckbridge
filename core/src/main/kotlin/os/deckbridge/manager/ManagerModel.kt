package os.deckbridge.manager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.EditException
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.PdbEditor
import os.deckbridge.manager.pdb.PlaylistItem
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.volume.ApplyReport
import os.deckbridge.manager.volume.BackupRecord
import os.deckbridge.manager.volume.Backups
import os.deckbridge.manager.volume.LoadedVolume
import os.deckbridge.manager.volume.PDB_RELATIVE
import os.deckbridge.manager.volume.SafeWriter
import os.deckbridge.manager.volume.VolumeException
import os.deckbridge.manager.volume.Volumes
import os.deckbridge.manager.smart.QuickFilter
import os.deckbridge.manager.smart.Smart

/** Uma mudança na fila: a edição e o texto que a tela e o backup mostram. */
data class PendingChange(val edit: Edit, val text: String)

enum class Column(val title: String) { ID("#"), TITLE("Título"), ARTIST("Artista"), ALBUM("Álbum"), GENRE("Gênero"), KEY("Tom"), BPM("BPM"), RATING("Nota"), YEAR("Ano"), TIME("Tempo") }

data class Sort(val column: Column?, val ascending: Boolean = true)

data class Banner(val text: String, val error: Boolean)

/**
 * Estado do Library Manager. Abrir e editar não tocam no pendrive: as edições ficam na fila [pending] e a tela mostra o
 * resultado delas. Só [apply] grava, e passa por [SafeWriter].
 */
class ManagerModel(val backups: Backups = Backups()) {
    var volume by mutableStateOf<LoadedVolume?>(null)
        private set
    var view by mutableStateOf<LibraryView?>(null)
        private set
    var pending by mutableStateOf<List<PendingChange>>(emptyList())
        private set
    var banner by mutableStateOf<Banner?>(null)
    var selection by mutableStateOf<Set<Int>>(emptySet())
    var anchor by mutableStateOf<Int?>(null)

    /** null é a coleção inteira; senão, o id da playlist ou pasta aberta. */
    var openPlaylist by mutableStateOf<Int?>(null)
    var query by mutableStateOf("")
    var sort by mutableStateOf(Sort(Column.ARTIST))
    /** Filtro rápido ativo ("Sem análise", "Sem nota"…). */
    var filter by mutableStateOf(QuickFilter.ALL)

    /** Playlist alvo: com ela definida, adicionar uma faixa a ela é um toque. */
    var target by mutableStateOf<Int?>(null)
    var redoStack by mutableStateOf<List<PendingChange>>(emptyList())
        private set
    var busy by mutableStateOf<String?>(null)
    var lastReport by mutableStateOf<ApplyReport?>(null)

    private var editor: PdbEditor? = null

    val isOpen: Boolean get() = volume != null
    val dirty: Boolean get() = pending.isNotEmpty()

    fun open(root: File): Boolean = try {
        val vol = Volumes.open(root)
        volume = vol
        pending = emptyList()
        redoStack = emptyList()
        editor = PdbEditor(vol.raw, vol.root)
        view = editor!!.view()
        selection = emptySet()
        filter = QuickFilter.ALL
        if (target != null && view!!.playlists.none { it.id == target }) target = null
        anchor = null
        openPlaylist = null
        query = ""
        banner = if (vol.raw.warnings.isEmpty()) null else Banner("O banco foi lido com avisos: ${vol.raw.warnings.first()}", error = false)
        true
    } catch (e: VolumeException) {
        banner = Banner(e.message ?: e.code, error = true)
        false
    }

    private fun rebuild(): PdbEditor {
        val fresh = PdbEditor(volume!!.raw, volume!!.root)
        pending.forEach { fresh.apply(it.edit) }
        return fresh
    }

    /** Valida e enfileira. Em caso de recusa, a fila fica como estava e [banner] diz o motivo. */
    fun submit(edit: Edit, text: String): Boolean {
        val e = editor ?: return false
        return try {
            e.apply(edit)
            pending = pending + PendingChange(edit, text)
            redoStack = emptyList()
            view = e.view()
            true
        } catch (ex: EditException) {
            editor = rebuild()
            banner = Banner(ex.message ?: "Mudança recusada.", error = true)
            false
        }
    }

    /** Refaz a última mudança desfeita. Qualquer mudança nova esvazia a pilha de refazer. */
    fun redo(): Boolean {
        val next = redoStack.lastOrNull() ?: return false
        val keep = redoStack.dropLast(1)
        if (!submit(next.edit, next.text)) return false
        redoStack = keep
        return true
    }

    fun undo() {
        if (pending.isEmpty()) return
        redoStack = redoStack + pending.last()
        pending = pending.dropLast(1)
        editor = rebuild()
        view = editor!!.view()
        selection = selection.filter { view!!.track(it) != null }.toSet()
        if (openPlaylist != null && view!!.playlists.none { it.id == openPlaylist }) openPlaylist = null
    }

    fun discard() {
        if (volume == null) return
        pending = emptyList()
        redoStack = emptyList()
        editor = PdbEditor(volume!!.raw, volume!!.root)
        view = editor!!.view()
        if (openPlaylist != null && view!!.playlists.none { it.id == openPlaylist }) openPlaylist = null
    }

    /** Grade, cues e prévia de onda da faixa, já com as edições da fila (um ANLZ editado aparece aqui antes de ser gravado). */
    class TrackFilesView(val beats: List<os.deckbridge.manager.anlz.Beat>, val cues: List<os.deckbridge.manager.anlz.Cue>, val wave: ByteArray, val durationMs: Int, val error: String?)

    fun hasAnalysis(t: os.deckbridge.manager.pdb.TrackItem): Boolean {
        val rel = t.analyzePath.replace('\\', '/').trimStart('/')
        return rel.isNotEmpty() && editor?.files?.exists(rel) == true
    }

    fun trackFiles(t: os.deckbridge.manager.pdb.TrackItem): TrackFilesView {
        val files = editor?.files ?: return TrackFilesView(emptyList(), emptyList(), ByteArray(0), t.durationSeconds * 1000, "nada aberto")
        val rel = t.analyzePath.replace('\\', '/').trimStart('/')
        val datBytes = files.read(rel) ?: return TrackFilesView(emptyList(), emptyList(), ByteArray(0), t.durationSeconds * 1000, "A faixa não tem arquivo de análise no pendrive.")
        return try {
            val dat = os.deckbridge.manager.anlz.AnlzModel.parse(datBytes)
            val tail = rel.takeLast(3)
            val extRel = rel.dropLast(3) + if (tail == tail.uppercase()) "EXT" else "ext"
            val extBytes = files.read(extRel)
            val ext = extBytes?.let { os.deckbridge.manager.anlz.AnlzModel.parse(it) }
            val merged = os.deckbridge.logic.mergeAnalysis(os.deckbridge.logic.parseAnlz(datBytes), extBytes?.let { os.deckbridge.logic.parseAnlz(it) })
            TrackFilesView(os.deckbridge.manager.anlz.GridIo.read(dat), os.deckbridge.manager.anlz.CueIo.read(dat, ext).cues.sortedWith(compareBy({ it.hot == 0 }, { it.hot }, { it.timeMs })), merged.bluePreview, t.durationSeconds * 1000, null)
        } catch (e: os.deckbridge.manager.anlz.AnlzFormatException) {
            TrackFilesView(emptyList(), emptyList(), ByteArray(0), t.durationSeconds * 1000, "Arquivo de análise inválido: ${e.message}")
        }
    }

    /** Arrastar faixas para uma playlist (ou pasta: vai para nenhuma, só playlists recebem). */
    fun dropOnPlaylist(trackIds: List<Int>, playlistId: Int): Boolean {
        val p = playlist(playlistId) ?: return false
        if (p.folder) {
            banner = Banner("\"${p.name}\" é uma pasta. Solte a faixa numa playlist dentro dela.", error = true)
            return false
        }
        val have = view?.entries?.get(playlistId).orEmpty().toSet()
        val fresh = trackIds.filter { it !in have }
        if (fresh.isEmpty()) {
            banner = Banner("Essas faixas já estão em \"${p.name}\".", error = false)
            return false
        }
        return submit(Edit.AddToPlaylist(playlistId, fresh), "Adicionar ${fresh.size} faixa${if (fresh.size == 1) "" else "s"} a \"${p.name}\"")
    }

    /** Arrastar dentro de uma playlist: [moving] passa a ficar antes de [beforeTrackId] (ou no fim, se for null). */
    fun reorderInPlaylist(playlistId: Int, moving: List<Int>, beforeTrackId: Int?): Boolean {
        val p = playlist(playlistId) ?: return false
        val current = view?.entries?.get(playlistId).orEmpty()
        val rest = current.filter { it !in moving }
        val at = if (beforeTrackId == null) rest.size else rest.indexOf(beforeTrackId).let { if (it < 0) rest.size else it }
        val order = rest.take(at) + current.filter { it in moving } + rest.drop(at)
        if (order == current) return false
        return submit(Edit.ReorderPlaylist(playlistId, order), "Reordenar \"${p.name}\"")
    }

    /** Várias edições de uma vez (diálogos de importar, renomear e organizar). Para na primeira recusa. */
    fun submitAll(items: List<Pair<Edit, String>>): Int {
        var n = 0
        for ((edit, text) in items) {
            if (!submit(edit, text)) break
            n++
        }
        return n
    }

    fun compact(progress: (String) -> Unit = {}): ApplyReport? {
        val vol = volume ?: return null
        if (dirty) {
            banner = Banner("Grave ou descarte as mudanças da fila antes de compactar.", error = true)
            return null
        }
        return try {
            val report = SafeWriter.compact(vol, backups, vol.root, progress)
            lastReport = report
            open(vol.root)
            banner = Banner("Banco compactado: ${report.bytesBefore / 1024} KB → ${report.bytesAfter / 1024} KB. Cópia de segurança em ${report.backup.dir.path}", error = false)
            report
        } catch (ex: VolumeException) {
            banner = Banner(ex.message ?: ex.code, error = true)
            null
        }
    }

    /** Contagem de cada filtro rápido, para os números nos botões. */
    fun filterCounts(): Map<QuickFilter, Int> = view?.let { v -> Smart.counts(v, { hasAnalysis(it) }) } ?: emptyMap()

    /** Um toque: põe a faixa na playlist alvo, ou a tira se já estiver nela. Devolve o texto do que fez, ou null se não há alvo. */
    fun toggleInTarget(trackId: Int): String? {
        val id = target ?: return null
        val p = playlist(id) ?: return null
        val t = view?.track(trackId) ?: return null
        val has = trackId in view?.entries?.get(id).orEmpty()
        return if (has) {
            if (submit(Edit.RemoveFromPlaylist(id, listOf(trackId)), "Tirar \"${t.title}\" de \"${p.name}\"")) "Tirada de \"${p.name}\"" else null
        } else {
            if (submit(Edit.AddToPlaylist(id, listOf(trackId)), "Adicionar \"${t.title}\" a \"${p.name}\"")) "Adicionada a \"${p.name}\"" else null
        }
    }

    /** Cria uma playlist com as faixas que a lista mostra agora (busca e filtro incluídos). */
    fun saveListAsPlaylist(name: String): Int? {
        val ids = rows().map { it.id }
        if (ids.isEmpty()) {
            banner = Banner("A lista está vazia: não há o que salvar.", error = true)
            return null
        }
        val id = nextPlaylistId()
        if (!submit(Edit.CreatePlaylist(id, 0, name, false), "Criar playlist \"$name\"")) return null
        if (!submit(Edit.AddToPlaylist(id, ids), "Adicionar ${ids.size} faixa(s) a \"$name\"")) return null
        return id
    }

    /** Ordena a playlist [id] pelo critério. Não faz nada (e diz false) se já estiver nessa ordem. */
    fun sortPlaylist(id: Int, mode: Smart.SortMode): Boolean {
        val p = playlist(id) ?: return false
        val current = view?.entries?.get(id).orEmpty()
        val order = Smart.sorted(view ?: return false, current, mode)
        if (order == current) return false
        return submit(Edit.ReorderPlaylist(id, order), "Ordenar \"${p.name}\": ${mode.title}")
    }

    /** Apaga as playlists vazias (não pastas). Devolve quantas. */
    fun removeEmptyPlaylists(): Int {
        val v = view ?: return 0
        val empty = v.playlists.filter { !it.folder && v.entries[it.id].orEmpty().isEmpty() }
        var n = 0
        for (p in empty) if (submit(Edit.DeletePlaylist(p.id), "Apagar playlist vazia \"${p.name}\"")) n++
        return n
    }

    fun nextPlaylistId(): Int = (view?.playlists?.maxOfOrNull { it.id } ?: 0) + 1

    /** Faixas com alguma edição de campo na fila (marcadas na tabela). */
    fun editedTrackIds(): Set<Int> = pending.mapNotNull { it.edit as? Edit.SetTracks }.flatMap { it.trackIds }.toSet()

    fun playlist(id: Int): PlaylistItem? = view?.playlists?.firstOrNull { it.id == id }

    /** Ids de faixa que a lista mostra: coleção ou playlist (pasta mostra as playlists de dentro, sem repetir). */
    fun scopeTrackIds(): List<Int> {
        val v = view ?: return emptyList()
        val open = openPlaylist ?: return v.tracks.map { it.id }
        val p = playlist(open) ?: return v.tracks.map { it.id }
        if (!p.folder) return v.entries[open].orEmpty().filter { v.track(it) != null }
        val seen = LinkedHashSet<Int>()
        fun walk(id: Int) {
            v.children(id).forEach { c -> if (c.folder) walk(c.id) else v.entries[c.id].orEmpty().forEach { seen += it } }
        }
        walk(open)
        return seen.filter { v.track(it) != null }
    }

    /** A lista da tela: escopo, busca e ordenação. Dentro de uma playlist, `sort.column == null` é a ordem da playlist. */
    fun rows(): List<TrackItem> {
        val v = view ?: return emptyList()
        val q = Smart.parseQuery(query)
        var list = scopeTrackIds().mapNotNull { v.track(it) }
        if (!q.isEmpty) list = list.filter { Smart.matches(q, it) }
        if (filter != QuickFilter.ALL) list = Smart.apply(filter, v, { hasAnalysis(it) }, list)
        val column = sort.column ?: return list
        val cmp: Comparator<TrackItem> = when (column) {
            Column.ID -> compareBy { it.id }
            Column.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            Column.ARTIST -> compareBy<TrackItem, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            Column.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.album }
            Column.GENRE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.genre }
            Column.KEY -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.key }
            Column.BPM -> compareBy { it.bpm }
            Column.RATING -> compareBy { it.rating }
            Column.YEAR -> compareBy { it.year }
            Column.TIME -> compareBy { it.durationSeconds }
        }
        return list.sortedWith(if (sort.ascending) cmp else cmp.reversed())
    }

    fun click(id: Int, ctrl: Boolean, shift: Boolean, visible: List<TrackItem>) {
        val a = anchor
        selection = when {
            shift && a != null -> {
                val from = visible.indexOfFirst { it.id == a }
                val to = visible.indexOfFirst { it.id == id }
                if (from < 0 || to < 0) setOf(id) else visible.subList(minOf(from, to), maxOf(from, to) + 1).map { it.id }.toSet()
            }
            ctrl -> if (id in selection) selection - id else selection + id
            else -> setOf(id)
        }
        if (!shift) anchor = id
    }

    fun toggleSort(column: Column) {
        sort = if (sort.column == column) Sort(column, !sort.ascending) else Sort(column, true)
    }

    /** Grava a fila no pendrive (ou na cópia [target]). Bloqueante: a tela chama fora da thread de interface. */
    fun apply(target: File? = null, progress: (String) -> Unit = {}): ApplyReport? {
        val vol = volume ?: return null
        val e = editor ?: return null
        return try {
            val report = SafeWriter.apply(vol, e, pending.map { it.text }, backups, target ?: vol.root, progress)
            lastReport = report
            val reopen = target ?: vol.root
            // Reabre o volume já gravado: a tela passa a mostrar o que está de fato no arquivo.
            if (target == null) {
                open(reopen)
                banner = Banner("Gravado no pendrive. Cópia de segurança em ${report.backup.dir.path}", error = false)
            } else {
                banner = Banner("Gravado na cópia ${target.path}. O pendrive aberto não foi tocado.", error = false)
            }
            report
        } catch (ex: VolumeException) {
            banner = Banner(ex.message ?: ex.code, error = true)
            null
        }
    }

    fun restore(record: BackupRecord): Boolean {
        val vol = volume ?: return false
        return try {
            val result = backups.restore(record, vol.root)
            open(vol.root)
            val extra = buildList {
                if (result.movedBack > 0) add("${result.movedBack} arquivo(s) voltaram ao nome antigo")
                if (result.removedAnalysis > 0) add("${result.removedAnalysis} arquivo(s) de análise criados foram removidos")
                if (result.keptAudio.isNotEmpty()) add("${result.keptAudio.size} áudio(s) importados continuam no pendrive (nada de áudio é apagado)")
            }
            banner = Banner("Pendrive restaurado para o estado de ${record.created}." + if (extra.isEmpty()) "" else " " + extra.joinToString("; ") + ".", error = false)
            true
        } catch (ex: VolumeException) {
            banner = Banner(ex.message ?: ex.code, error = true)
            false
        }
    }

    fun backupList(): List<BackupRecord> = volume?.let { backups.list(it.root) } ?: emptyList()
}

/** Procura pendrives já exportados pelo rekordbox nos pontos de montagem comuns. */
object VolumeDetector {
    fun find(extraRoots: List<File> = emptyList()): List<File> {
        val user = System.getProperty("user.name").orEmpty()
        val roots = mutableListOf(File("/media/$user"), File("/media"), File("/run/media/$user"), File("/mnt"), File("/Volumes"))
        roots += File.listRoots().toList()
        roots += extraRoots
        val found = LinkedHashSet<File>()
        for (root in roots) {
            val children = root.listFiles { f -> f.isDirectory } ?: continue
            for (c in children) if (File(c, PDB_RELATIVE).isFile) found += c
            if (File(root, PDB_RELATIVE).isFile) found += root
        }
        return found.toList()
    }
}
