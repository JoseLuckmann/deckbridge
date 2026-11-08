package os.deckbridge.manager.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O fim do MENU (Robolectric): a logo e a versão; 13 toques na logo (cada um gira o disco) ligam o modo desenvolvedor,
 * que mostra a memória e a última queda. E o modo DJ na barra principal.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class DevModeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    @Test fun thirteenTapsOnTheLogoTurnOnDeveloperMode() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ManagerApp(ctx, File(ctx.cacheDir, "dev-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 20); it.hideGuide() }
        a.changeDevMode(false)
        var dj = 0
        a.tab = Tab.MENU
        rule.setContent { ManagerRoot(a, Actions({}, {}, toDj = { dj++ })) }
        rule.waitForIdle()
        // O modo DJ na barra principal.
        rule.onNode(hasContentDescription("Modo DJ")).performClick()
        assertEquals(1, dj)
        val logo = rule.onNode(hasContentDescription("Logo do DeckBridge")).performScrollTo()
        rule.onNodeWithText("versão", substring = true).assertExists()
        repeat(DEV_TAPS - 1) { logo.performClick() }
        assertFalse(a.devMode)
        assertTrue(a.notice?.text.orEmpty().contains("Falta 1 toque"), a.notice?.text)
        logo.performClick()
        rule.waitForIdle()
        assertTrue(a.devMode)
        rule.onNodeWithText("Desenvolvedor".uppercase()).performScrollTo().assertExists()
        rule.onNodeWithText("Memória").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-71-vertical-menu-desenvolvedor.png").path)
        // A última queda fica anotada no aparelho e aparece aqui.
        CrashLog.write(ctx, Thread.currentThread(), IllegalStateException("teste"))
        assertTrue(assertNotNull(CrashLog.last(ctx)).contains("IllegalStateException: teste"))
        CrashLog.clear(ctx)
        rule.onNodeWithText("Desligar o modo desenvolvedor").performScrollTo().performClick()
        assertFalse(a.devMode)
    }
}
