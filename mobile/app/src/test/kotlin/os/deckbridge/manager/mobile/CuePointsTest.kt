package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
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
import os.deckbridge.manager.anlz.Cue
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.pdb.Edit

/**
 * Os pontos de interesse viram cues já na importação (intro, drop, break, outro…), e "Marcar pontos" põe nas que já
 * estavam na biblioteca sem tirar os cues do DJ (Robolectric, faixa sintética de estrutura conhecida).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class CuePointsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    /** Intro 16 (bumbo e chimbal), drop 32 (bumbo, baixo, acorde), break 16 (só acorde), drop 32, outro 16, a 124 BPM. */
    private fun structured(rate: Int = 22050): FloatArray {
        val parts = listOf(16 to "intro", 32 to "drop", 16 to "break", 32 to "drop", 16 to "outro")
        val bpm = 124.0
        val barSec = 240.0 / bpm
        val beatSec = 60.0 / bpm
        val out = FloatArray((parts.sumOf { it.first } * barSec * rate).toInt() + rate)
        var noise = 7
        var bar = 0
        for ((n, kind) in parts) {
            for (b in 0 until n) {
                val start = (bar + b) * barSec
                for (i in (start * rate).toInt() until ((start + barSec) * rate).toInt().coerceAtMost(out.size)) {
                    val t = i.toDouble() / rate
                    val inBeat = (t - start) % beatSec
                    var x = 0.0
                    if (kind != "break") {
                        x += 0.9 * exp(-inBeat * 20.0) * sin(2 * PI * (50.0 + 80.0 * exp(-inBeat * 40.0)) * inBeat)
                        noise = noise * 1103515245 + 12345
                        val off = (inBeat - beatSec / 2).let { if (it < 0) it + beatSec else it }
                        x += 0.12 * (((noise ushr 16) and 0x7FFF) / 16384.0 - 1.0) * exp(-off * 60.0)
                    }
                    if (kind == "drop") x += 0.25 * (1 - exp(-inBeat * 10.0)) * sin(2 * PI * 55.0 * t)
                    if (kind == "drop" || kind == "break") x += 0.08 * (sin(2 * PI * 220.0 * t) + sin(2 * PI * 277.0 * t) + sin(2 * PI * 330.0 * t))
                    out[i] = x.toFloat()
                }
            }
            bar += n
        }
        return out
    }

    @Test fun importMarksThePointsAndMarkPointsKeepsTheDjsCues() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ManagerApp(ctx, File(ctx.cacheDir, "pontos-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 12); it.hideGuide() }
        val f = File(a.root.parentFile, "origem3").apply { mkdirs() }.let { File(it, "Nara Vale - Estrutura.wav") }
        f.writeBytes(encodeWavMono16(structured(), 22050))
        val p = ImportPrep.prepare(f)
        // Já na importação: memória em cada seção e os pontos chave como hot cues.
        val names = p.suggestedCues.filter { it.isHot }.map { it.comment }
        assertTrue(names.containsAll(listOf("Intro", "Drop", "Break", "Drop 2", "Outro")), names.toString())
        assertTrue(p.suggestedCues.count { !it.isHot } >= 5)
        a.model.submit(Edit.ImportTrack(p, p.info.title, p.info.artist, "", "", p.read.keyName, 0, 0, p.suggestedCues), "importar")
        assertNotNull(a.model.apply(null))
        val t = a.model.view!!.tracks.first { it.title == "Estrutura" }
        assertEquals(p.suggestedCues.size, a.model.trackGrid(t).cues.size)

        // Uma faixa que já estava: o DJ tinha só um cue dele. Marcar pontos acrescenta sem tirar o dele.
        val mine = Cue(1, 5_000, comment = "meu")
        a.model.submit(Edit.SetCues(t.id, listOf(mine)), "só o meu")
        assertNotNull(a.model.apply(null))
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Ferramentas da biblioteca")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Marcar pontos (cues)").performClick()
        val until = System.currentTimeMillis() + 180_000
        while (a.job != null && System.currentTimeMillis() < until) {
            Thread.sleep(50)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        rule.waitForIdle()
        val now = a.model.trackGrid(a.model.view!!.track(t.id)!!).cues
        assertTrue(mine in now, "o cue do DJ ficou")
        assertTrue(now.any { it.comment == "Drop" } && now.any { it.comment == "Outro" }, now.toString())
        assertEquals(now.filter { it.isHot }.size, now.filter { it.isHot }.map { it.hot }.distinct().size)
        // Os cues aparecem na faixa.
        a.detailId = t.id
        a.detailPage = DetailPage.CUES
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(out, "mobile-70-vertical-cues-pontos.png").path)
    }
}
