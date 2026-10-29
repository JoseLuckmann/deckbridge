package os.deckbridge.manager.anlz

import kotlin.math.abs
import os.deckbridge.logic.AnlzBeat
import os.deckbridge.logic.PhraseSpan

/**
 * Os pontos de interesse de uma faixa como cues, pela análise de frases do app (as mesmas frases do rekordbox: Intro, Up,
 * Chorus, Down, Outro): cada começo de seção vira cue de memória ("Intro", "Build", "Drop", "Break", "Drop 2"…,
 * "Outro"), mais o fill antes de um drop e a última batida 1 ("Fim"); os oito mais úteis para mixar viram hot cues A–H
 * em ordem de tempo, com cor por tipo. Tudo preso à batida 1 mais próxima.
 *
 * Os nomes "Intro", "Drop" e "Outro" são os que o "Pronto pro show?" procura.
 */
object CuePlanner {
    /** Um ponto de interesse: onde (ms), o tipo e o nome que vai no cue. */
    data class Point(val timeMs: Int, val kind: String, val name: String)

    private const val GREEN = 0x3DDC6A
    private const val YELLOW = 0xFFD93B
    private const val RED = 0xFF3B3B
    private const val BLUE = 0x2EC5FF
    private const val PURPLE = 0xB44DFF

    /** Ordem de importância para os 8 hot cues. */
    private val PRIORITY = listOf("Intro", "Drop", "Break", "Drop 2", "Outro", "Build", "Break 2", "Drop 3", "Build 2", "Break 3", "Drop 4")

    private fun base(kind: String) = when (kind) {
        "Up" -> "Build"
        "Chorus" -> "Drop"
        "Down" -> "Break"
        "Outro" -> "Outro"
        else -> "Intro"
    }

    fun color(name: String): Int? = when (name.substringBefore(' ')) {
        "Intro" -> GREEN
        "Build" -> YELLOW
        "Drop" -> RED
        "Break" -> BLUE
        "Outro" -> PURPLE
        else -> null
    }

    /** Os pontos de interesse a partir das frases e da grade (em ordem de tempo). */
    fun points(phrases: List<PhraseSpan>, beats: List<AnlzBeat>, durationMs: Int): List<Point> {
        if (phrases.isEmpty()) return emptyList()
        val downbeats = beats.filter { it.beatInBar == 1 }.map { it.timeMs }
        fun snap(ms: Int) = downbeats.minByOrNull { abs(it - ms) }?.takeIf { abs(it - ms) < 2_500 } ?: ms
        // Frases seguidas do mesmo tipo são uma seção só.
        val sections = ArrayList<PhraseSpan>()
        for (p in phrases.sortedBy { it.startMs }) {
            val last = sections.lastOrNull()
            if (last != null && base(last.kind) == base(p.kind)) sections[sections.lastIndex] = last.copy(endMs = p.endMs, fillMs = p.fillMs ?: last.fillMs)
            else sections += p
        }
        val count = HashMap<String, Int>()
        val out = ArrayList<Point>()
        for ((i, s) in sections.withIndex()) {
            val b = base(s.kind)
            // Só uma Intro e um Outro; os outros contam (Drop, Drop 2…).
            if ((b == "Intro" || b == "Outro") && count.containsKey(b)) continue
            val n = (count[b] ?: 0) + 1
            count[b] = n
            val name = if (n == 1) b else "$b $n"
            // A Intro na primeira batida 1 (o começo do arquivo pode ser silêncio).
            val at = if (i == 0) downbeats.firstOrNull()?.takeIf { it < 15_000 } ?: s.startMs else snap(s.startMs)
            out += Point(at, s.kind, name)
            // O fill antes de um drop: o compasso em que o bumbo sai, ponto clássico para soltar o loop ou o efeito.
            val next = sections.getOrNull(i + 1)
            if (s.fillMs != null && next != null && base(next.kind) == "Drop") out += Point(snap(s.fillMs), "Fill", "Fill")
        }
        downbeats.lastOrNull { it < durationMs - 500 }?.let { if (out.none { p -> abs(p.timeMs - it) < 4_000 }) out += Point(it, "End", "Fim") }
        return out.filter { it.timeMs in 0 until durationMs.coerceAtLeast(1) }.distinctBy { it.timeMs }.sortedBy { it.timeMs }
    }

    /** Os cues: cada ponto como memória e os oito mais úteis como hot cues A–H, em ordem de tempo. */
    fun cues(points: List<Point>): List<Cue> {
        val hot = points.filter { it.name in PRIORITY }.sortedBy { PRIORITY.indexOf(it.name) }.take(8).sortedBy { it.timeMs }
        val hotCues = hot.mapIndexed { n, p -> Cue(n + 1, p.timeMs, comment = p.name, rgb = color(p.name)) }
        val memory = points.map { p -> Cue(0, p.timeMs, comment = p.name) }
        return hotCues + memory
    }

    fun cues(phrases: List<PhraseSpan>, beats: List<AnlzBeat>, durationMs: Int): List<Cue> = cues(points(phrases, beats, durationMs))

    /**
     * Junta os cues automáticos [auto] aos que a faixa já tem ([existing]) sem tocar em nenhum: memórias novas só onde não
     * há cue a menos de [nearMs]; hot cues novos só nas letras vagas e onde não há outro perto, os mais importantes
     * primeiro.
     */
    fun merge(existing: List<Cue>, auto: List<Cue>, nearMs: Int = 500): List<Cue> {
        val out = existing.toMutableList()
        fun near(ms: Int, hot: Boolean) = out.any { (it.isHot == hot) && abs(it.timeMs - ms) < nearMs }
        for (c in auto.filter { !it.isHot }) if (!near(c.timeMs, hot = false)) out += c
        val free = (1..8).filter { slot -> existing.none { it.hot == slot } }.toMutableList()
        for (c in auto.filter { it.isHot }.sortedBy { PRIORITY.indexOf(it.comment).let { i -> if (i < 0) 99 else i } }) {
            if (free.isEmpty()) break
            if (near(c.timeMs, hot = true)) continue
            out += c.copy(hot = free.removeAt(0))
        }
        return out
    }
}
