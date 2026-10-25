package os.deckbridge.manager.mobile

import androidx.activity.ComponentActivity
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
import os.deckbridge.logic.decodeWav
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.SetPlanner

/**
 * Planejar o set: abrir a partir da playlist, ajustar uma transição, montar a prévia e o set inteiro. As faixas são
 * cliques sintéticos em WAV (lidos sem codec); MP3/FLAC passam pelo MediaCodec, que só existe no aparelho.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class SetFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "set-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 60) }
    }

    @Test fun planFromThePlaylistEditARenderAndKeepThePlan() {
        val a = app()
        a.hideGuide()
        a.tab = Tab.PLAYLISTS
        a.model.openPlaylist = 1
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.onNodeWithText("SET").performClick()
        rule.waitForIdle()
        assertEquals(1, a.sets.playlistId)
        val plan = a.sets.plan()
        assertEquals(3, plan.size)
        rule.onNodeWithText("PRÓXIMA").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-15-vertical-set.png").path)

        // Pontos automáticos em início de compasso da grade analisada.
        val v = a.model.view!!
        val first = v.track(plan[0].fromId)!!
        val g = assertNotNull(a.sets.grid(first))
        assertTrue(g.beats.any { it.inBar == 1 && it.timeMs == plan[0].outMs }, "saída num início de compasso")

        // Ajuste: 8 compassos, corte; fica guardado ao reabrir.
        val edited = plan[0].copy(bars = 8, style = MixStyle.CUT)
        a.sets.update(edited)
        a.sets.close()
        a.sets.open(1)
        assertEquals(edited, a.sets.plan()[0])

        // Prévia da transição: 12 s antes + 8 compassos + 12 s depois.
        val wav = assertNotNull(decodeWav(a.sets.renderTransition(edited).readBytes()))
        val expected = minOf(12_000, edited.outMs) + (8 * SetPlanner.barMs(first.bpm)).toInt() + 12_000
        assertEquals(expected / 1000.0, wav.frameCount / 44100.0, 0.05)
        assertTrue(wav.left.take(wav.frameCount).any { kotlin.math.abs(it) > 0.1f }, "tem som")

        // O set inteiro: as três transições em sequência.
        val all = assertNotNull(a.sets.renderSet(a.sets.plan()))
        val set = assertNotNull(decodeWav(all.readBytes()))
        assertTrue(set.frameCount / 44100.0 > 3 * 16, "três transições: ${set.frameCount / 44100.0} s")

        // Sugestão: a próxima entra no fim da playlist.
        val next = a.sets.suggestions(a.sets.ids()).first()
        a.sets.append(next)
        assertEquals(next.id, a.sets.ids().last())
        assertEquals(4, a.sets.plan().size)
    }

    @Test fun transitionDialogShot() {
        val a = app()
        a.hideGuide()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        rule.onAllNodes(androidx.compose.ui.test.hasText("✎"))[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("COMPASSOS").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-16-vertical-transicao.png").path)
    }
}
