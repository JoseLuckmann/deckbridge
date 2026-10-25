package os.deckbridge.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.concurrent.thread
import os.deckbridge.manager.Column as Col
import os.deckbridge.manager.ManagerModel
import os.deckbridge.logic.StagePalette
import os.deckbridge.manager.VolumeDetector
import os.deckbridge.manager.smart.QuickFilter
import os.deckbridge.manager.smart.Smart
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.PlaylistItem
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.pdb.TrackPatch
import os.deckbridge.manager.volume.BackupRecord

/** Estado só da tela: janelas abertas, pastas expandidas. O que vale para o pendrive fica no [ManagerModel]. */
class UiState {
    var prompt by mutableStateOf<Prompt?>(null)
    var confirm by mutableStateOf<ConfirmAsk?>(null)
    var review by mutableStateOf(false)
    var backups by mutableStateOf(false)
    var addMenu by mutableStateOf(false)
    var expanded by mutableStateOf<Set<Int>>(emptySet())
    var applying by mutableStateOf<String?>(null)
    var chooser: (() -> File?) = { null }
    var chooseFiles: (() -> List<File>) = { emptyList() }
    var cueTrack by mutableStateOf<Int?>(null)
    var importRows by mutableStateOf<List<ImportRow>?>(null)
    var rename by mutableStateOf(false)
    var organize by mutableStateOf(false)
    var transfer by mutableStateOf<TransferState?>(null)
    val organizeState = OrganizeState()
    var drag by mutableStateOf<DragInfo?>(null)
    var hoverPlaylist by mutableStateOf<Int?>(null)

    /** Retângulos (na janela) das linhas da barra lateral ("pl:id") e da tabela ("row:id"), para saber onde se solta. */
    val rects = mutableStateMapOf<String, Rect>()

    fun updateHover() {
        val d = drag ?: return
        hoverPlaylist = rects.entries.firstOrNull { it.key.startsWith("pl:") && it.value.contains(d.pos) }?.key?.removePrefix("pl:")?.toIntOrNull()
    }

    fun finishDrag(model: ManagerModel, open: PlaylistItem?) {
        val d = drag ?: return
        drag = null
        hoverPlaylist = null
        val target = rects.entries.firstOrNull { it.key.startsWith("pl:") && it.value.contains(d.pos) }
        if (target != null) {
            model.dropOnPlaylist(d.ids, target.key.removePrefix("pl:").toInt())
            return
        }
        if (open != null && !open.folder && model.sort.column == null) {
            val row = rects.entries.firstOrNull { it.key.startsWith("row:") && it.value.contains(d.pos) } ?: return
            val over = row.key.removePrefix("row:").toInt()
            val ordered = model.view?.entries?.get(open.id).orEmpty()
            val before = if (d.pos.y < row.value.center.y) over else ordered.getOrNull(ordered.indexOf(over) + 1)
            model.reorderInPlaylist(open.id, d.ids.filter { it in ordered }, before)
        }
    }

    fun startImportOf(files: List<File>, model: ManagerModel) {
        if (!model.isOpen) {
            model.banner = os.deckbridge.manager.Banner("Abra um pendrive antes de importar áudio.", error = true)
            return
        }
        val expanded = files.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile && os.deckbridge.manager.volume.isAudio(it.name) }.take(300).toList() else listOf(f) }
        val audio = expanded.filter { os.deckbridge.manager.volume.isAudio(it.name) }
        if (audio.isEmpty()) {
            model.banner = os.deckbridge.manager.Banner("Nenhum arquivo de áudio (WAV, MP3, FLAC, AIFF, M4A) foi escolhido.", error = true)
            return
        }
        importRows = startImport(audio)
    }
}

data class DragInfo(val ids: List<Int>, val label: String, val pos: Offset)

class Prompt(val title: String, val initial: String, val action: String, val onOk: (String) -> Unit)

