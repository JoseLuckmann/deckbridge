package os.deckbridge.manager.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import os.deckbridge.manager.ManagerModel
import os.deckbridge.manager.pdb.TrackItem

fun formatDuration(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

/** A onda do player em qualquer tamanho. [marks] desenha grade e cues; [pos] negativo esconde a cabeça. */
@Composable
fun ColorWaveView(files: ManagerModel.TrackFilesView, durationMs: Int, pos: Int, height: androidx.compose.ui.unit.Dp, marks: Boolean, drag: Boolean = false, onPos: (Int) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(height).background(Palette.waveBg)
            .pointerInput(durationMs) { detectTapGestures { o -> onPos((o.x / size.width * durationMs).toInt().coerceIn(0, durationMs)) } }
            .let { if (drag) it.pointerInput(durationMs) { detectDragGestures { change, _ -> onPos((change.position.x / size.width * durationMs).toInt().coerceIn(0, durationMs)) } } else it },
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2
        val heights = files.colorHeights
        val colors = files.colors
        if (heights.isNotEmpty() && colors.size == heights.size) {
            val peak = heights.maxOf { it.toInt() and 0xFF }.coerceAtLeast(1)
            val col = w / heights.size
            for (c in heights.indices) {
                val half = ((heights[c].toInt() and 0xFF).toFloat() / peak * mid * 0.95f).coerceAtLeast(0.5f)
                drawRect(rgb(colors[c]), Offset(c * col, mid - half), Size(col + 0.6f, half * 2))
            }
        } else if (files.wave.isNotEmpty()) {
            val col = w / files.wave.size
            for (c in files.wave.indices) {
                val half = ((files.wave[c].toInt() and 0xFF) / 255f * mid * 0.95f).coerceAtLeast(0.5f)
                drawRect(Palette.wave, Offset(c * col, mid - half), Size(col + 0.6f, half * 2))
            }
        } else {
            drawLine(Palette.line, Offset(0f, mid), Offset(w, mid), 2f)
        }
        // Grade só como marcas curtas em cima e embaixo, para não cobrir a onda.
        if (marks) for (b in files.beats) {
            val x = b.timeMs.toFloat() / durationMs * w
            val bar = b.inBar == 1
            val len = if (bar) h * 0.16f else h * 0.07f
            val color = if (bar) Palette.danger else Palette.text.copy(alpha = 0.45f)
            drawLine(color, Offset(x, 0f), Offset(x, len), if (bar) 2f else 1f)
            drawLine(color, Offset(x, h - len), Offset(x, h), if (bar) 2f else 1f)
        }
        if (marks) for (c in files.cues) {
            val x = c.timeMs.toFloat() / durationMs * w
            val color = if (c.isHot) rgb(c.rgb ?: padRgb(c.hot)) else Palette.orange
            drawLine(color, Offset(x, 0f), Offset(x, h), 2f)
            drawRect(color, Offset(x - 6f, 0f), Size(12f, 12f))
        }
        if (pos >= 0) {
            val px = pos.toFloat() / durationMs * w
            drawLine(Palette.text, Offset(px, 0f), Offset(px, h), 3f)
        }
    }
}

/**
 * Onda pequena que toca: tocar num ponto ouve dali; enquanto toca, a cabeça anda. Usada na aba MARCAR e na triagem,
 * para decidir sem abrir os cues.
 */
@Composable
fun PlayableWave(app: StudioHost, t: TrackItem, height: androidx.compose.ui.unit.Dp = 56.dp) {
    val m = app.model
    val files = remember(t.id, t.analyzePath, m.pending.size) { m.trackFiles(t) }
    if (files.error != null) return
    val duration = files.durationMs.coerceAtLeast(1)
    var head by remember(t.id) { mutableIntStateOf(-1) }
    val playing = app.preview.playingId == t.id
    androidx.compose.runtime.LaunchedEffect(playing) {
        while (playing && app.preview.playingId == t.id) {
            head = app.preview.positionMs()
            kotlinx.coroutines.delay(100)
        }
        head = -1
    }
    ColorWaveView(files, duration, if (playing) head else -1, height, marks = false) { ms -> app.preview.toggle(t, fromMs = ms.coerceAtLeast(1)) }
}

