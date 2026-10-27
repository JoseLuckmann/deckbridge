package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.manager.ManagerModel
import os.deckbridge.manager.anlz.Cue
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.pdb.TrackPatch

fun formatMs(ms: Int): String = "%d:%02d.%03d".format(ms / 60000, ms / 1000 % 60, ms % 1000)

/** A–H para hot cues; "M" para cue de memória. */
fun hotLetter(hot: Int) = if (hot in 1..8) ('A' + hot - 1).toString() else "M"


/** Detalhes da faixa: marcar (nota, cor, comentário), editar campos, analisar e mexer em grade e cues. */
@Composable
fun DetailPanel(app: ManagerApp, t: TrackItem, onBack: (() -> Unit)? = null) {
    val m = app.model
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Palette.bg).border(androidx.compose.foundation.BorderStroke(1.dp, Palette.line)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Voltar" }) { Text("‹", fontSize = 22.sp) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(t.artist.ifEmpty { "sem artista" }, color = Palette.muted, fontSize = 13.sp, maxLines = 1)
            }
            val list = m.rows().map { it.id }
            val at = list.indexOf(t.id)
            if (at >= 0) {
                TextButton(onClick = { app.preview.stop(); app.detailId = list[at - 1] }, enabled = at > 0) { Text("‹", fontSize = 26.sp) }
                TextButton(onClick = { app.preview.stop(); app.detailId = list[at + 1] }, enabled = at < list.lastIndex) { Text("›", fontSize = 26.sp) }
            }
            if (onBack == null) TextButton(onClick = { app.detailId = null }) { Text("✕", fontSize = 16.sp) }
        }
        // Três abas curtas no lugar de uma página longa.
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DetailPage.values().forEach { pg ->
                FilterChip(selected = app.detailPage == pg, onClick = { app.detailPage = pg }, label = { Text(pg.title) }, modifier = Modifier.weight(1f))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (app.detailPage) {
                // Na ordem de quem marca: o que a faixa é, ouvir, dar nota e cor, pôr nas playlists.
                DetailPage.MARK -> {
                    StatsRow(t)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayableWave(app, t)
                        ListenRow(app, t)
                    }
                    Marking(app, t)
                    PlaylistChips(app, t)
                    TrackPlaces(app, t.id)
                }
                DetailPage.CUES -> CueSection(app, t)
                DetailPage.INFO -> Fields(app, t)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

enum class DetailPage(val title: String) { MARK("MARCAR"), CUES("CUES"), INFO("INFO") }

@Composable
private fun SectionTitle(text: String) = Text(text.uppercase(), color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)

@Composable
private fun Marking(app: ManagerApp, t: TrackItem) {
    val m = app.model
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            for (n in 1..5) {
                Text(
                    if (n <= t.rating) "★" else "☆", color = if (n <= t.rating) Palette.orange else Palette.muted, fontSize = 34.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).height(52.dp).clickable { m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = if (t.rating == n) 0 else n)), "Nota ${if (t.rating == n) 0 else n} em \"${t.title}\"") },
                )
            }
        }
        // As oito cores ocupam a largura toda: alvos grandes, sem sobra à direita.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Palette.trackColors.forEachIndexed { n, c ->
                val on = t.colorId == n + 1
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RbShape).background(c).clickable { m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(colorId = if (on) 0 else n + 1)), "Cor em \"${t.title}\"") },
                    contentAlignment = Alignment.Center,
                ) { if (on) Text("✓", color = Palette.bg, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Em que playlists a faixa está: tocar liga e desliga. "＋" cria uma nova já com a faixa. */
@Composable
private fun PlaylistChips(app: ManagerApp, t: TrackItem) {
    val m = app.model
    val v = m.view ?: return
    var naming by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(v.playlists.filter { !it.folder }.size) { n ->
            val p = v.playlists.filter { !it.folder }[n]
            val inIt = t.id in v.entries[p.id].orEmpty()
            FilterChip(selected = inIt, onClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                if (inIt) m.submit(Edit.RemoveFromPlaylist(p.id, listOf(t.id)), "Tirar \"${t.title}\" de \"${p.name}\"")
                else m.submit(Edit.AddToPlaylist(p.id, listOf(t.id)), "Adicionar \"${t.title}\" a \"${p.name}\"")
            }, label = { Text((if (inIt) "✓ " else "") + p.name, maxLines = 1) })
        }
        item { FilterChip(selected = false, onClick = { naming = true }, label = { Text("＋") }) }
    }
    if (naming) NameDialog("Nova playlist", confirm = "Criar", onName = { name ->
        val id = m.nextPlaylistId()
        if (m.submit(Edit.CreatePlaylist(id, 0, name, false), "Criar playlist \"$name\"")) m.submit(Edit.AddToPlaylist(id, listOf(t.id)), "Adicionar \"${t.title}\" a \"$name\"")
        naming = false
    }, onClose = { naming = false })
}

