package os.deckbridge.manager.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import os.deckbridge.manager.Column as TrackColumn
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.pdb.TrackPatch
import os.deckbridge.manager.smart.QuickFilter
import os.deckbridge.manager.smart.Smart

private val sortChoices = listOf(TrackColumn.ARTIST to "Artista", TrackColumn.TITLE to "Título", TrackColumn.BPM to "BPM", TrackColumn.KEY to "Tom", TrackColumn.RATING to "Nota", TrackColumn.ID to "Recentes")

@Composable
fun LibraryTab(app: ManagerApp, actions: Actions) {
    val m = app.model
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val rows = m.rows()
    val counts = m.filterCounts()
    if (app.helpRequested) { app.helpRequested = false; dialog = Dialog.SearchHelp }
    val narrowed = m.query.isNotBlank() || m.filter != QuickFilter.ALL
    Column(Modifier.fillMaxWidth()) {
        if (app.searchOpen || m.query.isNotEmpty()) SearchField(app) { dialog = Dialog.SearchHelp }
        // Uma única fileira: filtros, a playlist alvo (se houver) e "salvar" quando a lista está filtrada. Sem músicas, nada.
        if (m.view?.tracks?.isNotEmpty() == true) LazyRow(Modifier.padding(vertical = 6.dp), contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            m.target?.let { id -> m.playlist(id) }?.let { p ->
                // Para onde vai o "+" de cada linha.
                item { FilterChip(selected = true, onClick = { dialog = Dialog.PickTarget }, label = { Text("＋ ${p.name}", maxLines = 1) }) }
            }
            items(QuickFilter.values().filter { it == QuickFilter.ALL || it == m.filter || (counts[it] ?: 0) > 0 }) { f ->
                FilterChip(selected = m.filter == f, onClick = { m.filter = f }, label = {
                    Text(f.short(), maxLines = 1)
                    if (f != QuickFilter.ALL) Text(" ${counts[f] ?: 0}", color = Palette.orange, fontFamily = Mono, maxLines = 1)
                })
            }
            if (narrowed && rows.isNotEmpty()) item { FilterChip(selected = false, onClick = { dialog = Dialog.SaveList }, label = { Text("＋ Playlist", maxLines = 1) }) }
        }
        if (rows.isNotEmpty()) ListHeader(app)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (rows.isEmpty()) {
                item(key = "empty") { EmptyList(app, actions) }
            } else {
                items(rows, key = { it.id }) { t -> SwipeToTarget(app, t, onNoTarget = { dialog = Dialog.PickTarget }) { TrackRow(app, t, selected = t.id in m.selection, edited = t.id in m.editedTrackIds()) } }
            }
        }
        if (app.selecting) SelectionBar(app, rows) { dialog = it }
    }
    LibraryDialogs(app, dialog) { dialog = null }
}

/** Rótulo curto de cada filtro rápido (o celular não tem espaço para frases). */
fun QuickFilter.short() = when (this) {
    QuickFilter.ALL -> "Todas"
    QuickFilter.UNANALYZED -> "Sem análise"
    QuickFilter.UNRATED -> "Sem nota"
    QuickFilter.NO_PLAYLIST -> "Sem playlist"
    QuickFilter.NO_GENRE -> "Sem gênero"
    QuickFilter.NO_ARTIST -> "Sem artista"
    QuickFilter.TOP -> "★4+"
    QuickFilter.RECENT -> "Recentes"
}

