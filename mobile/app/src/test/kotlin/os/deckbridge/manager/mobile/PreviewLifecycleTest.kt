package os.deckbridge.manager.mobile

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * Bug relatado no S25 Ultra: fechar o app deixava a pré-escuta tocando para sempre. A tela agora para o player ao sair
 * (onStop/onDestroy) e não cria um segundo estado quando é recriada. O player aqui é o simulado do Robolectric.
 *
 * Toda atividade criada aqui é destruída no fim: uma tela Compose esquecida viva faz os testes de captura seguintes
 * esperarem para sempre por "ocioso".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class PreviewLifecycleTest {
    @org.junit.Before fun fresh() = ManagerApp.resetForTests()

    private fun playFirst(app: ManagerApp) {
        if (app.model.view?.tracks.isNullOrEmpty()) DemoLibrary.build(app, seconds = 12)
        val t = app.model.view!!.tracks.first()
        val file = File(app.root, t.filePath.trimStart('/'))
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), ShadowMediaPlayer.MediaInfo(12_000, 0))
        app.preview.toggle(t)
        assertTrue(app.preview.isPlaying, "a pré-escuta devia ter começado")
    }

    @Test fun leavingTheScreenStopsThePreview() {
        val controller = Robolectric.buildActivity(ManagerActivity::class.java).setup()
        val app = controller.get().app
        playFirst(app)
        controller.pause().stop()
        assertFalse(app.preview.isPlaying, "sair do app tem de parar o som")
        controller.destroy()
    }

    @Test fun closingTheAppStopsThePreview() {
        val controller = Robolectric.buildActivity(ManagerActivity::class.java).setup()
        val app = controller.get().app
        playFirst(app)
        controller.pause().stop().destroy()
        assertFalse(app.preview.isPlaying)
    }

    @Test fun aRecreatedScreenFindsTheSameStateInsteadOfAnOrphanPlayer() {
        val first = Robolectric.buildActivity(ManagerActivity::class.java).setup()
        val app = first.get().app
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(ManagerActivity::class.java).setup()
        assertSame(app, second.get().app)
        second.pause().stop().destroy()
    }

    @Test fun stopIsSafeToCallTwiceAndWithoutPlaying() {
        val controller = Robolectric.buildActivity(ManagerActivity::class.java).setup()
        val app = controller.get().app
        app.preview.stop()
        app.preview.stop()
        assertFalse(app.preview.isPlaying)
        controller.pause().stop().destroy()
    }
}
