package os.deckbridge.manager.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.PlaylistItem

/**
 * Playlists. Em pé: a lista e, ao abrir uma, as faixas dela em tela cheia. Deitado: lista e faixas lado a lado.
 * A ordem das faixas se muda com ▲ ▼ (arrastar não é confiável com o dedo numa lista que rola).
 */
@Composable
fun PlaylistsTab(app: ManagerApp) {
    val m = app.model
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    val view = m.view
    val open = m.openPlaylist?.let { id -> view?.playlists?.firstOrNull { it.id == id } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 560.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(260.dp).fillMaxHeight()) { PlaylistList(app) { dialog = it } }
                Box(Modifier.weight(1f).fillMaxHeight().background(Palette.bg)) {
                    if (open != null && !open.folder) PlaylistTracks(app, open, onBack = null) { dialog = it } else Hint("Escolha uma playlist.")
                }
            }
        } else if (open != null && !open.folder) {
            PlaylistTracks(app, open, onBack = { m.openPlaylist = null }) { dialog = it }
        } else {
            PlaylistList(app) { dialog = it }
        }
    }
    PlaylistDialogs(app, dialog) { dialog = null }
}

sealed interface PlaylistDialog {
    data object New : PlaylistDialog
    data class Rename(val p: PlaylistItem) : PlaylistDialog
    data class Delete(val p: PlaylistItem) : PlaylistDialog
}

@Composable
private fun Hint(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, color = Palette.muted, fontSize = 14.sp) }

@Composable
private fun PlaylistList(app: ManagerApp, show: (PlaylistDialog) -> Unit) {
    val m = app.model
    val view = m.view
    val lists = view?.playlists.orEmpty().sortedWith(compareBy({ it.parentId }, { it.sort }, { it.id }))
    Column(Modifier.fillMaxSize()) {
        Button(onClick = { show(PlaylistDialog.New) }, modifier = Modifier.fillMaxWidth().padding(12.dp).height(48.dp)) { Text("+ Nova playlist") }
        if (lists.isEmpty()) {
            Hint("Nenhuma playlist ainda. Crie uma aqui ou selecione faixas na Biblioteca e use \"Playlist\".")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(lists, key = { it.id }) { p ->
                    val count = view?.entries?.get(p.id)?.size ?: 0
                    val on = m.openPlaylist == p.id
                    Row(
                        Modifier.fillMaxWidth().background(if (on) Palette.surfaceHigh else Color.Transparent).clickable { m.openPlaylist = p.id }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text((if (p.folder) "▸ " else "") + p.name, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (p.folder) "pasta" else "$count faixa(s)", color = Palette.muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = { show(PlaylistDialog.Rename(p)) }) { Text("Renomear", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTracks(app: ManagerApp, p: PlaylistItem, onBack: (() -> Unit)?, show: (PlaylistDialog) -> Unit) {
    val m = app.model
    val ids = m.view?.entries?.get(p.id).orEmpty().filter { m.view?.track(it) != null }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Palette.surface).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack) { Text("‹ Voltar", fontSize = 16.sp) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(p.name, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${ids.size} faixa(s)", color = Palette.muted, fontSize = 12.sp)
            }
            TextButton(onClick = { m.target = p.id; app.did("\"${p.name}\" é a playlist alvo. Na Biblioteca, um toque no + adiciona.") }, enabled = m.target != p.id) { Text(if (m.target == p.id) "Alvo ✓" else "Usar como alvo", fontSize = 12.sp) }
            TextButton(onClick = { show(PlaylistDialog.Delete(p)) }) { Text("Apagar", color = Palette.danger) }
        }
        if (ids.size > 1) {
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ordenar:", color = Palette.muted, fontSize = 13.sp)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp)) {
                    items(os.deckbridge.manager.smart.Smart.SortMode.values().toList()) { mode ->
                        androidx.compose.material3.AssistChip(onClick = {
                            if (m.sortPlaylist(p.id, mode)) app.did("\"${p.name}\" ordenada: ${mode.title}") else app.notice = os.deckbridge.manager.Banner("Já está nessa ordem.", false)
                        }, label = { Text(mode.title, fontSize = 12.sp) })
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            Hint("Playlist vazia. Na Biblioteca, segure uma faixa para selecionar e toque em \"Playlist\".")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(ids.withIndex().toList(), key = { it.value }) { (n, id) ->
                    val t = m.view!!.track(id)!!
                    Row(Modifier.fillMaxWidth().clickable { app.detailId = id }.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${n + 1}", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist + if (t.bpm > 0) " · %.0f BPM".format(t.bpm) else "", color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        MoveButton("▲", enabled = n > 0) { swap(app, p, ids, n, n - 1) }
                        MoveButton("▼", enabled = n < ids.lastIndex) { swap(app, p, ids, n, n + 1) }
                        MoveButton("✕", enabled = true, color = Palette.danger) { m.submit(Edit.RemoveFromPlaylist(p.id, listOf(id)), "Tirar \"${t.title}\" de \"${p.name}\"") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveButton(glyph: String, enabled: Boolean, color: Color = Palette.text, onClick: () -> Unit) {
    Box(Modifier.width(48.dp).height(48.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, color = if (enabled) color else Palette.line, fontSize = 18.sp)
    }
}

private fun swap(app: ManagerApp, p: PlaylistItem, ids: List<Int>, a: Int, b: Int) {
    val order = ids.toMutableList()
    order[a] = ids[b].also { order[b] = ids[a] }
    app.model.submit(Edit.ReorderPlaylist(p.id, order), "Reordenar \"${p.name}\"")
}

@Composable
private fun PlaylistDialogs(app: ManagerApp, dialog: PlaylistDialog?, close: () -> Unit) {
    val m = app.model
    when (dialog) {
        PlaylistDialog.New -> NameDialog("Nova playlist", confirm = "Criar", onName = { name ->
            val id = m.nextPlaylistId()
            if (m.submit(Edit.CreatePlaylist(id, 0, name, false), "Criar playlist \"$name\"")) m.openPlaylist = id
            close()
        }, onClose = close)
        is PlaylistDialog.Rename -> NameDialog("Renomear playlist", dialog.p.name, "Renomear", onName = { name ->
            m.submit(Edit.RenamePlaylist(dialog.p.id, name), "Renomear \"${dialog.p.name}\" para \"$name\"")
            close()
        }, onClose = close)
        is PlaylistDialog.Delete -> ConfirmDialog(
            "Apagar \"${dialog.p.name}\"?", "As faixas continuam na biblioteca. Dá para desfazer até salvar.", "Apagar", danger = true,
            onConfirm = {
                m.submit(Edit.DeletePlaylist(dialog.p.id), "Apagar playlist \"${dialog.p.name}\"")
                m.openPlaylist = null
                close()
            }, onClose = close,
        )
        null -> {}
    }
}
