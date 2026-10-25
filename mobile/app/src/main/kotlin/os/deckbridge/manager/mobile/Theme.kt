package os.deckbridge.manager.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val bg = Color(0xFF0B1020)
    val surface = Color(0xFF141B2E)
    val surfaceHigh = Color(0xFF1C2540)
    val line = Color(0xFF2A3556)
    val text = Color(0xFFF1F4FA)
    val muted = Color(0xFF9AA7C2)
    val accent = Color(0xFF3DDC97)
    val accent2 = Color(0xFF2EC5FF)
    val warn = Color(0xFFFFB454)
    val danger = Color(0xFFFF6B6B)

    /** Cores de faixa do rekordbox, na ordem dos ids 1–8. */
    val trackColors = listOf(
        Color(0xFFE91E8C), Color(0xFFEF4444), Color(0xFFF97316), Color(0xFFEAB308),
        Color(0xFF22C55E), Color(0xFF06B6D4), Color(0xFF3B82F6), Color(0xFFA855F7),
    )
    val trackColorNames = listOf("Rosa", "Vermelho", "Laranja", "Amarelo", "Verde", "Água", "Azul", "Roxo")
}

@Composable
fun ManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent, onPrimary = Palette.bg, secondary = Palette.accent2, background = Palette.bg, onBackground = Palette.text,
            surface = Palette.surface, onSurface = Palette.text, surfaceVariant = Palette.surfaceHigh, onSurfaceVariant = Palette.muted,
            outline = Palette.line, error = Palette.danger,
        ),
        content = content,
    )
}
