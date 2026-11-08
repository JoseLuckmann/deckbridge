package os.deckbridge.manager.ui

import os.deckbridge.manager.studio.typingField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.StagePalette

/** Cores do design system do player (`StagePalette`, compartilhado com o app e o Pi): nenhuma cor própria. */
object Pal {
    val Bg = Color(StagePalette.bg)
    val Panel = Color(StagePalette.panel)
    val Raised = Color(StagePalette.panelRaised)
    val Line = Color(StagePalette.line)
    val Ink = Color(StagePalette.ink)
    val Muted = Color(StagePalette.mutedInk)
    val MutedBright = Color(StagePalette.inkMuted)
    val Orange = Color(StagePalette.accentOrange)
    val Blue = Color(StagePalette.accentBlue)
    val Red = Color(StagePalette.danger)
    val Green = Color(StagePalette.keyInk)
    val ActiveFill = Color(StagePalette.activeFill)
    val SelectedRow = Color(StagePalette.selectedRow)
    val KeyFill = Color(StagePalette.keyFill)
}

@Composable
fun Label(text: String, color: Color = Pal.Ink, size: TextUnit = 13.sp, bold: Boolean = false, modifier: Modifier = Modifier, maxLines: Int = 1) {
    BasicText(
        text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) = Label(text.uppercase(), Pal.Muted, 11.sp, bold = true, modifier = modifier)

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true) {
    // Botão principal = o "ativo" do player: azul-escuro com contorno azul e texto branco.
    val fill = if (primary) Pal.ActiveFill else Pal.Raised
    val ink = when {
        primary -> Pal.Ink
        danger -> Pal.Red
        else -> Pal.Blue
    }
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.35f)
            .height(30.dp)
            .background(fill)
            .border(1.dp, if (primary) Pal.Blue else Pal.Line)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, ink, 12.sp, bold = true) }
}

@Composable
fun TextBox(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "", multiline: Boolean = false) {
    Box(
        modifier
            .background(Pal.Bg)
            .border(1.dp, Pal.Line)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = !multiline,
            textStyle = TextStyle(color = Pal.Ink, fontSize = 13.sp),
            cursorBrush = SolidColor(Pal.Orange),
            // Com o foco aqui, as teclas são texto (os atalhos de uma tecla ficam desligados).
            modifier = Modifier.fillMaxWidth().typingField(),
        )
        if (value.isEmpty() && placeholder.isNotEmpty()) Label(placeholder, Pal.Muted, 13.sp)
    }
}

@Composable
fun Stars(rating: Int, onChange: ((Int) -> Unit)?, size: TextUnit = 14.sp) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (n in 1..5) {
            val filled = n <= rating
            Box(if (onChange != null) Modifier.clickable { onChange(if (rating == n) 0 else n) } else Modifier) {
                Label(if (filled) "★" else "☆", if (filled) Pal.Orange else Pal.Muted, size)
            }
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(Pal.Panel).border(1.dp, Pal.Line)) { content() }
}

/** Painel modal desenhado na própria tela (aparece também nas capturas offscreen, ao contrário de uma janela do sistema). */
@Composable
fun Modal(title: String, width: Dp, onClose: (() -> Unit)?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.width(width).background(Pal.Panel).border(1.dp, Pal.Line).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Label(title, Pal.Ink, 16.sp, bold = true)
                if (onClose != null) Label("✕", Pal.Muted, 16.sp, modifier = Modifier.clickable(onClick = onClose))
            }
            content()
        }
    }
}
