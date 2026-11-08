package os.deckbridge.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.deckbridge.logic.StagePalette

/**
 * O botão do MENU, discreto: só "⋯" em cinza, estreito, no fim da barra. No MENU fica o que não se usa sempre
 * (ajustes, ajuda, versão), então ele não disputa espaço nem atenção com o que se usa a toda hora. O mesmo nos dois
 * modos (Biblioteca e DJ); aceso em azul quando o MENU está aberto. A área de toque segue com 48 dp de altura na barra.
 */
@Composable
fun MenuButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 44.dp) {
    Box(
        modifier.width(width).fillMaxHeight().clickable(onClick = onClick).semantics { contentDescription = "Menu" },
        contentAlignment = Alignment.Center,
    ) {
        Text("⋯", color = Color(if (on) StagePalette.accentBlue else StagePalette.mutedInk), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
