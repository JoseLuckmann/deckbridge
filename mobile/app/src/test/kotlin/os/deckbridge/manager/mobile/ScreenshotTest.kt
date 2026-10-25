package os.deckbridge.manager.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renderiza as telas nas dimensões do Galaxy S25 Ultra (1440×3120 px, 560 dpi ≈ 411×891 dp), em pé e deitado.
 * É uma renderização em JVM (Robolectric): confere layout, não é o aparelho.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }
    private val actions = Actions({}, {})

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(ctx.cacheDir, "demo-${System.nanoTime()}/biblioteca/dados")
        return ManagerApp(ctx, root).also { DemoLibrary.build(it) }
    }

    private fun shot(name: String, app: ManagerApp) {
        rule.setContent { ManagerRoot(app, actions) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-560dpi")
    fun portraitLibrary() = shot("mobile-01-vertical-biblioteca", app())

    @Test @Config(qualifiers = "w411dp-h891dp-port-560dpi")
    fun portraitSelecting() {
        val a = app()
        a.selecting = true
        a.model.selection = setOf(a.model.view!!.tracks[0].id, a.model.view!!.tracks[2].id)
        shot("mobile-02-vertical-selecao", a)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-560dpi")
    fun portraitDetail() {
        val a = app()
        a.detailId = a.model.view!!.tracks.first { it.title == "Night Signal" }.id
        shot("mobile-03-vertical-faixa", a)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-560dpi")
    fun portraitPlaylists() {
        val a = app()
        a.tab = Tab.PLAYLISTS
        a.model.openPlaylist = 1
        shot("mobile-04-vertical-playlist", a)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-560dpi")
    fun portraitUsb() {
        val a = app()
        a.tab = Tab.USB
        shot("mobile-05-vertical-pendrive", a)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-560dpi")
    fun landscapeLibrary() {
        val a = app()
        a.detailId = a.model.view!!.tracks.first { it.title == "Night Signal" }.id
        shot("mobile-06-horizontal-biblioteca", a)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-560dpi")
    fun landscapePlaylists() {
        val a = app()
        a.tab = Tab.PLAYLISTS
        a.model.openPlaylist = 1
        shot("mobile-07-horizontal-playlist", a)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-560dpi")
    fun landscapeUsb() {
        val a = app()
        a.tab = Tab.USB
        shot("mobile-08-horizontal-pendrive", a)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-560dpi")
    fun portraitTargetAndFilters() {
        val a = app()
        a.hideGuide()
        a.model.target = 1
        a.model.query = "bpm:120-128"
        a.model.filter = os.deckbridge.manager.smart.QuickFilter.UNRATED
        shot("mobile-09-vertical-filtros-e-alvo", a)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-560dpi")
    fun portraitTriage() {
        val a = app()
        a.model.target = 2
        a.startTriage()
        a.triageAt = 3
        shot("mobile-10-vertical-triagem", a)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-560dpi")
    fun landscapeTriage() {
        val a = app()
        a.model.target = 2
        a.startTriage()
        a.triageAt = 3
        shot("mobile-11-horizontal-triagem", a)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-560dpi")
    fun portraitPreflight() {
        val a = app()
        a.model.submit(os.deckbridge.manager.pdb.Edit.CreatePlaylist(a.model.nextPlaylistId(), 0, "Rascunho", false), "x")
        a.tab = Tab.USB
        shot("mobile-12-vertical-pendrive-checagem", a)
    }
}
