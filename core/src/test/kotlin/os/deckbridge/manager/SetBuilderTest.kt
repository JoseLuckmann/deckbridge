package os.deckbridge.manager

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import os.deckbridge.manager.anlz.Beat
import os.deckbridge.manager.mix.SetBuilder
import os.deckbridge.manager.mix.SetDoctor
import os.deckbridge.manager.mix.SetIssue
import os.deckbridge.manager.mix.SetPlanner
import os.deckbridge.manager.mix.SetShape
import os.deckbridge.manager.mix.TrackGrid
import os.deckbridge.manager.mix.Transition
import os.deckbridge.manager.pdb.LibraryView
import os.deckbridge.manager.pdb.TrackItem

class SetBuilderTest {
    private fun t(id: Int, bpm: Double, key: String, rating: Int = 0, dur: Int = 300) =
        TrackItem(id, "T$id", "A", "", "House", key, "", "", bpm, 0, rating, 0, 0, dur, 0, 0, "/Contents/$id.mp3", "$id.mp3", "")

    private fun view(tracks: List<TrackItem>) = LibraryView(tracks, emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

    private fun pairCost(v: LibraryView, ids: List<Int>) = ids.zipWithNext().sumOf { (a, b) -> SetBuilder.cost(v.track(a)!!, v.track(b)!!) }

    // Camelot 8A=Am, 9A=Em, 10A=Bm, 7A=Dm, 3A=Bbm (longe de 8A).
    private val pool = listOf(
        t(1, 124.0, "Am", 3), t(2, 126.0, "Em", 5), t(3, 125.0, "Bm", 4), t(4, 123.0, "Dm", 2),
        t(5, 140.0, "Bbm", 5), t(6, 127.0, "Em", 1), t(7, 122.0, "Am", 4),
    )

    @Test fun ordenarDeixaAsTrocasMelhoresQueAOrdemDeChegada() {
        val v = view(pool)
        val ids = listOf(5, 1, 3, 7, 2, 4, 6)
        val sorted = SetBuilder.order(v, ids)
        assertEquals(ids.sorted(), sorted.sorted(), "mesmas faixas")
        assertTrue(pairCost(v, sorted) < pairCost(v, ids), "custo ${pairCost(v, sorted)} < ${pairCost(v, ids)}")
        // A de 140 BPM e tom distante fica numa ponta, não no meio de trocas boas.
        assertTrue(sorted.indexOf(5) == 0 || sorted.indexOf(5) == sorted.lastIndex, "$sorted")
        assertEquals(sorted, SetBuilder.order(v, ids), "determinístico")
        assertEquals(4, SetBuilder.order(v, ids, first = 4).first())
    }

    @Test fun subindoNaoDeixaOAndamentoCair() {
        val v = view(pool.filter { it.id != 5 })
        val sorted = SetBuilder.order(v, listOf(2, 6, 1, 3, 4, 7), SetShape.RISING)
        val bpms = sorted.map { v.track(it)!!.bpm }
        assertTrue(bpms.zipWithNext().none { (x, y) -> y < x - 1.0 }, "andamento subindo (recuo de até 1 BPM): $bpms")
    }

    @Test fun montarSetChegaPertoDaDuracaoEPrefereNotaAlta() {
        val big = (1..40).map { i -> t(i, 120.0 + (i % 9), listOf("Am", "Em", "Bm", "Dm", "C", "G")[i % 6], rating = i % 6, dur = 360) }
        val v = view(big)
        val ids = SetBuilder.build(v, big, minutes = 30, shape = SetShape.FLOW)
        val minutes = ids.sumOf { SetBuilder.playedMs(v.track(it)!!) } / 60000.0
        assertTrue(minutes in 30.0..37.0, "$minutes min")
        assertEquals(ids.size, ids.distinct().size)
        val avgRating = ids.map { v.track(it)!!.rating }.average()
        assertTrue(avgRating > big.map { it.rating }.average(), "nota média do set $avgRating")
        val bad = ids.zipWithNext().count { (a, b) -> !SetPlanner.fit(v.track(a)!!, v.track(b)!!).good }
        assertTrue(bad <= 1, "$bad trocas ruins em ${ids.size - 1}")
    }

    @Test fun checagemEPonte() {
        val v = view(pool)
        val grid = { tr: TrackItem -> if (tr.id == 4) null else TrackGrid((0 until 600).map { Beat(it % 4 + 1, 12500, it * 480) }, emptyList(), tr.durationSeconds * 1000) }
        val ids = listOf(1, 5, 4)
        val plan = listOf(Transition(1, 5, 299_000, 0, 16), Transition(5, 4, 100_000, 0, 16))
        val issues = SetDoctor.check(v, ids, plan, grid)
        val kinds = issues.map { Triple(it.fromId, it.toId, it.kind) }.toSet()
        assertTrue(Triple(1, 5, SetIssue.Kind.KEY) in kinds, "$issues")
        // 124 → 140: a de 140 toca a 124 (−11%). O master manda: é tocar normal, sem aviso.
        assertTrue(Triple(1, 5, SetIssue.Kind.TEMPO) !in kinds, "$issues")
        // 124 → 160 (−22%) passa do pitch do player.
        val far = view(pool + t(8, 160.0, "Am"))
        assertTrue(SetDoctor.check(far, listOf(1, 8), emptyList(), grid).any { it.kind == SetIssue.Kind.TEMPO && it.text.contains("-22%") })
        assertTrue(Triple(1, 5, SetIssue.Kind.NO_ROOM) in kinds, "saída a 1 s do fim")
        assertTrue(Triple(5, 4, SetIssue.Kind.NO_GRID) in kinds)
        assertTrue(issues.first().text.isNotBlank() && issues.all { it.text.length <= 40 }, "texto curto")

        // Am (124) → Bm (125) é distância 2: a ponte é Em (9A), vizinha das duas.
        val bridges = SetDoctor.bridges(v, v.track(1)!!, v.track(3)!!, exclude = setOf(1, 3))
        assertTrue(bridges.isNotEmpty())
        assertTrue(bridges.all { SetPlanner.fit(v.track(1)!!, it).good && SetPlanner.fit(it, v.track(3)!!).good })
        assertEquals(2, bridges.first().id, "Em com nota 5")
    }

    @Test fun marcaDoEnsaioFicaGuardada() {
        val f = Files.createTempDirectory("set").resolve("s.properties").toFile()
        val plan = listOf(Transition(1, 2, 1000, 0, mark = Transition.Mark.GOOD), Transition(2, 3, 2000, 0, mark = Transition.Mark.REDO), Transition(3, 4, 3000, 0))
        SetPlanner.save(f, plan)
        assertEquals(plan, SetPlanner.load(f))
    }
}

class SetTempoAndBranchTest {
    private fun t(id: Int, bpm: Double, key: String, rating: Int = 0) =
        TrackItem(id, "T$id", "A", "", "House", key, "", "", bpm, 0, rating, 0, 0, 300, 0, 0, "/Contents/$id.mp3", "$id.mp3", "")

