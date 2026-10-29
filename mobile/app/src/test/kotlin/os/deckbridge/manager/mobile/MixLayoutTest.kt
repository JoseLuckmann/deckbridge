package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O mixer do MIX no celular não quebra (Robolectric): na altura cheia e na que sobra no aparelho com as barras do sistema
 * (S25 Ultra), e com DESFAZER/GUARDAR aparecendo, nenhum botão invade outro nem o FX, e todos têm um tamanho de mexer.
 * Relato do dono: na 0.31.2 os botões sumiam (minúsculos) ou estouravam por cima do FX.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MixLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun check(tag: String) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ManagerApp(ctx, File(ctx.cacheDir, "mixlay-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 30); it.hideGuide() }
        a.sets.open(1)
        a.sets.openMix(a.sets.plan().first())
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        val until = System.currentTimeMillis() + 90_000
        while (a.sets.mixBusy && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        rule.waitForIdle()
        fun bounds(name: String): Rect = rule.onNode(hasContentDescription(name)).fetchSemanticsNode().boundsInRoot
        fun verify(state: String) {
            val knobs = listOf("HI", "MID", "LOW", "FILTER").flatMap { k -> listOf(1, 2).map { "$k CH $it" } }
            val rects = knobs.associateWith { bounds(it) }
            val density = ctx.resources.displayMetrics.density
            for ((n, r) in rects) assertTrue(r.width / density >= 44 && r.height / density >= 40, "$state: $n pequeno demais ($r)")
            for ((n1, r1) in rects) for ((n2, r2) in rects) if (n1 < n2) assertTrue(!r1.overlaps(r2), "$state: $n1 invade $n2")
            // E nenhum invade o botão FX do seu canal (o primeiro FX é do CH 1).
            val fx = rule.onAllNodes(androidx.compose.ui.test.hasText("FX")).fetchSemanticsNodes().map { it.boundsInRoot }
            for ((n, r) in rects) for (f in fx) assertTrue(!r.overlaps(f), "$state: $n invade o FX")
            rule.onRoot().captureRoboImage(File(out, "mobile-76-mix-$tag-$state.png").path)
        }
        verify("normal")
        // A mão mexe: aparecem DESFAZER e GUARDAR, e o mixer encolhe — sem quebrar.
        rule.onNode(hasContentDescription("LOW CH 2")).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.waitForIdle()
        rule.onNodeWithText("GUARDAR").assertExists()
        verify("com-guardar")
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-560dpi")
    fun fullHeight() = check("891")

    /** O que sobra no S25 Ultra com a barra de status e a de gestos (e a tela "Fonte maior" de muitos aparelhos). */
    @Test @Config(qualifiers = "w384dp-h760dp-port-560dpi")
    fun shortPhone() = check("760")
}
