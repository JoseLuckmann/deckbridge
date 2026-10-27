package os.deckbridge.manager.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import os.deckbridge.manager.smart.Keep

/**
 * Onde a faixa já está: os sets em que entrou (um toque abre o set) e quando chegou na biblioteca. Responde "onde eu
 * usei essa?" sem ter de lembrar nem procurar set por set.
 */
@Composable
fun TrackPlaces(app: StudioHost, trackId: Int) {
    val v = app.model.view ?: return
    val t = v.track(trackId) ?: return
    val sets = remember(v, trackId) { Keep.places(v, trackId, app.sets.knownSets()).filter { it.set } }
    val added = Keep.added(t)?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NOS SETS", color = Palette.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Text(
                "  " + listOfNotNull(if (sets.isEmpty()) "em nenhum ainda" else null, added?.let { "entrou em $it" }).joinToString(" · "),
                color = Palette.muted, fontSize = 12.sp,
            )
        }
        if (sets.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sets.forEach { p -> AssistChip(onClick = { app.sets.open(p.playlistId) }, label = { Text("SET ${p.name} ›", maxLines = 1) }) }
        }
    }
}
