package os.deckbridge.manager.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Números de BPM e tempo em fonte monoespaçada, como no player. */
val Mono = FontFamily.Monospace

private val defaultPad = PaddingValues(horizontal = 14.dp)

@Composable
private fun RbBox(
    onClick: () -> Unit, modifier: Modifier, enabled: Boolean, fill: Color, border: Color?, ink: Color, pad: PaddingValues, minHeight: Int, content: @Composable RowScope.() -> Unit,
) {
    val shown = if (enabled) ink else Palette.muted.copy(alpha = 0.6f)
    Row(
        modifier.heightIn(min = minHeight.dp).clip(RbShape).background(if (enabled) fill else Palette.surface)
            .let { if (border != null) it.border(BorderStroke(1.dp, if (enabled) border else Palette.line), RbShape) else it }
            .clickable(enabled = enabled, onClick = onClick).padding(pad),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides shown) {
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)) { content() }
        }
    }
}

/** Botão principal: preenchimento azul-escuro com contorno azul e texto branco, como o botão ativo do player. */
@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = defaultPad, content: @Composable RowScope.() -> Unit) =
    RbBox(onClick, modifier, enabled, Palette.blueActiveFill, Palette.blue, Palette.text, contentPadding, 40, content)

/** Botão comum: painel elevado escuro, contorno de linha e texto azul-claro, como os botões SOURCE/BROWSE do player. */
@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = defaultPad, content: @Composable RowScope.() -> Unit) =
    RbBox(onClick, modifier, enabled, Palette.surfaceHigh, Palette.line, Palette.blue, contentPadding, 40, content)

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp), content: @Composable RowScope.() -> Unit) =
    RbBox(onClick, modifier, enabled, Color.Transparent, null, Palette.blue, contentPadding, 40, content)

/** Botão pequeno de filtro. Selecionado = ativo (azul-escuro com contorno azul). */
@Composable
fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) =
    RbBox(onClick, modifier, true, if (selected) Palette.blueActiveFill else Palette.surfaceHigh, if (selected) Palette.blue else Palette.line, if (selected) Palette.text else Palette.blue, PaddingValues(horizontal = 12.dp), 34) { label() }

@Composable
fun AssistChip(onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) = FilterChip(false, onClick, label, modifier)

/** Painel de seção: fundo de painel e linha fina, sem cantos arredondados. */
fun Modifier.rbPanel(): Modifier = this.clip(RbShape).background(Palette.surface).border(BorderStroke(1.dp, Palette.line), RbShape)

fun Modifier.rbPad(h: Int, v: Int): Modifier = this.padding(horizontal = h.dp, vertical = v.dp)
