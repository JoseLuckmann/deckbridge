package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll

@Composable
fun NameDialog(title: String, initial: String = "", confirm: String = "OK", onName: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onName(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
        containerColor = Palette.surfaceHigh,
    )
}

@Composable
fun ColorDialog(onPick: (Int) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Cor da faixa") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sem cor", color = Palette.text, fontSize = 18.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(0) }.padding(vertical = 10.dp))
                Palette.trackColors.forEachIndexed { n, c ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(n + 1) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(Modifier.size(22.dp).clip(RbShape).background(c))
                        Text(Palette.trackColorNames[n], color = Palette.text, fontSize = 18.sp, modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
        containerColor = Palette.surfaceHigh,
    )
}

/** Escolhe uma playlist (não pasta) ou cria uma nova com as faixas selecionadas. */
@Composable
fun PlaylistPicker(app: ManagerApp, title: String = "Adicionar à playlist", onPick: (Int) -> Unit, onCreate: (String) -> Unit, onClose: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("Nova playlist", confirm = "Criar", onName = onCreate, onClose = { creating = false })
        return
    }
    val lists = app.model.view?.playlists?.filter { !it.folder }.orEmpty()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                Text("+ Nova playlist…", color = Palette.blue, fontSize = 18.sp, modifier = Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp))
                if (lists.isEmpty()) Text("Ainda não há playlists.", color = Palette.muted)
                LazyColumn(Modifier.padding(top = 4.dp)) {
                    items(lists, key = { it.id }) { p ->
                        Text(p.name, color = Palette.text, fontSize = 18.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
        containerColor = Palette.surfaceHigh,
    )
}


/** Edita vários campos de várias faixas de uma vez. Só os campos preenchidos mudam; os vazios ficam como estão. */
@Composable
fun BatchEditDialog(count: Int, onApply: (os.deckbridge.manager.pdb.TrackPatch) -> Unit, onClose: () -> Unit) {
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    val any = listOf(artist, album, genre, comment).any { it.isNotBlank() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Editar $count faixa(s)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(artist, { artist = it }, label = { Text("Artista") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(album, { album = it }, label = { Text("Álbum") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(genre, { genre = it }, label = { Text("Gênero") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(comment, { comment = it }, label = { Text("Comentário") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = any, onClick = {
                onApply(os.deckbridge.manager.pdb.TrackPatch(artist = artist.trim().ifEmpty { null }, album = album.trim().ifEmpty { null }, genre = genre.trim().ifEmpty { null }, comment = comment.trim().ifEmpty { null }))
            }) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
        containerColor = Palette.surfaceHigh,
    )
}

@Composable
fun SearchHelpDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Buscar e filtrar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "bpm:120-128" to "andamento",
                    "bpm:>124" to "acima de",
                    "tom:8A" to "tom (ou Am)",
                    "nota:4" to "4★ ou mais",
                    "cor:verde" to "cor",
                    "genero:house" to "gênero",
                ).forEach { (k, v) ->
                    Row { Text(k, color = Palette.orange, fontSize = 14.sp, modifier = Modifier.width(120.dp)); Text(v, color = Palette.text, fontSize = 14.sp) }
                }
                Text("Exemplo: aurora bpm:120-128 nota:4", color = Palette.muted, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Entendi") } },
        containerColor = Palette.surfaceHigh,
    )
}

/**
 * Uma mudança de nome de forma que não confunde: como fica (ARTISTA e TÍTULO, cada um na sua linha, inteiros) e, pequeno,
 * como estava. Antes a frase "título … → …; artista "" → …" era cortada e parecia que o artista ficava vazio.
 */
@Composable
private fun NameChange(old: os.deckbridge.manager.pdb.TrackItem, patch: os.deckbridge.manager.pdb.TrackPatch) {
    val artist = patch.artist ?: old.artist
    val title = patch.title ?: old.title
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text("ARTISTA ", color = Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(62.dp).padding(top = 3.dp))
            Text(artist.ifEmpty { "—" }, color = if (patch.artist != null) Palette.keyInk else Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Row {
            Text("TÍTULO ", color = Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(62.dp).padding(top = 3.dp))
            Text(title, color = if (patch.title != null) Palette.keyInk else Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Text("antes: " + listOf(old.artist.ifEmpty { "sem artista" }, old.title).joinToString(" — "), color = Palette.muted, fontSize = 11.sp)
    }
}

/** "Arrumar tudo": ajustes sugeridos por regras (espaços, maiúsculas, nome do arquivo). Tudo marcado por padrão; nada muda sem confirmar. */
@Composable
fun TidyDialog(app: ManagerApp, all: List<os.deckbridge.manager.organize.Suggestion>) {
    val on = remember(all) { androidx.compose.runtime.mutableStateListOf<Boolean>().apply { addAll(all.map { it.defaultOn }) } }
    AlertDialog(
        onDismissRequest = { app.tidy = null },
        title = { Text("Arrumar nomes") },
        text = {
            if (all.isEmpty()) {
                Text("Nada a arrumar ✓", color = Palette.keyInk)
            } else {
                Column {
                    LazyColumn(Modifier.height(440.dp)) {
                        items(all.indices.toList()) { i ->
                            val s = all[i]
                            Row(Modifier.fillMaxWidth().clickable { on[i] = !on[i] }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = on[i], onCheckedChange = { on[i] = it })
                                Column(Modifier.padding(start = 6.dp)) {
                                    val patch = (s.edit as? os.deckbridge.manager.pdb.Edit.SetTracks)?.patch
                                    val old = s.trackIds.singleOrNull()?.let { app.model.view?.track(it) }
                                    if (patch != null && old != null && (patch.title != null || patch.artist != null)) NameChange(old, patch)
                                    else {
                                        Text("${s.rule.title}: ${s.title}", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text(s.detail, color = Palette.muted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (all.isNotEmpty()) TextButton(onClick = { app.applyTidy(all.filterIndexed { i, _ -> on[i] }) }, enabled = on.any { it }) { Text("Aplicar ${on.count { it }}") }
        },
        dismissButton = { TextButton(onClick = { app.tidy = null }) { Text(if (all.isEmpty()) "Fechar" else "Cancelar") } },
        containerColor = Palette.surfaceHigh,
    )
}

/**
 * Repetidas: cada grupo é a mesma música que entrou mais de uma vez (áudio igual, ou mesmo nome e duração). A sugerida
 * para ficar já vem marcada (melhor qualidade, mais usada, nome mais arrumado); tocar em outra troca. JUNTAR leva as
 * playlists, a nota e a cor das outras para ela e tira as outras da biblioteca; o aviso tem Desfazer.
 */
@Composable
fun DuplicatesDialog(app: ManagerApp, groups: List<os.deckbridge.manager.organize.Duplicates.Group>) {
    val keep = remember(groups) { androidx.compose.runtime.mutableStateMapOf<Int, Int>().apply { groups.forEachIndexed { i, g -> put(i, g.keep.id) } } }
    AlertDialog(
        onDismissRequest = { app.duplicates = null },
        title = { Text("Repetidas · ${groups.size}") },
        text = {
            LazyColumn(Modifier.height(420.dp)) {
                items(groups.indices.toList()) { i ->
                    val g = groups[i]
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(if (g.sameAudio) "MESMO ÁUDIO" else "MESMO NOME E DURAÇÃO", color = Palette.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        g.tracks.forEach { t ->
                            val on = keep[i] == t.id
                            Row(
                                Modifier.fillMaxWidth().clip(RbShape).background(if (on) Palette.blueActiveFill else Palette.surfaceHigh).clickable { keep[i] = t.id }.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    val dur = "%d:%02d".format(t.durationSeconds / 60, t.durationSeconds % 60)
                                    Text(listOf(t.artist.ifBlank { "sem artista" }, dur, if (t.bitrate > 0) "${t.bitrate} kbps" else "", "★".repeat(t.rating)).filter { it.isNotEmpty() }.joinToString(" · "), color = Palette.mutedBright, fontSize = 11.sp, maxLines = 1)
                                }
                                Text(if (on) "FICA" else "SAI", color = if (on) Palette.keyInk else Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                            }
                            androidx.compose.foundation.layout.Spacer(Modifier.height(3.dp))
                        }
                        TextButton(onClick = { app.mergeDuplicates(g, g.tracks.first { it.id == keep[i] }) }) { Text("JUNTAR") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { groups.forEachIndexed { i, g -> app.mergeDuplicates(g, g.tracks.first { it.id == keep[i] }) } }) { Text("Juntar todas ${groups.size}") }
        },
        dismissButton = { TextButton(onClick = { app.duplicates = null }) { Text("Fechar") } },
        containerColor = Palette.surfaceHigh,
    )
}

/**
 * As cores automáticas: o DJ escolhe se a cor da faixa (o Color do rekordbox) vem da energia ou do gênero, e qual cor
 * para cada faixa de energia e cada gênero (tocar no quadrado abre a escolha de cor). Valem para as faixas que entram e
 * para "Classificar"; APLICAR pinta agora as que estão sem cor. A cor que o DJ deu nunca é trocada.
 */
@Composable
fun ColorRulesDialog(app: ManagerApp) {
    val r = app.colorRules
    var picking by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    @Composable fun Swatch(color: Int, label: String, onPick: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { picking = onPick }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            val c = Palette.trackColors.getOrNull(color - 1)
            androidx.compose.foundation.layout.Box(Modifier.size(26.dp).clip(RbShape).background(c ?: Palette.surface).border(androidx.compose.foundation.BorderStroke(1.dp, Palette.line), RbShape))
            Text(if (c != null) Palette.trackColorNames[color - 1] else "sem cor", color = Palette.mutedBright, fontSize = 12.sp, modifier = Modifier.width(76.dp).padding(start = 8.dp))
        }
    }
    AlertDialog(
        onDismissRequest = { app.colorsOpen = false },
        title = { Text("Cores automáticas") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    os.deckbridge.manager.organize.ColorRules.Mode.entries.forEach { m ->
                        val on = r.mode == m
                        androidx.compose.foundation.layout.Box(
                            Modifier.weight(1f).height(40.dp).clip(RbShape).background(if (on) Palette.blue else Palette.surface)
                                .border(androidx.compose.foundation.BorderStroke(1.dp, if (on) Palette.blue else Palette.line), RbShape).clickable { app.changeColorRules(r.copy(mode = m)) },
                            contentAlignment = Alignment.Center,
                        ) { Text(m.title, color = if (on) Palette.bg else Palette.blue, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                when (r.mode) {
                    os.deckbridge.manager.organize.ColorRules.Mode.OFF ->
                        Text("A faixa entra sem cor. Escolha energia ou gênero para pintar sozinho.", color = Palette.mutedBright, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    os.deckbridge.manager.organize.ColorRules.Mode.ENERGY -> os.deckbridge.manager.organize.ColorRules.BANDS.forEachIndexed { i, band ->
                        Swatch(r.byEnergy[i], "Energia $band") { c -> app.changeColorRules(app.colorRules.withEnergy(i, c)) }
                    }
                    os.deckbridge.manager.organize.ColorRules.Mode.GENRE -> {
                        // Os gêneros da biblioteca e os que a classificação palpita.
                        val names = (app.model.view?.tracks.orEmpty().map { it.genre.trim() }.filter { it.isNotEmpty() } +
                            os.deckbridge.manager.organize.ColorRules.DEFAULT_GENRES.keys.map { k -> k.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } })
                            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
                        names.forEach { g -> Swatch(r.byGenre[g.lowercase()] ?: 0, g) { c -> app.changeColorRules(app.colorRules.withGenre(g, c.takeIf { it in 1..8 })) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { app.colorsOpen = false; app.applyColors() }, enabled = r.mode != os.deckbridge.manager.organize.ColorRules.Mode.OFF) { Text("Pintar as sem cor") } },
        dismissButton = { TextButton(onClick = { app.colorsOpen = false }) { Text("Fechar") } },
        containerColor = Palette.surfaceHigh,
    )
    picking?.let { onPick -> ColorDialog(onPick = { onPick(it); picking = null }, onClose = { picking = null }) }
}
