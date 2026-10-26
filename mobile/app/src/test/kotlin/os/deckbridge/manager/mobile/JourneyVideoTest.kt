package os.deckbridge.manager.mobile

import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.time.Duration
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * Grava a jornada inteira do app com músicas do usuário (fora do repositório): `-PjourneyVideo=<pasta>` com `in/`
 * (áudios "Artista - Título.ext"). Importa, marca na triagem, monta a playlist, planeja e ouve a transição, exporta para
 * um pendrive (provedor de documentos de brinquedo). A caixa de nome da playlist não aparece (ver o passo 3). Cada toque é um toque de verdade na tela (Compose), anotado em
 * `out/taps.tsv` para o vídeo mostrar onde foi; `out/steps.tsv` tem as legendas. Quadros a 10 por segundo em
 * `out/frames`. É a tela renderizada em JVM (Robolectric), não o aparelho: o seletor de arquivos do sistema não aparece
 * (o toque em IMPORTAR entrega os arquivos direto), e MP3 passa pelo ffmpeg em vez do MediaCodec.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-xhdpi", sdk = [33])
class JourneyVideoTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val frameMs = 100L
    private var n = 0
    private lateinit var frames: File
    private val taps = StringBuilder()
    private val steps = StringBuilder()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Avança o relógio da tela (que aqui não anda sozinho: o cursor piscando nunca deixaria a tela "ociosa"). */
    private fun tick(ms: Long = 120) {
        // Mudanças feitas daqui (fora da composição) só chegam à tela depois de avisadas.
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
        idle()
        rule.waitForIdle()
    }

    /** Captura a tela inteira (com diálogos) e repete o quadro por [seconds]. */
    private fun hold(seconds: Double) {
        tick()
        val first = File(frames, "%05d.png".format(n++))
        captureScreenRoboImage(first.path)
        repeat(((seconds * 1000 / frameMs).toInt() - 1).coerceAtLeast(0)) { first.copyTo(File(frames, "%05d.png".format(n++)), overwrite = true) }
    }

    private fun step(text: String) = steps.append("$n\t$text\n")

    /** Toca no nó: marca o ponto (o vídeo desenha o toque nos quadros de antes) e clica. */
    private fun tap(node: SemanticsNodeInteraction, before: Double = 0.6) {
        val b = node.fetchSemanticsNode().boundsInRoot
        taps.append("$n\t${b.center.x.toInt()}\t${b.center.y.toInt()}\n")
        hold(before)
        node.performClick()
        tick(300)
    }

    private fun tapText(text: String, last: Boolean = true, substring: Boolean = false) {
        val all = rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes()
        check(all.isNotEmpty()) { "sem \"$text\" na tela" }
        tap(rule.onAllNodes(hasText(text, substring = substring))[if (last) all.lastIndex else 0])
    }

    /** O "+" da linha da faixa [title] na lista. */
    private fun tapPlusOf(title: String) {
        val y = rule.onAllNodes(hasText(title)).fetchSemanticsNodes().first().boundsInRoot.center.y
        val pluses = rule.onAllNodes(hasText("+")).fetchSemanticsNodes()
        val i = pluses.indices.minByOrNull { kotlin.math.abs(pluses[it].boundsInRoot.center.y - y) }!!
        tap(rule.onAllNodes(hasText("+"))[i], before = 0.5)
    }

    /** Enquanto uma tarefa roda, um quadro a cada ~0,7 s de verdade, mostrado por 0,3 s (acelerado). */
    private fun whileJob(app: ManagerApp, minimum: Double = 1.0) {
        var shown = 0.0
        while (app.job != null) {
            Thread.sleep(700)
            idle()
            hold(0.3)
            shown += 0.3
        }
        idle()
        if (shown < minimum) hold(minimum - shown)
    }

    @Test fun recordJourney() {
        val base = System.getProperty("journey.video").orEmpty()
        assumeTrue("sem -PjourneyVideo", base.isNotEmpty())
        val songs = File(base, "in").listFiles()!!.filter { it.extension.lowercase() in setOf("mp3", "wav", "flac", "m4a") }.sortedBy { it.name }
        val out = File(base, "out").apply { mkdirs() }
        frames = File(out, "frames").apply { deleteRecursively(); mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Pendrive de brinquedo, vazio.
        val usbBase = File(out, "usb").apply { deleteRecursively(); mkdirs() }
        File(usbBase, "USB DJ").mkdirs()
        FakeDocumentsProvider.base = usbBase
        FakeDocumentsProvider.failRenameTo = null
        Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(ProviderInfo().apply {
            authority = FakeDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
        })
        val tree = DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, "USB DJ")

        val app = ManagerApp(ctx, File(out, "lib/biblioteca/dados").apply { parentFile!!.parentFile!!.deleteRecursively() })
        app.start()
        val actions = Actions(
            pickAudio = { app.importAudio(songs.map { Uri.fromFile(it) }) },
            pickUsb = { app.chooseUsb(tree) },
        )
        rule.mainClock.autoAdvance = false
        rule.setContent { ManagerRoot(app, actions) }
        tick()

        // 1. Importar.
        step("1 · Importar as músicas")
        hold(2.0)
        tapText("IMPORTAR MÚSICAS")
        whileJob(app)
        app.notice = null
        hold(2.5)
        val v0 = app.model.view!!
        fun idOf(word: String) = v0.tracks.first { it.title.contains(word, ignoreCase = true) }.id

        // 2. Marcar: triagem, uma nota por faixa (a triagem passa sozinha para a próxima).
        step("2 · Marcar: nota em cada faixa")
        val stars = mapOf("Imagination" to 5, "See You Again" to 5, "Shinjuku" to 4, "How Does It Feel" to 4, "Fuck It" to 3)
        tapText("MARCAR")
        hold(1.2)
        while (app.triage != null) {
            val id = app.triage!![app.triageAt]
            val title = app.model.view!!.track(id)!!.title
            val r = stars.entries.firstOrNull { title.contains(it.key, ignoreCase = true) }?.value ?: 3
            hold(1.0)
            tap(rule.onAllNodes(hasText("☆"))[r - 1], before = 0.5)
            idle()
        }
        app.notice = null
        hold(1.5)

        // 3. Montar a playlist: criar, tocar no ＋ dela e pôr as faixas na ordem do set.
        step("3 · Montar a playlist")
        tapText("PLAYLISTS")
        hold(0.8)
        // A caixa de nome (campo de texto numa janela de diálogo) não fica "ociosa" no Robolectric — nem a do Material sem
        // nada do app —, então a playlist é criada direto, como o "Criar" faria.
        check(app.model.submit(os.deckbridge.manager.pdb.Edit.CreatePlaylist(app.model.nextPlaylistId(), 0, "Sexta à noite", false), "Criar playlist"))
        app.model.openPlaylist = app.model.view!!.playlists.first { it.name == "Sexta à noite" }.id
        hold(1.2)
        tapText("＋")
        hold(1.0)
        val order = listOf("Imagination", "See You Again", "How Does It Feel", "Shinjuku")
        for (word in order) tapPlusOf(v0.track(idOf(word))!!.title)
        app.notice = null
        hold(1.0)
        tapText("PLAYLISTS")
        hold(0.6)
        tapText("Sexta à noite", last = false)
        hold(2.0)

        // 4. Planejar a mixagem: o SET da playlist e o teste da primeira transição, tocando.
        step("4 · Planejar a mixagem")
        tapText("MIXAR")
        hold(2.5)
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
        val playTap = rule.onAllNodes(hasText("▶ PLAY"))[0]
        val b = playTap.fetchSemanticsNode().boundsInRoot
        taps.append("$n\t${b.center.x.toInt()}\t${b.center.y.toInt()}\n")
        hold(0.6)
        // Tocando: o relógio da tela é avançado à mão e a cabeça vai ao instante exato de cada quadro (o do áudio).
        app.sets.playMix()
        val audioFrame = n
        repeat(((endMs - startMs) / frameMs).toInt()) { k ->
            rule.mainClock.advanceTimeBy(frameMs)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(frameMs))
            app.sets.cursorMs = startMs + ((k + 1) * frameMs).toInt()
            val f = File(frames, "%05d.png".format(n++))
            captureScreenRoboImage(f.path)
        }
        // Sai pelo ✕ da tela de teste, como o usuário: é ele que para o som.
        val audioEnd = n + 6
        tapText("✕")
        check(!app.preview.isPlaying) { "saiu da mixagem e continuou tocando" }
        hold(1.0)
        tapText("‹")
        hold(0.8)

        // 5. Exportar para o pendrive.
        step("5 · Exportar para o pendrive")
        tapText("PENDRIVE")
        hold(1.2)
        tapText("ESCOLHER")
        whileJob(app, minimum = 0.6)
        hold(1.5)
        tapText("EXPORTAR", substring = true)
        whileJob(app)
        app.notice = null
        hold(3.5)

        File(out, "taps.tsv").writeText(taps.toString())
        File(out, "steps.tsv").writeText(steps.toString())
        File(out, "info.txt").writeText(
            "quadros\t$n\nms_por_quadro\t$frameMs\naudio_quadro\t$audioFrame\naudio_fim_quadro\t$audioEnd\naudio_inicio_ms\t$startMs\n" +
                "pendrive\t${File(usbBase, "USB DJ/PIONEER/rekordbox/export.pdb").length()}\n" +
                app.model.view!!.tracks.joinToString("") { "faixa\t${it.title}\t${"%.2f".format(it.bpm)}\t${it.key}\t${it.rating}\n" },
        )
        check(File(usbBase, "USB DJ/PIONEER/rekordbox/export.pdb").isFile) { "o export não foi para o pendrive" }
    }
}
