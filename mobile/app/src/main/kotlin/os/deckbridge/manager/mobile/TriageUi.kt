package os.deckbridge.manager.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.pdb.TrackPatch

/**
 * Triagem: uma faixa por vez, botões grandes. Nota e cor avançam sozinhas para a próxima; "Pular" e "Voltar" andam sem
 * marcar. Com playlist alvo, um botão grande põe a faixa nela. Ouvir (▶) usa o player do aparelho.
 */
@Composable
fun TriageScreen(app: ManagerApp) {
    val ids = app.triage ?: return
    val m = app.model
    val at = app.triageAt.coerceIn(0, ids.lastIndex.coerceAtLeast(0))
    val t = ids.getOrNull(at)?.let { m.view?.track(it) }
    if (t == null) {
        app.endTriage()
        return
    }
    fun advance() {
        app.preview.stop()
        if (at >= ids.lastIndex) {
            app.endTriage()
            app.notice = os.deckbridge.manager.Banner("Triagem concluída: ${ids.size} faixa(s) vistas.", false)
        } else app.triageAt = at + 1
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
        val wide = maxWidth >= WIDE_DP.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Palette.surface).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { app.endTriage() }) { Text("‹ Sair", fontSize = 16.sp) }
                Text("Triagem · ${at + 1} de ${ids.size}", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (m.dirty) TextButton(onClick = { app.save() }) { Text("Salvar") }
            }
            LinearProgressIndicator(progress = { (at + 1f) / ids.size }, modifier = Modifier.fillMaxWidth())
            if (wide) {
                Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { TrackCard(app, t) }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Controls(app, t, at, ids.size, true, ::advance) }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TrackCard(app, t)
                    Controls(app, t, at, ids.size, false, ::advance)
                }
            }
        }
    }
}

@Composable
private fun TrackCard(app: ManagerApp, t: TrackItem) {
    val playing = app.preview.playingId == t.id
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(t.artist.ifEmpty { "sem artista" } + if (t.album.isNotEmpty()) " · ${t.album}" else "", color = Palette.muted, fontSize = 16.sp, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 8.dp)) {
            Stat("BPM", if (t.bpm > 0) "%.1f".format(t.bpm) else "—")
            Stat("Tom", t.key.ifEmpty { "—" })
            Stat("Tempo", formatDuration(t.durationSeconds))
            Stat("Gênero", t.genre.ifEmpty { "—" })
        }
        Button(onClick = { app.preview.toggle(t) }, modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 6.dp)) { Text(if (playing) "■ Parar" else "▶ Ouvir") }
    }
}

@Composable
private fun Controls(app: ManagerApp, t: TrackItem, at: Int, total: Int, compact: Boolean, advance: () -> Unit) {
    val big = if (compact) 52.dp else 64.dp
    val mid = if (compact) 44.dp else 56.dp
    val m = app.model
    fun rate(n: Int) {
        m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = n)), "Nota $n em \"${t.title}\"")
        advance()
    }
    Text("NOTA (avança sozinho)", color = Palette.accent2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in 1..5) {
            Box(Modifier.weight(1f).height(big).clip(RoundedCornerShape(12.dp)).background(if (t.rating == n) Palette.warn else Palette.surfaceHigh).clickable { rate(n) }, contentAlignment = Alignment.Center) {
                Text("$n★", color = if (t.rating == n) Palette.bg else Palette.warn, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    Text("COR", color = Palette.accent2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Palette.trackColors.forEachIndexed { n, c ->
            Box(Modifier.weight(1f).height(if (compact) 38.dp else 48.dp).clip(CircleShape).background(c).clickable {
                m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(colorId = if (t.colorId == n + 1) 0 else n + 1)), "Cor em \"${t.title}\"")
            }, contentAlignment = Alignment.Center) { if (t.colorId == n + 1) Text("✓", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold) }
        }
    }
    val target = m.target?.let { m.playlist(it) }
    if (target != null) {
        val inIt = t.id in m.view?.entries?.get(target.id).orEmpty()
        Button(onClick = { m.toggleInTarget(t.id) }, modifier = Modifier.fillMaxWidth().height(mid)) { Text(if (inIt) "✓ Em \"${target.name}\" (tocar para tirar)" else "＋ Adicionar a \"${target.name}\"") }
    } else {
        OutlinedButton(onClick = { app.notice = os.deckbridge.manager.Banner("Defina a playlist alvo na Biblioteca para adicionar com um toque.", false) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Sem playlist alvo") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { app.preview.stop(); app.triageAt = (at - 1).coerceAtLeast(0) }, enabled = at > 0, modifier = Modifier.weight(1f).height(mid)) { Text("‹ Voltar") }
        OutlinedButton(onClick = { advance() }, modifier = Modifier.weight(1f).height(mid)) { Text(if (at >= total - 1) "Concluir" else "Pular ›") }
    }
    if (!compact) TextButton(onClick = { m.undo() }, enabled = m.dirty) { Text("Desfazer última mudança") }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, color = Palette.muted, fontSize = 12.sp)
    }
}
