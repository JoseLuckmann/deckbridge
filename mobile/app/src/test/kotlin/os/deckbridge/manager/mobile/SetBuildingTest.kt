package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

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
import os.deckbridge.manager.mix.SetBuilder
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch

/** Montar, checar, fazer ponte, ordenar e ensaiar um set, tocando na tela (Robolectric, não o aparelho). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class SetBuildingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    /** Biblioteca de demonstração com tons escolhidos, para as trocas serem previsíveis. */
    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ManagerApp(ctx, File(ctx.cacheDir, "sb-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40); it.setListView(true) }
        val keys = mapOf(
            "Night Signal" to "Am", "Glass Harbour" to "Em", "Tidal Bloom" to "Dm", "Soft Machines" to "C", "Concrete Garden" to "Bbm",
            "Afterhours" to "F#m", "Slow Pulse" to "Gm", "Velvet Rail" to "Am", "Copper Sky" to "Bm", "Small Hours" to "Em",
        )
        for (t in a.model.view!!.tracks) a.model.submit(Edit.SetTracks(listOf(t.id), TrackPatch(key = keys.getValue(t.title))), "tom")
        check(a.model.apply(null) != null)
        a.hideGuide()
        return a
    }

    private fun id(a: ManagerApp, title: String) = a.model.view!!.tracks.first { it.title == title }.id

    private fun waitJob(a: ManagerApp) {
        val until = System.currentTimeMillis() + 120_000
        while (a.job != null && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    @Test fun buildASetFromTheLibraryInOneDialog() {
        val a = app()
        a.tab = Tab.PLAYLISTS
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.onNodeWithText("MONTAR SET").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("30 min").performClick()
        rule.onNodeWithText("Subindo").performClick()
        rule.waitForIdle()
        captureScreenRoboImage(File(out, "mobile-19-vertical-montar-set.png").path)
        rule.onNodeWithText("MONTAR").performClick()
        rule.waitForIdle()
        val pid = assertNotNull(a.sets.playlistId, "abriu o SET do set novo")
        val v = a.model.view!!
        assertEquals("Set 30 min · Subindo", v.playlists.first { it.id == pid }.name)
        val ids = v.entries[pid].orEmpty()
        assertTrue(ids.size >= 5, "${ids.size} faixas")
        assertEquals(ids.size, ids.distinct().size)
        val bpms = ids.map { v.track(it)!!.bpm }
        assertTrue(bpms.zipWithNext().none { (x, y) -> y < x - 1.0 }, "subindo (recuo de até 1 BPM): $bpms")
        rule.onRoot().captureRoboImage(File(out, "mobile-20-vertical-set-montado.png").path)
    }

    @Test fun anIssueShowsOnTheTransitionAndTheBridgeFixesIt() {
        val a = app()
        val pid = a.model.nextPlaylistId()
        a.model.submit(Edit.CreatePlaylist(pid, 0, "Teste", false), "p")
        a.model.submit(Edit.AddToPlaylist(pid, listOf(id(a, "Night Signal"), id(a, "Copper Sky"))), "p")
        a.sets.open(pid)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        // Am → Bm: distância 2 na roda.
        rule.onNodeWithText("⚠ 1").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-21-vertical-set-aviso.png").path)
        // O aviso por extenso só ao tocar no ⚠ da troca; a ponte é a ação dele.
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Aviso")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Tom 8A→10A não combina", substring = true).assertExists()
        rule.onNodeWithText("Pôr uma ponte").performClick()
        rule.waitForIdle()
        captureScreenRoboImage(File(out, "mobile-22-vertical-ponte.png").path)
        rule.onAllNodes(hasText("· 9A ·", substring = true))[0].performClick()
        rule.waitForIdle()
        val ids = a.sets.ids()
        assertEquals(3, ids.size)
        assertEquals(id(a, "Night Signal"), ids.first())
        assertEquals(id(a, "Copper Sky"), ids.last())
        assertEquals("Em", a.model.view!!.track(ids[1])!!.key)
        assertTrue(a.sets.issues(ids, a.sets.plan()).isEmpty(), "trocas ok")
        // Um desfazer tira a ponte inteira.
        a.model.undo()
        a.model.undo()
        assertEquals(2, a.sets.ids().size)
    }

    @Test fun sortPutsTheTracksInMixingOrderKeepingTheOpener() {
        val a = app()
        val pid = a.model.nextPlaylistId()
        val order = listOf("Night Signal", "Concrete Garden", "Small Hours", "Afterhours", "Glass Harbour").map { id(a, it) }
        a.model.submit(Edit.CreatePlaylist(pid, 0, "Bagunça", false), "p")
        a.model.submit(Edit.AddToPlaylist(pid, order), "p")
        a.sets.open(pid)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Mais")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Ordenar para mixar").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Fluido").performClick()
        rule.waitForIdle()
        val ids = a.sets.ids()
        assertEquals(order.first(), ids.first(), "a de abertura fica")
        assertEquals(SetBuilder.order(a.model.view!!, order, first = order.first()), ids)
        fun cost(x: List<Int>) = x.zipWithNext().sumOf { (p, q) -> SetBuilder.cost(a.model.view!!.track(p)!!, a.model.view!!.track(q)!!) }
        assertTrue(cost(ids) < cost(order), "${cost(ids)} < ${cost(order)}")
    }

    @Test fun rehearsalPlaysEachTransitionAndKeepsTheMarks() {
        val a = app()
        org.robolectric.shadows.ShadowMediaPlayer.setMediaInfoProvider { org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(60_000, 0) }
        val pid = a.model.nextPlaylistId()
        a.model.submit(Edit.CreatePlaylist(pid, 0, "Ensaio", false), "p")
        a.model.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour").map { id(a, it) }), "p")
        a.sets.open(pid)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.onNodeWithText("ENSAIAR").performClick()
        waitJob(a)
        assertEquals(0, a.sets.rehearsal)
        assertTrue(a.preview.isPlaying, "a primeira transição tocando")
        rule.onRoot().captureRoboImage(File(out, "mobile-23-vertical-ensaio.png").path)
        rule.onNodeWithText("✓ BOA").performClick()
        waitJob(a)
        assertEquals(1, a.sets.rehearsal)
        rule.onNodeWithText("✗ REFAZER").performClick()
        waitJob(a)
        assertEquals(null, a.sets.rehearsal)
        assertTrue(!a.preview.isPlaying, "o ensaio terminou e parou")
        assertEquals("Ensaio: 1 boas · 1 a refazer", a.notice?.text)
        val marks = a.sets.plan().map { it.mark }
        assertEquals(listOf(Transition.Mark.GOOD, Transition.Mark.REDO), marks)
        // Guardado: reabrir o SET mostra as marcas; mudar a transição apaga a marca dela.
        a.sets.close()
        a.sets.open(pid)
        assertEquals(marks, a.sets.plan().map { it.mark })
        a.sets.openMix(a.sets.plan()[1])
        a.sets.editMix(a.sets.plan()[1].copy(bars = 8))
        waitJob(a)
        assertEquals(null, a.sets.plan()[1].mark)
    }
}
