package os.deckbridge.manager.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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

/** Exportar a biblioteca do aparelho para um pendrive (e, se o usuário quiser, trazer a de um pendrive). */
@Composable
fun UsbTab(app: ManagerApp, actions: Actions) {
    val m = app.model
    val usb = app.usb
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var withAudio by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card("1 · Biblioteca do aparelho") {
            val v = m.view
            Text("${v?.tracks?.size ?: 0} faixas · ${v?.playlists?.count { !it.folder } ?: 0} playlists", color = Palette.text, fontSize = 16.sp)
            Text(if (m.dirty) "Há mudanças não salvas. Salve antes de exportar." else "Tudo salvo no aparelho.", color = if (m.dirty) Palette.warn else Palette.muted, fontSize = 13.sp)
        }
        Card("2 · Pendrive") {
            if (usb == null) {
                Text("Conecte o pendrive no celular (cabo OTG ou adaptador USB-C) e escolha a pasta raiz dele.", color = Palette.muted, fontSize = 13.sp)
            } else {
                Text(usb.label, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                when {
                    usb.problem != null -> Text(usb.problem, color = Palette.danger, fontSize = 13.sp)
                    usb.existing != null -> Text("Já tem uma biblioteca: ${usb.existing.tracks} faixas, ${usb.existing.playlists} playlists. Exportar vai substituí-la (o banco antigo fica em backup no aparelho; nenhum áudio é apagado).", color = Palette.warn, fontSize = 13.sp)
                    else -> Text("Pendrive sem biblioteca. Será criada do zero.", color = Palette.muted, fontSize = 13.sp)
                }
            }
            OutlinedButton(onClick = actions.pickUsb, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (usb == null) "Escolher o pendrive" else "Escolher outro") }
        }
        Card("3 · Exportar") {
            Text("Copia as músicas e análises que faltam e grava o banco por último, no formato do rekordbox. Depois confere relendo o pendrive.", color = Palette.muted, fontSize = 13.sp)
            Button(
                onClick = { if (usb?.existing != null) confirm = Confirm.EXPORT else app.export() },
                enabled = usb != null && usb.problem == null && !m.dirty && (m.view?.tracks?.isNotEmpty() == true),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Exportar para o pendrive") }
        }
        Card("Trazer do pendrive") {
            Text("Substitui a biblioteca do aparelho pela do pendrive (a atual vai para backup). Só lê o pendrive.", color = Palette.muted, fontSize = 13.sp)
            OutlinedButton(
                onClick = { confirm = Confirm.IMPORT },
                enabled = usb?.existing != null && !m.dirty,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Trazer a biblioteca do pendrive") }
        }
        app.lastTransfer?.let { r ->
            Card("Último resultado") {
                Text("${r.tracks} faixas · ${r.playlists} playlists · ${r.files} arquivos (${r.copied} copiados, ${r.skipped} já estavam lá)", color = Palette.text, fontSize = 14.sp)
                r.backupDir?.let { Text("Backup do banco anterior: ${it.path}", color = Palette.muted, fontSize = 12.sp) }
                if (r.orphanAudio.isNotEmpty()) Text("${r.orphanAudio.size} áudio(s) no pendrive não pertencem à nova biblioteca; continuam lá, intactos.", color = Palette.muted, fontSize = 12.sp)
            }
        }
    }
    when (confirm) {
        Confirm.EXPORT -> ConfirmDialog(
            "Substituir a biblioteca do pendrive?",
            "O pendrive tem ${usb?.existing?.tracks} faixas. Vai ficar com as ${m.view?.tracks?.size} do aparelho. O banco antigo é guardado no aparelho; nenhum áudio é apagado.",
            "Exportar", onConfirm = { confirm = null; app.export() }, onClose = { confirm = null },
        )
        Confirm.IMPORT -> ConfirmDialog(
            "Trazer a biblioteca do pendrive?",
            "A biblioteca do aparelho será substituída pela do pendrive (${usb?.existing?.tracks} faixas), com as músicas e análises. A atual fica em backup.",
            "Trazer", onConfirm = { confirm = null; app.importFromUsb(withAudio) }, onClose = { confirm = null },
        )
        null -> {}
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.accent2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}
