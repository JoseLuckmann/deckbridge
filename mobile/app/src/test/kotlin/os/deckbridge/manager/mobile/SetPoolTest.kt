package os.deckbridge.manager.mobile

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.deckbridge.manager.mix.SetPool
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch

/** Set flexível: faixas fixas, depois pool; ramo em pool, ramo escolhido à mão e ramo de ramo (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class SetPoolTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ManagerApp(ctx, File(ctx.cacheDir, "pool-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40) }
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

    private fun click(text: String) {
        rule.onNodeWithText(text).performClick()
        rule.waitForIdle()
    }

    /** Escolha num diálogo cujo texto também aparece na tela de trás: o diálogo é o último na árvore. */
    private fun pick(text: String) {
        rule.onAllNodes(hasText(text)).let { it[it.fetchSemanticsNodes().lastIndex] }.performClick()
        rule.waitForIdle()
    }

    private fun turn(n: Int) {
        rule.onAllNodes(hasContentDescription("Ponto de virada"))[n].performClick()
        rule.waitForIdle()
    }

    @Test fun threeFixedThenAPoolPickedLive() {
        val a = app()
        val pid = a.model.nextPlaylistId()
        a.model.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        val main = listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky", "Concrete Garden", "Velvet Rail").map { id(a, it) }
        a.model.submit(Edit.AddToPlaylist(pid, main), "p")
        a.sets.open(pid)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        // Ponto de virada na 3ª: daqui em diante é pool.
        turn(2)
        click("Pool depois desta")
        assertEquals(3, a.sets.poolFrom)
        rule.onNodeWithText("POOL").assertExists()
        assertEquals(0, rule.onAllNodes(hasText("ORDENAR")).fetchSemanticsNodes().size, "pool não se ordena")
        assertEquals(2, a.sets.plan().size, "transições só nas 3 fixas")
        assertEquals(3, a.sets.poolIds().size)
        // Tocando a Copper Sky: as outras vêm na ordem da melhor troca a partir dela.
        val v = a.model.view!!
        a.sets.playingNow(id(a, "Copper Sky"))
        rule.waitForIdle()
        rule.onNodeWithText("tocando").assertExists()
        val expected = SetPool.next(v, v.track(id(a, "Copper Sky"))!!, a.sets.poolIds(), emptySet()).map { it.first.title }
        assertEquals(listOf("Concrete Garden", "Velvet Rail").sorted(), expected.sorted())
        rule.onRoot().captureRoboImage(File(out, "mobile-30-vertical-pool-agora.png").path)
        // A próxima: a de antes vai para as tocadas.
        a.sets.playingNow(id(a, expected.first()))
        rule.waitForIdle()
        assertEquals(setOf(id(a, "Copper Sky")), a.sets.poolPlayed)
        rule.onNodeWithText("tocada").assertExists()
        click("ZERAR")
        assertEquals(null, a.sets.poolNow)
        // Guardado: reabrir mantém a pool.
        a.sets.close()
        a.sets.open(pid)
        assertEquals(3, a.sets.poolFrom)
        a.sets.makePool(null)
        assertEquals(5, a.sets.plan().size)
    }

    @Test fun aPoolBranchAManualBranchAndABranchOfABranch() {
        val a = app()
        val pid = a.model.nextPlaylistId()
        a.model.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        a.model.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky").map { id(a, it) }), "p")
        a.sets.open(pid)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        // Ramo B em pool, saindo da 1ª e voltando na linha.
        turn(0)
        click("Criar ramo daqui")
        click("Pool")
        // O "4" do diálogo (a 4ª faixa do set também mostra "4"): o diálogo é o último na árvore.
        pick("4")
        rule.onRoot().captureRoboImage(File(out, "mobile-31-vertical-ramo-tipo.png").path)
        click("CRIAR")
        val b = assertNotNull(a.sets.branch)
        assertTrue(a.sets.isPool(b.playlistId))
        assertEquals(4, a.model.view!!.entries[b.playlistId]!!.size)
        assertNotNull(b.rejoinTrackId, "a pool volta para a linha")
        rule.onNodeWithText("POOL").assertExists()
        rule.onNodeWithText("VOLTA").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-32-vertical-ramo-pool.png").path)
        a.sets.openMain()
        rule.waitForIdle()

        // Ramo C "Eu escolho" saindo da 2ª: nasce vazio; ponho uma faixa e escolho onde volta.
        turn(1)
        click("Criar ramo daqui")
        click("Eu escolho")
        click("CRIAR")
        val c = assertNotNull(a.sets.branch)
        assertEquals(emptyList(), a.model.view!!.entries[c.playlistId].orEmpty())
        assertEquals(null, c.rejoinTrackId)
        val pick = assertNotNull(a.sets.suggestions(a.sets.ids()).firstOrNull(), "há sugestão para começar")
        val busy = a.model.view!!.entries[pid]!! + a.model.view!!.entries[b.playlistId]!!
        assertTrue(a.sets.suggestions(a.sets.ids()).none { it.id in busy }, "a sugestão do ramo não repete faixa do set")
        a.sets.append(pick)
        rule.waitForIdle()
        assertEquals(listOf(pick.id), a.model.view!!.entries[c.playlistId])
        click("VOLTA")
        pick("Copper Sky")
        assertEquals(id(a, "Copper Sky"), a.sets.branch!!.rejoinTrackId)

        // Ramo D de dentro do C: sai da faixa que pus e é outro final.
        turn(0)
        click("Criar ramo daqui")
        click("Eu escolho")
        click("CRIAR")
        val d = assertNotNull(a.sets.branch)
        assertEquals(c.playlistId, d.mainId, "ramo de ramo")
        assertEquals('D', a.sets.letterOf(d))
        assertEquals(pid, a.sets.rootId())
        // O mapa tem a linha e os três ramos.
        val routes = a.sets.routes()
        assertEquals(listOf('A', 'B', 'C', 'D'), routes.map { it.route.letter })
        assertEquals(listOf(id(a, "Night Signal"), id(a, "Small Hours"), pick.id), routes[3].route.ids, "D passa pela linha e pelo C")
        assertTrue(routes[1].route.pool.count { it } == 4, "o caminho B tem a pool")
        a.sets.mapOpen = true
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(out, "mobile-33-vertical-mapa-flexivel.png").path)
    }
}
