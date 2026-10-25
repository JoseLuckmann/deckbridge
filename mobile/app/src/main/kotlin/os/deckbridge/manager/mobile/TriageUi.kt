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
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
            app.notice = os.deckbridge.manager.Banner("Triagem ✓", false)
        } else app.triageAt = at + 1
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
        val wide = maxWidth >= WIDE_DP.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Palette.bg).border(androidx.compose.foundation.BorderStroke(1.dp, Palette.line)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { app.endTriage() }) { Text("✕", fontSize = 16.sp) }
                Text("TRIAGEM", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text("${at + 1}/${ids.size}", color = Palette.orange, fontSize = 14.sp, fontFamily = Mono, modifier = Modifier.padding(start = 10.dp).weight(1f))
                if (m.dirty) TextButton(onClick = { m.undo() }) { Text("↶", fontSize = 20.sp) }
            }
            LinearProgressIndicator(progress = { (at + 1f) / ids.size }, modifier = Modifier.fillMaxWidth(), color = Palette.blue, trackColor = Palette.line)
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(t.artist.ifEmpty { "—" }, color = Palette.muted, fontSize = 16.sp, maxLines = 1)
        Text(listOf(if (t.bpm > 0) "%.1f".format(t.bpm) else "—", t.key.ifEmpty { "—" }, formatDuration(t.durationSeconds)).joinToString("  ·  "), color = Palette.mutedBright, fontSize = 15.sp, fontFamily = Mono, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        // Tocar na onda ouve daquele ponto: dá para pular direto para o drop.
        PlayableWave(app, t, 64.dp)
        // Botão de tocar na cor do PLAY do player: verde-claro com texto escuro.
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(52.dp).clip(RbShape).background(if (playing) Palette.surfaceHigh else Palette.playBg).clickable { app.preview.toggle(t) }, contentAlignment = Alignment.Center) {
            Text(if (playing) "■ PARAR" else "▶ OUVIR", color = if (playing) Palette.blue else Palette.playInk, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        }
    }
}

@Composable
private fun Controls(app: ManagerApp, t: TrackItem, at: Int, total: Int, compact: Boolean, advance: () -> Unit) {
    val big = if (compact) 52.dp else 64.dp
    val mid = if (compact) 44.dp else 56.dp
    val m = app.model
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun rate(n: Int) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = n)), "Nota $n em \"${t.title}\"")
        advance()
    }
    // Cinco estrelas grandes: tocar na terceira dá 3 e já passa para a próxima.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in 1..5) {
            Box(Modifier.weight(1f).height(big).clip(RbShape).background(Palette.surfaceHigh).border(androidx.compose.foundation.BorderStroke(1.dp, Palette.line), RbShape).clickable { rate(n) }, contentAlignment = Alignment.Center) {
                Text(if (n <= t.rating) "★" else "☆", color = if (n <= t.rating) Palette.orange else Palette.mutedBright, fontSize = 30.sp)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Palette.trackColors.forEachIndexed { n, c ->
            Box(Modifier.weight(1f).height(if (compact) 38.dp else 48.dp).clip(RbShape).background(c).clickable {
                m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(colorId = if (t.colorId == n + 1) 0 else n + 1)), "Cor em \"${t.title}\"")
            }, contentAlignment = Alignment.Center) { if (t.colorId == n + 1) Text("✓", color = Palette.bg, fontWeight = FontWeight.Bold) }
        }
    }
    val target = m.target?.let { m.playlist(it) }
    if (target != null) {
        val inIt = t.id in m.view?.entries?.get(target.id).orEmpty()
        Button(onClick = { m.toggleInTarget(t.id) }, modifier = Modifier.fillMaxWidth().height(mid)) { Text(if (inIt) "✓ ${target.name}" else "＋ ${target.name}") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { app.preview.stop(); app.triageAt = (at - 1).coerceAtLeast(0) }, enabled = at > 0, modifier = Modifier.weight(1f).height(mid)) { Text("‹") }
        OutlinedButton(onClick = { advance() }, modifier = Modifier.weight(1f).height(mid)) { Text(if (at >= total - 1) "FIM" else "PULAR ›") }
    }
}
