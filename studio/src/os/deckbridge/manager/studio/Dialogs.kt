package os.deckbridge.manager.studio

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
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false, onConfirm: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(text, color = Palette.muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) Palette.danger else Palette.blue) } },
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
