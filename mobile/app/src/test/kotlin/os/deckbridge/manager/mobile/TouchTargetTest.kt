package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tamanho dos alvos de toque (lei de Fitts; Parhi, Karlson e Bederson 2006 recomendam ~9,2 mm para o polegar; o Material
 * pede 48 dp): todo controle tocável das telas principais tem área de toque de pelo menos 48 × 48 dp, mesmo quando o
 * desenho é menor. O Compose estende a área de um controle pequeno até 48 dp quando há espaço em volta; o teste confere
 * o resultado, a área que o dedo realmente acerta.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class TouchTargetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "toque-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40) }
    }

    /** Os controles tocáveis na tela de cima ([from] em diante na árvore) menores que [min] dp, com o que dizem. */
    private fun small(from: Int = 0, min: Float = 48f): List<String> {
        val density = rule.activity.resources.displayMetrics.density
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().drop(from)
        return nodes.filter { n ->
            val b = n.touchBoundsInRoot
            // Cortado pela borda da rolagem (parte fora da tela): não é um alvo pequeno, é um alvo que se rola até ver.
            val clipped = n.boundsInRoot.width < n.size.width - 1 || n.boundsInRoot.height < n.size.height - 1
            !clipped && (b.width / density < min - 0.5f || b.height / density < min - 0.5f)
        }.map { n -> "${label(n)} ${"%.0f×%.0f".format(n.touchBoundsInRoot.width / density, n.touchBoundsInRoot.height / density)}" }
    }

    private fun label(n: SemanticsNode): String {
        val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: n.children.firstNotNullOfOrNull { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        return "[" + (text ?: "?") + "]"
    }

    private fun count() = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size

    @Test fun everyTappableControlIsAtLeast48dp() {
        val a = app()
        a.hideGuide()
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        val found = mutableMapOf<String, List<String>>()
        found["músicas"] = small()
        a.tab = Tab.PLAYLISTS
        a.model.openPlaylist = 1
        rule.waitForIdle()
        found["playlist"] = small()
        // As telas de set ficam por cima: só os controles delas (depois dos da tela de baixo na árvore).
        val below = count()
        a.sets.open(1)
        rule.waitForIdle()
        found["set (quadro)"] = small(below)
        val beforeMix = count()
        a.sets.openMix(a.sets.plan().first())
        rule.waitForIdle()
        found["mix"] = small(beforeMix)
        a.sets.closeMix()
        a.sets.close()
        rule.waitForIdle()
        a.startTriage()
        rule.waitForIdle()
        found["marcar"] = small(below)
        val report = found.filter { it.value.isNotEmpty() }.map { (k, v) -> "$k: ${v.joinToString()}" }
        println("ALVOS PEQUENOS\n" + report.joinToString("\n"))
        assertTrue(report.isEmpty(), "Alvos de toque menores que 48 dp:\n" + report.joinToString("\n"))
    }
}