class ConfirmAsk(val title: String, val text: String, val action: String, val danger: Boolean, val onOk: () -> Unit)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun App(model: ManagerModel, ui: UiState = remember { UiState() }) {
    val dropTarget = remember(model, ui) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    event.awtTransferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<File>
                }.getOrNull() ?: return false
                ui.startImportOf(files, model)
                return true
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Pal.Bg).dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(model, ui)
            model.banner?.let { Banner(it.text, it.error) { model.banner = null } }
            if (!model.isOpen) Welcome(model, ui)
            else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Sidebar(model, ui, Modifier.width(250.dp).fillMaxHeight())
                    Center(model, ui, Modifier.weight(1f).fillMaxHeight())
                    Inspector(model, ui, Modifier.width(330.dp).fillMaxHeight())
                }
                PendingBar(model, ui)
            }
        }
        ui.prompt?.let { p -> Box(Modifier.fillMaxSize()) { PromptModal(p) { ui.prompt = null } } }
        ui.confirm?.let { c -> Box(Modifier.fillMaxSize()) { ConfirmModal(c) { ui.confirm = null } } }
        ui.cueTrack?.let { id -> model.view?.track(id)?.let { t -> Box(Modifier.fillMaxSize()) { CueEditorModal(model, t) { ui.cueTrack = null } } } }
        ui.importRows?.let { rows -> Box(Modifier.fillMaxSize()) { ImportModal(model, rows) { ui.importRows = null } } }
        if (ui.rename) Box(Modifier.fillMaxSize()) { RenameModal(model) { ui.rename = false } }
        if (ui.organize) Box(Modifier.fillMaxSize()) { OrganizeModal(model, ui.organizeState) { ui.organize = false } }
        if (ui.review) Box(Modifier.fillMaxSize()) { ReviewModal(model, ui) }
        if (ui.backups) Box(Modifier.fillMaxSize()) { BackupsModal(model, ui) }
        ui.transfer?.let { t -> Box(Modifier.fillMaxSize()) { TransferModal(model, t, ui.chooser) { ui.transfer = null } } }
        ui.drag?.let { d ->
            Box(Modifier.offset { IntOffset(d.pos.x.toInt() + 14, d.pos.y.toInt() + 10) }.background(Pal.Blue).padding(horizontal = 10.dp, vertical = 5.dp)) { Label(d.label, Pal.Bg, 12.sp, bold = true) }
        }
    }
}

@Composable
private fun TopBar(model: ManagerModel, ui: UiState) {
    Row(Modifier.fillMaxWidth().background(Pal.Panel).border(1.dp, Pal.Line).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Label("DECKBRIDGE", Pal.Blue, 16.sp, bold = true)
        Label("Library Manager", Pal.Ink, 16.sp)
        val vol = model.volume
        if (vol != null) {
            Label(vol.root.path, Pal.Muted, 12.sp, modifier = Modifier.weight(1f))
            Btn("Importar áudio…", { val f = ui.chooseFiles(); if (f.isNotEmpty()) ui.startImportOf(f, model) })
            Btn("Organizar…", { refreshSuggestions(model, ui.organizeState); ui.organize = true })
            Btn("Renomear arquivos…", { ui.rename = true })
            Btn("Exportar para pendrive…", { ui.transfer = TransferState(export = true) })
            Btn("Trazer de pendrive…", { ui.transfer = TransferState(export = false) })
            Btn("Backups", { ui.backups = true })
            Btn("Abrir outro…", { ui.chooser()?.let { model.open(it) } })
        } else {
            Spacer(Modifier.weight(1f))
        }
        Label(if (model.dirty) "● mudanças não gravadas" else "somente leitura até aplicar", if (model.dirty) Pal.Orange else Pal.Muted, 12.sp, bold = model.dirty)
    }
}

@Composable
private fun Banner(text: String, error: Boolean, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (error) Color(StagePalette.dangerBg) else Color(StagePalette.keyFill)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(text, if (error) Pal.Red else Pal.Green, 12.sp, modifier = Modifier.weight(1f), maxLines = 3)
        Label("✕", Pal.Muted, 14.sp, modifier = Modifier.clickable(onClick = onClose).padding(start = 12.dp))
    }
}

fun localLibraryRoot(): File = File(System.getProperty("user.home"), ".deckbridge/library")

@Composable
private fun Welcome(model: ManagerModel, ui: UiState) {
    val found = remember { VolumeDetector.find() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label("Abra um pendrive exportado pelo rekordbox", Pal.Ink, 20.sp, bold = true)
            Label("Aqui você organiza a biblioteca à mão: metadados, playlists e pastas. Nada é gravado enquanto você edita. As mudanças ficam numa fila, você revisa, e só então o programa grava, com cópia de segurança antes.", Pal.Muted, 13.sp, maxLines = 5)
            if (found.isNotEmpty()) {
                Caption("Pendrives encontrados")
                found.forEach { f -> Btn(f.path, { model.open(f) }, Modifier.fillMaxWidth()) }
            }
            Btn("Escolher a pasta do pendrive…", { ui.chooser()?.let { model.open(it) } }, primary = true)
            Caption("Ou use a biblioteca local deste computador")
            Label("Fica em ${localLibraryRoot().path}, no mesmo formato de um pendrive. Edite aqui e exporte para o pendrive quando quiser.", Pal.Muted, 12.sp, maxLines = 3)
            Btn("Abrir a biblioteca local", { os.deckbridge.manager.transfer.LocalLibrary.ensure(localLibraryRoot()); model.open(localLibraryRoot()) })
        }
    }
}

