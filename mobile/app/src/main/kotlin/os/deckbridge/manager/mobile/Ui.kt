package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


/**
 * A raiz, no estilo do player: fundo preto, faixa do topo com rótulo azul em caixa alta, e embaixo uma fileira de botões
 * iguais (como SOURCE / BROWSE / PLAYLIST / MENU), o ativo em azul-escuro com contorno azul. Deitado (Galaxy S25 Ultra
 * ≈ 892×412 dp), a fileira vira uma coluna de ícones à esquerda, a lista fica no meio e os detalhes ao lado.
 */
@Composable
fun ManagerRoot(app: ManagerApp, actions: Actions) {
    // As telas de set são compartilhadas com o computador: o "voltar" delas é o botão de voltar do Android.
    ManagerTheme { androidx.compose.runtime.CompositionLocalProvider(LocalBack provides { onBack -> androidx.activity.compose.BackHandler(onBack = onBack) }) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
            val wide = maxWidth >= WIDE_DP.dp
            val open = app.detailId?.let { app.model.view?.track(it) }
            // A pré-escuta é da faixa na tela: fechou a faixa ou passou para outra, para.
            val onScreen = app.triage?.getOrNull(app.triageAt) ?: app.detailId
            androidx.compose.runtime.LaunchedEffect(onScreen) {
                if (app.sets.playlistId == null && app.preview.playingId != null && app.preview.playingId != onScreen) app.preview.stop()
            }
            val detailTab = app.tab == Tab.LIBRARY || app.tab == Tab.PLAYLISTS
            if (wide) {
                Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    NavRail(app)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(app, actions)
                        ImportStrip(app)
                        if (app.tab != Tab.MENU) GuideStrip(app, actions)
                        Row(Modifier.weight(1f)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { TabContent(app, actions) }
                            if (app.tab == Tab.LIBRARY || (app.tab == Tab.PLAYLISTS && open != null)) {
                                Box(Modifier.width(360.dp).fillMaxHeight().background(Palette.surface).border(BorderStroke(1.dp, Palette.line))) {
                                    if (open != null) DetailPanel(app, open) else EmptyDetail()
                                }
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    if (open != null && detailTab) {
                        DetailPanel(app, open, onBack = { app.detailId = null })
                    } else {
                        TopBar(app, actions)
                        ImportStrip(app)
                        if (app.tab != Tab.MENU) GuideStrip(app, actions)
                        Box(Modifier.weight(1f)) { TabContent(app, actions) }
                        NavBar(app)
                    }
                }
            }
            if (app.triage != null) TriageScreen(app)
            if (app.sets.playlistId != null) SetScreen(app)
            if (app.sets.playlistId != null && app.sets.mapOpen) SetMapScreen(app)
            if (app.sets.mixing != null) MixScreen(app)
            // Abertos do MENU ou da lista: ficam por cima de qualquer aba (antes só apareciam na aba MÚSICAS).
            if (app.libraryTools) LibraryTools(app, actions) { app.libraryTools = false }
            app.tidy?.let { TidyDialog(app, it) }
            app.duplicates?.let { DuplicatesDialog(app, it) }
            JobOverlay(app)
            NoticeToast(app)
        }
    }
    }
}

@Composable
private fun TabContent(app: ManagerApp, actions: Actions) {
    when (app.tab) {
        Tab.LIBRARY -> LibraryTab(app, actions)
        Tab.PLAYLISTS -> PlaylistsTab(app)
        Tab.USB -> UsbTab(app, actions)
        Tab.MENU -> MenuTab(app, actions)
    }
}

/** Rótulo curto de cada área, em caixa alta, como a fileira de botões do player. */
private fun Tab.short() = when (this) {
    Tab.LIBRARY -> "MÚSICAS"
    Tab.PLAYLISTS -> "PLAYLISTS"
    Tab.USB -> "PENDRIVE"
    Tab.MENU -> "MENU"
}

