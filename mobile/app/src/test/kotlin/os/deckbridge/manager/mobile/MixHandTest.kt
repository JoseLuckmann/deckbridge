package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.deckbridge.manager.mix.Ctl
import os.deckbridge.manager.mix.Transition

/**
 * A mão do DJ no MIX (Robolectric): mexer no mixer muda o som e vira a mixagem da troca, guardada ou desfeita; o encaixe
 * à mão vale sem montar a prévia de novo; e depois de três mixagens guardadas as trocas novas começam do jeito dele.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class MixHandTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "mao-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40); it.hideGuide() }
    }

    private fun waitMix(a: ManagerApp) {
        val until = System.currentTimeMillis() + 90_000
        while (a.sets.mixBusy && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        rule.waitForIdle()
    }

    /** Põe um knob ou fader em [v] como o leitor de tela (e o dedo) faz: pela ação de ajustar o valor. */
    private fun turn(name: String, v: Float) {
        rule.onNode(hasContentDescription(name)).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
        rule.waitForIdle()
    }

    @Test fun theHandOnTheMixerBecomesThisTransitionsMix() {
        val a = app()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        val tr = a.sets.plan().first()
        a.sets.openMix(tr)
        waitMix(a)
        val p = assertNotNull(a.sets.mixPreview, a.sets.mixError)
        // A mão do DJ fica na aba MIXER.
        rule.onNodeWithText("MIXER").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("AUTOMÁTICO").assertExists()
        // Na saída, o DJ abre o grave da que entra (o automático o deixa fechado até a troca).
        a.sets.seekMix(p.leadMs)
        rule.waitForIdle()
        assertEquals(0f, p.levelsAt(p.leadMs, a.sets.mixing!!).bLow)
        turn("LOW CH 2", 1f)
        assertEquals(1f, p.levelsAt(p.leadMs + 500, a.sets.mixing!!, a.sets.draftTake).bLow, 0.001f)
        assertTrue(a.sets.takeChanged)
        rule.onNodeWithText("● SUA MÃO").assertExists()
        // E põe um filtro na que sai.
        turn("FILTER CH 1", 0.85f)
        rule.onRoot().captureRoboImage(File(out, "mobile-60-vertical-mix-sua-mao.png").path)
        // Desfazer volta ao automático; de novo, e guardar.
        rule.onNodeWithText("DESFAZER").performClick()
        rule.waitForIdle()
        assertNull(a.sets.draftTake)
        turn("LOW CH 2", 1f)
        rule.onNodeWithText("GUARDAR").performClick()
        rule.waitForIdle()
        val kept = assertNotNull(a.sets.mixing!!.take)
        assertNotNull(kept.at(Ctl.B_LOW, 1.0))
        rule.onNodeWithText("SUA MIXAGEM").assertExists()
        // Guardada no plano do set: a troca toca assim (e o mix de teste do set também).
        assertEquals(kept, a.sets.plan().first { it.fromId == tr.fromId && it.toId == tr.toId }.take)
        // O WAV da prévia tem a mão: no ponto de saída o grave da que entra já soa.
        assertTrue(a.sets.mixFile!!.isFile)

        // Encaixe à mão: vale na hora, sem ler as faixas de novo (a mesma prévia).
        rule.onNodeWithText("5 ms ▶").performScrollTo().performClick()
        rule.onNodeWithText("+1 BAT").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(5, a.sets.mixing!!.nudgeMs)
        assertEquals(1, a.sets.mixing!!.beatShift)
        assertSame(p, a.sets.mixPreview, "não montou de novo")
        assertEquals(p.shiftMs(tr) + 5 + p.beatMs, p.shiftMs(a.sets.mixing!!), 0.01)
        rule.onRoot().captureRoboImage(File(out, "mobile-61-vertical-mix-encaixe.png").path)
        rule.onNodeWithText("ZERAR").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(0, a.sets.mixing!!.nudgeMs)
        // Repetir a troca para ensaiar de novo. (O aviso de baixo some sozinho no aparelho; aqui o relógio não anda e ele
        // ficaria por cima da fileira do PLAY.)
        a.notice = null
        rule.waitForIdle()
        rule.onNodeWithText("LOOP").performClick()
        rule.waitForIdle()
        assertTrue(a.sets.mixLoop)
        assertEquals(a.sets.loopRange(p), a.sets.mixPlayer!!.loop)
        // Estilo muda o som na hora também (ao vivo), e a mixagem guardada continua.
        a.sets.editMix(a.sets.mixing!!.copy(pace = Transition.Pace.SLOW))
        assertSame(p, a.sets.mixPreview)
        assertEquals(kept, a.sets.mixing!!.take)
    }

    @Test fun threeKeptMixesTeachTheNewTransitions() {
        val a = app()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        // Em cada troca do set, o DJ começa a tirar a que sai na metade (fade lento) e guarda.
        for (tr in a.sets.plan()) {
            a.sets.openMix(tr)
            waitMix(a)
            val p = assertNotNull(a.sets.mixPreview, a.sets.mixError)
            a.sets.seekMix(p.leadMs + p.overlapMs / 2)
            a.sets.setControl(Ctl.A_VOL, 0.8f)
            a.sets.seekMix(p.leadMs + p.overlapMs)
            a.sets.setControl(Ctl.A_VOL, 0f)
            a.sets.keepTake()
            a.sets.closeMix()
        }
        val h = assertNotNull(a.sets.habits(), "três mixagens: já aprendeu")
        assertEquals(Transition.Pace.SLOW, h.pace)
        // Num outro set, as trocas automáticas já começam lentas, como ele faz.
        a.sets.close()
        a.sets.open(2)
        rule.waitForIdle()
        assertTrue(a.sets.plan().isNotEmpty())
        a.sets.plan().forEach { assertEquals(Transition.Pace.SLOW, it.pace, "troca nova no jeito do DJ") }
    }
}
