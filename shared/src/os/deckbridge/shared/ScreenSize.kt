package os.deckbridge.shared

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Os pontos de corte de tamanho de tela, num lugar só para o modo Biblioteca e o MIX (studio/), o modo DJ (stage/) e o
 * computador. Medidas em dp do espaço que a tela tem (o de `BoxWithConstraints`), não do aparelho.
 *
 * Referências: Galaxy S25 Ultra 412×891 dp em pé e 891×412 deitado no padrão (832×384 com o "zoom de tela" maior);
 * Tab S6 Lite 800×1333 em pé e 1333×800 deitado.
 */
object ScreenSize {
    /** A partir desta largura o modo Biblioteca e o MIX usam o arranjo largo (trilho de navegação, colunas lado a lado). */
    const val WIDE_DP = 600

    /** Abaixo desta altura (celular deitado) o modo DJ usa o arranjo apertado: topo numa linha, ondas primeiro. */
    val TIGHT_HEIGHT: Dp = 520.dp
}