@Composable
private fun Sidebar(model: ManagerModel, ui: UiState, modifier: Modifier) {
    val view = model.view ?: return
    Column(modifier.background(Pal.Panel).border(1.dp, Pal.Line)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
            Caption("Biblioteca", Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            SideRow("Coleção", view.tracks.size.toString(), 0, model.openPlaylist == null, "♫", null, ui) {
                model.openPlaylist = null
                model.selection = emptySet()
                model.sort = os.deckbridge.manager.Sort(Col.ARTIST)
            }
            Caption("Playlists", Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp))
            fun rows(parent: Int, depth: Int): List<Pair<PlaylistItem, Int>> = view.children(parent).flatMap { p ->
                listOf(p to depth) + if (p.folder && p.id in ui.expanded) rows(p.id, depth + 1) else emptyList()
            }
            rows(0, 0).forEach { (p, depth) ->
                val count = if (p.folder) view.children(p.id).size else view.entries[p.id].orEmpty().size
                SideRow(p.name, count.toString(), depth, model.openPlaylist == p.id, if (p.folder) (if (p.id in ui.expanded) "▾" else "▸") else "≡", p.id, ui) {
                    if (p.folder) ui.expanded = if (p.id in ui.expanded) ui.expanded - p.id else ui.expanded + p.id
                    model.openPlaylist = p.id
                    model.selection = emptySet()
                    model.sort = os.deckbridge.manager.Sort(if (p.folder) Col.ARTIST else null)
                }
            }
            if (view.playlists.isEmpty()) Label("Nenhuma playlist ainda.", Pal.Muted, 12.sp, modifier = Modifier.padding(14.dp))
        }
        val selected = model.openPlaylist?.let { model.playlist(it) }
        Column(Modifier.border(1.dp, Pal.Line).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("+ Playlist", { newPlaylist(model, ui, folder = false) }, Modifier.weight(1f))
                Btn("+ Pasta", { newPlaylist(model, ui, folder = true) }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Renomear", { selected?.let { p -> ui.prompt = Prompt("Renomear \"${p.name}\"", p.name, "Renomear") { n -> model.submit(Edit.RenamePlaylist(p.id, n), "Renomear ${if (p.folder) "pasta" else "playlist"} \"${p.name}\" para \"${n.trim()}\"") } } }, Modifier.weight(1f), enabled = selected != null)
                Btn("Excluir", { selected?.let { p -> ui.confirm = ConfirmAsk("Excluir \"${p.name}\"?", if (p.folder) "A pasta e tudo o que há dentro dela saem da biblioteca. As faixas continuam na coleção." else "A playlist sai da biblioteca. As faixas continuam na coleção.", "Excluir", true) { model.submit(Edit.DeletePlaylist(p.id), "Excluir ${if (p.folder) "pasta" else "playlist"} \"${p.name}\""); model.openPlaylist = null } } }, Modifier.weight(1f), danger = true, enabled = selected != null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val siblings = selected?.let { view.children(it.parentId) }.orEmpty()
                val at = siblings.indexOfFirst { it.id == selected?.id }
                Btn("▲ Subir", { selected?.let { model.submit(Edit.MovePlaylist(it.id, it.parentId, at - 1), "Subir \"${it.name}\"") } }, Modifier.weight(1f), enabled = selected != null && at > 0)
                Btn("▼ Descer", { selected?.let { model.submit(Edit.MovePlaylist(it.id, it.parentId, at + 1), "Descer \"${it.name}\"") } }, Modifier.weight(1f), enabled = selected != null && at in 0 until siblings.lastIndex)
            }
        }
    }
}

private fun newPlaylist(model: ManagerModel, ui: UiState, folder: Boolean) {
    val view = model.view ?: return
    val open = model.openPlaylist?.let { model.playlist(it) }
    // Dentro de uma pasta aberta, a nova entrada vai para ela; senão, na raiz.
    val parent = if (open?.folder == true) open.id else 0
    val kind = if (folder) "pasta" else "playlist"
    ui.prompt = Prompt("Nova $kind", "", "Criar") { name ->
        val id = model.nextPlaylistId()
        if (model.submit(Edit.CreatePlaylist(id, parent, name, folder), "Criar $kind \"${name.trim()}\"" + if (parent != 0) " em \"${open?.name}\"" else "")) {
            if (parent != 0) ui.expanded = ui.expanded + parent
            model.openPlaylist = id
            model.selection = emptySet()
            model.sort = os.deckbridge.manager.Sort(if (folder) Col.ARTIST else null)
        }
    }
    if (view.playlists.isEmpty() && false) return
}

