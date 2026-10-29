package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Achar a troca no MIX (Robolectric): o app ouve as duas faixas em segundo plano, mostra as formas de fazer a troca com
 * nota e porquê, e tocar numa usa ela (fica no plano do set). As faixas de demonstração são cliques: o que se confere
 * aqui é o caminho, não o gosto (esse é o `MixFinderTest` do núcleo, com faixas de estrutura conhecida).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class MixFinderUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun idleUntil(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 90_000
        while (!done() && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        rule.waitForIdle()
        assertTrue(done(), what)
    }

    @Test fun theAppHearsBothTracksAndOffersWaysToMix() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ManagerApp(ctx, File(ctx.cacheDir, "achar-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40); it.hideGuide() }
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        val tr = a.sets.plan().first()
        a.sets.openMix(tr)
        idleUntil("prévia montada") { !a.sets.mixBusy }
        rule.onNodeWithText("AJUSTAR").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("ACHAR A TROCA").assertExists()
        idleUntil("ouviu as duas faixas") { a.sets.mixOptions() != null }
        val options = assertNotNull(a.sets.mixOptions())
        assertTrue(options.isNotEmpty(), "achou alguma forma de fazer a troca")
        options.forEach { assertTrue(it.reasons.isNotEmpty() && it.score in 1..99, it.toString()) }
        // O perfil ficou guardado fora do pendrive (na pasta dos sets): a próxima vez não lê o áudio de novo.
        assertTrue(File(a.setsDir, "perfis").listFiles().orEmpty().size >= 2)
        rule.onRoot().captureRoboImage(File(out, "mobile-62-vertical-mix-achar-troca.png").path)
        // Usar a opção: vira a troca do set, com o porquê junto.
        val pick = options.last()
        rule.onNodeWithText(pick.title).performScrollTo().performClick()
        idleUntil("montou a escolhida") { !a.sets.mixBusy }
        val now = assertNotNull(a.sets.mixing)
        assertEquals(pick.transition.outMs, now.outMs)
        assertEquals(pick.transition.aLoopBars, now.aLoopBars)
        assertEquals(pick.transition.why, now.why)
        val kept = a.sets.plan().first { it.fromId == tr.fromId && it.toId == tr.toId }
        assertEquals(pick.transition.bars, kept.bars)
        rule.onNodeWithText("EM USO").assertExists()
        // O porquê fica na aba TROCA, junto do passo a passo.
        rule.onNodeWithText("TROCA").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("POR QUE ESTA TROCA").performScrollTo().assertExists()
    }
}
