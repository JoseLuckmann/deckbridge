package os.deckbridge.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.StagePalette

/**
 * As abas do app, todas do mesmo jeito: texto com um traço azul embaixo na aba aberta, sem caixa de botão — para não se
 * confundirem com os botões e os seletores (efeito, canal, filtro), que têm caixa. Um só componente para a faixa aberta, o
 * MIX, o mixer do modo DJ e a barra de baixo; [marker] deixa quem precisa pôr um modificador no texto de uma aba (o
 * destaque do guia).
 */
@Composable
fun UnderlineTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    fontSize: TextUnit = 13.sp,
    marker: (Int) -> Modifier = { Modifier },
    /** O fio em volta (some quando a aba já está dentro de uma barra com o seu). */
    framed: Boolean = true,
) {
    Row(if (framed) modifier.fillMaxWidth().border(BorderStroke(0.5.dp, Color(StagePalette.line))) else modifier) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(height).clickable { onSelect(i) }.semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = Color(if (on) StagePalette.accentBlue else StagePalette.inkMuted), fontSize = fontSize,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1, modifier = marker(i),
                )
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.7f).height(3.dp).background(Color(if (on) StagePalette.accentBlue else StagePalette.bg)))
            }
        }
    }
}
