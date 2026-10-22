package os.deckbridge.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.BEAT_FX_FRACTIONS
import os.deckbridge.logic.BEAT_FX_NAMES
import os.deckbridge.logic.BeatFxState
import os.deckbridge.logic.BrowseCategory
import os.deckbridge.logic.BrowsePlace
import os.deckbridge.logic.DeckFace
import os.deckbridge.logic.LETTER_COLORS
import os.deckbridge.logic.RbAnalysis
import os.deckbridge.logic.RbCatalog
import os.deckbridge.logic.PlayerSettings
import os.deckbridge.logic.RbTrack
import os.deckbridge.logic.RmxState
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.WaveZoom
import os.deckbridge.logic.waveModeIndex
import os.deckbridge.logic.activeLoop
import os.deckbridge.logic.barsFromSeconds
import os.deckbridge.logic.beatFxBeats
import os.deckbridge.logic.inkOn
import os.deckbridge.logic.loopLengthLabel
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    catalog: RbCatalog?,
    tracks: List<RbTrack?>,
    faces: List<DeckFace>,
    master: Int,
    quantize: Boolean,
    zoom: List<WaveZoom>,
    zoomLink: Boolean,
    fx: BeatFxState,
    jvmBanner: String?,
    browse: BrowsePlace? = null,
    historyIds: List<Int> = emptyList(),
    usbLabel: String = "",
    clock: String = "--:--",
    activeDeck: Int = 0,
    onPage: (Int) -> Unit = {},
    onBrowse: () -> Unit = {},
    onBrowseClose: () -> Unit = {},
    onZoom: (Int, WaveZoom) -> Unit = { _, _ -> },
    onLink: (Boolean) -> Unit = {},
    onPlay: (Int) -> Unit = {},
    onCue: (Int) -> Unit = {},
    onHot: (Int, Int) -> Unit = { _, _ -> },
    onSync: (Int) -> Unit = {},
    onMaster: (Int) -> Unit = {},
    onKey: (Int) -> Unit = {},
    onRemain: (Int) -> Unit = {},
    onRange: (Int) -> Unit = {},
    onFader: (Int, Float) -> Unit = { _, _ -> },
    onScratch: (Int, Float) -> Unit = { _, _ -> },
    onJump: (Int, Int) -> Unit = { _, _ -> },
    onQuantize: () -> Unit = {},
    onAuthorize: () -> Unit = {},
    onSimulate: () -> Unit = {},
    onBrowseMove: (Int) -> Unit = {},
    onBrowseEnter: () -> Unit = {},
    onBrowseBack: () -> Unit = {},
    onBrowsePick: (Int) -> Unit = {},
    onBrowseCategory: (BrowseCategory) -> Unit = {},
    onQuery: (String) -> Unit = {},
    onLoad: (RbTrack, Int) -> Unit = { _, _ -> },
    onActive: (Int) -> Unit = {},
    onFxChange: (BeatFxState) -> Unit = {},
    onOpenCategory: (BrowseCategory) -> Unit = {},
    onSeek: (Int, Float) -> Unit = { _, _ -> },
    status: String = "",
    nav: String = "",
    overlay: String = "",
    onOverlay: (String) -> Unit = {},
    settings: PlayerSettings = PlayerSettings(),
    onSettings: (PlayerSettings) -> Unit = {},
    rmx: RmxState = RmxState(),
    onRmx: (RmxState) -> Unit = {},
    fxList: Boolean = false,
    tagNames: Map<Int, List<String>> = emptyMap(),
    onTag: (Int, String, Boolean) -> Unit = { _, _, _ -> },
    midiLine: String = "",
    onDiagnostics: () -> Unit = {},
    onTestTone: (Int) -> Unit = {},
    onBeatLoop: (Int, Double) -> Unit = { _, _ -> },
    onLoopButton: (Int, String) -> Unit = { _, _ -> },
    searchBpm: Pair<Int, Int> = 0 to 999,
    onSearchBpm: (Int, Int) -> Unit = { _, _ -> },
    searchKey: String = "",
    onSearchKey: (String) -> Unit = {},
    queryText: String = "",
    tagTrack: Int? = null,
    onTagTrack: (Int) -> Unit = {},
) {
    var info by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var fxPanel by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf("") }
    val shownNav = nav.ifBlank {
        when {
            overlay == "menu" -> "MENU"
            overlay == "search" -> "SEARCH"
            overlay == "tags" -> "TAG LIST"
            browse != null -> picked.ifBlank { "BROWSE" }
            else -> picked
        }
    }
    val note = if (jvmBanner.isNullOrBlank()) null else "SIMULAÇÃO · Renderização em JVM"
    val masterTrack = tracks.getOrNull(master)
    val masterFace = faces.getOrElse(master) { DeckFace() }
    val setRemain = remainClock(masterTrack, masterFace)
    val fxDeck = when (fx.channel) {
        1 -> 1
        2 -> master
        else -> 0
    }
    val fxBpm = (tracks.getOrNull(fxDeck)?.bpm ?: 0.0) * (faces.getOrElse(fxDeck) { DeckFace() }.pitch)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(StagePalette.bg))) {
        val compact = maxWidth < 1400.dp || maxHeight < 700.dp
        val side = if (compact) 112.dp else 168.dp
        val rail = if (compact) 156.dp else 228.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 3.dp else 6.dp)) {
            TopBar(
                compact = compact,
                note = note,
                clock = clock,
                remain = setRemain,
                nav = shownNav,
                onNav = { id ->
                    picked = id
                    when (id) {
                        "SOURCE", "BROWSE" -> {
                            onOverlay("")
                            onBrowse()
                        }
                        "TAG LIST" -> onOverlay("tags")
                        "PLAYLIST" -> {
                            onOverlay("")
                            onOpenCategory(BrowseCategory.PLAYLIST)
                        }
                        "SEARCH" -> onOverlay("search")
                        "MENU" -> onOverlay("menu")
                    }
                },
                onInfo = { info = !info },
            )
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(top = if (compact) 3.dp else 6.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
            ) {
                Column(Modifier.width(side).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DeckInfo(0, tracks.getOrNull(0), faces.getOrElse(0) { DeckFace() }, catalog, quantize, settings.quantizeBeats, compact, Modifier.weight(1f))
                    DeckInfo(1, tracks.getOrNull(1), faces.getOrElse(1) { DeckFace() }, catalog, quantize, settings.quantizeBeats, compact, Modifier.weight(1f))
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ZoomedWave(0, tracks.getOrNull(0), analysisOf(catalog, tracks.getOrNull(0)), faces.getOrElse(0) { DeckFace() }, zoom.first(), grid, onZoom, onSeek, Modifier.weight(1f).fillMaxWidth(), settings.playhead.toDouble(), waveModeIndex(settings.waveColor))
                    ZoomedWave(1, tracks.getOrNull(1), analysisOf(catalog, tracks.getOrNull(1)), faces.getOrElse(1) { DeckFace() }, if (zoomLink) zoom.first() else zoom.getOrElse(1) { zoom.first() }, grid, onZoom, onSeek, Modifier.weight(1f).fillMaxWidth(), settings.playhead.toDouble(), waveModeIndex(settings.waveColor))
                }
                FxRail(
                    fx = fx,
                    bpm = fxBpm,
                    zoom = zoom.first(),
                    grid = grid,
                    fxPanel = fxPanel,
                    quantize = quantize,
                    status = status,
                    compact = compact,
                    onFxChange = onFxChange,
                    onGrid = { grid = it },
                    onFxPanel = { fxPanel = it },
                    onZoom = { onZoom(0, it) },
                    rmx = rmx,
                    onRmx = onRmx,
                    fxList = fxList,
                    modifier = Modifier.width(rail).fillMaxHeight(),
                )
            }
            HotCueBand(catalog, tracks, compact, onActive, onHot, Modifier.fillMaxWidth().padding(top = if (compact) 3.dp else 6.dp))
            PerformanceStrip(
                deck = activeDeck,
                compact = compact,
                loopOn = faces.getOrElse(activeDeck) { DeckFace() }.let { it.loopEnd > it.loopStart },
                modifier = Modifier.fillMaxWidth().padding(top = if (compact) 3.dp else 4.dp),
                onJump = { beats -> onJump(activeDeck, beats) },
                onLoop = { which -> onLoopButton(activeDeck, which) },
                onBeatLoop = { beats -> onBeatLoop(activeDeck, beats) },
            )
            Row(
                Modifier.fillMaxWidth().height(if (compact) 118.dp else 176.dp).padding(top = if (compact) 3.dp else 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DeckCard(0, tracks.getOrNull(0), faces.getOrElse(0) { DeckFace() }, analysisOf(catalog, tracks.getOrNull(0)), master == 0, activeDeck == 0, quantize, settings.quantizeBeats, compact, Modifier.weight(1f).fillMaxHeight(), onActive, onRemain, onRange, onMaster, onQuantize, onPlay)
                DeckCard(1, tracks.getOrNull(1), faces.getOrElse(1) { DeckFace() }, analysisOf(catalog, tracks.getOrNull(1)), master == 1, activeDeck == 1, quantize, settings.quantizeBeats, compact, Modifier.weight(1f).fillMaxHeight(), onActive, onRemain, onRange, onMaster, onQuantize, onPlay)
            }
        }
        if (info) {
            InfoCard(tracks.getOrNull(activeDeck), Modifier.align(Alignment.TopEnd).padding(12.dp)) { info = false }
        }
        if (overlay == "menu") {
            MenuScreen(settings, midiLine, onSettings, { onOverlay("") }, onDiagnostics, onTestTone)
        } else if (overlay == "search" && catalog != null) {
            SearchScreen(catalog.tracks, queryText, searchBpm.first, searchBpm.second, searchKey, onQuery, onSearchBpm, onSearchKey, onLoad) { onOverlay("") }
        } else if (overlay == "tags") {
            val pool = tracks.filterNotNull().ifEmpty { catalog?.tracks.orEmpty() }
            TagScreen(pool, tagNames, tagTrack, onTagTrack, onTag) { onOverlay("") }
        } else if (rmx.full) {
            RmxScreen(rmx, onRmx) { onRmx(rmx.copy(full = false)) }
        }
        if (browse != null && catalog != null && overlay.isEmpty() && !rmx.full) {
            BrowseScreen(
                catalog = catalog,
                place = browse,
                historyIds = historyIds,
                usbLabel = usbLabel,
                jvmBanner = jvmBanner,
                focusKey = tracks.getOrNull(master)?.keyName.orEmpty(),
                onClose = onBrowseClose,
                onMove = onBrowseMove,
                onEnter = onBrowseEnter,
                onBack = onBrowseBack,
                onPick = onBrowsePick,
                onCategory = onBrowseCategory,
                onQuery = onQuery,
                onLoad = onLoad,
                onAuthorize = onAuthorize,
                onSimulate = onSimulate,
            )
        }
    }
}

