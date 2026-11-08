package os.deckbridge.manager.studio

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
fun TriageScreen(app: StudioHost) {
    val ids = app.triage ?: return
    val m = app.model
    // Passou da última: o fim da triagem é um resumo com o próximo passo, não um aviso que some (regra pico-fim).
    if (app.triageAt >= ids.size) return TriageDone(app, ids)
    val at = app.triageAt.coerceIn(0, ids.lastIndex.coerceAtLeast(0))
    val t = ids.getOrNull(at)?.let { m.view?.track(it) }
    if (t == null) {
        app.endTriage()
        return
    }
    fun advance() {
        app.preview.stop()
        app.triageAt = at + 1
    }
    BackHandler { app.endTriage() }
    // Abaixo da barra de status (hora, bateria) e acima da de gestos: o ✕ e o contador ficam à vista e ao alcance.
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        val wide = maxWidth >= WIDE_DP.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Palette.bg).border(androidx.compose.foundation.BorderStroke(1.dp, Palette.line)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { app.endTriage() }) { Text("✕", fontSize = 16.sp) }
                Text("MARCAR", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text("${at + 1}/${ids.size}", color = Palette.orange, fontSize = 14.sp, fontFamily = Mono, modifier = Modifier.padding(start = 10.dp))
                // Perto do fim, quanto falta (o esforço cresce perto da meta: gradiente de objetivo).
                val left = ids.size - at - 1
                Text(if (left in 1..3) "  faltam $left" else "", color = Palette.mutedBright, fontSize = 12.sp, modifier = Modifier.weight(1f))
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
private fun TrackCard(app: StudioHost, t: TrackItem) {
    val playing = app.preview.playingId == t.id
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t.title.ifEmpty { t.fileName }, color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(t.artist.ifEmpty { "—" }, color = Palette.muted, fontSize = 16.sp, maxLines = 1)
        Text(listOf(if (t.bpm > 0) "%.1f".format(t.bpm) else "—", keyLabel(t.key), formatDuration(t.durationSeconds)).joinToString("  ·  "), color = Palette.mutedBright, fontSize = 15.sp, fontFamily = Mono, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        // Tocar na onda ouve daquele ponto: dá para pular direto para o drop.
        PlayableWave(app, t, 64.dp)
        // Botão de tocar na cor do PLAY do player: verde-claro com texto escuro.
        // Na primeira vez, o OUVIR leva o destaque: é por onde se começa a marcar.
        Box(
            Modifier.fillMaxWidth().padding(top = 6.dp).spotlight(app.hint("listen")).height(52.dp).clip(RbShape)
                .background(if (playing) Palette.surfaceHigh else Palette.playBg).clickable { app.seen("listen"); app.preview.toggle(t) },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (playing) "■ PARAR" else "▶ OUVIR", color = if (playing) Palette.blue else Palette.playInk, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        }
    }
}

@Composable
private fun Controls(app: StudioHost, t: TrackItem, at: Int, total: Int, compact: Boolean, advance: () -> Unit) {
    val big = if (compact) 52.dp else 64.dp
    val mid = if (compact) 44.dp else 56.dp
    val m = app.model
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun rate(n: Int) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        m.submit(Edit.SetTracks(listOf(t.id), TrackPatch(rating = n)), "Nota $n em \"${t.title}\"")
        advance()
    }
    KeyHint(app, "Teclas 1–5 dão a nota  ·  → pula  ·  ← volta  ·  Espaço ouve")
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
            Box(Modifier.weight(1f).height(if (compact) 38.dp else 48.dp).clip(RbShape).background(if (t.colorId == n + 1) c else c.copy(alpha = 0.55f)).clickable {
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

/**
 * O fim da triagem: quantas foram marcadas, como ficaram (nota alta, com cor, na playlist alvo) e o que fazer em seguida.
 * O fim de uma tarefa pesa na lembrança dela (Kahneman et al. 1993): termina numa tela de conquista, não num aviso que some.
 */
@Composable
private fun TriageDone(app: StudioHost, ids: List<Int>) {
    val m = app.model
    val v = m.view
    val tracks = ids.mapNotNull { v?.track(it) }
    val rated = tracks.count { it.rating > 0 }
    val top = tracks.count { it.rating >= 4 }
    val colored = tracks.count { it.colorId > 0 }
    val target = m.target?.let { m.playlist(it) }
    val inTarget = target?.let { p -> tracks.count { it.id in v?.entries?.get(p.id).orEmpty() } }
    BackHandler { app.endTriage() }
    Column(
        Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✓", color = Palette.keyInk, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Text("${tracks.size} passaram", color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().rbPanel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DoneLine("com nota", "$rated")
            DoneLine("★ 4 ou mais", "$top")
            DoneLine("com cor", "$colored")
            if (target != null && inTarget != null) DoneLine("em ${target.name}", "$inTarget")
        }
        Button(onClick = { app.endTriage() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("PRONTO") }
    }
}

@Composable
private fun DoneLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.mutedBright, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.orange, fontSize = 17.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    }
}

