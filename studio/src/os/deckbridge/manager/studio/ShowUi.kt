package os.deckbridge.manager.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.manager.pdb.TrackItem
import os.deckbridge.manager.smart.ShowCheck

/**
 * "Pronto pro show?": a lista curta, só com o que dá problema na cabine. O que falta vem primeiro, cada um com o que
 * fazer; o que está certo fica embaixo com ✓, para conferir de relance (checklist do tipo "faz e confere": Degani &
 * Wiener 1997; Haynes et al. 2009). Ver `docs/ux-show.md`.
 */
@Composable
fun ShowDialog(app: StudioHost, onRehearse: () -> Unit, onClose: () -> Unit) {
    val desk = app.sets
    val v = app.model.view ?: return
    // A conferência lê as grades do disco: fora da tela, e de novo quando a biblioteca muda (depois de arrumar).
    val items by produceState<List<ShowCheck.Item>?>(null, v, desk.branches) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { desk.showItems() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pronto pro show?") },
        text = {
            val list = items
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list == null) {
                    Text("Conferindo…", color = Palette.mutedBright, fontSize = 13.sp)
                    return@Column
                }
                val bad = list.filter { !it.ok }
                Text(
                    if (bad.isEmpty()) "TUDO PRONTO ✓" else "${bad.size} COISA(S) PARA OLHAR",
                    color = if (bad.isEmpty()) Palette.keyInk else Palette.orange, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                )
                // O que falta primeiro, com a ação; depois o que está certo.
                bad.forEach { item -> ShowRow(app, item, onRehearse = { onClose(); onRehearse() }, onClose = onClose) }
                list.filter { it.ok }.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FitMark(true)
                        Text(item.text, color = Palette.mutedBright, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("FECHAR") } },
        containerColor = Palette.surfaceHigh,
    )
}

@Composable
private fun ShowRow(app: StudioHost, item: ShowCheck.Item, onRehearse: () -> Unit, onClose: () -> Unit) {
    val v = app.model.view ?: return
    Column(Modifier.fillMaxWidth().rbPanel().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FitMark(false)
            Text(item.text, color = Palette.text, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp).weight(1f))
            when (item.fix) {
                ShowCheck.Fix.ANALYZE, ShowCheck.Fix.MARK_CUES ->
                    OutlinedButton(onClick = { app.sets.prepareForShow(item.trackIds); onClose() }, modifier = Modifier.height(36.dp)) { Text("ARRUMAR", fontSize = 12.sp) }
                ShowCheck.Fix.REHEARSE -> OutlinedButton(onClick = onRehearse, modifier = Modifier.height(36.dp)) { Text("ENSAIAR", fontSize = 12.sp) }
                ShowCheck.Fix.OPEN_ISSUES -> OutlinedButton(onClick = onClose, modifier = Modifier.height(36.dp)) { Text("VER ⚠", fontSize = 12.sp) }
                null -> {}
            }
        }
        // Quais faixas (até três nomes): o DJ reconhece em vez de procurar.
        if (item.trackIds.isNotEmpty()) Text(
            item.trackIds.take(3).mapNotNull { v.track(it)?.title }.joinToString(", ") + if (item.trackIds.size > 3) " e mais ${item.trackIds.size - 3}" else "",
            color = Palette.mutedBright, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 22.dp, top = 2.dp),
        )
    }
}

/** No fim do AO VIVO: guardar a ordem que tocou de verdade, num toque (ou não). */
@Composable
fun SavePlayedDialog(app: StudioHost) {
    val order = app.sets.playedToSave ?: return
    ConfirmDialog(
        "Guardar o que tocou?", "${order.size} faixas, na ordem em que tocaram. Vira uma playlist \"Tocado\" com a data, para lembrar o que funcionou e não repetir no mesmo lugar.",
        "GUARDAR", onConfirm = { app.sets.savePlayed() }, onClose = { app.sets.playedToSave = null },
    )
}

