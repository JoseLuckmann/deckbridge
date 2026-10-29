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
        // OUVIR: o set inteiro, faixas e viradas, feito na hora.
        rule.onNodeWithText("OUVIR").performClick()
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
        rule.onNode(hasContentDescription("Pausar")).assertExists()
        rule.onNodeWithText(sp.chapters.first().title).assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-65-vertical-set-segundo-plano.png").path)
        a.tab = Tab.PLAYLISTS
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Pausar")).performClick()
        rule.waitForIdle()
        assertTrue(a.preview.paused)
        rule.onNode(hasContentDescription("Tocar")).performClick()
        rule.waitForIdle()
        // Próxima virada: cai 10 s antes dela, para ouvir a troca chegando.
        rule.onNode(hasContentDescription("Próxima virada")).performClick()
        rule.waitForIdle()
        val turn = sp.chapters.first { it.turn }.startMs
        assertEquals((turn - Preview.LEAD_IN_MS).toDouble(), a.preview.positionMs().toDouble(), 300.0)
        // Tocar no nome volta ao set.
        rule.onNode(hasContentDescription("Virada anterior")).assertExists()
        a.sets.open(1)
        rule.waitForIdle()
        a.sets.close()
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Parar o set")).performClick()
        rule.waitForIdle()
        assertNull(a.preview.setPlay)
        rule.onNode(hasContentDescription("Pausar")).assertDoesNotExist()
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
        rule.onNodeWithText("Ouvir só as viradas").performClick()
        rule.waitForIdle()
        val sp = assertNotNull(a.preview.setPlay, a.notice?.text)
        assertTrue(sp.chapters.isNotEmpty() && sp.chapters.all { it.turn })
        a.preview.stop()
    }

    private fun anyKeepsScreenOn(v: View): Boolean = v.keepScreenOn || (v is ViewGroup && (0 until v.childCount).any { anyKeepsScreenOn(v.getChildAt(it)) })

    @Test fun listeningToTheMixKeepsTheScreenOn() {
        val a = app()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        a.sets.openMix(a.sets.plan().first())
        idleUntil("prévia montada") { !a.sets.mixBusy }
        assertTrue(!anyKeepsScreenOn(rule.activity.window.decorView), "parado: a tela pode apagar")
        a.sets.playMix()
        rule.waitForIdle()
        assertTrue(a.sets.mixPlaying, a.notice?.text ?: "")
        assertTrue(anyKeepsScreenOn(rule.activity.window.decorView), "ouvindo a mixagem: a tela não apaga")
        a.sets.playMix()
        rule.waitForIdle()
        assertTrue(!anyKeepsScreenOn(rule.activity.window.decorView))
    }
}
