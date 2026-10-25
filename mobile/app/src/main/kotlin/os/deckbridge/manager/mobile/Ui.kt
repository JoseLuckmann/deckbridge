package os.deckbridge.manager.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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

/** Largura a partir da qual a tela vira duas colunas (celular deitado, tablet): lista à esquerda e detalhes à direita. */
const val WIDE_DP = 600

/**
 * A raiz. No celular em pé: barra do topo, conteúdo e navegação embaixo; a faixa aberta ocupa a tela toda.
 * Deitado (Galaxy S25 Ultra ≈ 892×412 dp): trilho de navegação à esquerda, lista no meio e detalhes ao lado.
 */
@Composable
fun ManagerRoot(app: ManagerApp, actions: Actions) {
    ManagerTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
            val wide = maxWidth >= WIDE_DP.dp
            val open = app.detailId?.let { app.model.view?.track(it) }
            if (wide) {
                Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    NavRail(app)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(app, actions, compact = true)
                        Row(Modifier.weight(1f)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { TabContent(app, actions) }
                            if (app.tab == Tab.LIBRARY || (app.tab == Tab.PLAYLISTS && open != null)) {
                                Box(Modifier.width(360.dp).fillMaxHeight().background(Palette.surface)) {
                                    if (open != null) DetailPanel(app, open) else EmptyDetail()
                                }
                            }
                        }
                        SaveBar(app)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    if (open != null && (app.tab == Tab.LIBRARY || app.tab == Tab.PLAYLISTS)) {
                        DetailPanel(app, open, onBack = { app.detailId = null })
                    } else {
                        TopBar(app, actions, compact = false)
                        Box(Modifier.weight(1f)) { TabContent(app, actions) }
                        SaveBar(app)
                        NavBar(app)
                    }
                }
            }
            JobOverlay(app)
            NoticeToast(app)
        }
    }
}

@Composable
private fun TabContent(app: ManagerApp, actions: Actions) {
    when (app.tab) {
        Tab.LIBRARY -> LibraryTab(app, actions)
        Tab.PLAYLISTS -> PlaylistsTab(app)
        Tab.USB -> UsbTab(app, actions)
    }
}

@Composable
private fun TopBar(app: ManagerApp, actions: Actions, compact: Boolean) {
    val count = app.model.view?.tracks?.size ?: 0
    Row(Modifier.fillMaxWidth().background(Palette.surface).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(app.tab.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            if (app.tab == Tab.LIBRARY) Text("$count faixas · salva no aparelho", fontSize = 12.sp, color = Palette.muted)
        }
        if (app.tab == Tab.LIBRARY) {
            Button(onClick = actions.pickAudio, contentPadding = ButtonDefaults.ButtonWithIconContentPadding, modifier = Modifier.height(44.dp)) { Text("+ Importar") }
        }
    }
}

/** Faixa com as mudanças ainda não gravadas: salvar ou desfazer. Some quando não há nada pendente. */
@Composable
private fun SaveBar(app: ManagerApp) {
    val m = app.model
    if (!m.dirty) return
    Row(Modifier.fillMaxWidth().background(Palette.surfaceHigh).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${m.pending.size} mudança(s) não salva(s)", color = Palette.warn, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(m.pending.last().text, color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { m.undo() }) { Text("Desfazer") }
        TextButton(onClick = { m.discard() }) { Text("Descartar", color = Palette.danger) }
        Button(onClick = { app.save() }, modifier = Modifier.height(44.dp)) { Text("Salvar") }
    }
}

@Composable
private fun NavBar(app: ManagerApp) {
    Row(Modifier.fillMaxWidth().background(Palette.surface)) {
        Tab.values().forEach { t ->
            val on = app.tab == t
            Column(
                Modifier.weight(1f).clickable { app.tab = t }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(48.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (on) Palette.accent else Palette.surface))
                Spacer(Modifier.height(6.dp))
                Text(t.title, color = if (on) Palette.accent else Palette.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun NavRail(app: ManagerApp) {
    Column(Modifier.width(88.dp).fillMaxHeight().background(Palette.surface).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Tab.values().forEach { t ->
            val on = app.tab == t
            Column(
                Modifier.width(80.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Palette.surfaceHigh else Palette.surface).clickable { app.tab = t }.padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.title, color = if (on) Palette.accent else Palette.muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun EmptyDetail() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("Toque numa faixa para ver, marcar e editar.", color = Palette.muted, fontSize = 14.sp)
    }
}

@Composable
private fun JobOverlay(app: ManagerApp) {
    val j = app.job ?: return
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xAA000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).clip(RoundedCornerShape(16.dp)).background(Palette.surfaceHigh).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(j.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            if (j.text.isNotEmpty()) Text(j.text, fontSize = 13.sp, color = Palette.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = { j.cancelled = true }) { Text("Cancelar") }
        }
    }
}

@Composable
private fun NoticeToast(app: ManagerApp) {
    val n = app.notice ?: return
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (n.error) Palette.danger else Palette.accent).padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(n.text, Modifier.weight(1f), color = Palette.bg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            TextButton(onClick = { app.notice = null }) { Text("OK", color = Palette.bg) }
        }
    }
}

/** O que a tela pede à atividade (seletores de arquivo do sistema). */
class Actions(val pickAudio: () -> Unit, val pickUsb: () -> Unit)
