package os.deckbridge.manager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import os.deckbridge.manager.ManagerModel
import os.deckbridge.manager.anlz.Beat
import os.deckbridge.manager.anlz.Cue
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackItem

/** `m:ss.mmm` → ms. Aceita `ss.mmm`, `m:ss` e milissegundos soltos com sufixo `ms`. Null se não entender. */
fun parseMs(text: String): Int? {
    val t = text.trim().lowercase()
    if (t.isEmpty()) return null
    if (t.endsWith("ms")) return t.removeSuffix("ms").trim().toIntOrNull()?.takeIf { it >= 0 }
    val parts = t.split(":")
    if (parts.size > 2) return null
    val minutes = if (parts.size == 2) parts[0].toIntOrNull() ?: return null else 0
    val seconds = parts.last().replace(',', '.').toDoubleOrNull() ?: return null
    if (minutes < 0 || seconds < 0 || (parts.size == 2 && seconds >= 60)) return null
    return ((minutes * 60 + seconds) * 1000).roundToInt()
}

fun formatMs(ms: Int): String = "%d:%02d.%03d".format(ms / 60000, ms / 1000 % 60, ms % 1000)

private val hotColors = listOf(0xFF3B3B, 0x2EC5FF, 0x3DDC6A, 0xB44DFF, 0x3DDC6A, 0xFF8A1E, 0x3B6CFF, 0xFFE03B)
private val palette = listOf(0xFF3B3B, 0xFF8A1E, 0xFFE03B, 0x3DDC6A, 0x2EC5FF, 0x3B6CFF, 0xB44DFF, 0xFF4DB8)

fun hotLetter(hot: Int) = if (hot in 1..8) ('A' + hot - 1).toString() else "M"

private class CueDraft(var hot: Int, var time: String, var loop: String, var comment: String, var rgb: Int?)

private fun CueDraft.toCue(): Pair<Cue?, String?> {
    val t = parseMs(time) ?: return null to "tempo inválido \"$time\""
    val l = if (loop.isBlank()) null else (parseMs(loop) ?: return null to "fim do loop inválido \"$loop\"")
    if (l != null && l <= t) return null to "o fim do loop tem de vir depois do começo"
    return Cue(hot, t, l, comment.trim(), if (hot in 1..8) (rgb ?: hotColors[hot - 1]) else null) to null
}

