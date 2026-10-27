package os.deckbridge.manager

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.graphics.asSkiaBitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import os.deckbridge.logic.click
import os.deckbridge.logic.encodeWavMono16
import os.deckbridge.manager.importer.ImportPrep
import os.deckbridge.manager.pdb.Edit
import os.deckbridge.manager.pdb.TrackPatch
import os.deckbridge.manager.transfer.LocalLibrary
import os.deckbridge.manager.ui.App
import os.deckbridge.manager.ui.UiState
import os.deckbridge.manager.volume.Backups

/**
 * As telas de montar set no computador: as mesmas do celular (studio/), abertas pelo SET da playlist, com clique de mouse
 * (offscreen, sem janela do sistema). Capturas em `build/prints` (ou SHOTS_DIR).
 */
@OptIn(ExperimentalTestApi::class)
class StudioTest {
    private val out = File(System.getenv("SHOTS_DIR") ?: "build/prints").apply { mkdirs() }

    /** Biblioteca local com faixas sintéticas (cliques no andamento), nomes inventados, tons escolhidos. */
    private fun library(): ManagerModel {
        val root = File(TestVolumes.tempDir("dbm-set"), "library")
        LocalLibrary.ensure(root)
        val m = ManagerModel(Backups(TestVolumes.tempDir("dbm-set-bk")))
        check(m.open(root))
        val src = TestVolumes.tempDir("dbm-set-src")
        val specs = listOf(
            Triple("Night Signal", 124.0, "Am"), Triple("Glass Harbour", 126.0, "Em"), Triple("Tidal Bloom", 122.0, "Dm"),
            Triple("Soft Machines", 120.0, "C"), Triple("Concrete Garden", 128.0, "Em"), Triple("Afterhours", 130.0, "F#m"),
            Triple("Slow Pulse", 118.0, "Gm"), Triple("Velvet Rail", 121.0, "Am"), Triple("Copper Sky", 125.0, "Bm"), Triple("Small Hours", 123.0, "Em"),
        )
        val edits = specs.mapIndexed { n, (title, bpm, _) ->
            val f = File(src, "$title.wav").apply { writeBytes(encodeWavMono16(click(bpm = bpm, seconds = 40, rate = 22050, freq = 220.0 + n * 40), 22050)) }
            val p = ImportPrep.prepare(f)
            Edit.ImportTrack(p, title, "Demo", "", "House", p.read.keyName, 2025, n + 1, p.suggestedCues) to "Importar $title"
        }
        check(m.submitAll(edits) == specs.size)
        check(m.apply(null) != null)
        for ((title, _, key) in specs) m.submit(Edit.SetTracks(listOf(m.view!!.tracks.first { it.title == title }.id), TrackPatch(key = key)), "tom")
        check(m.apply(null) != null)
        return m
    }

    private fun id(m: ManagerModel, title: String) = m.view!!.tracks.first { it.title == title }.id

