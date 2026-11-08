package os.deckbridge.manager.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.assertTrue
import org.junit.Test
import os.deckbridge.manager.studio.Palette

/**
 * Contraste do texto (WCAG 2.x, 1.4.3: 4,5:1 para texto normal; a conta vem de 3:1 para visão normal vezes 1,5 para quem
 * enxerga 20/40). Confere os pares de cor de texto e fundo que as telas realmente usam, inclusive sobre a linha
 * selecionada e o botão ativo, onde o cinza normal não passa e as telas usam [Palette.dimOn].
 */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb).toDouble() / minOf(la, lb)
    }

    @Test fun textPairsUsedOnScreenReachAA() {
        val plain = listOf(Palette.bg, Palette.surface, Palette.surfaceHigh, Palette.waveBg)
        val inks = mapOf(
            "texto" to Palette.text, "cinza claro" to Palette.mutedBright, "cinza" to Palette.muted, "azul" to Palette.blue,
            "laranja" to Palette.orange, "verde do tom" to Palette.keyInk, "aviso" to Palette.noteInk, "vermelho" to Palette.danger,
        )
        val pairs = buildList {
            for ((name, ink) in inks) for (bg in plain) add("$name sobre fundo ${plain.indexOf(bg)}" to (ink to bg))
            // Linha selecionada e botão ativo: texto, azul, laranja e o cinza escolhido por dimOn(true).
            for (bg in listOf(Palette.selectedRow, Palette.blueActiveFill)) {
                for ((name, ink) in listOf("texto" to Palette.text, "secundário" to Palette.dimOn(true), "laranja" to Palette.orange, "verde do tom" to Palette.keyInk)) {
                    add("$name sobre selecionado" to (ink to bg))
                }
            }
            add("aviso sobre faixa de aviso" to (Palette.noteInk to Palette.noteBg))
            add("texto sobre faixa de erro" to (Palette.text to Palette.dangerBg))
            add("PLAY" to (Palette.playInk to Palette.playBg))
            add("tom compatível" to (Palette.keyInk to Palette.keyFill))
            add("tom longe" to (Palette.orange to Palette.dangerBg))
            add("botão ativo (texto preto sobre azul)" to (Palette.bg to Palette.blue))
        }
        val low = pairs.map { (name, p) -> name to ratio(p.first, p.second) }.filter { it.second < 4.5 }
        assertTrue(low.isEmpty(), "Abaixo de 4,5:1: " + low.joinToString { "${it.first} ${"%.1f".format(it.second)}" })
        // E o motivo de dimOn existir: o cinza normal não passa sobre a linha selecionada.
        assertTrue(ratio(Palette.muted, Palette.selectedRow) < 4.5)
    }
}
