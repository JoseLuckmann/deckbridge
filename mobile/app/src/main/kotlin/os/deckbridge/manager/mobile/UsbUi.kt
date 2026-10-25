package os.deckbridge.manager.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Confirm { EXPORT, IMPORT }

/** Exportar: aparelho → pendrive. Uma linha por coisa, um botão grande; explicações só nas confirmações. */
@Composable
fun UsbTab(app: ManagerApp, actions: Actions) {
    val m = app.model
    val v = m.view
    val usb = app.usb
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val noAudio = v?.tracks?.count { t -> !java.io.File(app.root, t.filePath.replace('\\', '/').trimStart('/')).isFile } ?: 0
    val unanalyzed = v?.tracks?.count { !m.hasAnalysis(it) } ?: 0
    val emptyLists = v?.playlists?.count { !it.folder && v.entries[it.id].orEmpty().isEmpty() } ?: 0
    val blocked = noAudio > 0 || (v?.tracks?.isEmpty() ?: true)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Line("APARELHO", "${v?.tracks?.size ?: 0} faixas · ${v?.playlists?.count { !it.folder } ?: 0} playlists", if (m.dirty) Palette.orange else Palette.text)
        if (noAudio > 0) Issue("$noAudio sem áudio", blocking = true, null, null)
        if (unanalyzed > 0) Issue("$unanalyzed sem análise", blocking = false, "ANALISAR") { app.analyzeAll() }
        if (emptyLists > 0) Issue("$emptyLists playlists vazias", blocking = false, "APAGAR") { app.did("${m.removeEmptyPlaylists()} apagada(s)") }
        Row(Modifier.fillMaxWidth().rbPanel().padding(start = 12.dp, end = 6.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PENDRIVE", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(usb?.label ?: "—", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                when {
                    usb?.problem != null -> Text("ilegível", color = Palette.danger, fontSize = 12.sp)
                    usb?.existing != null -> Text("${usb.existing.tracks} faixas", color = Palette.orange, fontSize = 12.sp, fontFamily = Mono)
                    usb != null -> Text("vazio", color = Palette.muted, fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = actions.pickUsb, modifier = Modifier.height(40.dp)) { Text(if (usb == null) "ESCOLHER" else "TROCAR", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { if (usb?.existing != null) confirm = Confirm.EXPORT else app.export() },
            enabled = usb != null && usb.problem == null && !blocked,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (m.dirty) "SALVAR E EXPORTAR" else "EXPORTAR", fontSize = 15.sp) }
        app.lastTransfer?.let { r ->
            Text("✓ ${r.tracks} faixas · ${r.copied} copiados", color = Palette.keyInk, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { confirm = Confirm.IMPORT }, enabled = usb?.existing != null && !m.dirty) { Text("TRAZER DO PENDRIVE", fontSize = 12.sp) }
    }
    when (confirm) {
        Confirm.EXPORT -> ConfirmDialog(
            "Substituir o pendrive?",
            "${usb?.existing?.tracks} → ${v?.tracks?.size} faixas. O banco antigo fica guardado. Nenhum áudio é apagado.",
            "EXPORTAR", onConfirm = { confirm = null; app.export() }, onClose = { confirm = null },
        )
        Confirm.IMPORT -> ConfirmDialog(
            "Trazer do pendrive?",
            "A biblioteca do aparelho vira a do pendrive (${usb?.existing?.tracks} faixas). A atual fica guardada.",
            "TRAZER", onConfirm = { confirm = null; app.importFromUsb(true) }, onClose = { confirm = null },
        )
        null -> {}
    }
}

@Composable
private fun Line(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().rbPanel().padding(horizontal = 12.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Text(value, color = valueColor, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Issue(text: String, blocking: Boolean, action: String?, onFix: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (blocking) Palette.danger else Palette.orange, RbShape))
        Text(text, color = if (blocking) Palette.danger else Palette.orange, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
        if (action != null && onFix != null) TextButton(onClick = onFix) { Text(action, fontSize = 12.sp) }
    }
}