@Composable
private fun SideRow(name: String, count: String, depth: Int, selected: Boolean, icon: String, playlistId: Int?, ui: UiState, onClick: () -> Unit) {
    val hover = playlistId != null && ui.hoverPlaylist == playlistId
    if (playlistId != null) DisposableEffect(playlistId) { onDispose { ui.rects.remove("pl:$playlistId") } }
    Row(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { if (playlistId != null) ui.rects["pl:$playlistId"] = it.boundsInWindow() }
            .background(if (hover) Pal.Orange.copy(alpha = 0.35f) else if (selected) Pal.SelectedRow else Color.Transparent)
            .clickable(onClick = onClick).padding(start = (14 + depth * 16).dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(icon, if (selected) Pal.Blue else Pal.Muted, 13.sp, modifier = Modifier.width(14.dp))
        Label(name, if (selected) Pal.Ink else Pal.Ink, 13.sp, bold = selected, modifier = Modifier.weight(1f))
        Label(count, Pal.Muted, 11.sp)
    }
}

private val columnWidths = mapOf(Col.ID to 46.dp, Col.KEY to 54.dp, Col.BPM to 62.dp, Col.RATING to 84.dp, Col.YEAR to 50.dp, Col.TIME to 56.dp, Col.GENRE to 104.dp)

@Composable
private fun Center(model: ManagerModel, ui: UiState, modifier: Modifier) {
    val view = model.view ?: return
    val rows = model.rows()
    val open = model.openPlaylist?.let { model.playlist(it) }
    val inTrackPlaylist = open != null && !open.folder
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextBox(model.query, { model.query = it }, Modifier.weight(1f), placeholder = "Buscar… ou filtrar: bpm:120-128 tom:8A nota:4 cor:verde")
            Box {
                Btn("Adicionar à playlist ▾", { ui.addMenu = !ui.addMenu }, enabled = model.selection.isNotEmpty() && view.playlists.any { !it.folder })
                if (ui.addMenu) AddMenu(model, ui)
            }
            if (!inTrackPlaylist) {
                val sel = model.selection.toList()
                Btn("Remover da biblioteca", {
                    ui.confirm = ConfirmAsk(
                        "Remover ${sel.size} faixa${if (sel.size == 1) "" else "s"} da biblioteca?",
                        "Saem da coleção, das playlists, do histórico e das tags. O arquivo de áudio e o de análise continuam no pendrive: nada de música é apagado.",
                        "Remover", true,
                    ) { model.submit(Edit.RemoveTracks(sel), "Remover ${sel.size} faixa${if (sel.size == 1) "" else "s"} da biblioteca: ${sel.take(3).mapNotNull { view.track(it)?.title }.joinToString()}${if (sel.size > 3) "…" else ""}"); model.selection = emptySet() }
                }, danger = true, enabled = sel.isNotEmpty())
            }
            if (inTrackPlaylist) {
                val ordered = model.sort.column == null
                val ids = view.entries[open!!.id].orEmpty()
                val sel = model.selection.filter { it in ids }
                Btn("▲", { moveInPlaylist(model, open, sel, -1) }, enabled = ordered && sel.size == 1 && ids.indexOf(sel.first()) > 0)
                Btn("▼", { moveInPlaylist(model, open, sel, +1) }, enabled = ordered && sel.size == 1 && ids.indexOf(sel.first()) in 0 until ids.lastIndex)
                Btn("Remover da playlist", { model.submit(Edit.RemoveFromPlaylist(open.id, sel), "Remover ${sel.size} faixa${if (sel.size == 1) "" else "s"} de \"${open.name}\""); model.selection = emptySet() }, danger = true, enabled = sel.isNotEmpty())
            }
        }
        // Filtros rápidos: "o que ainda falta fazer?" com um clique, com a contagem.
        val counts = model.filterCounts()
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            QuickFilter.values().filter { it == QuickFilter.ALL || it == model.filter || (counts[it] ?: 0) > 0 }.forEach { f ->
                Btn(if (f == QuickFilter.ALL) f.title else "${f.title} · ${counts[f] ?: 0}", { model.filter = f }, primary = model.filter == f)
            }
            if ((model.query.isNotBlank() || model.filter != QuickFilter.ALL) && rows.isNotEmpty()) {
                Btn("Salvar lista como playlist", { ui.prompt = Prompt("Nome da playlist (${rows.size} faixa${if (rows.size == 1) "" else "s"})", "", "Criar") { n -> model.saveListAsPlaylist(n.trim()) } })
            }
            if (inTrackPlaylist) {
                Label("Ordenar:", Pal.Muted, 12.sp, modifier = Modifier.padding(start = 10.dp))
                Smart.SortMode.values().filter { it != Smart.SortMode.TITLE && it != Smart.SortMode.ARTIST }.forEach { mode -> Btn(mode.title, { model.sortPlaylist(open!!.id, mode) }) }
            }
        }
        Label(
            (open?.name ?: "Coleção") + " · ${rows.size} faixa${if (rows.size == 1) "" else "s"}" + if (model.selection.isNotEmpty()) " · ${model.selection.size} selecionada${if (model.selection.size == 1) "" else "s"}" else "",
            Pal.Muted, 12.sp, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        )
        Row(Modifier.fillMaxWidth().background(Pal.Panel).border(1.dp, Pal.Line).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Col.entries.forEach { c ->
                val label = if (c == Col.ID && inTrackPlaylist) "Pos" else c.title
                val active = model.sort.column == c || (c == Col.ID && inTrackPlaylist && model.sort.column == null)
                val arrow = if (model.sort.column == c) (if (model.sort.ascending) " ▲" else " ▼") else if (active) " ●" else ""
                val base = when (c) {
                    Col.TITLE -> Modifier.weight(2.2f).padding(end = 8.dp)
                    Col.ARTIST, Col.ALBUM -> Modifier.weight(1.6f).padding(end = 8.dp)
                    else -> cellModifier(c)
                }
                val m = base.clickable {
                    if (c == Col.ID && inTrackPlaylist) model.sort = os.deckbridge.manager.Sort(null) else model.toggleSort(c)
                }
                Label(label + arrow, if (active) Pal.Orange else Pal.Muted, 11.sp, bold = true, modifier = m)
            }
        }
        val edited = model.editedTrackIds()
        val ids = if (inTrackPlaylist) view.entries[open!!.id].orEmpty() else emptyList()
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { t ->
                TrackRowView(
                    t, selected = t.id in model.selection, edited = t.id in edited, position = if (inTrackPlaylist) ids.indexOf(t.id) + 1 else null, ui = ui,
                    onDragStart = { at ->
                        if (t.id !in model.selection) model.selection = setOf(t.id)
                        val picked = rows.map { it.id }.filter { it in model.selection }
                        ui.drag = DragInfo(picked, if (picked.size == 1) t.title else "${picked.size} faixas", at)
                        ui.updateHover()
                    },
                    onDragMove = { at -> ui.drag = ui.drag?.copy(pos = at); ui.updateHover() },
                    onDragEnd = { ui.finishDrag(model, open) },
                ) { ctrl, shift -> model.click(t.id, ctrl, shift, rows) }
            }
        }
    }
}

