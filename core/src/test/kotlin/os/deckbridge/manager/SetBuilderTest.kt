package os.deckbridge.manager

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(Triple(1, 5, SetIssue.Kind.TEMPO) in kinds)
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