@Composable
private fun TopBar(
    compact: Boolean,
    note: String?,
    clock: String,
    remain: String,
    nav: String,
    onNav: (String) -> Unit,
    onInfo: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(if (compact) 16.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (note != null) {
                TextInk(note, color = Color(StagePalette.simInk), fontSize = if (compact) 9.sp else 11.sp, maxLines = 1, modifier = Modifier.widthIn(max = if (compact) 180.dp else 280.dp))
            }
            Spacer(Modifier.weight(1f))
            TextInk(clock, fontSize = if (compact) 11.sp else 13.sp, weight = FontWeight.Medium, mono = true)
            TextInk("REMAIN", fontSize = 9.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(start = 8.dp))
            TextInk(remain, fontSize = if (compact) 11.sp else 13.sp, weight = FontWeight.Medium, mono = true)
            Box(Modifier.padding(start = 8.dp).clickable(onClick = onInfo)) {
                TextInk("INFO", color = Color(StagePalette.accentBlue), fontSize = 11.sp, weight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth().height(if (compact) 44.dp else 60.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("SOURCE", "BROWSE", "TAG LIST", "PLAYLIST", "SEARCH", "MENU").forEach { label ->
                NavButton(label, compact, nav == label, Modifier.weight(1f)) { onNav(label) }
            }
        }
    }
}

@Composable
private fun DeckInfo(
    deck: Int,
    track: RbTrack?,
    face: DeckFace,
    catalog: RbCatalog?,
    quantize: Boolean,
    quantizeBeats: Double,
    compact: Boolean,
    modifier: Modifier,
) {
    val analysis = analysisOf(catalog, track)
    val bpm = track?.bpm ?: 0.0
    val bars = if (track == null) null else barsFromSeconds(face.position.toDouble(), bpm)
    val loop = activeLoop(analysis)
    Column(
        modifier.fillMaxWidth().border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(if (compact) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
    ) {
        TextInk("DECK ${deck + 1}", fontSize = if (compact) 11.sp else 13.sp, weight = FontWeight.Bold, maxLines = 1)
        Box(Modifier.width(if (compact) 28.dp else 36.dp).height(2.dp).background(Color(StagePalette.accentBlue)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            UsbMark()
            TextInk("USB1", fontSize = if (compact) 10.sp else 12.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp), maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("KEY", fontSize = if (compact) 9.sp else 11.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.width(if (compact) 28.dp else 36.dp))
            TextInk(track?.keyName?.ifBlank { "—" } ?: "—", fontSize = if (compact) 12.sp else 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
        }
        if (bars == null) {
            TextInk("—", color = Color(StagePalette.accentOrange), fontSize = if (compact) 12.sp else 16.sp, weight = FontWeight.Bold)
        } else {
            TextInk("%.1f Bars".format(bars), color = Color(StagePalette.accentOrange), fontSize = if (compact) 12.sp else 16.sp, weight = FontWeight.Bold, maxLines = 1)
        }
        if (face.loopEnd > face.loopStart && track != null) {
            MetaLine("LOOP", loopLengthLabel((face.loopStart * 1000).roundToInt(), (face.loopEnd * 1000).roundToInt(), track.bpm), compact)
        } else if (loop != null && track != null) {
            MetaLine("LOOP", loopLengthLabel(loop.first, loop.second, track.bpm), compact)
        } else {
            MetaLine("LOOP", "—", compact)
        }
        MetaLine("JUMP", if (face.lastJump == 0.0) "1" else "%+.0f".format(face.lastJump), compact)
        MetaLine("Q", if (quantize) quantizeMark(quantizeBeats) else "OFF", compact)
    }
}

@Composable
private fun MetaLine(label: String, value: String, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextInk(label, fontSize = if (compact) 9.sp else 10.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.width(if (compact) 36.dp else 44.dp), maxLines = 1)
        TextInk(value, fontSize = if (compact) 10.sp else 12.sp, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun UsbMark() {
    Canvas(Modifier.width(12.dp).height(14.dp)) {
        val ink = Color(StagePalette.ink)
        drawRoundRect(ink, topLeft = Offset(size.width * 0.22f, size.height * 0.38f), size = Size(size.width * 0.56f, size.height * 0.55f), style = Stroke(1.3f))
        drawLine(ink, Offset(size.width * 0.36f, size.height * 0.16f), Offset(size.width * 0.36f, size.height * 0.42f), 1.2f)
        drawLine(ink, Offset(size.width * 0.5f, size.height * 0.06f), Offset(size.width * 0.5f, size.height * 0.42f), 1.2f)
        drawLine(ink, Offset(size.width * 0.64f, size.height * 0.16f), Offset(size.width * 0.64f, size.height * 0.42f), 1.2f)
    }
}

@Composable
private fun FxRail(
    fx: BeatFxState,
    bpm: Double,
    zoom: WaveZoom,
    grid: Boolean,
    fxPanel: Boolean,
    quantize: Boolean,
    status: String,
    compact: Boolean,
    onFxChange: (BeatFxState) -> Unit,
    onGrid: (Boolean) -> Unit,
    onFxPanel: (Boolean) -> Unit,
    onZoom: (WaveZoom) -> Unit,
    rmx: RmxState,
    onRmx: (RmxState) -> Unit,
    fxList: Boolean,
    modifier: Modifier,
) {
    var listOpen by remember { mutableStateOf(false) }
    val showList = fxList || listOpen
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(if (compact) 4.dp else 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Choice("BEAT FX", fxPanel && !rmx.panel, compact, Modifier.weight(1f)) {
                    onFxPanel(true)
                    onRmx(rmx.copy(panel = false, full = false))
                }
                Choice("RMX", rmx.panel, compact, Modifier.weight(1f)) { onRmx(rmx.copy(panel = true)) }
            }
            if (rmx.panel) {
                RmxRail(rmx, compact, onRmx) { onRmx(rmx.copy(full = true, panel = true)) }
            } else if (fxPanel) {
                FxBody(fx, bpm, compact, showList, onFxChange)
            } else {
                TextInk("STATUS", fontSize = 10.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp))
                TextInk(status.ifBlank { "À espera de uma faixa." }, fontSize = if (compact) 10.sp else 12.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 4)
                TextInk(if (quantize) "QUANTIZE 1/4" else "QUANTIZE OFF", color = Color(StagePalette.accentBlue), fontSize = 11.sp, weight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                TextInk("LVL ${fx.level}", fontSize = 11.sp, mono = true, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Choice("ZOOM", !grid, compact, Modifier.weight(1f)) { onGrid(false) }
            Choice("GRID", grid, compact, Modifier.weight(1f)) { onGrid(true) }
        }
        if (!grid) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Choice("−", false, compact) { onZoom(zoom.zoomOut()) }
                TextInk(if (zoom.bars == null) "ALL" else "${zoom.bars}", fontSize = 12.sp, weight = FontWeight.Bold, mono = true, modifier = Modifier.weight(1f), maxLines = 1)
                Choice("+", false, compact) { onZoom(zoom.zoomIn()) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Choice("STATUS", !fxPanel && !rmx.panel, compact, Modifier.weight(1f)) {
                onRmx(rmx.copy(panel = false))
                onFxPanel(false)
            }
            Choice("LISTA", showList && fxPanel && !rmx.panel, compact, Modifier.weight(1f)) { listOpen = !listOpen }
        }
    }
}

@Composable
private fun FxBody(fx: BeatFxState, bpm: Double, compact: Boolean, list: Boolean, onFxChange: (BeatFxState) -> Unit) {
    val on = fx.on
    val nameFill = if (on) Color(StagePalette.accentBlue) else Color(StagePalette.panelRaised)
    val nameInk = if (on) Color(inkOn(StagePalette.accentBlue)) else Color(StagePalette.accentBlue)
    Row(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.weight(1f).height(if (compact) 28.dp else 40.dp).clip(RoundedCornerShape(2.dp)).background(nameFill).border(1.dp, Color(StagePalette.accentBlue)).clickable {
                onFxChange(fx.copy(on = !fx.on))
            },
            contentAlignment = Alignment.Center,
        ) {
            TextInk(fx.name().uppercase(), color = nameInk, fontSize = if (compact) 12.sp else 16.sp, weight = FontWeight.Bold, maxLines = 1)
        }
        Box(
            Modifier.width(if (compact) 22.dp else 28.dp).height(if (compact) 28.dp else 40.dp).border(1.dp, Color(StagePalette.line)).clickable {
                onFxChange(fx.copy(effect = (fx.effect + 1) % BEAT_FX_NAMES.size))
            },
            contentAlignment = Alignment.Center,
        ) {
            TextInk("›", color = Color(StagePalette.accentBlue), fontSize = 16.sp, weight = FontWeight.Bold)
        }
    }
    if (list) {
        Column(Modifier.fillMaxWidth().height(if (compact) 150.dp else 240.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
            BEAT_FX_NAMES.forEachIndexed { index, name ->
                val selected = fx.effect == index
                TextInk(
                    name.uppercase(),
                    color = Color(if (selected) StagePalette.accentOrange else StagePalette.accentBlue),
                    fontSize = if (compact) 11.sp else 13.sp,
                    weight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clickable { onFxChange(fx.copy(effect = index, on = true)) }.padding(vertical = 2.dp),
                )
            }
        }
        return
    }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextInk(if (bpm > 1.0) "%.2f".format(bpm) else "—", fontSize = if (compact) 14.sp else 18.sp, weight = FontWeight.Medium, mono = true)
        TextInk("BPM", fontSize = 9.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.weight(1f))
        Box(Modifier.border(1.dp, Color(0xFF5CDBF0), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
            TextInk("AUTO", color = Color(0xFF5CDBF0), fontSize = 9.sp, weight = FontWeight.Bold)
        }
    }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf("3/4", "1", "2").forEach { label ->
            val selected = bigFractionSelected(fx, label)
            FractionCell(label, selected, compact, Modifier.weight(1f)) {
                onFxChange(fx.copy(fraction = fractionIndex(label)))
            }
        }
    }
    val beats = beatFxBeats(fx.fractionLabel())
    val ms = if (bpm > 1.0) (beats * 60000.0 / bpm).roundToInt() else 0
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
        TextInk("TIME", fontSize = 9.sp, color = Color(StagePalette.inkMuted))
        Spacer(Modifier.weight(1f))
        TextInk(if (bpm > 1.0) ms.toString() else "—", fontSize = if (compact) 13.sp else 16.sp, weight = FontWeight.Medium, mono = true)
        TextInk(" ms", fontSize = 10.sp, color = Color(StagePalette.inkMuted))
    }
    TextInk("X-PAD PARAMETER", fontSize = 8.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    TextInk("BEAT", fontSize = 9.sp, color = Color(0xFF5CDBF0), weight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
    Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("1/16", "1/8", "1/4", "1/2", "3/4", "1").forEach { label ->
            FractionCell(label, xpadSelected(fx, label), compact, Modifier.weight(1f)) {
                onFxChange(fx.copy(fraction = fractionIndex(label)))
            }
        }
    }
    TextInk("CH SELECT", fontSize = 9.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf("1" to 0, "2" to 1, "MST" to 2).forEach { (label, channel) ->
            Choice(label, fx.channel == channel, compact, Modifier.weight(1f)) { onFxChange(fx.copy(channel = channel)) }
        }
    }
}

@Composable
private fun HotCueBand(
    catalog: RbCatalog?,
    tracks: List<RbTrack?>,
    compact: Boolean,
    onActive: (Int) -> Unit,
    onHot: (Int, Int) -> Unit,
    modifier: Modifier,
) {
    Row(modifier.height(if (compact) 78.dp else 108.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HotGrid(0, analysisOf(catalog, tracks.getOrNull(0)), compact, Modifier.weight(1f).fillMaxHeight(), onActive, onHot)
        HotGrid(1, analysisOf(catalog, tracks.getOrNull(1)), compact, Modifier.weight(1f).fillMaxHeight(), onActive, onHot)
    }
}

@Composable
private fun HotGrid(
    deck: Int,
    analysis: RbAnalysis?,
    compact: Boolean,
    modifier: Modifier,
    onActive: (Int) -> Unit,
    onHot: (Int, Int) -> Unit,
) {
    Column(modifier) {
        TextInk("HOT CUE", fontSize = if (compact) 9.sp else 11.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted))
        repeat(2) { row ->
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(4) { column ->
                    val index = row * 4 + column
                    val cue = analysis?.hotCues?.firstOrNull { it.index == index }
                    val letter = ('A' + index).toString()
                    val color = argb(LETTER_COLORS[index])
                    Row(
                        Modifier.weight(1f).fillMaxHeight().background(Color(StagePalette.panel)).border(1.dp, Color(StagePalette.line)).clickable {
                            onActive(deck)
                            onHot(deck, index)
                        }.padding(horizontal = if (compact) 3.dp else 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextInk(letter, color = color, fontSize = if (compact) 12.sp else 15.sp, weight = FontWeight.Bold)
                        TextInk(
                            cue?.comment?.ifBlank { "" } ?: "",
                            fontSize = if (compact) 9.sp else 12.sp,
                            color = if (cue == null) Color(StagePalette.inkMuted) else Color(StagePalette.ink),
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PerformanceStrip(
    deck: Int,
    compact: Boolean,
    loopOn: Boolean,
    modifier: Modifier,
    onJump: (Int) -> Unit,
    onLoop: (String) -> Unit,
    onBeatLoop: (Double) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().height(if (compact) 22.dp else 28.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInk("D${deck + 1}", fontSize = if (compact) 9.sp else 11.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentBlue), modifier = Modifier.width(if (compact) 22.dp else 28.dp))
            listOf(-32, -16, -8, -4, -2, -1, 1, 2, 4, 8, 16, 32).forEach { beats ->
                Choice(if (beats > 0) "+$beats" else "$beats", false, compact, Modifier.weight(1f)) { onJump(beats) }
            }
        }
        Row(Modifier.fillMaxWidth().height(if (compact) 22.dp else 28.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInk("LOOP", fontSize = if (compact) 9.sp else 11.sp, weight = FontWeight.Bold, color = Color(StagePalette.inkMuted), modifier = Modifier.width(if (compact) 36.dp else 44.dp))
            Choice("4", loopOn, compact, Modifier.weight(1f)) { onLoop("4") }
            Choice("IN", false, compact, Modifier.weight(1f)) { onLoop("IN") }
            Choice("OUT", false, compact, Modifier.weight(1f)) { onLoop("OUT") }
            Choice("1/2", false, compact, Modifier.weight(1f)) { onLoop("HALF") }
            Choice("x2", false, compact, Modifier.weight(1f)) { onLoop("DOUBLE") }
            Choice("EXIT", loopOn, compact, Modifier.weight(1f)) { onLoop("RELOOP") }
            listOf(0.25 to "1/4", 0.5 to "1/2", 1.0 to "1", 2.0 to "2", 8.0 to "8", 16.0 to "16", 32.0 to "32").forEach { (beats, label) ->
                Choice(label, false, compact, Modifier.weight(1f)) { onBeatLoop(beats) }
            }
        }
    }
}

@Composable
private fun DeckCard(
    deck: Int,
    track: RbTrack?,
    face: DeckFace,
    analysis: RbAnalysis?,
    master: Boolean,
    active: Boolean,
    quantize: Boolean,
    quantizeBeats: Double,
    compact: Boolean,
    modifier: Modifier,
    onActive: (Int) -> Unit,
    onRemain: (Int) -> Unit,
    onRange: (Int) -> Unit,
    onMaster: (Int) -> Unit,
    onQuantize: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    val duration = (track?.durationSeconds ?: 0).toDouble()
    val shown = if (track == null) {
        0.0
    } else if (face.remain) {
        (duration - face.position).coerceAtLeast(0.0)
    } else {
        face.position.toDouble().coerceAtLeast(0.0)
    }
    val clock = splitClock(shown)
    val percent = (face.pitch - 1f) * 100f
    val pitched = if (track == null) 0.0 else track.bpm * face.pitch
    Column(
        modifier.border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).clickable { onActive(deck) }.padding(if (compact) 4.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val badgeFill = if (active) Color(StagePalette.accentBlue) else Color(StagePalette.panelRaised)
            val badgeInk = if (active) Color(inkOn(StagePalette.accentBlue)) else Color(StagePalette.ink)
            Box(Modifier.clip(RoundedCornerShape(2.dp)).background(badgeFill).padding(horizontal = 5.dp, vertical = 1.dp)) {
                TextInk("DECK ${deck + 1}", color = badgeInk, fontSize = if (compact) 9.sp else 11.sp, weight = FontWeight.Bold)
            }
            TextInk("♪", color = Color(StagePalette.accentBlue), fontSize = if (compact) 12.sp else 14.sp, modifier = Modifier.padding(start = 6.dp))
            TextInk(track?.title ?: "Sem faixa", fontSize = if (compact) 12.sp else 15.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (face.onAir) {
                Box(Modifier.clip(RoundedCornerShape(2.dp)).background(Color(StagePalette.onAir)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    TextInk("ON AIR", color = Color.White, fontSize = if (compact) 8.sp else 10.sp, weight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.padding(top = if (compact) 2.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { onRemain(deck) }) {
                TextInk(if (face.remain) "REMAIN" else "TIME", fontSize = 9.sp, color = Color(StagePalette.inkMuted))
                Row(verticalAlignment = Alignment.Bottom) {
                    TextInk(clock.first, fontSize = if (compact) 22.sp else 36.sp, weight = FontWeight.Medium, mono = true, maxLines = 1)
                    TextInk(clock.second, fontSize = if (compact) 12.sp else 16.sp, weight = FontWeight.Medium, mono = true, modifier = Modifier.padding(bottom = if (compact) 2.dp else 5.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp).clickable { onRange(deck) }) {
                TextInk("TEMPO", fontSize = 9.sp, color = Color(StagePalette.inkMuted))
                TextInk(tempoText(percent), fontSize = if (compact) 11.sp else 13.sp, weight = FontWeight.SemiBold, mono = true, maxLines = 1)
                Box(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(2.dp)).background(Color(StagePalette.playBg)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                    TextInk(rangeBadge(face.range), color = Color(StagePalette.playInk), fontSize = 10.sp, weight = FontWeight.Bold)
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.clickable { onMaster(deck) }) {
                if (master) {
                    Box(Modifier.clip(RoundedCornerShape(2.dp)).background(Color(StagePalette.accentOrange)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                        TextInk("MASTER", color = Color(inkOn(StagePalette.accentOrange)), fontSize = 8.sp, weight = FontWeight.Bold)
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                }
                val border = if (master) Color(StagePalette.accentOrange) else Color(StagePalette.ink)
                val parts = bpmParts(pitched)
                Row(
                    Modifier.padding(top = 2.dp).border(1.5.dp, border).padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 2.dp else 4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    TextInk(parts.first, fontSize = if (compact) 18.sp else 28.sp, weight = FontWeight.Medium, mono = true)
                    TextInk(parts.second, fontSize = if (compact) 11.sp else 14.sp, weight = FontWeight.Medium, mono = true, modifier = Modifier.padding(bottom = if (compact) 1.dp else 3.dp))
                }
            }
        }
        OverviewWave(track, analysis, face.position.toDouble(), Modifier.fillMaxWidth().weight(1f).padding(top = 3.dp))
        PhraseBar(track, Modifier.fillMaxWidth().height(if (compact) 4.dp else 7.dp))
        Row(Modifier.padding(top = if (compact) 2.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable { onPlay(deck) }) { Lamp(if (face.playing) "PLAY" else "PAUSE", face.playing, compact) }
            Lamp("A.HOT CUE", true, compact)
            Lamp("AUTO CUE", analysis?.mainCueMs != null && analysis.mainCueMs >= 0, compact)
            Box(Modifier.clickable(onClick = onQuantize)) {
                Lamp(if (quantize) "QUANTIZE ${quantizeMark(quantizeBeats)}" else "QUANTIZE", quantize, compact)
            }
        }
    }
}

@Composable
private fun InfoCard(track: RbTrack?, modifier: Modifier, onClose: () -> Unit) {
    Column(modifier.width(260.dp).background(Color(StagePalette.panel)).border(1.dp, Color(StagePalette.line)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("INFO", fontSize = 12.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentBlue), modifier = Modifier.weight(1f))
            NavButton("FECHAR", compact = true, onClick = onClose)
        }
        TextInk(track?.title ?: "Sem faixa", fontSize = 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp), maxLines = 2)
        TextInk(track?.artist ?: "", fontSize = 13.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
        TextInk(track?.album ?: "", fontSize = 13.sp, maxLines = 1)
        TextInk(listOfNotNull(track?.keyName?.takeIf { it.isNotBlank() }, track?.let { "%.1f BPM".format(it.bpm) }).joinToString("  ·  "), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun NavButton(label: String, compact: Boolean, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fill = if (selected) {
        Brush.verticalGradient(listOf(Color(0xFF315A93), Color(0xFF1A3A68)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF2A2E36), Color(0xFF121418)))
    }
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(fill).border(if (selected) 2.dp else 1.dp, Color(if (selected) StagePalette.accentBlue else StagePalette.line), RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = Color(if (selected) StagePalette.ink else StagePalette.accentBlue), fontSize = if (compact) 11.sp else 15.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, compact: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(if (compact) 20.dp else 24.dp).border(if (selected) 1.5.dp else 1.dp, Color(if (selected) StagePalette.accentBlue else StagePalette.line), RoundedCornerShape(2.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = if (selected) Color(StagePalette.accentBlue) else Color(StagePalette.ink), fontSize = if (compact) 9.sp else 11.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun FractionCell(label: String, selected: Boolean, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill = if (selected) Color(0xFF5E636C.toInt()) else Color(StagePalette.bg)
    val ink = if (selected) Color.White else Color(StagePalette.accentBlue)
    Box(modifier.height(if (compact) 18.dp else 22.dp).background(fill).border(1.dp, Color(if (selected) 0xFF5E636C.toInt() else StagePalette.line)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        TextInk(label, color = ink, fontSize = if (compact) 8.sp else 10.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Lamp(label: String, on: Boolean, compact: Boolean) {
    TextInk(label, color = if (on) Color(StagePalette.accentBlue) else Color(StagePalette.inkMuted), fontSize = if (compact) 8.sp else 10.sp, weight = FontWeight.Bold, maxLines = 1)
}

private fun analysisOf(catalog: RbCatalog?, track: RbTrack?): RbAnalysis? = track?.let { catalog?.analyses?.get(it.id) }

private fun remainClock(track: RbTrack?, face: DeckFace): String {
    if (track == null) return "--:--"
    val left = (track.durationSeconds - face.position).toInt().coerceAtLeast(0)
    return "%d:%02d".format(left / 60, left % 60)
}

private fun splitClock(seconds: Double): Pair<String, String> {
    val millis = (seconds.coerceAtLeast(0.0) * 1000.0).roundToInt()
    val whole = millis / 1000
    return "%02d:%02d".format(whole / 60, whole % 60) to ".%03d".format(millis % 1000)
}

private fun tempoText(percent: Float): String {
    val sign = if (percent >= 0f) "+" else ""
    return "$sign${"%.1f".format(percent)}%"
}

private fun rangeBadge(range: Double): String = when {
    abs(range - 0.06) < 1e-6 -> "6%"
    abs(range - 0.10) < 1e-6 -> "10%"
    abs(range - 0.16) < 1e-6 -> "16%"
    abs(range - 0.25) < 1e-6 -> "WIDE"
    else -> "${(range * 100).roundToInt()}%"
}

private fun quantizeMark(beats: Double): String = when (beats) {
    0.25 -> "1/16"
    0.5 -> "1/8"
    2.0 -> "1/2"
    4.0 -> "1"
    else -> "1/4"
}

private fun bpmParts(bpm: Double): Pair<String, String> {
    if (bpm <= 1.0) return "---" to ""
    val tenths = (bpm * 10.0).roundToInt()
    return (tenths / 10).toString() to ".${tenths % 10}"
}

private fun bigFractionSelected(fx: BeatFxState, label: String): Boolean = when (label) {
    "3/4" -> fx.fractionLabel() == "3/4"
    "1" -> fx.fractionLabel() == "1/1"
    "2" -> fx.fractionLabel() == "2/1"
    else -> false
}

private fun xpadSelected(fx: BeatFxState, label: String): Boolean = when (label) {
    "1" -> fx.fractionLabel() == "1/1"
    else -> fx.fractionLabel() == label
}

private fun fractionIndex(label: String): Int {
    val key = when (label) {
        "1" -> "1/1"
        "2" -> "2/1"
        else -> label
    }
    val index = BEAT_FX_FRACTIONS.indexOf(key)
    return if (index < 0) 5 else index
}
