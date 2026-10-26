package os.deckbridge.manager.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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

    @Test fun searchIsHiddenUntilAskedAndClosingClearsIt() {
        val a = app()
        a.hideGuide()
        rule.setContent { ManagerRoot(a, noActions) }
        rule.onAllNodesWithText("Buscar").assertCountEquals(0)
        rule.onNodeWithText("⌕").performClick()
        rule.waitForIdle()
        assertTrue(a.searchOpen)
        a.model.query = "aurora"
        rule.waitForIdle()
        assertTrue(a.model.rows().all { it.artist.contains("Aurora") })
        rule.onNodeWithText("✕").performClick()
        rule.waitForIdle()
        assertEquals("", a.model.query)
        assertTrue(!a.searchOpen)
    }

    @Test fun triageAdvancesAfterRatingAndFinishesWithAMessage() {
        val a = app()
        a.hideGuide()
        a.startTriage()
        val total = a.triage!!.size
        val firstId = a.triage!![0]
        rule.setContent { ManagerRoot(a, noActions) }
        rule.onAllNodesWithText("☆")[3].performClick()
        rule.waitForIdle()
        assertEquals(4, a.model.view!!.track(firstId)!!.rating)
        assertEquals(1, a.triageAt)
        repeat(total - 1) {
            rule.onNodeWithText(if (a.triageAt >= total - 1) "FIM" else "PULAR ›").performClick()
            rule.waitForIdle()
        }
        assertNull(a.triage)
        assertNotNull(a.notice)
    }

    @Test fun quickFilterChipNarrowsTheListAndTheGuideLeadsToTheNextStep() {
        val a = app()
        rule.setContent { ManagerRoot(a, noActions) }
        // Guia visível: os quatro passos, o de agora destacado; tocar leva até lá.
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Passo 4: Pendrive")).assertExists()
        a.model.filter = os.deckbridge.manager.smart.QuickFilter.TOP
        rule.waitForIdle()
        assertTrue(a.model.rows().all { it.rating >= 4 })
        assertTrue(a.model.rows().isNotEmpty())
    }

    @Test fun playlistChipsOnTheTrackToggleMembershipInOneTap() {
        val a = app()
        a.hideGuide()
        val t = a.model.view!!.tracks.first { it.title == "Night Signal" }
        assertTrue(t.id !in a.model.view!!.entries[2].orEmpty())
        a.detailId = t.id
        rule.setContent { ManagerRoot(a, noActions) }
        rule.onNodeWithText("Aquecimento").performClick()
        rule.waitForIdle()
        assertTrue(t.id in a.model.view!!.entries[2].orEmpty())
        rule.onNodeWithText("✓ Aquecimento").performClick()
        rule.waitForIdle()
        assertTrue(t.id !in a.model.view!!.entries[2].orEmpty())
    }

    @Test fun swipingARowRightPutsItInTheTargetPlaylist() {
        val a = app()
        a.hideGuide()
        a.model.target = 2
        val t = a.model.view!!.tracks.first { it.title == "Night Signal" }
        assertTrue(t.id !in a.model.view!!.entries[2].orEmpty())
        rule.setContent { ManagerRoot(a, noActions) }
        rule.onNodeWithText("Night Signal").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertTrue(t.id in a.model.view!!.entries[2].orEmpty(), "deslizar põe na playlist alvo")
        assertEquals(null, a.detailId, "deslizar não abre a faixa")
    }

    @Test fun theTracksTabShowsTheWholeLibraryEvenWithAPlaylistOpen() {
        // Achado ao gravar a jornada: abrir uma playlist na aba PLAYLISTS deixava a aba FAIXAS só com as dela.
        val a = app()
        a.hideGuide()
        a.tab = Tab.PLAYLISTS
        a.model.openPlaylist = 2
        rule.setContent { ManagerRoot(a, noActions) }
        rule.onNodeWithText("MÚSICAS").performClick()
        rule.waitForIdle()
        assertEquals(a.model.view!!.tracks.size, a.model.rows().size)
        rule.onNodeWithText("Night Signal").assertExists()
    }
}