@Composable
private fun AddMenu(model: ManagerModel, ui: UiState) {
    val view = model.view ?: return
    Column(Modifier.width(280.dp).background(Pal.Raised).border(1.dp, Pal.Line).padding(vertical = 4.dp).clickable(enabled = false) {}, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        fun walk(parent: Int, depth: Int): List<Pair<PlaylistItem, Int>> = view.children(parent).flatMap { listOf(it to depth) + walk(it.id, depth + 1) }
        walk(0, 0).forEach { (p, depth) ->
            if (p.folder) Label(p.name, Pal.Muted, 11.sp, bold = true, modifier = Modifier.padding(start = (12 + depth * 14).dp, top = 6.dp, bottom = 2.dp))
            else Label("≡  " + p.name, Pal.Ink, 13.sp, modifier = Modifier.fillMaxWidth().clickable {
                val ids = model.rows().map { it.id }.filter { it in model.selection }
                model.submit(Edit.AddToPlaylist(p.id, ids), "Adicionar ${ids.size} faixa${if (ids.size == 1) "" else "s"} a \"${p.name}\"")
                ui.addMenu = false
            }.padding(start = (12 + depth * 14).dp, top = 5.dp, bottom = 5.dp, end = 10.dp))
        }
    }
}

private fun moveInPlaylist(model: ManagerModel, playlist: PlaylistItem, selected: List<Int>, delta: Int) {
    val ids = model.view?.entries?.get(playlist.id).orEmpty().toMutableList()
    val id = selected.singleOrNull() ?: return
    val at = ids.indexOf(id)
    val to = at + delta
    if (at < 0 || to !in ids.indices) return
    ids.removeAt(at)
    ids.add(to, id)
    model.submit(Edit.ReorderPlaylist(playlist.id, ids), "Mover \"${model.view?.track(id)?.title}\" na playlist \"${playlist.name}\"")
}

