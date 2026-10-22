package os.deckbridge.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.HistoryRow
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.camelotOf
import os.deckbridge.logic.sessionSpanSeconds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

@Composable
fun HistoryScreen(
    rows: List<HistoryRow>,
    onClose: () -> Unit,
    onCsv: () -> Unit,
    onM3u: () -> Unit,
) {
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val day = SimpleDateFormat("dd MMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val span = sessionSpanSeconds(rows)
    val whenLabel = rows.minOfOrNull { it.atMillis }?.let { day.format(Date(it)) } ?: "—"
    Column(Modifier.fillMaxSize().background(Color(StagePalette.bg)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("HISTÓRICO", fontSize = 22.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentBlue), modifier = Modifier.weight(1f))
            UtilityButton("CSV", onCsv, modifier = Modifier.padding(end = 8.dp))
            UtilityButton("M3U8", onM3u, modifier = Modifier.padding(end = 8.dp))
            UtilityButton("FECHAR", onClose)
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 10.dp).background(Color(StagePalette.panel)).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            TextInk("SESSÃO  $whenLabel", fontSize = 16.sp, weight = FontWeight.Bold)
            TextInk(
                "${rows.size} faixas   ·   %02d:%02d:%02d entre a primeira e a última".format(span / 3600, (span % 3600) / 60, span % 60),
                fontSize = 14.sp,
                color = Color(StagePalette.inkMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
            TextInk("A exportação pede uma pasta. O USB do rekordbox não é gravado.", fontSize = 12.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 2.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).background(Color(0xFF1A3A68)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderCell("HORA", Modifier.width(108.dp))
            HeaderCell("DECK", Modifier.width(64.dp))
            HeaderCell("FAIXA", Modifier.weight(1f))
            HeaderCell("BPM", Modifier.width(120.dp))
            HeaderCell("TOM", Modifier.width(88.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (rows.isEmpty()) {
                TextInk("Nenhuma faixa no ar ainda.", fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp))
            }
            rows.forEachIndexed { index, row ->
                val shade = if (index % 2 == 0) Color(StagePalette.panel) else Color(StagePalette.panelRaised)
                val camelot = camelotOf(row.keyName)
                val showCamelot = camelot != null && !camelot.equals(row.keyName, ignoreCase = true)
                Row(
                    Modifier.fillMaxWidth().background(shade).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextInk(clock.format(Date(row.atMillis)), fontSize = 15.sp, mono = true, modifier = Modifier.width(108.dp))
                    TextInk("D${row.deck + 1}", color = Color(StagePalette.accentBlue), fontSize = 16.sp, weight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        TextInk(row.title, fontSize = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
                        TextInk(row.artist, fontSize = 13.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
                    }
                    Column(Modifier.width(120.dp)) {
                        TextInk("%.1f".format(row.bpm), fontSize = 16.sp, mono = true, weight = FontWeight.Bold)
                        TextInk(
                            "no ar %.1f".format(row.playedBpm),
                            fontSize = 12.sp,
                            mono = true,
                            color = Color(if (abs(row.playedBpm - row.bpm) > 0.05) StagePalette.accentOrange else StagePalette.inkMuted),
                        )
                    }
                    Column(Modifier.width(88.dp)) {
                        TextInk(row.keyName.ifBlank { "—" }, fontSize = 16.sp, weight = FontWeight.Bold)
                        if (showCamelot) {
                            TextInk(camelot.orEmpty(), fontSize = 12.sp, color = Color(StagePalette.inkMuted))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(label: String, modifier: Modifier) {
    TextInk(label, modifier = modifier, fontSize = 12.sp, weight = FontWeight.Bold, color = Color.White)
}