    @Test fun theSetScreensWorkOnTheComputer() = runDesktopComposeUiTest(1360, 820) {
        val m = library()
        val pid = m.nextPlaylistId()
        m.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        m.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky").map { id(m, it) }), "p")
        m.openPlaylist = pid
        // Preferências e planos isolados: o teste não mexe nas do usuário.
        val prefs = java.util.prefs.Preferences.userRoot().node("deckbridge-test/${System.nanoTime()}")
        val ui = UiState().apply { studioPrefs = prefs; studioSetsHome = TestVolumes.tempDir("dbm-sets") }
        setContent { App(m, ui) }
        waitForIdle()
        fun last(all: SemanticsNodeInteractionCollection) = all[all.fetchSemanticsNodes().size - 1]
        fun click(text: String) {
            last(onAllNodes(hasText(text))).performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
        }
        fun tap(description: String) {
            onAllNodes(hasContentDescription(description, substring = true))[0].performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
        }
        fun shot(name: String) {
            val img = onRoot().captureToImage()
            val data = org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!
            File(out, "$name.png").writeBytes(data.bytes)
        }
        val studio = assertNotNull(ui.studio)
        // O SET da playlist abre as telas de set: no computador (largo) o quadro corre para a direita.
        click("SET ▸")
        assertEquals(pid, studio.sets.playlistId)
        assertTrue(!studio.setAsList, "o quadro é o padrão")
        tap("Pôr faixa na linha A")
        onAllNodes(hasText("Pôr na linha A")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        click("＋")
        click("PRONTO")
        assertEquals(5, studio.sets.entries().size)
        studio.notice = null
        // Vertentes: abertura Night Signal → Small Hours; as vertentes saem da Small Hours e juntam na Glass Harbour.
        click("Small Hours")
        click("DIVIDIR")
        click("Criar ramo daqui")
        click("junta onde combinar")
        click("Glass Harbour")
        click("CRIAR")
        assertEquals(id(m, "Glass Harbour"), studio.sets.branch?.rejoinTrackId)
        click("Small Hours")
        click("DIVIDIR")
        click("Criar ramo daqui")
        click("CRIAR")
        studio.notice = null
        waitForIdle()
        val layout = studio.sets.canvas()!!
        assertEquals(3, layout.rows)
        assertTrue(layout.lanes.drop(1).all { it.rejoin?.trackId == id(m, "Glass Harbour") })
        shot("desktop-set-quadro")
        // Ouvir uma vertente é ouvir o caminho inteiro por ela: abertura → C → fechamento.
        val c = layout.lanes[2]
        studio.sets.focus(c.playlistId)
        waitForIdle()
        val path = assertNotNull(studio.sets.pathPlan())
        val opening = listOf("Night Signal", "Small Hours").map { id(m, it) }
        val ending = m.view!!.entries[pid]!!.dropWhile { it != id(m, "Glass Harbour") }
        assertEquals(opening + c.cards.map { it.trackId } + ending, path.route.ids)
        assertEquals(path.route.ids.size - 1, path.plan.size)
        assertTrue(onAllNodes(hasText("OUVIR C", substring = true)).fetchSemanticsNodes().isNotEmpty(), "o OUVIR diz qual caminho")
        // Arrastar com o mouse: o primeiro cartão de B desce uma fileira e vira parte de C (um desfazer volta).
        val bLane = layout.lanes[1]
        val moving = m.view!!.track(bLane.cards.first().trackId)!!
        last(onAllNodes(hasText(moving.title))).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 52f))
            moveBy(Offset(0f, 52f))
            release()
        }
        waitForIdle()
        assertTrue(moving.id !in m.view!!.entries[bLane.playlistId].orEmpty(), "saiu de B")
        assertEquals(moving.id, m.view!!.entries[c.playlistId].orEmpty().first(), "entrou em C no mesmo lugar")
        m.undo()
        waitForIdle()
        assertEquals(moving.id, m.view!!.entries[bLane.playlistId].orEmpty().first(), "um desfazer volta")
        // Ver tudo / afastar: o quadro inteiro muda de escala.
        tap("Afastar")
        shot("desktop-set-afastado")
        tap("Aproximar")
        // A cola do set: as linhas, onde cada vertente sai e junta, e as trocas.
        tap("Mais")
        click("Cola do set")
        val sheet = assertNotNull(studio.shared)
        assertTrue("VERTENTE B · sai de Small Hours · junta em Glass Harbour" in sheet, sheet)
        assertTrue("VERTENTE C · sai de Small Hours · junta em Glass Harbour" in sheet, sheet)
        assertTrue("⑂ vertente B, C" in sheet, sheet)
        assertTrue("comp." in sheet)
        File(out, "desktop-cola-do-set.txt").writeText(sheet)
        // Ao vivo: tocar na que está tocando mostra o que pode vir depois (a linha segue ou entra numa vertente).
        tap("Mais")
        click("Ao vivo")
        assertTrue(studio.sets.live)
        click("Small Hours")
        val nowLayout = studio.sets.canvas()!!
        val small = nowLayout.lanes[0].cards.first { it.trackId == id(m, "Small Hours") }
        val options = os.deckbridge.manager.mix.SetCanvas.nextFrom(nowLayout, small).map { it.trackId }.toSet()
        assertEquals(setOf(id(m, "Glass Harbour"), nowLayout.lanes[1].cards.first().trackId, nowLayout.lanes[2].cards.first().trackId), options)
        shot("desktop-set-ao-vivo")
        // Escolher a vertente C pela barra: ela passa a tocar, a Small Hours vai para as tocadas.
        val cFirst = m.view!!.track(nowLayout.lanes[2].cards.first().trackId)!!
        click(cFirst.title)
        assertEquals(nowLayout.lanes[2].playlistId to 0, studio.sets.liveNow)
        assertTrue(id(m, "Small Hours") in studio.sets.livePlayed)
        assertTrue(studio.back(), "Esc sai do ao vivo")
        waitForIdle()
        assertTrue(!studio.sets.live)
        // Saindo do ao vivo com duas ou mais tocadas, o app pergunta se guarda o que tocou; aqui, não.
        onAllNodes(hasText("Guardar o que tocou?")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        click("Cancelar")
        assertEquals(null, studio.sets.playedToSave)
        // A troca abre o MIX (a mesma tela do celular); Esc volta.
        val first = studio.sets.lanePlan(pid).first()
        studio.sets.focus(pid)
        waitForIdle()
        tap("Troca ${first.fromId}→${first.toId}")
        assertEquals(first.fromId, studio.sets.mixing?.fromId)
        // A prévia é montada em segundo plano (áudio dos arquivos da biblioteca): espera a onda.
        val until = System.currentTimeMillis() + 60_000
        while (studio.sets.mixPreview == null && studio.sets.mixError == null && System.currentTimeMillis() < until) {
            Thread.sleep(100)
            waitForIdle()
        }
        assertNotNull(studio.sets.mixPreview, studio.sets.mixError ?: "a prévia não ficou pronta")
        click("LENTA")
        click("8 COMP.")
        assertEquals(8, studio.sets.mixing?.swapBars)
        shot("desktop-set-mix")
        // A análise errou o drop: põe a cabeça 10 compassos depois da entrada da que entra e marca. A troca passa a durar
        // até ele, o grave troca no drop, e o cue vai para a fila da faixa.
        val p = studio.sets.mixPreview!!
        val b = m.view!!.track(p.transition.toId)!!
        val barB = 4 * 60000.0 / b.bpm
        studio.sets.seekMix(p.fromB(p.transition.inMs + (10 * barB).toInt()))
        waitForIdle()
        click("DROP AQUI")
        val marked = studio.sets.mixing!!
        assertEquals(10, marked.bars)
        assertEquals(1.0, marked.swapAt)
        assertTrue(m.pending.any { (it.edit as? Edit.SetCues)?.trackId == b.id }, "o drop entra na fila da faixa")
        assertEquals(10, os.deckbridge.manager.mix.SetPlanner.barsToDrop(b, studio.sets.grid(b), marked.inMs), "e já vale para as outras trocas com ela")
        assertTrue(studio.back(), "Esc fecha o MIX")
        waitForIdle()
        assertEquals(null, studio.sets.mixing)
        // A lista continua no ⋯; e o mapa do andamento.
        tap("Mais")
        click("Andamento do set")
        assertTrue(studio.sets.mapOpen)
        waitForIdle()
        shot("desktop-set-mapa")
        assertTrue(studio.back())
        waitForIdle()
        tap("Mais")
        click("Ver em lista")
        assertTrue(studio.setAsList)
        waitForIdle()
        shot("desktop-set-lista")
        // Os planos ficam fora da biblioteca de músicas, ao lado dela (biblioteca local), nunca num pendrive.
        assertTrue(File(studio.setsDir, "branches.properties").isFile)
        assertTrue(studio.back())
        waitForIdle()
        assertEquals(null, studio.sets.playlistId, "Esc fecha o SET")
        prefs.removeNode()
    }

    @Test fun markingOneByOneWorksOnTheComputer() = runDesktopComposeUiTest(1360, 820) {
        val m = library()
        val prefs = java.util.prefs.Preferences.userRoot().node("deckbridge-test/${System.nanoTime()}")
        val ui = UiState().apply { studioPrefs = prefs; studioSetsHome = TestVolumes.tempDir("dbm-sets") }
        setContent { App(m, ui) }
        waitForIdle()
        val studio = assertNotNull(ui.studio)
        onAllNodes(hasText("MARCAR ▸")).let { it[it.fetchSemanticsNodes().size - 1] }.performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        val queue = assertNotNull(studio.triage, "a triagem abre")
        val first = queue.first()
        // Três estrelas: marca e já passa para a próxima.
        // As estrelas da triagem são as últimas cinco (a tabela da biblioteca fica embaixo e também tem estrelas).
        onAllNodes(hasText("☆")).let { it[it.fetchSemanticsNodes().size - 3] }.performClick()
        waitForIdle()
        assertEquals(3, m.view!!.track(first)!!.rating)
        assertEquals(1, studio.triageAt)
        val img = onRoot().captureToImage()
        File(out, "desktop-marcar.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        assertTrue(studio.back(), "Esc fecha a triagem")
        waitForIdle()
        assertEquals(null, studio.triage)
        prefs.removeNode()
    }

    @Test fun newTracksAndTheSetsATrackIsInShowOnTheComputer() = runDesktopComposeUiTest(1360, 820) {
        val m = library()
        val pid = m.nextPlaylistId()
        m.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        m.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky").map { id(m, it) }), "p")
        val prefs = java.util.prefs.Preferences.userRoot().node("deckbridge-test/${System.nanoTime()}")
        val ui = UiState().apply { studioPrefs = prefs; studioSetsHome = TestVolumes.tempDir("dbm-sets") }
        setContent { App(m, ui) }
        waitForIdle()
        fun click(text: String) {
            val all = onAllNodes(hasText(text))
            all[all.fetchSemanticsNodes().size - 1].performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
        }
        // A caixa de entrada: sem nota e fora das playlists (as 6 que não estão no Sábado).
        click("Novas 6")
        assertEquals(6, m.rows().size)
        click("Todas")
        // Onde a faixa está: nenhum set ainda; depois de abrir o Sábado como set, ele aparece e abre com um clique.
        m.selection = setOf(id(m, "Night Signal"))
        waitForIdle()
        onAllNodes(hasText("em nenhum ainda", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        val studio = assertNotNull(ui.studio)
        studio.sets.open(pid)
        studio.sets.close()
        m.selection = emptySet()
        waitForIdle()
        m.selection = setOf(id(m, "Night Signal"))
        waitForIdle()
        org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.let { File(out, "desktop-nos-sets.png").writeBytes(it.bytes) }
        click("SET Sábado ›")
        assertEquals(pid, studio.sets.playlistId)
    }

    @Test fun keyboardShortcutsAreShownAndWork() = runDesktopComposeUiTest(1360, 820) {
        val m = library()
        val pid = m.nextPlaylistId()
        m.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        m.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky").map { id(m, it) }), "p")
        check(m.apply(null) != null)
        val prefs = java.util.prefs.Preferences.userRoot().node("deckbridge-test/${System.nanoTime()}")
        val ui = UiState().apply { studioPrefs = prefs; studioSetsHome = TestVolumes.tempDir("dbm-sets") }
        setContent { App(m, ui) }
        waitForIdle()
        fun click(text: String) {
            val all = onAllNodes(hasText(text))
            all[all.fetchSemanticsNodes().size - 1].performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
        }
        fun shot(name: String) {
            // Com diálogo aberto há duas raízes: a de cima é a do diálogo.
            val roots = onAllNodes(androidx.compose.ui.test.isRoot())
            val img = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
            val data = org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!
            File(out, "$name.png").writeBytes(data.bytes)
        }
        val studio = assertNotNull(ui.studio)
        val keys = os.deckbridge.manager.studio.StudioKeys
        val K = androidx.compose.ui.input.key.Key
        // A tecla aparece ao lado do botão (barra de baixo) e ? abre a lista.
        onAllNodes(hasText("↶ Desfazer  Ctrl+Z")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        assertTrue(keys.press(studio, K.F1))
        waitForIdle()
        onAllNodes(hasText("Atalhos do teclado")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        shot("desktop-atalhos")
        click("OK")
        assertTrue(!keys.helpOpen)
        // MARCAR pelo teclado: 3 dá a nota e passa; Ctrl+Z desfaz.
        click("MARCAR ▸")
        onAllNodes(hasText("Teclas 1–5", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "dica de tecla na triagem") }
        val first = studio.triage!![0]
        assertTrue(keys.press(studio, K.Three))
        waitForIdle()
        assertEquals(3, m.view!!.track(first)!!.rating)
        assertEquals(1, studio.triageAt)
        assertTrue(keys.press(studio, K.DirectionLeft))
        assertEquals(0, studio.triageAt)
        assertTrue(keys.press(studio, K.Z, ctrl = true))
        assertEquals(0, m.view!!.track(first)!!.rating)
        assertTrue(keys.press(studio, K.Y, ctrl = true))
        assertEquals(3, m.view!!.track(first)!!.rating)
        // Escrevendo num campo, as teclas de uma letra não são atalhos.
        keys.typing = true
        assertTrue(!keys.press(studio, K.Four))
        keys.typing = false
        studio.endTriage()
        waitForIdle()
        // SET: os ícones vêm com palavra; tirar uma faixa de onde sai uma vertente não pergunta, avisa com DESFAZER.
        studio.sets.open(pid)
        waitForIdle()
        onAllNodes(hasText("VER TUDO")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "zoom com rótulo") }
        val small = id(m, "Small Hours")
        studio.sets.createBranch(small, 1, os.deckbridge.manager.mix.SetShape.values().first(), rejoin = true, kind = os.deckbridge.manager.studio.SetDesk.BranchKind.MANUAL)
        studio.sets.openMain()
        waitForIdle()
        val index = studio.sets.entries().indexOf(small)
        // O relógio do teste anda sozinho enquanto espera e o aviso sumiria (8 s): parado aqui.
        mainClock.autoAdvance = false
        studio.sets.removeAt(index)
        waitForIdle()
        assertTrue(small !in studio.sets.entries())
        onAllNodes(hasText("DESFAZER  Ctrl+Z")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "aviso com desfazer: ${studio.notice} ${studio.noticeUndo} ${m.dirty}") }
        shot("desktop-desfazer")
        click("DESFAZER  Ctrl+Z")
        assertTrue(small in studio.sets.entries())
        mainClock.autoAdvance = true
        // Trabalho com "n/N": a porcentagem.
        assertEquals(0.3f, os.deckbridge.manager.studio.jobFraction("3/10: Night Signal"))
        assertEquals(null, os.deckbridge.manager.studio.jobFraction("Gravando"))
        // Erros ditos com causa e o que fazer, não o nome da exceção.
        val fe: (Throwable) -> String = { os.deckbridge.manager.studio.friendlyError(it) }
        assertEquals("Não achei um arquivo: Night Signal.wav. Ele foi movido ou apagado? Confira a pasta, ou importe a música de novo.", fe(java.io.FileNotFoundException("/x/Contents/Night Signal.wav (No such file or directory)")).replace("Night Signal.wav", "Night Signal.wav"))
        assertTrue(fe(java.io.IOException("write failed: ENOSPC (No space left on device)")).startsWith("Acabou o espaço"))
        assertTrue(fe(IllegalStateException()).startsWith("Não deu certo (IllegalStateException)"))
    }

    @Test fun setHelpersReplaceGoalAndDuplicate() = runDesktopComposeUiTest(1360, 820) {
        val m = library()
        val pid = m.nextPlaylistId()
        m.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        m.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky").map { id(m, it) }), "p")
        check(m.apply(null) != null)
        val prefs = java.util.prefs.Preferences.userRoot().node("deckbridge-test/${System.nanoTime()}")
        val ui = UiState().apply { studioPrefs = prefs; studioSetsHome = TestVolumes.tempDir("dbm-sets") }
        setContent { App(m, ui) }
        waitForIdle()
        fun click(text: String) {
            val all = onAllNodes(hasText(text))
            all[all.fetchSemanticsNodes().size - 1].performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
        }
        fun shot(name: String) {
            val roots = onAllNodes(androidx.compose.ui.test.isRoot())
            val img = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
            File(out, "$name.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        }
        val studio = assertNotNull(ui.studio)
        val desk = studio.sets
        desk.open(pid)
        waitForIdle()
        // TROCAR: a segunda faixa por uma que combina com a primeira e a terceira, no mesmo lugar.
        click("Small Hours")
        click("TROCAR")
        onAllNodes(hasText("Trocar Small Hours")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        val choices = desk.replaceChoices(1)
        assertTrue(choices.isNotEmpty(), "há opções")
        shot("desktop-trocar")
        val pick = choices.first()
        assertTrue(desk.replaceAt(1, pick))
        assertEquals(pick.id, desk.entries()[1])
        assertEquals(4, desk.entries().size)
        m.undo()
        assertEquals(id(m, "Small Hours"), desk.entries()[1])
        // Duração alvo: guardada por set, mostrada junto da duração.
        desk.setGoal(60)
        waitForIdle()
        onAllNodes(hasText("/ 60:00", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "alvo no topo") }
        desk.close()
        desk.open(pid)
        assertEquals(60, desk.goalMinutes)
        // Duplicar: uma cópia para testar, com as mesmas faixas e o mesmo alvo; o original não muda.
        val copy = assertNotNull(desk.duplicate())
        assertEquals(copy, desk.playlistId)
        assertEquals(m.view!!.entries[pid], m.view!!.entries[copy])
        assertEquals("Sábado (v2)", m.view!!.playlists.first { it.id == copy }.name)
        assertEquals(60, desk.goalMinutes)
    }

    @Test fun aRequestFromTheFloorGetsABridgeIntoTheSet() = runDesktopComposeUiTest(1360, 820) {
        val m = library()
        val pid = m.nextPlaylistId()
        m.submit(Edit.CreatePlaylist(pid, 0, "Sábado", false), "p")
        m.submit(Edit.AddToPlaylist(pid, listOf("Night Signal", "Small Hours", "Glass Harbour", "Copper Sky").map { id(m, it) }), "p")
        check(m.apply(null) != null)
        val prefs = java.util.prefs.Preferences.userRoot().node("deckbridge-test/${System.nanoTime()}")
        val ui = UiState().apply { studioPrefs = prefs; studioSetsHome = TestVolumes.tempDir("dbm-sets") }
        setContent { App(m, ui) }
        waitForIdle()
        fun click(text: String) {
            val all = onAllNodes(hasText(text))
            all[all.fetchSemanticsNodes().size - 1].performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
        }
        fun shot(name: String) {
            val roots = onAllNodes(androidx.compose.ui.test.isRoot())
            val img = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
            File(out, "$name.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        }
        val desk = assertNotNull(ui.studio).sets
        desk.open(pid)
        desk.startLive()
        desk.liveTo(desk.canvas()!!.lanes[0].cards[0])
        waitForIdle()
        // Tocando Night Signal (8A, 124); pedem Slow Pulse (6A, 118): Tidal Bloom (7A, 122) faz a ponte.
        click("PEDIDO")
        // O campo do diálogo (o último; o da busca da biblioteca fica embaixo).
        onAllNodes(androidx.compose.ui.test.hasSetTextAction()).let { it[it.fetchSemanticsNodes().size - 1] }.performTextInput("Slow")
        waitForIdle()
        click("CAMINHO")
        val until = System.currentTimeMillis() + 20_000
        while (onAllNodes(hasText("Procurando a ponte…")).fetchSemanticsNodes().isNotEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            waitForIdle()
        }
        onAllNodes(hasText("1 faixa(s) até lá", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "uma ponte") }
        shot("desktop-pedido")
        click("PÔR NO SET")
        val line = m.view!!.entries[pid]!!
        assertEquals(listOf("Night Signal", "Tidal Bloom", "Slow Pulse", "Small Hours"), line.take(4).map { t -> m.view!!.track(t)!!.title })
    }
}