private fun cellModifier(c: Col): Modifier = columnWidths[c]?.let { Modifier.width(it) } ?: Modifier.width(0.dp)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TrackRowView(
    t: TrackItem, selected: Boolean, edited: Boolean, position: Int?, ui: UiState,
    onDragStart: (Offset) -> Unit, onDragMove: (Offset) -> Unit, onDragEnd: () -> Unit,
    onClick: (ctrl: Boolean, shift: Boolean) -> Unit,
) {
    val ink = if (edited) Pal.Orange else Pal.Ink
    var origin by remember { mutableStateOf(Offset.Zero) }
    var at by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(t.id) { onDispose { ui.rects.remove("row:${t.id}") } }
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(if (selected) Pal.SelectedRow else Color.Transparent)
            .onGloballyPositioned {
                origin = it.positionInWindow()
                ui.rects["row:${t.id}"] = it.boundsInWindow()
            }
            .onPointerEvent(PointerEventType.Press) {
                val m = it.keyboardModifiers
                onClick(m.isCtrlPressed || m.isMetaPressed, m.isShiftPressed)
            }
            .pointerInput(t.id) {
                detectDragGestures(
                    onDragStart = { local -> at = origin + local; onDragStart(at) },
                    onDrag = { change, amount -> change.consume(); at += amount; onDragMove(at) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { ui.drag = null; ui.hoverPlaylist = null },
                )
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label((position ?: t.id).toString(), Pal.Muted, 12.sp, modifier = cellModifier(Col.ID))
        Label((if (edited) "● " else "") + t.title, ink, 13.sp, bold = edited, modifier = Modifier.weight(2.2f).padding(end = 8.dp))
        Label(t.artist, ink, 13.sp, modifier = Modifier.weight(1.6f).padding(end = 8.dp))
        Label(t.album, Pal.Muted, 13.sp, modifier = Modifier.weight(1.6f).padding(end = 8.dp))
        Label(t.genre, Pal.Muted, 13.sp, modifier = cellModifier(Col.GENRE))
        Label(t.key, Pal.Green, 12.sp, bold = true, modifier = cellModifier(Col.KEY))
        Label(if (t.bpm > 0) "%.1f".format(t.bpm) else "", Pal.Ink, 12.sp, modifier = cellModifier(Col.BPM))
        Box(cellModifier(Col.RATING)) { Stars(t.rating, null, 12.sp) }
        Label(if (t.year > 0) t.year.toString() else "", Pal.Muted, 12.sp, modifier = cellModifier(Col.YEAR))
        Label("%d:%02d".format(t.durationSeconds / 60, t.durationSeconds % 60), Pal.Muted, 12.sp, modifier = cellModifier(Col.TIME))
    }
}

@Composable
private fun Inspector(model: ManagerModel, ui: UiState, modifier: Modifier) {
    val view = model.view ?: return
    val tracks = model.selection.mapNotNull { view.track(it) }
    Column(modifier.background(Pal.Panel).border(1.dp, Pal.Line).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tracks.isEmpty()) {
            Caption("Faixa")
            Label("Selecione uma ou mais faixas para editar título, artista, álbum, gênero, tom, nota e comentário. Com várias selecionadas, só os campos que você mexer mudam.", Pal.Muted, 13.sp, maxLines = 6)
        } else {
            InspectorForm(model, ui, tracks)
        }
    }
}

private class Draft(var text: String? = null)

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.InspectorForm(model: ManagerModel, ui: UiState, tracks: List<TrackItem>) {
    // O formulário recomeça quando a seleção ou os dados mudam (depois de guardar, por exemplo).
    val key = tracks.map { it.id to it.hashCode() }
    var drafts by remember(key) { mutableStateOf(mapOf<String, String>()) }
    var rating by remember(key) { mutableStateOf<Int?>(null) }
    fun common(get: (TrackItem) -> String): Pair<String, Boolean> {
        val values = tracks.map(get).distinct()
        return if (values.size == 1) values.first() to false else "" to true
    }
    val multi = tracks.size > 1
    Caption(if (multi) "${tracks.size} faixas" else "Faixa ${tracks.first().id}")
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        data class F(val id: String, val title: String, val get: (TrackItem) -> String)
        val fields = listOf(
            F("title", "Título") { it.title },
            F("artist", "Artista") { it.artist },
            F("album", "Álbum") { it.album },
            F("genre", "Gênero") { it.genre },
            F("key", "Tom") { it.key },
            F("label", "Selo") { it.label },
            F("year", "Ano") { if (it.year > 0) it.year.toString() else "" },
            F("trackNumber", "Nº da faixa") { if (it.trackNumber > 0) it.trackNumber.toString() else "" },
            F("comment", "Comentário") { it.comment },
        )
        for (f in fields) {
            if (multi && f.id == "title") continue
            val (value, mixed) = common(f.get)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Caption(f.title)
                TextBox(drafts[f.id] ?: value, { drafts = drafts + (f.id to it) }, Modifier.fillMaxWidth(), placeholder = if (mixed && drafts[f.id] == null) "— valores diferentes —" else "")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Caption("Nota")
            Stars(rating ?: common { it.rating.toString() }.first.toIntOrNull() ?: 0, { rating = it }, 20.sp)
        }
        Caption("Somente leitura")
        val single = tracks.first()
        if (!multi) {
            Info("BPM", "%.2f".format(single.bpm))
            Info("Duração", "%d:%02d".format(single.durationSeconds / 60, single.durationSeconds % 60))
            Info("Taxa", "${single.sampleRate} Hz" + if (single.bitrate > 0) " · ${single.bitrate} kbps" else "")
            Info("Arquivo", single.filePath)
            Info("Análise", single.analyzePath)
        } else {
            Label("BPM, duração e arquivo aparecem com uma faixa só.", Pal.Muted, 12.sp, maxLines = 2)
        }
    }
    val patch = TrackPatch(
        title = drafts["title"]?.takeIf { it != tracks.first().title || multi }?.trim()?.takeIf { it.isNotEmpty() },
        artist = drafts["artist"]?.trim(),
        album = drafts["album"]?.trim(),
        genre = drafts["genre"]?.trim(),
        key = drafts["key"]?.trim(),
        label = drafts["label"]?.trim(),
        comment = drafts["comment"],
        year = drafts["year"]?.trim()?.let { if (it.isEmpty()) 0 else it.toIntOrNull() },
        trackNumber = drafts["trackNumber"]?.trim()?.let { if (it.isEmpty()) 0 else it.toIntOrNull() },
        rating = rating,
    )
    val changed = !patch.isEmpty
    if (!multi) Btn("Cues e grade…", { ui.cueTrack = single0(tracks).id }, Modifier.fillMaxWidth(), enabled = model.hasAnalysis(tracks.first()))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Guardar na fila", {
            val text = describe(tracks, patch)
            if (model.submit(Edit.SetTracks(tracks.map { it.id }, patch), text)) {
                drafts = emptyMap()
                rating = null
            }
        }, Modifier.weight(1f), primary = true, enabled = changed)
        Btn("Reverter", { drafts = emptyMap(); rating = null }, enabled = changed)
    }
}

