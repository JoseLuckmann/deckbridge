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

/** "Arrumar tudo": ajustes sugeridos por regras (espaços, maiúsculas, nome do arquivo). Tudo marcado por padrão; nada muda sem confirmar. */
@Composable
fun TidyDialog(app: ManagerApp, all: List<os.deckbridge.manager.organize.Suggestion>) {
    val on = remember(all) { androidx.compose.runtime.mutableStateListOf<Boolean>().apply { addAll(all.map { it.defaultOn }) } }
    AlertDialog(
        onDismissRequest = { app.tidy = null },
        title = { Text("Arrumar tudo") },
        text = {
            if (all.isEmpty()) {
                Text("Nada a arrumar ✓", color = Palette.keyInk)
            } else {
                Column {
                    LazyColumn(Modifier.height(320.dp)) {
                        items(all.indices.toList()) { i ->
                            val s = all[i]
                            Row(Modifier.fillMaxWidth().clickable { on[i] = !on[i] }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = on[i], onCheckedChange = { on[i] = it })
                                Column(Modifier.padding(start = 6.dp)) {
                                    Text("${s.rule.title}: ${s.title}", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(s.detail, color = Palette.muted, fontSize = 12.sp, maxLines = 3)
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