@Composable
private fun StatsRow(t: TrackItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Stat("BPM", if (t.bpm > 0) "%.2f".format(t.bpm) else "—")
        Stat("Tom", keyLabel(t.key) + if (!KeyStyle.musical && t.key.isNotEmpty()) " · ${t.key}" else "")
        Stat("Tempo", formatDuration(t.durationSeconds))
    }
}

@Composable
private fun ListenRow(app: ManagerApp, t: TrackItem) {
    val m = app.model
    val has = m.hasAnalysis(t)
    run {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(48.dp).clip(RbShape).background(if (app.preview.playingId == t.id) Palette.surfaceHigh else Palette.playBg).clickable { app.preview.toggle(t) }, contentAlignment = Alignment.Center) {
                Text(if (app.preview.playingId == t.id) "■ PARAR" else "▶ OUVIR", color = if (app.preview.playingId == t.id) Palette.blue else Palette.playInk, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
            // Só aparece quando falta a análise; reanalisar fica em INFO.
            if (!has) OutlinedButton(onClick = { app.analyze(t, force = false) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("ANALISAR") }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono)
        Text(label.uppercase(), color = Palette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun CueSection(app: ManagerApp, t: TrackItem) {
    val m = app.model
    val files = remember(t.id, t.analyzePath, m.pending.size) { m.trackFiles(t) }
    var pos by remember(t.id) { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val problem = files.error
        if (problem != null) {
            Text(problem, color = Palette.muted, fontSize = 13.sp)
            return
        }
        val duration = files.durationMs.coerceAtLeast(1)
        Wave(files, duration, pos) { pos = it }
        // Posição, pulo de batida e ouvir daqui numa linha só.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(formatMs(pos), color = Palette.text, fontSize = 15.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
            val beats = files.beats.map { it.timeMs }
            OutlinedButton(onClick = { pos = beats.lastOrNull { it < pos - 5 } ?: 0 }, modifier = Modifier.height(42.dp)) { Text("◀") }
            OutlinedButton(onClick = { pos = beats.firstOrNull { it > pos + 5 } ?: pos }, modifier = Modifier.height(42.dp)) { Text("▶") }
            Box(Modifier.height(42.dp).clip(RbShape).background(Palette.playBg).clickable { app.preview.toggle(t, fromMs = pos.coerceAtLeast(1)) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text("▶ OUVIR", color = Palette.playInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        // HOT CUE: grade 2×4 como no player; tocar grava o cue na posição marcada.
        SectionTitle("Hot cue")
        for (rowStart in listOf(1, 5)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (hot in rowStart until rowStart + 4) {
                    val existing = files.cues.firstOrNull { it.hot == hot }
                    Row(
                        Modifier.weight(1f).height(48.dp).background(Palette.bg).border(androidx.compose.foundation.BorderStroke(1.dp, if (existing != null) rgb(padRgb(hot)) else Palette.line), RbShape).clickable {
                            val rest = files.cues.filter { it.hot != hot }
                            m.submit(Edit.SetCues(t.id, rest + Cue(hot, pos, comment = "", rgb = padRgb(hot))), "Hot cue ${hotLetter(hot)} em \"${t.title}\"")
                        }.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(hotLetter(hot), color = rgb(padRgb(hot)), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(existing?.let { "${it.timeMs / 60000}:%02d".format(it.timeMs / 1000 % 60) } ?: "", color = Palette.mutedBright, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { m.submit(Edit.SetCues(t.id, files.cues + Cue(0, pos)), "Cue de memória em \"${t.title}\"") },
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) { Text("＋ MEMÓRIA") }
        files.cues.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable { pos = c.timeMs }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(Palette.bg).border(androidx.compose.foundation.BorderStroke(1.dp, if (c.isHot) rgb(c.rgb ?: padRgb(c.hot)) else Palette.orange), RbShape), contentAlignment = Alignment.Center) {
                    Text(hotLetter(c.hot), color = if (c.isHot) rgb(c.rgb ?: padRgb(c.hot)) else Palette.orange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(formatMs(c.timeMs) + if (c.isLoop) "  → ${formatMs(c.loopEndMs!!)}" else "", color = Palette.text, fontSize = 14.sp, fontFamily = Mono, modifier = Modifier.weight(1f).padding(start = 12.dp))
                TextButton(onClick = { m.submit(Edit.SetCues(t.id, files.cues - c), "Apagar cue de \"${t.title}\"") }) { Text("✕", color = Palette.danger, fontSize = 16.sp) }
            }
        }
        GridEditor(m, t, files, pos)
    }
}

@Composable
private fun GridEditor(m: ManagerModel, t: TrackItem, files: ManagerModel.TrackFilesView, pos: Int) {
    var bpmText by remember(t.id, t.bpm) { mutableStateOf(if (t.bpm > 0) "%.2f".format(t.bpm) else "") }
    val bpm = bpmText.replace(',', '.').toDoubleOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Grade")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(bpmText, { bpmText = it }, label = { Text("BPM") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { m.submit(Edit.SetGrid(t.id, bpm!!, pos), "Grade de \"${t.title}\": %.2f BPM começando em ${formatMs(pos)}".format(bpm)) }, enabled = bpm != null && bpm in 40.0..250.0, modifier = Modifier.height(56.dp)) {
                Text("1ª BATIDA AQUI", fontSize = 12.sp)
            }
        }
    }
}

/**
 * Onda como no cartão do player: a prévia colorida do ANLZ (PWV4, grave/médio/agudo misturados em RGB), espelhada no
 * centro. Sem ela, a prévia azul. Por cima, a grade (início de compasso em vermelho), os cues e a posição.
 * Toque ou arraste para mudar a posição.
 */
@Composable
private fun Wave(files: ManagerModel.TrackFilesView, durationMs: Int, pos: Int, onPos: (Int) -> Unit) =
    ColorWaveView(files, durationMs, pos, 96.dp, marks = true, onPos = onPos, drag = true)

@Composable
private fun Fields(app: ManagerApp, t: TrackItem) {
    val m = app.model
    var title by remember(t) { mutableStateOf(t.title) }
    var artist by remember(t) { mutableStateOf(t.artist) }
    var album by remember(t) { mutableStateOf(t.album) }
    var genre by remember(t) { mutableStateOf(t.genre) }
    var comment by remember(t) { mutableStateOf(t.comment) }
    val changed = title != t.title || artist != t.artist || album != t.album || genre != t.genre || comment != t.comment
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(artist, { artist = it }, label = { Text("Artista") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(album, { album = it }, label = { Text("Álbum") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(genre, { genre = it }, label = { Text("Gênero") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(comment, { comment = it }, label = { Text("Comentário") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(
            onClick = {
                val patch = TrackPatch(
                    title = title.takeIf { it != t.title }, artist = artist.takeIf { it != t.artist }, album = album.takeIf { it != t.album },
                    genre = genre.takeIf { it != t.genre }, comment = comment.takeIf { it != t.comment },
                )
                m.submit(Edit.SetTracks(listOf(t.id), patch), "Editar \"${t.title}\"")
            },
            enabled = changed, modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("APLICAR") }
        OutlinedButton(onClick = { app.analyze(t, force = m.hasAnalysis(t)) }, modifier = Modifier.fillMaxWidth().height(44.dp)) { Text(if (m.hasAnalysis(t)) "REANALISAR" else "ANALISAR") }
        Text(t.filePath.substringAfterLast('/'), color = Palette.muted, fontSize = 11.sp, maxLines = 1)
    }
}
