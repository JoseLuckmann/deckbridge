package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.time.Duration
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.pdb.Edit

/**
 * Grava os quadros de um teste de mixagem com músicas de verdade do usuário, que ficam fora do repositório:
 * `-PmixVideo=<pasta>` com `in/` (WAVs "Artista - Título.wav"). Importa e analisa como o app, escolhe o par que melhor
 * combina, abre o SET e o teste de mixagem e toca a prévia, capturando a tela a 15 quadros por segundo; o áudio que
 * acompanha é a própria prévia montada. Os quadros vão para `out/frames`, e `out/info.txt` diz o que juntar.
 * `mobile/tools/montar-video-mix.sh <pasta>` junta quadros e áudio num MP4 (ffmpeg). Sem a pasta, não roda. O player é o simulado do Robolectric e a tela é renderizada em JVM, não no aparelho.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-xhdpi")
class MixVideoTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val fps = 15
    private val frameMs = 1000L / fps

    @Test fun recordMixTest() {
        val base = System.getProperty("mix.video").orEmpty()
        assumeTrue("sem -PmixVideo", base.isNotEmpty())
        val inDir = File(base, "in")
        val out = File(base, "out").apply { mkdirs() }
        val frames = File(out, "frames").apply { deleteRecursively(); mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = ManagerApp(ctx, File(out, "lib/biblioteca/dados").apply { parentFile!!.parentFile!!.deleteRecursively() }).also { it.setListView(true) }
        app.start()
        app.hideGuide()

        val songs = inDir.listFiles { f -> f.extension.lowercase() == "wav" }!!.sortedBy { it.name }
        val edits = songs.mapIndexed { n, f ->
            val p = ImportPrep.prepare(f)
            val (artist, title) = f.nameWithoutExtension.split(" - ", limit = 2).let { if (it.size == 2) it[0] to it[1] else "" to it[0] }
            Edit.ImportTrack(p, title, artist, "", "House", p.read.keyName, 0, n + 1, p.suggestedCues) to "Importar $title"
        }
        check(app.model.submitAll(edits) == songs.size) { app.model.banner?.text.orEmpty() }
        check(app.model.apply(null) != null) { app.model.banner?.text.orEmpty() }
        val v = app.model.view!!
        val log = StringBuilder()
        v.tracks.forEach { log.append("faixa\t${it.title}\t${"%.2f".format(it.bpm)}\t${it.key}\t${it.durationSeconds}s\n") }

        // O par que melhor combina: tom igual ou vizinho e menor diferença de andamento.
        val pairs = v.tracks.flatMap { a -> v.tracks.filter { it.id != a.id }.map { a to it } }
        val (a, b) = pairs.minByOrNull { (x, y) ->
            val f = SetPlanner.fit(x, y)
            (if (f.good) 0.0 else 100.0) + (f.keyDistance ?: 9) * 3 + kotlin.math.abs(f.bpmDiffPct)
        }!!
        log.append("par\t${a.title} -> ${b.title}\t${SetPlanner.fit(a, b)}\n")
        app.model.submit(Edit.CreatePlaylist(1, 0, "Teste de mix", false), "p")
        app.model.submit(Edit.AddToPlaylist(1, listOf(a.id, b.id)), "p")
        check(app.model.apply(null) != null)

        var n = 0
        fun shot(times: Int = 1) {
            val first = File(frames, "%05d.png".format(n++))
            rule.onRoot().captureRoboImage(first.path)
            repeat(times - 1) { first.copyTo(File(frames, "%05d.png".format(n++)), overwrite = true) }
        }
        fun idle(ms: Long) {
            rule.mainClock.advanceTimeBy(ms)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        }

        rule.mainClock.autoAdvance = false
        app.tab = Tab.PLAYLISTS
        app.model.openPlaylist = 1
        app.sets.open(1)
        rule.setContent { ManagerRoot(app, Actions({}, {})) }
        idle(100)
        shot(fps * 3) // a tela do SET, 3 s

        val tr = app.sets.plan().first()
        log.append("transicao\t$tr\n")
        app.sets.openMix(tr)
        idle(50)
        shot(fps / 2) // montando
        val until = System.currentTimeMillis() + 300_000
        while (app.sets.mixBusy && System.currentTimeMillis() < until) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val p = checkNotNull(app.sets.mixPreview) { app.sets.mixError.orEmpty() }
        val startMs = (p.leadMs - 4 * p.barMsA).toInt().coerceAtLeast(0)
        val endMs = (p.leadMs + p.overlapMs + 4 * p.barMsA).toInt().coerceAtMost(p.lengthMs)
        app.sets.seekMix(startMs)
        idle(frameMs)
        shot(fps) // parado no ponto de partida, 1 s
        val intro = n

        val file = checkNotNull(app.sets.mixFile)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), ShadowMediaPlayer.MediaInfo(p.lengthMs * 4, 0))
        app.sets.playMix()
        check(app.sets.mixPlaying) { "não começou a tocar" }
        val total = ((endMs - startMs) / frameMs).toInt()
        // O relógio do player simulado não anda junto com os quadros: a cabeça vai para o instante exato de cada quadro,
        // o mesmo do áudio que acompanha o vídeo.
        repeat(total) { k ->
            idle(frameMs)
            app.sets.cursorMs = startMs + ((k + 1) * frameMs).toInt()
            shot()
        }
        file.copyTo(File(out, "mix.wav"), overwrite = true)
        log.append("quadros\t$n\tfps\t$fps\tintro\t$intro\n")
        log.append("audio\t${file.absolutePath}\tinicio_ms\t$startMs\tfim_cursor_ms\t${app.sets.cursorMs}\n")
        app.sets.closeMix()
        File(out, "info.txt").writeText(log.toString())
    }
}
