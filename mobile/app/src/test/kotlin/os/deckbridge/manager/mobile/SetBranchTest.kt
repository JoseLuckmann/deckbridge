package os.deckbridge.manager.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
import os.deckbridge.manager.mix.SetBranches
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.SetTempo
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch

/** Ramos do set e o master, tocando na tela (Robolectric, não o aparelho). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class SetBranchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ManagerApp(ctx, File(ctx.cacheDir, "br-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40); it.setListView(true) }
        // Concrete Garden em Em: com Bbm não existe ramo de 3 faixas com todas as trocas no tom nesta biblioteca pequena.
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

    private fun mainSet(a: ManagerApp): Int {
        val pid = a.model.nextPlaylistId()
        a.model.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        a.model.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky").map { id(a, it) }), "p")
        return pid
    }

    @Test fun aBranchLeavesFromATrackAndComesBackLater() {
        val a = app()
        val main = mainSet(a)
        a.sets.open(main)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        // O ponto de virada da primeira faixa → criar ramo.
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Ponto de virada"))[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Criar ramo daqui").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("MAIS OPÇÕES").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Manter").performClick()
        rule.waitForIdle()
        captureScreenRoboImage(File(out, "mobile-24-vertical-novo-ramo.png").path)
        rule.onNodeWithText("CRIAR").performClick()
        rule.waitForIdle()

        val b = assertNotNull(a.sets.branch, "abriu o ramo")
        val v = a.model.view!!
        assertEquals(main, b.mainId)
        assertEquals(id(a, "Night Signal"), b.fromTrackId)
        assertEquals("Sábado - ramo B", v.playlists.first { it.id == b.playlistId }.name, "playlist de verdade (vai para o pendrive)")
        val branchIds = v.entries[b.playlistId].orEmpty()
        assertEquals(3, branchIds.size, "o padrão: 3 faixas")
        assertTrue(branchIds.none { it in v.entries[main].orEmpty() }, "ramo com faixas fora da linha")
        val line = a.sets.ids()
        assertEquals(b.fromTrackId, line.first())
        // "Volta para a linha: Sim" (o padrão): o ramo mira e volta mais adiante.
        val r = assertNotNull(b.rejoinTrackId, "voltou para a linha")
        assertEquals(r, line.last())
        assertTrue(v.entries[main].orEmpty().indexOf(r) >= 2, "volta mais adiante na linha")
        assertTrue(line.zipWithNext().all { (x, y) -> SetPlanner.fit(v.track(x)!!, v.track(y)!!).keyOk }, "trocas de tom boas no ramo")
        // O ramo tem as próprias transições (do ponto de virada à primeira do ramo, e até a volta).
        assertEquals(line.size - 1, a.sets.plan().size)
        rule.onRoot().captureRoboImage(File(out, "mobile-25-vertical-ramo.png").path)

        // Voltar: o set principal mostra o ramo debaixo do ponto de virada.
        rule.onAllNodes(hasText("‹"))[rule.onAllNodes(hasText("‹")).fetchSemanticsNodes().lastIndex].performClick()
        rule.waitForIdle()
        assertEquals(main, a.sets.playlistId)
        rule.onNodeWithText(" RAMO B").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-26-vertical-set-com-ramo.png").path)
        // Guardado fora do banco: reabrir acha o ramo.
        val saved = SetBranches.load(File(a.root.parentFile, "sets/branches.properties"))
        assertEquals(listOf(b), saved)
        // Tocar na linha do ramo abre ele de novo.
        rule.onNodeWithText(" RAMO B").performClick()
        rule.waitForIdle()
        assertEquals(b.playlistId, a.sets.playlistId)
    }

    @Test fun aFixedMasterMakesEveryTrackPlayAtIt() {
        val a = app()
        val main = mainSet(a)
        a.sets.open(main)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        // O master fica na faixa de cima: tocar nele escolhe.
        rule.onNodeWithText("AUTO").assertExists()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Master")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("126 BPM").performClick()
        rule.waitForIdle()
        assertEquals(126.0, a.sets.masterFixed)
        assertEquals(listOf(126.0, 126.0, 126.0, 126.0), a.sets.masters())
        assertEquals(126.0, a.sets.tempoFor(a.sets.plan()[0]))
        // Todas tocam a 126; o BPM da faixa e quanto estica só aparecem acima de 10% (aqui, nenhuma).
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("126")).fetchSemanticsNodes().size >= 5, "o master e as 4 faixas a 126")
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("123 +2%")).fetchSemanticsNodes().size)
        rule.onRoot().captureRoboImage(File(out, "mobile-27-vertical-master-fixo.png").path)
        // Guardado por set: reabrir mantém.
        a.sets.close()
        a.sets.open(main)
        assertEquals(126.0, a.sets.masterFixed)
        // Automático: segue as faixas sem saltos.
        a.sets.setMaster(null)
        val m = a.sets.masters()
        m.zipWithNext().forEach { (x, y) -> assertTrue(kotlin.math.abs(y - x) / x * 100 <= SetTempo.STEP_PCT + 1e-9) }
    }

    @Test fun theMapShowsEveryRouteAndItsTempo() {
        val a = app()
        val main = mainSet(a)
        a.sets.open(main)
        // Ramo B: sai da primeira e volta; ramo C: sai da terceira e vira outro final.
        val b = assertNotNull(a.sets.createBranch(id(a, "Night Signal"), 3, os.deckbridge.manager.mix.SetShape.FLOW, rejoin = true))
        a.sets.openMain()
        val c = assertNotNull(a.sets.createBranch(id(a, "Glass Harbour"), 2, os.deckbridge.manager.mix.SetShape.RISING, rejoin = false))
        a.sets.openMain()
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Mapa do set")).performClick()
        rule.waitForIdle()
        assertTrue(a.sets.mapOpen)
        rule.onNodeWithText("3 caminhos").assertExists()
        rule.onNodeWithText("Linha principal").assertExists()
        rule.onNodeWithText("Glass Harbour → outro final").assertExists()
        // Cada caminho com o andamento: a linha principal tem 4 faixas; B passa pelo ramo e volta; C acaba no ramo.
        val routes = a.sets.routes()
        assertEquals(3, routes.size)
        assertEquals(a.model.view!!.entries[main], routes[0].route.ids)
        assertEquals(b, routes[1].route.branch!!.playlistId)
        assertEquals(c, routes[2].route.branch!!.playlistId)
        assertEquals(null, routes[2].route.merge)
        routes.forEach { r ->
            assertTrue(r.tempo.startMs.zipWithNext().all { (x, y) -> y > x }, "o tempo anda")
            assertTrue(r.tempo.masters.zipWithNext().all { (x, y) -> kotlin.math.abs(y - x) / x * 100 <= SetTempo.STEP_PCT + 1e-9 }, "sem saltos")
        }
        rule.onRoot().captureRoboImage(File(out, "mobile-28-vertical-mapa.png").path)
        // Escolher o caminho B mostra as faixas dele com a hora em que entram.
        rule.onNodeWithText("B").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("ANDAMENTO · RAMO B").assertExists()
        assertEquals(2, rule.onAllNodes(hasText("0:00")).fetchSemanticsNodes().size, "eixo do gráfico e a primeira faixa")
        rule.onRoot().captureRoboImage(File(out, "mobile-29-vertical-mapa-ramo.png").path)
        // › abre a linha do caminho (o do B é o penúltimo › da tela: o mapa está por cima e o C vem depois).
        val opens = rule.onAllNodes(hasText("›"))
        opens[opens.fetchSemanticsNodes().lastIndex - 1].performClick()
        rule.waitForIdle()
        assertEquals(b, a.sets.playlistId)
        assertTrue(!a.sets.mapOpen)
    }
}
