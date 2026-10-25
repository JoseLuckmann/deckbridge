package os.deckbridge.manager.mobile

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false, onConfirm: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(text, color = Palette.muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) Palette.danger else Palette.accent) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
        containerColor = Palette.surfaceHigh,
    )
}

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
fun ChoiceDialog(title: String, choices: List<String>, onPick: (Int) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                choices.forEachIndexed { n, c ->
                    Text(c, color = Palette.text, fontSize = 18.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(n) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
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
                        androidx.compose.foundation.layout.Box(Modifier.size(22.dp).clip(CircleShape).background(c))
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
fun PlaylistPicker(app: ManagerApp, onPick: (Int) -> Unit, onCreate: (String) -> Unit, onClose: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("Nova playlist", confirm = "Criar", onName = onCreate, onClose = { creating = false })
        return
    }
    val lists = app.model.view?.playlists?.filter { !it.folder }.orEmpty()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Adicionar à playlist") },
        text = {
            Column {
                Text("+ Nova playlist…", color = Palette.accent, fontSize = 18.sp, modifier = Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp))
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
