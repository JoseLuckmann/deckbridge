package os.deckbridge.manager

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import os.deckbridge.logic.analyzeTrack
import os.deckbridge.manager.importer.AudioIo

/**
 * Onde a grade do analisador cai em relação ao bumbo, em músicas de verdade (fora do repositório):
 * `GRID_CHECK=<pasta>[:<pasta>…]` com WAVs. Por faixa, a mediana (e a dispersão) do desvio entre a batida da grade e
 * (1) o início do clique do bumbo (1–5 kHz, 25% do pico, a regra declarada do analisador) e (2) o início do grave
 * (< 150 Hz, 50% do pico). Positivo = o bumbo vem depois da grade. Só imprime.
 */
class GridPhaseCheckTest {
    private fun onePoleLow(x: FloatArray, rate: Int, hz: Double): FloatArray {
        val a = (1 - exp(-2 * PI * hz / rate)).toFloat()
        var y = 0f
        return FloatArray(x.size) { y += a * (x[it] - y); y }
    }

    private fun smoothAbs(x: FloatArray, width: Int): FloatArray {
        val out = FloatArray(x.size)
        var acc = 0.0
        for (i in x.indices) {
            acc += abs(x[i])
            if (i >= width) acc -= abs(x[i - width])
            out[i] = (acc / width).toFloat()
        }
        return out
    }

    /** Do pico perto da batida, volta até o envelope cair abaixo de [frac] do pico: o início do ataque. */
    private fun attack(env: FloatArray, rate: Int, ms: Double, frac: Float): Double? {
        val c = (ms * rate / 1000).toInt()
        val from = c - rate * 40 / 1000
        val to = c + rate * 70 / 1000
        if (from < 0 || to >= env.size) return null
        var pk = from
        for (i in from..to) if (env[i] > env[pk]) pk = i
        if (env[pk] <= 1e-5f) return null
        var i = pk
        while (i > from && env[i] > frac * env[pk]) i--
        if (i == from) return null
        return i * 1000.0 / rate - ms
    }

    /** Início da subida: do pico, volta até [frac] da subida acima do mínimo antes dele. */
    private fun rise(env: FloatArray, rate: Int, ms: Double, frac: Float): Double? {
        val c = (ms * rate / 1000).toInt()
        val from = c - rate * 40 / 1000
        val to = c + rate * 70 / 1000
        if (from < 0 || to >= env.size) return null
        var pk = from
        for (i in from..to) if (env[i] > env[pk]) pk = i
        var mn = env[pk]
        for (i in from..pk) mn = minOf(mn, env[i])
        if (env[pk] - mn <= 1e-5f) return null
        var i = pk
        while (i > from && env[i] > mn + frac * (env[pk] - mn)) i--
        if (i == from) return null
        return i * 1000.0 / rate - ms
    }

    private fun stats(v: List<Double>): String {
        if (v.size < 8) return "—"
        val s = v.sorted()
        return "%+6.1f (p25 %+6.1f p75 %+6.1f)".format(s[s.size / 2], s[s.size / 4], s[s.size * 3 / 4])
    }

    @Test fun measure() {
        val dirs = System.getenv("GRID_CHECK")?.split(':')?.map(::File)?.filter { it.isDirectory }.orEmpty()
        assumeTrue("sem GRID_CHECK", dirs.isNotEmpty())
        for (f in dirs.flatMap { d -> d.listFiles { x -> x.extension == "wav" }!!.sortedBy { it.name } }) {
            val mono = AudioIo.mono(f)
            val rate = mono.rate
            val x = mono.samples
            val read = analyzeTrack(x, rate)
            // Diagnóstico da passada de alinhamento sobre a grade alinhada (o que ela veria de novo).
            os.deckbridge.logic.alignGridToClick(x, rate, read.beats, read.bpm) { println("   alinhar ${f.nameWithoutExtension.take(30)}: $it") }
            val beats = read.beats
            if (beats.size < 32) { println("${f.name}: sem grade"); continue }
            // Clique: 1–5 kHz por diferença de passa-baixas, envelope de 1 ms.
            val l5 = onePoleLow(x, rate, 5000.0)
            val l1 = onePoleLow(x, rate, 1000.0)
            val click = smoothAbs(FloatArray(x.size) { l5[it] - l1[it] }, rate / 1000)
            val low = smoothAbs(onePoleLow(onePoleLow(x, rate, 150.0), rate, 150.0), rate * 3 / 1000)
            val c = beats.mapNotNull { attack(click, rate, it.timeMs.toDouble(), 0.25f) }
            val g = beats.mapNotNull { attack(low, rate, it.timeMs.toDouble(), 0.5f) }
            val low1 = smoothAbs(onePoleLow(onePoleLow(x, rate, 150.0), rate, 150.0), rate / 1000)
            val g10 = beats.mapNotNull { rise(low1, rate, it.timeMs.toDouble(), 0.1f) }
            val c10 = beats.mapNotNull { rise(click, rate, it.timeMs.toDouble(), 0.1f) }
            println("   %-43s subida 10%%: clique %s  grave %s".format("", stats(c10), stats(g10)))
            println("%-46s bpm %7.3f  clique %s  grave %s  n=%d".format(f.nameWithoutExtension.take(46), read.bpm, stats(c), stats(g), beats.size))
        }
    }
}
