package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import android.view.View
import android.view.ViewGroup
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * Ouvir o set como um player de música (Robolectric): toca fora da tela do set, com a barra do player em qualquer aba,
 * o serviço de mídia (notificação, tela bloqueada) ligado, pausa, pula de trecho e para. E ouvindo a mixagem no MIX a
 * tela não apaga. O som de verdade e a tela bloqueada só no aparelho (`BLOCKED_HARDWARE`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class SetPlayerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "player-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 30); it.hideGuide() }
    }

    private fun idleUntil(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 120_000
        while (!done() && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        rule.waitForIdle()
        assertTrue(done(), what)
    }

    @Test fun theWholeSetPlaysInTheBackgroundWithAPlayerBar() {
        val a = app()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        val plan = a.sets.plan()
        // OUVIR: o set inteiro, faixas e viradas, feito na hora. (No JVM não há placa de som para segurar o tempo e o
        // set inteiro "toca" em instantes: pausa logo depois de começar, antes da tela andar.)
        rule.onAllNodes(androidx.compose.ui.test.hasText("OUVIR", substring = true))[0].assertExists()
        rule.runOnUiThread {
            a.sets.playWhole(plan)
            a.preview.pause()
        }
        rule.waitForIdle()
        val sp = assertNotNull(a.preview.setPlay, a.notice?.text)
        assertEquals(plan.size, sp.chapters.count { it.turn }, "uma virada por troca")
        assertEquals(plan.size + 1, sp.chapters.count { !it.turn }, "e cada faixa")
        assertTrue(sp.durationMs > plan.size * 20_000, "o set inteiro, não só as viradas: ${sp.durationMs}")
        // O serviço de mídia (notificação, controles na tela bloqueada) foi ligado.
        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedService
        assertEquals(SetPlaybackService::class.java.name, started?.component?.className)
        // As capas do player da notificação: numa faixa e numa virada.
        fun save(at: Int, name: String) = File(out, name).outputStream().use { SetPlaybackService.cover(sp.title, sp.chapters, at).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        save(0, "mobile-66-notificacao-capa-faixa.png")
        save(sp.chapters.indexOfFirst { it.turn }, "mobile-67-notificacao-capa-virada.png")
        // Sair do set não para: a barra do player aparece nas outras telas.
        a.sets.close()
        rule.waitForIdle()
        assertTrue(a.preview.inBackground)
        rule.onNode(hasContentDescription("Tocar")).assertExists()
        rule.onNodeWithText("Faixa 1/${plan.size + 1}", substring = true).assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-65-vertical-set-segundo-plano.png").path)
        a.tab = Tab.PLAYLISTS
        rule.waitForIdle()
        assertTrue(a.preview.paused)
        rule.onNode(hasContentDescription("Tocar")).assertExists()
        // Próxima virada: cai 10 s antes dela, para ouvir a troca chegando.
        rule.onNode(hasContentDescription("Próxima virada")).performClick()
        rule.waitForIdle()
        val turn = sp.chapters.first { it.turn }.startMs
        // Caiu 10 s antes da virada. No JVM o set "toca" instantaneamente: com a máquina carregada a posição já andou um
        // pouco depois do salto, então vale "no ponto do salto ou à frente dele", nunca antes.
        assertTrue(a.preview.positionMs() >= turn - Preview.LEAD_IN_MS - 300, "pulou para ${a.preview.positionMs()}, esperado ≥ ${turn - Preview.LEAD_IN_MS}")
        // Tocar no nome volta ao set.
        rule.onNode(hasContentDescription("Virada anterior")).assertExists()
        a.sets.open(1)
        rule.waitForIdle()
        a.sets.close()
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Parar o set")).performClick()
        rule.waitForIdle()
        assertNull(a.preview.setPlay)
        rule.onNode(hasContentDescription("Tocar")).assertDoesNotExist()
    }

    @Test fun onlyTheTurnsIsStillThereInTheMenu() {
        val a = app()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        val plan = a.sets.plan()
        val f = assertNotNull(a.sets.renderSet(plan))
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(f.absolutePath), ShadowMediaPlayer.MediaInfo(plan.size * 40_000, 0))
        rule.onNode(hasContentDescription("Mais")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Ouvir só as viradas").assertExists()
        rule.runOnUiThread {
            a.sets.playAll(plan)
            a.preview.pause()
        }
        rule.waitForIdle()
        val sp = assertNotNull(a.preview.setPlay, a.notice?.text)
        assertTrue(sp.chapters.isNotEmpty() && sp.chapters.all { it.turn })
        a.preview.stop()
    }

    @Test fun listeningToTheMixKeepsTheScreenOn() {
        // A regra: tocando no MIX ou no ensaio, acesa; parado, ou o set em segundo plano, pode apagar.
        assertTrue(keepAwake(playing = true, inBackground = false, mixOpen = true, rehearsing = false))
        assertTrue(keepAwake(playing = true, inBackground = false, mixOpen = false, rehearsing = true))
        assertTrue(!keepAwake(playing = false, inBackground = false, mixOpen = true, rehearsing = false))
        assertTrue(!keepAwake(playing = true, inBackground = true, mixOpen = false, rehearsing = false))
        assertTrue(!keepAwake(playing = true, inBackground = false, mixOpen = false, rehearsing = false))
        // E a tela a aplica: com a regra ligada, a vista do app pede a tela acesa.
        val a = app()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        assertTrue(!anyKeepsScreenOn(rule.activity.window.decorView), "nada tocando: a tela pode apagar")
    }

    private fun anyKeepsScreenOn(v: View): Boolean = v.keepScreenOn || (v is ViewGroup && (0 until v.childCount).any { anyKeepsScreenOn(v.getChildAt(it)) })
}
