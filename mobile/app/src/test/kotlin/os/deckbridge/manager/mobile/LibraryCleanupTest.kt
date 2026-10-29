package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.deckbridge.logic.encodeWavMono16
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.organize.Rule as OrgRule
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch

/**
 * Arrumar a biblioteca (Robolectric): nomes que entraram sujos (o número da fila de importação, sublinhados, artista no
 * título) viram "Artista - Título" em MÚSICAS → ⋯ → Arrumar nomes; a mesma música importada duas vezes com nomes diferentes aparece em
 * Procurar repetidas pelo áudio, e JUNTAR deixa uma, nas playlists da outra.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class LibraryCleanupTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "arrumar-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40); it.hideGuide() }
    }

    private fun idleUntil(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 120_000
        while (!done() && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        rule.waitForIdle()
        assertTrue(done(), what)
    }

    @Test fun dirtyNamesBecomeArtistAndTitle() {
        val a = app()
        val v = a.model.view!!
        val night = v.tracks.first { it.title == "Night Signal" }
        val glass = v.tracks.first { it.title == "Glass Harbour" }
        // Como chegaram da importação até a 0.24.0.
        a.model.submit(Edit.SetTracks(listOf(night.id), TrackPatch(title = "0003-Aurora_Drive_-_Night_Signal_(Official_Video)", artist = "")), "sujo")
        a.model.submit(Edit.SetTracks(listOf(glass.id), TrackPatch(artist = "0000-Aurora Drive")), "sujo")
        assertNotNull(a.model.apply(null))
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        // Nas ferramentas da própria biblioteca (o ⋯ de MÚSICAS), não no MENU.
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Ferramentas da biblioteca")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Arrumar nomes").performClick()
        rule.waitForIdle()
        val tidy = assertNotNull(a.tidy)
        assertTrue(tidy.none { it.rule == OrgRule.DUPLICATE }, "repetidas têm tela própria")
        rule.onRoot().captureRoboImage(File(out, "mobile-63-vertical-arrumar-nomes.png").path)
        rule.onAllNodesWithText("Aplicar", substring = true)[0].performClick()
        rule.waitForIdle()
        val now = a.model.view!!
        assertEquals("Night Signal" to "Aurora Drive", now.track(night.id)!!.let { it.title to it.artist })
        assertEquals("Aurora Drive", now.track(glass.id)!!.artist)
    }

    /** Uma "música" sintética com melodia que muda, bumbo e ruído (o clique da demonstração não tem o que comparar). */
    private fun song(seconds: Int, rate: Int, lead: Int = 0, gain: Float = 1f): FloatArray {
        val rnd = java.util.Random(3)
        val notes = DoubleArray(seconds * 2) { 200.0 * Math.pow(2.0, rnd.nextInt(24) / 12.0) }
        var noise = 9
        return FloatArray((seconds + lead) * rate) { i ->
            val t = i.toDouble() / rate - lead
            if (t < 0) 0f else {
                val f = notes[(t * 2).toInt().coerceAtMost(notes.lastIndex)]
                noise = noise * 1103515245 + 12345
                val n = ((noise ushr 16) and 0x7FFF) / 16384.0 - 1.0
                (gain * (0.3 * sin(2 * PI * f * t) + 0.15 * sin(2 * PI * f * 1.5 * t) + 0.5 * Math.exp(-(t % 0.5) * 30) * sin(2 * PI * 60 * t) + 0.02 * n)).toFloat()
            }
        }
    }

    @Test fun theSameSongWithAnotherNameIsFoundAndMerged() {
        val a = app()
        val dir = File(a.root.parentFile, "origem2").apply { mkdirs() }
        val f1 = File(dir, "Nara Vale - Night Bus.wav").apply { writeBytes(encodeWavMono16(song(40, 22050), 22050)) }
        val f2 = File(dir, "02_nb_final_master.wav").apply { writeBytes(encodeWavMono16(song(40, 22050, lead = 1, gain = 0.8f), 22050)) }
        for (f in listOf(f1, f2)) {
            val p = ImportPrep.prepare(f)
            a.model.submit(Edit.ImportTrack(p, p.info.title, p.info.artist, "", "", p.read.keyName, 0, 0, p.suggestedCues), "importar")
        }
        assertNotNull(a.model.apply(null))
        val v = a.model.view!!
        val one = v.tracks.first { it.title == "Night Bus" }
        assertEquals("Nara Vale", one.artist, "nome limpo já na importação")
        val two = v.tracks.first { it.title == "Nb Final Master" }
        a.model.submit(Edit.AddToPlaylist(1, listOf(two.id)), "p")
        assertNotNull(a.model.apply(null))
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Ferramentas da biblioteca")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Procurar repetidas").performClick()
        idleUntil("ouviu e comparou") { a.job == null && a.duplicates != null }
        val g = assertNotNull(a.duplicates).single()
        assertEquals(setOf(one.id, two.id), g.tracks.map { it.id }.toSet())
        assertTrue(g.sameAudio)
        // A que está numa playlist fica sugerida.
        assertEquals(two.id, g.keep.id)
        rule.onRoot().captureRoboImage(File(out, "mobile-64-vertical-repetidas.png").path)
        // O DJ prefere a de nome certo: toca nela e junta.
        rule.onNodeWithText("Night Bus").performClick()
        rule.onNodeWithText("JUNTAR").performClick()
        rule.waitForIdle()
        val after = a.model.view!!
        assertTrue(after.track(two.id) == null, "a repetida saiu")
        assertTrue(one.id in after.entries[1].orEmpty(), "a que ficou entrou na playlist da outra")
        // As impressões ficaram fora da biblioteca.
        assertTrue(File(a.root.parentFile, "digitais").listFiles().orEmpty().isNotEmpty())
        // E um desfazer volta tudo.
        a.model.undo()
        assertNotNull(a.model.view!!.track(two.id))
    }
}
