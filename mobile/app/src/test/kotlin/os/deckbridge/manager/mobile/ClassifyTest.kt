package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import os.deckbridge.manager.organize.ColorRules
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch

/**
 * Classificar nos campos do rekordbox (Robolectric): gênero, energia no Comment e My Tags pelas ferramentas da biblioteca;
 * as cores automáticas por energia; e a faixa aberta mostrando gênero, a régua de energia e as My Tags.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class ClassifyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun idleUntil(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 180_000
        while (!done() && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        rule.waitForIdle()
        assertTrue(done(), what)
    }

    @Test fun classifyThenPaintByEnergyAndShowItOnTheTrack() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ManagerApp(ctx, File(ctx.cacheDir, "classe-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 24); it.hideGuide() }
        // Tira a cor de todas: a cor automática só pinta faixa sem cor.
        a.model.submit(Edit.SetTracks(a.model.view!!.tracks.map { it.id }, TrackPatch(colorId = 0)), "sem cor")
        assertNotNull(a.model.apply(null))
        a.changeColorRules(ColorRules(mode = ColorRules.Mode.OFF))
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        // Classificar pelas ferramentas da biblioteca: lê o áudio de cada faixa.
        rule.onNode(hasContentDescription("Ferramentas da biblioteca")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Classificar (gênero, energia, tags)").performClick()
        // Em segundo plano: a faixa de progresso no topo, sem janela por cima (a tela segue livre).
        rule.waitForIdle()
        rule.onNodeWithText("CLASSIFICANDO").assertExists()
        assertTrue(a.job == null, "sem janela de espera")
        rule.onRoot().captureRoboImage(File(out, "mobile-75-vertical-classificando-em-segundo-plano.png").path)
        // O lote grava sozinho (em lotes, com cópia de segurança).
        idleUntil("classificou") { a.work == null && a.model.view!!.tracks.all { os.deckbridge.manager.organize.TrackClassifier.energyOf(it.comment) != null } }
        val v = a.model.view!!
        assertTrue(a.model.myTags().values.any { it.isNotEmpty() }, "My Tags gravadas")
        // Cores por energia, escolhidas na janela; pintar as sem cor.
        rule.onNode(hasContentDescription("Ferramentas da biblioteca")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Cores automáticas").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Por energia").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Energia 8–10").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-73-vertical-cores-automaticas.png").path)
        rule.onNodeWithText("Pintar as sem cor").performClick()
        rule.waitForIdle()
        val painted = a.model.view!!.tracks
        for (t in painted) {
            val e = os.deckbridge.manager.organize.TrackClassifier.energyOf(t.comment)!!
            assertEquals(a.colorRules.byEnergy[ColorRules.band(e)], t.colorId, "${t.title}: energia $e")
        }
        // A faixa aberta mostra gênero, energia e My Tags.
        val t = v.tracks.first { a.model.myTags()[it.id].orEmpty().isNotEmpty() }
        a.detailId = t.id
        a.detailPage = DetailPage.INFO
        rule.waitForIdle()
        rule.onNodeWithText("ENERGIA").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasText("MY TAG", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        rule.onRoot().captureRoboImage(File(out, "mobile-74-vertical-classificacao.png").path)
    }
}
