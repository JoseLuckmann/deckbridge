package os.deckbridge.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    rows: List<HistoryRow>,
    onClose: () -> Unit,
    onCsv: () -> Unit,
    onM3u: () -> Unit,
) {
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    Column(Modifier.fillMaxSize().background(Color(StagePalette.bg)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInk("HISTÓRICO", fontSize = 20.sp, weight = FontWeight.Bold, color = Color(StagePalette.accentBlue), modifier = Modifier.weight(1f))
            UtilityButton("CSV", onCsv, modifier = Modifier.padding(end = 6.dp))
            UtilityButton("M3U8", onM3u, modifier = Modifier.padding(end = 6.dp))
            UtilityButton("FECHAR", onClose)
        }
        TextInk("Sessão neste aparelho. A exportação pede uma pasta. O USB do rekordbox não é gravado.", fontSize = 12.sp, color = Color(StagePalette.inkMuted), modifier = Modifier.padding(top = 6.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (rows.isEmpty()) {
                TextInk("Nenhuma faixa no ar ainda.", fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp))
            }
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().border(1.dp, Color(StagePalette.line)).background(Color(StagePalette.panel)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextInk("D${row.deck + 1}", color = Color(StagePalette.accentBlue), fontSize = 13.sp, weight = FontWeight.Bold, modifier = Modifier.widthPad())
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        TextInk(row.title, fontSize = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
                        TextInk(row.artist, fontSize = 12.sp, color = Color(StagePalette.inkMuted), maxLines = 1)
                    }
                    TextInk("%.1f".format(row.bpm), fontSize = 14.sp, mono = true)
                    TextInk(row.keyName.ifBlank { "—" }, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                    TextInk(clock.format(Date(row.atMillis)), fontSize = 13.sp, mono = true, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

private fun Modifier.widthPad(): Modifier = this.padding(end = 4.dp)