    private fun view(tracks: List<TrackItem>, lists: Map<Int, List<Int>> = emptyMap()) = LibraryView(
        tracks, lists.keys.map { os.deckbridge.manager.pdb.PlaylistItem(it, 0, "P$it", false, it) }, lists,
        emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
    )

    @Test fun masterSegueAsFaixasSemSaltos() {
        val natives = listOf(124.0, 116.0, 128.0, 128.0, 140.0)
        val m = os.deckbridge.manager.mix.SetTempo.plan(natives)
        assertEquals(124.0, m[0], 1e-9)
        m.zipWithNext().forEach { (a, b) -> assertTrue(kotlin.math.abs(b - a) / a * 100 <= 2.5 + 1e-9, "salto $a → $b") }
        // A de 116 toca acima do BPM dela (o master não despenca).
        assertEquals(4.2, os.deckbridge.manager.mix.SetTempo.stretchPct(116.0, m[1]), 0.1) // master 120,9: a de 116 toca a +4,2%
        assertEquals(listOf(128.0, 128.0, 128.0), os.deckbridge.manager.mix.SetTempo.plan(listOf(116.0, 124.0, 135.0), fixed = 128.0))
        assertEquals(10.34, os.deckbridge.manager.mix.SetTempo.stretchPct(116.0, 128.0), 0.01)
        // Modo guardado.
        val f = Files.createTempDirectory("m").resolve("x.master").toFile()
        os.deckbridge.manager.mix.SetTempo.saveMode(f, 128.0)
        assertEquals(128.0, os.deckbridge.manager.mix.SetTempo.loadMode(f))
        os.deckbridge.manager.mix.SetTempo.saveMode(f, null)
        assertEquals(null, os.deckbridge.manager.mix.SetTempo.loadMode(f))
    }

