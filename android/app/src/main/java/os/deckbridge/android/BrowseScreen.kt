package os.deckbridge.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.BrowseCategory
import os.deckbridge.logic.BrowsePlace
import os.deckbridge.logic.BrowseRow
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.browseRows

@Composable
fun BrowseScreen(
    catalog: RbCatalog,
    place: BrowsePlace,
    historyIds: List<Int>,
    usbLabel: String,
    jvmBanner: String?,
    onClose: () -> Unit,
    onMove: (Int) -> Unit,
    onEnter: () -> Unit,
    onBack: () -> Unit,
    onPick: (Int) -> Unit = {},
    onCategory: (BrowseCategory) -> Unit = {},
    onQuery: (String) -> Unit,
    onLoad: (RbTrack, Int) -> Unit,
    onAuthorize: () -> Unit,
    onSimulate: () -> Unit,
) {
    val rows = browseRows(catalog, place, historyIds)
    val safeIndex = if (rows.isEmpty()) 0 else place.index.coerceIn(0, rows.lastIndex)
    val selected = rows.getOrNull(safeIndex)
    BoxFill {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextInk("SOURCE", fontSize = 11.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                TextInk(usbLabel.ifBlank { "USB" }, fontSize = 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                Mini("BACK", onBack)
                Mini("FECHAR", onClose)
                Mini("LOAD DECK 1", { selected?.track?.let { onLoad(it, 0) } })
                Mini("LOAD DECK 2", { selected?.track?.let { onLoad(it, 1) } })
            }
            if (!jvmBanner.isNullOrBlank()) {
                TextInk("SIMULAÇÃO · $jvmBanner", fontSize = 11.sp, color = Color(StagePalette.simInk), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Mini("AUTORIZAR", onAuthorize)
                Mini("SIMULADO", onSimulate)
                Mini("HISTORY", { onCategory(BrowseCategory.HISTORY) })
                Mini("▲", { onMove(-1) })
                Mini("▼", { onMove(1) })
                Mini("ABRIR", onEnter)
            }
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    Modifier.width(168.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color(StagePalette.panel)).verticalScroll(rememberScrollState()).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextInk("CATEGORIA", fontSize = 10.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                    BrowseCategory.entries.forEach { category ->
                        val highlighted = if (place.inList) place.category == category else BrowseCategory.entries.getOrNull(place.index) == category
                        Mini(category.label, { onCategory(category) }, selected = highlighted, fill = true)
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color(StagePalette.panel)).padding(8.dp),
                ) {
                    if (place.category == BrowseCategory.SEARCH && place.inList) {
                        BasicTextField(
                            value = place.query,
                            onValueChange = onQuery,
                            textStyle = TextStyle(color = Color(StagePalette.ink), fontSize = 16.sp),
                            cursorBrush = SolidColor(Color(StagePalette.ink)),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(StagePalette.bg)).padding(8.dp),
                            decorationBox = { inner ->
                                if (place.query.isEmpty()) TextInk("Buscar título ou artista", color = Color(StagePalette.inkMuted), fontSize = 14.sp)
                                inner()
                            },
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        TextInk("FAIXA", Modifier.weight(1f), fontSize = 11.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                        TextInk("BPM", Modifier.width(64.dp), fontSize = 11.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                        TextInk("TOM", Modifier.width(48.dp), fontSize = 11.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
                    }
                    if (rows.isEmpty()) {
                        TextInk(
                            if (place.category == BrowseCategory.HISTORY) "Sem histórico nesta sessão." else "Lista vazia.",
                            fontSize = 16.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        rows.forEachIndexed { index, row ->
                            BrowseLine(row, index == safeIndex) { onPick(index) }
                        }
                    }
                }
                if (selected?.track != null) {
                    TrackInfo(selected.track, catalog, Modifier.width(240.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun BoxFill(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(StagePalette.bg))) { content() }
}

@Composable
private fun BrowseLine(row: BrowseRow, selected: Boolean, onClick: () -> Unit) {
    val fill = if (selected) Color(StagePalette.navSelected) else Color.Transparent
    val ink = if (selected) Color(StagePalette.navSelectedInk) else Color(StagePalette.ink)
    val muted = if (selected) Color(StagePalette.navSelectedInk) else Color(StagePalette.inkMuted)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(4.dp)).background(fill).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.track != null) {
            val color = Color(0xFF000000.toInt() or (StagePalette.pads[kotlin.math.abs(row.track.id) % StagePalette.pads.size] and 0xFFFFFF))
            Column(Modifier.size(28.dp).clip(RoundedCornerShape(3.dp)).background(color)) {}
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            TextInk(row.title, color = ink, fontSize = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (row.artist.isNotBlank()) TextInk(row.artist, color = muted, fontSize = 12.sp, maxLines = 1)
        }
        TextInk(row.bpm, Modifier.width(64.dp), color = ink, fontSize = 16.sp, weight = FontWeight.Bold, mono = true)
        TextInk(row.keyName, Modifier.width(48.dp), color = ink, fontSize = 16.sp, weight = FontWeight.Bold, mono = true)
    }
}

@Composable
private fun TrackInfo(track: RbTrack, catalog: RbCatalog, modifier: Modifier) {
    val tags = catalog.trackTags[track.id].orEmpty()
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color(StagePalette.panel)).padding(10.dp)) {
        TextInk("INFO", fontSize = 10.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
        TextInk(track.title, fontSize = 18.sp, weight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        TextInk(track.artist, fontSize = 14.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
        TextInk("${"%.1f".format(track.bpm)} BPM", fontSize = 20.sp, weight = FontWeight.Bold, mono = true, modifier = Modifier.padding(top = 8.dp))
        TextInk(track.keyName, fontSize = 18.sp, weight = FontWeight.Bold, mono = true)
        TextInk(track.album.ifBlank { "Sem álbum" }, fontSize = 13.sp, color = Color(StagePalette.inkMuted), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        if (tags.isNotEmpty()) {
            TextInk(tags.joinToString(" · ") { it.name }, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Mini(label: String, onClick: () -> Unit, selected: Boolean = false, fill: Boolean = false) {
    val background = if (selected) Color(StagePalette.navSelected) else Color(StagePalette.panelRaised)
    val ink = if (selected) Color(StagePalette.navSelectedInk) else Color(StagePalette.ink)
    TextInk(
        label,
        color = ink,
        fontSize = 13.sp,
        weight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .border(1.dp, Color(StagePalette.line), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
