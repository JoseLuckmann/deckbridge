package os.deckbridge.manager.mobile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.toArgb
import os.deckbridge.logic.StagePalette

/**
 * O design system de referência é o do player (`StagePalette`, o espírito do rekordbox/CDJ). Estes testes impedem que o
 * app de biblioteca volte a ter paleta, formas ou componentes próprios.
 */
class DesignSystemTest {
    private val sources = File("src/main/kotlin/os/deckbridge/manager/mobile").listFiles { f -> f.extension == "kt" }!!.toList()

    @Test fun `nenhuma cor escrita a mao fora do tema`() {
        val offenders = sources.filter { it.name != "Theme.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { n, l -> if (Regex("Color\\(0x|Color\\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta)").containsMatchIn(l)) "${f.name}:${n + 1}: ${l.trim()}" else null }
        }
        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }

    @Test fun `as cores vem do StagePalette`() {
        assertEquals(StagePalette.bg, Palette.bg.toArgb())
        assertEquals(StagePalette.accentBlue, Palette.blue.toArgb())
        assertEquals(StagePalette.accentOrange, Palette.orange.toArgb())
        assertEquals(StagePalette.selectedRow, Palette.selectedRow.toArgb())
        assertEquals(StagePalette.pads.toList(), Palette.pads.map { it.toArgb() })
    }

    @Test fun `sem botoes ou paineis arredondados do Material`() {
        val offenders = sources.filter { it.name != "Components.kt" && it.name != "Theme.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { n, l -> if (Regex("RoundedCornerShape\\(|CircleShape|material3\\.(Button|OutlinedButton|TextButton|FilterChip|AssistChip|Card|ElevatedButton)\\b").containsMatchIn(l)) "${f.name}:${n + 1}: ${l.trim()}" else null }
        }
        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }

    @Test fun `fundo preto e texto claro com contraste suficiente`() {
        fun lum(c: Int): Double = os.deckbridge.logic.relativeLuminance(c)
        val ratio = { a: Int, b: Int -> (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05) }
        assertTrue(ratio(StagePalette.ink, StagePalette.bg) > 12)
        assertTrue(ratio(StagePalette.accentBlue, StagePalette.panelRaised) > 7, "texto azul do botão sobre o painel")
        assertTrue(ratio(StagePalette.mutedInk, StagePalette.bg) > 4.5, "texto secundário")
        assertTrue(ratio(StagePalette.keyInk, StagePalette.keyFill) > 7, "tom compatível")
    }
}
