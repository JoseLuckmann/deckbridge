package os.deckbridge.manager.mobile

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
            if (onBack != null) TextButton(onClick = onBack) { Text("‹ VOLTAR", fontSize = 13.sp) }
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
            if (onBack == null) TextButton(onClick = { app.detailId = null }) { Text("Fechar") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Marking(app, t)
            AnalysisSection(app, t)
            CueSection(app, t)
            Fields(m, t)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text.uppercase(), color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)

@Composable
private fun Marking(app: ManagerApp, t: TrackItem) {
    val m = app.model
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Marcar")
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            for (n in 1..5) {
                Text(
                    if (n <= t.rating) "★" else "☆", color = if (n <= t.rating) Palette.orange else Palette.muted, fontSize = 32.sp,
                    modifier = Modifier.size(48.dp).clickable { m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = if (t.rating == n) 0 else n)), "Nota ${if (t.rating == n) 0 else n} em \"${t.title}\"") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Palette.trackColors.forEachIndexed { n, c ->
                val on = t.colorId == n + 1
                Box(
                    Modifier.size(32.dp).clip(RbShape).background(c).clickable { m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(colorId = if (on) 0 else n + 1)), "Cor em \"${t.title}\"") },
                    contentAlignment = Alignment.Center,
                ) { if (on) Text("✓", color = Palette.bg, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun AnalysisSection(app: ManagerApp, t: TrackItem) {
    val m = app.model
    val has = m.hasAnalysis(t)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Análise")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat("BPM", if (t.bpm > 0) "%.2f".format(t.bpm) else "—")
            Stat("Tom", t.key.ifEmpty { "—" })
            Stat("Tempo", formatDuration(t.durationSeconds))
            Stat("Grade", if (has) "sim" else "não")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.preview.toggle(t) }, modifier = Modifier.weight(1f).height(48.dp)) { Text(if (app.preview.playingId == t.id) "■ Parar" else "▶ Ouvir") }
            OutlinedButton(onClick = { app.analyze(t, force = has) }, modifier = Modifier.weight(1.4f).height(48.dp)) { Text(if (has) "Reanalisar" else "Analisar agora") }
        }
        if (!has) Text("Sem análise a faixa toca, mas sem grade nem cues no player.", color = Palette.muted, fontSize = 12.sp)
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
        SectionTitle("Grade e cues")
        val problem = files.error
        if (problem != null) {
            Text(problem, color = Palette.muted, fontSize = 13.sp)
            return
        }
        val duration = files.durationMs.coerceAtLeast(1)
        Wave(files.wave, files.beats.map { it.timeMs to it.inBar }, files.cues, duration, pos) { pos = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatMs(pos), color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val beats = files.beats.map { it.timeMs }
            OutlinedButton(onClick = { pos = beats.lastOrNull { it < pos - 5 } ?: 0 }, modifier = Modifier.height(44.dp)) { Text("◀ batida") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { pos = beats.firstOrNull { it > pos + 5 } ?: pos }, modifier = Modifier.height(44.dp)) { Text("batida ▶") }
        }
        OutlinedButton(onClick = { app.preview.toggle(t, fromMs = pos.coerceAtLeast(1)) }, modifier = Modifier.fillMaxWidth().height(44.dp)) { Text("▶ Ouvir daqui") }
        // HOT CUE A: grade 2×4 como no player. Cada célula é escura, com a letra na cor do pad; tocar grava o hot cue nesta posição.
        Text("HOT CUE · toque grava na posição marcada", color = Palette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
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
        ) { Text("Cue de memória aqui") }
        files.cues.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable { pos = c.timeMs }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(Palette.bg).border(androidx.compose.foundation.BorderStroke(1.dp, if (c.isHot) rgb(c.rgb ?: padRgb(c.hot)) else Palette.orange), RbShape), contentAlignment = Alignment.Center) {
                    Text(hotLetter(c.hot), color = if (c.isHot) rgb(c.rgb ?: padRgb(c.hot)) else Palette.orange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(formatMs(c.timeMs) + if (c.isLoop) "  → ${formatMs(c.loopEndMs!!)}" else "", color = Palette.text, fontSize = 14.sp, fontFamily = Mono, modifier = Modifier.weight(1f).padding(start = 12.dp))
                TextButton(onClick = { m.submit(Edit.SetCues(t.id, files.cues - c), "Apagar cue de \"${t.title}\"") }) { Text("Apagar", color = Palette.danger) }
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
        Text("Grade regular", color = Palette.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(bpmText, { bpmText = it }, label = { Text("BPM") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { m.submit(Edit.SetGrid(t.id, bpm!!, pos), "Grade de \"${t.title}\": %.2f BPM começando em ${formatMs(pos)}".format(bpm)) }, enabled = bpm != null && bpm in 40.0..250.0, modifier = Modifier.height(56.dp)) {
                Text("Primeira batida aqui", fontSize = 12.sp)
            }
        }
        Text("Move a primeira batida para a posição marcada na onda e recalcula as batidas.", color = Palette.muted, fontSize = 12.sp)
    }
}

/** Onda com batidas (a do início do compasso mais forte), cues e a posição. Toque ou arraste para mudar a posição. */
@Composable
private fun Wave(wave: ByteArray, beats: List<Pair<Int, Int>>, cues: List<Cue>, durationMs: Int, pos: Int, onPos: (Int) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(110.dp).background(Palette.waveBg).border(androidx.compose.foundation.BorderStroke(1.dp, Palette.line))
            .pointerInput(durationMs) { detectTapGestures { o -> onPos((o.x / size.width * durationMs).toInt().coerceIn(0, durationMs)) } }
            .pointerInput(durationMs) { detectDragGestures { change, _ -> onPos((change.position.x / size.width * durationMs).toInt().coerceIn(0, durationMs)) } },
    ) {
        val w = size.width
        val h = size.height
        if (wave.isNotEmpty()) {
            val col = w / wave.size
            for (c in wave.indices) {
                val v = (wave[c].toInt() and 0xFF) / 255f
                val bar = (v * h * 0.9f).coerceAtLeast(1f)
                drawRect(Palette.wave, Offset(c * col, (h - bar) / 2), Size(col.coerceAtLeast(1f), bar))
            }
        } else {
            drawLine(Palette.line, Offset(0f, h / 2), Offset(w, h / 2), 2f)
        }
        for ((ms, inBar) in beats) {
            val x = ms.toFloat() / durationMs * w
            // Início de compasso em vermelho e as demais batidas em branco fraco, como a grade do player.
            drawLine(if (inBar == 1) Palette.danger else Palette.text.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, if (inBar == 1) h else h * 0.18f), if (inBar == 1) 2f else 1f)
        }
        for (c in cues) {
            val x = c.timeMs.toFloat() / durationMs * w
            val color = if (c.isHot) rgb(c.rgb ?: padRgb(c.hot)) else Palette.orange
            drawLine(color, Offset(x, 0f), Offset(x, h), 3f)
            drawRect(color, Offset(x, 0f), Size(14f, 14f))
        }
        val px = pos.toFloat() / durationMs * w
        drawLine(Palette.text, Offset(px, 0f), Offset(px, h), 3f)
    }
}

@Composable
private fun Fields(m: ManagerModel, t: TrackItem) {
    var title by remember(t) { mutableStateOf(t.title) }
    var artist by remember(t) { mutableStateOf(t.artist) }
    var album by remember(t) { mutableStateOf(t.album) }
    var genre by remember(t) { mutableStateOf(t.genre) }
    var comment by remember(t) { mutableStateOf(t.comment) }
    val changed = title != t.title || artist != t.artist || album != t.album || genre != t.genre || comment != t.comment
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Informações")
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
        ) { Text("Aplicar mudanças") }
        Text("Arquivo: ${t.filePath}", color = Palette.muted, fontSize = 11.sp)
    }
}
