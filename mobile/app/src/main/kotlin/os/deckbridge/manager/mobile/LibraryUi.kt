package os.deckbridge.manager.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import os.deckbridge.manager.Column as TrackColumn
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.pdb.TrackPatch

private val sortChoices = listOf(TrackColumn.ARTIST to "Artista", TrackColumn.TITLE to "Título", TrackColumn.BPM to "BPM", TrackColumn.KEY to "Tom", TrackColumn.RATING to "Nota", TrackColumn.ID to "Recentes")

@Composable
fun LibraryTab(app: ManagerApp, actions: Actions) {
    val m = app.model
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val rows = m.rows()
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = m.query, onValueChange = { m.query = it }, singleLine = true,
            placeholder = { Text("Buscar título, artista, tom, comentário") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sortChoices) { (col, name) ->
                val on = m.sort.column == col
                FilterChip(selected = on, onClick = { if (on) m.toggleSort(col) else m.sort = os.deckbridge.manager.Sort(col, col != TrackColumn.ID && col != TrackColumn.RATING && col != TrackColumn.BPM) }, label = {
                    Text(name + if (on) (if (m.sort.ascending) " ↑" else " ↓") else "")
                })
            }
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (m.query.isBlank()) "A biblioteca está vazia." else "Nada encontrado.", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (m.query.isBlank()) {
                        Text("Importe músicas do aparelho (WAV, MP3, FLAC, AAC). Elas são analisadas aqui mesmo: BPM, tom, grade e cues.", color = Palette.muted, fontSize = 14.sp)
                        OutlinedButton(onClick = actions.pickAudio) { Text("Importar músicas") }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)) {
                items(rows, key = { it.id }) { t ->
                    TrackRow(app, t, selected = t.id in m.selection, edited = t.id in m.editedTrackIds())
                }
            }
        }
        if (app.selecting) SelectionBar(app, rows) { dialog = it }
    }
    LibraryDialogs(app, dialog) { dialog = null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(app: ManagerApp, t: TrackItem, selected: Boolean, edited: Boolean) {
    val m = app.model
    val open = app.detailId == t.id
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Palette.surfaceHigh else if (open) Palette.surface else Color.Transparent)
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
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.selecting) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(if (selected) Palette.accent else Palette.line), contentAlignment = Alignment.Center) {
                if (selected) Text("✓", color = Palette.bg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
        }
        val color = Palette.trackColors.getOrNull(t.colorId - 1)
        Box(Modifier.size(10.dp).clip(CircleShape).background(color ?: Color.Transparent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "sem artista" },
                color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(stars(t.rating) + (if (edited) "  · editada" else "") + (if (!m.hasAnalysis(t)) "  · sem análise" else ""), color = if (edited) Palette.warn else Palette.muted, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (t.bpm > 0) "%.1f".format(t.bpm) else "—", color = Palette.accent2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(t.key.ifEmpty { "—" }, color = Palette.muted, fontSize = 13.sp)
            Text(formatDuration(t.durationSeconds), color = Palette.muted, fontSize = 12.sp)
        }
    }
}

fun stars(n: Int) = "★".repeat(n.coerceIn(0, 5)) + "☆".repeat(5 - n.coerceIn(0, 5))
fun formatDuration(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

sealed interface Dialog {
    data object AddToPlaylist : Dialog
    data object Rate : Dialog
    data object Color : Dialog
    data object Remove : Dialog
}

@Composable
private fun SelectionBar(app: ManagerApp, rows: List<TrackItem>, show: (Dialog) -> Unit) {
    val m = app.model
    Column(Modifier.fillMaxWidth().background(Palette.surfaceHigh).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${m.selection.size} selecionada(s)", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = { m.selection = rows.map { it.id }.toSet() }) { Text("Todas") }
            TextButton(onClick = { m.selection = emptySet(); app.selecting = false }) { Text("Fechar") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { show(Dialog.AddToPlaylist) }, Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("Playlist", fontSize = 13.sp) }
            OutlinedButton(onClick = { show(Dialog.Rate) }, Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("Nota", fontSize = 13.sp) }
            OutlinedButton(onClick = { show(Dialog.Color) }, Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("Cor", fontSize = 13.sp) }
            OutlinedButton(onClick = { show(Dialog.Remove) }, Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("Remover", fontSize = 13.sp, color = Palette.danger) }
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
        Dialog.AddToPlaylist -> PlaylistPicker(app, onPick = { id ->
            val p = m.playlist(id)
            val have = m.view?.entries?.get(id).orEmpty().toSet()
            val fresh = ids.filter { it !in have }
            if (fresh.isNotEmpty() && p != null) m.submit(Edit.AddToPlaylist(id, fresh), "Adicionar ${fresh.size} a \"${p.name}\"")
            done()
        }, onCreate = { name ->
            val id = m.nextPlaylistId()
            if (m.submit(Edit.CreatePlaylist(id, 0, name, false), "Criar playlist \"$name\"")) m.submit(Edit.AddToPlaylist(id, ids), "Adicionar ${ids.size} a \"$name\"")
            done()
        }, onClose = close)
        Dialog.Rate -> ChoiceDialog("Nota", (0..5).map { if (it == 0) "Sem nota" else stars(it) }, onPick = { n ->
            m.submit(Edit.SetTracks(ids, TrackPatch(rating = n)), "Nota $n em ${ids.size} faixa(s)")
            done()
        }, onClose = close)
        Dialog.Color -> ColorDialog(onPick = { id ->
            m.submit(Edit.SetTracks(ids, TrackPatch(colorId = id)), "Cor em ${ids.size} faixa(s)")
            done()
        }, onClose = close)
        Dialog.Remove -> ConfirmDialog(
            title = "Remover ${ids.size} faixa(s) da biblioteca?",
            text = "Elas saem do banco, das playlists e do histórico. Os arquivos de áudio não são apagados. Dá para desfazer até salvar.",
            confirm = "Remover", danger = true,
            onConfirm = {
                m.submit(Edit.RemoveTracks(ids), "Remover ${ids.size} faixa(s)")
                if (app.detailId in ids) app.detailId = null
                done()
            }, onClose = close,
        )
        null -> {}
    }
}
