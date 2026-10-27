package os.deckbridge.manager.studio

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import os.deckbridge.logic.StagePalette

/**
 * O design system é o do player, não um novo: `StagePalette` (fundo preto, painéis quase pretos, texto azul-claro nos
 * botões, laranja para o que pede atenção, linha de lista selecionada em azul, tom compatível em verde). Aqui só há
 * apelidos para ele. Nenhuma cor é escrita à mão fora deste arquivo (o teste `DesignSystemTest` confere), então o
 * celular não se afasta da referência sem que um teste falhe.
 */
object Palette {
    private fun c(argb: Int) = Color(argb)

    val bg = c(StagePalette.bg)
    val surface = c(StagePalette.panel)
    val surfaceHigh = c(StagePalette.panelRaised)
    val line = c(StagePalette.line)
    val text = c(StagePalette.ink)
    val muted = c(StagePalette.mutedInk)
    val mutedBright = c(StagePalette.inkMuted)

    /**
     * Texto secundário sobre [selected] (linha azul, botão ativo): o cinza normal fica abaixo de 4,5:1 ali (WCAG 1.4.3),
     * então troca pelo cinza claro.
     */
    fun dimOn(selected: Boolean) = if (selected) mutedBright else muted

    /** Azul de rótulo e de botão. */
    val blue = c(StagePalette.accentBlue)
    val blueActiveFill = c(StagePalette.activeFill)
    val blueActiveLine = c(StagePalette.activeLine)
    val selectedRow = c(StagePalette.selectedRow)

    /** Laranja: valor em destaque, mudança pendente, aviso. */
    val orange = c(StagePalette.accentOrange)
    val danger = c(StagePalette.danger)
    val dangerBg = c(StagePalette.dangerBg)
    val onAir = c(StagePalette.onAir)

    /** Botão de tocar e tom compatível: verde-claro sobre verde-escuro, como no player. */
    val playBg = c(StagePalette.playBg)
    val playInk = c(StagePalette.playInk)
    val keyFill = c(StagePalette.keyFill)
    val keyInk = c(StagePalette.keyInk)

    /** Faixa de simulação/aviso do player: fundo âmbar-escuro, texto âmbar-claro. */
    val noteBg = c(StagePalette.simBg)
    val noteInk = c(StagePalette.simInk)

    val wave = c(StagePalette.wave)
    val waveBg = c(StagePalette.waveBg)
    val waveLow = c(StagePalette.waveLow)
    val waveMid = c(StagePalette.waveMid)
    val waveHigh = c(StagePalette.waveHigh)
    val deckA = c(StagePalette.deckA)
    val deckB = c(StagePalette.deckB)

    /** Cores dos pads de hot cue (A–H), as mesmas do player. */
    val pads = StagePalette.pads.map { c(it) }

    /** Cores de faixa do rekordbox, ids 1–8: rosa, vermelho, laranja, amarelo, verde, água, azul, roxo. */
    val trackColors = listOf(pads[7], pads[0], pads[1], pads[2], pads[3], pads[4], pads[5], pads[6])
    val trackColorNames = listOf("Rosa", "Vermelho", "Laranja", "Amarelo", "Verde", "Água", "Azul", "Roxo")

    // Apelidos que as telas já usavam; apontam para as cores do player.
    val accent get() = blue
    val accent2 get() = blue
    val warn get() = orange
}

/** Cor gravada num cue (RGB sem alfa) como cor de tela. */
fun rgb(v: Int?, fallback: Color = Color.Gray): Color = if (v == null) fallback else Color(0xFF000000.toInt() or (v and 0xFFFFFF))

/** Cor do pad de hot cue (1 = A … 8 = H), a mesma do player, como RGB gravável no cue. */
fun padRgb(hot: Int): Int = StagePalette.pads[(hot - 1).coerceIn(0, 7)] and 0xFFFFFF

/** A partir desta largura a tela vira duas colunas (celular deitado, tablet, computador). */
const val WIDE_DP = 600

/** Cantos quase retos, como os botões e painéis do player. */
val RbShape = RoundedCornerShape(3.dp)

@Composable
fun ManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.blue, onPrimary = Palette.bg, secondary = Palette.blue, background = Palette.bg, onBackground = Palette.text,
            surface = Palette.surface, onSurface = Palette.text, surfaceVariant = Palette.surfaceHigh, onSurfaceVariant = Palette.muted,
            outline = Palette.line, outlineVariant = Palette.line, error = Palette.danger,
        ),
        shapes = Shapes(extraSmall = RbShape, small = RbShape, medium = RbShape, large = RbShape, extraLarge = RbShape),
        content = content,
    )
}