/** Topo: o nome da área em azul, a contagem em laranja e, à direita, só os botões da área. */
@Composable
private fun TopBar(app: ManagerApp, actions: Actions) {
    val v = app.model.view
    val count = when (app.tab) {
        Tab.LIBRARY -> app.model.rows().size
        Tab.PLAYLISTS -> v?.playlists?.count { !it.folder } ?: 0
        else -> null
    }
    Row(Modifier.fillMaxWidth().height(52.dp).background(Palette.bg).border(BorderStroke(1.dp, Palette.line)).padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(app.tab.short(), color = Palette.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        if (count != null) Text("$count", color = Palette.orange, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.padding(start = 8.dp))
        // O estado da gravação, discreto (visibilidade do estado do sistema): grava sozinho, mas a pessoa vê que gravou.
        val m = app.model
        // Alterado ainda não gravado (grava sozinho em seguida), gravando agora, ou salvo.
        val state = when {
            m.saving -> "gravando…"
            m.dirty -> "• alterado"
            else -> "✓ salvo"
        }
        if (m.view != null) Text(
            state, color = if (m.saving || m.dirty) Palette.orange else Palette.muted, fontSize = 11.sp,
            modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = state },
        )
        Spacer(Modifier.weight(1f))
        UndoButtons(app)
        if (app.tab == Tab.LIBRARY) {
            TextButton(onClick = { app.searchOpen = !app.searchOpen }) { Text("⌕", fontSize = 22.sp) }
            // As ferramentas da biblioteca moram na própria aba (o MENU fica com o que vale para o app todo).
            TextButton(onClick = { app.libraryTools = true }, modifier = Modifier.semantics { contentDescription = "Ferramentas da biblioteca" }) { Text("⋯", fontSize = 20.sp) }
            OutlinedButton(onClick = actions.pickAudio, modifier = Modifier.height(38.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) { Text("＋", fontSize = 18.sp) }
        }
    }
}

/**
 * A importação rodando em segundo plano: uma faixa fina com quantas já passaram, a música da vez e ✕ para parar. A tela
 * continua livre; as músicas aparecem na lista conforme ficam prontas.
 */
@Composable
private fun ImportStrip(app: ManagerApp) {
    val p = app.imports.progress ?: return
    Column(Modifier.fillMaxWidth().background(Palette.surface)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("IMPORTANDO", color = Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Text("  ${p.done}/${p.total}", color = Palette.orange, fontSize = 12.sp, fontFamily = Mono)
            Text(
                "  " + shortTitle(p.current.substringBeforeLast('.')), color = Palette.mutedBright, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { app.imports.cancel() }, modifier = Modifier.semantics { contentDescription = "Parar a importação" }) { Text("✕", fontSize = 14.sp) }
        }
        LinearProgressIndicator(
            progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Palette.blue, trackColor = Palette.line,
        )
    }
}

/** Desfazer e refazer, no topo: só aparecem quando há o que desfazer (gravar é sozinho). */
@Composable
private fun UndoButtons(app: ManagerApp) {
    val m = app.model
    if (m.dirty) TextButton(onClick = { m.undo() }, enabled = !m.saving, modifier = Modifier.semantics { contentDescription = "Desfazer" }) { Text("↶", fontSize = 20.sp) }
    if (m.redoStack.isNotEmpty()) TextButton(onClick = { m.redo() }, modifier = Modifier.semantics { contentDescription = "Refazer" }) { Text("↷", fontSize = 20.sp) }
}

/** O botão da área do próximo passo do guia leva o destaque (contorno duplo). */
private fun spotlit(app: ManagerApp, t: Tab): Boolean {
    if (app.guideHidden) return false
    val step = app.step() ?: return false
    // Importar se faz na própria lista de músicas (já aberta no começo): sem destaque na fileira.
    if (step == GuideStep.IMPORT) return false
    return step.tab == t && app.tab != t
}

/**
 * Fileira de botões iguais, como SOURCE / BROWSE / TAG LIST / PLAYLIST / SEARCH / MENU, na ordem do trabalho: músicas
 * (onde também se marca), playlists, pendrive. O ativo: azul-escuro, contorno azul.
 */
@Composable
private fun NavBar(app: ManagerApp) {
    // Uma barra só, sem caixas: o ativo em azul com o traço em cima, os outros apagados (como as abas do player).
    Row(Modifier.fillMaxWidth().background(Palette.surface).border(BorderStroke(0.5.dp, Palette.line)).height(56.dp)) {
        // MARCAR não tem aba: é o primeiro botão da fileira de filtros de MÚSICAS.
        listOf(Tab.LIBRARY, Tab.PLAYLISTS, Tab.USB, Tab.MENU).forEach { t ->
            val on = app.tab == t
            Box(Modifier.weight(1f).fillMaxHeight().clickable { app.tab = t }, contentAlignment = Alignment.Center) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth(0.6f).height(2.dp).background(if (on) Palette.blue else Palette.surface))
                Row(Modifier.spotlight(spotlit(app, t)).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.short(), color = if (on) Palette.blue else Palette.mutedBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Coluna de ícones à esquerda, como o menu lateral da lista do player: ícone e três a quatro letras, o ativo em azul com barra. */
@Composable
private fun NavRail(app: ManagerApp) {
    Column(Modifier.width(64.dp).fillMaxHeight().background(Palette.bg).border(BorderStroke(1.dp, Palette.line)).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        data class Item(val tab: Tab, val glyph: String, val label: String)
        listOf(Item(Tab.LIBRARY, "≡", "MÚSICAS"), Item(Tab.PLAYLISTS, "♫", "LISTAS"), Item(Tab.USB, "⎘", "PENDRIVE"), Item(Tab.MENU, "⋯", "MENU")).forEach { it ->
            val on = app.tab == it.tab
            val hot = spotlit(app, it.tab)
            Column(
                Modifier.fillMaxWidth().spotlight(hot).clickable { app.tab = it.tab }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(it.glyph, color = if (on) Palette.blue else Palette.mutedBright, fontSize = 20.sp)
                Text(it.label, color = if (on) Palette.blue else Palette.mutedBright, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, maxLines = 1)
                Box(Modifier.padding(top = 3.dp).width(26.dp).height(2.dp).background(if (on) Palette.blue else Palette.bg))
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
    Box(Modifier.fillMaxSize().background(Palette.bg.copy(alpha = 0.78f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).rbPanel().background(Palette.surfaceHigh).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(j.title.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.blue, letterSpacing = 0.6.sp)
            if (j.text.isNotEmpty()) Text(j.text, fontSize = 13.sp, color = Palette.mutedBright, maxLines = 3, overflow = TextOverflow.Ellipsis)
            // Com "n/N" no texto, a barra enche e diz a porcentagem (Myers 1985: quem vê quanto falta espera melhor).
            val f = jobFraction(j.text)
            if (f != null) {
                Text("${(f * 100).toInt()}%", fontSize = 20.sp, color = Palette.orange, fontFamily = Mono)
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = Palette.blue, trackColor = Palette.line)
            } else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.blue, trackColor = Palette.line)
            OutlinedButton(onClick = { j.cancelled = true }) { Text("CANCELAR", fontSize = 12.sp) }
        }
    }
}

/** Aviso na faixa âmbar do player (SIMULAÇÃO…); erro em vermelho com contorno. */
@Composable
private fun NoticeToast(app: ManagerApp) {
    val n = app.notice ?: return
    // Acima da barra de gestos do Android (sem ela, o aviso ficava atrás dos botões do sistema).
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(8.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.fillMaxWidth().clip(RbShape).background(if (n.error) Palette.dangerBg else Palette.noteBg).border(BorderStroke(1.dp, if (n.error) Palette.danger else Palette.noteInk.copy(alpha = 0.4f)), RbShape)
                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Erro: borda vermelha e texto claro (vermelho sobre o fundo vermelho-escuro fica abaixo de 4,5:1, WCAG 1.4.3).
            Text(n.text, Modifier.weight(1f), color = if (n.error) Palette.text else Palette.noteInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (app.noticeUndo && app.model.dirty) TextButton(onClick = { app.undoFromNotice() }) { Text("DESFAZER", color = Palette.noteInk, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            TextButton(onClick = { app.notice = null }) { Text("OK", color = if (n.error) Palette.text else Palette.noteInk, fontSize = 12.sp) }
        }
    }
}

/** O que a tela pede à atividade (seletores de arquivo do sistema). */
class Actions(val pickAudio: () -> Unit, val pickUsb: () -> Unit, val pickFolder: () -> Unit = {}, val toDj: () -> Unit = {})
