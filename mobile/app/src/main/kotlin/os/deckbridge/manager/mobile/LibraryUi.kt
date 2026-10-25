package os.deckbridge.manager.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import os.deckbridge.manager.smart.QuickFilter
import os.deckbridge.manager.smart.Smart

private val sortChoices = listOf(TrackColumn.ARTIST to "Artista", TrackColumn.TITLE to "Título", TrackColumn.BPM to "BPM", TrackColumn.KEY to "Tom", TrackColumn.RATING to "Nota", TrackColumn.ID to "Recentes")

@Composable
fun LibraryTab(app: ManagerApp, actions: Actions) {
    val m = app.model
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val rows = m.rows()
    val counts = m.filterCounts()
    val unrated = counts[QuickFilter.UNRATED] ?: 0
    val unanalyzed = counts[QuickFilter.UNANALYZED] ?: 0
    val narrowed = m.query.isNotBlank() || m.filter != QuickFilter.ALL
    Column(Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            item(key = "guide") { GuideCard(app, actions) }
            item(key = "search") {
                OutlinedTextField(
                    value = m.query, onValueChange = { m.query = it }, singleLine = true,
                    placeholder = { Text("Buscar ou filtrar: bpm:120-128 tom:8A nota:4", maxLines = 1) },
                    trailingIcon = { if (m.query.isNotEmpty()) TextButton(onClick = { m.query = "" }) { Text("✕", fontSize = 18.sp) } else TextButton(onClick = { dialog = Dialog.SearchHelp }) { Text("?", fontSize = 18.sp) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            item(key = "actions") {
                LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.view?.tracks?.isNotEmpty() == true) {
                        item { ActionChip(if (unrated > 0) "▶ Triagem · $unrated sem nota" else "▶ Triagem", primary = unrated > 0) { app.startTriage() } }
                        if (unanalyzed > 0) item { ActionChip("Analisar $unanalyzed", primary = false) { app.analyzeAll() } }
                        item { ActionChip("✨ Arrumar tudo", primary = false) { app.openTidy() } }
                    }
                    item {
                        val col = sortChoices.firstOrNull { it.first == m.sort.column }?.second ?: "Ordem"
                        ActionChip("↕ $col${if (m.sort.ascending) " ↑" else " ↓"}", primary = false) { dialog = Dialog.Sort }
                    }
                    if (narrowed && rows.isNotEmpty()) item { ActionChip("Salvar lista como playlist", primary = false) { dialog = Dialog.SaveList } }
                }
            }
            item(key = "filters") {
                LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(QuickFilter.values().filter { it == QuickFilter.ALL || it == m.filter || (counts[it] ?: 0) > 0 }) { f ->
                        FilterChip(selected = m.filter == f, onClick = { m.filter = f }, label = { Text(if (f == QuickFilter.ALL) f.title else "${f.title} · ${counts[f] ?: 0}") })
                    }
                }
            }
            item(key = "target") { TargetBar(app) { dialog = Dialog.PickTarget } }
            if (rows.isEmpty()) {
                item(key = "empty") { EmptyList(app, actions) }
            } else {
                items(rows, key = { it.id }) { t -> TrackRow(app, t, selected = t.id in m.selection, edited = t.id in m.editedTrackIds()) }
            }
        }
        if (app.selecting) SelectionBar(app, rows) { dialog = it }
    }
    LibraryDialogs(app, dialog) { dialog = null }
}

