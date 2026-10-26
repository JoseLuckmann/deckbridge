package os.deckbridge.manager.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import os.deckbridge.logic.click
import os.deckbridge.logic.encodeWavMono16

/** Ícone, atalhos do ícone e "compartilhar para importar". Atividades criadas aqui são destruídas no fim. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class PlatformTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun render(res: Int, size: Int, name: String, round: Boolean) {
        val d = ContextCompat.getDrawable(ctx, res)!!
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF000000.toInt()
        if (round) c.drawCircle(size / 2f, size / 2f, size / 2f, paint) else c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // O desenho fica na área segura do ícone adaptativo: nos cantos não há traço (só fundo ou transparência).
        assertTrue(bmp.getPixel(2, 2) == 0 || bmp.getPixel(2, 2) == 0xFF000000.toInt(), "traço fora da área segura")
        assertTrue((0 until size).any { x -> bmp.getPixel(x, size / 2) != 0xFF000000.toInt() }, "ícone vazio")
    }

    @Test fun launcherIconAndShortcutsRender() {
        render(R.drawable.ic_launcher_foreground, 432, "icone-app", round = true)
        render(R.drawable.ic_launcher_monochrome, 216, "icone-app-tematico", round = true)
        render(R.drawable.ic_sc_triage, 144, "atalho-triagem", round = true)
        render(R.drawable.ic_sc_import, 144, "atalho-importar", round = true)
        render(R.drawable.ic_sc_export, 144, "atalho-exportar", round = true)
        val info = ctx.packageManager.getActivityInfo(android.content.ComponentName(ctx, ManagerActivity::class.java), 0)
        assertEquals("Biblioteca DJ", info.loadLabel(ctx.packageManager).toString())
    }

    @org.junit.Before fun fresh() = ManagerApp.resetForTests()

    private fun demoApp(): ManagerApp = ManagerApp.get(ctx).also { DemoLibrary.build(it, seconds = 12) }

    private fun settle(app: ManagerApp) {
        repeat(600) {
            // Avança o relógio da thread principal: os quadros do Compose são agendados com atraso.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            if (app.job == null && app.pendingIntent == null && !app.imports.running) return
            Thread.sleep(20)
        }
    }

    @Test fun triageShortcutOpensTriage() {
        val app = demoApp()
        app.handle(Intent(ACTION_TRIAGEM)) {}
        assertNotNull(app.triage, "o atalho Triagem abre a triagem")
        var picked = false
        app.handle(Intent(ACTION_IMPORTAR)) { picked = true }
        assertTrue(picked, "o atalho Importar abre o seletor")
    }

    /** De ponta a ponta pela tela (só um teste por classe abre atividade: o Robolectric não desenha uma segunda). */
    @Test fun exportShortcutGoesToThePendriveTab() {
        val app = demoApp()
        val c = Robolectric.buildActivity(ManagerActivity::class.java, Intent(ACTION_EXPORTAR)).setup()
        settle(app)
        assertEquals(Tab.USB, app.tab)
        app.tab = Tab.LIBRARY
        c.pause().stop().destroy()
    }

    @Test fun sharingAnAudioFileImportsIt() {
        val app = demoApp()
        val before = app.model.view!!.tracks.size
        val wav = File(ctx.cacheDir, "Compartilhada - Faixa Nova.wav").apply { writeBytes(encodeWavMono16(click(bpm = 127.0, seconds = 12, rate = 22050, freq = 500.0), 22050)) }
        val share = Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(wav))
        app.handle(share) {}
        settle(app)
        assertEquals(before + 1, app.model.view!!.tracks.size, app.notice?.text.orEmpty())
        // Compartilhar a mesma música de novo não duplica.
        app.handle(share) {}
        settle(app)
        assertEquals(before + 1, app.model.view!!.tracks.size)
    }
}
