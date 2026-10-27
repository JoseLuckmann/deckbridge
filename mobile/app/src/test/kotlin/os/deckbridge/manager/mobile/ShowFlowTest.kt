package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
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
import os.deckbridge.manager.smart.ShowCheck

/** No dia do show: conferir o set, arrumar o que falta, guardar o que tocou e responder a um pedido (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class ShowFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    /** O AO VIVO está guardado (Features.liveMode); os testes dele o ligam e aqui ele volta a ficar desligado. */
    @org.junit.After fun liveOff() { os.deckbridge.manager.studio.Features.liveMode = false }


    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "show-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40); it.hideGuide() }
    }

    private fun click(text: String) {
        val all = rule.onAllNodes(hasText(text))
        all[all.fetchSemanticsNodes().size - 1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun waitJob(a: ManagerApp) {
        val until = System.currentTimeMillis() + 180_000
        while (a.job != null && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    @Test fun theShowChecklistPointsAtWhatIsMissingAndFixesIt() {
        val a = app()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        val before = a.sets.showItems().associateBy { it.kind }
        // A biblioteca de demonstração tem áudio e análise de tudo; nenhuma troca foi ensaiada ainda.
        assertTrue(before.getValue(ShowCheck.Kind.AUDIO).ok)
        assertTrue(before.getValue(ShowCheck.Kind.ANALYSIS).ok)
        assertTrue(!before.getValue(ShowCheck.Kind.REHEARSED).ok)
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Mais"))[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        click("Pronto pro show?")
        val until = System.currentTimeMillis() + 30_000
        while (rule.onAllNodes(hasText("Conferindo…")).fetchSemanticsNodes().isNotEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            rule.waitForIdle()
        }
        rule.onAllNodes(hasText("PARA OLHAR", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        rule.onAllNodes(hasText("Áudio de todas no aparelho")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "o que está certo aparece com ✓") }
        rule.onRoot().captureRoboImage(File(out, "mobile-58-vertical-pronto-pro-show.png").path)
        click("FECHAR")
        // Arrumar: cues de estrutura onde faltam (sem apagar os do DJ); um desfazer volta tudo.
        val missing = before.getValue(ShowCheck.Kind.CUES).trackIds
        if (missing.isNotEmpty()) {
            val pending = a.model.pending.size
            a.sets.prepareForShow(missing)
            waitJob(a)
            assertTrue(a.model.pending.size >= pending)
        }
    }

    @Test fun whatWasPlayedLiveIsSavedInTheRealOrder() {
        val a = app()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        val cards = a.sets.canvas()!!.lanes[0].cards
        os.deckbridge.manager.studio.Features.liveMode = true
        a.sets.startLive()
        // Tocou a 1ª, pulou para a 3ª (mudou de ideia na hora) e depois a 2ª.
        a.sets.liveTo(cards[0])
        a.sets.liveTo(cards[2])
        a.sets.liveTo(cards[1])
        a.sets.stopLive()
        rule.waitForIdle()
        rule.onAllNodes(hasText("Guardar o que tocou?")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        rule.onRoot().captureRoboImage(File(out, "mobile-59-vertical-guardar-o-que-tocou.png").path)
        click("GUARDAR")
        val v = a.model.view!!
        val saved = v.playlists.first { it.name.startsWith("Tocado ") }
        assertEquals(listOf(cards[0], cards[2], cards[1]).map { it.trackId }, v.entries[saved.id])
        assertTrue(saved.id in a.sets.knownSets(), "conta como set")
    }

    @Test fun aRequestGetsABridgeFromWhatIsPlaying() {
        val a = app()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        val lane = a.sets.canvas()!!.lanes[0]
        os.deckbridge.manager.studio.Features.liveMode = true
        a.sets.startLive()
        a.sets.liveTo(lane.cards[0])
        rule.waitForIdle()
        rule.onAllNodes(hasText("PEDIDO")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "PEDIDO no AO VIVO") }
        val now = assertNotNull(a.sets.liveTrackItem())
        // Pedido: uma faixa fora do set.
        val inSet = a.model.view!!.entries[1].orEmpty().toSet()
        val target = a.model.view!!.tracks.first { it.id !in inSet }
        val route = a.sets.requestRoute(now, target)
        if (route != null) {
            assertTrue(route.size <= os.deckbridge.manager.smart.Request.MAX_HOPS)
            assertTrue(a.sets.insertRoute(route, target))
            val line = a.model.view!!.entries[1].orEmpty()
            // Logo depois da que toca: o caminho e o pedido.
            assertEquals((route + target).map { it.id }, line.subList(1, 2 + route.size))
        }
    }
}
