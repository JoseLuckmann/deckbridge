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
    val unrated = counts[QuickFilter.UNRATED] ?: 0
    val unanalyzed = counts[QuickFilter.UNANALYZED] ?: 0
    if (app.helpRequested) { app.helpRequested = false; dialog = Dialog.SearchHelp }
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
            if (rows.isNotEmpty()) item(key = "header") { ListHeader(app) }
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
    if (primary) Button(onClick = onClick, modifier = Modifier.height(38.dp)) { Text(text, fontSize = 12.sp, maxLines = 1) }
    else OutlinedButton(onClick = onClick, modifier = Modifier.height(38.dp)) { Text(text, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun EmptyList(app: ManagerApp, actions: Actions) {
    val m = app.model
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (m.view?.tracks?.isEmpty() == true) {
            Text("A biblioteca está vazia.", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Importe músicas do aparelho (WAV, MP3, FLAC, AAC). Elas são analisadas aqui mesmo: BPM, tom, grade e cues.", color = Palette.muted, fontSize = 14.sp)
            Button(onClick = actions.pickAudio, modifier = Modifier.height(52.dp)) { Text("IMPORTAR MÚSICAS") }
        } else {
            Text("Nenhuma faixa nesta lista.", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(if (m.filter != QuickFilter.ALL) m.filter.hint else "Mude a busca.", color = Palette.muted, fontSize = 14.sp)
            OutlinedButton(onClick = { m.query = ""; m.filter = QuickFilter.ALL }) { Text("LIMPAR BUSCA E FILTRO") }
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
    Column(Modifier.fillMaxWidth().padding(10.dp).rbPanel().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text("PRIMEIROS PASSOS · $doneCount DE ${steps.size}", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            Text(if (expanded) "▲" else "▼", color = Palette.muted, fontSize = 12.sp)
            TextButton(onClick = { app.hideGuide() }) { Text("OCULTAR", fontSize = 11.sp) }
        }
        if (expanded) steps.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).border(BorderStroke(1.dp, if (s.done) Palette.keyInk else if (s === next) Palette.blue else Palette.line), RbShape).background(if (s.done) Palette.keyFill else Palette.bg, RbShape), contentAlignment = Alignment.Center) {
                    if (s.done) Text("✓", color = Palette.keyInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(s.title, color = if (s.done) Palette.muted else Palette.text, fontSize = 14.sp, fontWeight = if (s === next) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 10.dp, top = 3.dp, bottom = 3.dp).weight(1f))
                Text(s.detail, color = Palette.orange, fontSize = 12.sp)
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
        }, modifier = Modifier.fillMaxWidth().height(46.dp)) { Text("PRÓXIMO: ${next.title.uppercase()} →", fontSize = 13.sp) }
    }
}

@Composable
private fun TargetBar(app: ManagerApp, pick: () -> Unit) {
    val m = app.model
    val name = m.target?.let { m.playlist(it)?.name }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (name == null) {
            TextButton(onClick = pick) { Text("＋ PLAYLIST ALVO (UM TOQUE PARA ADICIONAR)", fontSize = 12.sp) }
        } else {
            Text("ALVO", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Box(Modifier.padding(start = 8.dp).clip(RbShape).background(Palette.blueActiveFill).border(BorderStroke(1.dp, Palette.blue), RbShape).clickable(onClick = pick).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("≡ $name · ${m.view?.entries?.get(m.target!!)?.size ?: 0}", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { m.target = null }) { Text("✕", fontSize = 16.sp) }
        }
    }
}

/** Cabeçalho da lista como no player: # TÍTULO … BPM TOM; tocar ordena e tocar de novo inverte. */
@Composable
private fun ListHeader(app: ManagerApp) {
    val m = app.model
    @Composable fun head(label: String, col: TrackColumn, mod: Modifier) {
        val on = m.sort.column == col
        Text(
            label + if (on) (if (m.sort.ascending) " ▲" else " ▼") else "",
            color = if (on) Palette.orange else Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
            modifier = mod.clickable {
                if (on) m.toggleSort(col) else m.sort = os.deckbridge.manager.Sort(col, col != TrackColumn.ID && col != TrackColumn.RATING && col != TrackColumn.BPM)
            }.padding(vertical = 10.dp),
        )
    }
    Row(Modifier.fillMaxWidth().background(Palette.surface).border(BorderStroke(0.5.dp, Palette.line)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        head("TÍTULO", TrackColumn.ARTIST, Modifier.weight(1f).padding(start = 98.dp))
        head("NOTA", TrackColumn.RATING, Modifier.padding(end = 14.dp))
        head("BPM", TrackColumn.BPM, Modifier.padding(end = 12.dp))
        head("TOM", TrackColumn.KEY, Modifier.padding(end = 38.dp))
    }
}

/** Disco da faixa como na lista do player: quadrado na cor da faixa com o anel branco e o ponto. */
@Composable
private fun Disc(color: Color?) {
    Canvas(Modifier.size(36.dp).background(color ?: Palette.surfaceHigh)) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Palette.text, radius = size.minDimension * 0.36f, center = c, style = Stroke(width = 3f))
        drawCircle(Palette.text, radius = size.minDimension * 0.07f, center = c)
    }
}

/** Miniatura da onda (a prévia do ANLZ), com cache por arquivo de análise. */
@Composable
private fun MiniWave(app: ManagerApp, t: TrackItem) {
    val wave = remember(t.id, t.analyzePath) { app.waveFor(t) }
    Canvas(Modifier.width(52.dp).height(36.dp).background(Palette.waveBg)) {
        if (wave.isEmpty()) return@Canvas
        val col = size.width / 52f
        for (i in 0 until 52) {
            val v = (wave[(i * wave.size / 52).coerceAtMost(wave.size - 1)].toInt() and 0xFF) / 255f
            val h = (v * size.height * 0.9f).coerceAtLeast(1f)
            drawRect(Palette.wave, Offset(i * col, (size.height - h) / 2), Size(col.coerceAtLeast(1f), h))
        }
    }
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
    Row(
        Modifier.fillMaxWidth()
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
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.selecting) {
            Box(Modifier.size(20.dp).border(BorderStroke(1.dp, if (selected) Palette.blue else Palette.line), RbShape).background(if (selected) Palette.blueActiveFill else Palette.bg), contentAlignment = Alignment.Center) {
                if (selected) Text("✓", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
        }
        Disc(Palette.trackColors.getOrNull(t.colorId - 1))
        Spacer(Modifier.width(4.dp))
        MiniWave(app, t)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "sem artista" },
                color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Estrelas tocáveis na própria linha: marcar sem abrir a faixa.
                for (n in 1..5) {
                    Box(Modifier.width(28.dp).height(30.dp).clickable { m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = if (t.rating == n) 0 else n)), "Nota ${if (t.rating == n) 0 else n} em \"${t.title}\"") }, contentAlignment = Alignment.CenterStart) {
                        Text(if (n <= t.rating) "★" else "☆", color = if (n <= t.rating) Palette.orange else Palette.muted, fontSize = 17.sp)
                    }
                }
                val flags = (if (edited) "EDITADA " else "") + (if (!m.hasAnalysis(t)) "SEM ANÁLISE" else "")
                if (flags.isNotBlank()) Text(flags.trim(), color = Palette.orange, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Text(if (t.bpm > 0) "%.1f".format(t.bpm) else "—", color = Palette.text, fontSize = 15.sp, fontFamily = Mono, modifier = Modifier.padding(start = 6.dp))
        Box(Modifier.padding(start = 8.dp).width(40.dp).height(24.dp).background(if (compatible) Palette.keyFill else Color.Transparent), contentAlignment = Alignment.Center) {
            Text(t.key.ifEmpty { "—" }, color = if (compatible) Palette.keyInk else Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (m.target != null && !app.selecting) {
            Box(Modifier.size(52.dp).clickable { app.did(m.toggleInTarget(t.id)) }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(34.dp).border(BorderStroke(1.dp, if (inTarget) Palette.blue else Palette.line), RbShape).background(if (inTarget) Palette.blueActiveFill else Palette.surfaceHigh), contentAlignment = Alignment.Center) {
                    Text(if (inTarget) "✓" else "+", color = if (inTarget) Palette.text else Palette.blue, fontSize = 19.sp, fontWeight = FontWeight.Bold)
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
                }, modifier = Modifier.height(44.dp)) { Text("＋ ao alvo", fontSize = 13.sp, color = Palette.blue) }
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
