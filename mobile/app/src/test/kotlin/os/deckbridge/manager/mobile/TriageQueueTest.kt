package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch

/**
 * Relato do dono: "minha biblioteca tá toda classificada e quando abro ele me obriga a passar por todas as músicas".
 * MARCAR só traz as que faltam; com tudo marcado, não abre e diz isso; rever as marcadas é por escolha, no MENU.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class TriageQueueTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "fila-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 12); it.hideGuide() }
    }

    @Test fun aFullyRatedLibraryDoesNotOpenTheTriage() {
        val a = app()
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        val v = a.model.view!!
        // Só as sem nota entram.
        a.startTriage()
        val queue = assertNotNull(a.triage)
        assertTrue(queue.all { v.track(it)!!.rating == 0 }, "só as que faltam marcar")
        a.endTriage()
        // Toda a biblioteca classificada: MARCAR não abre e diz por quê.
        assertTrue(a.model.submit(Edit.SetTracks(v.tracks.map { it.id }, TrackPatch(rating = 3)), "todas com nota"))
        a.startTriage()
        assertNull(a.triage, "não obriga a passar por todas")
        assertTrue(a.notice?.text.orEmpty().startsWith("Tudo marcado"), a.notice?.text)
        // Rever é uma escolha: todas as marcadas, e o ✕ fecha na hora.
        a.startTriage(review = true)
        assertEquals(v.tracks.size, a.triage?.size)
        rule.waitForIdle()
        rule.onNodeWithText("✕").performClick()
        rule.waitForIdle()
        assertNull(a.triage)
    }
}
