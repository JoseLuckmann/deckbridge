package os.deckbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
import os.deckbridge.logic.keysCompatible

private val selectedRow = Color(0xFF123E86)
private val keyFill = Color(0xFF12381C)
private val keyInk = Color(0xFFB6FFC8)

@Composable
fun BrowseScreen(
    catalog: RbCatalog,
    place: BrowsePlace,
    historyIds: List<Int>,
    usbLabel: String,
    jvmBanner: String?,
    focusKey: String = "",
    artworkOf: (RbTrack?) -> ByteArray? = { null },
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
    onMatchKey: (String) -> Unit = {},
    onBpmWindow: () -> Unit = {},
    onSort: (os.deckbridge.logic.ListSort) -> Unit = {},
) {
    val rows = browseRows(catalog, place, historyIds)
    val safeIndex = if (rows.isEmpty()) 0 else place.index.coerceIn(0, rows.lastIndex)
    val selected = rows.getOrNull(safeIndex)
    Column(Modifier.fillMaxSize().background(Color(StagePalette.bg))) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextInk("SOURCE", fontSize = 11.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentBlue))
            TextInk(usbLabel.ifBlank { "USB1" }, fontSize = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (!jvmBanner.isNullOrBlank()) {
                TextInk("SIMULAÇÃO · Renderização em JVM", fontSize = 10.sp, color = Color(StagePalette.simInk), maxLines = 1, modifier = Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f))
            }
            BrowseChip("BACK", onBack)
            BrowseChip("LOAD 1") { selected?.track?.let { onLoad(it, 0) } }
            BrowseChip("LOAD 2") { selected?.track?.let { onLoad(it, 1) } }
            BrowseChip("▲") { onMove(-1) }
            BrowseChip("▼") { onMove(1) }
            BrowseChip("ABRIR", onEnter)
            BrowseChip("USB") { onSimulate() }
            BrowseChip(if (place.matchKey.isBlank()) "TOM" else "TOM ON") { onMatchKey(if (place.matchKey.isBlank()) focusKey else "") }
            BrowseChip(if (place.bpmMin == 0 && place.bpmMax >= 999) "BPM" else "BPM ON") { onBpmWindow() }
            BrowseChip("FECHAR", onClose)
        }
        if (place.category == BrowseCategory.SEARCH && place.inList) {
            BasicTextField(
                value = place.query,
                onValueChange = onQuery,
                textStyle = TextStyle(color = Color(StagePalette.ink), fontSize = 14.sp),
                cursorBrush = SolidColor(Color(StagePalette.accentBlue)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).border(1.dp, Color(StagePalette.line)).padding(horizontal = 8.dp, vertical = 6.dp),
                decorationBox = { inner ->
                    if (place.query.isEmpty()) TextInk("Título ou artista", color = Color(StagePalette.inkMuted), fontSize = 13.sp)
                    inner()
                },
            )
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.width(72.dp).fillMaxHeight().border(1.dp, Color(StagePalette.line)).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrowseCategory.entries.forEach { category ->
                    val on = place.category == category
                    Column(
                        Modifier.fillMaxWidth().clickable { onCategory(category) }.padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CategoryGlyph(category, on)
                        TextInk(shortLabel(category), color = if (on) Color(StagePalette.accentBlue) else Color(StagePalette.inkMuted), fontSize = 8.sp, weight = FontWeight.Bold, maxLines = 1)
                        if (on) Box(Modifier.padding(top = 2.dp).width(16.dp).height(2.dp).background(Color(StagePalette.accentBlue)))
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().height(22.dp).border(1.dp, Color(StagePalette.line)).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextInk("#", Modifier.width(36.dp), fontSize = 10.sp, color = Color(StagePalette.inkMuted), weight = FontWeight.Bold)
                    SortHeader("TITLE", os.deckbridge.logic.ListSort.TITLE, place, Modifier.weight(1f), onSort)
                    SortHeader("BPM", os.deckbridge.logic.ListSort.BPM, place, Modifier.width(64.dp), onSort)
                    SortHeader("KEY", os.deckbridge.logic.ListSort.KEY, place, Modifier.width(64.dp), onSort)
                }
                val filterLabel = browseFilterLabel(place)
                if (filterLabel.isNotEmpty()) {
                    TextInk("FILTRO $filterLabel", fontSize = 11.sp, color = Color(StagePalette.accentOrange), weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                if (rows.isEmpty()) {
                    TextInk(
                        if (place.category == BrowseCategory.HISTORY) "Sem histórico nesta sessão." else "Lista vazia.",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    rows.forEachIndexed { index, row ->
                        BrowseLine(row, index, index == safeIndex, catalog, focusKey, artworkOf) { onPick(index) }
                    }
                }
                Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextInk("${rows.size} itens", fontSize = 10.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.weight(1f))
                    TextInk("AUTORIZAR", fontSize = 10.sp, color = Color(StagePalette.accentBlue), weight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onAuthorize))
                }
            }
        }
    }
}

