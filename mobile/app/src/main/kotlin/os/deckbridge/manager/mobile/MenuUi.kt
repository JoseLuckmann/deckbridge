package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import os.deckbridge.manager.smart.QuickFilter

/**
 * MENU, como no player: linhas com o rótulo à esquerda e o valor ou a ação à direita. Aqui moram as ações em lote e as
 * que não precisam estar na frente o tempo todo.
 */
@Composable
fun MenuTab(app: ManagerApp, actions: Actions) {
    val m = app.model
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Só o que vale para o app todo. O que é da biblioteca fica em MÚSICAS → ⋯; o que é do set, no ⋯ do SET.
        Section("App")
        // O outro modo do app: tocar (a biblioteca grava a fila antes e sai da memória).
        Line("Modo DJ ›", "tocar") { actions.toDj() }
        Section("Ajuda")
        Line("Guia para começar", if (app.guideHidden) "desligado" else "ligado") { if (app.guideHidden) app.showGuide() else app.hideGuide() }
        Line("Buscar e filtrar", "") { app.tab = Tab.LIBRARY; app.helpRequested = true }
        Section("Tela")
        Line("Tom", if (KeyStyle.musical) "Am (musical)" else "8A (Camelot)") { app.setMusicalKeys(!KeyStyle.musical) }
        Section("Aparelho")
        Line("Teste de áudio", app.audioCheckResult.substringAfter("· ").ifEmpty { "" }) { app.audioCheck() }
        Section("Segurança")
        Line("Cópias de segurança", "${m.backupList().size}") {}
        if (app.devMode) DevSection(app)
        AboutLogo(app)
    }
}

/** Toques na logo para ligar o modo desenvolvedor (como o "número da versão" do Android). */
const val DEV_TAPS = 13

/**
 * O fim do MENU: a logo (o disco) e a versão. Cada toque gira o disco uma volta; [DEV_TAPS] toques ligam o modo
 * desenvolvedor, e os últimos avisam quantos faltam.
 */
@Composable
private fun AboutLogo(app: ManagerApp) {
    val spin = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    var taps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Logo do DeckBridge",
            modifier = Modifier.size(96.dp).graphicsLayer { rotationZ = spin.value }.clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
            ) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                // Uma volta por toque, somando: tocando rápido o disco acelera, como um prato.
                val target = spin.targetValue + 360f
                scope.launch { spin.animateTo(target, androidx.compose.animation.core.tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
                if (app.devMode) {
                    if (taps == 0) app.tell("O modo desenvolvedor já está ligado")
                    taps++
                    return@clickable
                }
                taps++
                val left = DEV_TAPS - taps
                when {
                    left <= 0 -> { app.changeDevMode(true); app.tell("Modo desenvolvedor ligado"); taps = 0 }
                    left <= 5 -> app.tell(if (left == 1) "Falta 1 toque para o modo desenvolvedor" else "Faltam $left toques para o modo desenvolvedor")
                }
            },
        )
        Text("DeckBridge", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text("versão ${app.version.ifEmpty { "—" }}", color = Palette.mutedBright, fontSize = 12.sp)
    }
}

/** O que só o modo desenvolvedor mostra: a memória do app e a última queda (para mandar a quem corrige). */
@Composable
private fun DevSection(app: ManagerApp) {
    val ctx = app.context
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val crash = androidx.compose.runtime.remember(tick) { CrashLog.last(ctx) }
    val rt = Runtime.getRuntime()
    val mb = 1024 * 1024
    @Suppress("UNUSED_EXPRESSION") tick
    Section("Desenvolvedor")
    Line("Memória", "${(rt.totalMemory() - rt.freeMemory()) / mb} de ${rt.maxMemory() / mb} MB") { tick++ }
    Line("Última queda", crash?.lineSequence()?.firstOrNull()?.removePrefix("Quando: ") ?: "nenhuma") {
        if (crash == null) return@Line
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Queda do DeckBridge", crash))
        app.tell("Registro da queda copiado")
    }
    if (crash != null) Line("Apagar o registro da queda", "") { CrashLog.clear(ctx); tick++ }
    Line("Desligar o modo desenvolvedor", "") { app.changeDevMode(false) }
}

/**
 * As ferramentas da biblioteca (o ⋯ do topo de MÚSICAS): importar pasta, analisar, arrumar nomes, repetidas, grades e
 * rever as marcadas. Importar músicas é o ＋ ao lado.
 */
@Composable
fun LibraryTools(app: ManagerApp, actions: Actions, onClose: () -> Unit) {
    val counts = app.model.filterCounts()
    val unrated = counts[QuickFilter.UNRATED] ?: 0
    val options = listOf<Pair<String, () -> Unit>>(
        "Importar pasta" to { actions.pickFolder() },
        "Analisar todas · ${counts[QuickFilter.UNANALYZED] ?: 0}" to { app.analyzeAll() },
        "Marcar pontos (cues)" to { app.markPoints() },
        "Classificar (gênero, energia, tags)" to { app.classifyAll() },
        "Cores automáticas" to { app.colorsOpen = true },
        "Arrumar nomes" to { app.openTidy() },
        "Procurar repetidas" to { app.findDuplicates() },
        "Refazer grades" to { app.refreshGrids() },
        "Rever as marcadas · ${(app.model.view?.tracks?.size ?: 0) - unrated}" to { app.startTriage(review = true) },
    )
    ChoiceDialog("Biblioteca", options.map { it.first }, onPick = { n -> onClose(); options[n].second() }, onClose = onClose)
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
private fun Line(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().border(BorderStroke(0.5.dp, Palette.line)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.orange, fontSize = 13.sp)
    }
}
