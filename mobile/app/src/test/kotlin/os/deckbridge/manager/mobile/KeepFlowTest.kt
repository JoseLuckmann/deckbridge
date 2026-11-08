package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.deckbridge.manager.smart.QuickFilter

/**
 * Manter a biblioteca e montar sets com menos memória de cabeça: a caixa de "Novas", onde a faixa já entrou, por que uma
 * sugestão aparece e o lembrete da troca que volta na hora (Robolectric, não o aparelho).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-port-560dpi")
class KeepFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("prints.dir") ?: "build/prints").apply { mkdirs() }

    /** O AO VIVO está guardado (Features.liveMode); os testes dele o ligam e aqui ele volta a ficar desligado. */
    @org.junit.After fun liveOff() { os.deckbridge.manager.studio.Features.liveMode = false }


    private fun app(): ManagerApp {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ManagerApp(ctx, File(ctx.cacheDir, "keep-${System.nanoTime()}/biblioteca/dados")).also { DemoLibrary.build(it, seconds = 40); it.hideGuide() }
    }

    private fun id(a: ManagerApp, title: String) = a.model.view!!.tracks.first { it.title == title }.id

    /** O último que bate: as telas de set ficam por cima da biblioteca, e os diálogos por cima delas. */
    private fun click(text: String) {
        val all = rule.onAllNodes(hasText(text))
        all[all.fetchSemanticsNodes().size - 1].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    @Test fun newTracksWaitInTheInboxAndTriageStartsThere() {
        val a = app()
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        // Na demonstração, só "Copper Sky" está sem nota e fora das playlists.
        assertEquals(1, a.model.filterCounts()[QuickFilter.NEW])
        click("Novas")
        assertEquals(QuickFilter.NEW, a.model.filter)
        assertEquals(listOf("Copper Sky"), a.model.rows().map { it.title })
        rule.onRoot().captureRoboImage(File(out, "mobile-49-vertical-novas.png").path)
        // A triagem começa pelas novas.
        a.startTriage()
        rule.waitForIdle()
        assertEquals(id(a, "Copper Sky"), a.triage!!.first())
    }

    @Test fun theTrackShowsTheSetsItIsInAndOpensThem() {
        val a = app()
        val night = id(a, "Night Signal")
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        a.detailId = night
        rule.waitForIdle()
        rule.onNodeWithText("NOS SETS").performScrollTo()
        rule.onNodeWithText("em nenhum ainda", substring = true).assertExists()
        // Abrir "Sexta à noite" como set: a faixa passa a mostrar esse set.
        a.sets.open(1)
        a.sets.close()
        a.detailId = null
        rule.waitForIdle()
        a.detailId = night
        rule.waitForIdle()
        rule.onNodeWithText("SET Sexta à noite ›").performScrollTo()
        rule.onRoot().captureRoboImage(File(out, "mobile-50-vertical-nos-sets.png").path)
        click("SET Sexta à noite ›")
        assertEquals(1, a.sets.playlistId)
    }

    @Test fun suggestionsSayWhyAndTheMixRemembersANote() {
        val a = app()
        a.sets.open(1)
        rule.setContent { ManagerRoot(a, Actions({}, {})) }
        rule.waitForIdle()
        // Por que cada sugestão está ali: tom, andamento, nota e uso em sets.
        val last = a.model.view!!.track(a.sets.entries().last())!!
        val pick = a.sets.addChoices("").first()
        val why = a.sets.why(pick)
        assertTrue("nunca em set" in why || "em 1 set" in why, why)
        assertTrue(why.startsWith("mesmo tom") || why.startsWith("tom "), "$why (depois de ${last.title})")
        click("＋")
        rule.onNodeWithText(why).assertExists()
        rule.onRoot().captureRoboImage(File(out, "mobile-51-vertical-sugestao-com-motivo.png").path)
        // O DJ dá o rumo (Jin et al. 2020): SOBE só traz as mais rápidas.
        click("SOBE")
        val up = a.sets.addChoices("", os.deckbridge.manager.smart.Steer.UP)
        assertTrue(up.all { it.bpm > last.bpm }, up.joinToString { "${it.title} ${it.bpm}" })
        click("COMBINA")
        // "Não sugerir esta" (Amershi et al. 2019): sai das sugestões e volta pelo ⋯.
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Não sugerir ${pick.title}"))[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(pick.id in a.sets.hidden)
        assertTrue(a.sets.addChoices("").none { it.id == pick.id })
        assertTrue(a.sets.addChoices(pick.title).any { it.id == pick.id }, "a busca ainda acha")
        click("PRONTO")
        a.sets.showAllSuggestions()
        assertTrue(a.sets.hidden.isEmpty())
        // O lembrete da troca: escrito no MIX, guardado no plano, na cola e ao vivo.
        val tr = a.sets.plan().first()
        a.sets.openMix(tr)
        rule.waitForIdle()
        // O lembrete fica na aba TROCA (o MIX abre no mixer).
        click("TROCA")
        rule.onNodeWithText("ESCREVER").performScrollTo()
        click("ESCREVER")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("segurar o grave até o vocal")
        click("GUARDAR")
        assertEquals("segurar o grave até o vocal", a.sets.plan().first().note)
        // Ajustada pelo DJ: o quadro marca diferente das automáticas.
        assertTrue((tr.fromId to tr.toId) in a.sets.adjustedPairs(1))
        rule.onNodeWithText("LEMBRETE").performScrollTo()
        rule.onRoot().captureRoboImage(File(out, "mobile-52-vertical-mix-lembrete.png").path)
        a.sets.closeMix()
        assertTrue("“segurar o grave até o vocal”" in a.sets.sheet())
        os.deckbridge.manager.studio.Features.liveMode = true
        a.sets.startLive()
        a.sets.liveTo(a.sets.canvas()!!.lanes[0].cards.first { it.trackId == tr.fromId })
        rule.waitForIdle()
        rule.onNodeWithText("segurar o grave até o vocal", substring = true).assertExists()
        // E continua guardado depois de abrir de novo.
        a.sets.stopLive()
        a.sets.close()
        a.sets.open(1)
        assertEquals("segurar o grave até o vocal", a.sets.plan().first().note)
    }
}