/**
 * Pedido da pista (no AO VIVO): busca a música pedida e mostra a ponte da que está tocando até ela, com até três faixas
 * que sobem ou descem tom e andamento aos poucos. PÔR NO SET põe o caminho logo depois da que toca.
 */
@Composable
fun RequestDialog(app: StudioHost, onClose: () -> Unit) {
    val desk = app.sets
    val v = app.model.view ?: return
    val now = desk.liveTrackItem()
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<TrackItem?>(null) }
    val found = remember(query, v) {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) emptyList() else v.tracks.filter { t -> words.all { "${t.title} ${t.artist}".lowercase().contains(it) } }.take(8)
    }
    val route by produceState<Pair<TrackItem, List<TrackItem>?>?>(null, target, now) {
        val t = target
        value = if (t == null || now == null) null else t to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { desk.requestRoute(now, t) }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pedido") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (now == null) {
                    Text("Toque antes na que está tocando.", color = Palette.mutedBright, fontSize = 13.sp)
                    return@Column
                }
                val t = target
                if (t == null) {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Música pedida") }, singleLine = true, modifier = Modifier.fillMaxWidth().typingField())
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        found.forEach { f ->
                            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.title.ifEmpty { f.fileName }, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${keyLabel(f.key)} · ${bpmLabel(f.bpm)}", color = Palette.mutedBright, fontSize = 12.sp, fontFamily = Mono)
                                }
                                OutlinedButton(onClick = { target = f }, modifier = Modifier.height(36.dp)) { Text("CAMINHO", fontSize = 12.sp) }
                            }
                        }
                    }
                } else RouteView(app, now, t, route?.takeIf { it.first == t }?.second, computing = route?.first != t)
            }
        },
        confirmButton = {
            val r = route?.takeIf { it.first == target }?.second
            if (target != null && r != null) TextButton(onClick = { if (desk.insertRoute(r, target!!)) onClose() }) { Text("PÔR NO SET") }
            else TextButton(onClick = onClose) { Text("FECHAR") }
        },
        dismissButton = { if (target != null) TextButton(onClick = { target = null }) { Text("OUTRA") } },
        containerColor = Palette.surfaceHigh,
    )
}

/** O caminho de [now] até [target]: cada faixa com tom e BPM, ▶ para ouvir. */
@Composable
private fun RouteView(app: StudioHost, now: TrackItem, target: TrackItem, route: List<TrackItem>?, computing: Boolean) {
    when {
        computing -> Text("Procurando a ponte…", color = Palette.mutedBright, fontSize = 13.sp)
        route == null -> Text(
            "Nenhuma ponte de até ${os.deckbridge.manager.smart.Request.MAX_HOPS} faixas combina. Dá para entrar com um corte seco ou com ECHO.",
            color = Palette.orange, fontSize = 13.sp,
        )
        route.isEmpty() -> Text("Combina direto com a que está tocando: pode entrar na próxima.", color = Palette.keyInk, fontSize = 13.sp)
        else -> Text("${route.size} faixa(s) até lá, tom e andamento mudando aos poucos:", color = Palette.mutedBright, fontSize = 13.sp)
    }
    val steps = listOf(now) + route.orEmpty() + target
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { n, t ->
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (n) { 0 -> "AGORA"; steps.lastIndex -> "PEDIDO"; else -> "$n" },
                    color = if (n == steps.lastIndex) Palette.orange else Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(56.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(shortTitle(t.title.ifEmpty { t.fileName }), color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${keyLabel(t.key)} · ${bpmLabel(t.bpm)}", color = Palette.mutedBright, fontSize = 12.sp, fontFamily = Mono)
                }
                if (n > 0) TextButton(onClick = { app.preview.toggle(t) }) { Text(if (app.preview.playingId == t.id) "■" else "▶", fontSize = 14.sp) }
            }
        }
    }
}
