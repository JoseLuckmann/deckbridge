package os.deckbridge.manager.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Os dois modos do app (ADR-0012): o último modo volta, e o modo DJ lê a mesma pasta que a Biblioteca grava. */
@RunWith(RobolectricTestRunner::class)
class ModesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun theLastModeIsRememberedAcrossProcesses() {
        File(ctx.filesDir, "modo.txt").delete()
        assertEquals(Modes.Mode.LIBRARY, Modes.last(ctx), "a primeira vez abre a Biblioteca")
        Modes.remember(ctx, Modes.Mode.DJ)
        // Um arquivo, não SharedPreferences: o outro processo lê o que este gravou.
        assertEquals("DJ", File(ctx.filesDir, "modo.txt").readText())
        assertEquals(Modes.Mode.DJ, Modes.last(ctx))
        Modes.remember(ctx, Modes.Mode.LIBRARY)
        assertEquals(Modes.Mode.LIBRARY, Modes.last(ctx))
    }

    @Test fun djModeReadsTheLibraryTheLibraryModeWrites() {
        assertEquals(ManagerApp.defaultRoot(ctx), Modes.libraryRoot(ctx))
        val pdb = File(Modes.libraryRoot(ctx), "PIONEER/rekordbox/export.pdb")
        pdb.delete()
        assertFalse(Modes.hasLocalLibrary(ctx))
        val a = ManagerApp(ctx)
        DemoLibrary.build(a, seconds = 10)
        assertTrue(Modes.hasLocalLibrary(ctx), "a biblioteca gravada é a que o modo DJ abre")
        // O modo DJ abre a pasta só para ler (FolderVolume não tem como escrever).
        val volume = os.deckbridge.logic.FolderVolume(Modes.libraryRoot(ctx), "Biblioteca do aparelho")
        assertTrue(volume.exists("PIONEER/rekordbox/export.pdb"))
    }

    @Test fun theDjIntentOpensTheDjScreenAndAsksForTheLocalLibrary() {
        val intent = Modes.djIntent(ctx, openLocal = true)
        assertEquals(Modes.DJ_ACTIVITY, intent.component?.className)
        assertEquals(ctx.packageName, intent.component?.packageName)
        assertTrue(intent.getBooleanExtra(Modes.EXTRA_OPEN_LOCAL, false))
        assertEquals(ManagerActivity::class.java.name, Modes.libraryIntent(ctx).component?.className)
    }
}