@Composable
private fun ActionChip(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (primary) Palette.accent else Palette.surfaceHigh).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (primary) Palette.bg else Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun EmptyList(app: ManagerApp, actions: Actions) {
    val m = app.model
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (m.view?.tracks?.isEmpty() == true) {
            Text("A biblioteca está vazia.", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Importe músicas do aparelho (WAV, MP3, FLAC, AAC). Elas são analisadas aqui mesmo: BPM, tom, grade e cues.", color = Palette.muted, fontSize = 14.sp)
            Button(onClick = actions.pickAudio, modifier = Modifier.height(52.dp)) { Text("Importar músicas") }
        } else {
            Text("Nenhuma faixa nesta lista.", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(if (m.filter != QuickFilter.ALL) m.filter.hint else "Mude a busca.", color = Palette.muted, fontSize = 14.sp)
            OutlinedButton(onClick = { m.query = ""; m.filter = QuickFilter.ALL }) { Text("Limpar busca e filtro") }
        }
    }
}

/** "Primeiros passos": o caminho importar → analisar → marcar → playlist → exportar, com o próximo passo em destaque. */
@Composable
private fun GuideCard(app: ManagerApp, actions: Actions) {
    val m = app.model
    val v = m.view ?: return
    val steps = Smart.journey(v, { m.hasAnalysis(it) }, app.exportedOnce)
    if (app.guideHidden || steps.all { it.done }) return
    val next = steps.first { !it.done }
    val doneCount = steps.count { it.done }
    var expanded by remember { mutableStateOf(doneCount == 0) }
    Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Palette.surface).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text("Primeiros passos · $doneCount de ${steps.size}", color = Palette.accent2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            Text(if (expanded) "▲" else "▼", color = Palette.muted, fontSize = 12.sp)
            TextButton(onClick = { app.hideGuide() }) { Text("Ocultar", fontSize = 12.sp) }
        }
        if (expanded) steps.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(if (s.done) Palette.accent else if (s === next) Palette.accent2 else Palette.line), contentAlignment = Alignment.Center) {
                    if (s.done) Text("✓", color = Palette.bg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(s.title, color = if (s.done) Palette.muted else Palette.text, fontSize = 14.sp, fontWeight = if (s === next) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 10.dp, top = 3.dp, bottom = 3.dp).weight(1f))
                Text(s.detail, color = Palette.muted, fontSize = 12.sp)
            }
        }
        Button(onClick = {
            when (next.action) {
                Smart.StepAction.IMPORT -> actions.pickAudio()
                Smart.StepAction.ANALYZE -> app.analyzeAll()
                Smart.StepAction.TRIAGE -> app.startTriage()
                Smart.StepAction.PLAYLIST -> app.tab = Tab.PLAYLISTS
                Smart.StepAction.EXPORT -> app.tab = Tab.USB
            }
        }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Próximo: ${next.title} →") }
    }
}

@Composable
private fun TargetBar(app: ManagerApp, pick: () -> Unit) {
    val m = app.model
    val name = m.target?.let { m.playlist(it)?.name }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (name == null) {
            TextButton(onClick = pick) { Text("＋ Definir playlist alvo (um toque para adicionar)", fontSize = 13.sp) }
        } else {
            Text("Alvo:", color = Palette.muted, fontSize = 13.sp)
            Box(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(16.dp)).background(Palette.surfaceHigh).clickable(onClick = pick).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("≡ $name · ${m.view?.entries?.get(m.target!!)?.size ?: 0}", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { m.target = null }) { Text("✕", fontSize = 16.sp) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(app: ManagerApp, t: TrackItem, selected: Boolean, edited: Boolean) {
    val m = app.model
    val open = app.detailId == t.id
    val inTarget = m.target?.let { t.id in m.view?.entries?.get(it).orEmpty() } == true
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
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Estrelas tocáveis na própria linha: marcar sem abrir a faixa.
                for (n in 1..5) {
                    Box(Modifier.width(30.dp).height(32.dp).clickable { m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = if (t.rating == n) 0 else n)), "Nota ${if (t.rating == n) 0 else n} em \"${t.title}\"") }, contentAlignment = Alignment.CenterStart) {
                        Text(if (n <= t.rating) "★" else "☆", color = if (n <= t.rating) Palette.warn else Palette.muted, fontSize = 18.sp)
                    }
                }
                val flags = (if (edited) "editada " else "") + (if (!m.hasAnalysis(t)) "sem análise" else "")
                if (flags.isNotBlank()) Text(flags.trim(), color = if (edited) Palette.warn else Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (t.bpm > 0) "%.1f".format(t.bpm) else "—", color = Palette.accent2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(t.key.ifEmpty { "—" }, color = Palette.muted, fontSize = 13.sp)
            Text(formatDuration(t.durationSeconds), color = Palette.muted, fontSize = 12.sp)
        }
        if (m.target != null && !app.selecting) {
            Box(
                Modifier.size(56.dp).clickable { app.did(m.toggleInTarget(t.id)) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(if (inTarget) Palette.accent else Palette.surfaceHigh), contentAlignment = Alignment.Center) {
                    Text(if (inTarget) "✓" else "+", color = if (inTarget) Palette.bg else Palette.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else Spacer(Modifier.width(14.dp))
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
                }, modifier = Modifier.height(44.dp)) { Text("＋ ao alvo", fontSize = 13.sp, color = Palette.accent) }
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
        Dialog.PickTarget -> PlaylistPicker(app, title = "Playlist alvo", onPick = { id ->
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
    app.tidy?.let { TidyDialog(app, it) }
}
