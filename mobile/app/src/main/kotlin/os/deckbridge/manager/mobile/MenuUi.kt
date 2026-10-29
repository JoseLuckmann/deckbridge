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
    }
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
