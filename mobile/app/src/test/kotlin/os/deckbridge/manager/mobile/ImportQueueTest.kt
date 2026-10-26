package os.deckbridge.manager.mobile

import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import os.deckbridge.logic.click
import os.deckbridge.logic.encodeWavMono16
import os.deckbridge.manager.pdb.Edit

/** Importação em segundo plano: a tela fica livre, cada música entra ao ficar pronta, e o que se faz no meio fica. */
@RunWith(RobolectricTestRunner::class)
class ImportQueueTest {
    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "imp-${System.nanoTime()}/biblioteca/dados")).also { it.start() }
    }

    private fun songs(dir: File, n: Int): List<Uri> = (0 until n).map { k ->
        File(dir, "Artista $k - Faixa $k.wav").apply {
            parentFile!!.mkdirs()
            writeBytes(encodeWavMono16(click(bpm = 120.0 + k, seconds = 12, rate = 22050, freq = 200.0 + k * 50), 22050))
        }.let { Uri.fromFile(it) }
    }

    /** Deixa a thread da tela andar (é nela que cada música entra na lista) até a importação terminar. */
    private fun waitImport(a: ManagerApp, during: (() -> Unit)? = null) {
        var once = during
        val until = System.currentTimeMillis() + 120_000
        while (a.imports.running && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            if (once != null && a.model.view!!.tracks.isNotEmpty()) {
                once()
                once = null
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(a.imports.running, "a importação terminou")
    }

    @Test fun importsInTheBackgroundWhileTheScreenIsFree() {
        val a = app()
        val dir = File(a.root.parentFile, "origem")
        a.importAudio(songs(dir, 5))
        assertTrue(a.imports.running, "já aparece como importando")
        assertNull(a.job, "sem tela de espera: a tela fica livre")
        // No meio da importação, com a primeira música já na lista: cria uma playlist e põe ela.
        waitImport(a) {
            val pid = a.model.nextPlaylistId()
            assertTrue(a.model.submit(Edit.CreatePlaylist(pid, 0, "Enquanto importa", false), "p"))
            assertTrue(a.model.submit(Edit.AddToPlaylist(pid, listOf(a.model.view!!.tracks.first().id)), "p"))
        }
        val v = a.model.view!!
        assertEquals(5, v.tracks.size)
        assertTrue(v.tracks.all { it.bpm > 0 }, "analisadas")
        assertEquals(1, v.entries[v.playlists.first { it.name == "Enquanto importa" }.id]!!.size, "o que foi feito durante a importação ficou")
        assertTrue(a.notice!!.text.startsWith("5 importadas"), a.notice!!.text)
        // Tudo gravado: a biblioteca relida do disco tem as 5 e a playlist; o cache da importação foi apagado.
        assertFalse(File(a.context.cacheDir, "importar").exists())
        a.autosave()
        val until = System.currentTimeMillis() + 30_000
        while (a.model.saving && System.currentTimeMillis() < until) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle() }
        val again = ManagerApp(a.context, a.root).also { it.start() }
        assertEquals(5, again.model.view!!.tracks.size)
        assertTrue(again.model.view!!.playlists.any { it.name == "Enquanto importa" })
        assertTrue(v.tracks.all { File(a.root, it.filePath.trimStart('/')).isFile }, "áudio copiado para a biblioteca")

        // As mesmas de novo: todas puladas.
        a.importAudio(songs(dir, 5))
        waitImport(a)
        assertEquals(5, a.model.view!!.tracks.size)
        assertTrue(a.notice!!.text.contains("5 já estavam"), a.notice!!.text)
    }

    @Test fun stoppingKeepsWhatAlreadyCameIn() {
        val a = app()
        a.importAudio(songs(File(a.root.parentFile, "origem"), 8))
        waitImport(a) { a.imports.cancel() }
        val n = a.model.view!!.tracks.size
        assertTrue(n in 1 until 8, "parou no meio: $n")
        assertTrue(a.notice!!.text.contains("parou"), a.notice!!.text)
        assertFalse(a.model.dirty, "o que entrou foi gravado")
    }
}