@Composable
private fun SearchField(app: ManagerApp, help: () -> Unit) {
    val m = app.model
    OutlinedTextField(
        value = m.query, onValueChange = { m.query = it }, singleLine = true,
        placeholder = { Text("Buscar", maxLines = 1) },
        trailingIcon = {
            Row {
                TextButton(onClick = help) { Text("?", fontSize = 16.sp) }
                TextButton(onClick = { m.query = ""; app.searchOpen = false }) { Text("✕", fontSize = 16.sp) }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp),
    )
}

@Composable
private fun EmptyList(app: ManagerApp, actions: Actions) {
    val m = app.model
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (m.view?.tracks?.isEmpty() == true) {
            // Três portas: músicas soltas, uma pasta inteira, ou um pendrive que já veio do rekordbox.
            Text("Comece pelas suas músicas", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("WAV · MP3 · FLAC · AAC", color = Palette.muted, fontSize = 13.sp)
            Button(onClick = actions.pickAudio, modifier = Modifier.fillMaxWidth().spotlight(!app.guideHidden).height(56.dp)) { Text("IMPORTAR MÚSICAS", fontSize = 15.sp) }
            OutlinedButton(onClick = actions.pickFolder, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("IMPORTAR UMA PASTA") }
            TextButton(onClick = { app.tab = Tab.USB; actions.pickUsb() }, modifier = Modifier.padding(top = 8.dp)) { Text("TENHO UM PENDRIVE DO REKORDBOX", fontSize = 12.sp) }
        } else {
            Text("Nenhuma faixa nesta lista.", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(if (m.filter != QuickFilter.ALL) m.filter.hint else "Mude a busca.", color = Palette.muted, fontSize = 14.sp)
            OutlinedButton(onClick = { m.query = ""; m.filter = QuickFilter.ALL }) { Text("LIMPAR BUSCA E FILTRO") }
        }
    }
}

/** Cabeçalho da lista como no player: TÍTULO … BPM TOM; tocar ordena e tocar de novo inverte. */
@Composable
private fun ListHeader(app: ManagerApp) {
    val m = app.model
    @Composable fun head(label: String, col: TrackColumn, mod: Modifier) {
        val on = m.sort.column == col
        Text(
            label + if (on) (if (m.sort.ascending) " ▲" else " ▼") else "",
            color = if (on) Palette.orange else Palette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1,
            modifier = mod.clickable {
                if (on) m.toggleSort(col) else m.sort = os.deckbridge.manager.Sort(col, col != TrackColumn.ID && col != TrackColumn.RATING && col != TrackColumn.BPM)
            }.padding(vertical = 8.dp),
        )
    }
    Row(Modifier.fillMaxWidth().background(Palette.surface).border(BorderStroke(0.5.dp, Palette.line)).padding(start = 58.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        head("TÍTULO", TrackColumn.ARTIST, Modifier.weight(1f))
        head("NOTA", TrackColumn.RATING, Modifier.padding(end = 12.dp))
        head("BPM", TrackColumn.BPM, Modifier.width(52.dp))
        head("TOM", TrackColumn.KEY, Modifier.width(40.dp))
    }
}

/** Disco da faixa como na lista do player: quadrado na cor da faixa com o anel branco e o ponto. */
@Composable
private fun Disc(color: Color?) {
    Canvas(Modifier.size(40.dp).background(color ?: Palette.surfaceHigh)) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Palette.text, radius = size.minDimension * 0.36f, center = c, style = Stroke(width = 3f))
        drawCircle(Palette.text, radius = size.minDimension * 0.07f, center = c)
    }
}

/**
 * Deslizar a linha para a direita põe a faixa na playlist alvo (ou tira, se já está); sem alvo, pede qual. A linha
 * volta ao lugar: é um gesto de ação, não de apagar.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToTarget(app: ManagerApp, t: TrackItem, onNoTarget: () -> Unit, content: @Composable () -> Unit) {
    val m = app.model
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd && !app.selecting) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            if (m.target == null) onNoTarget() else app.did(m.toggleInTarget(t.id))
        }
        false
    })
    val target = m.target?.let { m.playlist(it) }
    val inTarget = target != null && t.id in m.view?.entries?.get(target.id).orEmpty()
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Palette.blueActiveFill).padding(start = 18.dp), contentAlignment = Alignment.CenterStart) {
                Text(if (target == null) "＋ PLAYLIST?" else (if (inTarget) "− " else "＋ ") + target.name, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
    ) { Box(Modifier.background(Palette.bg)) { content() } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(app: ManagerApp, t: TrackItem, selected: Boolean, edited: Boolean) {
    val m = app.model
    val open = app.detailId == t.id
    val inTarget = m.target?.let { t.id in m.view?.entries?.get(it).orEmpty() } == true
    // Tom compatível com a faixa aberta: célula verde, como o tom compatível com o deck master no player.
    val master = app.detailId?.let { m.view?.track(it) }
    val compatible = master != null && master.id != t.id && Smart.camelot(master.key)?.let { a -> Smart.camelot(t.key)?.let { b -> Smart.harmonicDistance(a, b) <= 1 } } == true
    val analyzed = m.hasAnalysis(t)
    Row(
        Modifier.fillMaxWidth().height(60.dp)
            .background(if (selected || open) Palette.selectedRow else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (app.selecting) {
                        m.selection = if (t.id in m.selection) m.selection - t.id else m.selection + t.id
                        if (m.selection.isEmpty()) app.selecting = false
                    } else app.detailId = t.id
                },
                onLongClick = {
                    app.selecting = true
                    m.selection = m.selection + t.id
                },
            )
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.selecting) {
            Box(Modifier.size(20.dp).border(BorderStroke(1.dp, if (selected) Palette.blue else Palette.line), RbShape).background(if (selected) Palette.blueActiveFill else Palette.bg), contentAlignment = Alignment.Center) {
                if (selected) Text("✓", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
        }
        Disc(Palette.trackColors.getOrNull(t.colorId - 1))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // Ponto laranja: mudança não salva.
                if (edited) Box(Modifier.padding(start = 6.dp).size(6.dp).background(Palette.orange, RbShape))
            }
            Text(t.artist.ifEmpty { "—" }, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.rating > 0) Text("★".repeat(t.rating), color = Palette.orange, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, end = 8.dp))
        // Sem análise: o BPM fica apagado, sem texto extra.
        Text(if (t.bpm > 0) "%.1f".format(t.bpm) else "—", color = if (analyzed) Palette.text else Palette.muted, fontSize = 14.sp, fontFamily = Mono, modifier = Modifier.width(52.dp))
        Box(Modifier.width(40.dp).height(24.dp).background(if (compatible) Palette.keyFill else Color.Transparent), contentAlignment = Alignment.Center) {
            Text(t.key.ifEmpty { "—" }, color = if (compatible) Palette.keyInk else Palette.mutedBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (m.target != null && !app.selecting) {
            Box(Modifier.size(52.dp).clickable { app.did(m.toggleInTarget(t.id)) }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).border(BorderStroke(1.dp, if (inTarget) Palette.blue else Palette.line), RbShape).background(if (inTarget) Palette.blueActiveFill else Palette.surfaceHigh), contentAlignment = Alignment.Center) {
                    Text(if (inTarget) "✓" else "+", color = if (inTarget) Palette.text else Palette.blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else Spacer(Modifier.width(10.dp))
    }
}

fun stars(n: Int) = "★".repeat(n.coerceIn(0, 5)) + "☆".repeat(5 - n.coerceIn(0, 5))
fun formatDuration(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

sealed interface Dialog {
    data object AddToPlaylist : Dialog
    data object Rate : Dialog
    data object Color : Dialog
    data object Remove : Dialog
    data object Edit : Dialog
    data object PickTarget : Dialog
    data object SaveList : Dialog
    data object SearchHelp : Dialog
    data object Sort : Dialog
}

@Composable
private fun SelectionBar(app: ManagerApp, rows: List<TrackItem>, show: (Dialog) -> Unit) {
    val m = app.model
    Column(Modifier.fillMaxWidth().background(Palette.surfaceHigh).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${m.selection.size} selecionada(s)", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = { m.selection = rows.map { it.id }.toSet() }) { Text("Todas (${rows.size})") }
            TextButton(onClick = { m.selection = emptySet(); app.selecting = false }) { Text("Fechar") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (m.target != null) item {
                OutlinedButton(onClick = {
                    val id = m.target!!
                    val p = m.playlist(id)
                    val fresh = m.selection.filter { it !in m.view?.entries?.get(id).orEmpty() }
                    if (p != null && fresh.isNotEmpty() && m.submit(Edit.AddToPlaylist(id, fresh), "Adicionar ${fresh.size} a \"${p.name}\"")) app.did("${fresh.size} faixa(s) em \"${p.name}\"")
                    m.selection = emptySet()
                    app.selecting = false
                }, modifier = Modifier.height(44.dp)) { Text("＋ ${m.target?.let { m.playlist(it)?.name }.orEmpty()}", fontSize = 13.sp, color = Palette.blue, maxLines = 1) }
            }
            item { OutlinedButton(onClick = { show(Dialog.AddToPlaylist) }, modifier = Modifier.height(44.dp)) { Text("Playlist", fontSize = 13.sp) } }
            item { OutlinedButton(onClick = { show(Dialog.Edit) }, modifier = Modifier.height(44.dp)) { Text("Editar", fontSize = 13.sp) } }
            item { OutlinedButton(onClick = { show(Dialog.Rate) }, modifier = Modifier.height(44.dp)) { Text("Nota", fontSize = 13.sp) } }
            item { OutlinedButton(onClick = { show(Dialog.Color) }, modifier = Modifier.height(44.dp)) { Text("Cor", fontSize = 13.sp) } }
            item { OutlinedButton(onClick = { show(Dialog.Remove) }, modifier = Modifier.height(44.dp)) { Text("Remover", fontSize = 13.sp, color = Palette.danger) } }
        }
    }
}

@Composable
private fun LibraryDialogs(app: ManagerApp, dialog: Dialog?, close: () -> Unit) {
    val m = app.model
    val ids = m.selection.toList()
    fun done() {
        close()
        app.selecting = false
        m.selection = emptySet()
    }
    when (dialog) {
        Dialog.AddToPlaylist -> {
            // Com alvo definido, "＋ ao alvo" adiciona direto; sem alvo (ou pelo botão Playlist) abre o seletor.
            PlaylistPicker(app, onPick = { id ->
                val p = m.playlist(id)
                val have = m.view?.entries?.get(id).orEmpty().toSet()
                val fresh = ids.filter { it !in have }
                if (fresh.isNotEmpty() && p != null && m.submit(Edit.AddToPlaylist(id, fresh), "Adicionar ${fresh.size} a \"${p.name}\"")) app.did("${fresh.size} faixa(s) em \"${p.name}\"")
                done()
            }, onCreate = { name ->
                val id = m.nextPlaylistId()
                if (m.submit(Edit.CreatePlaylist(id, 0, name, false), "Criar playlist \"$name\"")) m.submit(Edit.AddToPlaylist(id, ids), "Adicionar ${ids.size} a \"$name\"")
                app.did("Playlist \"$name\" criada com ${ids.size} faixa(s)")
                done()
            }, onClose = close)
        }
        Dialog.PickTarget -> PlaylistPicker(app, title = "Adicionar em", onPick = { id ->
            m.target = id
            close()
        }, onCreate = { name ->
            val id = m.nextPlaylistId()
            if (m.submit(Edit.CreatePlaylist(id, 0, name, false), "Criar playlist \"$name\"")) m.target = id
            close()
        }, onClose = close)
        Dialog.Rate -> ChoiceDialog("Nota", (0..5).map { if (it == 0) "Sem nota" else stars(it) }, onPick = { n ->
            m.submit(Edit.SetTracks(ids, TrackPatch(rating = n)), "Nota $n em ${ids.size} faixa(s)")
            app.did("Nota aplicada a ${ids.size} faixa(s)")
            done()
        }, onClose = close)
        Dialog.Color -> ColorDialog(onPick = { id ->
            m.submit(Edit.SetTracks(ids, TrackPatch(colorId = id)), "Cor em ${ids.size} faixa(s)")
            app.did("Cor aplicada a ${ids.size} faixa(s)")
            done()
        }, onClose = close)
        Dialog.Edit -> BatchEditDialog(count = ids.size, onApply = { patch ->
            m.submit(Edit.SetTracks(ids, patch), "Editar ${ids.size} faixa(s)")
            app.did("${ids.size} faixa(s) editada(s)")
            done()
        }, onClose = close)
        Dialog.SaveList -> NameDialog("Salvar lista como playlist", confirm = "Criar", onName = { name ->
            val n = m.rows().size
            if (m.saveListAsPlaylist(name) != null) app.did("Playlist \"$name\" com $n faixa(s)")
            close()
        }, onClose = close)
        Dialog.SearchHelp -> SearchHelpDialog(close)
        Dialog.Sort -> ChoiceDialog("Ordenar por", sortChoices.map { (col, name) -> name + if (m.sort.column == col) (if (m.sort.ascending) "  ↑" else "  ↓") else "" }, onPick = { n ->
            val (col, _) = sortChoices[n]
            if (m.sort.column == col) m.toggleSort(col) else m.sort = os.deckbridge.manager.Sort(col, col != TrackColumn.ID && col != TrackColumn.RATING && col != TrackColumn.BPM)
            close()
        }, onClose = close)
        Dialog.Remove -> ConfirmDialog(
            title = "Remover ${ids.size}?",
            text = "Os arquivos de áudio ficam.",
            confirm = "Remover", danger = true,
            onConfirm = {
                m.submit(Edit.RemoveTracks(ids), "Remover ${ids.size} faixa(s)")
                if (app.detailId in ids) app.detailId = null
                done()
            }, onClose = close,
        )
        null -> {}
    }
    app.tidy?.let { TidyDialog(app, it) }
}
