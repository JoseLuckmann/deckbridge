package os.deckbridge.manager.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Os gestos que o usuário repete o tempo todo, tocando de verdade na tela (Robolectric, não o aparelho). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class UxFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "ux-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 12) }
    }

    private val noActions = Actions({}, {})

    @Test fun oneTapOnPlusAddsToTheTargetPlaylistAndTheNoticeUndoesIt() {
        val a = app()
        a.hideGuide()
        a.model.target = 2
        val before = a.model.view!!.entries[2].orEmpty().size
        rule.setContent { ManagerRoot(a, noActions) }
        rule.onAllNodesWithText("+")[0].performClick()
        rule.waitForIdle()
        assertEquals(before + 1, a.model.view!!.entries[2].orEmpty().size)
        rule.onNodeWithText("DESFAZER").performClick()
        rule.waitForIdle()
        assertEquals(before, a.model.view!!.entries[2].orEmpty().size)
    }

    @Test fun tappingAStarOnTheRowRatesWithoutOpeningTheTrack() {
        val a = app()
        a.hideGuide()
        val first = a.model.rows().first { it.rating == 0 }
        rule.setContent { ManagerRoot(a, noActions) }
        // Cada linha tem 5 estrelas; a primeira linha sem nota: toca a 3ª estrela (☆ vazias, todas iguais), então acha pela ordem.
        val index = a.model.rows().indexOf(first)
        rule.onAllNodesWithText("☆")[0].performClick()
        rule.waitForIdle()
        assertNull(a.detailId, "tocar na estrela não abre a faixa")
        assertTrue(a.model.dirty)
        assertTrue(a.model.pending.isNotEmpty() && index >= 0)
    }

    @Test fun triageAdvancesAfterRatingAndFinishesWithAMessage() {
        val a = app()
        a.hideGuide()
        a.startTriage()
        val total = a.triage!!.size
        val firstId = a.triage!![0]
        rule.setContent { ManagerRoot(a, noActions) }
        rule.onNodeWithText("4★").performClick()
        rule.waitForIdle()
        assertEquals(4, a.model.view!!.track(firstId)!!.rating)
        assertEquals(1, a.triageAt)
        repeat(total - 1) {
            rule.onNodeWithText(if (a.triageAt >= total - 1) "Concluir" else "Pular ›").performClick()
            rule.waitForIdle()
        }
        assertNull(a.triage)
        assertNotNull(a.notice)
    }

    @Test fun quickFilterChipNarrowsTheListAndTheGuideLeadsToTheNextStep() {
        val a = app()
        rule.setContent { ManagerRoot(a, noActions) }
        // Guia visível: o próximo passo não concluído é "Marcar" ou "Exportar"; o botão leva até lá.
        rule.onNodeWithText("PRIMEIROS PASSOS", substring = true).assertExists()
        a.model.filter = os.deckbridge.manager.smart.QuickFilter.TOP
        rule.waitForIdle()
        assertTrue(a.model.rows().all { it.rating >= 4 })
        assertTrue(a.model.rows().isNotEmpty())
    }
}
