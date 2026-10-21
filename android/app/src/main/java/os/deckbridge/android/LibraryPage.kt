package os.deckbridge.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.RbPlaylist
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.StagePalette

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
    jvmBanner: String? = null,
    onPage: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(OpenTreeReadOnly()) { uri -> onAuthorize(uri) }
    StageScaffold(
        page = 1,
        onPage = onPage,
        simulationNote = simulationNote(jvmBanner, catalog?.simulated == true || notice.contains("SIMULA")),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextInk("SOURCE", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                TextInk("  $usbLabel", fontSize = 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            }
            TextInk(notice, fontSize = 14.sp, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StageButton("Autorizar pendrive", { picker.launch(removableInitialUri(context)) })
                StageButton("Pendrive simulado", onSimulate)
                StageButton("Reler permissão", onReloadPersisted)
            }
            when {
                loading -> TextInk("Lendo export.pdb…", fontSize = 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                catalog == null -> TextInk(
                    "Nenhuma biblioteca. Conecte o pendrive exportado pelo rekordbox ou use o pendrive simulado.",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
                catalog.error != null -> TextInk(
                    "Não li a biblioteca: ${catalog.error}",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
                else -> LibraryBody(catalog, selectedId, onSelect, onLoadDeck, Modifier.weight(1f).padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun LibraryBody(
    catalog: RbCatalog,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    onLoadDeck: (RbTrack, Int) -> Unit,
    modifier: Modifier,
) {
    val selected = catalog.playlists.firstOrNull { it.id == selectedId }
    Column(modifier) {
        TextInk(
            if (catalog.simulated) "Estes arquivos não são um pendrive real. O app não grava." else "Lido do volume autorizado. O app não grava.",
            fontSize = 13.sp,
            color = Color(StagePalette.inkMuted),
            maxLines = 1,
        )
        TextInk(
            "${catalog.tracks.size} faixas · ${catalog.playlists.size} itens na árvore",
            fontSize = 14.sp,
            weight = FontWeight.SemiBold,
        )
        if (catalog.oneLibraryNote.isNotBlank()) {
            TextInk(catalog.oneLibraryNote, fontSize = 13.sp, color = Color(StagePalette.inkMuted), maxLines = 2)
        }
        catalog.warnings.forEach { TextInk(it, fontSize = 13.sp, maxLines = 2) }
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(StagePalette.panel))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextInk("Playlists", fontSize = 13.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                catalog.playlists.forEach { playlist ->
                    PlaylistRow(playlist, catalog.playlists, selectedId == playlist.id, onSelect)
                }
            }
            Column(
                Modifier
                    .weight(0.62f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(StagePalette.panel))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                TextInk(selected?.name ?: "Escolha uma playlist", fontSize = 20.sp, weight = FontWeight.SemiBold, maxLines = 1)
                when {
                    selected == null -> TextInk("A árvore fica à esquerda.", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    selected.folder -> TextInk("Pasta. Abra uma playlist dentro dela.", fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    selected.trackIds.isEmpty() -> TextInk("Playlist vazia.", fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    else -> {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextInk("Faixa", Modifier.weight(1f), fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                            TextInk("BPM", Modifier.width(72.dp), fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                            TextInk("Tom", Modifier.width(48.dp), fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                            TextInk("Tempo", Modifier.width(64.dp), fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                            Box(Modifier.width(132.dp))
                        }
                        selected.trackIds.forEach { id ->
                            val track = catalog.tracks.firstOrNull { it.id == id } ?: return@forEach
                            TrackRow(track, catalog.trackTags[track.id].orEmpty(), onLoadDeck)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: RbPlaylist, all: List<RbPlaylist>, selected: Boolean, onSelect: (Int) -> Unit) {
    val depth = playlistDepth(playlist, all)
    val mark = if (playlist.folder) "▸" else "•"
    StageButton(
        label = "$mark  ${playlist.name}",
        onClick = { onSelect(playlist.id) },
        selected = selected,
        modifier = Modifier.fillMaxWidth().padding(start = (depth * 14).dp),
        fontSize = 14.sp,
    )
}

private fun playlistDepth(playlist: RbPlaylist, all: List<RbPlaylist>): Int {
    var depth = 0
    var parent = playlist.parentId
    val seen = mutableSetOf<Int>()
    while (parent != 0 && seen.add(parent)) {
        depth += 1
        parent = all.firstOrNull { it.id == parent }?.parentId ?: break
    }
    return depth.coerceAtMost(4)
}

@Composable
private fun TrackRow(
    track: RbTrack,
    tags: List<os.deckbridge.logic.RbTag>,
    onLoadDeck: (RbTrack, Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextInk(track.title, fontSize = 18.sp, weight = FontWeight.SemiBold, maxLines = 1)
                TextInk(track.artist, fontSize = 14.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
            }
            TextInk("%.1f".format(track.bpm), Modifier.width(72.dp), fontSize = 20.sp, weight = FontWeight.Bold, mono = true)
            TextInk(track.keyName, Modifier.width(48.dp), fontSize = 18.sp, weight = FontWeight.Bold, mono = true)
            TextInk(clock(track.durationSeconds), Modifier.width(64.dp), fontSize = 16.sp, weight = FontWeight.SemiBold, mono = true)
            Row(Modifier.width(132.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StageButton("A", { onLoadDeck(track, 0) }, Modifier.weight(1f))
                StageButton("B", { onLoadDeck(track, 1) }, Modifier.weight(1f))
            }
        }
        if (tags.isNotEmpty()) {
            TextInk(
                tags.joinToString(" · ") { if (it.category.isBlank()) it.name else "${it.category} / ${it.name}" },
                fontSize = 13.sp,
                color = Color(StagePalette.inkMuted),
                maxLines = 1,
            )
        }
    }
}

private fun clock(seconds: Int): String {
    val whole = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(whole / 60, whole % 60)
}