private fun single0(tracks: List<TrackItem>) = tracks.first()

@Composable
private fun Info(label: String, value: String) {
    Column {
        Label(label, Pal.Muted, 11.sp)
        Label(value.ifEmpty { "—" }, Pal.Ink, 12.sp, maxLines = 2)
    }
}

/** Texto da mudança, com antes e depois quando é uma faixa só. */
fun describe(tracks: List<TrackItem>, patch: TrackPatch): String {
    val who = if (tracks.size == 1) "\"${tracks.first().title}\"" else "${tracks.size} faixas"
    val parts = ArrayList<String>()
    fun add(name: String, before: List<String>, after: String?) {
        if (after == null) return
        parts += if (before.size == 1) "$name: \"${before.first()}\" → \"$after\"" else "$name → \"$after\""
    }
    add("título", tracks.map { it.title }.distinct(), patch.title)
    add("artista", tracks.map { it.artist }.distinct(), patch.artist)
    add("álbum", tracks.map { it.album }.distinct(), patch.album)
    add("gênero", tracks.map { it.genre }.distinct(), patch.genre)
    add("tom", tracks.map { it.key }.distinct(), patch.key)
    add("selo", tracks.map { it.label }.distinct(), patch.label)
    add("comentário", tracks.map { it.comment }.distinct(), patch.comment)
    add("ano", tracks.map { it.year.toString() }.distinct(), patch.year?.toString())
    add("nº da faixa", tracks.map { it.trackNumber.toString() }.distinct(), patch.trackNumber?.toString())
    add("nota", tracks.map { it.rating.toString() }.distinct(), patch.rating?.toString())
    return "$who — ${parts.joinToString("; ")}"
}

@Composable
private fun PendingBar(model: ManagerModel, ui: UiState) {
    Row(Modifier.fillMaxWidth().background(Pal.Panel).border(1.dp, Pal.Line).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val n = model.pending.size
        if (n == 0) Label("Nenhuma mudança na fila.", Pal.Muted, 13.sp, modifier = Modifier.weight(1f))
        else Label("$n mudança${if (n == 1) "" else "s"} na fila · última: ${model.pending.last().text}", Pal.Orange, 13.sp, modifier = Modifier.weight(1f))
        Btn("Desfazer a última", { model.undo() }, enabled = n > 0)
        if (model.redoStack.isNotEmpty()) Btn("Refazer", { model.redo() })
        Btn("Descartar tudo", { ui.confirm = ConfirmAsk("Descartar ${model.pending.size} mudanças?", "As mudanças da fila somem. O pendrive não foi tocado.", "Descartar", true) { model.discard() } }, danger = true, enabled = n > 0)
        Btn("Revisar e aplicar…", { ui.review = true }, primary = true, enabled = n > 0)
    }
}

@Composable
private fun PromptModal(p: Prompt, close: () -> Unit) {
    var text by remember { mutableStateOf(p.initial) }
    Modal(p.title, 420.dp, close) {
        TextBox(text, { text = it }, Modifier.fillMaxWidth(), placeholder = "Nome")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(p.action, { if (text.isNotBlank()) { p.onOk(text); close() } }, primary = true, enabled = text.isNotBlank())
            Btn("Cancelar", close)
        }
    }
}

@Composable
private fun ConfirmModal(c: ConfirmAsk, close: () -> Unit) {
    Modal(c.title, 440.dp, close) {
        Label(c.text, Pal.Muted, 13.sp, maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(c.action, { c.onOk(); close() }, danger = c.danger, primary = !c.danger)
            Btn("Cancelar", close)
        }
    }
}

