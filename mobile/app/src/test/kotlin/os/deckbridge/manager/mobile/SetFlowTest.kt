package os.deckbridge.manager.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.deckbridge.logic.decodeWav
import os.deckbridge.manager.mix.MixStyle
import os.deckbridge.manager.mix.SetPlanner

/**
 * Planejar o set: abrir a partir da playlist, ajustar uma transição, montar a prévia e o set inteiro. As faixas são
 * cliques sintéticos em WAV (lidos sem codec); MP3/FLAC passam pelo MediaCodec, que só existe no aparelho.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class SetFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "set-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 60) }
    }

    @Test fun planFromThePlaylistEditARenderAndKeepThePlan() {
        val a = app()
        a.hideGuide()
        a.tab = Tab.PLAYLISTS
        a.model.openPlaylist = 1
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.onNodeWithText("MIXAR").performClick()
        rule.waitForIdle()
        assertEquals(1, a.sets.playlistId)
        val plan = a.sets.plan()
        assertEquals(3, plan.size)
        rule.onNodeWithText("PRÓXIMA").assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-15-vertical-set.png").path)

        // Pontos automáticos em início de compasso da grade analisada.
        val v = a.model.view!!
        val first = v.track(plan[0].fromId)!!
        val g = assertNotNull(a.sets.grid(first))
        assertTrue(g.beats.any { it.inBar == 1 && it.timeMs == plan[0].outMs }, "saída num início de compasso")

        // Ajuste: 8 compassos, corte; fica guardado ao reabrir.
        val edited = plan[0].copy(bars = 8, style = MixStyle.CUT)
        a.sets.update(edited)
        a.sets.close()
        a.sets.open(1)
        assertEquals(edited, a.sets.plan()[0])

        // Prévia da transição: 12 s antes + 8 compassos + 12 s depois.
        val wav = assertNotNull(decodeWav(a.sets.renderTransition(edited).readBytes()))
        val expected = minOf(12_000, edited.outMs) + (8 * SetPlanner.barMs(first.bpm)).toInt() + 12_000
        assertEquals(expected / 1000.0, wav.frameCount / 44100.0, 0.05)
        assertTrue(wav.left.take(wav.frameCount).any { kotlin.math.abs(it) > 0.1f }, "tem som")

        // O set inteiro: as três transições em sequência.
        val all = assertNotNull(a.sets.renderSet(a.sets.plan()))
        val set = assertNotNull(decodeWav(all.readBytes()))
        assertTrue(set.frameCount / 44100.0 > 3 * 16, "três transições: ${set.frameCount / 44100.0} s")

        // Sugestão: a próxima entra no fim da playlist.
        val next = a.sets.suggestions(a.sets.ids()).first()
        a.sets.append(next)
        assertEquals(next.id, a.sets.ids().last())
        assertEquals(4, a.sets.plan().size)
    }

    private fun waitMix(a: ManagerApp) {
        val until = System.currentTimeMillis() + 60_000
        while (a.sets.mixBusy && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        rule.waitForIdle()
    }

    @Test fun mixScreenLooksLikeThePlayerAndEditsRebuildThePreview() {
        val a = app()
        a.hideGuide()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        rule.onAllNodes(androidx.compose.ui.test.hasText("MIX"))[0].performClick()
        rule.waitForIdle()
        assertNotNull(a.sets.mixing)
        waitMix(a)
        val p = assertNotNull(a.sets.mixPreview, a.sets.mixError)
        assertNotNull(p.waveA)
        assertNotNull(p.waveB)
        assertTrue(a.sets.mixing!!.masterTempo, "master tempo ligado por padrão")
        // A cabeça começa dois compassos antes da saída: é onde se quer ouvir.
        assertEquals(p.leadMs - (2 * p.barMsA).toInt(), a.sets.cursorMs)
        rule.onNodeWithText("DECK 1").assertExists()
        rule.onNodeWithText("▶ PLAY").assertExists()
        rule.onNodeWithText("SYNC FINO").assertExists()
        assertTrue(a.sets.mixing!!.phaseLock, "sync fino ligado por padrão")
        // Faixas sintéticas com grade certa: nada a corrigir (o botão de grade fica apagado).
        assertTrue(kotlin.math.abs(p.measuredMs ?: 0.0) < 3, "medido ${p.measuredMs}")
        rule.onRoot().captureRoboImage(File(out, "mobile-16-vertical-teste-de-mix.png").path)

        // Leva a cabeça para o meio da troca e confere os níveis do mixer (troca de grave: grave da 2 entrou).
        a.sets.seekMix(p.leadMs + p.overlapMs * 3 / 4)
        rule.waitForIdle()
        val lv = p.levelsAt(a.sets.cursorMs)
        assertEquals(0f, lv.aLow)
        assertEquals(1f, lv.bLow)
        rule.onAllNodes(androidx.compose.ui.test.hasText("KILL")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "grave cortado aparece como KILL") }
        rule.onRoot().captureRoboImage(File(out, "mobile-17-vertical-mix-no-meio.png").path)

        // Ajuste pela fileira: CORTE e 8 compassos, sem master tempo. Monta de novo e guarda no plano.
        rule.onNodeWithText("CORTE").performClick()
        waitMix(a)
        rule.onNodeWithText("8").performClick()
        waitMix(a)
        rule.onNodeWithText("MASTER TEMPO").performClick()
        waitMix(a)
        val tr = a.sets.mixing!!
        assertEquals(MixStyle.CUT, tr.style)
        assertEquals(8, tr.bars)
        assertTrue(!tr.masterTempo)
        assertEquals(8 * a.sets.mixPreview!!.barMsA, a.sets.mixPreview!!.overlapMs.toDouble(), 1.0)
        assertEquals(tr, a.sets.plan().first { it.fromId == tr.fromId && it.toId == tr.toId })
        a.sets.closeMix()
        rule.waitForIdle()
        rule.onNodeWithText("PRÓXIMA").assertExists()
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-560dpi")
    fun mixScreenLandscape() {
        val a = app()
        a.hideGuide()
        a.sets.open(1)
        a.sets.openMix(a.sets.plan()[1])
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        waitMix(a)
        a.sets.seekMix(a.sets.mixPreview!!.leadMs + 1500)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(out, "mobile-18-horizontal-teste-de-mix.png").path)
    }

    @Test fun audioCheckMeasuresOnTheLibrary() {
        val a = app()
        a.audioCheck()
        val until = System.currentTimeMillis() + 60_000
        while (a.job != null && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(a.audioCheckResult.startsWith("WAV"), a.audioCheckResult + " / " + a.notice?.text)
    }

    @Test fun refreshGridsFixesOnlyTheTrackThatWasOff() {
        val a = app()
        val t = a.model.view!!.tracks.first()
        val before = a.model.trackFiles(t).beats
        check(a.model.submit(os.deckbridge.manager.pdb.Edit.SetGrid(t.id, t.bpm, before.first().timeMs + 18), "grade torta"))
        check(a.model.apply(null) != null)
        a.refreshGrids()
        val until = System.currentTimeMillis() + 120_000
        while (a.job != null && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val text = a.notice?.text.orEmpty()
        assertTrue(text.startsWith("1 refeitas") && text.contains("já certas"), text)
        val after = a.model.trackFiles(a.model.view!!.track(t.id)!!).beats
        assertTrue(kotlin.math.abs(after.first().timeMs - before.first().timeMs) <= 1, "${after.first()} vs ${before.first()}")
        assertTrue(!a.model.dirty, "gravado")
    }

    @Test fun leavingTheMixTestStopsTheMusic() {
        // Relato: "saiu da mixagem e a música continuou tocando".
        val a = app()
        a.hideGuide()
        a.sets.open(1)
        a.sets.openMix(a.sets.plan()[0])
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        waitMix(a)
        val f = assertNotNull(a.sets.mixFile)
        org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(org.robolectric.shadows.util.DataSource.toDataSource(f.absolutePath), org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(60_000, 0))
        rule.mainClock.autoAdvance = false
        a.sets.playMix()
        assertTrue(a.preview.isPlaying, "devia estar tocando")
        rule.mainClock.advanceTimeBy(200)
        // O ✕ da tela de teste (a última na árvore: as abas de baixo continuam nela).
        val closes = rule.onAllNodes(androidx.compose.ui.test.hasText("✕"))
        closes[closes.fetchSemanticsNodes().lastIndex].performClick()
        rule.mainClock.advanceTimeBy(200)
        assertTrue(!a.preview.isPlaying, "saiu e continuou tocando")
        assertEquals(null, a.sets.mixing)
    }

    @Test fun aPreviewStillBeingBuiltDoesNotStartAfterLeavingTheSet() {
        val a = app()
        // O player simulado aceita qualquer arquivo: se a prévia fosse tocar, tocaria.
        org.robolectric.shadows.ShadowMediaPlayer.setMediaInfoProvider { org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(60_000, 0) }
        a.sets.open(1)
        val tr = a.sets.plan()[0]
        a.sets.play(tr) // monta em segundo plano
        a.sets.close() // sai antes de ficar pronta (voltar do sistema)
        val until = System.currentTimeMillis() + 60_000
        while (a.job != null && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(!a.preview.isPlaying, "começou a tocar depois de sair do SET")
        // E com o SET aberto, a mesma prévia toca (o simulado funciona).
        a.sets.open(1)
        a.sets.play(tr)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(a.preview.isPlaying, "a prévia pronta devia tocar com o SET aberto")
        a.preview.stop()
    }
}