    @Test fun cento16Em128EhTocarNormal() {
        val a = t(1, 128.0, "Am")
        val b = t(2, 116.0, "Am")
        assertTrue(SetPlanner.fit(a, b).good, "116 em 128 (−9,4%): normal")
        assertTrue(!SetPlanner.fit(a, t(3, 100.0, "Am")).bpmOk, "100 em 128: não")
    }

    @Test fun ramoSaiDoPontoDeViradaEVoltaMaisAdiante() {
        // Linha principal em 8A/9A ~126; o resto da biblioteca tem opções para mais energia.
        val main = listOf(t(1, 124.0, "Am"), t(2, 125.0, "Em"), t(3, 126.0, "Am"), t(4, 126.0, "Em"), t(5, 127.0, "Am"), t(6, 128.0, "Em"))
        val extra = listOf(t(10, 126.0, "Em", 5), t(11, 128.0, "Bm", 4), t(12, 130.0, "Em", 3), t(13, 131.0, "Am", 2), t(14, 100.0, "C#m", 5))
        val v = view(main + extra, mapOf(1 to main.map { it.id }))
        val (ids, back) = SetBuilder.branch(v, main.map { it.id }, from = 2, count = 3, shape = SetShape.RISING)
        assertEquals(3, ids.size)
        assertTrue(ids.none { it in main.map { m -> m.id } }, "ramo só com faixas fora da linha: $ids")
        assertTrue(14 !in ids, "a de 100 BPM e tom longe fica de fora")
        val line = listOf(2) + ids + listOfNotNull(back)
        val bpms = ids.map { v.track(it)!!.bpm }
        assertTrue(bpms.zipWithNext().none { (x, y) -> y < x - 1.0 }, "mais energia: $bpms")
        val backId = assertNotNull(back)
        assertTrue(main.map { it.id }.indexOf(backId) >= 3, "volta mais adiante: $backId")
        assertTrue(line.zipWithNext().all { (x, y) -> SetPlanner.fit(v.track(x)!!, v.track(y)!!).good }, "$line")
        val (_, none) = SetBuilder.branch(v, main.map { it.id }, from = 2, count = 2, shape = SetShape.FLOW, rejoin = false)
        assertEquals(null, none)
    }

    @Test fun ramoNaoCaiNaArmadilhaDoPassoAPasso() {
        // Passo a passo puro vai para a Dm (nota alta, vizinha da Am) e daí só chega na Em com troca fora do tom.
        val main = listOf(t(1, 124.0, "Am"), t(2, 124.0, "Am"), t(3, 125.0, "Em"))
        val extra = listOf(t(10, 124.0, "Dm", 5), t(11, 124.0, "Em"), t(12, 125.0, "Em"))
        val v = view(main + extra, mapOf(1 to main.map { it.id }))
        val (ids, back) = SetBuilder.branch(v, main.map { it.id }, from = 1, count = 2, shape = SetShape.FLOW)
        val line = listOf(1) + ids + listOfNotNull(assertNotNull(back))
        assertTrue(line.zipWithNext().all { (x, y) -> SetPlanner.fit(v.track(x)!!, v.track(y)!!).keyOk }, "todas as trocas no tom: $line")
    }

    @Test fun ramosGuardadosESoOsVivos() {
        val f = Files.createTempDirectory("b").resolve("branches.properties").toFile()
        val list = listOf(os.deckbridge.manager.mix.SetBranch(20, 1, 2, 5), os.deckbridge.manager.mix.SetBranch(21, 1, 3, null))
        os.deckbridge.manager.mix.SetBranches.save(f, list)
        assertEquals(list, os.deckbridge.manager.mix.SetBranches.load(f))
        val tracks = (1..12).map { t(it, 126.0, "Am") }
        val v = view(tracks, mapOf(1 to listOf(1, 2, 3, 4, 5), 20 to listOf(10, 11)))
        // O ramo 21 não tem playlist: some.
        assertEquals(listOf(list[0]), os.deckbridge.manager.mix.SetBranches.alive(v, list))
        assertEquals(listOf(2, 10, 11, 5), os.deckbridge.manager.mix.SetBranches.line(v, list[0]))
        assertEquals('B', os.deckbridge.manager.mix.SetBranches.letter(0))
    }
}