@Composable
private fun BrowseLine(
    row: BrowseRow,
    index: Int,
    selected: Boolean,
    catalog: RbCatalog,
    focusKey: String,
    artworkOf: (RbTrack?) -> ByteArray?,
    onClick: () -> Unit,
) {
    val fill = if (selected) selectedRow else Color.Transparent
    val ink = Color.White
    val muted = if (selected) Color(0xFFD5DDEA) else Color(StagePalette.inkMuted)
    val compatible = row.track != null && keysCompatible(focusKey, row.keyName)
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(fill).clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextInk((index + 1).toString(), Modifier.width(36.dp), color = muted, fontSize = 12.sp, mono = true, maxLines = 1)
        if (row.track != null) {
            CoverTile(artworkOf(row.track), row.title, Modifier.padding(end = 6.dp).size(32.dp))
            MiniWave(catalog.analyses[row.track.id], Modifier.width(64.dp).height(28.dp).padding(end = 8.dp))
        } else {
            Box(Modifier.width(78.dp).height(28.dp), contentAlignment = Alignment.Center) {
                TextInk(if (row.folder) "▸" else "·", color = Color(StagePalette.accentBlue), fontSize = 14.sp)
            }
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            TextInk(row.title, color = ink, fontSize = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (row.artist.isNotBlank()) TextInk(row.artist, color = muted, fontSize = 10.sp, maxLines = 1)
        }
        TextInk(row.bpm, Modifier.width(64.dp), color = ink, fontSize = 13.sp, weight = FontWeight.Medium, mono = true, maxLines = 1)
        if (row.track != null && row.keyName.isNotBlank() && row.keyName != "—") {
            Box(
                Modifier.width(52.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(if (compatible) keyFill else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                TextInk(row.keyName, color = if (compatible) keyInk else ink, fontSize = 12.sp, weight = FontWeight.Bold, maxLines = 1)
            }
        } else {
            Box(Modifier.width(52.dp))
        }
    }
}

@Composable
private fun CategoryGlyph(category: BrowseCategory, on: Boolean) {
    val color = if (on) Color(StagePalette.accentBlue) else Color(StagePalette.ink)
    Canvas(Modifier.size(18.dp)) {
        val s = size.minDimension
        when (category) {
            BrowseCategory.PLAYLIST -> {
                repeat(3) { line ->
                    val y = s * (0.22f + line * 0.26f)
                    drawLine(color, Offset(s * 0.15f, y), Offset(s * 0.85f, y), 1.4f)
                }
            }
            BrowseCategory.TRACK -> {
                drawCircle(color, s * 0.16f, Offset(s * 0.38f, s * 0.62f), style = Stroke(1.4f))
                drawLine(color, Offset(s * 0.54f, s * 0.62f), Offset(s * 0.54f, s * 0.18f), 1.4f)
                drawLine(color, Offset(s * 0.54f, s * 0.18f), Offset(s * 0.78f, s * 0.30f), 1.4f)
            }
            BrowseCategory.ARTIST -> {
                drawCircle(color, s * 0.16f, Offset(s * 0.5f, s * 0.32f), style = Stroke(1.3f))
                drawArc(color, 200f, 140f, false, Offset(s * 0.18f, s * 0.48f), Size(s * 0.64f, s * 0.46f), style = Stroke(1.3f))
            }
            BrowseCategory.ALBUM -> drawCircle(color, s * 0.32f, Offset(s * 0.5f, s * 0.5f), style = Stroke(1.4f))
            BrowseCategory.BPM -> drawCircle(color, s * 0.28f, Offset(s * 0.5f, s * 0.5f), style = Stroke(1.4f))
            BrowseCategory.KEY -> {
                drawCircle(color, s * 0.16f, Offset(s * 0.38f, s * 0.62f), style = Stroke(1.3f))
                drawLine(color, Offset(s * 0.5f, s * 0.5f), Offset(s * 0.82f, s * 0.2f), 1.4f)
            }
            BrowseCategory.TAG -> drawRoundRect(color, Offset(s * 0.18f, s * 0.28f), Size(s * 0.64f, s * 0.42f), style = Stroke(1.3f))
            BrowseCategory.HISTORY -> drawCircle(color, s * 0.32f, Offset(s * 0.5f, s * 0.5f), style = Stroke(1.4f))
            BrowseCategory.SEARCH -> {
                drawCircle(color, s * 0.24f, Offset(s * 0.42f, s * 0.42f), style = Stroke(1.5f))
                drawLine(color, Offset(s * 0.6f, s * 0.6f), Offset(s * 0.82f, s * 0.82f), 1.6f)
            }
        }
    }
}

@Composable
private fun BrowseChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(22.dp).clip(RoundedCornerShape(4.dp)).background(Color(StagePalette.panelRaised)).border(1.dp, Color(StagePalette.line), RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = Color(StagePalette.accentBlue), fontSize = 10.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun SortHeader(
    label: String,
    sort: os.deckbridge.logic.ListSort,
    place: BrowsePlace,
    modifier: Modifier,
    onSort: (os.deckbridge.logic.ListSort) -> Unit,
) {
    val mark = when {
        place.sort != sort -> label
        place.sortDesc -> "$label ↓"
        else -> "$label ↑"
    }
    val color = if (place.sort == sort) Color(StagePalette.accentBlue) else Color(StagePalette.inkMuted)
    TextInk(mark, modifier.clickable { onSort(sort) }, fontSize = 10.sp, color = color, weight = FontWeight.Bold, maxLines = 1)
}

private fun browseFilterLabel(place: BrowsePlace): String {
    val narrow = place.bpmMin > 0 || place.bpmMax < 999
    return buildString {
        if (narrow) append("${place.bpmMin}–${place.bpmMax} BPM")
        if (place.matchKey.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append("TOM ${place.matchKey}")
        }
    }
}

private fun shortLabel(category: BrowseCategory): String = when (category) {
    BrowseCategory.PLAYLIST -> "LIST"
    BrowseCategory.TRACK -> "TRK"
    BrowseCategory.ARTIST -> "ART"
    BrowseCategory.ALBUM -> "ALB"
    BrowseCategory.BPM -> "BPM"
    BrowseCategory.KEY -> "KEY"
    BrowseCategory.TAG -> "TAG"
    BrowseCategory.HISTORY -> "HIS"
    BrowseCategory.SEARCH -> "SRC"
}