@Composable
private fun ReviewModal(model: ManagerModel, ui: UiState) {
    val report = model.lastReport
    Modal("Revisar e aplicar ao pendrive", 760.dp, if (ui.applying == null) ({ ui.review = false }) else null) {
        Label("Pendrive: ${model.volume?.root?.path.orEmpty()}", Pal.Muted, 12.sp)
        Caption("${model.pending.size} mudança${if (model.pending.size == 1) "" else "s"}")
        Column(Modifier.fillMaxWidth().height(230.dp).background(Pal.Bg).border(1.dp, Pal.Line).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            model.pending.forEachIndexed { n, c -> Label("${n + 1}.  ${c.text}", Pal.Ink, 12.sp, maxLines = 2) }
        }
        Column(Modifier.fillMaxWidth().background(Pal.Raised).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("O que acontece ao gravar", Pal.Blue, 12.sp, bold = true)
            Label("1. O banco novo é montado em memória e conferido: só as tabelas editadas mudam e as outras ficam idênticas byte a byte.", Pal.Muted, 12.sp, maxLines = 2)
            Label("2. Uma cópia do export.pdb atual vai para ~/.deckbridge/backups, fora do pendrive, com SHA-256.", Pal.Muted, 12.sp, maxLines = 2)
            Label("3. O novo arquivo é gravado ao lado, conferido e trocado de uma vez. Se algo falhar, o original volta.", Pal.Muted, 12.sp, maxLines = 2)
            Label("Nenhum player nem o rekordbox foi testado com bancos gravados por este programa. Teste primeiro numa cópia do pendrive.", Pal.Orange, 12.sp, maxLines = 2)
        }
        val busy = ui.applying
        if (busy != null) Label("Gravando… $busy", Pal.Orange, 13.sp, bold = true)
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Gravar no pendrive", {
                ui.applying = "começando"
                thread(name = "gravar") {
                    val r = model.apply { ui.applying = it }
                    ui.applying = null
                    if (r != null) ui.review = false
                }
            }, primary = true)
            Btn("Gravar numa cópia…", {
                val target = ui.chooser()
                if (target != null) {
                    ui.applying = "começando"
                    thread(name = "gravar-copia") {
                        model.apply(target) { ui.applying = it }
                        ui.applying = null
                        ui.review = false
                    }
                }
            })
            Btn("Voltar", { ui.review = false })
        }
        if (report != null && busy == null && false) Label(report.backup.dir.path, Pal.Muted, 11.sp)
    }
}

@Composable
private fun BackupsModal(model: ManagerModel, ui: UiState) {
    val list = model.backupList()
    var armed by remember { mutableStateOf<BackupRecord?>(null) }
    Modal("Cópias de segurança", 720.dp, { ui.backups = false }) {
        Label("Cada gravação guarda antes uma cópia do export.pdb. Restaurar devolve o pendrive àquele estado e, antes disso, guarda o estado de agora (dá para desfazer).", Pal.Muted, 12.sp, maxLines = 3)
        if (list.isEmpty()) Label("Ainda não há cópias para este pendrive.", Pal.Muted, 13.sp)
        Column(Modifier.fillMaxWidth().height(260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEach { r ->
                Row(Modifier.fillMaxWidth().background(Pal.Raised).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Label(prettyStamp(r.created), Pal.Ink, 13.sp, bold = true)
                        Label(r.changes.firstOrNull() ?: "(sem descrição)" + if (r.changes.size > 1) " e mais ${r.changes.size - 1}" else "", Pal.Muted, 12.sp)
                    }
                    if (armed == r) {
                        Btn("Confirmar restauração", { model.restore(r); armed = null; ui.backups = false }, danger = true)
                        Btn("Não", { armed = null })
                    } else Btn("Restaurar", { armed = r })
                }
            }
        }
        val vol = model.volume
        if (vol != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val size = vol.pdbFile.length() / 1024
            Btn("Compactar o banco", {
                ui.applying = "compactando"
                thread(name = "compactar") {
                    model.compact { ui.applying = it }
                    ui.applying = null
                }
            }, enabled = !model.dirty && ui.applying == null)
            Label(if (model.dirty) "Grave ou descarte a fila antes de compactar." else "export.pdb: $size KB. As gravações deixam páginas antigas no arquivo; compactar tira só o que ficou sem uso, sem mudar nenhuma linha.", Pal.Muted, 11.sp, maxLines = 2)
        }
        Btn("Fechar", { ui.backups = false })
    }
}

/** `20261025-113439-573` → `25/10/2026 11:34:39`. */
fun prettyStamp(stamp: String): String {
    val m = Regex("""(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})(?:-\d+)?(.*)""").matchEntire(stamp) ?: return stamp
    val g = m.groupValues
    return "${g[3]}/${g[2]}/${g[1]} ${g[4]}:${g[5]}:${g[6]}"
}
