package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch

/** O SET em quadro: pôr faixas, dividir a linha, mover, tirar e abrir a troca, tocando na tela (Robolectric, não o aparelho). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class SetBoardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    /** O AO VIVO está guardado (Features.liveMode); os testes dele o ligam e aqui ele volta a ficar desligado. */
    @org.junit.After fun liveOff() { os.deckbridge.manager.studio.Features.liveMode = false }


    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ManagerApp(ctx, File(ctx.cacheDir, "qd-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40) }
        // Os mesmos tons do teste de ramos (com eles existe ramo de 3 faixas no tom nesta biblioteca pequena).
        val keys = mapOf(
            "Night Signal" to "Am", "Glass Harbour" to "Em", "Tidal Bloom" to "Dm", "Soft Machines" to "C", "Concrete Garden" to "Em",
            "Afterhours" to "F#m", "Slow Pulse" to "Gm", "Velvet Rail" to "Am", "Copper Sky" to "Bm", "Small Hours" to "Em",
        )
        for (t in a.model.view!!.tracks) a.model.submit(Edit.SetTracks(listOf(t.id), TrackPatch(key = keys.getValue(t.title))), "tom")
        check(a.model.apply(null) != null)
        a.hideGuide()
        return a
    }

    private fun id(a: ManagerApp, title: String) = a.model.view!!.tracks.first { it.title == title }.id

    /** O último que bate: a tela do SET fica por cima da biblioteca, e os diálogos por cima do SET. */
    private fun click(text: String) {
        val all = rule.onAllNodes(hasText(text))
        all[all.fetchSemanticsNodes().size - 1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun tap(description: String) {
        // O quadro rola nos dois sentidos e o controle pode estar fora da tela: aciona o toque dele direto.
        rule.onAllNodes(hasContentDescription(description, substring = true))[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    @Test fun theSetIsABoardWhereLinesSplit() {
        val a = app()
        val pid = a.model.nextPlaylistId()
        a.model.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        a.model.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky").map { id(a, it) }), "p")
        a.sets.open(pid)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        assertTrue(!a.setAsList, "o quadro é o padrão")
        // Cada faixa é um cartão; o ＋ no fim da linha põe mais (as que combinam com a última primeiro).
        assertTrue(rule.onAllNodes(hasText("Copper Sky")).fetchSemanticsNodes().size >= 2, "cartão por cima da biblioteca")
        tap("Pôr faixa na linha A")
        rule.onNodeWithText("Pôr na linha A").assertExists()
        rule.onNodeWithText("COMBINAM COM A ÚLTIMA").assertExists()
        click("＋")
        click("PRONTO")
        assertEquals(5, a.sets.entries().size)
        a.notice = null
        // Tocar num cartão o escolhe: DIVIDIR abre um ramo dali, numa linha embaixo.
        click("Small Hours")
        click("DIVIDIR")
        click("Criar ramo daqui")
        click("CRIAR")
        val branch = assertNotNull(a.sets.branch, "o ramo passa a ser a linha escolhida")
        assertEquals(id(a, "Small Hours"), branch.fromTrackId)
        val layout = a.sets.canvas()!!
        assertEquals(2, layout.rows)
        assertTrue(layout.lanes[1].cards.isNotEmpty())
        assertEquals(layout.lanes[0].cards[1].col + 1, layout.lanes[1].cards[0].col, "o ramo começa logo depois de onde sai")
        rule.onNode(hasContentDescription("Linha B")).assertExists()
        a.notice = null
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(out, "mobile-41-vertical-set-quadro.png").path)
        // Escolher um cartão da linha principal volta a mexer nela; › anda com a faixa para frente.
        click("Night Signal")
        assertEquals(pid, a.sets.playlistId)
        tap("Para frente")
        assertEquals(id(a, "Night Signal"), a.sets.entries()[1])
        rule.onRoot().captureRoboImage(File(out, "mobile-42-vertical-set-quadro-faixa.png").path)
        // TIRAR: sem ramo saindo dela, sai direto (desfazível).
        click("TIRAR")
        assertTrue(id(a, "Night Signal") !in a.sets.entries())
        a.notice = null
        // De onde sai um ramo também sai direto, sem pergunta (desfazer em vez de confirmar): o aviso diz que a vertente
        // some junto e o DESFAZER traz as duas de volta.
        click("Small Hours")
        click("TIRAR")
        assertTrue(id(a, "Small Hours") !in a.sets.entries())
        rule.onNodeWithText("a vertente", substring = true).assertExists()
        click("DESFAZER")
        assertTrue(id(a, "Small Hours") in a.sets.entries())
        assertTrue(a.sets.branchesAt(a.sets.entries().indexOf(id(a, "Small Hours"))).isNotEmpty(), "a vertente voltou")
        // O número entre dois cartões é a troca: tocar abre o MIX dela.
        val first = a.sets.lanePlan(pid).first()
        tap("Troca ${first.fromId}→${first.toId}")
        assertEquals(first.fromId, a.sets.mixing?.fromId)
        a.sets.closeMix()
        rule.waitForIdle()
        // A outra vista continua no ⋯.
        tap("Mais")
        click("Ver em lista")
        assertTrue(a.setAsList)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-560dpi")
    fun lyingDownTheTimeRunsToTheRight() {
        val a = app()
        val pid = a.model.nextPlaylistId()
        a.model.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        a.model.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky").map { id(a, it) }), "p")
        a.sets.open(pid)
        assertNotNull(a.sets.createBranch(id(a, "Small Hours"), 3, os.deckbridge.manager.mix.SetShape.RISING, rejoin = true))
        a.notice = null
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Linha B")).assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-43-horizontal-set-quadro.png").path)
    }

    @Test fun openingThenStrandsThatJoinForTheEnding() {
        // Abertura (Night Signal, Small Hours), fechamento (Glass Harbour, Copper Sky): as vertentes saem da Small Hours
        // e juntam na Glass Harbour.
        val a = app()
        val pid = a.model.nextPlaylistId()
        a.model.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        a.model.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky").map { id(a, it) }), "p")
        a.sets.open(pid)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        click("Small Hours")
        click("DIVIDIR")
        click("Criar ramo daqui")
        // Onde junta fica à vista no diálogo: escolher a Glass Harbour.
        click("junta onde combinar")
        click("Glass Harbour")
        rule.onNodeWithText("junta em Glass Harbour").assertExists()
        click("CRIAR")
        assertEquals(id(a, "Glass Harbour"), a.sets.branch?.rejoinTrackId)
        a.notice = null
        // A segunda vertente do mesmo ponto já abre juntando no mesmo lugar.
        click("Small Hours")
        click("DIVIDIR")
        click("Criar ramo daqui")
        rule.onNodeWithText("junta em Glass Harbour").assertExists()
        click("CRIAR")
        a.notice = null
        val layout = a.sets.canvas()!!
        assertEquals(3, layout.rows)
        assertTrue(layout.lanes.drop(1).all { it.split?.trackId == id(a, "Small Hours") && it.rejoin?.trackId == id(a, "Glass Harbour") })
        // O fechamento fica depois da vertente mais longa (linha do tempo).
        val end = layout.lanes[0].cards.first { it.trackId == id(a, "Glass Harbour") }
        assertTrue(layout.lanes.drop(1).all { end.col > it.endCol })
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(out, "mobile-44-vertical-set-vertentes.png").path)
        // Ver tudo: o set inteiro cabe na tela do celular.
        tap("Ver tudo")
        rule.onRoot().captureRoboImage(File(out, "mobile-45-vertical-set-ver-tudo.png").path)
        tap("Aproximar")
        // O AO VIVO está guardado: fora do menu do app.
        tap("Mais")
        assertTrue(rule.onAllNodes(hasText("Ao vivo")).fetchSemanticsNodes().isEmpty(), "Ao vivo fora do app")
        click("Cancelar")
        os.deckbridge.manager.studio.Features.liveMode = true
        // Ao vivo no celular (guardado, testado): tocar na que toca mostra as vertentes possíveis.
        tap("Mais")
        click("Ao vivo")
        click("Small Hours")
        assertEquals(pid to 1, a.sets.liveNow)
        rule.onNodeWithText("AO VIVO").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-46-vertical-set-ao-vivo.png").path)
        click("SAIR")
        assertTrue(!a.sets.live)
        // O ↩ no fim de cada vertente muda onde ela junta.
        tap("Junta C")
        rule.onNodeWithText("Junta em").assertExists()
        click("Copper Sky")
        assertEquals(id(a, "Copper Sky"), a.sets.branch?.rejoinTrackId)
    }
}
