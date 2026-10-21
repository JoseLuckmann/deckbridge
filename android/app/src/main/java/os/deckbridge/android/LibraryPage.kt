package os.deckbridge.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.RbPlaylist
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.ReadableVolume
import os.deckbridge.logic.textSp

class OpenTreeReadOnly : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: android.content.Context, input: Uri?): android.content.Intent {
        return super.createIntent(context, input).addFlags(
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }
}

@Composable
fun LibraryPage(
    catalog: RbCatalog?,
    loading: Boolean,
    notice: String,
    usbLabel: String,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    onAuthorize: (Uri?) -> Unit,
    onSimulate: () -> Unit,
    onReloadPersisted: () -> Unit,
    onLoadDeck: (RbTrack, Int) -> Unit,
    widthPx: Int,
) {
    val context = LocalContext.current
    val size = textSp(widthPx).sp
    val picker = rememberLauncherForActivityResult(OpenTreeReadOnly()) { uri -> onAuthorize(uri) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Biblioteca rekordbox — somente leitura", fontSize = size)
        Text("Volume USB: $usbLabel", fontSize = size)
        Text(notice, fontSize = size)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(removableInitialUri(context)) }) { Text("Autorizar pendrive") }
            Button(onClick = onSimulate) { Text("Pendrive simulado") }
            Button(onClick = onReloadPersisted) { Text("Reler permissão") }
        }
        if (loading) {
            Text("Lendo export.pdb…", fontSize = size)
            return@Column
        }
        if (catalog == null) {
            Text("Nenhuma biblioteca. Conecte o pendrive exportado pelo rekordbox ou use o pendrive simulado.", fontSize = size)
            return@Column
        }
        if (catalog.error != null) {
            Text("Não li a biblioteca: ${catalog.error}", fontSize = size)
            return@Column
        }
        Text(
            if (catalog.simulated) "SIMULAÇÃO — estes arquivos não são um pendrive real." else "Lido do volume autorizado. O app não grava.",
            fontSize = size,
        )
        Text("${catalog.tracks.size} faixas · ${catalog.playlists.size} itens na árvore", fontSize = size)
        Text(catalog.oneLibraryNote, fontSize = size)
        catalog.warnings.forEach { Text(it, fontSize = size) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Playlists", fontSize = size)
                catalog.playlists.forEach { playlist ->
                    PlaylistButton(playlist, selectedId == playlist.id, size, onSelect)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val selected = catalog.playlists.firstOrNull { it.id == selectedId }
                Text(selected?.name ?: "Escolha uma playlist", fontSize = size)
                val ids = selected?.trackIds.orEmpty()
                if (selected?.folder == true) Text("Pasta. Abra uma playlist dentro dela.", fontSize = size)
                if (ids.isEmpty() && selected?.folder == false) Text("Playlist vazia.", fontSize = size)
                ids.forEach { id ->
                    val track = catalog.tracks.firstOrNull { it.id == id }
                    if (track != null) TrackRow(track, size, onLoadDeck)
                }
            }
        }
    }
}

@Composable
private fun PlaylistButton(playlist: RbPlaylist, selected: Boolean, size: androidx.compose.ui.unit.TextUnit, onSelect: (Int) -> Unit) {
    val prefix = if (playlist.folder) "Pasta" else "Lista"
    Button(onClick = { onSelect(playlist.id) }, modifier = Modifier.fillMaxWidth()) {
        Text("$prefix ${playlist.name}${if (selected) " · aberta" else ""}", fontSize = size)
    }
}

@Composable
private fun TrackRow(track: RbTrack, size: androidx.compose.ui.unit.TextUnit, onLoadDeck: (RbTrack, Int) -> Unit) {
    Text("${track.title} — ${track.artist}", fontSize = size)
    Text("${track.bpm} BPM · ${track.keyName} · ${track.durationSeconds}s · ${track.genre}", fontSize = size)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onLoadDeck(track, 0) }) { Text("Deck A") }
        Button(onClick = { onLoadDeck(track, 1) }) { Text("Deck B") }
    }
}
