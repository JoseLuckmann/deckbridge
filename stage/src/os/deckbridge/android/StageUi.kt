package os.deckbridge.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.StagePalette
import os.deckbridge.logic.inkOn

fun stageScheme() = darkColorScheme(
    primary = Color(StagePalette.navSelected),
    onPrimary = Color(StagePalette.navSelectedInk),
    secondary = Color(StagePalette.deckA),
    onSecondary = Color(StagePalette.bg),
    background = Color(StagePalette.bg),
    onBackground = Color(StagePalette.ink),
    surface = Color(StagePalette.panel),
    onSurface = Color(StagePalette.ink),
    surfaceVariant = Color(StagePalette.panelRaised),
    onSurfaceVariant = Color(StagePalette.ink),
    outline = Color(StagePalette.line),
)

fun simulationNote(jvmBanner: String?, simulated: Boolean): String? = when {
    !jvmBanner.isNullOrBlank() -> "SIMULAÇÃO · $jvmBanner"
    simulated -> "SIMULAÇÃO"
    else -> null
}

fun argb(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

@Composable
fun StageScaffold(
    page: Int,
    onPage: (Int) -> Unit,
    simulationNote: String?,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = stageScheme()) {
        Surface(
            color = Color(StagePalette.bg),
            contentColor = Color(StagePalette.ink),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StageNav("Diagnóstico", page == 0, Modifier.weight(1f)) { onPage(0) }
                    StageNav("Biblioteca", page == 1, Modifier.weight(1f)) { onPage(1) }
                    StageNav("Decks", page == 2, Modifier.weight(1f)) { onPage(2) }
                }
                if (simulationNote != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(StagePalette.simBg))
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        TextInk(
                            simulationNote,
                            color = Color(StagePalette.simInk),
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

@Composable
private fun StageNav(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill = if (selected) Color(StagePalette.navSelected) else Color(StagePalette.panelRaised)
    val ink = if (selected) Color(StagePalette.navSelectedInk) else Color(StagePalette.ink)
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .border(1.dp, Color(StagePalette.line), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = ink, fontSize = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun StageButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    fill: Color = Color(StagePalette.panelRaised),
    ink: Color = Color(StagePalette.ink),
    fontSize: TextUnit = 14.sp,
) {
    val background = if (selected) Color(StagePalette.navSelected) else fill
    val foreground = if (selected) Color(StagePalette.navSelectedInk) else ink
    Box(
        modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, Color(StagePalette.line), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextInk(label, color = foreground, fontSize = fontSize, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun TextInk(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color(StagePalette.ink),
    fontSize: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = weight,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun VerticalFader(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TextInk(label, fontSize = 13.sp, weight = FontWeight.Bold)
        TextInk("${(value.coerceIn(0f, 1f) * 100).toInt()}", fontSize = 16.sp, mono = true, weight = FontWeight.Bold)
        Box(
            Modifier
                .padding(top = 6.dp)
                .width(28.dp)
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(StagePalette.waveBg))
                .border(1.dp, Color(StagePalette.line), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onChange((1f - offset.y / size.height).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        onChange((1f - change.position.y / size.height).coerceIn(0f, 1f))
                    }
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(value.coerceIn(0.04f, 1f))
                    .background(accent),
            )
        }
    }
}
