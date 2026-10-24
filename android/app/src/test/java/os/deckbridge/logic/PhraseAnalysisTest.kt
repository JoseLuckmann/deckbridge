package os.deckbridge.logic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Faixa sintética de 128 BPM com estrutura conhecida (em compassos): Intro 16 (bumbo e chimbal, sem baixo), Up 8 (entra
 * metade do baixo), Chorus 32 (bumbo, baixo e chimbal), Down 16 (só um acorde, sem bumbo nem baixo), Chorus 32,
 * Outro 16 (bumbo e chimbal). As frases do analisador têm de sair nessa ordem e no compasso certo.
 */
class PhraseAnalysisTest {
    private val rate = 22050
    private val bpm = 128.0

    private fun track(): FloatArray {
        val layout = listOf(16 to "intro", 8 to "up", 32 to "chorus", 16 to "down", 32 to "chorus", 16 to "outro")
        val barSec = 240.0 / bpm
        val beatSec = 60.0 / bpm
        val bars = layout.sumOf { it.first }
        val out = FloatArray((bars * barSec * rate).toInt() + rate)
        var noise = 12345
        var bar = 0
        for ((length, part) in layout) {
            for (b in 0 until length) {
                val barStart = (bar + b) * barSec
                val kick = part != "down"
                val bass = when (part) {
                    "chorus" -> 1.0
                    "up" -> 0.5
                    else -> 0.0
                }
                val hats = part != "down"
                val pad = part == "down" || part == "chorus"
                val from = (barStart * rate).toInt()
                val to = ((barStart + barSec) * rate).toInt().coerceAtMost(out.size)
                for (i in from until to) {
                    val t = i.toDouble() / rate
                    val inBeat = (t - barStart) % beatSec
                    var x = 0.0
                    if (kick) x += 0.7 * exp(-inBeat * 30.0) * sin(2 * PI * (50.0 + 80.0 * exp(-inBeat * 40.0)) * inBeat)
                    // Baixo contínuo (como um baixo de house): é ele que sobe o grave do Up e do Chorus.
                    if (bass > 0.0) x += bass * 0.35 * sin(2 * PI * 55.0 * t)
                    if (hats) {
                        noise = noise * 1103515245 + 12345
                        val n = ((noise ushr 16) and 0x7FFF) / 16384.0 - 1.0
                        val off = (inBeat - beatSec / 2).let { if (it < 0) it + beatSec else it }
                        x += 0.12 * n * exp(-off * 60.0)
                    }
                    if (pad) x += 0.08 * (sin(2 * PI * 220.0 * t) + sin(2 * PI * 277.2 * t) + sin(2 * PI * 329.6 * t))
                    out[i] = x.toFloat()
                }
            }
            bar += length
        }
        return out
    }

    @Test
    fun knownStructureComesOutAsPhrases() {
        val samples = track()
        val read = analyzeTrack(samples, rate)
        assertEquals(bpm, read.bpm, 0.5)
        val barSec = 240.0 / read.bpm
        val kinds = read.phrases.map { it.kind }
        // Frases seguidas com o mesmo rótulo contam como uma seção.
        val sections = kinds.fold(emptyList<String>()) { acc, kind -> if (acc.lastOrNull() == kind) acc else acc + kind }
        val shown = read.phrases.joinToString(" ") { "%s@%.1f".format(it.kind, it.startMs / 1000.0 / barSec) }
        assertEquals(shown, listOf("Intro", "Up", "Chorus", "Down", "Chorus", "Outro"), sections)
        fun startOf(kind: String, nth: Int = 0): Double {
            val starts = read.phrases.filterIndexed { i, p -> p.kind == kind && (i == 0 || read.phrases[i - 1].kind != kind) }
            return starts[nth].startMs / 1000.0
        }
        val expected = listOf("Up" to 16, "Chorus" to 24, "Down" to 56, "Outro" to 104)
        for ((kind, bar) in expected) {
            val delta = abs(startOf(kind) - bar * barSec) / barSec
            assertTrue("$kind em ${startOf(kind)} s, esperado compasso $bar ($delta compassos)", delta <= 1.0)
        }
        assertTrue("segundo Chorus", abs(startOf("Chorus", 1) - 72 * barSec) / barSec <= 1.0)
        // Os pontos de estrutura vêm das frases.
        assertEquals(startOf("Chorus"), read.structure.dropSec, 1e-3)
        assertEquals(startOf("Down"), read.structure.breakSec, 1e-3)
        assertEquals(startOf("Outro"), read.structure.outroSec, 1e-3)
        // Cada frase tem 4 a 32 compassos e começa num compasso da grade.
        for (phrase in read.phrases.dropLast(1)) {
            val length = (phrase.endMs - phrase.startMs) / 1000.0 / barSec
            assertTrue("frase de $length compassos", length in 3.5..32.5)
        }
    }
}
