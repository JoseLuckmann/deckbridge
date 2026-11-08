package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Texto grande: o Android 14 deixa o usuário aumentar o texto até 200% (curva não linear, para os títulos grandes não
 * explodirem). As telas principais têm de continuar usáveis assim: nada some e os controles principais aparecem.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi", fontScale = 2.0f)
class LargeTextTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    @Test fun mainScreensWorkWithTextAt200Percent() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ManagerApp(ctx, File(ctx.cacheDir, "grande-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40); it.hideGuide() }
        assertTrue(ctx.resources.configuration.fontScale >= 1.99f)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(out, "mobile-54-texto-200-musicas.png").path)
        a.sets.open(1)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(out, "mobile-55-texto-200-set.png").path)
        a.sets.openMix(a.sets.plan().first())
        rule.waitForIdle()
        rule.onNodeWithText("▶ PLAY").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-56-texto-200-mix.png").path)
        rule.onNodeWithText("AJUSTAR").performClickSafe()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(out, "mobile-57-texto-200-mix-ajustes.png").path)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickSafe() =
        performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
}
