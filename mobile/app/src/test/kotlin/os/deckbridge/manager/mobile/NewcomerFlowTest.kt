package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.compose.ui.test.assertCountEquals
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Quem abre o app pela primeira vez: o guia de 4 passos, os destaques e as telas vazias com uma ação (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class NewcomerFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun click(text: String) {
        rule.onNodeWithText(text).performClick()
        rule.waitForIdle()
    }

    @Test fun anEmptyAppShowsWhereToStart() {
        val a = ManagerApp(ctx(), File(ctx().cacheDir, "novo-${System.nanoTime()}/biblioteca/dados")).also { it.start() }
        var picked = ""
        rule.setContent { ManagerRoot(a, Actions(pickAudio = { picked = "audio" }, pickUsb = { picked = "usb" })) }
        rule.waitForIdle()
        assertEquals(GuideStep.IMPORT, a.step())
        rule.onNode(hasContentDescription("Passo 1: Importar")).assertExists()
        rule.onNodeWithText("Comece pelas suas músicas").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-34-vertical-primeiro-uso.png").path)
        click("IMPORTAR MÚSICAS")
        assertEquals("audio", picked)
        // Quem já tem um pendrive do rekordbox: vai para o PENDRIVE e escolhe o pendrive.
        click("TENHO UM PENDRIVE DO REKORDBOX")
        assertEquals("usb", picked)
        assertEquals(Tab.USB, a.tab)
        // Sem músicas, as outras áreas apontam para importar.
        rule.onNodeWithText("IMPORTAR MÚSICAS").assertExists()
        click("PLAYLISTS")
        rule.onNodeWithText("IMPORTAR MÚSICAS").assertExists()
        // MARCAR não tem aba: mora em MÚSICAS e só aparece quando há músicas sem nota.
        rule.onAllNodes(androidx.compose.ui.test.hasText("MARCAR", substring = true)).assertCountEquals(0)
    }

    @Test fun theGuideWalksThroughRatePlaylistMixAndUsb() {
        val a = ManagerApp(ctx(), File(ctx().cacheDir, "guia-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 12) }
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        val step = a.step()
        assertTrue(step != GuideStep.IMPORT, "músicas importadas e analisadas: o passo é outro ($step)")
        rule.onRoot().captureRoboImage(File(out, "mobile-35-vertical-guia.png").path)

        // Playlist: o ＋ dela leva para as músicas com o ＋ em cada linha apontando para ela.
        click("PLAYLISTS")
        val pid = a.model.view!!.playlists.first { !it.folder && a.model.view!!.entries[it.id].orEmpty().size >= 2 }
        rule.onNodeWithText(pid.name).performClick()
        rule.waitForIdle()
        assertTrue(a.hint("set"), "MIXAR destacado até o primeiro uso")
        rule.onRoot().captureRoboImage(File(out, "mobile-36-vertical-playlist-mixar.png").path)
        click("＋")
        assertEquals(Tab.LIBRARY, a.tab)
        assertEquals(pid.id, a.model.target)
        assertTrue(rule.onAllNodes(hasText("＋ ${pid.name}")).fetchSemanticsNodes().isNotEmpty(), "o filtro mostra para onde vai o ＋")
        assertTrue(rule.onAllNodes(hasText("+")).fetchSemanticsNodes().isNotEmpty(), "＋ em cada linha")

        // MIXAR: o destaque some depois de usado; no SET, o ▶ da primeira transição e depois o ENSAIAR.
        click("PLAYLISTS")
        rule.onNodeWithText(pid.name).performClick()
        rule.waitForIdle()
        click("SET")
        assertFalse(a.hint("set"))
        assertTrue(a.hint("hear"))
        rule.onRoot().captureRoboImage(File(out, "mobile-37-vertical-set-primeiro-play.png").path)
        a.seen("hear")
        assertTrue(a.hint("rehearse"))
        click("ENSAIAR")
        assertFalse(a.hint("rehearse"))
        // O ensaio começou a montar a prévia: cancela e espera sair.
        a.job?.cancelled = true
        val until = System.currentTimeMillis() + 60_000
        while (a.job != null && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        a.sets.stopRehearsal()
        a.sets.close()
        rule.waitForIdle()

        // Pendrive: dois passos numerados; sem pendrive, o ESCOLHER leva o destaque.
        a.notice = null
        click("PENDRIVE")
        rule.onNodeWithText("ESCOLHER").assertExists()
        rule.onNodeWithText("NO CELULAR").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-38-vertical-pendrive-passos.png").path)

        // Fechar o guia some com ele e com os destaques; ligar de novo no MENU traz tudo de volta.
        rule.onNode(hasContentDescription("Fechar o guia")).performClick()
        rule.waitForIdle()
        assertTrue(a.guideHidden)
        assertFalse(a.hint("usb"))
        click("MENU")
        click("Guia para começar")
        assertFalse(a.guideHidden)
        assertTrue(a.hint("set"), "ligar o guia mostra os destaques de novo")
    }
}
