package os.deckbridge.manager.mobile

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.time.Duration
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * Grava a montagem de um set com músicas do usuário (fora do repositório): `-PjourneyVideo=<pasta>` com `in/` (áudios
 * "Artista - Título.ext"). Importa e marca, monta o set pela tela (MONTAR SET), fixa o master e ouve a primeira
 * transição (o som sai de `out/mix.wav`), cria um ramo no ponto de virada, faz do fim do set uma pool escolhida na hora
 * e abre o mapa. Mesma ferramenta de montagem do vídeo da jornada. É a tela renderizada em JVM (Robolectric), não o
 * aparelho.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-xhdpi", sdk = [33])
class SetVideoTest : VideoTestBase() {
    @Test fun recordSetBuilding() {
        val base = System.getProperty("journey.video").orEmpty()
        assumeTrue("sem -PjourneyVideo", base.isNotEmpty())
        val songs = File(base, "in").listFiles()!!.filter { it.extension.lowercase() in setOf("mp3", "wav", "flac", "m4a") }.sortedBy { it.name }
        val out = File(base, "out").apply { mkdirs() }
        frames = File(out, "frames").apply { deleteRecursively(); mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

        val app = ManagerApp(ctx, File(out, "lib/biblioteca/dados").apply { parentFile!!.parentFile!!.deleteRecursively() })
        app.start()
        val actions = Actions(pickAudio = { app.importAudio(songs.map { Uri.fromFile(it) }) }, pickUsb = {})
        rule.mainClock.autoAdvance = false
        rule.setContent { ManagerRoot(app, actions) }
        tick()

        // 1. Importar e marcar.
        step("1 · Importar e marcar")
        hold(1.5)
        tapText("IMPORTAR MÚSICAS")
        whileJob(app)
        app.notice = null
        hold(1.5)
        val stars = mapOf("Imagination" to 5, "See You Again" to 5, "Shinjuku" to 4, "How Does It Feel" to 4, "Fuck It" to 3)
        tapText("MARCAR")
        hold(1.0)
        while (app.triage != null) {
            val title = app.model.view!!.track(app.triage!![app.triageAt])!!.title
            val r = stars.entries.firstOrNull { title.contains(it.key, ignoreCase = true) }?.value ?: 3
            hold(0.7)
            tap(rule.onAllNodes(hasText("☆"))[r - 1], before = 0.4)
            idle()
        }
        app.notice = null
        hold(1.0)

        // 2. Montar o set: das de nota 4 ou mais, na ordem que o app escolhe pelas trocas de tom e andamento.
        step("2 · Montar o set")
        tapText("PLAYLISTS")
        hold(1.0)
        tapText("MONTAR SET")
        hold(1.5)
        tapText("★4+")
        hold(1.5)
        tapText("MONTAR")
        app.notice = null
        hold(3.0)

        // 3. O master manda: fixa 130 e todas as faixas tocam nele (o quanto cada uma estica aparece na linha).
        step("3 · O master manda no andamento")
        tapText("MASTER AUTO")
        hold(1.0)
        tapText("130 BPM")
        app.notice = null
        hold(3.0)

        // 4. Ouvir a primeira transição, já no master.
        step("4 · Ouvir a transição")
        tap(rule.onAllNodes(hasText("MIX"))[0])
        val until = System.currentTimeMillis() + 300_000
        while (app.sets.mixBusy && System.currentTimeMillis() < until) {
            Thread.sleep(600)
            idle()
            hold(0.3)
        }
        idle()
        val p = checkNotNull(app.sets.mixPreview) { app.sets.mixError.orEmpty() }
        val startMs = (p.leadMs - 4 * p.barMsA).toInt().coerceAtLeast(0)
        val endMs = (p.leadMs + p.overlapMs + 2 * p.barMsA).toInt().coerceAtMost(p.lengthMs)
        app.sets.seekMix(startMs)
        hold(1.0)
        val file = checkNotNull(app.sets.mixFile)
        file.copyTo(File(out, "mix.wav"), overwrite = true)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), ShadowMediaPlayer.MediaInfo(p.lengthMs * 4, 0))
        val b = rule.onAllNodes(hasText("▶ PLAY"))[0].fetchSemanticsNode().boundsInRoot
        taps.append("$n\t${b.center.x.toInt()}\t${b.center.y.toInt()}\n")
        hold(0.6)
        app.sets.playMix()
        val audioFrame = n
        repeat(((endMs - startMs) / frameMs).toInt()) { k ->
            rule.mainClock.advanceTimeBy(frameMs)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(frameMs))
            app.sets.cursorMs = startMs + ((k + 1) * frameMs).toInt()
            captureScreenRoboImage(File(frames, "%05d.png".format(n++)).path)
        }
        val audioEnd = n + 6
        tapText("✕")
        check(!app.preview.isPlaying) { "saiu da mixagem e continuou tocando" }
        hold(1.0)
        tapText("‹")
        hold(1.0)

        // 5. Ponto de virada: um ramo que sai da 2ª faixa e, se der, volta mais adiante.
        step("5 · Ponto de virada: um ramo")
        tap(rule.onAllNodes(hasContentDescription("Ponto de virada"))[1])
        hold(1.0)
        tapText("Criar ramo daqui")
        hold(1.5)
        tapText("Manter")
        hold(1.0)
        tapText("CRIAR")
        app.notice = null
        hold(3.5)
        tapText("‹")
        hold(2.5)

        // 6. Pool: do ponto de virada em diante, as faixas ficam sem ordem e escolho na hora qual vem.
        step("6 · Pool: escolher na hora")
        tap(rule.onAllNodes(hasContentDescription("Ponto de virada"))[1])
        hold(1.0)
        tapText("Pool depois desta")
        app.notice = null
        hold(2.5)
        tap(rule.onAllNodes(hasText("AGORA"))[0])
        hold(3.0)
        tap(rule.onAllNodes(hasText("AGORA"))[1], before = 0.8)
        hold(3.0)

        // 7. O mapa: a linha principal e o ramo, com o andamento de cada um.
        step("7 · O mapa do set")
        tap(rule.onNode(hasContentDescription("Mapa do set")))
        hold(4.0)
        tapText("B")
        hold(4.0)

        File(out, "taps.tsv").writeText(taps.toString())
        File(out, "steps.tsv").writeText(steps.toString())
        File(out, "info.txt").writeText(
            "quadros\t$n\nms_por_quadro\t$frameMs\naudio_quadro\t$audioFrame\naudio_fim_quadro\t$audioEnd\naudio_inicio_ms\t$startMs\n" +
                app.model.view!!.tracks.joinToString("") { "faixa\t${it.title}\t${"%.2f".format(it.bpm)}\t${it.key}\t${it.rating}\n" },
        )
    }
}
