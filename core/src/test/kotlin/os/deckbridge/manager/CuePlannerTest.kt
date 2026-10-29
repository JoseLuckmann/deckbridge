package os.deckbridge.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import os.deckbridge.logic.AnlzBeat
import os.deckbridge.logic.PhraseSpan
import os.deckbridge.manager.anlz.Cue
import os.deckbridge.manager.anlz.CuePlanner

/** Os pontos de interesse viram cues: memória em cada seção, os oito mais úteis como hot cues, sem pisar nos do DJ. */
class CuePlannerTest {
    // 120 BPM: batida a cada 500 ms, batida 1 a cada 2 s começando em 100 ms (o arquivo abre com um pouco de silêncio).
    private val beats = (0 until 480).map { AnlzBeat(it % 4 + 1, 120.0, 100 + it * 500) }

    private val phrases = listOf(
        PhraseSpan(0, 32_000, "Intro", "Intro"),
        PhraseSpan(32_000, 48_000, "Up", "Up"),
        PhraseSpan(48_000, 80_000, "Chorus", "Chorus"),
        PhraseSpan(80_000, 96_000, "Chorus", "Chorus"), // mesma seção: o drop continua
        PhraseSpan(96_000, 128_000, "Down", "Down", fillMs = 126_000),
        PhraseSpan(128_000, 192_000, "Chorus", "Chorus"),
        PhraseSpan(192_000, 224_000, "Outro", "Outro"),
    )

    @Test fun `cada secao vira memoria e os pontos chave viram hot cues em ordem`() {
        val pts = CuePlanner.points(phrases, beats, 230_000)
        assertEquals(listOf("Intro", "Build", "Drop", "Break", "Fill", "Drop 2", "Outro", "Fim"), pts.map { it.name })
        // A Intro na primeira batida 1 (não no silêncio do começo); tudo na batida 1.
        assertEquals(100, pts.first().timeMs)
        assertTrue(pts.all { (it.timeMs - 100) % 2000 == 0 }, pts.toString())
        val cues = CuePlanner.cues(pts)
        val hot = cues.filter { it.isHot }
        assertEquals(listOf("Intro", "Build", "Drop", "Break", "Drop 2", "Outro"), hot.map { it.comment })
        assertEquals((1..6).toList(), hot.map { it.hot }, "A, B, C… em ordem de tempo")
        assertEquals(0xFF3B3B, hot.first { it.comment == "Drop" }.rgb)
        assertEquals(pts.size, cues.count { !it.isHot }, "uma memória por ponto")
        // Os nomes que o "Pronto pro show?" procura.
        assertTrue(listOf("Intro", "Drop", "Outro").all { n -> cues.any { it.comment == n } })
    }

    @Test fun `juntar nao mexe nos cues do dj`() {
        val mine = listOf(Cue(1, 48_100, comment = "meu drop"), Cue(3, 150_000, comment = "vocal"), Cue(0, 10_000, comment = "meu"))
        val auto = CuePlanner.cues(CuePlanner.points(phrases, beats, 230_000))
        val merged = CuePlanner.merge(mine, auto)
        assertTrue(merged.containsAll(mine), "os do DJ ficam")
        // A e C eram do DJ: os automáticos vão para as letras vagas, e o Drop (perto do drop dele) não entra de novo.
        val hot = merged.filter { it.isHot }
        assertEquals(hot.size, hot.map { it.hot }.distinct().size, "uma faixa por letra")
        assertTrue(hot.none { it.comment == "Drop" }, "já há um hot cue no drop")
        assertTrue(hot.any { it.comment == "Intro" } && hot.size <= 8)
        // Memórias novas só onde não há outra perto.
        assertEquals(1, merged.count { !it.isHot && kotlin.math.abs(it.timeMs - 10_000) < 500 })
    }
}