@Composable
fun CueEditorModal(model: ManagerModel, track: TrackItem, onClose: () -> Unit) {
    val files = model.trackFiles(track)
    val key = listOf(track.id, files.cues, files.beats.size, files.beats.firstOrNull())
    var drafts by remember(key) { mutableStateOf(files.cues.map { CueDraft(it.hot, formatMs(it.timeMs), it.loopEndMs?.let(::formatMs).orEmpty(), it.comment, it.rgb) }) }
    var version by remember(key) { mutableStateOf(0) }
    val first = files.beats.firstOrNull()
    var bpmText by remember(key) { mutableStateOf(first?.let { "%.2f".format(it.tempo100 / 100.0) } ?: "%.2f".format(track.bpm)) }
    var firstText by remember(key) { mutableStateOf((first?.timeMs ?: 0).toString()) }

    Modal("Cues e grade — ${track.title}", 1040.dp, onClose) {
        if (files.error != null) {
            Label(files.error, Pal.Red, 13.sp, maxLines = 3)
            Btn("Fechar", onClose)
            return@Modal
        }
        Wave(files.wave, files.beats, drafts.mapNotNull { it.toCue().first }, files.durationMs)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.width(110.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { Caption("BPM"); TextBox(bpmText, { bpmText = it }, Modifier.fillMaxWidth()) }
            Column(Modifier.width(130.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { Caption("1ª batida (ms)"); TextBox(firstText, { firstText = it }, Modifier.fillMaxWidth()) }
            val bpm = bpmText.replace(',', '.').toDoubleOrNull()
            Btn("÷ 2", { bpm?.let { bpmText = "%.2f".format(it / 2) } }, enabled = bpm != null)
            Btn("× 2", { bpm?.let { bpmText = "%.2f".format(it * 2) } }, enabled = bpm != null)
            Btn("−10 ms", { firstText = ((firstText.toIntOrNull() ?: 0) - 10).coerceAtLeast(0).toString() })
            Btn("+10 ms", { firstText = ((firstText.toIntOrNull() ?: 0) + 10).toString() })
            val ok = bpm != null && bpm in 20.0..400.0 && (firstText.toIntOrNull() ?: -1) >= 0
            val same = first != null && bpm != null && (bpm * 100).roundToInt() == first.tempo100 && firstText.toIntOrNull() == first.timeMs
            Btn("Guardar grade na fila", {
                model.submit(Edit.SetGrid(track.id, bpm!!, firstText.toInt()), "\"${track.title}\": grade a ${"%.2f".format(bpm)} BPM, 1ª batida em ${firstText} ms")
            }, primary = true, enabled = ok && !same)
            Label(if (first == null) "sem grade" else "${files.beats.size} batidas", Pal.Muted, 12.sp)
        }
        Caption("Cues")
        Column(Modifier.fillMaxWidth().height(210.dp).background(Pal.Bg).border(1.dp, Pal.Line).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (drafts.isEmpty()) Label("Nenhum cue. Use os botões abaixo para criar.", Pal.Muted, 12.sp)
            drafts.forEachIndexed { n, d ->
                key(version, n) {
                    var hot by remember { mutableStateOf(d.hot) }
                    var time by remember { mutableStateOf(d.time) }
                    var loop by remember { mutableStateOf(d.loop) }
                    var comment by remember { mutableStateOf(d.comment) }
                    var rgb by remember { mutableStateOf(d.rgb) }
                    d.hot = hot; d.time = time; d.loop = loop; d.comment = comment; d.rgb = rgb
                    val err = d.toCue().second
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val color = if (hot in 1..8) Color(0xFF000000 or (rgb ?: hotColors[hot - 1]).toLong()) else Pal.Muted
                        Box(Modifier.width(44.dp).height(28.dp).background(color.copy(alpha = 0.25f)).border(1.dp, color).clickable { hot = (hot + 1) % 9 }, contentAlignment = Alignment.Center) { Label(hotLetter(hot), color, 14.sp, bold = true) }
                        TextBox(time, { time = it }, Modifier.width(110.dp), placeholder = "m:ss.mmm")
                        TextBox(loop, { loop = it }, Modifier.width(110.dp), placeholder = "fim do loop")
                        TextBox(comment, { comment = it }, Modifier.weight(1f), placeholder = "nome")
                        if (hot in 1..8) Box(Modifier.size(24.dp).background(Color(0xFF000000 or (rgb ?: hotColors[hot - 1]).toLong())).border(1.dp, Pal.Line).clickable {
                            val cur = rgb ?: hotColors[hot - 1]
                            rgb = palette[(palette.indexOf(cur) + 1).mod(palette.size)]
                        })
                        Label("✕", Pal.Red, 16.sp, modifier = Modifier.clickable { drafts = drafts.filterIndexed { i, _ -> i != n }; version++ }.padding(horizontal = 6.dp))
                        if (err != null) Label(err, Pal.Red, 11.sp)
                    }
                }
            }
        }
        val parsed = drafts.map { it.toCue() }
        val cues = parsed.mapNotNull { it.first }
        val problem = parsed.firstNotNullOfOrNull { it.second } ?: run {
            val hots = cues.filter { it.isHot }.map { it.hot }
            if (hots.size != hots.distinct().size) "cada hot cue (A–H) só pode aparecer uma vez" else null
        }
        val unchanged = cues.sortedBy { it.hot * 100000000L + it.timeMs } == files.cues.sortedBy { it.hot * 100000000L + it.timeMs }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val freeHot = (1..8).firstOrNull { h -> drafts.none { it.hot == h } }
            Btn("+ Hot cue", { drafts = drafts + CueDraft(freeHot ?: 1, formatMs(0), "", "", null); version++ }, enabled = freeHot != null)
            Btn("+ Memória", { drafts = drafts + CueDraft(0, formatMs(0), "", "", null); version++ })
            Btn("+ Loop", { drafts = drafts + CueDraft(0, formatMs(0), formatMs(2000), "Loop", null); version++ })
            Btn("Guardar cues na fila", {
                model.submit(Edit.SetCues(track.id, cues), "\"${track.title}\": ${cues.size} cue${if (cues.size == 1) "" else "s"}")
            }, primary = true, enabled = problem == null && !unchanged)
            Btn("Reverter", { drafts = files.cues.map { CueDraft(it.hot, formatMs(it.timeMs), it.loopEndMs?.let(::formatMs).orEmpty(), it.comment, it.rgb) }; version++ }, enabled = !unchanged)
            Label(problem ?: "D–H só vão no .EXT; o .DAT guarda A–C.", if (problem != null) Pal.Red else Pal.Muted, 12.sp)
            Box(Modifier.weight(1f))
            Btn("Fechar", onClose)
        }
    }
}

/** Prévia da onda com a grade (compasso mais forte) e os cues. [wave] são as alturas 0–255 da prévia do ANLZ. */
@Composable
private fun Wave(wave: ByteArray, beats: List<Beat>, cues: List<Cue>, durationMs: Int) {
    Canvas(Modifier.fillMaxWidth().height(96.dp).background(Pal.Bg).border(1.dp, Pal.Line)) {
        val w = size.width
        val h = size.height
        val total = (durationMs.coerceAtLeast(1)).toFloat()
        if (wave.isNotEmpty()) {
            val columns = wave.size
            val step = w / columns
            for (c in 0 until columns) {
                val v = (wave[c].toInt() and 0xFF) / 255f
                val bar = (v * (h - 22f)).coerceAtLeast(1f)
                drawRect(Color(0xFF2E6BCC), Offset(c * step, h / 2 + 4 - bar / 2), Size(step.coerceAtLeast(1f), bar))
            }
        }
        for (b in beats) {
            val x = b.timeMs / total * w
            if (x > w) break
            val bar = b.inBar == 1
            drawRect(if (bar) Color(0xCCFF8A1E) else Color(0x33FFFFFF), Offset(x, if (bar) 0f else h * 0.78f), Size(if (bar) 1.4f else 1f, if (bar) h else h * 0.22f))
        }
        for (c in cues) {
            val x = c.timeMs / total * w
            val color = if (c.isHot) Color(0xFF000000 or (c.rgb ?: hotColors[c.hot - 1]).toLong()) else Color(0xFFDC2828)
            c.loopEndMs?.let { end -> drawRect(Color(0x553DDC6A), Offset(x, 0f), Size(((end - c.timeMs) / total * w).coerceAtLeast(2f), h)) }
            drawRect(color, Offset(x, 0f), Size(2f, h))
            drawRect(color, Offset(x, 0f), Size(14f, 14f))
        }
    }
}
