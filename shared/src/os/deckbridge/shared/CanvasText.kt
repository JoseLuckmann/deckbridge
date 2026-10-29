package os.deckbridge.shared

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText

/**
 * Texto dentro de um Canvas (nomes de frase, números de compasso, letras de cue, "Bars") desenhado só se cabe inteiro na
 * área. Uma função para as telas do modo DJ (stage/) e do MIX (studio/): o `drawText` do Compose lança exceção quando
 * sobra largura negativa (texto começando depois da borda), e foi isso que derrubou o app no S25 Ultra (0.29.0).
 */
fun DrawScope.drawTextFitting(
    laid: TextLayoutResult,
    topLeft: Offset,
    /** Um fundo atrás do texto (para ler sobre a onda), com [pad] de folga. */
    background: Color? = null,
    pad: Offset = Offset(2f, 1f),
): Boolean {
    val w = laid.size.width
    val h = laid.size.height
    if (topLeft.x < 0f || topLeft.y < 0f || topLeft.x + w > size.width || topLeft.y + h > size.height) return false
    if (background != null) drawRect(background, Offset(topLeft.x - pad.x, topLeft.y - pad.y), Size(w + 2 * pad.x, h + 2 * pad.y))
    drawText(laid, topLeft = topLeft)
    return true
}

/** O mesmo, medindo [text] com [style]. */
fun DrawScope.drawTextFitting(measurer: TextMeasurer, text: String, style: TextStyle, topLeft: Offset, background: Color? = null, pad: Offset = Offset(2f, 1f)): Boolean =
    drawTextFitting(measurer.measure(text, style), topLeft, background, pad)
