package os.deckbridge.manager.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
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
/** Pôr músicas na playlist [id]: a lista de músicas abre com o ＋ em cada linha apontando para ela. */
fun addTo(app: ManagerApp, id: Int) {
    app.model.target = id
    app.model.openPlaylist = null
    app.tab = Tab.LIBRARY
    app.did("＋ em cada música")
}

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
                    if (open != null && !open.folder) PlaylistTracks(app, open, onBack = null) { dialog = it } else Hint("")
                }
            }
        } else if (open != null && !open.folder) {
            PlaylistTracks(app, open, onBack = { m.openPlaylist = null }) { dialog = it }
        } else {
            PlaylistList(app) { dialog = it }
        }
    }
    PlaylistDialogs(app, dialog, { dialog = it }) { dialog = null }
}

sealed interface PlaylistDialog {
    data object New : PlaylistDialog
    data class Rename(val p: PlaylistItem) : PlaylistDialog
    data class Delete(val p: PlaylistItem) : PlaylistDialog
    data class More(val p: PlaylistItem) : PlaylistDialog
    data class Sort(val p: PlaylistItem) : PlaylistDialog
    data object Build : PlaylistDialog
}

@Composable
private fun Hint(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, color = Palette.muted, fontSize = 14.sp) }

@Composable
private fun PlaylistList(app: ManagerApp, show: (PlaylistDialog) -> Unit) {
    val m = app.model
    val view = m.view
    val lists = view?.playlists.orEmpty().sortedWith(compareBy({ it.parentId }, { it.sort }, { it.id }))
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { show(PlaylistDialog.New) }, modifier = Modifier.weight(1f).height(46.dp)) { Text("+ NOVA PLAYLIST", fontSize = 13.sp) }
            // Montar um set pronto para mixar a partir da biblioteca, das melhores ou de uma playlist.
            val noTracks = view?.tracks.isNullOrEmpty()
            Button(
                onClick = { show(PlaylistDialog.Build) }, enabled = !noTracks,
                modifier = Modifier.weight(1f).spotlight(lists.isEmpty() && !noTracks && !app.guideHidden).height(46.dp),
            ) { Text("MONTAR SET", fontSize = 13.sp) }
        }
        if (lists.isEmpty()) {
            // Sem músicas não há o que pôr numa playlist: o caminho é importar.
            if (view?.tracks.isNullOrEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Button(onClick = { app.tab = Tab.LIBRARY }, modifier = Modifier.height(52.dp)) { Text("IMPORTAR MÚSICAS") }
            } else Hint("")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(lists, key = { it.id }) { p ->
                    val count = view?.entries?.get(p.id)?.size ?: 0
                    val on = m.openPlaylist == p.id
                    Row(
                        Modifier.fillMaxWidth().background(if (on) Palette.selectedRow else Color.Transparent).clickable { m.openPlaylist = p.id }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text((if (p.folder) "▸ " else "") + p.name, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            
                        }
                        Text(if (p.folder) "" else "$count", color = Palette.orange, fontSize = 13.sp, fontFamily = Mono)
                        if (m.target == p.id) Text("  ＋", color = Palette.blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
        Row(Modifier.fillMaxWidth().background(Palette.bg).border(androidx.compose.foundation.BorderStroke(1.dp, Palette.line)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack) { Text("‹ VOLTAR", fontSize = 13.sp) }
            Text(p.name, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
            Text("${ids.size}", color = Palette.orange, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.padding(start = 8.dp).weight(1f))
            // ＋ põe músicas; MIXAR abre o set (transições, ouvir, ensaiar).
            OutlinedButton(onClick = { addTo(app, p.id) }, modifier = Modifier.height(38.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("＋", fontSize = 16.sp) }
            if (ids.size >= 2) Button(
                onClick = { app.seen("set"); app.sets.open(p.id) },
                modifier = Modifier.padding(start = 6.dp).spotlight(app.hint("set")).height(38.dp),
            ) { Text("MIXAR", fontSize = 12.sp) }
            // Tudo que não é usado o tempo todo fica no ⋯.
            TextButton(onClick = { show(PlaylistDialog.More(p)) }) { Text("⋯", fontSize = 22.sp) }
        }
        if (ids.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Button(onClick = { addTo(app, p.id) }, modifier = Modifier.spotlight(!app.guideHidden).height(52.dp)) { Text("ADICIONAR MÚSICAS") }
            }
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
private fun PlaylistDialogs(app: ManagerApp, dialog: PlaylistDialog?, show: (PlaylistDialog) -> Unit, close: () -> Unit) {
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
            "Apagar \"${dialog.p.name}\"?", "As faixas ficam na biblioteca.", "Apagar", danger = true,
            onConfirm = {
                m.submit(Edit.DeletePlaylist(dialog.p.id), "Apagar playlist \"${dialog.p.name}\"")
                m.openPlaylist = null
                close()
            }, onClose = close,
        )
        is PlaylistDialog.More -> {
            val p = dialog.p
            val options = listOf(if (m.target == p.id) "Parar de adicionar aqui" else "Adicionar músicas aqui", "Ordenar…", "Renomear", "Apagar")
            ChoiceDialog(p.name, options, onPick = { n ->
                when (n) {
                    0 -> { if (m.target == p.id) m.target = null else addTo(app, p.id); close() }
                    1 -> show(PlaylistDialog.Sort(p))
                    2 -> show(PlaylistDialog.Rename(p))
                    else -> show(PlaylistDialog.Delete(p))
                }
            }, onClose = close)
        }
        is PlaylistDialog.Sort -> {
            val modes = os.deckbridge.manager.smart.Smart.SortMode.values().toList()
            ChoiceDialog("Ordenar", modes.map { it.title }, onPick = { n ->
                if (m.sortPlaylist(dialog.p.id, modes[n])) app.did("Ordenada")
                close()
            }, onClose = close)
        }
        PlaylistDialog.Build -> BuildSetDialog(app, onClose = close)
        null -> {}
    }
}

/**
 * Montar set: de onde (a biblioteca, as de nota 4 ou mais, ou uma playlist), quanto tempo e que forma. O app escolhe e
 * ordena pelas trocas de tom e andamento, cria a playlist e abre o SET dela para ouvir e ajustar.
 */
@Composable
private fun BuildSetDialog(app: ManagerApp, onClose: () -> Unit) {
    val v = app.model.view ?: return
    val lists = v.playlists.filter { !it.folder && v.entries[it.id].orEmpty().size >= 2 }.take(6)
    val sources = listOf("Toda a biblioteca", "★4+") + lists.map { it.name }
    var source by remember { mutableStateOf(0) }
    var minutes by remember { mutableStateOf(60) }
    var shape by remember { mutableStateOf(os.deckbridge.manager.mix.SetShape.FLOW) }
    fun pool(): List<os.deckbridge.manager.pdb.TrackItem> = when (source) {
        0 -> v.tracks
        1 -> v.tracks.filter { it.rating >= 4 }
        else -> v.entries[lists[source - 2].id].orEmpty().mapNotNull { v.track(it) }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Montar set") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DE ONDE", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sources.forEachIndexed { n, name -> FilterChip(source == n, { source = n }, { Text(name, maxLines = 1) }) }
                }
                Text("${pool().count { it.bpm > 0 }} faixas", color = Palette.muted, fontSize = 12.sp)
                Text("DURAÇÃO", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 60, 90, 120).forEach { m -> FilterChip(minutes == m, { minutes = m }, { Text("$m min") }) }
                }
                Text("FORMA", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    os.deckbridge.manager.mix.SetShape.values().forEach { sh -> FilterChip(shape == sh, { shape = sh }, { Text(sh.title, maxLines = 1) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val name = "Set $minutes min · ${shape.title}"
                if (app.sets.buildSet(pool(), minutes, shape, name) != null) onClose()
            }, enabled = pool().count { it.bpm > 0 } >= 2) { Text("MONTAR") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
        containerColor = Palette.surfaceHigh,
    )
}
